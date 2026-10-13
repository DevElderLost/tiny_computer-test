package com.example.tiny_computer.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.droiddeck.launcher.input.EvdevKeys
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.ShortcutPrefs
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.ui.TinyTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Status UI yang dibaca overlay Compose. */
class DisplayUiState {
    var menuOpen by mutableStateOf(false)
    var touchpad by mutableStateOf(false)
    var padOverlay by mutableStateOf(Cfg.padOverlay)
    var padIdentity by mutableStateOf(Cfg.padIdentity)
    /** Keyboard PC di layar (dari DroidDeck): tombol evdev asli, bukan keyboard Android. */
    var pcKeyboard by mutableStateOf(false)
    /** True bila layar ini menjalankan aplikasi Shortcut (bukan desktop): tombol Shutdown dan layar progres aktif. */
    var appMode by mutableStateOf(false)
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

        val p = PadBridge(File(File(filesDir, "session"), "dev/input"))
        p.start()
        pad = p
        Launcher.onAppSessionEnded = onAppEnded

        surfaceView = DeskSurface(this).apply {
            holder.addCallback(this@DisplayActivity)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val overlay = ComposeView(this).apply {
            setContent {
                TinyTheme {
                    DisplayOverlay(
                        state = ui,
                        pad = p,
                        sendKey = { code, down -> WaylandCompositor.nativeSendKey(code, if (down) 1 else 0) },
                        onClose = { finish() },
                        onShutdown = {
                            // matikan semua aplikasi + Xwayland sesi Shortcut sekarang juga; layar progres tampil
                            // sampai semua proses benar-benar mati, baru kembali ke menu Shortcut
                            Launcher.shutdownAppSession { finish() }
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
        surfaceView.requestFocus()
    }

    override fun onDestroy() {
        if (Launcher.onAppSessionEnded === onAppEnded) Launcher.onAppSessionEnded = null
        pad?.stop()
        pad = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Surface / compositor

    override fun surfaceCreated(holder: SurfaceHolder) {
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
        if (!CompositorHost.isStarted) {
            WaylandCompositor.nativeSetHdrRequest(WaylandCompositor.HDR_MODE_OFF, "tiny", false, false)
        }
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
        CompositorHost.detach(holder.surface)
    }

    // ------------------------------------------------------------------ Masukan

    /** Posisi layar -> koordinat scene (piksel output), dengan letterbox FIT di tengah. */
    private fun sceneX(vx: Float): Int {
        val s = min(surfaceView.width.toFloat() / outW, surfaceView.height.toFloat() / outH)
        val off = (surfaceView.width - outW * s) / 2f
        return ((vx - off) / s).toInt().coerceIn(0, outW - 1)
    }

    private fun sceneY(vy: Float): Int {
        val s = min(surfaceView.width.toFloat() / outW, surfaceView.height.toFloat() / outH)
        val off = (surfaceView.height - outH * s) / 2f
        return ((vy - off) / s).toInt().coerceIn(0, outH - 1)
    }

    private fun move(x: Float, y: Float) =
        WaylandCompositor.nativeSendSceneInput(SCENE_MOTION, sceneX(x), sceneY(y))

    private fun button(code: Int, down: Boolean) =
        WaylandCompositor.nativeSendSceneInput(SCENE_BUTTON, code, if (down) 1 else 0)

    private fun handleTouch(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) return handleMouse(e)
        val touchpad = ui.touchpad
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false; scrollAcc = 0f
                if (!touchpad) { move(e.x, e.y); button(BTN_LEFT, true); leftHeld = true }
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
                } else if (touchpad) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (abs(dx) + abs(dy) > 1f) moved = true
                    lastX = e.x; lastY = e.y
                    val s = min(surfaceView.width.toFloat() / outW, surfaceView.height.toFloat() / outH)
                    // gerak relatif dalam 1/256 piksel scene
                    WaylandCompositor.nativeSendSceneInput(
                        SCENE_RELATIVE, (dx / s * 256f * TOUCHPAD_GAIN).toInt(), (dy / s * 256f * TOUCHPAD_GAIN).toInt(),
                    )
                } else {
                    move(e.x, e.y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!moved && e.eventTime - downTime < TAP_MS) { button(BTN_RIGHT, true); button(BTN_RIGHT, false) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (leftHeld) { button(BTN_LEFT, false); leftHeld = false }
                else if (touchpad && !moved && e.eventTime - downTime < TAP_MS && e.pointerCount == 1) {
                    button(BTN_LEFT, true); button(BTN_LEFT, false)
                }
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

        /** Ukuran output yang terakhir diberikan ke compositor (satu proses = satu compositor). */
        @Volatile private var lastOutput: Pair<Int, Int>? = null
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
        private const val SCENE_MOTION = 2
        private const val SCENE_BUTTON = 3
        private const val SCENE_WHEEL = 4
        private const val SCENE_RELATIVE = 6
        private const val BTN_LEFT = 272
        private const val BTN_RIGHT = 273
        private const val BTN_MIDDLE = 274
        private const val TAP_MS = 250L
        private const val SCROLL_STEP_PX = 40f
        private const val TOUCHPAD_GAIN = 1.6f
    }
}
