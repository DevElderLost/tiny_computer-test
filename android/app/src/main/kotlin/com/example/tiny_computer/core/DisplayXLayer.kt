package com.example.tiny_computer.core

import android.util.Log
import java.io.File

/**
 * Lapisan Vulkan VK_LAYER_TINY_displayx (extra/displayx-layer): WSI DisplayX+ untuk aplikasi Vulkan guest glibc di atas X++.
 * Host Android mengalokasikan buffer dan mengirimnya sebagai dma-buf lewat soket abstrak "displayx" (protokol v2 di
 * displayx_protocol.hpp); lapisan mengimpornya ke Turnip. Lapisan hanya mengambil alih surface xcb/xlib yang window-nya
 * dikenal server X++ dan mematikan dirinya sendiri bila Turnip/kgsl tidak mendukung impor dma-buf atau sync_fd, jadi aman
 * dibiarkan menyala (alasannya tampil di stderr guest dengan DISPLAYX_LOG_LEVEL=2).
 */
object DisplayXLayer {
    private const val TAG = "DisplayXLayer"
    private const val GUEST_DIR = "/home/tiny/.local/share/tiny/extra/displayx"
    private const val NAME = "VK_LAYER_TINY_displayx"

    /** Lapisan hasil build CI ada di data aplikasi (disalin dari assets oleh [Rootfs.ensureVkLayer]). */
    fun available(): Boolean {
        val dir = File(TinyEnv.dataPath, "tiny/extra/displayx")
        return File(dir, "libVkLayer_tiny_displayx.so").isFile && File(dir, "VkLayer_tiny_displayx.json").isFile
    }

    /** Host hanya membuka soket DisplayX+ untuk X++ dengan backend DisplayX dan opsi DisplayX+ menyala. */
    private fun wanted(xpp: Boolean, backend: Int, plus: Boolean) = xpp && plus && backend == Cfg.XPP_DISPLAYX

    /** Format warna -> DISPLAYX_SURFACE_FORMAT (nama yang sama dengan Winlator: rgba8 / bgra8). */
    private fun surfaceFormatEnv(format: Int) = if (format == Cfg.SURFACE_BGRA) "bgra8" else "rgba8"

    /**
     * Setelan Display > VSync / present mode ([Cfg.presentMode]: 0 auto, 1 FIFO, 2 FIFO relaxed, 3 Mailbox, 4 Immediate) ->
     * DISPLAYX_PRESENT_MODE (VkPresentModeKHR 0 IMMEDIATE, 1 MAILBOX, 2 FIFO, 3 FIFO_RELAXED), dibaca lapisan guest saat
     * membuat swapchain. Auto = null: lapisan memakai mode yang diminta aplikasinya sendiri. Tanpa env ini swapchain
     * memakai pilihan aplikasi (mis. PPSSPP meminta FIFO) dan setelan di aplikasi ini tidak berpengaruh.
     */
    private fun presentModeEnv(setting: Int): String? = when (setting) {
        1 -> "2"
        2 -> "3"
        3 -> "1"
        4 -> "0"
        else -> null
    }

    /** Variabel lingkungan guest yang mengaktifkan lapisan; null bila tidak dipakai atau tidak ada di APK ini. */
    fun envList(
        xpp: Boolean = Cfg.desktopXpp, backend: Int = Cfg.xppBackend, present: Int = Cfg.presentMode,
        plus: Boolean = Cfg.xppDisplayXPlus, format: Int = Cfg.xppSurfaceFormat,
    ): List<String>? {
        if (!wanted(xpp, backend, plus)) return null
        if (!available()) {
            Log.w(TAG, "DisplayX+ aktif, tetapi lapisan tidak ada di APK ini (CI gagal membangunnya)")
            return null
        }
        return listOfNotNull(
            "VK_LAYER_PATH=$GUEST_DIR",
            "VK_INSTANCE_LAYERS=$NAME",
            "VK_LOADER_LAYERS_ENABLE=$NAME",
            presentModeEnv(present)?.let { "DISPLAYX_PRESENT_MODE=$it" },
            "DISPLAYX_SURFACE_FORMAT=${surfaceFormatEnv(format)}",
        )
    }

    /**
     * Potongan skrip shell untuk Shortcut: menambahkan lapisan ke env yang sudah ada (mis. memlimit) tanpa menimpanya dan
     * tanpa menggandakan bila env global sudah memuatnya. Null bila tidak dipakai.
     */
    fun shellExports(
        xpp: Boolean, backend: Int, present: Int = Cfg.presentMode, plus: Boolean = Cfg.xppDisplayXPlus,
        format: Int = Cfg.xppSurfaceFormat,
    ): String? {
        if (envList(xpp, backend, present, plus, format) == null) return null
        // present mode Shortcut bisa beda dari setelan global yang sudah ada di env: selalu timpa, atau hapus bila Auto
        val presentLine = presentModeEnv(present)?.let { "export DISPLAYX_PRESENT_MODE=$it" } ?: "unset DISPLAYX_PRESENT_MODE"
        return listOf(
            "case \":\${VK_INSTANCE_LAYERS:-}:\" in",
            "  *:$NAME:*) ;;",
            "  *)",
            "    export VK_LAYER_PATH=\"\${VK_LAYER_PATH:+\$VK_LAYER_PATH:}$GUEST_DIR\"",
            "    export VK_INSTANCE_LAYERS=\"\${VK_INSTANCE_LAYERS:+\$VK_INSTANCE_LAYERS:}$NAME\"",
            "    export VK_LOADER_LAYERS_ENABLE=\"\${VK_LOADER_LAYERS_ENABLE:+\$VK_LOADER_LAYERS_ENABLE,}$NAME\"",
            "    ;;",
            "esac",
            presentLine,
            "export DISPLAYX_SURFACE_FORMAT=${surfaceFormatEnv(format)}",
        ).joinToString("\n", postfix = "\n")
    }
}

/** Menggabungkan env beberapa lapisan Vulkan: VK_LAYER_PATH dan VK_INSTANCE_LAYERS dengan ':', VK_LOADER_LAYERS_ENABLE dengan ','. */
object VkLayers {
    private val separators = mapOf("VK_LAYER_PATH" to ":", "VK_INSTANCE_LAYERS" to ":", "VK_LOADER_LAYERS_ENABLE" to ",")

    fun merge(vararg parts: List<String>?): List<String>? {
        val merged = LinkedHashMap<String, String>()
        for (entry in parts.filterNotNull().flatten()) {
            val key = entry.substringBefore('=')
            val value = entry.substringAfter('=', "")
            val sep = separators[key]
            merged[key] = if (sep != null && merged.containsKey(key)) merged[key] + sep + value else value
        }
        return if (merged.isEmpty()) null else merged.map { "${it.key}=${it.value}" }
    }
}
