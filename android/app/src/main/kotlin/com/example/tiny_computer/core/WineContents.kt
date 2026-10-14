package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Paket konten Winlator (`.wcp`) untuk Debian trixie arm64: Wine/Proton **arm64ec**, FEXCore, DXVK, VKD3D.
 * Format sama dengan `ContentsManager` Winlator: tar yang dikompres xz atau zstd, berisi `profile.json`
 * (`type`, `versionName`, `versionCode`, `description`, `files[{source,target}]`, dan untuk Wine/Proton
 * `wine{binPath,libPath,prefixPack}`) beserta berkasnya.
 *
 * Tata letak di host (terlihat di guest sebagai `~/.local/share/tiny/wine`):
 *   `$DATA_DIR/tiny/wine/contents/<Tipe>/<nama>-<kode>/`  isi paket apa adanya
 *   `$DATA_DIR/tiny/wine/env.sh`                          lingkungan Wine aktif (dibaca shim di /usr/local/bin)
 *   `$DATA_DIR/share/.wine`                               prefix (HOME guest = `$DATA_DIR/share`)
 *
 * Template target di profile.json dipetakan ke guest:
 *   `${system32}` / `${syswow64}` -> drive_c/windows/system32 / syswow64 di prefix
 *   `${localbin}`                 -> /usr/local/bin kontainer (biner Box64 native)
 *   `${libdir}`                   -> `lib` milik Wine aktif (tempat berkas .so di `lib/wine/aarch64-unix/`; di sini FEX
 *                                    menaruh libwow64fex.so / libarm64ecfex.so, yang dicari Wine relatif ke loader-nya)
 */
object WineContents {
    private const val TAG = "WineContents"
    const val GUEST_ROOT = "/home/tiny/.local/share/tiny/wine"
    const val CATALOG_URL = "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json"
    private const val BACKUP_SUFFIX = ".tiny-orig"

    enum class Type(val label: String) {
        WINE("Wine"), PROTON("Proton"), DXVK("DXVK"), VKD3D("VKD3D"), FEXCORE("FEXCore"),
        BOX64("Box64"), WOWBOX64("WOWBox64");

        val isWine: Boolean get() = this == WINE || this == PROTON

        companion object {
            fun of(name: String?) = values().firstOrNull { it.label.equals(name, ignoreCase = true) }
        }
    }

    class FileMap(val source: String, val target: String)

    class Profile(
        val type: Type, val name: String, val code: Int, val desc: String, val files: List<FileMap>,
        val wineBin: String, val wineLib: String, val prefixPack: String?,
        /** Nama folder terpasang (dipakai sebagai id: `<Tipe>/<dirName>`). */
        val dirName: String = "",
    ) {
        val id: String get() = "${type.label}/$dirName"
        val title: String get() = if (code > 1) "$name ($code)" else name
    }

    class Remote(val type: Type, val name: String, val code: Int, val url: String)

    // ------------------------------------------------------------------ status tugas (untuk UI)

    /** Teks tugas yang sedang berjalan (unduh / pasang), kosong bila tidak ada. */
    var taskText by mutableStateOf("")
        private set
    var taskPercent by mutableStateOf(-1)
        private set
    var taskError by mutableStateOf<String?>(null)
        private set
    /** Naik setiap kali isi daftar terpasang berubah, supaya UI membaca ulang. */
    var revision by mutableStateOf(0)
        private set

    @Volatile private var busy = false
    val isBusy: Boolean get() = busy

    private fun setTask(text: String, percent: Int) { taskText = text; taskPercent = percent }

    // ------------------------------------------------------------------ path

    fun root() = File(TinyEnv.dataPath, "tiny/wine")
    private fun contentRoot() = File(root(), "contents")
    private fun typeDir(t: Type) = File(contentRoot(), t.label)
    fun dirOf(p: Profile) = File(typeDir(p.type), p.dirName)
    fun guestDirOf(p: Profile) = "$GUEST_ROOT/contents/${p.type.label}/${p.dirName}"
    fun prefixDir() = File(TinyEnv.dataPath, "share/.wine")
    /** Build Wine ini punya driver tampilan Wayland (`winewayland.so`)? Tidak semua build menyertakannya. */
    fun supportsWayland(p: Profile?): Boolean =
        p != null && File(dirOf(p), "${p.wineLib}/wine/aarch64-unix/winewayland.so").isFile

