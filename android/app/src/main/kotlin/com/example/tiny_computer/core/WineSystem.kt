package com.example.tiny_computer.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Wine bawaan rootfs (Hangover lewat apt, lihat [HangoverSetup]): deteksi, dukungan driver tampilan Wayland, dan
 * penulisan driver tampilan ke registri prefix `~/.wine`. Dipakai [ShortcutRunner], [ExeShortcuts], dan [HangoverGroup].
 */
object WineSystem {
    private const val TAG = "WineSystem"

    /** Prefix Wine. HOME guest (/home/tiny) ada di dalam rootfs kontainer, jadi `~/.wine` terlihat dari Android. */
    fun prefixDir() = File(TinyEnv.containerDir(), "home/tiny/.wine")

    fun prefixReady() = File(prefixDir(), "system.reg").isFile && File(prefixDir(), "drive_c/windows/system32").isDirectory

    /** Lokasi winewayland.so Wine sistem (path DALAM guest), dicoba berurutan. */
    private val WAYLAND_SO = listOf(
        "/usr/lib/wine/aarch64-unix/winewayland.so",
        "/usr/lib/aarch64-linux-gnu/wine/aarch64-unix/winewayland.so",
    )

    /** File/symlink ada di rootfs. Symlink absolut ke /etc/alternatives tidak ter-resolve dari sisi Android, jadi cek linknya juga. */
    private fun inRootfs(guestPath: String): Boolean {
        val f = File(TinyEnv.containerDir(), guestPath.removePrefix("/"))
        return f.exists() || runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
    }

    /** Wine bawaan rootfs (apt/Hangover): ada `/usr/bin/wine` atau folder `/usr/lib/wine`. */
    fun installed(): Boolean = inRootfs("/usr/bin/wine") || inRootfs("/usr/lib/wine")

    /** Path guest winewayland.so, atau null bila build Wine-nya tidak menyertakannya. */
    fun waylandSo(): String? = WAYLAND_SO.firstOrNull { inRootfs(it) }

    fun supportsWayland(): Boolean = waylandSo() != null

    /** Path winewayland.so untuk skrip shell (dipakai diagnosis [ShortcutRunner]). */
    fun waylandSoShellPath(): String = waylandSo() ?: WAYLAND_SO[0]

    /** Wayland dipilih pengguna DAN didukung Wine terpasang (belum memperhitungkan jalur grafis; lihat [displayWayland]). */
    fun waylandCapable(): Boolean = Cfg.wineDisplay != "x11" && supportsWayland()

    /**
     * Jalur X11 (Xwayland + Zink/Turnip) HARUS dipakai walau Wayland tersedia bila grafis Wine adalah OpenGL (mode WineD3D):
     * EGL platform Wayland di Mesa tidak bisa memuat Zink tanpa node DRM sungguhan, lalu diam-diam jatuh ke llvmpipe
     * (OpenGL software, berat; log: wined3d_guess_card ... GL_RENDERER "llvmpipe"). Lewat X11 jalur LIBGL_KOPPER_DRI2
     * (lihat Launcher.guestSpec) tetap memakai Zink di Turnip.
     * DXVK (Vulkan) sebaliknya HARUS lewat Wayland: winevulkan lewat Xwayland tanpa DRI3 macet dan layar hitam.
     * Mode yang dipakai = [d3dModeIsDxvk] (pilihan pengguna, atau isi registri prefix bila belum memilih).
     */
    fun x11Preferred(): Boolean = !d3dModeIsDxvk()

    /** Wine memakai winewayland.drv pada peluncuran ini: didukung, dipilih pengguna, dan jalur grafisnya Vulkan (DXVK). */
    fun displayWayland(): Boolean = waylandCapable() && !x11Preferred()

    /** Nilai registri `HKCU\Software\Wine\Drivers\Graphics`: daftar dicoba berurutan, jadi X11 menjadi cadangan. */
    fun displayDriverValue(): String = if (displayWayland()) "wayland,x11" else "x11"

    /**
     * Menjalankan [command] di shell kontainer dan menunggu kodenya lewat berkas status di `~/.local/share/tiny`
     * (terlihat dari Android di `$DATA_DIR/tiny`). Mengembalikan kode keluar, atau null bila shell mati / waktu habis.
     */
    private fun guestRun(ctx: Context, command: String, timeoutMs: Long): Int? {
        val status = File(TinyEnv.dataPath, "tiny/wine-setup.status")
        status.parentFile?.mkdirs()
        status.delete()
        TermSessions.forCurrent(ctx).writeLine(
            "rm -f ~/.local/share/tiny/wine-setup.status; ($command; echo \$? > ~/.local/share/tiny/wine-setup.status)",
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!(status.isFile && status.length() > 0)) {
            if (TermSessions.peek()?.exitCode != null) return null
            if (SystemClock.elapsedRealtime() > deadline) { Log.w(TAG, "waktu habis: $command"); return null }
            Thread.sleep(500)
        }
        val code = runCatching { status.readText().trim().toInt() }.getOrDefault(-1)
        status.delete()
        return code
    }

