package com.example.tiny_computer.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/**
 * Membaca daftar aplikasi Linux yang terpasang langsung dari rootfs (tanpa menjalankan proses di
 * kontainer): berkas .desktop di `usr/share/applications`, `usr/local/share/applications` dan
 * `~/.local/share/applications`, lengkap dengan ikonnya (tema ikon hicolor dan tema lain, pixmaps,
 * atau path absolut). Logika pembacaan entri dan pencarian ikon mengikuti `AppImageManager` /
 * `FlatpakManager` milik DroidDeck.
 *
 * Path di dalam rootfs adalah path tamu: symlink absolut (mis. `/etc/alternatives/...`) tidak boleh
 * dibuka langsung dari sisi Android, jadi selalu lewat [resolve].
 */
object DesktopApps {
    class App(
        /** Nama berkas .desktop tanpa ekstensi, mis. `firefox-esr`. Kunci untuk pengaturan per aplikasi. */
        val id: String,
        val name: String,
        val comment: String,
        /** Baris `Exec=` mentah (kode field %f/%u dst belum dibuang). */
        val exec: String,
        /** Nilai `Icon=`: nama tema atau path absolut di dalam kontainer. */
        val icon: String,
        val terminal: Boolean,
        /** `Path=`: direktori kerja bawaan, boleh kosong. */
        val path: String,
        /**
         * Aplikasi sistem (bawaan desktop: pengaturan, panel, manajer berkas, pemasang paket, dst).
         * Tidak tampil di menu Shortcut kecuali pengguna menekan "Show hidden". Lihat [isSystemApp].
         */
        val system: Boolean = false,
    )

    // urutan prioritas: entri pengguna menimpa entri sistem dengan id yang sama
    private val APP_DIRS = listOf(
        "home/tiny/.local/share/applications",
        "usr/local/share/applications",
        "usr/share/applications",
        "var/lib/flatpak/exports/share/applications",
    )
    private val ICON_ROOTS = listOf("home/tiny/.local/share/icons", "usr/local/share/icons", "usr/share/icons")
    private val BIN_DIRS = listOf("usr/local/bin", "usr/bin", "bin", "usr/local/games", "usr/games", "usr/sbin", "sbin", "home/tiny/.local/bin")
    private const val WANTED_ICON_PX = 96

    /** Folder (path tamu) tempat aplikasi yang dipasang pengguna berada: tidak pernah dianggap aplikasi sistem. */
    private val USER_APP_PREFIXES = listOf("/home/", "/usr/local/", "/var/lib/flatpak/")

    /** Nilai `Categories=` yang menandai aplikasi bawaan desktop / pengaturan sistem (tanpa beda huruf besar-kecil). */
    private val SYSTEM_CATEGORIES = setOf(
        "settings", "desktopsettings", "hardwaresettings", "packagemanager", "system", "core",
        "x-xfce", "x-xfce-toplevel", "x-xfce-settingsdialog", "x-xfce-personalsettings",
        "x-xfce-hardwaresettings", "x-xfce-systemsettings", "x-xfce-mailreader", "x-xfce-webbrowser",
        "x-xfce-filemanager", "x-xfce-terminalemulator", "x-gnome-settings-panel",
    )

    /** Awalan id berkas .desktop komponen sistem yang kategorinya tidak konsisten. */
    private val SYSTEM_ID_PREFIXES = listOf(
        "xfce", "org.xfce.", "thunar", "exo-", "xfwm4", "xfdesktop", "panel-", "org.freedesktop.", "xdg-",
        "nm-", "pavucontrol", "gdebi", "fcitx", "im-config", "ibus",
    )

    /**
     * Aplikasi sistem = bawaan desktop, bukan aplikasi yang dipasang pengguna. Entri di `~/.local`,
     * `/usr/local` dan flatpak selalu dianggap aplikasi pengguna; selebihnya dinilai dari `Categories=`
     * dan awalan id.
     */
    private fun isSystemApp(guestPath: String, id: String, categories: String): Boolean {
        if (USER_APP_PREFIXES.any { guestPath.startsWith(it) }) return false
        val lid = id.lowercase()
        if (SYSTEM_ID_PREFIXES.any { lid.startsWith(it) }) return true
        return categories.split(';').any { it.trim().lowercase() in SYSTEM_CATEGORIES }
    }