    /** Wayland dipakai bila dipilih pengguna DAN didukung build Wine aktif. */
    fun displayWayland(): Boolean = Cfg.wineDisplay != "x11" && supportsWayland(activeWine())

    /** Nilai registri `HKCU\Software\Wine\Drivers\Graphics`: daftar dicoba berurutan, jadi X11 menjadi cadangan. */
    fun displayDriverValue(): String = if (displayWayland()) "wayland,x11" else "x11"

    fun prefixReady() = File(prefixDir(), "system.reg").isFile && File(prefixDir(), "drive_c/windows/system32").isDirectory

    private fun safeName(s: String) = s.trim().replace(Regex("[^A-Za-z0-9._+-]+"), "_").trim('.', '_').take(80).ifEmpty { "content" }

    // ------------------------------------------------------------------ daftar terpasang & pilihan aktif

    fun installed(type: Type): List<Profile> =
        (typeDir(type).listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { d ->
            val f = File(d, "profile.json")
            if (f.isFile) runCatching { parseProfile(JSONObject(f.readText()), d.name) }.getOrNull() else null
        }.filter { it.type == type }

    fun find(id: String): Profile? {
        val type = Type.of(id.substringBefore('/', "")) ?: return null
        return installed(type).firstOrNull { it.id == id }
    }

    /** Wine/Proton aktif: pilihan pengguna, atau satu-satunya yang terpasang. */
    fun activeWine(): Profile? {
        find(Cfg.wineActive)?.takeIf { it.type.isWine }?.let { return it }
        return (installed(Type.WINE) + installed(Type.PROTON)).firstOrNull()
    }

    fun active(type: Type): Profile? = when (type) {
        Type.WINE, Type.PROTON -> activeWine()
        Type.FEXCORE -> find(Cfg.fexActive)
        Type.DXVK -> find(Cfg.dxvkActive)
        Type.VKD3D -> find(Cfg.vkd3dActive)
        Type.BOX64 -> find(Cfg.box64Active)
        Type.WOWBOX64 -> find(Cfg.wowbox64Active)
    }

    fun select(type: Type, id: String) {
        when (type) {
            Type.WINE, Type.PROTON -> Cfg.wineActive = id
            Type.FEXCORE -> Cfg.fexActive = id
            Type.DXVK -> Cfg.dxvkActive = id
            Type.VKD3D -> Cfg.vkd3dActive = id
            Type.BOX64 -> Cfg.box64Active = id
            Type.WOWBOX64 -> Cfg.wowbox64Active = id
        }
    }

    // ------------------------------------------------------------------ profil

    private fun parseProfile(o: JSONObject, dirName: String): Profile {
        val type = Type.of(o.getString("type")) ?: throw IllegalArgumentException("type tidak dikenal: ${o.optString("type")}")
        val name = o.optString("versionName", o.optString("verName", "")).ifEmpty { throw IllegalArgumentException("versionName kosong") }
        val code = o.optInt("versionCode", o.optInt("verCode", 0))
        val files = ArrayList<FileMap>()
        val arr = o.optJSONArray("files") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            files += FileMap(f.getString("source"), f.getString("target"))
        }
        val w = o.optJSONObject("wine")
        if (type.isWine && w == null) throw IllegalArgumentException("profil Wine tanpa blok \"wine\"")
        return Profile(
            type, name, code, o.optString("description", ""), files,
            w?.optString("binPath", "bin")?.ifEmpty { "bin" } ?: "bin",
            w?.optString("libPath", "lib")?.ifEmpty { "lib" } ?: "lib",
            w?.optString("prefixPack", "")?.ifEmpty { null },
            dirName,
        )
    }

    // ------------------------------------------------------------------ template target

