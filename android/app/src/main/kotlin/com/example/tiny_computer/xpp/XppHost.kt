package com.example.tiny_computer.xpp

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.LaunchLog
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.DisplayFit
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * X++ native X server (libxpp.so, dari Winlator Ludashi) sebagai pilihan "Display server" desktop.
 *
 * Server X11 ini hidup di dalam proses aplikasi dan mendengarkan di `/tmp/.X11-unix/X4` kontainer (folder tmp kontainer di sisi
 * Android), jadi sesi X11 Debian cukup memakai DISPLAY=:4 tanpa Xwayland dan tanpa compositor Wayland. Server dibuat sekali per
 * desktop dan hidup lebih lama dari layar desktop ([com.example.tiny_computer.display.DisplayActivity]): layar yang ditutup lalu
 * dibuka lagi hanya memasang Surface baru, klien X di dalam kontainer tidak terputus.
 *
 * Mode tampilan ([Cfg.xppBackend]): Vulkan (swapchain Vulkan, VulkanRendererContext Winlator tanpa frame generation), EGL,
 * DisplayX, dan Old Display (= backend EGL, seperti Winlator). Mode tampilan hanya mengatur cara layar digambar; tidak
 * mengubah batasan DRI3 di bawah.
 *
 * Batasan: DRI3/Present milik server ini memakai transport AHardwareBuffer khas Winlator, bukan dmabuf. Mesa biasa di Debian
 * tidak memakainya (jatuh ke software/XRender), jadi Vulkan/Turnip lewat X11 tidak berfungsi di mode ini.
 *
 * RENDER (A1/A8/RGB24/ARGB32, Composite, FillRectangles, glyph, trapezoid), Composite (overlay = root), Damage, XFixes dan SHAPE
 * diimplementasikan di server; compositor xfwm4 berjalan di atas server ini.
 */
object XppHost {
    private const val TAG = "XppHost"
    private val lock = Any()

    private var handle = 0L
    private var width = 0
    private var height = 0
    private var backend = NativeX11.BACKEND_EGL
    private var presentMode = 2
    private var bufferCount = 0
    private var memLimitMb = 0
    private var trueDisplayX = false
    /** Batas FPS khusus sesi Shortcut yang menyalakan server ini; null = [Cfg.fpsLimit]. */
    @Volatile private var fpsLimitOverride: Int? = null
    private var boundSocket: String? = null
    private var surface: Surface? = null
    private var refreshHz = 60f

    /** Posisi kursor terakhir yang dikirim ke server (piksel scene) dan sisa pecahan 1/256 piksel gerak relatif. */
    private var curX = 0
    private var curY = 0
    private var remX = 0
    private var remY = 0
    private var scrollRemV = 0
    private var scrollRemH = 0

    /** True selama layar desktop sedang menampilkan server ini: masukan sentuh/keyboard diarahkan ke sini, bukan ke compositor Wayland. */
    @Volatile var displayAttached = false

    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = synchronized(lock) { handle != 0L }

    /** Ukuran layar X yang sedang berjalan, atau null bila server belum dibuat. */
    val size: Pair<Int, Int>? get() = synchronized(lock) { if (handle != 0L) width to height else null }

    /** Setelan [Cfg.presentMode] (0 auto, 1 FIFO, 2 FIFO relaxed, 3 Mailbox, 4 Immediate) -> VkPresentModeKHR. Auto = FIFO. */
    private fun vkPresentMode(setting: Int): Int = when (setting) {
        2 -> 3
        3 -> 1
        4 -> 0
        else -> 2
    }

    private fun vkPresentName(mode: Int): String = when (mode) {
        0 -> "IMMEDIATE"
        1 -> "MAILBOX"
        3 -> "FIFO relaxed"
        else -> "FIFO"
    }

    fun socketFile(): File = File(File(TinyEnv.runtimeDir(), ".X11-unix"), "X4")

