package com.example.tiny_computer.display

import android.view.Choreographer
import com.example.tiny_computer.xpp.XppHost
import kotlin.math.abs
import kotlin.math.min

/**
 * Menggeser tampilan ke atas selama keyboard PC di layar muncul, lalu membuatnya mengikuti kursor (logika layar AVNC:
 * `LayoutManager` + `PointerModes.doMovePointer`). Di AVNC keyboard mengecilkan viewport tanpa mengubah skala frame,
 * frame hanya bisa digeser di dalam area aman, dan tiap kursor bergerak frame digeser supaya kursor tetap di tengah
 * area aman. Di sini keyboard adalah overlay di tepi bawah, jadi area aman = tinggi layar dikurangi tinggi keyboard,
 * dan "geser frame" = menggeser View layar ([onPan] = translationY), sehingga berlaku sama untuk semua mode display
 * (X++ Vulkan / EGL / Old Display / DisplayX, dan compositor Wayland) tanpa menyentuh renderer.
 *
 * Rentang geser 0 .. -tinggi keyboard: bagian bawah layar tidak pernah tertarik sampai ke atas keyboard.
 * Kursor dibaca lewat [SceneInput.cursorPosition], jadi gerak dari touchpad, controller, atau mouse sama-sama diikuti.
 *
 * Selama jari menyentuh langsung ([directContact], mode sentuh langsung atau mouse mutlak) tampilan ditahan: kursor
 * berada di bawah jari, menggeser tampilan saat itu membuat kursor lari dari jarinya.
 */
class KeyboardPan(
    private val ui: DisplayUiState,
    /** Lebar dan tinggi View layar (tanpa pergeseran). */
    private val viewSize: () -> Pair<Float, Float>,
    private val scene: () -> Pair<Int, Int>,
    private val directContact: () -> Boolean,
    /** Dipanggil dengan pergeseran vertikal baru (<= 0) setiap berubah. */
    private val onPan: (Float) -> Unit,
) : Choreographer.FrameCallback {
    private val pos = IntArray(2)
    private var running = false
    private var panY = 0f
    private var applied = 0f
    private var target = 0f
    private var tracked = false
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Berhenti dan kembalikan tampilan ke posisi semula (layar tidak tampil). */
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        tracked = false
        target = 0f
        panY = 0f
        if (applied != 0f) { applied = 0f; onPan(0f) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        step()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step() {
        val kb = if (ui.pcKeyboard) ui.pcKeyboardHeight.toFloat() else 0f
        if (kb <= 0f && panY == 0f && target == 0f) { tracked = false; return }
        val (vw, vh) = viewSize()
        val limit = min(kb, vh * MAX_PAN_FRACTION)
        if (limit <= 0f || vw <= 0f || vh <= 0f) {
            target = 0f
            tracked = false
        } else if (SceneInput.cursorPosition(pos) >= 2) {
            // posisi kursor: compositor Wayland melaporkan piksel scene x16, server X++ piksel scene
            val k = if (XppHost.displayAttached) 1f else 16f
            val changed = pos[0] != lastX || pos[1] != lastY
            lastX = pos[0]
            lastY = pos[1]
            // pertama kali keyboard muncul langsung diarahkan ke kursor; setelah itu hanya saat kursor bergerak
            if (!directContact() && (!tracked || changed)) {
                val (sw, sh) = scene()
                val cy = DisplayFit.screenY(pos[1] / k, vw, vh, sw, sh)
                // kursor di tengah area di atas keyboard, dibatasi 0 .. -limit (sama dengan coercePosition AVNC)
                target = ((vh - kb) / 2f - cy).coerceIn(-limit, 0f)
                tracked = true
            }
        }
        val d = target - panY
        panY = if (abs(d) < SNAP_PX) target else panY + d * SMOOTH
        if (panY != applied) { applied = panY; onPan(panY) }
    }

    private companion object {
        /** Bagian dari fraksi tinggi layar yang paling banyak boleh tergeser. */
        const val MAX_PAN_FRACTION = 0.8f
        const val SMOOTH = 0.35f
        const val SNAP_PX = 0.5f
    }
}
