package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * Shortcut program Windows (.exe) yang ditambahkan pengguna lewat tombol Add di menu Shortcut.
 *
 * Tiap shortcut ditulis sebagai berkas `.desktop` pengguna di `~/.local/share/applications` dengan
 * `Exec=wine "<path .exe>"` dan `Path=<folder .exe>`. Karena itu entri ini diperlakukan persis seperti
 * aplikasi Linux lain: tampil di grid Shortcut, dijalankan di sesi proot BARU oleh [ShortcutRunner]
 * (Xwayland + window manager ringan, tanpa membuka desktop), dan punya pengaturan per aplikasi
 * ([ShortcutPrefs]) lewat tekan lama. Perintah `wine` berasal dari shim yang ditulis [WineContents]
 * di `/usr/local/bin` kontainer (shim itu sendiri memuat `env.sh` Wine aktif).
 */
object ExeShortcuts {
    /** Awalan id berkas .desktop; juga penanda bahwa entri ini boleh dihapus dari menu Shortcut. */
    const val ID_PREFIX = "tinyexe-"

    sealed class Outcome {
        data class Ok(val name: String, val id: String) : Outcome()
        object NotExe : Outcome()
        object BadLocation : Outcome()
        object NeedWine : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun isExeShortcut(id: String) = id.startsWith(ID_PREFIX)

    /** Wine sudah disiapkan (shim `wine` ada di /usr/local/bin kontainer). */
    fun wineReady(): Boolean = File(TinyEnv.containerDir(), "usr/local/bin/wine").exists()

    private fun appsDir() = File(TinyEnv.containerDir(), "home/tiny/.local/share/applications")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun oneLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()

    /** Argumen Exec berkutip ganda; karakter khusus (persen, backslash, kutip, dolar, backtick) di-escape agar aman untuk sh. */
    private fun execQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`").replace("%", "%%") + "\""

    private fun displayName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Path penyimpanan Android -> path yang terlihat di dalam kontainer (proot me-mount /storage dan /media/sd). */
    private fun fromHostPath(p: String): String? {
        val primary = Regex("^/(?:storage/emulated/\\d+|storage/self/primary|sdcard)(/.*)?$").matchEntire(p)
        return when {
            primary != null -> "/media/sd" + primary.groupValues[1]
            p.startsWith("/storage/") -> p
            else -> null
        }
    }

    /**
     * URI hasil pemilih berkas -> path DI DALAM kontainer, atau null bila lokasinya tidak bisa dipetakan
     * (mis. Recent, Google Drive, atau Download dengan id numerik). Program Windows butuh berkas aslinya
     * (DLL dan data di sebelahnya), jadi berkas TIDAK disalin.
     */
    fun guestPath(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return fromHostPath(uri.path ?: return null)
        if (!DocumentsContract.isDocumentUri(ctx, uri)) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val i = docId.indexOf(':')
                if (i <= 0) return null
                val volume = docId.substring(0, i)
                val rel = docId.substring(i + 1).trim('/')
                if (rel.isEmpty()) return null
                if (volume.equals("primary", ignoreCase = true)) "/media/sd/$rel" else "/storage/$volume/$rel"
            }
            "com.android.providers.downloads.documents" ->
                if (docId.startsWith("raw:")) fromHostPath(docId.removePrefix("raw:")) else null
            else -> null
        }
    }

    /** Memvalidasi pilihan pengguna (harus .exe, lokasi terpetakan, Wine siap) lalu membuat shortcut-nya. */
    fun addFromUri(ctx: Context, uri: Uri): Outcome {
        val file = displayName(ctx, uri) ?: return Outcome.NotExe
        if (!file.endsWith(".exe", ignoreCase = true)) return Outcome.NotExe
        if (!wineReady()) return Outcome.NeedWine
        val path = guestPath(ctx, uri) ?: return Outcome.BadLocation
        return runCatching { Outcome.Ok(add(path, file.dropLast(4)), idFor(path, file.dropLast(4))) }
            .getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun idFor(guestPath: String, label: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(guestPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return ID_PREFIX + safe(label).take(32) + "-" + h.take(8)
    }

    /** Menulis berkas .desktop; menambahkan .exe yang sama lagi hanya memperbarui entri yang ada. Mengembalikan nama tampilan. */
    fun add(guestPath: String, label: String): String {
        val name = oneLine(label).ifEmpty { "Windows app" }
        val dir = guestPath.substringBeforeLast('/', "/home/tiny").ifEmpty { "/" }
        val f = File(appsDir().apply { mkdirs() }, idFor(guestPath, label) + ".desktop")
        f.writeText(
            "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=${name}\n" +
                "Comment=${oneLine(guestPath)}\n" +
                "Exec=wine ${execQuote(guestPath)}\n" +
                "Path=${oneLine(dir)}\n" +
                "Terminal=false\n" +
                "Categories=Wine;\n",
        )
        f.setReadable(true, false)
        return name
    }

    /** Menghapus shortcut buatan pengguna beserta pengaturan dan sisa skrip/lognya. Entri lain tidak disentuh. */
    fun remove(id: String): Boolean {
        if (!isExeShortcut(id)) return false
        val ok = File(appsDir(), "$id.desktop").delete()
        ShortcutPrefs.reset(id)
        File(TinyEnv.dataPath, "tiny/shortcuts/run-${safe(id)}.sh").delete()
        File(TinyEnv.containerDir(), "home/tiny/.vnc/shortcut-${safe(id)}.log").delete()
        return ok
    }
}
