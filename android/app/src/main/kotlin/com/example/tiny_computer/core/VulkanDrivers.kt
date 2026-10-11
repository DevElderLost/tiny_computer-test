package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Impor driver Vulkan seperti DroidDeck (gpu/LinuxVulkanDriverManager, gpu/TurnipDriver,
 * gpu/DriverBundle), dua jenis yang TIDAK bisa saling menggantikan:
 *
 *  - Linux (glibc): libvulkan_freedreno.so yang dimuat proses DI DALAM kontainer. Dipasang ke
 *    `files/linux_vulkan_drivers/<id>/` bersama `icd.json` (library_path absolut). Folder `files`
 *    terlihat dari guest lewat `--mount=/data`, jadi ICD-nya dibaca guest di path host yang sama.
 *  - Android (bionic, adrenotools): dimuat compositor aplikasi untuk menampilkan frame. Dipasang
 *    ke `files/graphics_driver/<id>/` (folder yang sama dengan yang dibaca [com.example.tiny_computer.display.TurnipLoader]).
 *
 * Yang bisa diimpor: zip satu driver (Linux atau Android, jenisnya dikenali dari isi: libc.so.6 =
 * glibc), zip bundel DroidDeck (`manifest.json` dengan dua bagian `android` + `linux`), dan
 * `.tzst`/`.tar.zst` adrenotools.
 */
object VulkanDrivers {
    private const val TAG = "VulkanDrivers"
    const val LIB = "libvulkan_freedreno.so"
    private const val ICD = "icd.json"
    private const val META = "meta.json"
    private const val MAX_BYTES = 512L shl 20

    class Result(val linuxId: String?, val androidId: String?)

    fun linuxRoot(ctx: Context) = File(ctx.filesDir, "linux_vulkan_drivers")
    fun androidRoot(ctx: Context) = File(ctx.filesDir, "graphics_driver")

    fun installedLinux(ctx: Context): List<String> =
        linuxRoot(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && isLinuxInstalled(ctx, it.name) }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun installedAndroid(ctx: Context): List<String> =
        androidRoot(ctx).listFiles()?.filter { d ->
            d.isDirectory && !d.name.startsWith(".") && runCatching {
                val lib = JSONObject(File(d, META).readText()).optString("libraryName", "")
                lib.isNotEmpty() && File(d, lib).isFile
            }.getOrDefault(false)
        }?.map { it.name }?.sorted() ?: emptyList()

    private fun validId(id: String) = id.isNotEmpty() && !id.contains('/') && !id.contains("..")

    fun isLinuxInstalled(ctx: Context, id: String): Boolean {
        if (!validId(id)) return false
        val d = File(linuxRoot(ctx), id)
        return File(d, LIB).isFile && File(d, ICD).isFile
    }

    /** Path ICD driver Linux terpilih, atau null (otomatis / driver yang dipilih sudah hilang). */
    fun activeIcd(ctx: Context): String? {
        val id = Cfg.linuxDriver
        return if (isLinuxInstalled(ctx, id)) File(File(linuxRoot(ctx), id), ICD).absolutePath else null
    }

    fun label(ctx: Context, id: String, linux: Boolean): String {
        val meta = runCatching { JSONObject(File(File(if (linux) linuxRoot(ctx) else androidRoot(ctx), id), META).readText()) }.getOrNull()
        val name = meta?.optString("name", "").orEmpty().ifEmpty { id }
        val ver = meta?.optString("driverVersion", "").orEmpty()
        return if (ver.isEmpty() || name.contains(ver)) name else "$name $ver"
    }

    fun removeLinux(ctx: Context, id: String) {
        if (!validId(id)) return
        File(linuxRoot(ctx), id).deleteRecursively()
        if (Cfg.linuxDriver == id) Cfg.linuxDriver = ""
    }

    fun removeAndroid(ctx: Context, id: String) {
        if (!validId(id)) return
        File(androidRoot(ctx), id).deleteRecursively()
        if (Cfg.androidDriver == id) Cfg.androidDriver = ""
    }

