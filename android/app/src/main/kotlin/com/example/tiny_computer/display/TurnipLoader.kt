package com.example.tiny_computer.display

import android.content.Context
import android.os.Build
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File

/**
 * Driver Vulkan yang dipakai compositor. Harus Turnip (build bionic lewat adrenotools): driver
 * sistem Adreno tidak punya VK_EXT_image_drm_format_modifier sehingga dmabuf Xwayland tidak bisa
 * diimpor. Dua build ikut di APK (Adreno 7xx dan 8xx); GPU menentukan yang dibuka.
 * Ringkasan dari TurnipDriver.java (DroidDeck).
 */
class TurnipLoader(private val context: Context) {
    private val contentDir = File(context.filesDir, "graphics_driver")

    fun driverPath(id: String) = File(contentDir, id).absolutePath + "/"

    fun libraryName(id: String): String? = runCatching {
        JSONObject(File(File(contentDir, id), "meta.json").readText()).optString("libraryName", "").ifEmpty { null }
    }.getOrNull()

    /** Id driver yang siap dipakai, atau null (jatuh ke libvulkan sistem). */
    fun install(preferred: String): String? {
        val id = if (preferred.isNotEmpty() && File(contentDir, preferred).isDirectory) preferred else chooseByDevice()
        val dir = File(contentDir, id)
        if (!File(dir, "meta.json").isFile) {
            dir.deleteRecursively()
            if (!extract("graphics_driver/adrenotools-$id.tzst", dir)) {
                dir.deleteRecursively()
                return null
            }
        }
        val lib = libraryName(id)
        if (lib == null || !File(dir, lib).isFile) {
            Log.e(TAG, "$id dibuka tanpa $lib")
            return null
        }
        Log.i(TAG, "driver grafis $id ($lib)")
        return id
    }

    private fun chooseByDevice(): String {
        val model = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
            .firstNotNullOfOrNull { runCatching { File(it).readText().trim() }.getOrNull()?.takeIf { s -> s.isNotEmpty() } }
        Log.i(TAG, "gpu model: ${model ?: "tidak diketahui"}")
        val gen = model?.let { Regex("(\\d)\\d\\d").find(it)?.groupValues?.get(1)?.get(0) }
        return when {
            gen != null && gen >= '8' -> A8XX
            gen == '7' -> A7XX
            else -> if (Build.VERSION.SDK_INT >= 36) A8XX else A7XX
        }
    }

    private fun extract(asset: String, dest: File): Boolean = try {
        dest.mkdirs()
        context.assets.open(asset).use { raw ->
            TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
                var e = tar.nextTarEntry
                while (e != null) {
                    val out = File(dest, e.name).canonicalFile
                    if (out.path.startsWith(dest.canonicalPath + File.separator)) {
                        if (e.isDirectory) out.mkdirs()
                        else if (e.isFile) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                            if (e.mode and 0b001001001 != 0) out.setExecutable(true, false)
                        }
                    }
                    e = tar.nextTarEntry
                }
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "extract $asset", e)
        false
    }

    companion object {
        private const val TAG = "TurnipLoader"
        private const val A7XX = "turnip25.1.0"
        private const val A8XX = "turnip-sdk36"
    }
}