    /**
     * Membuat dan menyalakan server bila belum ada; dibuat ulang bila ukuran, backend atau kontainer berubah. Hanya dipanggil
     * saat desktop belum berjalan ([com.example.tiny_computer.core.Launcher.launchGuiBackend]), jadi tidak ada klien yang terputus.
     * Mengembalikan false (dan mengisi [lastError]) bila gagal.
     */
    fun ensureStarted(
        ctx: Context, w: Int, h: Int, backendOverride: Int? = null, presentOverride: Int? = null, fpsOverride: Int? = null,
        bufferOverride: Int? = null, memLimitOverride: Int? = null, displayXPlusOverride: Boolean? = null,
    ): Boolean = synchronized(lock) {
        val sock = socketFile()
        // batas FPS sesi ini: pilihan khusus Shortcut atau Display > FPS; null (Open desktop) mengembalikan ke pengaturan utama
        fpsLimitOverride = fpsOverride
        // mode tampilan: pilihan khusus sebuah Shortcut ([backendOverride]) atau pengaturan desktop utama
        val backendKey = backendOverride ?: Cfg.xppBackend
        // Vulkan -> backend 0, DisplayX -> 2; EGL dan Old Display -> EGL (Old Display pada X++ = EGL native, seperti Winlator).
        val wantBackend = when (backendKey) {
            Cfg.XPP_VULKAN -> NativeX11.BACKEND_VULKAN
            Cfg.XPP_DISPLAYX -> NativeX11.BACKEND_DISPLAYX
            else -> NativeX11.BACKEND_EGL
        }
        // Present mode memakai setelan Display > VSync / present mode ([Cfg.presentMode]) yang sama dengan compositor Wayland,
        // dipetakan ke VkPresentModeKHR: 0 IMMEDIATE, 1 MAILBOX, 2 FIFO, 3 FIFO_RELAXED. Hanya dipakai backend Vulkan.
        val wantPresent = if (wantBackend == NativeX11.BACKEND_VULKAN) vkPresentMode(presentOverride ?: Cfg.presentMode) else 2
        // Buffering swapchain (jumlah gambar) dan batas memori renderer: sama dengan Display > Buffering dan Batas memori > Compositor
        // di compositor Wayland; hanya backend Vulkan yang punya swapchain dan anggaran memori. Dikirim lewat bit flags nativeCreate.
        val wantBuffer = if (wantBackend == NativeX11.BACKEND_VULKAN) (bufferOverride ?: Cfg.swapBuffers).coerceIn(0, 15) else 0
        val wantMem = if (wantBackend == NativeX11.BACKEND_VULKAN) (memLimitOverride ?: Cfg.compositorMemMb).coerceIn(0, 32767) else 0
        // DisplayX+ hanya bermakna pada backend DisplayX (bridge soket dibuat di sisi native hanya untuk kombinasi itu).
        val wantTrueDisplayX = wantBackend == NativeX11.BACKEND_DISPLAYX && (displayXPlusOverride ?: Cfg.xppDisplayXPlus)
        if (handle != 0L && width == w && height == h && backend == wantBackend && presentMode == wantPresent &&
            bufferCount == wantBuffer && memLimitMb == wantMem && trueDisplayX == wantTrueDisplayX &&
            boundSocket == sock.absolutePath) {
            return true
        }
        stopLocked()
        lastError = null
        try {
            sock.parentFile?.mkdirs()
            // Jejak protokol (diagnosis): aktif selama berkas /tmp/.xpp-trace ada di kontainer; hasil di /tmp/xpp-trace.log dan Download/Wayland-logs
            runCatching { android.system.Os.setenv("XPP_TRACE_DIR", TinyEnv.runtimeDir().toString(), true) }
            // salinan jejak ikut ditulis ke Download/Wayland-logs (xpp-trace-<waktu>.log), folder yang sama dengan log shortcut/container
            runCatching {
                File(LaunchLog.DIR).mkdirs()
                android.system.Os.setenv("XPP_TRACE_LOG_DIR", LaunchLog.DIR, true)
            }
            // server tidak pernah menghapus soket yang masih ada; sisa soket dari proses sebelumnya dibuang di sini
            if (sock.exists()) sock.delete()
            var flags = NativeX11.FLAG_NO_MIT_SHM or NativeX11.FLAG_NO_DRI3 or
                if (wantBackend == NativeX11.BACKEND_DISPLAYX) NativeX11.FLAG_PERFORMANCE or NativeX11.FLAG_PRESENT_AT_REFRESH else 0
            if (wantTrueDisplayX) flags = flags or NativeX11.FLAG_TRUE_DISPLAYX
            flags = flags or (wantBuffer shl 12) or (wantMem shl 16) // bit 12-15 buffering, bit 16-30 batas memori (MiB)
            val h0 = NativeX11.nativeCreate(
                w, h, false, "", "", ctx.applicationInfo.nativeLibraryDir, wantPresent, false, wantBackend, flags,
            )
            if (h0 == 0L) throw IllegalStateException("nativeCreate returned 0")
            handle = h0
            width = w
            height = h
            backend = wantBackend
            presentMode = wantPresent
            bufferCount = wantBuffer
            memLimitMb = wantMem
            trueDisplayX = wantTrueDisplayX
            // jalur shm tidak dipakai (MIT-SHM tidak diiklankan), tetap diberikan karena parameter wajib
            NativeX11.nativeStart(h0, sock.absolutePath, File(sock.parentFile, ".xpp-shm").absolutePath)
            boundSocket = sock.absolutePath
            loadIccProfile(ctx, h0)
            curX = w / 2
            curY = h / 2
            applyOptionsLocked(ctx)
            surface?.let { NativeX11.nativeSurface(h0, it) }
            val backendName = when (backendKey) {
                Cfg.XPP_VULKAN -> "Vulkan (${vkPresentName(wantPresent)})"
                Cfg.XPP_DISPLAYX -> if (wantTrueDisplayX) "DisplayX+" else "DisplayX"
                Cfg.XPP_OLD_DISPLAY -> "Old Display (EGL)"
                else -> "EGL"
            }
            Log.i(TAG, "X++ aktif: ${w}x$h backend=$backendName socket=${sock.path}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "X++ gagal dinyalakan", t)
            lastError = t.message ?: t.javaClass.simpleName
            stopLocked()
            false
        }
    }

    /** Surface layar desktop; null saat layar ditutup. Disimpan supaya tetap terpasang bila server baru dibuat sesudahnya. */
    fun setSurface(s: Surface?, ctx: Context? = null) = synchronized(lock) {
        surface = s
        if (handle != 0L) {
            runCatching {
                NativeX11.nativeSurface(handle, s)
                if (s != null && ctx != null) applyOptionsLocked(ctx)
            }.onFailure { Log.e(TAG, "surface", it) }
        }
    }

    /** Opsi tampilan: kursor X digambar server, FPS dan laju refresh panel, filter dari pengaturan Upscaler. */
    fun applyOptions(ctx: Context) = synchronized(lock) { if (handle != 0L) applyOptionsLocked(ctx) }

    private fun applyOptionsLocked(ctx: Context) {
        @Suppress("DEPRECATION")
        val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
        refreshHz = if (hz >= 1f) hz else 60f
        NativeX11.nativeRefreshRates(handle, intArrayOf(refreshHz.roundToInt()))
        // Mode tampilan menu sesi ([Cfg.displayFit]): Fit = fullscreen mati; Stretch = fullscreen (isi layar); Soft Stretch =
        // fullscreen + stretch 1 (shader pelebaran horizontal Vulkan/EGL, sama dengan Winlator Ludashi). DisplayX tidak punya Soft Stretch.
        val fit = Cfg.displayFit
        val soft = fit == Cfg.FIT_SOFT && backend != NativeX11.BACKEND_DISPLAYX
        // DisplayX menggambar Stretch biasa; beri tahu DisplayFit agar sentuhan/kursor tidak memakai kurva warp.
        DisplayFit.softSupported = backend != NativeX11.BACKEND_DISPLAYX
        NativeX11.nativeOptions(handle, fit != Cfg.FIT_FIT, true, fpsLimitOverride ?: Cfg.fpsLimit, refreshHz)
        // Cfg.upscaler: 2 = Nearest. Filter lain memakai bilinear (0 = linear, 1 = nearest di semua backend); efek lain milik
        // compositor Wayland tidak ada di server ini. Parameter stretch: 0 = Fit/Stretch biasa, 1 = Soft Stretch.
        // Efek layar dari Cfg (menu sesi > Effect): efek HDR (postFX 3) dan colour grade (brightness, contrast, gamma, saturation)
        // berjalan di renderer Vulkan dan EGL/Old Display. DisplayX menyerahkan frame langsung ke SurfaceControl tanpa pass
        // shader, jadi efeknya dinetralkan di sana. Satuan dikonversi dari slider ke satuan shader (netral 0, 1, 1, 1).
        val fx = backend != NativeX11.BACKEND_DISPLAYX
        val postFx = if (fx && Cfg.hdrEffect) 3 else 0
        val brightness = if (fx) (Cfg.fxBrightness / 100f).coerceIn(-1f, 1f) else 0f
        val contrast = if (fx) (1f + Cfg.fxContrast / 100f).coerceIn(0f, 2f) else 1f
        val gamma = if (fx) (Cfg.fxGamma / 100f).coerceIn(0.1f, 5f) else 1f
        val saturation = if (fx) (Cfg.fxSaturation / 100f).coerceIn(0f, 2f) else 1f
        NativeX11.nativeVisualOptions(
            handle, if (Cfg.upscaler == 2) 1 else 0, postFx, if (soft) 1 else 0, 0.5f, false, null, 2, -1, 70, refreshHz, 0,
            brightness, contrast, gamma, saturation,
        )
    }

    /** True bila server X++ hidup dengan backend DisplayX (tanpa pass shader: efek layar tidak berlaku). */
    val isDisplayX: Boolean get() = synchronized(lock) { handle != 0L && backend == NativeX11.BACKEND_DISPLAYX }

    /** Profil ICC layar: pakai `filesDir/display.icc` bila ada dan valid; selain itu server memakai sRGB bawaan. */
    private fun loadIccProfile(ctx: Context, h: Long) {
        val file = File(ctx.filesDir, "display.icc")
        if (!file.isFile || file.length() !in 132L..(1L shl 20)) return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        if (!NativeX11.nativeIccProfile(h, bytes)) Log.w(TAG, "display.icc bukan profil ICC yang valid, memakai sRGB bawaan")
    }

    /** Mematikan server dan menghapus soketnya. Aman dipanggil bila server tidak ada. */
    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        val h = handle
        handle = 0L
        DisplayFit.softSupported = true // sesi berikutnya (Xwayland/Wayland/X++ lain) mendukung Soft Stretch
        boundSocket = null
        remX = 0; remY = 0; scrollRemV = 0; scrollRemH = 0
        if (h != 0L) runCatching { NativeX11.nativeDestroy(h) }.onFailure { Log.e(TAG, "destroy", it) }
        runCatching { socketFile().takeIf { it.exists() }?.delete() }
    }

