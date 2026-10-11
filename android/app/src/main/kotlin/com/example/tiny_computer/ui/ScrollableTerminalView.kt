package com.example.tiny_computer.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.termux.view.TerminalView
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.math.ceil

/**
 * TerminalView yang bisa digulir. Di versi lama dua hal menggagalkannya:
 *  1. `update = { it.onScreenUpdated() }` di AndroidView dipanggil setiap rekomposisi, dan
 *     onScreenUpdated() Termux selalu mengembalikan tampilan ke baris terbawah;
 *  2. gerakan geser di dalam Compose hanya ditangani GestureDetector bawaan yang mudah dibatalkan.
 *
 * Di sini geser satu jari secara vertikal mengubah `mTopRow` langsung (jangkauan = riwayat
 * transkrip), dan selama pengguna sedang menggulir ke atas, keluaran baru tidak menyeretnya kembali.
 * Buffer alternatif (vim, htop) dan mouse tracking tetap ditangani Termux. Bila refleksi ke
 * `mTopRow` gagal, perilaku kembali ke bawaan Termux.
 */
class ScrollableTerminalView(context: Context) : TerminalView(context, null) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val topRowField: Field? = runCatching {
        TerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }
    }.onFailure { Log.w(TAG, "mTopRow tidak bisa diakses, gulir manual nonaktif", it) }.getOrNull()

    private var rowPx = 0f
    private var downY = 0f
    private var lastY = 0f
    private var remainder = 0f
    private var dragging = false
    private var userScrolled = false

    private fun topRow(): Int = runCatching { topRowField?.getInt(this) }.getOrNull() ?: 0
    private fun setTopRow(v: Int) { runCatching { topRowField?.setInt(this, v) } }

    private fun rowHeight(): Float {
        if (rowPx <= 0f) rowPx = 24f
        return rowPx
    }

    // sama dengan TerminalRenderer: Typeface.MONOSPACE, tinggi baris = ceil(fontSpacing)
    override fun setTextSize(textSize: Int) {
        super.setTextSize(textSize)
        rowPx = ceil(Paint().apply { typeface = Typeface.MONOSPACE; this.textSize = textSize.toFloat() }.fontSpacing)
    }

    /** Kembali ke baris terbawah (dipanggil saat pengguna mengetik / menekan tombol). */
    fun scrollToBottom() {
        if (userScrolled || topRow() != 0) {
            setTopRow(0)
            userScrolled = false
            invalidate()
        }
    }

    override fun onScreenUpdated() {
        if (userScrolled && topRow() < 0) { invalidate(); return }
        userScrolled = false
        super.onScreenUpdated()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val emu = currentSession?.emulator
        if (topRowField == null || emu == null || emu.isAlternateBufferActive || emu.isMouseTrackingActive || event.pointerCount > 1) {
            dragging = false
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y; lastY = event.y; remainder = 0f; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // batalkan gerakan di GestureDetector Termux agar tidak jadi ketukan / zoom
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dragging) {
                    scrollBy(event.y - lastY, emu.screen.activeTranscriptRows)
                    lastY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Jari turun = isi bergerak turun = riwayat lebih lama terlihat (mTopRow makin negatif). */
    private fun scrollBy(dy: Float, history: Int) {
        remainder += dy
        val h = rowHeight()
        val rows = (remainder / h).toInt()
        if (rows == 0) return
        remainder -= rows * h
        val next = (topRow() - rows).coerceIn(-history, 0)
        setTopRow(next)
        userScrolled = next < 0
        invalidate()
    }

    private companion object { const val TAG = "ScrollTerminalView" }
}
