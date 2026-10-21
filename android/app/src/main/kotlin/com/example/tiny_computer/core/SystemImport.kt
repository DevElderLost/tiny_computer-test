package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.tiny_computer.R
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Import data sistem Linux arm64 dari berkas pilihan pengguna (Debian trixie, Debian 12, Ubuntu, dst.) bila APK
 * tidak membawa data sistem (xa*) dan belum ada sistem terpasang. Logikanya mengikuti import kontainer di
 * Tiny Container master (tar ke `containers/N` lewat proot --link2symlink, lalu akun/grup Android ditambahkan),
 * tetapi arsip dialirkan langsung dari berkas pilihan ke tar tanpa disalin dulu ke penyimpanan aplikasi.
 *
 * Format dikenali dari byte awalnya (bukan dari nama berkas): xz (tar.xz) didekompresi `tar -J`; zstd (tar.zst)
 * dan gzip didekompresi di Kotlin lalu dialirkan sebagai tar polos.
 */
object SystemImport {
    private const val TAG = "TinySysImport"

    /** Akhiran yang diterima pemilih berkas: tar.xz dan tar.zst, ditambah gzip/tar polos. */
    private val SUFFIXES = listOf(".tar.xz", ".txz", ".tar.zst", ".tar.zstd", ".tzst", ".tar.gz", ".tgz", ".tar")

    private const val EM_AARCH64 = 0xB7

    private enum class Format { XZ, ZSTD, GZIP, TAR }

    fun isSupportedName(name: String): Boolean = name.lowercase().let { n -> SUFFIXES.any { n.endsWith(it) } }

    /** Nama tampilan sistem: nama berkas tanpa akhiran arsip. */
    fun systemName(fileName: String): String {
        val lower = fileName.lowercase()
        val suffix = SUFFIXES.firstOrNull { lower.endsWith(it) } ?: return fileName.ifBlank { "Linux" }
        return fileName.dropLast(suffix.length).ifBlank { "Linux" }
    }

    fun queryName(ctx: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    fun querySize(ctx: Context, uri: Uri): Long {
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        }
        return size
    }

    /** Menghitung byte yang sudah dibaca dari berkas sumber (arsip terkompresi) untuk persen kemajuan. */
    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    /**
     * Memasang sistem dari [uri] ke containers/0. Dipanggil di thread latar oleh [Startup.beginImport]; melempar
     * pengecualian (pesan sudah siap ditampilkan) bila gagal, dan pemanggil membersihkan lewat [cleanup].
     * Penanda "sistem terpasang" (`defaultContainer`) baru ditulis paling akhir, jadi impor yang gagal
     * tidak meninggalkan sistem setengah jadi.
     */
    fun run(ctx: Context, uri: Uri, displayName: String) {
        val data = TinyEnv.dataPath
        TinyEnv.currentContainer = 0
        val total = querySize(ctx, uri)
        val root = File(data, "containers/0")

        Rootfs.step("installingBootPackage")
        Rootfs.linkApplib(ctx)
        Rootfs.setupBootstrap(ctx)

        Rootfs.step("importingSystem")
        if (total > 0 && File(data).usableSpace < total * 3) throw IOException(ctx.getString(R.string.tc_import_no_space))
        // sisa impor yang gagal / pemasangan rusak: bersihkan dulu tanpa mengikuti symlink
        deleteTree(root)
        File(root, ".l2s").mkdirs()

        val raw = ctx.contentResolver.openInputStream(uri) ?: throw IOException(ctx.getString(R.string.tc_import_cannot_open))
        val counted = CountingStream(raw)
        val buffered = BufferedInputStream(counted, 1 shl 16)
        var readError: Throwable? = null
        val result = try {
            val format = detectFormat(buffered)
            Log.i(TAG, "import $displayName: format=$format, ukuran=$total")
            val stream: InputStream = when (format) {
                Format.ZSTD -> ZstdCompressorInputStream(buffered)
                Format.GZIP -> GzipCompressorInputStream(buffered, true)
                else -> buffered
            }
            Rootfs.runTar(data, xz = format == Format.XZ) { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = try { stream.read(buf) } catch (e: Throwable) { readError = e; -1 }
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (total > 0) Rootfs.percent = (counted.count * 99 / total).toInt().coerceIn(0, 99)
                }
            }
        } finally {
            runCatching { buffered.close() }
        }
        readError?.let { throw IOException(ctx.getString(R.string.tc_import_read_failed, it.message ?: it.javaClass.simpleName), it) }
        Rootfs.percent = -1
        if (result.code != 0) Log.w(TAG, "tar exit=${result.code}: ${result.tail}")