    fun displayName(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "driver"

    /**
     * Pasang driver dari [uri]. Mengembalikan id yang terpasang; melempar [IllegalArgumentException]
     * (pesan siap tampil) bila isinya bukan driver yang bisa dipakai, [IOException] bila gagal baca.
     */
    fun importDriver(ctx: Context, uri: Uri): Result {
        val name = displayName(ctx, uri)
        val stage = File(ctx.cacheDir, "drv-${System.nanoTime()}")
        try {
            stage.mkdirs()
            extract(ctx, uri, name, stage)
            var linuxId: String? = null
            var androidId: String? = null

            val manifest = File(stage, "manifest.json").takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val drivers = manifest?.optJSONObject("drivers")
            val aPath = drivers?.optJSONObject("android")?.optString("path").orEmpty()
            val lPath = drivers?.optJSONObject("linux")?.optString("path").orEmpty()
            val title = manifest?.optString("package").orEmpty().ifEmpty { name }
            if (aPath.isNotEmpty() && lPath.isNotEmpty()) {
                androidId = installAndroid(ctx, safeDir(stage, aPath), title)
                    ?: throw IllegalArgumentException("Bagian Android bundel tidak valid (meta.json + library bionic aarch64).")
                linuxId = installLinux(ctx, safeDir(stage, lPath), title)
                    ?: run { removeAndroid(ctx, androidId!!); throw IllegalArgumentException("Bagian Linux bundel tidak valid (libvulkan_freedreno.so glibc aarch64).") }
            } else {
                val dirs = stage.walkTopDown().filter { it.isDirectory }.filter { d ->
                    d.listFiles()?.any { it.isFile && (it.name == META || it.name.startsWith("libvulkan_freedreno")) } == true
                }.toList()
                for (d in dirs) {
                    if (androidId == null) androidId = installAndroid(ctx, d, name)
                    if (androidId == null && linuxId == null) linuxId = installLinux(ctx, d, name)
                }
            }
            if (linuxId == null && androidId == null) {
                throw IllegalArgumentException("Bukan driver yang dikenali. Butuh zip/tzst Turnip: Linux (glibc, libvulkan_freedreno.so) atau Android (adrenotools, meta.json + libraryName).")
            }
            Log.i(TAG, "diimpor $name: linux=$linuxId android=$androidId")
            return Result(linuxId, androidId)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun safeDir(root: File, rel: String): File {
        val d = File(root, rel.trim().trim('/')).canonicalFile
        require(d.path.startsWith(root.canonicalPath + File.separator) && d.isDirectory) { "Path di manifest tidak valid: $rel" }
        return d
    }

    // ------------------------------------------------------------------ ekstraksi aman (anti zip-slip, batas ukuran)

    private fun extract(ctx: Context, uri: Uri, name: String, dest: File) {
        val tar = name.endsWith(".tzst", true) || name.endsWith(".tar.zst", true)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("tidak bisa membuka $uri")
        var total = 0L
        val root = dest.canonicalFile
        fun sink(entryName: String, copy: (java.io.OutputStream) -> Unit) {
            val out = File(root, entryName).canonicalFile
            if (!out.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("Entri di luar folder tujuan: $entryName")
            out.parentFile?.mkdirs()
            out.outputStream().use(copy)
        }
        fun guard(n: Long) { total += n; if (total > MAX_BYTES) throw IllegalArgumentException("Arsip terlalu besar (>${MAX_BYTES shr 20} MB)") }
        input.use { raw ->
            if (tar) {
                TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { t ->
                    var e = t.nextTarEntry
                    while (e != null) {
                        if (e.isFile) sink(e.name) { o -> val b = ByteArray(1 shl 16); while (true) { val r = t.read(b); if (r <= 0) break; guard(r.toLong()); o.write(b, 0, r) } }
                        e = t.nextTarEntry
                    }
                }
            } else {
                ZipInputStream(raw).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) sink(e.name) { o -> val b = ByteArray(1 shl 16); while (true) { val r = z.read(b); if (r <= 0) break; guard(r.toLong()); o.write(b, 0, r) } }
                        e = z.nextEntry
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Linux (glibc)

    private fun installLinux(ctx: Context, dir: File, displayName: String): String? {
        val so = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("libvulkan_freedreno") && it.name.endsWith(".so") } ?: return null
        if (!isAarch64Elf(so)) return null
        if (!containsAscii(so, "libc.so.6")) return null // build bionic = driver Android, bukan Linux
        val zipMeta = File(dir, META).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val name = zipMeta?.optString("name", "").orEmpty().ifEmpty { displayName.removeSuffix(".zip").removeSuffix(".tzst").removeSuffix(".tar.zst") }
        val root = linuxRoot(ctx).apply { mkdirs() }
        val id = uniqueId(root, sanitize(name))
        val tmp = File(root, ".tmp-${System.nanoTime()}").apply { mkdirs() }
        return try {
            so.copyTo(File(tmp, LIB), overwrite = true)
            val finalLib = File(File(root, id), LIB).absolutePath
            File(tmp, ICD).writeText(
                JSONObject().put("file_format_version", "1.0.0")
                    .put("ICD", JSONObject().put("library_path", finalLib).put("api_version", "1.1.274")).toString(2),
            )
            File(tmp, META).writeText(
                JSONObject().put("kind", "linux-vulkan-icd").put("name", name)
                    .put("driverVersion", zipMeta?.optString("driverVersion", "").orEmpty())
                    .put("libc", "glibc").put("importedAt", System.currentTimeMillis()).toString(2),
            )
            val target = File(root, id)
            if (!tmp.renameTo(target)) throw IOException("tidak bisa memindahkan ke $target")
            // proses guest berjalan sebagai uid yang sama, tapi tetap buat dapat dibaca/dieksekusi
            target.setExecutable(true, false); target.setReadable(true, false)
            File(target, LIB).apply { setReadable(true, false); setExecutable(true, false) }
            File(target, ICD).setReadable(true, false)
            id
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ Android (bionic, adrenotools)

    private fun installAndroid(ctx: Context, dir: File, displayName: String): String? {
        val metaFile = File(dir, META).takeIf { it.isFile } ?: return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        if (meta.optString("kind") == "linux-vulkan-icd") return null
        val libName = meta.optString("libraryName", "")
        if (libName.isEmpty()) return null
        val lib = File(dir, libName).takeIf { it.isFile } ?: return null
        if (!isAarch64Elf(lib) || containsAscii(lib, "libc.so.6")) return null
        val name = meta.optString("name", "").ifEmpty { displayName.removeSuffix(".zip").removeSuffix(".tzst").removeSuffix(".tar.zst") }
        val root = androidRoot(ctx).apply { mkdirs() }
        val id = uniqueId(root, sanitize(name))
        val tmp = File(root, ".tmp-${System.nanoTime()}").apply { mkdirs() }
        return try {
            dir.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
            val target = File(root, id)
            if (!tmp.renameTo(target)) throw IOException("tidak bisa memindahkan ke $target")
            File(target, libName).apply { setReadable(true, false); setExecutable(true, false) }
            id
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ util

    private fun uniqueId(root: File, base: String): String {
        var id = base
        var n = 2
        while (File(root, id).exists()) id = "$base-${n++}"
        return id
    }

    private fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").replace(Regex("^[._]+"), "").take(64)
        return s.ifEmpty { "driver" }
    }

    /** Magic ELF, 64-bit, little-endian, e_machine = 0xB7 (AArch64). */
    private fun isAarch64Elf(f: File): Boolean = runCatching {
        FileInputStream(f).use { i ->
            val h = ByteArray(20)
            var n = 0
            while (n < h.size) { val r = i.read(h, n, h.size - n); if (r < 0) break; n += r }
            n >= 20 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4].toInt() == 2 && h[5].toInt() == 1 && ((h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)) == 0xB7
        }
    }.getOrDefault(false)

    /** Pemindaian byte untuk soname: "libc.so.6" (glibc, berversi) vs "libc.so" (bionic). */
    private fun containsAscii(f: File, needle: String): Boolean = runCatching {
        val nb = needle.toByteArray(Charsets.US_ASCII)
        FileInputStream(f).use { i ->
            val buf = ByteArray(1 shl 16)
            var carry = 0
            while (true) {
                val r = i.read(buf, carry, buf.size - carry)
                if (r <= 0) break
                val len = carry + r
                var p = 0
                while (p + nb.size <= len) {
                    var j = 0
                    while (j < nb.size && buf[p + j] == nb[j]) j++
                    if (j == nb.size) return@runCatching true
                    p++
                }
                carry = minOf(nb.size - 1, len)
                System.arraycopy(buf, len - carry, buf, 0, carry)
            }
            false
        }
    }.getOrDefault(false)
}