    // ------------------------------------------------------------------ mode Direct3D (WineD3D / DXVK)

    /** DLL yang digantikan DXVK (sama dengan perintah cepat "Enable/Disable DXVK" tiny_computer original). */
    private val D3D_DLLS = listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi")

    /**
     * Perintah `wine reg add` yang menulis DllOverrides untuk [D3D_DLLS]: `native` (DXVK, berkas DXVK di system32/syswow64
     * prefix) atau `builtin` (WineD3D). Bentuk perintahnya sama dengan logika original, dipisah `;` agar muat satu baris.
     */
    fun d3dRegCommands(dxvk: Boolean): String {
        val letter = if (dxvk) "n" else "b"
        val word = if (dxvk) "native" else "builtin"
        val overrides = D3D_DLLS.joinToString(",") { "$it=$letter" }
        return D3D_DLLS.joinToString("; ") {
            "WINEDLLOVERRIDES=\"$overrides\" wine reg add 'HKEY_CURRENT_USER\\Software\\Wine\\DllOverrides' /v $it /d $word /f >/dev/null 2>&1"
        }
    }

    /**
     * Mode yang tertulis di registri prefix (`user.reg`, DllOverrides d3d11): true = native (DXVK), false = builtin
     * (WineD3D / tanpa override), null bila prefix belum ada.
     */
    fun d3dRegistryDxvk(): Boolean? {
        val f = File(prefixDir(), "user.reg")
        if (!f.isFile) return null
        return runCatching {
            f.useLines { lines ->
                var inSection = false
                for (l in lines) {
                    if (l.startsWith("[")) { inSection = l.startsWith("[Software\\\\Wine\\\\DllOverrides]"); continue }
                    if (inSection && l.startsWith("\"d3d11\"=")) return@useLines l.substringAfter('=').trim().startsWith("\"native")
                }
                false
            }
        }.getOrNull()
    }

    /** Mode untuk UI: pilihan pengguna ([Cfg.d3dMode]) bila ada, selain itu isi registri prefix. */
    fun d3dModeIsDxvk(): Boolean = when (Cfg.d3dMode) {
        "dxvk" -> true
        "wined3d" -> false
        else -> d3dRegistryDxvk() ?: false
    }

    /**
     * Skrip sinkronisasi untuk [ShortcutRunner]: bila pengguna sudah memilih mode ([Cfg.d3dMode]) dan registri prefix belum
     * sesuai, jalankan [d3dRegCommands]. Dicek tiap peluncuran (satu grep); tanpa `wineserver -w`. Null bila belum dipilih.
     */
    fun d3dSyncScript(): String? {
        val dxvk = when (Cfg.d3dMode) { "dxvk" -> true; "wined3d" -> false; else -> return null }
        val want = if (dxvk) "native" else "builtin"
        val d = "$"
        return """
(
  if ! grep -qs "^\"d3d11\"=\"$want\"" "$d{WINEPREFIX:-${d}HOME/.wine}/user.reg"; then
    echo "[tiny] Wine: mode Direct3D di registri belum $want; ditulis ulang (${if (dxvk) "DXVK" else "WineD3D"})"
    ${d3dRegCommands(dxvk)}
  fi
)
""".trimStart()
    }

    /**
     * Dipanggil UI saat pengguna mengganti mode Direct3D: menulis DllOverrides ke registri prefix lewat shell kontainer.
     * Hanya jalan bila kontainer menyala dan prefix ada; selain itu disinkronkan di peluncuran Shortcut berikutnya.
     */
    fun applyD3dModeAsync(ctx: Context) {
        val app = ctx.applicationContext
        val dxvk = Cfg.d3dMode == "dxvk"
        Thread({
            if (!Launcher.isStarted || !prefixReady()) return@Thread
            // Driver tampilan ikut disamakan: WineD3D -> x11 (OpenGL lewat Zink), DXVK -> wayland,x11 (Vulkan).
            // Cfg.d3dMode sudah diubah UI sebelum fungsi ini dipanggil, jadi displayDriverValue() sudah mengikuti mode baru.
            val display = "wine reg add 'HKCU\\Software\\Wine\\Drivers' /v Graphics /d ${displayDriverValue()} /f >/dev/null 2>&1"
            runCatching { guestRun(app, d3dRegCommands(dxvk) + "; " + display, 5 * 60_000L) }
        }, "tiny-wine-d3d").start()
    }

    /**
     * Dipanggil UI saat pengguna mengganti driver tampilan Wine: menulis "wayland,x11" atau "x11" ke registri prefix.
     * Hanya jalan bila kontainer menyala dan prefix ada; selain itu berlaku di peluncuran Shortcut berikutnya.
     */
    fun applyDisplayAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            if (!Launcher.isStarted || !prefixReady()) return@Thread
            runCatching {
                val cmd = "wine reg add 'HKCU\\Software\\Wine\\Drivers' /v Graphics /d ${displayDriverValue()} /f; wineserver -w"
                guestRun(app, cmd, 5 * 60_000L)
            }
        }, "tiny-wine-display").start()
    }
}
