package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Shader ReShade FX (vkBasalt) untuk compositor: penyimpanan, impor, dan pergantian saat sesi berjalan.
 *
 * Tata letak mengikuti ReShade: `files/reshade/Shaders` (berkas .fx dan .fxh untuk #include) dan
 * `files/reshade/Textures` (gambar yang dirujuk `source = "lut.png"`). Impor menerima berkas .fx/.fxh, gambar,
 * atau zip paket shader (mis. reshade-shaders): isi zip dipilah ke Shaders dan Textures menurut nama folder/ekstensi.
 * Pemilihan shader disimpan di [Cfg.reshadeShader] dan dikirim ke native lewat [apply]; native mengompilasi di thread
 * pekerja dan menukar shader pada frame berikutnya, jadi aman dipanggil kapan saja, termasuk saat game berjalan.
 */
object ReshadeShaders {
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "bmp", "tga", "dds")
    private val SHADER_EXT = setOf("fx", "fxh", "h", "inc")

    fun root(): File = File(TinyEnv.dataPath, "reshade")
    fun shadersDir(): File = File(root(), "Shaders").apply { mkdirs(); ensureCore(this) }
    fun texturesDir(): File = File(root(), "Textures").apply { mkdirs() }

    /** ReShade.fxh dan ReShadeUI.fxh (CC0, dari reshade-shaders) dibawa APK: hampir semua .fx meng-#include keduanya. */
    private fun ensureCore(dir: File) {
        for (n in listOf("ReShade.fxh", "ReShadeUI.fxh")) {
            val f = File(dir, n)
            if (f.isFile) continue
            runCatching { TinyEnv.app.assets.open("reshade/$n").use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        }
    }

    /** Efek yang bisa dipilih: semua .fx di Shaders (termasuk subfolder), jalur relatif, urut abjad. */
    fun list(): List<String> {
        val base = shadersDir()
        return base.walkTopDown().filter { it.isFile && it.extension.equals("fx", true) }
            .map { it.relativeTo(base).path }.sortedBy { it.lowercase() }.toList()
    }

    fun label(rel: String): String = rel.removeSuffix(".fx").removeSuffix(".FX")

    /** Hasil impor: jumlah shader (.fx), berkas pendukung (.fxh dll.), dan tekstur. */
    data class ImportResult(val effects: Int, val includes: Int, val textures: Int)

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun import(ctx: Context, uris: List<Uri>): ImportResult {
        var fx = 0; var inc = 0; var tex = 0
        fun count(ext: String) { when { ext == "fx" -> fx++; ext in IMAGE_EXT -> tex++; else -> inc++ } }
        for (uri in uris) {
            val name = displayName(ctx, uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            val input = ctx.contentResolver.openInputStream(uri) ?: continue
            input.use {
                when {
                    ext == "zip" -> importZip(it) { e -> count(e) }
                    ext in IMAGE_EXT -> { save(it, File(texturesDir(), name.substringAfterLast('/'))); tex++ }
                    ext in SHADER_EXT -> { save(it, File(shadersDir(), name.substringAfterLast('/'))); count(ext) }
                    else -> throw IllegalArgumentException(name)
                }
            }
        }
        return ImportResult(fx, inc, tex)
    }

    /** Zip paket shader: `Shaders/...` dan `Textures/...` (di folder mana pun) dipertahankan, selain itu menurut ekstensi. */
    private fun importZip(input: InputStream, onFile: (String) -> Unit) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty() || parts.any { it == ".." }) continue // tolak zip-slip
                val ext = parts.last().substringAfterLast('.', "").lowercase()
                val si = parts.indexOfLast { it.equals("Shaders", true) }
                val ti = parts.indexOfLast { it.equals("Textures", true) }
                val dest = when {
                    ti >= 0 && ti < parts.size - 1 && ti > si -> File(texturesDir(), parts.drop(ti + 1).joinToString("/"))
                    si >= 0 && si < parts.size - 1 -> File(shadersDir(), parts.drop(si + 1).joinToString("/"))
                    ext in IMAGE_EXT -> File(texturesDir(), parts.last())
                    ext in SHADER_EXT -> File(shadersDir(), parts.last())
                    else -> continue
                }
                if (!dest.canonicalPath.startsWith(root().canonicalPath + File.separator)) continue
                save(zip, dest, close = false)
                onFile(dest.extension.lowercase())
            }
        }
    }

    private fun save(input: InputStream, dest: File, close: Boolean = true) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.outputStream().use { input.copyTo(it) }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        if (close) input.close()
    }

    fun remove(rel: String) {
        File(shadersDir(), rel).delete()
        if (Cfg.reshadeShader == rel) select("")
    }

    /** Kirim pilihan saat ini ke native (compositor yang sedang hidup). Kosong = efek dimatikan. */
    fun apply() {
        val rel = Cfg.reshadeShader
        val f = if (rel.isEmpty()) null else File(shadersDir(), rel)
        if (f != null && f.isFile) WaylandCompositor.nativeSetReshade(f.absolutePath, shadersDir().absolutePath, texturesDir().absolutePath)
        else WaylandCompositor.nativeSetReshade("", "", "")
    }

    fun applyIfRunning() {
        if (CompositorHost.isStarted) apply()
    }

    /** Ganti shader secara real-time (menu sesi) atau untuk sesi berikutnya (pengaturan). */
    fun select(rel: String) {
        Cfg.reshadeShader = rel
        applyIfRunning()
    }

    /** "off", "compiling X", "ok: N pass(es), X" atau "error: ..." dari compositor. */
    fun status(): String = if (CompositorHost.isStarted) runCatching { WaylandCompositor.nativeReshadeStatus() }.getOrDefault("") else ""
}
