package com.example.tiny_computer.display

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mode touchpad: layar menjadi trackpad, kursor digerakkan relatif (bukan mengikuti jari).
 *
 * - satu jari geser: gerak kursor (percepatan ringan, sisa pecahan piksel dibawa ke gerakan berikut)
 * - ketuk: klik kiri; ketuk dua jari: klik kanan; ketuk tiga jari: klik tengah
 * - seret & lepas (drag and drop), tiga cara: ketuk lalu sentuh lagi dan geser; tekan lama lalu
 *   geser (getar tanda tombol tertahan); atau tiga jari geser. Tombol dilepas saat jari diangkat.
 * - dua jari geser: gulir halus dua sumbu (kunci sumbu bila geseran jelas ke satu arah), lalu
 *   meluncur dengan momentum bila jari diangkat sambil bergerak
 *
 * Semuanya dikirim ke compositor sebagai input scene, jalur yang sama dengan sentuh langsung. Saat
 * pointer dikunci program (game mouse-look), compositor mengubah delta ini menjadi relative_motion.
 */
class TouchpadInput(private val slopPx: Float, private val onDragStart: () -> Unit = {}) {
    /** Pengali kecepatan kursor. */
    var speed = 1.6f
    /** Pengali kecepatan gulir. */
    var scrollSpeed = 1f
    /** true: isi halaman mengikuti jari (geser ke atas = turun), seperti layar sentuh. */
    var naturalScroll = true

    private val handler = Handler(Looper.getMainLooper())
    private val pos = IntArray(2)

    private var started = false
    private var viewScale = 1f
    private var downTime = 0L        // jari pertama turun
    private var multiDownTime = 0L   // jari tambahan terakhir turun
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var pendX = 0f           // gerak di dalam slop; dilepas begitu melewatinya, tidak dibuang
    private var pendY = 0f
    private var moved = false
    private var maxPointers = 0
    private var holding = false      // tombol kiri tertahan (seret)
    private var threeFinger = false  // seret yang dimulai tiga jari
    private var quiet = false        // sisa jari setelah gulir / seret tiga jari: tidak menggerakkan apa pun
    private var lastTapUp = 0L
    private var remX = 0f            // sisa di bawah 1/256 piksel
    private var remY = 0f
    private var scrollRemV = 0f      // sisa di bawah 1/120 takik
    private var scrollRemH = 0f
    private var scrollLock = 0       // 0 belum ditentukan, 1 vertikal, 2 horizontal, 3 bebas
    private var scrollDx = 0f
    private var scrollDy = 0f
    private var velV = 0f            // kecepatan gulir, 1/120 takik per milidetik
    private var velH = 0f
    private var flingV = 0f
    private var flingH = 0f

    private val longPress = Runnable {
        if (!moved && !holding && !quiet && maxPointers == 1) {
            press()
            onDragStart()
        }
    }

    private val flingTick = object : Runnable {
        override fun run() {
            flingV *= FLING_DECAY
            flingH *= FLING_DECAY
            if (hypot(flingV, flingH) < FLING_STOP) { flingV = 0f; flingH = 0f; return }
            emitScroll(flingV * FLING_FRAME_MS, flingH * FLING_FRAME_MS)
            handler.postDelayed(this, FLING_FRAME_MS)
        }
    }

    /** Lepas tombol yang masih tertahan dan hentikan momentum (mode diganti, atau gestur dibatalkan). */
    fun cancel() {
        handler.removeCallbacks(longPress)
        stopFling()
        release()
        lastTapUp = 0L
        quiet = false
    }

