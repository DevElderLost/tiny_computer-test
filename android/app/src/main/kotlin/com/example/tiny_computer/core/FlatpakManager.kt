package com.example.tiny_computer.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.R
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Flathub Store untuk kontainer Debian 13 (trixie), dipindahkan dari `FlatpakManager` milik DroidDeck.
 *
 * Pemasangan Flatpak ([beginSetup]) dijalankan di TERMINAL kontainer lewat skrip `tiny-flatpak-setup` (apt +
 * remote Flathub per pengguna), jadi setiap langkah dan galatnya terlihat. Setelah itu semua perintah toko
 * (pasang, hapus, perbarui) berjalan tanpa terminal di proot sekali pakai ([Launcher.runGuest]) lewat pembantu
 * Python `tiny-flatpak` yang mencetak satu objek JSON per baris (`op`, `progress`, `error`, `warning`, `done`).
 *
 * Android tidak punya user namespace, jadi bubblewrap asli tidak bisa jalan. Flatpak diarahkan ke `tiny-bwrap`
 * (`FLATPAK_BWRAP`) yang meneruskan sandbox ke [BwrapSpawner] di sisi aplikasi.
 *
 * Semua berkas pendukung ada di assets `tiny/flatpak` dan disalin ke `$DATA_DIR/tiny/flatpak`, yang di dalam
 * kontainer terlihat sebagai `~/.local/share/tiny/flatpak`. Instalasi flatpak memakai satu instalasi per pengguna
 * di `~/.local/share/flatpak`, jadi tidak perlu helper sistem Flatpak maupun polkit.
 */
object FlatpakManager {
    private const val TAG = "TinyFlatpak"
    private const val ASSET_DIR = "tiny/flatpak"
    private const val ARCH = "aarch64"

    const val GUEST_DIR = "/home/tiny/.local/share/tiny/flatpak"
    const val BWRAP = "$GUEST_DIR/tiny-bwrap"
    private const val HELPER = "$GUEST_DIR/tiny-flatpak"
    private const val SETUP = "$GUEST_DIR/tiny-flatpak-setup"
    private const val GUEST_EXPORTS = "/home/tiny/.local/share/flatpak/exports/share"
    private const val USER_DIR = "home/tiny/.local/share/flatpak"

    private val FILES = listOf("tiny-flatpak-setup", "tiny-flatpak", "tiny-bwrap", "flathub.flatpakrepo")

    /** Tab Flathub tampil bila true. Menjadi state Compose supaya rel/bilah navigasi ikut berubah saat saklar diubah. */
    var enabled by mutableStateOf(Cfg.flathubStore)
        private set

    fun enable(on: Boolean) {
        Cfg.flathubStore = on
        enabled = on
    }

    /** Aksi yang sedang berjalan (pasang/hapus/perbarui); null bila tidak ada. Satu aksi pada satu waktu. */
    @Volatile var busy: String? = null
        private set

    class App(val id: String, val name: String, val icon: File?, val branch: String, val summary: String?)

    fun hostDir(): File = File(TinyEnv.dataPath, "tiny/flatpak")

    @Volatile private var staged = false

    /** Menyalin skrip pendukung dari assets (selalu menimpa supaya pembaruan aplikasi ikut). Mengembalikan galat, atau null. */
    fun stageFiles(ctx: Context): String? = try {
        val dir = hostDir().apply { mkdirs() }
        for (name in FILES) {
            val dst = File(dir, name)
            ctx.assets.open("$ASSET_DIR/$name").use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
        }
        staged = true
        null
    } catch (t: Throwable) {
        Log.e(TAG, "menyalin skrip flatpak gagal", t)
        t.message ?: t.javaClass.simpleName
    }

    /**
     * Env tambahan untuk proses guest yang menjalankan flatpak (`env -i ... $EXTRA_OPT`), dan menyalakan
     * [BwrapSpawner]. Juga memasukkan ekspor flatpak pengguna ke XDG_DATA_DIRS supaya aplikasinya muncul di menu.
     */
    fun sessionEnv(ctx: Context): String {
        if (!staged) stageFiles(ctx)
        BwrapSpawner.start(ctx)
        return "FLATPAK_BWRAP=$BWRAP TINY_BWRAP_SOCKET=${BwrapSpawner.socket(ctx)} XDG_DATA_DIRS=$GUEST_EXPORTS:/usr/local/share:/usr/share"
    }

