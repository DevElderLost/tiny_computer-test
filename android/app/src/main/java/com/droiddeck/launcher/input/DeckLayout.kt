package com.droiddeck.launcher.input

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/**
 * Posisi grip belakang (L4/L5/R4/R5) dan trackpad L/R identitas Steam Deck. Dipakai bersama oleh controller di sesi
 * (ControllerOverlay) dan editor tata letak (OnScreenControls dengan deckExtras), supaya keduanya menempatkan bagian ini
 * di titik yang sama. Posisi buatan pengguna disimpan di [ControllerPrefs.layout] (kunci per ukuran layar, titik tengah
 * dinormalkan 0..1) bersama kelompok tombol lain; yang belum diatur memakai [defaultCenter].
 */
object DeckLayout {
    /** [id] = kunci layout, [wDp] x [hDp] = ukuran dasar (sebelum dikali skala ukuran controller). */
    class Spec(val id: String, val label: String, val pad: Boolean, val wDp: Float, val hDp: Float)

    const val L4 = "l4"
    const val L5 = "l5"
    const val R4 = "r4"
    const val R5 = "r5"
    const val PAD_L = "padl"
    const val PAD_R = "padr"

    private const val GRIP_W = 44f
    private const val GRIP_H = GRIP_W * 0.7f
    private const val PAD_W = 112f
    private const val PAD_H = 92f

    val specs = listOf(
        Spec(L4, "L4", false, GRIP_W, GRIP_H),
        Spec(L5, "L5", false, GRIP_W, GRIP_H),
        Spec(R5, "R5", false, GRIP_W, GRIP_H),
        Spec(R4, "R4", false, GRIP_W, GRIP_H),
        Spec(PAD_L, "L", true, PAD_W, PAD_H),
        Spec(PAD_R, "R", true, PAD_W, PAD_H),
    )

    fun spec(id: String): Spec = specs.first { it.id == id }

    /** Titik tengah bawaan dalam piksel untuk layar [w] x [h], kerapatan [density], skala ukuran [scale] (1 = 100%). */
    fun defaultCenter(id: String, w: Float, h: Float, density: Float, scale: Float): Pair<Float, Float> {
        val s = GRIP_W * scale * density
        val gap = 6f * density
        val edge = 20f * density
        val gripY = 76f * density + s * 0.35f
        val padDx = 122f * scale * density
        val padY = h / 2f + 36f * scale * density
        return when (id) {
            L4 -> (edge + s / 2f) to gripY
            L5 -> (edge + s + gap + s / 2f) to gripY
            R4 -> (w - edge - s / 2f) to gripY
            R5 -> (w - edge - s - gap - s / 2f) to gripY
            PAD_L -> (w / 2f - padDx) to padY
            else -> (w / 2f + padDx) to padY
        }
    }

    /** Menjaga kotak [halfW] x [halfH] di sekitar ([x], [y]) tetap di dalam layar. */
    fun clamp(x: Float, y: Float, w: Float, h: Float, halfW: Float, halfH: Float): Pair<Float, Float> {
        val margin = 4f
        return max(halfW + margin, min(w - halfW - margin, x)) to max(halfH + margin, min(h - halfH - margin, y))
    }

    /** Titik tengah (piksel) semua bagian: nilai [saved] (normal 0..1) bila ada, selain itu [defaultCenter]. */
    fun resolve(saved: Map<String, Pair<Float, Float>>, w: Int, h: Int, density: Float, scale: Float): Map<String, Pair<Float, Float>> {
        val out = LinkedHashMap<String, Pair<Float, Float>>()
        for (s in specs) {
            val pos = saved[s.id]?.let { it.first * w to it.second * h } ?: defaultCenter(s.id, w.toFloat(), h.toFloat(), density, scale)
            out[s.id] = clamp(pos.first, pos.second, w.toFloat(), h.toFloat(), s.wDp * scale * density / 2f, s.hDp * scale * density / 2f)
        }
        return out
    }

    fun resolve(context: Context, w: Int, h: Int, density: Float, scale: Float): Map<String, Pair<Float, Float>> =
        resolve(ControllerPrefs.layout(context, w, h), w, h, density, scale)
}
