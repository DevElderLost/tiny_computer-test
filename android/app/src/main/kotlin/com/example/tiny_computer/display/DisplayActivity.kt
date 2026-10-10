package com.example.tiny_computer.display

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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

    // pelacakan sentuhan
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var leftHeld = false
    private var scrollAcc = 0f
    private var shiftInjected = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        val size = Launcher.displaySize(this)
        outW = size.first
        outH = size.second

        val p = PadBridge(File(File(filesDir, "session"), "dev/input"))
        p.start()
        pad = p

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
                        onKeyboard = { toggleKeyboard() },
                        onClose = { finish() },
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

    override fun onResume() {
        super.onResume()
        ui.padOverlay = Cfg.padOverlay
        ui.padIdentity = Cfg.padIdentity
        surfaceView.requestFocus()
    }

    override fun onDestroy() {
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
        CompositorHost.startOrAttach(
            holder.surface, rt.path,
            id?.let { turnip.driverPath(it) }, id?.let { turnip.libraryName(it) },
            applicationInfo.nativeLibraryDir, outW, outH, refresh, Cfg.fpsLimit,
        )
        SessionState.running = true
        // soket wayland-0 sudah ada: nyalakan Xwayland + sesi X11 Debian di dalam kontainer (sekali per peluncuran)
        if (Launcher.isStarted && !Launcher.guiStarted) Launcher.launchGuiBackend(this)
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
            if (event.action == KeyEvent.ACTION_UP) ui.menuOpen = !ui.menuOpen
            return true
        }
        val evdev = EvdevKeys.fromKeyCode(code)
        if (evdev <= 0) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) {
            // tekan-tahan: kirim ulang sebagai tombol baru (klien Xwayland mengulang sendiri)
            return true
        }
        // keyboard layar mengirim huruf kapital/simbol sebagai KEYCODE_x + META_SHIFT_ON tanpa tombol shift sendiri
        val isShiftKey = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
        if (!isShiftKey && down && event.isShiftPressed && !shiftInjected && event.device?.isVirtual != false) {
            WaylandCompositor.nativeSendKey(SHIFT_EVDEV, 1); shiftInjected = true
        }
        WaylandCompositor.nativeSendKey(evdev, if (down) 1 else 0)
        if (!down && shiftInjected && !isShiftKey) { WaylandCompositor.nativeSendKey(SHIFT_EVDEV, 0); shiftInjected = false }
        return true
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        surfaceView.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    /** SurfaceView yang menerima sentuhan dan menghasilkan InputConnection TYPE_NULL (tombol mentah). */
    private inner class DeskSurface(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return BaseInputConnection(this, false)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
    }

    companion object {
        private const val TAG = "DisplayActivity"
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
        private const val SCENE_MOTION = 2
        private const val SCENE_BUTTON = 3
        private const val SCENE_WHEEL = 4
        private const val SCENE_RELATIVE = 6
        private const val BTN_LEFT = 272
        private const val BTN_RIGHT = 273
        private const val BTN_MIDDLE = 274
        private const val SHIFT_EVDEV = 42
        private const val TAP_MS = 250L
        private const val SCROLL_STEP_PX = 40f
        private const val TOUCHPAD_GAIN = 1.6f
    }
}