    /** `${system32}`/`${syswow64}` -> berkas di prefix; `${libdir}` -> lib Wine aktif. null bila template tidak diizinkan. */
    private fun resolveTarget(target: String, wine: Profile?): File? {
        val (base, rest) = when {
            target.startsWith("\${system32}/") -> File(prefixDir(), "drive_c/windows/system32") to target.removePrefix("\${system32}/")
            target.startsWith("\${syswow64}/") -> File(prefixDir(), "drive_c/windows/syswow64") to target.removePrefix("\${syswow64}/")
            target.startsWith("\${libdir}/") -> (wine?.let { File(dirOf(it), it.wineLib) } ?: return null) to target.removePrefix("\${libdir}/")
            target.startsWith("\${localbin}/") -> File(TinyEnv.containerDir(), "usr/local/bin") to target.removePrefix("\${localbin}/")
            else -> return null
        }
        if (rest.isEmpty() || rest.contains("..") || rest.contains("dosdevices")) return null
        return File(base, rest)
    }

    private fun templateAllowed(type: Type, target: String): Boolean {
        val rest = target.substringAfter('/', "")
        if (rest.contains("..") || rest.contains("dosdevices")) return false
        return when (type) {
            Type.DXVK, Type.VKD3D -> target.startsWith("\${system32}/") || target.startsWith("\${syswow64}/")
            // FEX: dll di system32 dan .so unix di lib/wine/aarch64-unix
            Type.FEXCORE -> target.startsWith("\${system32}/") || target.startsWith("\${libdir}/wine/")
            // WOWBox64: wowbox64.dll (PE arm64) untuk emulasi i386; Box64: biner native di /usr/local/bin
            Type.WOWBOX64 -> target.startsWith("\${system32}/") || target.startsWith("\${syswow64}/")
            Type.BOX64 -> target.startsWith("\${localbin}/")
            else -> false
        }
    }

    // ------------------------------------------------------------------ ekstraksi WCP