    // ------------------------------------------------------------------ Masukan

    /** Posisi kursor terakhir (piksel scene), untuk gerak relatif trackpad dan penempatan awal. */
    fun cursor(out: IntArray): Int {
        if (out.size < 2) return 0
        out[0] = curX
        out[1] = curY
        return 2
    }

    fun moveTo(x: Int, y: Int) = synchronized(lock) {
        if (handle == 0L) return@synchronized
        curX = x.coerceIn(0, max(width - 1, 0))
        curY = y.coerceIn(0, max(height - 1, 0))
        NativeX11.nativeInput(handle, NativeX11.INPUT_MOVE_TO, curX, curY)
    }

    /** [dx]/[dy] dalam 1/256 piksel scene (satuan gerak relatif compositor); sisanya dibawa ke panggilan berikutnya. */
    fun moveByFixed(dx: Int, dy: Int) = synchronized(lock) {
        if (handle == 0L) return@synchronized
        remX += dx
        remY += dy
        val ix = remX / 256
        val iy = remY / 256
        if (ix == 0 && iy == 0) return@synchronized
        remX -= ix * 256
        remY -= iy * 256
        curX = (curX + ix).coerceIn(0, max(width - 1, 0))
        curY = (curY + iy).coerceIn(0, max(height - 1, 0))
        NativeX11.nativeInput(handle, NativeX11.INPUT_MOVE_BY, ix, iy)
    }