    // ------------------------------------------------------------------ pemasangan (terminal)

    /** Flatpak, pembantu Python, dan remote Flathub sudah terpasang di rootfs. Dibaca langsung dari berkas, tanpa proses. */
    fun ready(): Boolean {
        val root = TinyEnv.containerDir()
        if (!File(root, "usr/bin/flatpak").exists()) return false
        if (!File(root, "usr/lib/python3/dist-packages/gi/__init__.py").isFile) return false
        val config = runCatching { File(root, "$USER_DIR/repo/config").readText() }.getOrNull() ?: return false
        return config.contains("[remote \"flathub\"]")
    }

    /** Skrip pasang sedang berjalan di terminal; TinyApp menunggu penandanya lalu menutup tampilan terminal. */
    var setupWatching by mutableStateOf(false)
        private set

    fun stopWatchingSetup() { setupWatching = false }

    /** Skrip menulis berkas ini di langkah terakhir; tidak ada bila gagal (terminal dibiarkan agar galatnya terbaca). */
    fun isSetupDone(): Boolean = File(hostDir(), "setup.done").exists()

    /** Alasan kegagalan yang ditulis skrip pasang, atau null. */
    fun setupFailure(): String? = File(hostDir(), "setup.failed").takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }

    /**
     * Menjalankan skrip pasang Flatpak + Flathub di terminal kontainer. Kontainer dinyalakan dulu bila belum
     * berjalan (perintahnya ditulis ke terminal begitu siap). Mengembalikan galat, atau null bila sudah dikirim.
     */
    fun beginSetup(ctx: Context): String? {
        stageFiles(ctx)?.let { return it }
        File(hostDir(), "setup.done").delete()
        File(hostDir(), "setup.failed").delete()
        // saklar Pengaturan > Log: keluaran skrip pasang ikut ditulis ke Download/Wayland-logs (launch-*-flatpak-setup.log)
        // lewat tee, seperti keluaran aplikasi Shortcut; /storage dimount apa adanya ke guest.
        val log = LaunchLog.begin("flatpak-setup")
        LaunchLog.write(log, "flatpak", "pasang Flatpak + Flathub (Debian trixie) di terminal kontainer")
        val command = if (log == null) "clear; bash $SETUP" else "clear; bash $SETUP 2>&1 | tee -a '${log.file.absolutePath}'"
        setupWatching = true
        if (Startup.phase == Startup.Phase.DONE && Launcher.isStarted) {
            Launcher.runInTerminal(ctx, command)
            NavRequest.terminal = true
        } else {
            Startup.beginForTerminal(ctx, command)
        }
        return null
    }

    // ------------------------------------------------------------------ aplikasi terpasang

    fun installedApps(): List<App> {
        val apps = File(TinyEnv.containerDir(), "$USER_DIR/app")
        return apps.listFiles()?.mapNotNull { dir ->
            val id = dir.name
            val branch = File(dir, ARCH).listFiles()?.firstOrNull { File(it, "active").exists() } ?: return@mapNotNull null
            val export = File(File(branch, "active"), "export/share")
            val desktop = runCatching { File(export, "applications/$id.desktop").readText() }.getOrNull()
            val name = desktop?.let { entry(it, "Name") } ?: id.substringAfterLast('.')
            App(id, name, icon(export, id), branch.name, desktop?.let { entry(it, "Comment") })
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun isInstalled(id: String): Boolean =
        File(TinyEnv.containerDir(), "$USER_DIR/app/$id/$ARCH").listFiles()?.any { File(it, "active").exists() } == true

    private fun entry(desktop: String, key: String): String? {
        var inMain = false
        for (line in desktop.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inMain = t == "[Desktop Entry]"; continue }
            if (inMain && t.startsWith("$key=")) return t.substringAfter('=').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun icon(export: File, id: String): File? {
        val hicolor = File(export, "icons/hicolor")
        hicolor.listFiles()
            ?.mapNotNull { size -> size.name.substringBefore('x').toIntOrNull()?.let { it to File(size, "apps/$id.png") } }
            ?.filter { it.second.isFile }
            ?.maxByOrNull { it.first }?.second?.let { return it }
        // Audacity dan lainnya hanya membawa SVG (scalable) atau ikon di app-info/appstream
        File(hicolor, "scalable/apps/$id.svg").takeIf { it.isFile }?.let { return it }
        return DesktopApps.iconFile(TinyEnv.containerDir(), id)
    }

    // ------------------------------------------------------------------ perintah toko (tanpa terminal)

    /** Sistem Linux terpasang, skrip tersalin, dan data proot siap (kontainer tidak perlu menyala). Galat, atau null. */
    private fun prepare(ctx: Context): String? {
        if (!Startup.installed) return ctx.getString(R.string.tc_fh_need_system)
        if (!ready()) return ctx.getString(R.string.tc_fh_not_ready)
        stageFiles(ctx)?.let { return it }
        ensureMonitorResolv()
        val latch = CountDownLatch(1)
        Startup.prepareForApp(ctx) { latch.countDown() }
        if (!latch.await(120, TimeUnit.SECONDS)) return ctx.getString(R.string.tc_fh_prepare_timeout)
        return null
    }

    /**
     * Runtime KDE (6.10 dst.) membawa `etc/resolv.conf` berupa symlink ke `/run/host/monitor/resolv.conf`. Saat
     * deploy, Flatpak hanya menghapus tautan itu bila GIO melihatnya ada, dan GIO mengikuti symlink: bila sasarannya
     * tidak ada, tautan tertinggal dan pembuatan tautan baru gagal ("File exists"), sehingga pemasangan aplikasi
     * yang butuh runtime KDE batal. Pembantu Python mencoba membuat sasarannya sebagai pengguna `tiny`, tetapi
     * `/run` di rootfs bisa tidak dapat ditulis dari guest. Di sini berkasnya dibuat dari sisi Android, tempat
     * rootfs sepenuhnya milik aplikasi, sebelum tiap perintah toko.
     */
    private fun ensureMonitorResolv() {
        runCatching {
            val root = TinyEnv.containerDir()
            val target = File(root, "run/host/monitor/resolv.conf")
            if (target.isFile) return
            target.parentFile?.mkdirs()
            Files.deleteIfExists(target.toPath()) // symlink putus dari percobaan sebelumnya
            val src = DesktopApps.resolve(root, File(root, "etc/resolv.conf"))?.takeIf { it.isFile }
            target.writeText(src?.readText()?.takeIf { it.isNotBlank() } ?: "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            target.setReadable(true, false)
        }.onFailure { Log.w(TAG, "menyiapkan /run/host/monitor/resolv.conf gagal", it) }
    }

    private fun runGuest(ctx: Context, verb: String, args: List<String>, onLine: (String) -> Unit): Int {
        var argv = listOf("/usr/bin/python3", HELPER, verb) + args
        // Tanpa D-Bus sesi, akhir pemasangan gagal "Could not connect: No such file or directory" (log pemasangan
        // Azahar): bungkus dengan bus sesi sementara bila dbus-run-session ada di kontainer.
        if (File(TinyEnv.containerDir(), "usr/bin/dbus-run-session").exists()) argv = listOf("/usr/bin/dbus-run-session", "--") + argv
        val log = runCatching { File(hostDir(), "flatpak-$verb.log").printWriter() }.getOrNull()
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        // saklar Pengaturan > Log: satu berkas per perintah di Download/Wayland-logs (launch-*-flatpak-<perintah>.log);
        // baris progres dilewati karena dikirim tiap 0,5 detik
        val session = LaunchLog.begin("flatpak-$verb")
        LaunchLog.write(session, "flatpak", argv.joinToString(" "))
        var code = -1
        try {
            val env = sessionEnv(ctx) + " XDG_RUNTIME_DIR=/tmp XDG_DATA_HOME=/home/tiny/.local/share PYTHONUNBUFFERED=1"
            code = Launcher.runGuest(ctx, argv, env) { line ->
                log?.println(line)
                log?.flush()
                if (session != null && !line.startsWith("{\"e\": \"progress\"")) LaunchLog.write(session, "flatpak", line)
                onLine(line)
            }
            return code
        } finally {
            LaunchLog.write(session, "exit", "$verb keluar dengan kode $code")
            log?.close()
        }
    }

    private inline fun <T> exclusive(what: String, ifBusy: () -> T, block: () -> T): T {
        synchronized(this) {
            if (busy != null) return ifBusy()
            busy = what
        }
        try { return block() } finally { busy = null }
    }

    private fun transaction(ctx: Context, verb: String, id: String?, onProgress: (String, Int) -> Unit): String? {
        prepare(ctx)?.let { return it }
        return exclusive("$verb:${id ?: "all"}", { ctx.getString(R.string.tc_fh_busy) }) {
            var error: String? = null
            var step = ctx.getString(R.string.tc_fh_starting)
            val status = runGuest(ctx, verb, listOfNotNull(id)) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull()
                if (o == null) { Log.i(TAG, "$verb: $line"); return@runGuest }
                when (o.optString("e")) {
                    "op" -> { step = opLabel(ctx, o); onProgress(step, overall(o, 0)) }
                    "progress" -> onProgress(step, overall(o, o.optInt("percent")))
                    "error" -> { error = o.optString("message"); Log.w(TAG, "$verb $id: $error") }
                    "warning" -> Log.w(TAG, "$verb $id: ${o.optString("message")}")
                }
            }
            error ?: if (status != 0) ctx.getString(R.string.tc_fh_exit_status, status) else null
        }
    }

    private fun opLabel(ctx: Context, o: JSONObject): String {
        val ref = o.optString("ref")
        if (ref.isEmpty()) return o.optString("status").ifEmpty { ctx.getString(R.string.tc_fh_working) }
        val name = refLabel(ctx, ref)
        val n = o.optInt("n")
        val kind = o.optString("kind")
        return if (n > 1) ctx.getString(when (kind) {
            "uninstall" -> R.string.tc_fh_removing_step
            "update" -> R.string.tc_fh_updating_step
            else -> R.string.tc_fh_installing_step
        }, name, o.optInt("i"), n)
        else ctx.getString(when (kind) {
            "uninstall" -> R.string.tc_fh_removing
            "update" -> R.string.tc_fh_updating
            else -> R.string.tc_fh_installing
        }, name)
    }

    /** Nama yang enak dibaca untuk ref flatpak (`app/<id>/<arch>/<cabang>` atau `runtime/...`). */
    private fun refLabel(ctx: Context, ref: String): String {
        val parts = ref.split('/')
        val id = parts.getOrNull(1) ?: return ref
        if (parts.firstOrNull() == "app") return id.substringAfterLast('.')
        val branch = parts.getOrNull(3)
        return when {
            id.endsWith(".Locale") -> ctx.getString(R.string.tc_fh_ref_translations)
            ".GL." in id || ".GL32." in id -> ctx.getString(R.string.tc_fh_ref_graphics)
            "codecs" in id.lowercase() || id.endsWith(".ffmpeg-full") -> ctx.getString(R.string.tc_fh_ref_codecs)
            id.endsWith(".Platform") -> {
                val vendor = when (val v = id.removeSuffix(".Platform").substringAfterLast('.')) {
                    "gnome", "kde" -> v.uppercase()
                    else -> v.replaceFirstChar { it.uppercase() }
                }
                if (branch != null) ctx.getString(R.string.tc_fh_ref_runtime_branch, vendor, branch)
                else ctx.getString(R.string.tc_fh_ref_runtime, vendor)
            }
            else -> id.substringAfterLast('.')
        }
    }

    private fun overall(o: JSONObject, percent: Int): Int {
        val n = o.optInt("n").coerceAtLeast(1)
        val i = o.optInt("i").coerceIn(1, n)
        return ((i - 1) * 100 + percent.coerceIn(0, 100)) / n
    }

    fun install(ctx: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(ctx, "install", id, onProgress)
    fun uninstall(ctx: Context, id: String, onProgress: (String, Int) -> Unit) = transaction(ctx, "uninstall", id, onProgress)
    fun update(ctx: Context, id: String?, onProgress: (String, Int) -> Unit) = transaction(ctx, "update", id, onProgress)

    /** Id aplikasi yang punya pembaruan, atau null bila pengecekan gagal. */
    fun updates(ctx: Context): Set<String>? {
        if (prepare(ctx) != null) return emptySet()
        var apps: Set<String>? = null
        exclusive("updates", {}) {
            runGuest(ctx, "updates", emptyList()) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@runGuest
                if (o.optString("e") == "updates") {
                    val a = o.optJSONArray("apps")
                    apps = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
                }
            }
        }
        return apps
    }
}