        // tar bisa berakhir dengan kode galat hanya karena node perangkat (dev/*) tidak bisa dibuat; yang menentukan
        // adalah apakah isinya rootfs Linux
        if (!File(root, "etc").isDirectory || !(File(root, "usr").isDirectory || File(root, "bin").isDirectory)) {
            throw IOException(ctx.getString(R.string.tc_import_not_rootfs) + if (result.tail.isNotBlank()) "\n${result.tail}" else "")
        }
        val machine = detectMachine(root)
        if (machine != null && machine != EM_AARCH64) {
            throw IOException(ctx.getString(R.string.tc_import_wrong_arch, machineName(machine)))
        }

        val script = Rootfs.asset(ctx, "tiny/import_rootfs.sh").replace("@DATA_PATH@", data)
        val code = Shell.execute(script)
        if (code != 0) Log.w(TAG, "import_rootfs.sh exit=$code")

        // rootfs hasil ekspor Tiny Computer (ada user tiny + tmoe-linux) memakai perintah boot bawaan; selain itu
        // dijalankan sebagai root dengan perintah boot generik
        val tinyCompatible = isTinyRootfs(root)
        if (tinyCompatible) prepareTinyRootfs(root)
        val info = JSONObject()
            .put("name", systemName(displayName))
            .put("boot", if (tinyCompatible) TinyEnv.bootForRootfs(root, TinyEnv.defaultBoot) else TinyEnv.bootGeneric)
            .put("vnc", "startnovnc &")
            .put("vncUrl", "http://localhost:36082/vnc.html?host=localhost&port=36082&autoconnect=true&resize=remote&password=12345678")
            .put("vncUri", "vnc://127.0.0.1:5904?VncPassword=12345678&SecurityType=2")
            .put("commands", if (tinyCompatible) TinyEnv.defaultCommands else TinyEnv.genericCommands)
            .put("generic", !tinyCompatible)
        Containers.replaceAll(listOf(info.toString()))
        Rootfs.applyFirstTimeDefaults(ctx)
        // `localedef` bawaan hanya untuk rootfs Tiny; di rootfs lain locale en_US belum tentu ada
        if (!tinyCompatible) TinyEnv.postCommand = ""
        Log.i(TAG, "import selesai: ${systemName(displayName)} (tiny=$tinyCompatible)")
    }

    /**
     * True bila [root] adalah rootfs Tiny: ada user `tiny` (/home/tiny) DAN salah satu penanda Tiny, yaitu
     * `usr/local/etc/tmoe-linux` (Tiny Computer lama) atau `/.tiny.yaml` (ekspor Tiny Container master/tc4,
     * yang tidak memakai tmoe-linux). Sebelumnya hanya tmoe-linux yang dicek sehingga ekspor master salah
     * ditandai "generic" dan desktop-nya diblokir.
     */
    fun isTinyRootfs(root: File): Boolean =
        File(root, "home/tiny").isDirectory &&
            (File(root, "usr/local/etc/tmoe-linux").exists() || File(root, ".tiny.yaml").exists())

    /** Titik mount yang dipakai perintah boot Tiny harus ada di rootfs ekspor master (yang tidak membuatnya). */
    private fun prepareTinyRootfs(root: File) {
        val dirs = listOf(
            "home/tiny/.local/share/tiny", "home/tiny/.local/bin",
            "home/tiny/公共", "home/tiny/图片", "home/tiny/音乐", "home/tiny/视频", "home/tiny/下载", "home/tiny/照片", "home/tiny/文档",
            "home/tiny/Public", "home/tiny/Pictures", "home/tiny/Music", "home/tiny/Videos", "home/tiny/Downloads",
            "home/tiny/Documents", "home/tiny/Photos",
            "usr/share/fonts/wpsm", "usr/share/fonts/yozom", "usr/share/fonts/androidm",
            "tmp/.X11-unix",
        )
        for (d in dirs) runCatching { File(root, d).mkdirs() }
        runCatching { File(root, "tmp").setWritable(true, false) }
    }

    /**
     * Memperbaiki sistem yang SUDAH terlanjur diimpor dan ditandai generic padahal sebenarnya rootfs Tiny
     * (mis. ekspor master): tanda generic dilepas dan boot/perintah diganti ke bawaan Tiny. Mengembalikan true
     * bila ada yang diubah. Aman dipanggil berulang.
     */
    fun reclassify(index: Int): Boolean {
        if (!Containers.isGeneric(index)) return false
        val root = TinyEnv.containerDir(index)
        if (!isTinyRootfs(root)) return false
        prepareTinyRootfs(root)
        Containers.put(index, "generic", false)
        Containers.put(index, "boot", TinyEnv.bootForRootfs(root, TinyEnv.defaultBoot))
        Containers.put(index, "commands", TinyEnv.defaultCommands)
        Log.i(TAG, "container #$index dikenali sebagai rootfs Tiny (generic dilepas)")
        return true
    }

    /** Membersihkan sisa impor yang gagal: folder rootfs dihapus, dan tidak ada penanda terpasang yang tertulis. */
    fun cleanup() {
        runCatching { deleteTree(File(TinyEnv.dataPath, "containers/0")) }
        runCatching { File(TinyEnv.dataPath, "containers/0").mkdirs() }
    }

    private fun detectFormat(s: BufferedInputStream): Format {
        s.mark(8)
        val head = ByteArray(6)
        var got = 0
        while (got < head.size) {
            val r = s.read(head, got, head.size - got)
            if (r < 0) break
            got += r
        }
        s.reset()
        fun b(i: Int) = head[i].toInt() and 0xFF
        return when {
            got >= 6 && b(0) == 0xFD && b(1) == 0x37 && b(2) == 0x7A && b(3) == 0x58 && b(4) == 0x5A && b(5) == 0x00 -> Format.XZ
            got >= 4 && b(0) == 0x28 && b(1) == 0xB5 && b(2) == 0x2F && b(3) == 0xFD -> Format.ZSTD
            got >= 2 && b(0) == 0x1F && b(1) == 0x8B -> Format.GZIP
            else -> Format.TAR
        }
    }

    /** Mengikuti symlink di dalam rootfs (yang absolut dibaca relatif terhadap [root]); null bila bukan berkas biasa. */
    private fun resolveInRoot(root: File, rel: String): File? {
        var cur = File(root, rel)
        repeat(10) {
            val p = cur.toPath()
            if (!Files.isSymbolicLink(p)) return if (cur.isFile) cur else null
            val t = Files.readSymbolicLink(p).toString()
            cur = if (t.startsWith("/")) File(root, t.trimStart('/')) else File(cur.parentFile, t)
        }
        return null
    }

    /** `e_machine` dari ELF biner umum di rootfs (183 = AArch64), atau null bila tidak ada yang terbaca. */
    private fun detectMachine(root: File): Int? {
        for (rel in listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash", "bin/busybox", "usr/bin/env", "bin/ls")) {
            val f = resolveInRoot(root, rel) ?: continue
            val head = runCatching { f.inputStream().use { i -> ByteArray(20).also { i.read(it) } } }.getOrNull() ?: continue
            if (head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()) {
                return (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
            }
        }
        return null
    }

    private fun machineName(m: Int): String = when (m) {
        0x3E -> "x86_64"
        0x03 -> "x86"
        0x28 -> "ARM 32-bit"
        0xF3 -> "RISC-V"
        else -> "machine 0x" + m.toString(16)
    }

    /**
     * Menghapus folder tanpa mengikuti symlink: rootfs bisa berisi symlink absolut ke penyimpanan ponsel
     * (mis. /storage/emulated/0), dan `File.deleteRecursively` akan ikut menghapus isinya.
     */
    fun deleteTree(dir: File) {
        if (!Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                d.toFile().apply { setReadable(true); setWritable(true); setExecutable(true) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.delete(f) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.delete(d) }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
