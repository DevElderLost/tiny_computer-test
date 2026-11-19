package com.example.tiny_computer.display

import com.example.tiny_computer.core.Cfg
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mode tampilan layar ala Winlator Ludashi, sama untuk semua display server (Xwayland, Wayland, X++):
 *  - [FIT]: gambar diletakkan di tengah dengan rasio asli (letterbox),
 *  - [STRETCH]: gambar memenuhi seluruh layar,
 *  - [SOFT]: Stretch dengan pelebaran horizontal non-linear (kurva window_stretch.frag: strength 0.40, profile 0.60),
 *    tepi kiri/kanan lebih terentang daripada tengah.
 * Objek ini juga memetakan sentuhan layar -> piksel scene dan sebaliknya, supaya kursor dan jari tetap tepat di mode mana pun.
 */
object DisplayFit {
    const val FIT = Cfg.FIT_FIT
    const val STRETCH = Cfg.FIT_STRETCH
    const val SOFT = Cfg.FIT_SOFT

    private const val SOFT_STRENGTH = 0.40f
    private const val SOFT_PROFILE = 0.60f

    val mode: Int get() = Cfg.displayFit

    /** Nilai scale mode compositor Wayland: 1 = FIT, 2 = STRETCH (Soft memakai STRETCH + [nativeSetSoftStretch]). */
    fun waylandScaleMode(): Int = if (mode == FIT) 1 else 2

    /** Kurva Soft Stretch: posisi output 0..1 -> posisi sumber 0..1 (sama dengan shader). */
    private fun warp(t: Float): Float {
        val x = t * 2f - 1f
        val x2 = x * x
        val x5 = x2 * x2 * x
        val sinShape = sin(0.5f * PI.toFloat() * x)
        val quintic = 0.5f * x5 - 1.5f * x2 * x + 2f * x
        val curved = sinShape * (1f - SOFT_PROFILE) + quintic * SOFT_PROFILE
        val warped = x + (curved - x) * SOFT_STRENGTH
        return (0.5f * warped + 0.5f).coerceIn(0f, 1f)
    }

    /** Kebalikan [warp] (monoton naik): posisi sumber 0..1 -> posisi output 0..1, lewat bagi dua. */
    private fun unwarp(u: Float): Float {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (warp(mid) < u) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** Piksel layar per piksel scene (satu angka untuk kecepatan kursor/ukuran kursor). */
    fun viewScale(viewW: Float, viewH: Float, sceneW: Int, sceneH: Int): Float {
        val sx = viewW / sceneW
        val sy = viewH / sceneH
        return if (mode == FIT) min(sx, sy) else sqrt(sx * sy)
    }

    /** Koordinat X layar -> piksel X scene. */
    fun sceneX(vx: Float, viewW: Float, viewH: Float, sceneW: Int, sceneH: Int): Int {
        val sx = when (mode) {
            FIT -> {
                val s = min(viewW / sceneW, viewH / sceneH)
                (vx - (viewW - sceneW * s) / 2f) / s
            }
            STRETCH -> vx / viewW * sceneW
            else -> warp((vx / viewW).coerceIn(0f, 1f)) * sceneW
        }
        return sx.toInt().coerceIn(0, sceneW - 1)
    }

    /** Koordinat Y layar -> piksel Y scene. */
    fun sceneY(vy: Float, viewW: Float, viewH: Float, sceneW: Int, sceneH: Int): Int {
        val sy = if (mode == FIT) {
            val s = min(viewW / sceneW, viewH / sceneH)
            (vy - (viewH - sceneH * s) / 2f) / s
        } else {
            vy / viewH * sceneH
        }
        return sy.toInt().coerceIn(0, sceneH - 1)
    }

    /** Piksel X scene (boleh pecahan) -> koordinat X layar; kebalikan [sceneX], untuk menggambar kursor. */
    fun screenX(px: Float, viewW: Float, viewH: Float, sceneW: Int, sceneH: Int): Float = when (mode) {
        FIT -> {
            val s = min(viewW / sceneW, viewH / sceneH)
            (viewW - sceneW * s) / 2f + px * s
        }
        STRETCH -> px / sceneW * viewW
        else -> unwarp((px / sceneW).coerceIn(0f, 1f)) * viewW
    }

    /** Piksel Y scene -> koordinat Y layar. */
    fun screenY(py: Float, viewW: Float, viewH: Float, sceneW: Int, sceneH: Int): Float =
        if (mode == FIT) {
            val s = min(viewW / sceneW, viewH / sceneH)
            (viewH - sceneH * s) / 2f + py * s
        } else {
            py / sceneH * viewH
        }
}
