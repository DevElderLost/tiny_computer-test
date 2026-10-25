package com.example.tiny_computer.display

import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.ReshadeShaders
import com.example.tiny_computer.core.TinyPrefs

/**
 * Menerapkan efek layar compositor Wayland dari [Cfg] (HDR bloom, CAS, debanding, grading warna, FXAA,
 * ketajaman upscaler). Semua setter native boleh dipanggil kapan saja dari thread mana pun; compositor
 * memakainya pada frame berikutnya. Nilai netral = pass efek mati.
 */
object ScreenFx {
    fun apply() {
        WaylandCompositor.nativeSetHdr(Cfg.hdrEffect)
        WaylandCompositor.nativeSetCas(Cfg.casOn, Cfg.casLevel)
        WaylandCompositor.nativeSetDeband(Cfg.debandOn, Cfg.debandLevel)
        WaylandCompositor.nativeSetScreenEffects(
            Cfg.fxBrightness.toFloat(), Cfg.fxContrast.toFloat(), Cfg.fxGamma / 100f, Cfg.fxSaturation.toFloat(),
            Cfg.fxFxaa, false, false, false,
        )
        // kunci yang belum pernah diatur pengguna membiarkan bawaan compositor
        if (TinyPrefs.contains("upscaleSharpness")) WaylandCompositor.nativeSetUpscaleSharpness(Cfg.upscaleSharpness)
        if (TinyPrefs.contains("hdrSdrWhite")) WaylandCompositor.nativeSetHdrSdrWhite(Cfg.hdrSdrWhite.toFloat())
        ReshadeShaders.apply() // shader ReShade FX terpilih (Pengaturan > Display / menu sesi > Effect)
    }

    /** Present mode dan buffering swapchain layar: berlaku langsung bila compositor hidup (swapchain dibangun ulang). */
    fun applyPresentationIfRunning() {
        if (!CompositorHost.isStarted) return
        WaylandCompositor.nativeSetPresentMode(Cfg.presentMode)
        WaylandCompositor.nativeSetBuffering(Cfg.swapBuffers)
    }

    /** Batas memori Vulkan compositor: berlaku langsung (alokasi baru di atas batas ditolak). */
    fun applyMemoryLimitIfRunning() {
        if (!CompositorHost.isStarted) return
        WaylandCompositor.nativeSetMemoryLimit(Cfg.compositorMemMb)
    }

    fun applyUpscalerIfRunning() {
        if (CompositorHost.isStarted) WaylandCompositor.nativeSetUpscaler(Cfg.upscaler)
    }

    /** Dari menu sesi (tab Effect): berlaku langsung bila compositor sedang hidup, selain itu saat sesi berikutnya. */
    fun applyIfRunning() {
        if (CompositorHost.isStarted) apply()
    }

    fun reset() {
        Cfg.hdrEffect = false; Cfg.casOn = false; Cfg.debandOn = false
        Cfg.fxBrightness = 0; Cfg.fxContrast = 0; Cfg.fxGamma = 100; Cfg.fxSaturation = 100; Cfg.fxFxaa = false
        Cfg.casLevel = 50; Cfg.debandLevel = 100
        applyIfRunning()
    }
}
