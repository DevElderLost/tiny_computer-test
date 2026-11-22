package com.example.tiny_computer.controls

import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.example.tiny_computer.display.SceneInput

/**
 * Mengubah satu [Binding] yang ditekan/dilepas (dari tombol layar profil atau controller fisik) menjadi masukan nyata:
 * keyboard dan mouse lewat [SceneInput] (compositor Wayland atau X++), gamepad lewat [PadBridge.applyTouch] sehingga
 * bergabung dengan pad Deck/Xbox 360 yang sama dengan yang dibaca game (libfakeinput -> /dev/input + hidraw).
 * Port `InputControlsView.handleInputEvent` Winlator.
 *
 * Beberapa sumber boleh menahan binding yang sama (mis. dua tombol layar ke KEY_W): tombol dilepas baru setelah
 * penahan terakhir lepas ([holds]).
 */
class BindingDispatcher(private val pad: PadBridge?) {
    private val holds = IntArray(Binding.entries.size)
    private val main = Handler(Looper.getMainLooper())

    /** Kecepatan kursor profil (Profile.cursorSpeed), dipakai MOUSE_MOVE_* dan tombol mode gerak mouse. */
    @Volatile var cursorSpeed = 1f

    private var moveX = 0f
    private var moveY = 0f
    private var moveTimer = false
    private val moveTick = object : Runnable {
        override fun run() {
            if (moveX == 0f && moveY == 0f) { moveTimer = false; return }
            moveCursor(moveX * MOVE_PX_PER_TICK, moveY * MOVE_PX_PER_TICK)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** [offset] 0 = penuh; 0..1 = kekuatan sumbu analog (stik/trackpad). */
    fun handle(binding: Binding, down: Boolean, offset: Float = 0f) {
        if (binding == Binding.NONE) return
        when {
            binding.isGamepad -> gamepad(binding, down, offset)
            binding.isMouseMove -> mouseMove(binding, down, offset)
            binding == Binding.MOUSE_SCROLL_UP -> if (down) SceneInput.send(SceneInput.SCENE_WHEEL, -1, 0)
            binding == Binding.MOUSE_SCROLL_DOWN -> if (down) SceneInput.send(SceneInput.SCENE_WHEEL, 1, 0)
            binding.isMouse -> hold(binding, down) { SceneInput.send(SceneInput.SCENE_BUTTON, binding.mouseButton, if (it) 1 else 0) }
            else -> hold(binding, down) { SceneInput.key(binding.evdev, it) }
        }
    }

    /** Gerak relatif kursor dalam piksel scene (tombol mode gerak mouse, trackpad profil). */
    fun moveCursor(dx: Float, dy: Float) {
        val ix = (dx * cursorSpeed * 256f).toInt()
        val iy = (dy * cursorSpeed * 256f).toInt()
        if (ix != 0 || iy != 0) SceneInput.send(SceneInput.SCENE_RELATIVE, ix, iy)
    }

    private fun hold(b: Binding, down: Boolean, apply: (Boolean) -> Unit) {
        val i = b.ordinal
        if (down) {
            if (holds[i]++ == 0) apply(true)
        } else if (holds[i] > 0) {
            if (--holds[i] == 0) apply(false)
        }
    }

    private fun mouseMove(b: Binding, down: Boolean, offset: Float) {
        val v = if (!down) 0f else if (offset != 0f) offset else 1f
        when (b) {
            Binding.MOUSE_MOVE_LEFT -> moveX = -v
            Binding.MOUSE_MOVE_RIGHT -> moveX = v
            Binding.MOUSE_MOVE_UP -> moveY = -v
            else -> moveY = v
        }
        if (!moveTimer && (moveX != 0f || moveY != 0f)) {
            moveTimer = true
            main.post(moveTick)
        }
    }

    private fun gamepad(b: Binding, down: Boolean, offset: Float) {
        val p = pad ?: return
        val strength = if (!down) 0f else if (offset != 0f) kotlin.math.abs(offset) else 1f
        p.applyTouch { s ->
            when (b) {
                Binding.GAMEPAD_BUTTON_A -> s.press(PadState.A, down)
                Binding.GAMEPAD_BUTTON_B -> s.press(PadState.B, down)
                Binding.GAMEPAD_BUTTON_X -> s.press(PadState.X, down)
                Binding.GAMEPAD_BUTTON_Y -> s.press(PadState.Y, down)
                Binding.GAMEPAD_BUTTON_L1 -> s.press(PadState.LB, down)
                Binding.GAMEPAD_BUTTON_R1 -> s.press(PadState.RB, down)
                Binding.GAMEPAD_BUTTON_SELECT -> s.press(PadState.SELECT, down)
                Binding.GAMEPAD_BUTTON_START -> s.press(PadState.START, down)
                Binding.GAMEPAD_BUTTON_L3 -> s.press(PadState.L3, down)
                Binding.GAMEPAD_BUTTON_R3 -> s.press(PadState.R3, down)
                Binding.GAMEPAD_BUTTON_L2 -> s.leftTrigger = strength
                Binding.GAMEPAD_BUTTON_R2 -> s.rightTrigger = strength
                // konvensi evdev PadState: y positif = ke bawah (atas = -1), sama dengan Winlator
                Binding.GAMEPAD_LEFT_THUMB_UP -> s.leftY = -strength
                Binding.GAMEPAD_LEFT_THUMB_DOWN -> s.leftY = strength
                Binding.GAMEPAD_LEFT_THUMB_LEFT -> s.leftX = -strength
                Binding.GAMEPAD_LEFT_THUMB_RIGHT -> s.leftX = strength
                Binding.GAMEPAD_RIGHT_THUMB_UP -> s.rightY = -strength
                Binding.GAMEPAD_RIGHT_THUMB_DOWN -> s.rightY = strength
                Binding.GAMEPAD_RIGHT_THUMB_LEFT -> s.rightX = -strength
                Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> s.rightX = strength
                Binding.GAMEPAD_DPAD_UP -> s.up = down
                Binding.GAMEPAD_DPAD_RIGHT -> s.right = down
                Binding.GAMEPAD_DPAD_DOWN -> s.down = down
                Binding.GAMEPAD_DPAD_LEFT -> s.left = down
                else -> {}
            }
        }
    }

    /** Lepas semua binding yang masih tertahan (layar ditutup, profil diganti, jari tertinggal). */
    fun releaseAll() {
        for (b in Binding.entries) {
            if (holds[b.ordinal] > 0) {
                holds[b.ordinal] = 1
                handle(b, false)
            }
        }
        moveX = 0f; moveY = 0f
        main.removeCallbacks(moveTick)
        moveTimer = false
        pad?.applyTouch { s ->
            // state yang sama dengan pad fisik: dilepas hanya saat profil/sesi berganti atau layar berhenti
            s.leftX = 0f; s.leftY = 0f; s.rightX = 0f; s.rightY = 0f; s.leftTrigger = 0f; s.rightTrigger = 0f
            s.up = false; s.right = false; s.down = false; s.left = false
            for (btn in intArrayOf(PadState.A, PadState.B, PadState.X, PadState.Y, PadState.LB, PadState.RB, PadState.SELECT, PadState.START, PadState.L3, PadState.R3)) s.press(btn, false)
        }
    }

    companion object {
        private const val TICK_MS = 16L
        private const val MOVE_PX_PER_TICK = 9f
    }
}