    private class Counting(i: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(i) {
        private var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(d: Long) { n += d; onBytes(n) }
    }

    private fun decompress(raw: InputStream): InputStream {
        val s = BufferedInputStream(raw, 1 shl 16)
        s.mark(8)
        val m = ByteArray(6)
        var got = 0
        while (got < m.size) { val r = s.read(m, got, m.size - got); if (r < 0) break; got += r }
        s.reset()
        fun b(i: Int) = m[i].toInt() and 0xff
        return when {
            got >= 6 && b(0) == 0xFD && b(1) == 0x37 && b(2) == 0x7A && b(3) == 0x58 && b(4) == 0x5A -> XZCompressorInputStream(s, true)
            got >= 4 && b(0) == 0x28 && b(1) == 0xB5 && b(2) == 0x2F && b(3) == 0xFD -> ZstdCompressorInputStream(s)
            else -> throw IllegalArgumentException("Bukan berkas .wcp (harus tar xz atau zstd).")
        }
    }

    private fun isExecutable(rel: String, mode: Int): Boolean =
        mode and 0x49 != 0 || rel.startsWith("bin/") || rel.endsWith(".so") || rel.contains(".so.")

    /** Mengekstrak tar ke [dest]. Aman terhadap path keluar folder; symlink dan hardlink dipertahankan. */
    private fun extractTar(input: InputStream, dest: File) {
        val root = dest.canonicalFile
        TarArchiveInputStream(decompress(input)).use { tar ->
            val buf = ByteArray(1 shl 16)
            var e = tar.nextTarEntry
            while (e != null) {
                val rel = e.name.removePrefix("./").trimStart('/')
                if (rel.isNotEmpty()) {
                    val out = File(root, rel)
                    // simlink boleh menunjuk path guest absolut (belum ada di host): yang diperiksa induknya saja
                    val canon = (if (e.isSymbolicLink || e.isLink) out.parentFile ?: root else out).canonicalFile
                    if (rel.split('/').any { it == ".." } || !(canon.path == root.path || canon.path.startsWith(root.path + File.separator))) {
                        throw IllegalArgumentException("Entri di luar folder tujuan: ${e.name}")
                    }
                    when {
                        e.isDirectory -> { out.mkdirs(); out.setExecutable(true, false); out.setReadable(true, false) }
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            Files.deleteIfExists(out.toPath())
                            Files.createSymbolicLink(out.toPath(), Paths.get(e.linkName))
                        }
                        e.isLink -> {
                            out.parentFile?.mkdirs()
                            val src = File(root, e.linkName.removePrefix("./"))
                            if (src.canonicalPath.startsWith(root.path + File.separator) && src.isFile) src.copyTo(out, overwrite = true)
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o ->
                                while (true) { val r = tar.read(buf); if (r < 0) break; o.write(buf, 0, r) }
                            }
                            out.setReadable(true, false)
                            out.setWritable(true, true)
                            if (isExecutable(rel, e.mode)) out.setExecutable(true, false)
                        }
                    }
                }
                e = tar.nextTarEntry
            }
        }
    }

    // ------------------------------------------------------------------ pasang

    /**
     * Memasang satu .wcp dari [open] (ukuran terkompresi [size] untuk persen, boleh -1). Mengembalikan profil
     * terpasang; melempar [IllegalArgumentException] (pesan siap tampil) bila isinya tidak cocok.
     */
    private fun install(label: String, size: Long, expect: Type? = null, open: () -> InputStream): Profile {
        val stage = File(root(), "tmp/stage-${System.nanoTime()}")
        try {
            stage.mkdirs()
            setTask(label, if (size > 0) 0 else -1)
            var last = -1
            open().use { raw ->
                extractTar(Counting(raw) { n ->
                    if (size > 0) {
                        val p = (n * 100 / size).toInt().coerceIn(0, 99)
                        if (p != last) { last = p; setTask(label, p) }
                    }
                }, stage)
            }
            val pf = File(stage, "profile.json")
            if (!pf.isFile) throw IllegalArgumentException("profile.json tidak ada di paket.")
            val probe = runCatching { parseProfile(JSONObject(pf.readText()), "x") }
                .getOrElse { throw IllegalArgumentException("profile.json tidak valid: ${it.message}") }
            if (expect != null && probe.type != expect && !(expect.isWine && probe.type.isWine)) {
                throw IllegalArgumentException("This package is ${probe.type.label}, not ${expect.label}.")
            }
            validate(stage, probe)
            val dirName = safeName("${probe.name}-${probe.code}")
            val dest = File(typeDir(probe.type), dirName)
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!stage.renameTo(dest)) {
                stage.copyRecursively(dest, overwrite = true)
            }
            return parseProfile(JSONObject(File(dest, "profile.json").readText()), dirName)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun validate(stage: File, p: Profile) {
        val root = stage.canonicalFile
        for (f in p.files) {
            val src = File(stage, f.source).canonicalFile
            if (!src.path.startsWith(root.path + File.separator) || !src.isFile) throw IllegalArgumentException("Berkas paket hilang: ${f.source}")
            if (!templateAllowed(p.type, f.target)) throw IllegalArgumentException("Target tidak diizinkan untuk ${p.type.label}: ${f.target}")
        }
        if (p.type.isWine) {
            val bin = File(stage, p.wineBin)
            val lib = File(stage, p.wineLib)
            if (!bin.isDirectory || !File(bin, "wine").exists() || !lib.isDirectory) {
                throw IllegalArgumentException("Wine/Proton tidak lengkap: butuh ${p.wineBin}/wine dan ${p.wineLib}/.")
            }
            if (!File(lib, "wine/aarch64-unix").isDirectory) {
                throw IllegalArgumentException("Ini bukan Wine arm64ec (tidak ada lib/wine/aarch64-unix). Build x86_64 butuh Box64, yang tidak dipakai di Tiny.")
            }
        }
        if (p.type == Type.FEXCORE && p.files.none { it.target.endsWith("libwow64fex.dll") }) {
            throw IllegalArgumentException("Paket FEXCore tidak berisi libwow64fex.dll.")
        }
        if (p.type == Type.WOWBOX64 && p.files.none { it.target.endsWith("wowbox64.dll", true) }) {
            throw IllegalArgumentException("Paket WOWBox64 tidak berisi wowbox64.dll.")
        }
        if (p.type == Type.BOX64 && p.files.none { it.target.endsWith("/box64") }) {
            throw IllegalArgumentException("Paket Box64 tidak berisi biner box64.")
        }
    }

    private fun runTask(block: () -> Unit) {
        if (busy) return
        busy = true
        taskError = null
        Thread({
            try { block() } catch (t: Throwable) {
                Log.w(TAG, "tugas gagal", t)
                taskError = t.message ?: t.javaClass.simpleName
            } finally {
                setTask("", -1)
                busy = false
                revision++
            }
        }, "tiny-wine-content").start()
    }

    /** Pasang dari berkas yang dipilih pengguna (SAF). Pilihan aktif otomatis pindah ke paket baru. */
    fun importUri(ctx: Context, uri: Uri, expect: Type? = null) {
        val app = ctx.applicationContext
        runTask {
            var name = "wcp"
            var size = -1L
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; size = if (it.isNull(1)) -1 else it.getLong(1) }
            }
            val p = install(name, size, expect) { app.contentResolver.openInputStream(uri) ?: throw IOException("tidak bisa membuka $uri") }
            autoSelect(p)
        }
    }

    /** Unduh dari katalog lalu pasang. */
    fun downloadAndInstall(ctx: Context, r: Remote) {
        runTask {
            val tmp = File(root(), "tmp").apply { mkdirs() }
            val f = File(tmp, "download-${System.nanoTime()}.wcp")
            try {
                download(r.url, f) { got, total -> setTask("${r.type.label} ${r.name}", if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else -1) }
                val p = install("${r.type.label} ${r.name}", f.length()) { FileInputStream(f) }
                autoSelect(p)
            } finally {
                f.delete()
            }
        }
    }

    private fun autoSelect(p: Profile) {
        select(p.type, p.id)
        // FEX/DXVK/VKD3D/Box64 yang dipilih langsung dipasang ke prefix; env.sh ikut ditulis ulang (HODLL bergantung padanya)
        if (!p.type.isWine) runCatching { onSelectionChanged() }
    }

    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TinyComputer")
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var got = 0L
            var last = -1L
            c.inputStream.use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val r = i.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r)
                        got += r
                        if (got - last >= (256 shl 10)) { last = got; progress(got, total) }
                    }
                }
            }
            progress(got, total)
        } finally {
            c.disconnect()
        }
    }

    fun remove(p: Profile) {
        dirOf(p).deleteRecursively()
        when (p.type) {
            Type.WINE, Type.PROTON -> if (Cfg.wineActive == p.id) Cfg.wineActive = ""
            Type.FEXCORE -> if (Cfg.fexActive == p.id) Cfg.fexActive = ""
            Type.DXVK -> if (Cfg.dxvkActive == p.id) Cfg.dxvkActive = ""
            Type.VKD3D -> if (Cfg.vkd3dActive == p.id) Cfg.vkd3dActive = ""
            Type.BOX64 -> if (Cfg.box64Active == p.id) Cfg.box64Active = ""
            Type.WOWBOX64 -> if (Cfg.wowbox64Active == p.id) Cfg.wowbox64Active = ""
        }
        runCatching { applyAll() }
        revision++
    }

    // ------------------------------------------------------------------ katalog jarak jauh

    private val EMBEDDED = listOf(
        Remote(Type.WINE, "proton-10-arm64ec", 1, "https://github.com/StevenMXZ/Winlator-Contents/releases/download/1.0/proton-10-arm64ec.wcp.xz"),
        Remote(Type.DXVK, "2.4.1-arm64ec-gplasync", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/DXVK/dxvk-2.4.1-arm64ec-gplasync.wcp"),
        Remote(Type.DXVK, "2.6.2-arm64ec-gplasync", 0, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/DXVK/dxvk-2.6.2-arm64ec-gplasync.wcp"),
        Remote(Type.DXVK, "2.7.1-arm64ec-gplasync-fix-leegao", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/DXVK/dxvk-2.7.1-arm64ec-gplasync-fix-leegao.wcp"),
        Remote(Type.DXVK, "3.1-arm64ec-gplasync", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/DXVK/dxvk-gplasync-arm64ec-3.1.1-1.wcp"),
        Remote(Type.FEXCORE, "241214", 1, "https://github.com/ziad9267/Winlator-Contents/raw/refs/heads/main/FEXCore/fexcore_241214.wcp"),
        Remote(Type.FEXCORE, "2505", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/fexcore-2505.wcp"),
        Remote(Type.FEXCORE, "2507_fix", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/fexcore_2507_fix.wcp"),
        Remote(Type.FEXCORE, "2508.1", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2508.1.wcp"),
        Remote(Type.FEXCORE, "2509.1", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2509.1.wcp"),
        Remote(Type.FEXCORE, "2510.1", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2510.1.wcp"),
        Remote(Type.FEXCORE, "2511", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2511.wcp"),
        Remote(Type.FEXCORE, "2512", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2512.wcp"),
        Remote(Type.FEXCORE, "2601", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2601.wcp"),
        Remote(Type.FEXCORE, "2601-aabdded-nightly", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/FEXCore-2601-aabdded.wcp"),
        Remote(Type.FEXCORE, "2603", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2603.wcp"),
        Remote(Type.FEXCORE, "2604", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2604.wcp"),
        Remote(Type.FEXCORE, "2605", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2605.wcp"),
        Remote(Type.FEXCORE, "2607", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2607.wcp"),
        Remote(Type.FEXCORE, "2608", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2608.wcp"),
        Remote(Type.FEXCORE, "2609", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2609.wcp"),
        Remote(Type.FEXCORE, "2610", 1, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/FEXCore/2610.wcp"),
        Remote(Type.VKD3D, "2.14.1-arm64ec", 0, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/VKD3D/vkd3d-2.14.1-arm64ec.wcp"),
        Remote(Type.VKD3D, "arm64ec-3.0a.wcp", 0, "https://github.com/StevenMXZ/Winlator-Contents/raw/refs/heads/main/VKD3D/vkd3d-arm64ec-3.0a.wcp"),
    )

    /** Katalog daftar konten Winlator; bila tidak terjangkau dipakai daftar tertanam di atas. Panggil di thread latar. */
    fun catalog(): List<Remote> {
        val live = runCatching {
            val c = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000 }
            try {
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val t = Type.of(o.optString("type")) ?: return@mapNotNull null
                    val n = o.optString("verName")
                    // Wine/Proton harus arm64ec; DXVK/VKD3D arm64ec dipakai sebagai default, sisanya x86 hanya untuk syswow64
                    if (t.isWine && !n.contains("arm64ec", true)) return@mapNotNull null
                    Remote(t, n, o.optString("verCode", "0").toIntOrNull() ?: 0, o.optString("remoteUrl"))
                }
            } finally { c.disconnect() }
        }.onFailure { Log.w(TAG, "katalog tidak terjangkau, memakai daftar tertanam: ${it.message}") }.getOrNull()
        return (live?.takeIf { it.isNotEmpty() } ?: EMBEDDED).filter { it.url.startsWith("https://") }
    }

    // ------------------------------------------------------------------ penerapan ke guest

    /**
     * Dipanggil setelah pengguna mengganti pilihan aktif. Bila Wine sudah pernah disiapkan, env.sh + shim ditulis
     * ulang (DXVK/VKD3D/FEX mengubah isinya); bila belum, cukup menerapkan berkas ke prefix yang mungkin sudah ada.
     * Menyalin berkas besar, jadi panggil di thread latar.
     */
    fun onSelectionChanged() {
        if (File(root(), "env.sh").isFile) writeRuntime() else applyAll()
        revision++
    }

    /**
     * Isi shim di /usr/local/bin. Bila TINY_LAUNCH_LOG ada (log peluncuran menyala, lihat [LaunchLog]) satu baris
     * dicatat ke berkas sesi dan, jika stderr bukan terminal dan skrip Shortcut belum menyalurkan keluaran ke log
     * (TINY_LAUNCH_TEE), stderr Wine ikut ditambahkan ke sana. stdout tidak diubah supaya `$(wine ...)` tetap jalan.
     * wineserver (dijalankan otomatis oleh wine) tidak dicatat.
     */
    private fun shimText(wine: Profile, n: String): String {
        val real = "\"\$TINY_WINE_ROOT/${wine.wineBin}/$n\""
        val head = "#!/bin/sh\n$SHIM_MARK\n. $GUEST_ROOT/env.sh\n"
        if (n == "wineserver") return head + "exec $real \"\$@\"\n"
        return head +
            "if [ -n \"\$TINY_LAUNCH_LOG\" ]; then\n" +
            "  echo \"\$(date +%H:%M:%S.%3N)  wine      $n \$*\" >> \"\$TINY_LAUNCH_LOG\" 2>/dev/null\n" +
            "  if [ -z \"\$TINY_LAUNCH_TEE\" ] && [ ! -t 2 ] && [ -w \"\$TINY_LAUNCH_LOG\" ]; then\n" +
            "    exec $real \"\$@\" 2>> \"\$TINY_LAUNCH_LOG\"\n" +
            "  fi\n" +
            "fi\n" +
            "exec $real \"\$@\"\n"
    }

    private fun runtimeDir() = root().apply { mkdirs() }
    private fun shimDir() = File(TinyEnv.containerDir(), "usr/local/bin")
    private const val SHIM_MARK = "# tiny-wine-shim"

    private fun jsonOf(f: File): JSONObject = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())

    /** Salin [from] ke [to]; berkas asli (mis. dll palsu buatan wineboot) dicadangkan sekali sebagai `.tiny-orig`. */
    private fun place(from: File, to: File, backup: Boolean) {
        to.parentFile?.mkdirs()
        if (backup && to.exists() && !File(to.path + BACKUP_SUFFIX).exists()) to.renameTo(File(to.path + BACKUP_SUFFIX))
        else if (to.exists()) to.delete()
        from.copyTo(to, overwrite = true)
        to.setReadable(true, false)
        if (to.name.endsWith(".so") || to.path.contains("/usr/local/bin/")) to.setExecutable(true, false)
    }

    /**
     * Menyamakan prefix + lib Wine dengan pilihan saat ini: file milik tipe yang diganti/dicabut dikembalikan
     * ke aslinya, file tipe terpilih disalin. DLL (system32/syswow64) hanya diproses bila prefix sudah ada;
     * `.so` FEX di lib Wine selalu diproses. Aman dipanggil berulang.
     */
    @Synchronized
    fun applyAll() {
        val wine = activeWine()
        val state = File(runtimeDir(), "applied.json")
        val applied = jsonOf(state)
        val prefix = prefixReady()
        for (type in listOf(Type.FEXCORE, Type.DXVK, Type.VKD3D, Type.WOWBOX64, Type.BOX64)) {
            // mode WineD3D: DXVK tidak diterapkan, jadi DLL asli Wine dikembalikan (lihat D3dMode)
            val prof = if (type == Type.DXVK && !D3dMode.isDxvk()) null else active(type)
            val key = type.label
            val old = applied.optJSONArray(key) ?: JSONArray()
            val oldSet = (0 until old.length()).map { old.getString(it) }.toSet()
            val keep = ArrayList<String>()
            val wanted = HashMap<String, File>() // target absolut -> sumber
            if (prof != null) for (f in prof.files) {
                val dst = resolveTarget(f.target, wine) ?: continue
                val isDll = f.target.startsWith("\${system32}") || f.target.startsWith("\${syswow64}")
                if (isDll && !prefix) continue
                wanted[dst.path] = File(dirOf(prof), f.source)
            }
            // kembalikan yang tidak lagi diinginkan
            for (i in 0 until old.length()) {
                val path = old.getString(i)
                if (path in wanted) continue
                val dst = File(path)
                val bak = File(path + BACKUP_SUFFIX)
                dst.delete()
                if (bak.exists()) bak.renameTo(dst)
            }
            for ((path, src) in wanted) {
                if (!src.isFile) continue
                val dst = File(path)
                // dll di prefix dan biner di /usr/local/bin: berkas asli dicadangkan sekali dan dikembalikan saat dicabut
                val keepOrig = path.contains("/drive_c/windows/") || path.contains("/usr/local/bin/")
                // sudah diterapkan dengan ukuran sama: lewati (DXVK puluhan MB)
                if (!(path in oldSet && dst.isFile && dst.length() == src.length())) place(src, dst, backup = keepOrig)
                keep += path
            }
            applied.put(key, JSONArray(keep))
        }
        state.writeText(applied.toString(2))
    }

    private fun dllNames(p: Profile?): String =
        p?.files?.map { it.target.substringAfterLast('/') }?.filter { it.endsWith(".dll", true) }
            ?.map { it.removeSuffix(".dll").removeSuffix(".DLL") }?.distinct()?.joinToString(",").orEmpty()

    /**
     * Menulis `env.sh` + shim di /usr/local/bin kontainer sehingga `wine`, `wineserver`, `winecfg`, ... langsung
     * bisa dipanggil dari terminal, menu Shortcut, dan perintah Wine di Pengaturan. Mengembalikan pesan galat
     * (Inggris, siap tampil) atau null bila berhasil.
     */
    @Synchronized
    fun writeRuntime(): String? {
        val wine = activeWine() ?: return "No Wine/Proton installed. Import or download an arm64ec build first."
        val bin = File(dirOf(wine), wine.wineBin)
        if (!File(bin, "wine").exists()) return "Wine files are missing; reinstall ${wine.title}."
        val fex = active(Type.FEXCORE)
        val dxvk = dllNames(active(Type.DXVK))
        val vkd3d = dllNames(active(Type.VKD3D))
        val g = guestDirOf(wine)

        val overrides = buildList {
            add("mscoree,mshtml=d")      // tanpa dialog Mono/Gecko
            add("winemenubuilder.exe=d") // tanpa entri menu otomatis
            // mode DXVK: native,builtin; mode WineD3D: builtin dipaksa lewat env supaya menang atas registri yang basi
            if (D3dMode.isDxvk()) { if (dxvk.isNotEmpty()) add("$dxvk=n,b") } else add("${D3dMode.dllCsv()}=b")
            if (vkd3d.isNotEmpty()) add("$vkd3d=n,b")
        }.joinToString(";")

        val env = buildString {
            append("# Ditulis oleh Tiny Computer (WineContents.writeRuntime). Jangan diedit; dibuat ulang saat Wine dipilih.\n")
            append("export TINY_WINE_ROOT='$g'\n")
            append("export WINEPREFIX=\"\${WINEPREFIX:-\$HOME/.wine}\"\n")
            append("export WINEDEBUG=\"\${WINEDEBUG:--all}\"\n")
            append("export WINEESYNC=\"\${WINEESYNC:-0}\" WINEFSYNC=\"\${WINEFSYNC:-0}\"\n")
            append("export WINEDLLOVERRIDES=\"\${WINEDLLOVERRIDES:-$overrides}\"\n")
            // emulator i386 (HODLL): FEXCore atau WOWBox64 (Cfg.emu32); "auto" memakai FEXCore bila terpasang, selain itu WOWBox64
            val hodll = when (Cfg.emu32) {
                "fex" -> "libwow64fex.dll"
                "box64" -> "wowbox64.dll"
                else -> if (fex != null) "libwow64fex.dll" else if (active(Type.WOWBOX64) != null) "wowbox64.dll" else null
            }
            if (hodll != null) append("export HODLL=$hodll\n")
            append(EmuPresets.envLines())
            if (displayWayland()) {
                // Vulkan/DXVK lewat Xwayland (xcb) gagal di Turnip karena tanpa DRI3; surface Wayland langsung ke
                // compositor DroidDeck jalan. Hanya bila soketnya ada; selain itu Wine memakai X11 (cadangan di registri).
                // MESA_VK_WSI_DEBUG=sw (jalur X11 tanpa DRI3) memaksa Vulkan Wayland menyalin lewat shm: dilepas di sini.
                append("if [ -S /tmp/wayland-0 ]; then export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0; unset MESA_VK_WSI_DEBUG; fi\n")
            }
            append("export LD_LIBRARY_PATH=\"\$TINY_WINE_ROOT/${wine.wineLib}\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"\n")
            append("export PATH=\"\$TINY_WINE_ROOT/${wine.wineBin}:\$PATH\"\n")
        }
        val envFile = File(runtimeDir(), "env.sh")
        envFile.writeText(env)
        envFile.setReadable(true, false)

        // shim untuk setiap berkas di bin/ (kecuali preloader), supaya tidak bergantung pada PATH sesi
        val dir = shimDir().apply { mkdirs() }
        val names = (bin.listFiles() ?: emptyArray()).filter { it.isFile && it.name != "wine-preloader" }.map { it.name }
        val listFile = File(runtimeDir(), "shims.txt")
        val oldNames = if (listFile.isFile) listFile.readLines().filter { it.isNotBlank() } else emptyList()
        for (stale in oldNames - names.toSet()) {
            val f = File(dir, stale)
            if (f.isFile && f.readText().contains(SHIM_MARK)) f.delete()
        }
        for (n in names) {
            val f = File(dir, n)
            if (f.exists() && !f.readText().contains(SHIM_MARK)) continue // jangan timpa berkas milik sistem/paket lain
            f.writeText(shimText(wine, n))
            f.setReadable(true, false); f.setExecutable(true, false)
        }
        listFile.writeText(names.joinToString("\n"))
        applyAll()
        return null
    }
}
