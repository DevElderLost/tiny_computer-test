package com.example.tiny_computer.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.graphics.Rect
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.SharedPreferences
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.PadMotion
import com.droiddeck.launcher.input.RumbleReceiver
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.HdrSupport
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.NavRequest
import com.example.tiny_computer.core.ShortcutPrefs
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.ui.TinyTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Status UI yang dibaca overlay Compose. */
class DisplayUiState {
    var menuOpen by mutableStateOf(false)
    var touchpad by mutableStateOf(Cfg.touchpadMode)
    var touchpadSpeed by mutableStateOf(Cfg.touchpadSpeed.toFloat())
    var touchpadScroll by mutableStateOf(Cfg.touchpadScroll.toFloat())
    var touchpadNatural by mutableStateOf(Cfg.touchpadNatural)
    /** Gambar kursor bawaan rootfs (CursorView) dan pengali ukurannya. */
    var showCursor by mutableStateOf(Cfg.showCursor)
    var cursorScale by mutableStateOf(Cfg.cursorScale.toFloat())
    var padOverlay by mutableStateOf(Cfg.padOverlay)
    var padIdentity by mutableStateOf(Cfg.padIdentity)
    /** Gyro controller: saklar, tujuan stik ("off" / "left" / "right"), dan sensitivitas 1..10 (ControllerPrefs). */
    var gyro by mutableStateOf(false)
    var gyroTarget by mutableStateOf(ControllerPrefs.GYRO_RIGHT)
    var gyroSens by mutableStateOf(5)
    /** Mapping trackpad L/R (ControllerPrefs.PAD_STICK / PAD_HID / PAD_MOUSE) dan kecepatan kursornya 1..10 (mode mouse). */
    var trackpad by mutableStateOf(ControllerPrefs.PAD_STICK)
    var trackpadSpeed by mutableStateOf(5)
    /** Keyboard PC di layar (dari DroidDeck): tombol evdev asli, bukan keyboard Android. */
    var pcKeyboard by mutableStateOf(false)
    /** True bila layar ini menjalankan aplikasi Shortcut (bukan desktop): tombol Shutdown dan layar progres aktif. */
    var appMode by mutableStateOf(false)
    /** Ukuran scene compositor (kecepatan kursor trackpad mode mouse) dan penghitung muat-ulang tata letak controller. */
    var sceneW by mutableStateOf(1280)
    var sceneH by mutableStateOf(720)
    var padLayoutRev by mutableStateOf(0)
}

/**
 * Layar desktop: SurfaceView tempat compositor Wayland DroidDeck menggambar (Xwayland + sesi X11
 * Debian ada di dalam kontainer), ditutup ComposeView untuk menu dan controller di layar.
 * Pengganti halaman X11/AVNC/noVNC versi Flutter.
 */
class DisplayActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val ui = DisplayUiState()
    private lateinit var surfaceView: DeskSurface
    private var pad: PadBridge? = null
    private lateinit var touchpadInput: TouchpadInput
    /** Gyro + akselerometer HP sebagai gyro controller (hidraw Deck) dan/atau stik; hanya hidup selama layar ini tampil. */
    private var motion: PadMotion? = null
    private var resumed = false
    private val controllerPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { syncMotion() } }
    private var lastTouchpad = false

    // menu sesi: geser dari tepi kiri ke kanan
    private var density = 1f
    private var edgeTracking = false   // jari turun di tepi kiri dan belum jadi geseran
    private var edgeSwallow = false    // geseran sudah dikenali: sisa gestur tidak diteruskan ke sesi
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var outW = 1280
    private var outH = 720

    // Shortcut: id aplikasi yang membuka layar ini (null = desktop biasa), dan yang menunggu surface siap
    private var currentApp: String? = null
    private var pendingApp: String? = null
    private var surfaceReady = false

    // semua aplikasi Shortcut selesai: tutup layar ini supaya kembali ke menu Shortcut (desktop tidak ikut tertutup)
    private val onAppEnded: (String?) -> Unit = { failed ->
        if (currentApp != null && !isFinishing) {
            if (failed != null) {
                Toast.makeText(applicationContext, "App stopped right after start. Log: ~/.vnc/shortcut-$failed.log", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    // pelacakan sentuhan
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var leftHeld = false
    private var scrollAcc = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readLaunchIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val size = Launcher.sessionSize(this, currentApp)
        outW = size.first
        outH = size.second
        ui.sceneW = outW
        ui.sceneH = outH

        // seret dimulai (tekan lama / tiga jari): getaran singkat sebagai tanda tombol kiri tertahan
        density = resources.displayMetrics.density
        touchpadInput = TouchpadInput(ViewConfiguration.get(this).scaledTouchSlop.toFloat()) {
            surfaceView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        lastTouchpad = ui.touchpad
        val p = PadBridge(File(File(filesDir, "session"), "dev/input"))
        p.start()
        pad = p
        // rumble dari game (evdev FF + hidraw Deck) -> getar HP / controller fisik
        RumbleReceiver.start(this)
        ControllerPrefs.prefs(this).registerOnSharedPreferenceChangeListener(controllerPrefsListener)
        Launcher.onAppSessionEnded = onAppEnded

        surfaceView = DeskSurface(this).apply {
            holder.addCallback(this@DisplayActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            // gestur Back sistem juga mulai dari tepi kiri: sisihkan sebagian tepi (batas sistem 200dp) untuk menu
            addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val h = bottom - top
                    val strip = minOf(h, (EDGE_EXCLUDE_DP * density).toInt())
                    val y = (h - strip) / 2
                    v.systemGestureExclusionRects = listOf(Rect(0, y, (EDGE_ZONE_DP * density).toInt(), y + strip))
                }
            }
        }
        val overlay = ComposeView(this).apply {
            setContent {
                TinyTheme {
                    DisplayOverlay(
                        state = ui,
                        pad = p,
                        sendKey = { code, down -> WaylandCompositor.nativeSendKey(code, if (down) 1 else 0) },
                        // Back to terminal: tutup layar desktop dan minta TinyApp membuka tab Terminal
                        onClose = { NavRequest.terminal = true; finish() },
                        onShutdown = {
                            // matikan sekarang juga; layar progres tampil sampai semua proses benar-benar mati.
                            // Sesi aplikasi Shortcut: semua aplikasi + Xwayland-nya; desktop: Xwayland + sesi X11.
                            // Setelah itu layar ditutup (kembali ke menu Shortcut atau terminal).
                            if (currentApp != null) Launcher.shutdownAppSession { finish() }
                            else Launcher.shutdownDesktop { finish() }
                        },
                        onPadOverlayChange = { on ->
                            // aplikasi Shortcut punya pengaturan controller sendiri; desktop memakai yang global
                            val app = currentApp
                            if (app != null) { ShortcutPrefs.setPadOverlay(app, on) } else { Cfg.padOverlay = on }
                        },
                    )
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            // kursor rootfs: di atas compositor, di bawah menu; tidak menerima sentuhan
            addView(CursorView(this@DisplayActivity, ui) { currentScene() }, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1))
        })
    }

    /** Id aplikasi Shortcut dari intent (bila ada) menentukan sesi mana yang dinyalakan di surfaceCreated. */
    private fun readLaunchIntent(i: Intent?) {
        val app = i?.getStringExtra(EXTRA_APP)
        currentApp = app
        pendingApp = app
        ui.appMode = app != null
        ui.padOverlay = ShortcutPrefs.padOverlay(app) ?: Cfg.padOverlay
    }

    /** launchMode singleTask: ketukan Shortcut berikutnya masuk ke sini selama layar ini masih hidup. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLaunchIntent(intent)
        syncOutputSize()
        if (surfaceReady) startRequestedSession()
    }

    /**
     * Ukuran layar aplikasi yang diminta bisa berbeda dari yang sedang tampil (tiap aplikasi Shortcut
     * punya ukuran sendiri): ubah ukuran output compositor, juga pemetaan sentuhan ([outW]/[outH]).
     */
    private fun syncOutputSize() {
        val size = Launcher.sessionSize(this, currentApp)
        if (size.first == outW && size.second == outH) return
        outW = size.first
        outH = size.second
        ui.sceneW = outW
        ui.sceneH = outH
        if (surfaceReady) {
            WaylandCompositor.nativeResizeOutput(outW, outH)
            lastOutput = outW to outH
        }
    }

    /**
     * Mode aplikasi: jalankan aplikasi yang diminta (di dalam desktop bila sedang berjalan, atau di
     * Xwayland khusus aplikasi). Mode desktop: nyalakan desktop sekali per peluncuran. Layar yang
     * dibuka ulang (surface dibuat lagi) tanpa permintaan baru hanya menempel ke sesi yang berjalan.
     */
    private fun startRequestedSession() {
        val app = pendingApp
        if (app != null) {
            // aplikasi Shortcut berjalan di proot sendiri: tidak butuh kontainer/terminal yang hidup
            pendingApp = null
            Launcher.launchApp(this, app)
            return
        }
        if (!Launcher.isStarted) return
        if (currentApp == null && !Launcher.guiStarted) Launcher.launchGuiBackend(this)
    }

    override fun onResume() {
        super.onResume()
        ui.padOverlay = ShortcutPrefs.padOverlay(currentApp) ?: Cfg.padOverlay
        ui.padIdentity = Cfg.padIdentity
        ui.touchpad = Cfg.touchpadMode
        ui.showCursor = Cfg.showCursor
        // kembali dari editor tata letak (atau pengaturan lain): controller layar membaca ulang posisi dan mode
        ui.padLayoutRev++
        resumed = true
        syncMotion()
        surfaceView.requestFocus()
    }

    override fun onPause() {
        // sensor tidak dibaca saat layar ini tidak tampil
        resumed = false
        stopMotion()
        super.onPause()
    }

    /** Menyalakan/mematikan gyro sesuai saklar di Pengaturan Controller dan menu samping, dan memasang mapping-nya. */
    private fun syncMotion() {
        // mapping trackpad: sama dengan Pengaturan > Controller; diubah dari tab Controller atau menu samping
        ui.trackpad = ControllerPrefs.trackpadMode(this)
        ui.trackpadSpeed = ControllerPrefs.trackpadMouseSpeed(this)
        ui.gyro = ControllerPrefs.gyroEnabled(this)
        ui.gyroTarget = ControllerPrefs.gyroTarget(this)
        ui.gyroSens = ControllerPrefs.gyroSensitivity(this)
        if (!ui.gyro || pad == null || !resumed) {
            stopMotion()
            return
        }
        val m = motion ?: PadMotion(this, { displayRotation() }, pad).also {
            motion = it
            it.start()
        }
        m.configure(ui.gyroTarget, ui.gyroSens)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun stopMotion() {
        motion?.stop()
        motion = null
    }

    override fun onDestroy() {
        if (Launcher.onAppSessionEnded === onAppEnded) Launcher.onAppSessionEnded = null
        ControllerPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(controllerPrefsListener)
        stopMotion()
        RumbleReceiver.stop()
        pad?.stop()
        pad = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Surface / compositor

    override fun surfaceCreated(holder: SurfaceHolder) {
        // ukuran Surface lama bukan pembanding untuk Surface baru: tanpa ini surfaceChanged pertama
        // dianggap "diubah ukurannya" dan memutus-pasang ulang swapchain tepat setelah dibuat
        surfW = 0
        surfH = 0
        val rt = TinyEnv.runtimeDir().apply { mkdirs() }
        // keymap yang diberikan compositor ke klien wl_keyboard (cara guest membaca kode evdev kita)
        runCatching {
            assets.open("wayland/keymap.xkb").use { i -> File(rt, "keymap.xkb").outputStream().use { i.copyTo(it) } }
        }.onFailure { Log.e(TAG, "keymap.xkb", it) }

        val turnip = TurnipLoader(this)
        val id = if (CompositorHost.isStarted) null else turnip.install(Cfg.androidDriver)
        val refresh = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate

        WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
        WaylandCompositor.nativeSetUpscaler(Cfg.upscaler)
        WaylandCompositor.nativeSetPresentMode(Cfg.presentMode)
        WaylandCompositor.nativeSetBuffering(Cfg.swapBuffers)
        if (!CompositorHost.isStarted) {
            // Gerbang HDR compositor memakai laporan Display ini; HDR hanya menyala bila diminta (Pengaturan)
            // DAN panel mendukung HDR10. HDR memakai display layer, jadi zero-copy ikut dinyalakan.
            val probe = HdrSupport.probe(this)
            WaylandCompositor.nativeSetHdrDisplay(
                probe.displayId, probe.name, probe.formats, probe.hdr10,
                probe.maxLuminance, probe.maxAverageLuminance, probe.minLuminance,
                probe.ratioAvailable, probe.ratio, Build.VERSION.SDK_INT,
            )
            val hdrOn = Cfg.hdrOutput && probe.reason == null
            if (hdrOn) {
                runCatching { android.system.Os.setenv("DROIDDECK_WAYLAND_HDR", "1", true) }
                WaylandCompositor.nativeSetZeroCopy(true)
            } else {
                runCatching { android.system.Os.unsetenv("DROIDDECK_WAYLAND_HDR") }
            }
            WaylandCompositor.nativeSetHdrRequest(
                if (hdrOn) WaylandCompositor.HDR_MODE_ON else WaylandCompositor.HDR_MODE_OFF,
                "tiny settings" + (if (Cfg.hdrOutput && !hdrOn) " (refused: ${probe.reason})" else ""),
                hdrOn, hdrOn,
            )
            Log.i(TAG, "hdr: " + (if (hdrOn) "on" else if (Cfg.hdrOutput) "wanted but ${probe.reason}" else "off") +
                " - display ${probe.formats.ifEmpty { "SDR" }}")
        }
        // log sesi compositor (Download/Wayland-logs/wayland-*.log) ikut saklar Pengaturan > Log; dibaca compositor
        // sekali saat dinyalakan, jadi perubahan saklar berlaku mulai peluncuran aplikasi berikutnya
        android.system.Os.setenv("TINY_WAYLAND_LOG", if (Cfg.launchLog) "1" else "0", true)
        // DRM palsu (extra/fakedrm): compositor menyebut render node tiruan itu di dmabuf feedback, dibaca saat compositor mulai
        if (Cfg.fakeDrm) android.system.Os.setenv("DROIDDECK_WAYLAND_FAKE_RENDER_NODE", "1", true)
        else android.system.Os.unsetenv("DROIDDECK_WAYLAND_FAKE_RENDER_NODE")
        CompositorHost.startOrAttach(
            holder.surface, rt.path,
            id?.let { turnip.driverPath(it) }, id?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir, outW, outH, refresh, Cfg.fpsLimit,
        )
        // compositor hidup lebih lama dari activity dan hanya menerima ukuran awal saat pertama dinyalakan
        // (lihat CompositorHost.startOrAttach): sesi berikutnya dengan ukuran lain harus mengubahnya sendiri
        val prev = lastOutput
        if (prev != null && prev != (outW to outH)) WaylandCompositor.nativeResizeOutput(outW, outH)
        lastOutput = outW to outH
        // efek layar (HDR bloom, CAS, debanding, grading, ketajaman) dari menu sesi > Effect; setter native aman dipanggil kapan saja
        ScreenFx.apply()
        SessionState.running = true
        surfaceReady = true
        // soket wayland-0 sudah ada: nyalakan desktop (Xwayland + sesi X11 Debian) atau aplikasi Shortcut
        startRequestedSession()
    }

    private var surfW = 0
    private var surfH = 0

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val resized = surfW != 0 && (width != surfW || height != surfH)
        surfW = width
        surfH = height
        if (resized) CompositorHost.resize(holder.surface) { }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        surfW = 0
        surfH = 0
        CompositorHost.detach(holder.surface)
    }

    // ------------------------------------------------------------------ Masukan

    private val sceneBuf = IntArray(2)

    /**
     * Ukuran scene yang SEDANG digambar compositor. Tanpa desktop Wine scene mengikuti window terbesar,
     * jadi bisa lebih besar dari ukuran sesi ([outW]/[outH]) begitu dialog yang lebih tinggi dari window
     * utama terbuka (Configure Azahar 960x736 di atas window 960x544). Pemetaan sentuhan dan kursor harus
     * memakai ukuran ini; dengan [outW]/[outH] ketukan mendarat di y yang salah dan tombol dialog tidak
     * pernah kena. Belum ada yang tergambar = ukuran sesi.
     */
    private fun currentScene(): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (WaylandCompositor.nativeSceneSize(sceneBuf) >= 2 && sceneBuf[0] > 0 && sceneBuf[1] > 0) {
            w = sceneBuf[0]
            h = sceneBuf[1]
        } else {
            w = outW
            h = outH
        }
        // overlay Compose (kecepatan trackpad controller) ikut ukuran yang sama
        if (ui.sceneW != w) ui.sceneW = w
        if (ui.sceneH != h) ui.sceneH = h
        return w to h
    }

    /** Posisi layar -> koordinat scene (piksel scene yang digambar), dengan letterbox FIT di tengah. */
    private fun sceneX(vx: Float): Int {
        val (sw, sh) = currentScene()
        val s = min(surfaceView.width.toFloat() / sw, surfaceView.height.toFloat() / sh)
        val off = (surfaceView.width - sw * s) / 2f
        return ((vx - off) / s).toInt().coerceIn(0, sw - 1)
    }

    private fun sceneY(vy: Float): Int {
        val (sw, sh) = currentScene()
        val s = min(surfaceView.width.toFloat() / sw, surfaceView.height.toFloat() / sh)
        val off = (surfaceView.height - sh * s) / 2f
        return ((vy - off) / s).toInt().coerceIn(0, sh - 1)
    }

    private fun move(x: Float, y: Float) =
        WaylandCompositor.nativeSendSceneInput(SCENE_MOTION, sceneX(x), sceneY(y))

    private fun button(code: Int, down: Boolean) =
        WaylandCompositor.nativeSendSceneInput(SCENE_BUTTON, code, if (down) 1 else 0)

    /**
     * Menu sesi: jari turun di tepi kiri lalu digeser ke kanan membuka [SessionDrawer]. Sentuhan yang
     * mulai di tepi itu tidak diteruskan ke sesi (ketukan di tepi tidak berbuat apa-apa). True = dimakan.
     */
    private fun handleEdgeSwipe(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwallow = false
                edgeTracking = !ui.menuOpen && e.x <= EDGE_ZONE_DP * density
                edgeDownX = e.x
                edgeDownY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (edgeTracking) {
                val dx = e.x - edgeDownX
                val dy = abs(e.y - edgeDownY)
                if (dx > EDGE_SWIPE_DP * density && dx > dy * 1.2f) {
                    edgeTracking = false
                    edgeSwallow = true
                    ui.menuOpen = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val eaten = edgeTracking || edgeSwallow
                edgeTracking = false
                edgeSwallow = false
                return eaten
            }
        }
        return edgeTracking || edgeSwallow
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) return handleMouse(e)
        if (handleEdgeSwipe(e)) return true
        val touchpad = ui.touchpad
        if (touchpad != lastTouchpad) {
            // mode diganti di tengah gestur: lepas tombol yang masih tertahan
            if (leftHeld) { button(BTN_LEFT, false); leftHeld = false }
            touchpadInput.cancel()
            lastTouchpad = touchpad
        }
        if (touchpad) {
            val (sw, sh) = currentScene()
            val s = min(surfaceView.width.toFloat() / sw, surfaceView.height.toFloat() / sh)
            touchpadInput.speed = ui.touchpadSpeed
            touchpadInput.scrollSpeed = ui.touchpadScroll
            touchpadInput.naturalScroll = ui.touchpadNatural
            return touchpadInput.onTouch(e, s, sw, sh)
        }
        // sentuh langsung: kursor mengikuti jari
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false; scrollAcc = 0f
                move(e.x, e.y); button(BTN_LEFT, true); leftHeld = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // dua jari: gulir; ketuk dua jari = klik kanan (saat dilepas tanpa bergerak)
                if (leftHeld) { button(BTN_LEFT, false); leftHeld = false }
                lastY = (e.getY(0) + e.getY(1)) / 2f
                scrollAcc = 0f; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val y = (e.getY(0) + e.getY(1)) / 2f
                    scrollAcc += (lastY - y)
                    lastY = y
                    val steps = (scrollAcc / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        moved = true
                        scrollAcc -= steps * SCROLL_STEP_PX
                        WaylandCompositor.nativeSendSceneInput(SCENE_WHEEL, steps, 0)
                    }
                } else {
                    move(e.x, e.y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!moved && e.eventTime - downTime < TAP_MS) { button(BTN_RIGHT, true); button(BTN_RIGHT, false) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (leftHeld) { button(BTN_LEFT, false); leftHeld = false }
            }
        }
        return true
    }

    private fun handleMouse(e: MotionEvent): Boolean {
        move(e.x, e.y)
        val left = e.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        val right = e.buttonState and MotionEvent.BUTTON_SECONDARY != 0
        val mid = e.buttonState and MotionEvent.BUTTON_TERTIARY != 0
        if (left != mouseLeft) { button(BTN_LEFT, left); mouseLeft = left }
        if (right != mouseRight) { button(BTN_RIGHT, right); mouseRight = right }
        if (mid != mouseMid) { button(BTN_MIDDLE, mid); mouseMid = mid }
        return true
    }

    private var mouseLeft = false
    private var mouseRight = false
    private var mouseMid = false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val p = pad
        if (p != null && PadBridge.isFromController(event.device) && p.onMotionEvent(event)) return true
        if (event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                WaylandCompositor.nativeSendSceneInput(SCENE_WHEEL, (-v).toInt().let { if (it == 0 && v != 0f) (if (v > 0) -1 else 1) else it }, 0)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE) {
                return handleMouse(event)
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = pad
        if (p != null && PadBridge.isControllerKey(event) && p.onKeyEvent(event)) return true
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (ui.pcKeyboard) ui.pcKeyboard = false else ui.menuOpen = !ui.menuOpen
            }
            return true
        }
        val evdev = EvdevKeys.fromKeyCode(code)
        if (evdev <= 0) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) {
            // tekan-tahan: kirim ulang sebagai tombol baru (klien Xwayland mengulang sendiri)
            return true
        }
        WaylandCompositor.nativeSendKey(evdev, if (down) 1 else 0)
        return true
    }

    /** SurfaceView yang menerima sentuhan. Teks diketik lewat keyboard PC di layar atau keyboard fisik. */
    private inner class DeskSurface(context: Context) : SurfaceView(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
    }

    companion object {
        /** Id berkas .desktop (tanpa ekstensi) yang dijalankan tanpa membuka desktop; tidak ada = desktop biasa. */
        const val EXTRA_APP = "tiny.shortcut.app"
        private const val TAG = "DisplayActivity"

        /** Lebar zona tepi kiri tempat geseran menu dimulai, jarak geser yang membukanya, dan tinggi tepi yang disisihkan dari gestur Back. */
        private const val EDGE_ZONE_DP = 28f
        private const val EDGE_SWIPE_DP = 36f
        private const val EDGE_EXCLUDE_DP = 200f

        /** Ukuran output yang terakhir diberikan ke compositor (satu proses = satu compositor). */
        @Volatile private var lastOutput: Pair<Int, Int>? = null
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
        private const val SCENE_MOTION = 2
        private const val SCENE_BUTTON = 3
        private const val SCENE_WHEEL = 4
        private const val BTN_LEFT = 272
        private const val BTN_RIGHT = 273
        private const val BTN_MIDDLE = 274
        private const val TAP_MS = 250L
        private const val SCROLL_STEP_PX = 40f
    }
}
