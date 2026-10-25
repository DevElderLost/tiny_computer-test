package com.example.tiny_computer.core

import android.util.Log
import java.io.File

/**
 * Batas memori Vulkan untuk aplikasi di dalam kontainer (Pengaturan > Batas memori). Dijalankan oleh lapisan Vulkan
 * VK_LAYER_TINY_memlimit (extra/vkmemlimit): memotong ukuran heap yang dilaporkan dan menolak alokasi di atas batas.
 * Batas untuk compositor sendiri ada di sisi native (vk_membudget.c) dan tidak lewat sini.
 */
object VkMemLimit {
    private const val TAG = "VkMemLimit"
    private const val GUEST_DIR = "/home/tiny/.local/share/tiny/extra/vkmemlimit"

    /** Pilihan batas (MiB); 0 = tanpa batas. */
    val STEPS = listOf(0, 512, 1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384)

    /** Lapisan hasil build CI ada di data aplikasi (disalin dari assets oleh [Rootfs.ensureVkLayer]). */
    fun available(): Boolean {
        val dir = File(TinyEnv.dataPath, "tiny/extra/vkmemlimit")
        return File(dir, "libVkLayer_tiny_memlimit.so").isFile && File(dir, "VkLayer_tiny_memlimit.json").isFile
    }

    /** Variabel lingkungan guest yang mengaktifkan lapisan dengan batas [mb] MiB; null bila tanpa batas. */
    fun envList(mb: Int = Cfg.vulkanMemMb): List<String>? {
        if (mb <= 0) return null
        if (!available()) {
            Log.w(TAG, "batas memori Vulkan $mb MB diminta, tetapi lapisan tidak ada di APK ini (CI gagal membangunnya)")
            return null
        }
        return listOf(
            "TINY_VK_MEM_LIMIT_MB=$mb",
            "VK_LAYER_PATH=$GUEST_DIR",
            "VK_INSTANCE_LAYERS=VK_LAYER_TINY_memlimit",
            "VK_LOADER_LAYERS_ENABLE=VK_LAYER_TINY_memlimit",
        )
    }

    /** Sama dengan [envList] sebagai potongan `A=1 B=2 ` untuk dilekatkan ke opsi lingkungan proot. */
    fun envOpt(): String? = envList()?.joinToString(" ")
}
