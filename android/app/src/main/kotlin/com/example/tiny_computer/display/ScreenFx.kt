package com.example.tiny_computer.display

import android.content.Context
import android.widget.Toast
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.R
import com.example.tiny_computer.core.ReshadeShaders
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.core.TinyPrefs
import com.example.tiny_computer.xpp.XppHost

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

    /** Scale mode + Soft Stretch compositor Wayland dari [Cfg.displayFit] (Fit / Stretch / Soft Stretch). */
    fun applyDisplayMode() {
        WaylandCompositor.nativeSetScaleMode(DisplayFit.waylandScaleMode(), 0)
        WaylandCompositor.nativeSetSoftStretch(DisplayFit.mode == DisplayFit.SOFT)
    }

    /** Dari menu sesi: mode tampilan berlaku langsung di compositor Wayland (Xwayland/Wayland) dan server X++ yang hidup. */
    fun applyDisplayModeIfRunning(ctx: Context) {
        if (CompositorHost.isStarted) applyDisplayMode()
        XppHost.applyOptions(ctx)
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
        // X++ tidak memakai compositor Wayland, jadi setter native di atas tidak sampai ke layarnya. HDR dan colour grade
        // dikirim ke renderer X++ (Vulkan, EGL, Old Display); efek lain (CAS, debanding, FXAA, ReShade) belum ada di sana.
        if (XppHost.isRunning) {
            XppHost.applyOptions(TinyEnv.app)
            noteXppLimits()
        }
    }

    private var notedXppDisplayX = false
    private var notedXppPartial = false

    /** Satu kali per proses: beri tahu pengguna efek mana yang tidak berlaku di sesi X++ yang sedang berjalan. */
    private fun noteXppLimits() {
        val grading = Cfg.hdrEffect || Cfg.fxBrightness != 0 || Cfg.fxContrast != 0 || Cfg.fxGamma != 100 || Cfg.fxSaturation != 100
        if (XppHost.isDisplayX) {
            if (grading && !notedXppDisplayX) {
                notedXppDisplayX = true
                Toast.makeText(TinyEnv.app, R.string.tc_fx_xpp_displayx, Toast.LENGTH_LONG).show()
            }
        } else if ((Cfg.casOn || Cfg.debandOn || Cfg.fxFxaa) && !notedXppPartial) {
            notedXppPartial = true
            Toast.makeText(TinyEnv.app, R.string.tc_fx_xpp_partial, Toast.LENGTH_LONG).show()
        }
    }

    fun reset() {
        Cfg.hdrEffect = false; Cfg.casOn = false; Cfg.debandOn = false
        Cfg.fxBrightness = 0; Cfg.fxContrast = 0; Cfg.fxGamma = 100; Cfg.fxSaturation = 100; Cfg.fxFxaa = false
        Cfg.casLevel = 50; Cfg.debandLevel = 100
        applyIfRunning()
    }
}
