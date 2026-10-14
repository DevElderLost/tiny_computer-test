package com.example.tiny_computer.display

import android.view.MotionEvent
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mode touchpad: layar menjadi trackpad, kursor digerakkan relatif (bukan mengikuti jari).
 *
 * - satu jari geser: gerak kursor (dengan percepatan ringan)
 * - ketuk: klik kiri; ketuk dua jari: klik kanan
 * - ketuk lalu sentuh lagi dan geser: seret dengan tombol kiri tertahan (ketuk dua kali = klik ganda)
 * - dua jari geser: gulir
 *
 * Semuanya dikirim ke compositor sebagai input scene, jalur yang sama dengan sentuh langsung. Saat
 * pointer dikunci program (game mouse-look), compositor mengubah delta ini menjadi relative_motion.
 */
class TouchpadInput(private val slopPx: Float) {
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var travel = 0f
    private var moved = false
    private var maxPointers = 0
    private var dragging = false
    private var lastTapUp = 0L
    private var scrollAcc = 0f
    private var started = false
    private val pos = IntArray(2)

    /** Lepas tombol yang masih tertahan (mode diganti, atau gestur dibatalkan sistem). */
    fun cancel() {
        if (dragging) button(BTN_LEFT, false)
        dragging = false
        lastTapUp = 0L
        scrollAcc = 0f
    }

    /**
     * [viewScale] = piksel layar per piksel scene (letterbox FIT), [speed] = pengali kecepatan,
     * [sceneW]/[sceneH] = ukuran scene, untuk menaruh kursor di tengah saat pertama dipakai.
     */
    fun onTouch(e: MotionEvent, viewScale: Float, speed: Float, sceneW: Int, sceneH: Int): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureStart(sceneW, sceneH)
                downTime = e.eventTime
                lastX = e.x; lastY = e.y
                lastMoveTime = e.eventTime
                travel = 0f; moved = false; maxPointers = 1; scrollAcc = 0f
                // sentuhan kedua tepat setelah ketukan: mulai seret (tombol kiri tertahan)
                if (lastTapUp != 0L && e.eventTime - lastTapUp < DRAG_GAP_MS) {
                    button(BTN_LEFT, true)
                    dragging = true
                }
                lastTapUp = 0L
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                lastX = centroidX(e, -1); lastY = centroidY(e, -1)
                scrollAcc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = centroidX(e, -1)
                val cy = centroidY(e, -1)
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx; lastY = cy
                travel += hypot(dx, dy)
                if (travel > slopPx) moved = true
                if (e.pointerCount >= 2 && !dragging) {
                    scrollAcc += -dy
                    val steps = (scrollAcc / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        scrollAcc -= steps * SCROLL_STEP_PX
                        WaylandCompositor.nativeSendSceneInput(SCENE_WHEEL, steps, 0)
                    }
                } else if (moved) {
                    val dt = max(1L, e.eventTime - lastMoveTime)
                    val v = hypot(dx, dy) / dt // piksel per milidetik
                    val accel = 1f + min(ACCEL_MAX, v * ACCEL_K)
                    val k = speed * accel * 256f / max(viewScale, 0.01f) // 1/256 piksel scene
                    val ix = (dx * k).toInt()
                    val iy = (dy * k).toInt()
                    if (ix != 0 || iy != 0) WaylandCompositor.nativeSendSceneInput(SCENE_RELATIVE, ix, iy)
                }
                lastMoveTime = e.eventTime
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // ketuk dua jari tanpa bergeser = klik kanan
                if (e.pointerCount == 2 && maxPointers == 2 && !moved && !dragging &&
                    e.eventTime - downTime < TAP_MS) {
                    button(BTN_RIGHT, true); button(BTN_RIGHT, false)
                }
                // jari yang tersisa menjadi acuan baru supaya kursor tidak melompat
                lastX = centroidX(e, e.actionIndex); lastY = centroidY(e, e.actionIndex)
                scrollAcc = 0f
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    button(BTN_LEFT, false)
                    dragging = false
                    lastTapUp = 0L
                } else if (!moved && maxPointers == 1 && e.eventTime - downTime < TAP_MS) {
                    button(BTN_LEFT, true); button(BTN_LEFT, false)
                    lastTapUp = e.eventTime
                } else {
                    lastTapUp = 0L
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    /** Kursor yang belum pernah digerakkan ada di (0,0): taruh di tengah scene dulu. */
    private fun ensureStart(sceneW: Int, sceneH: Int) {
        if (started) return
        started = true
        if (WaylandCompositor.nativeCursorPosition(pos) >= 2 && pos[0] == 0 && pos[1] == 0) {
            WaylandCompositor.nativeSendSceneInput(SCENE_MOTION, sceneW / 2, sceneH / 2)
        }
    }

    private fun button(code: Int, down: Boolean) =
        WaylandCompositor.nativeSendSceneInput(SCENE_BUTTON, code, if (down) 1 else 0)

    /** Titik tengah semua jari, tanpa jari [skip] (-1 = semua). */
    private fun centroidX(e: MotionEvent, skip: Int): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n == 0) e.x else sum / n
    }

    private fun centroidY(e: MotionEvent, skip: Int): Float {
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n == 0) e.y else sum / n
    }

    private companion object {
        // jenis input scene compositor (lihat droiddeck_wayland_send_scene_input)
        const val SCENE_MOTION = 2
        const val SCENE_BUTTON = 3
        const val SCENE_WHEEL = 4
        const val SCENE_RELATIVE = 6
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val TAP_MS = 250L
        const val DRAG_GAP_MS = 300L
        const val SCROLL_STEP_PX = 40f
        const val ACCEL_K = 0.4f
        const val ACCEL_MAX = 1.2f
    }
}