    /** Desktop sudah terpasang dan pernah dibuka sekali: syarat ikon tampil di menu Shortcut. */
    fun ready(): Boolean =
        Startup.installed && Cfg.desktopOpenedOnce && File(TinyEnv.containerDir(), "usr/share/applications").isDirectory

    // ------------------------------------------------------------------ berkas di dalam rootfs

    /** Mengikuti symlink dengan akar [root] sebagai `/` milik kontainer; null bila putus. */
    fun resolve(root: File, file: File): File? {
        var cur = file
        repeat(10) {
            val p = cur.toPath()
            if (!Files.isSymbolicLink(p)) return if (cur.exists()) cur else null
            val target = runCatching { Files.readSymbolicLink(p).toString() }.getOrNull() ?: return null
            cur = if (target.startsWith("/")) File(root, target.removePrefix("/")) else File(cur.parentFile, target)
        }
        return null
    }

    private fun existsInRoot(root: File, rel: String): Boolean =
        Files.exists(File(root, rel).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun commandExists(root: File, cmd: String): Boolean =
        if (cmd.startsWith("/")) existsInRoot(root, cmd.removePrefix("/"))
        else BIN_DIRS.any { existsInRoot(root, "$it/$cmd") }

    // ------------------------------------------------------------------ daftar aplikasi

    /** Semua aplikasi yang boleh tampil di menu, urut menurut nama. Dipanggil di thread latar. */
    fun scan(root: File = TinyEnv.containerDir()): List<App> {
        synchronized(themeCache) { themeCache.clear() }
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        for (dir in APP_DIRS) {
            val base = File(root, dir)
            val files = base.listFiles() ?: continue
            // satu tingkat subfolder (mis. kde4/) ikut dibaca, seperti menu desktop pada umumnya
            val all = files.flatMap { f -> if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) f.listFiles()?.toList().orEmpty() else listOf(f) }
            for (f in all) {
                if (!f.name.endsWith(".desktop")) continue
                val id = f.name.removeSuffix(".desktop")
                if (!seen.add(id)) continue
                parse(root, id, f)?.let { out += it }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Satu aplikasi menurut id-nya tanpa memindai semuanya (dipanggil dari thread utama saat peluncuran). */
    fun find(id: String, root: File = TinyEnv.containerDir()): App? {
        for (dir in APP_DIRS) {
            val base = File(root, dir)
            val candidates = listOf(File(base, "$id.desktop")) +
                base.listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "$id.desktop") }
            for (f in candidates) {
                if (f.exists() || Files.isSymbolicLink(f.toPath())) return parse(root, id, f)
            }
        }
        return null
    }

    private fun parse(root: File, id: String, file: File): App? {
        val real = resolve(root, file)?.takeIf { it.isFile } ?: return null
        val values = HashMap<String, String>()
        var inMain = false
        try {
            real.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("[")) { inMain = line == "[Desktop Entry]"; continue }
                    if (!inMain) continue
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (values["Type"] != "Application") return null
        if (values["NoDisplay"].equals("true", true) || values["Hidden"].equals("true", true)) return null
        val exec = values["Exec"].orEmpty()
        if (exec.isEmpty()) return null
        val tryExec = values["TryExec"].orEmpty()
        if (tryExec.isNotEmpty() && !commandExists(root, tryExec)) return null
        val name = localized(values, "Name").ifEmpty { id }
        return App(
            id = id, name = name, comment = localized(values, "Comment"), exec = exec,
            icon = values["Icon"].orEmpty(), terminal = values["Terminal"].equals("true", true),
            path = values["Path"].orEmpty(),
            system = isSystemApp(file.path.removePrefix(root.path).let { if (it.startsWith("/")) it else "/$it" }, id, values["Categories"].orEmpty()),
        )
    }

    /** `Name[id_ID]`, lalu `Name[id]`, lalu `Name` tanpa lokal. */
    private fun localized(values: Map<String, String>, key: String): String {
        val loc = Locale.getDefault()
        val lang = when (loc.language) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> loc.language }
        if (loc.country.isNotEmpty()) values["$key[${lang}_${loc.country}]"]?.takeIf { it.isNotEmpty() }?.let { return it }
        values["$key[$lang]"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return values[key].orEmpty()
    }

    // ------------------------------------------------------------------ ikon

    // tema -> daftar (ukuran, folder apps) yang benar-benar ada; dibangun sekali per pemindaian
    private val themeCache = HashMap<String, List<Pair<Int, File>>>()
    private const val SCALABLE = Int.MAX_VALUE
    private val SIZE_DIR = Regex("(\\d+)x\\1")

    private fun appDirs(theme: File): List<Pair<Int, File>> = synchronized(themeCache) {
        themeCache.getOrPut(theme.path) {
            val out = ArrayList<Pair<Int, File>>()
            for (d in theme.listFiles().orEmpty()) {
                val n = d.name
                val m = SIZE_DIR.matchEntire(n)
                when {
                    n == "scalable" -> out += SCALABLE to File(d, "apps")
                    m != null -> out += m.groupValues[1].toInt() to File(d, "apps")
                    // tata letak lain (mis. Papirus): apps/<ukuran>/
                    n == "apps" -> d.listFiles().orEmpty().forEach { s -> s.name.toIntOrNull()?.let { out += it to s } }
                }
            }
            out.filter { it.second.isDirectory }
        }
    }

    /**
     * Berkas ikon untuk [icon]: PNG ukuran paling dekat 96 px di tema mana pun (hicolor lebih dulu),
     * lalu SVG, lalu pixmaps. Null bila tidak ada, dan kartu menampilkan huruf pertama nama aplikasi.
     */
    fun iconFile(root: File, icon: String): File? {
        if (icon.isEmpty()) return null
        if (icon.startsWith("/")) return resolve(root, File(root, icon.removePrefix("/")))?.takeIf { it.isFile }
        val name = icon.removeSuffix(".png").removeSuffix(".svg").removeSuffix(".xpm")
        val themes = ArrayList<File>()
        for (r in ICON_ROOTS) {
            val rootDir = File(root, r)
            val hicolor = File(rootDir, "hicolor")
            if (hicolor.isDirectory) themes += hicolor
            rootDir.listFiles()?.filter { it.isDirectory && it.name != "hicolor" }?.sortedBy { it.name }?.let { themes += it }
        }
        val dirs = themes.flatMap { appDirs(it) }
        // PNG: per tema, ukuran terdekat; tema yang lebih awal menang
        for (theme in themes) {
            val png = appDirs(theme).filter { it.first != SCALABLE }
                .map { (size, dir) -> size to File(dir, "$name.png") }
                .filter { it.second.exists() }
                .minWithOrNull(compareBy<Pair<Int, File>>({ kotlin.math.abs(it.first - WANTED_ICON_PX) }, { -it.first }))
            png?.second?.let { f -> resolve(root, f)?.takeIf { it.isFile }?.let { return it } }
        }
        for ((_, dir) in dirs) {
            val svg = File(dir, "$name.svg")
            if (svg.exists()) resolve(root, svg)?.takeIf { it.isFile }?.let { return it }
        }
        for (ext in listOf("png", "svg")) {
            val f = File(root, "usr/share/pixmaps/$name.$ext")
            if (f.exists()) resolve(root, f)?.takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    private val bitmaps = object : LruCache<String, Bitmap>(64) {}

    /** Membuang bitmap ikon [icon] dari cache (berkas ikonnya baru diganti atau dihapus). */
    fun forgetIcon(icon: String) {
        bitmaps.snapshot().keys.filter { it.startsWith("$icon@") }.forEach { bitmaps.remove(it) }
    }

    /**
     * Ikon aplikasi sebagai bitmap sisi paling lama ~[px]; di-cache. Shortcut .exe tanpa ikon di tema
     * mencoba mengambilnya dari .exe-nya ([ExeShortcuts.fallbackIcon]). Dipanggil di thread latar.
     */
    fun icon(root: File, app: App, px: Int): Bitmap? {
        // ikon kosong (shortcut .exe lama) tidak boleh berbagi kunci: pakai id aplikasinya
        val key = "${app.icon.ifEmpty { app.id }}@$px"
        bitmaps.get(key)?.let { return it }
        val file = iconFile(root, app.icon) ?: ExeShortcuts.fallbackIcon(app) ?: return null
        val bmp = runCatching { if (isSvg(file)) renderSvg(file, px) else decodeScaled(file, px) }.getOrNull() ?: return null
        bitmaps.put(key, bmp)
        return bmp
    }

    private fun decodeScaled(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun isSvg(f: File): Boolean = try {
        f.inputStream().use { val head = ByteArray(1024); val n = it.read(head); n > 0 && String(head, 0, n).contains("<svg") }
    } catch (e: Exception) { false }

    private fun renderSvg(file: File, px: Int): Bitmap? {
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }
}
