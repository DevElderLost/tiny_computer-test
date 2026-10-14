package com.example.tiny_computer.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlin.math.max
import kotlin.math.min

/**
 * Menggambar kursor bawaan rootfs di atas SurfaceView.
 *
 * Compositor tidak menggabungkan kursor ke scene (jalur zero-copy mengirim buffer program langsung ke
 * layer Android). Ia hanya menyimpan gambar dari wl_pointer.set_cursor (kursor tema X11 milik Xwayland)
 * dan posisi pointer; lapisan inilah yang menggambarnya. Tanpa lapisan ini kursor rootfs tidak pernah
 * terlihat. View ini tidak menerima sentuhan: semuanya diteruskan ke SurfaceView di bawahnya.
 */
class CursorView(
    context: Context,
    private val ui: DisplayUiState,
    private val sceneSize: () -> Pair<Int, Int>,
) : View(context), Choreographer.FrameCallback {
    private val pos = IntArray(2)
    private var px16 = 0
    private var py16 = 0
    private var lastSerial = -1
    private var lastShow = true
    private var lastScale = 1f
    private var hidden = true
    private var clientDecided = false
    private var bitmap: Bitmap? = null
    private var hotX = 0
    private var hotY = 0
    private var scratch: IntArray? = null
    private var running = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val arrow = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        poll()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun poll() {
        var dirty = false
        val show = ui.showCursor
        val scale = ui.cursorScale
        if (show != lastShow || scale != lastScale) { lastShow = show; lastScale = scale; dirty = true }
        if (!show) { if (dirty) invalidate(); return }

        val serial = WaylandCompositor.nativeCursorSerial()
        if (serial != lastSerial) {
            lastSerial = serial
            refreshImage()
            dirty = true
        }
        if (WaylandCompositor.nativeCursorPosition(pos) >= 2 && (pos[0] != px16 || pos[1] != py16)) {
            px16 = pos[0]; py16 = pos[1]
            dirty = true
        }
        if (dirty) invalidate()
    }

    /** Ambil gambar kursor terbaru dari compositor (hanya saat serial berubah). */
    private fun refreshImage() {
        val buf = scratch ?: IntArray(WaylandCompositor.CURSOR_BUF_INTS).also { scratch = it }
        val n = WaylandCompositor.nativeCursorSnapshot(buf)
        if (n < 6) return
        clientDecided = buf[0] != 0
        hidden = buf[1] != 0
        val w = buf[2]
        val h = buf[3]
        hotX = buf[4]
        hotY = buf[5]
        if (hidden || w <= 0 || h <= 0 || n < 6 + w * h) { bitmap = null; return }
        // piksel dari compositor ARGB ter-premultiply; Bitmap.setPixels meminta yang tidak
        val argb = IntArray(w * h)
        for (i in argb.indices) {
            val c = buf[6 + i]
            val a = c ushr 24
            argb[i] = if (a == 0 || a == 255) c else {
                val r = min(255, ((c shr 16) and 0xFF) * 255 / a)
                val g = min(255, ((c shr 8) and 0xFF) * 255 / a)
                val b = min(255, (c and 0xFF) * 255 / a)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(argb, 0, w, 0, 0, w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        if (!ui.showCursor || hidden && clientDecided) return
        val (sw, sh) = sceneSize()
        if (sw <= 0 || sh <= 0 || width <= 0 || height <= 0) return
        // sama dengan pemetaan sentuhan di DisplayActivity: scene diletakkan FIT di tengah layar
        val s = min(width.toFloat() / sw, height.toFloat() / sh)
        val cx = (width - sw * s) / 2f + px16 / 16f * s
        val cy = (height - sh * s) / 2f + py16 / 16f * s
        val k = max(0.1f, s * ui.cursorScale)
        val bmp = bitmap
        if (bmp != null) {
            val l = cx - hotX * k
            val t = cy - hotY * k
            canvas.drawBitmap(bmp, null, RectF(l, t, l + bmp.width * k, t + bmp.height * k), bmpPaint)
        } else if (!clientDecided) {
            // belum ada program yang menyebut kursor sama sekali: anak panah cadangan supaya mode
            // touchpad tetap bisa dipakai
            arrow.reset()
            arrow.moveTo(cx, cy)
            arrow.lineTo(cx, cy + 17f * k)
            arrow.lineTo(cx + 4.5f * k, cy + 13f * k)
            arrow.lineTo(cx + 7.5f * k, cy + 19.5f * k)
            arrow.lineTo(cx + 10f * k, cy + 18.5f * k)
            arrow.lineTo(cx + 7f * k, cy + 12f * k)
            arrow.lineTo(cx + 12f * k, cy + 12f * k)
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        }
    }
}