    /**
     * [viewScale] = piksel layar per piksel scene (letterbox FIT), [sceneW]/[sceneH] = ukuran scene,
     * untuk menaruh kursor di tengah saat pertama dipakai.
     */
    fun onTouch(e: MotionEvent, viewScale: Float, sceneW: Int, sceneH: Int): Boolean {
        this.viewScale = viewScale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                ensureStart(sceneW, sceneH)
                downTime = e.eventTime
                multiDownTime = e.eventTime
                lastX = e.x; lastY = e.y
                lastMoveTime = e.eventTime
                resetMotion()
                maxPointers = 1
                quiet = false
                threeFinger = false
                scrollLock = 0; scrollDx = 0f; scrollDy = 0f
                scrollRemV = 0f; scrollRemH = 0f
                velV = 0f; velH = 0f
                if (lastTapUp != 0L && e.eventTime - lastTapUp < DRAG_GAP_MS) {
                    // ketuk lalu sentuh lagi: tombol kiri tertahan sampai jari diangkat
                    press()
                } else {
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                lastTapUp = 0L
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                stopFling()
                maxPointers = max(maxPointers, e.pointerCount)
                multiDownTime = e.eventTime
                quiet = false
                resetMotion()
                scrollLock = 0; scrollDx = 0f; scrollDy = 0f
                velV = 0f; velH = 0f
                lastX = centroidX(e, -1); lastY = centroidY(e, -1)
                lastMoveTime = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (holding) {
                    release()
                    lastTapUp = 0L
                } else if (!moved && !quiet && maxPointers == 1 && e.eventTime - downTime < TAP_MS) {
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

    private fun onMove(e: MotionEvent) {
        val cx = centroidX(e, -1)
        val cy = centroidY(e, -1)
        var dx = cx - lastX
        var dy = cy - lastY
        lastX = cx; lastY = cy
        val dt = max(1L, e.eventTime - lastMoveTime)
        lastMoveTime = e.eventTime
        if (quiet) return
        if (!moved) {
            pendX += dx; pendY += dy
            if (hypot(pendX, pendY) <= slopPx) return
            moved = true
            handler.removeCallbacks(longPress)
            dx = pendX; dy = pendY
            pendX = 0f; pendY = 0f
        }
        val n = e.pointerCount
        if (n >= 3 && !holding) {
            // tiga jari yang sudah bergeser: seret
            press()
            threeFinger = true
            onDragStart()
        }
        if (n >= 2 && !holding) scroll(dx, dy, dt) else movePointer(dx, dy, dt)
    }

    private fun movePointer(dx: Float, dy: Float, dt: Long) {
        val v = hypot(dx, dy) / dt // piksel per milidetik
        val accel = 1f + min(ACCEL_MAX, v * ACCEL_K)
        val k = speed * accel * 256f / max(viewScale, 0.01f) // 1/256 piksel scene per piksel layar
        val fx = dx * k + remX
        val fy = dy * k + remY
        val ix = fx.toInt()
        val iy = fy.toInt()
        remX = fx - ix
        remY = fy - iy
        if (ix != 0 || iy != 0) WaylandCompositor.nativeSendSceneInput(SCENE_RELATIVE, ix, iy)
    }

    private fun scroll(dx: Float, dy: Float, dt: Long) {
        var sx = dx
        var sy = dy
        if (scrollLock == 0) {
            // tunggu geseran yang cukup untuk memilih sumbu; yang sudah terkumpul tidak dibuang
            scrollDx += dx; scrollDy += dy
            val ax = abs(scrollDx)
            val ay = abs(scrollDy)
            if (max(ax, ay) < slopPx) return
            scrollLock = when {
                ay > ax * LOCK_RATIO -> 1
                ax > ay * LOCK_RATIO -> 2
                else -> 3
            }
            sx = scrollDx; sy = scrollDy
        }
        if (scrollLock == 1) sx = 0f else if (scrollLock == 2) sy = 0f
        val k = V120_PER_PX * scrollSpeed * (if (naturalScroll) 1f else -1f)
        val v = -sy * k
        val h = -sx * k
        emitScroll(v, h)
        velV = velV * 0.6f + (v / dt) * 0.4f
        velH = velH * 0.6f + (h / dt) * 0.4f
    }

    private fun onPointerUp(e: MotionEvent) {
        handler.removeCallbacks(longPress)
        val n = e.pointerCount // masih termasuk jari yang diangkat
        val tap = !moved && !holding && e.eventTime - multiDownTime < TAP_MS
        if (tap && n == 2 && maxPointers == 2) {
            button(BTN_RIGHT, true); button(BTN_RIGHT, false)
        } else if (tap && n == 3 && maxPointers == 3) {
            button(BTN_MIDDLE, true); button(BTN_MIDDLE, false)
        }
        if (threeFinger && n - 1 < 3) {
            release()
            quiet = true
        } else if (!holding && n - 1 < 2) {
            // dari gulir kembali ke satu jari: jari itu tidak boleh menggerakkan kursor
            if (moved && e.eventTime - lastMoveTime < FLING_IDLE_MS) startFling()
            quiet = true
        }
        // jari yang tersisa menjadi acuan baru supaya tidak ada lompatan
        lastX = centroidX(e, e.actionIndex); lastY = centroidY(e, e.actionIndex)
        scrollLock = 0; scrollDx = 0f; scrollDy = 0f
    }

    /** 1/120 takik pada tiap sumbu; sisa pecahan dibawa ke panggilan berikutnya. */
    private fun emitScroll(v: Float, h: Float) {
        scrollRemV += v
        scrollRemH += h
        val iv = scrollRemV.toInt()
        val ih = scrollRemH.toInt()
        if (iv != 0) { scrollRemV -= iv; WaylandCompositor.nativeSendSceneInput(SCENE_SMOOTH, 0, iv) }
        if (ih != 0) { scrollRemH -= ih; WaylandCompositor.nativeSendSceneInput(SCENE_SMOOTH, 1, ih) }
    }

    private fun startFling() {
        if (hypot(velV, velH) < FLING_MIN) return
        flingV = velV
        flingH = velH
        handler.postDelayed(flingTick, FLING_FRAME_MS)
    }

    private fun stopFling() {
        handler.removeCallbacks(flingTick)
        flingV = 0f
        flingH = 0f
    }

    private fun resetMotion() {
        moved = false
        pendX = 0f; pendY = 0f
    }

    private fun press() {
        holding = true
        button(BTN_LEFT, true)
    }

    private fun release() {
        if (holding) button(BTN_LEFT, false)
        holding = false
        threeFinger = false
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
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n == 0) e.x else sum / n
    }

    private fun centroidY(e: MotionEvent, skip: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n == 0) e.y else sum / n
    }

    private companion object {
        // jenis input scene compositor (lihat droiddeck_wayland_send_scene_input)
        const val SCENE_MOTION = 2
        const val SCENE_BUTTON = 3
        const val SCENE_RELATIVE = 6
        const val SCENE_SMOOTH = 9
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_MIDDLE = 274
        const val TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
        const val DRAG_GAP_MS = 300L
        const val ACCEL_K = 0.4f
        const val ACCEL_MAX = 1.2f
        const val V120_PER_PX = 3f      // 40 px jari = satu takik, seperti sebelumnya
        const val LOCK_RATIO = 2f
        const val FLING_MIN = 1f        // 1/120 takik per ms
        const val FLING_STOP = 0.05f
        const val FLING_DECAY = 0.92f
        const val FLING_FRAME_MS = 16L
        const val FLING_IDLE_MS = 60L
    }
}
