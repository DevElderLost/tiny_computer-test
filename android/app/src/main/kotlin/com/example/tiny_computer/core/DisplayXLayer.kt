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
    private fun wanted(xpp: Boolean, backend: Int) = xpp && Cfg.xppDisplayXPlus && backend == Cfg.XPP_DISPLAYX

    /** Variabel lingkungan guest yang mengaktifkan lapisan; null bila tidak dipakai atau tidak ada di APK ini. */
    fun envList(xpp: Boolean = Cfg.desktopXpp, backend: Int = Cfg.xppBackend): List<String>? {
        if (!wanted(xpp, backend)) return null
        if (!available()) {
            Log.w(TAG, "DisplayX+ aktif, tetapi lapisan tidak ada di APK ini (CI gagal membangunnya)")
            return null
        }
        return listOf(
            "VK_LAYER_PATH=$GUEST_DIR",
            "VK_INSTANCE_LAYERS=$NAME",
            "VK_LOADER_LAYERS_ENABLE=$NAME",
        )
    }

    /**
     * Potongan skrip shell untuk Shortcut: menambahkan lapisan ke env yang sudah ada (mis. memlimit) tanpa menimpanya dan
     * tanpa menggandakan bila env global sudah memuatnya. Null bila tidak dipakai.
     */
    fun shellExports(xpp: Boolean, backend: Int): String? {
        if (envList(xpp, backend) == null) return null
        return listOf(
            "case \":\${VK_INSTANCE_LAYERS:-}:\" in",
            "  *:$NAME:*) ;;",
            "  *)",
            "    export VK_LAYER_PATH=\"\${VK_LAYER_PATH:+\$VK_LAYER_PATH:}$GUEST_DIR\"",
            "    export VK_INSTANCE_LAYERS=\"\${VK_INSTANCE_LAYERS:+\$VK_INSTANCE_LAYERS:}$NAME\"",
            "    export VK_LOADER_LAYERS_ENABLE=\"\${VK_LOADER_LAYERS_ENABLE:+\$VK_LOADER_LAYERS_ENABLE,}$NAME\"",
            "    ;;",
            "esac",
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