    /** Tombol X11: 1 kiri, 2 tengah, 3 kanan, 4/5 gulir atas/bawah, 6/7 gulir kiri/kanan. */
    private fun xButton(button: Int, down: Boolean) {
        if (handle != 0L) NativeX11.nativeInput(handle, NativeX11.INPUT_BUTTON, button, if (down) 1 else 0)
    }

    /** Kode tombol evdev (BTN_LEFT 272, BTN_RIGHT 273, BTN_MIDDLE 274) -> tombol X11. Tombol samping tidak didukung server. */
    fun evdevButton(code: Int, down: Boolean) = synchronized(lock) {
        when (code) {
            272 -> xButton(1, down)
            273 -> xButton(3, down)
            274 -> xButton(2, down)
        }
    }

    /** [steps] takik roda: positif = gulir ke bawah (sama dengan compositor Wayland). */
    fun wheel(steps: Int) = synchronized(lock) {
        val n = min(kotlin.math.abs(steps), 20)
        val button = if (steps > 0) 5 else 4
        repeat(n) { xButton(button, true); xButton(button, false) }
    }

    /** Gulir halus: [axis] 0 vertikal, 1 horizontal; [value] dalam 1/120 takik, positif = bawah/kanan. Sisa pecahan dibawa. */
    fun smoothScroll(axis: Int, value: Int) = synchronized(lock) {
        if (handle == 0L) return@synchronized
        if (axis == 0) scrollRemV += value else scrollRemH += value
        val acc = if (axis == 0) scrollRemV else scrollRemH
        val notches = acc / 120
        if (notches == 0) return@synchronized
        if (axis == 0) scrollRemV -= notches * 120 else scrollRemH -= notches * 120
        val button = if (axis == 0) (if (notches > 0) 5 else 4) else (if (notches > 0) 7 else 6)
        repeat(min(kotlin.math.abs(notches), 20)) { xButton(button, true); xButton(button, false) }
    }

    /** Tombol keyboard: kode evdev Linux; keycode X11 = evdev + 8 (tabel XKeycode server). */
    fun key(evdev: Int, down: Boolean) = synchronized(lock) {
        if (handle == 0L || evdev !in 1..247) return@synchronized
        NativeX11.nativeInput(handle, NativeX11.INPUT_KEY, evdev + 8, if (down) 1 else 0)
    }
}
