package com.example.tiny_computer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.display.PadSetup
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Port `launchCurrentContainer`, `setupAudio`, `launchGUIBackend` dari workflow.dart. */
object Launcher {
    /**
     * Xwayland dari patch.tzst (extra/xwayland-dri3-nodrm): DRI3 tanpa DRM. Dipakai hanya bila toggle
     * "Enable DRI3" aktif dan berkasnya ada; selain itu Xwayland bawaan rootfs (tanpa DRI3, lihat
     * MESA_VK_WSI_DEBUG di guestSpec). Dibaca dari sisi guest: folder extra di-mount ke ~/.local/share/tiny/extra.
     */
    /** DRM palsu untuk glamor Xwayland: butuh toggle-nya, DRI3 + Turnip, libfakedrm.so dan Xwayland hasil patch di extra. */
    fun fakeDrmActive(): Boolean {
        val extra = File(TinyEnv.dataPath, "tiny/extra")
        return Cfg.fakeDrm && Cfg.dri3 && Cfg.turnip &&
            File(extra, "libfakedrm.so").isFile && File(extra, "Xwayland").isFile
    }

    /** Awalan variabel lingkungan untuk proses Xwayland saja (LD_PRELOAD tidak dipasang ke proses guest lain). */
    fun xwaylandEnv(): String =
        if (fakeDrmActive())
            "FAKEDRM_ROOT=/tmp/.fakedrm LD_PRELOAD=\"\${LD_PRELOAD:+\$LD_PRELOAD:}/home/tiny/.local/share/tiny/extra/libfakedrm.so\" "
        else ""

    fun xwaylandBin(): String =
        if (Cfg.dri3 && File(TinyEnv.dataPath, "tiny/extra/Xwayland").isFile)
            "/home/tiny/.local/share/tiny/extra/Xwayland"
        else "Xwayland"

    @Volatile private var audioProcess: Process? = null
    @Volatile private var started = false

    /** True setelah perintah Xwayland + Xsession dikirim ke kontainer yang sedang berjalan. */
    // state Compose (bukan @Volatile biasa) supaya tombol "kembali ke desktop" muncul/hilang mengikuti status desktop
    var guiStarted by mutableStateOf(false)

    /**
     * Proses proot terpisah yang menjalankan Xwayland (+ window manager ringan) khusus untuk aplikasi
     * dari menu Shortcut, tanpa sesi desktop. Bukan bagian dari shell kontainer (PTY): hidup sendiri,
     * seperti proses guest DroidDeck. Membuka desktop menggantikan sesi ini ([stopAppSession]).
     */
    @Volatile private var appSession: Process? = null

    /** Proot terpisah milik tiap aplikasi Shortcut yang sedang berjalan. */
    private val appProcs = ArrayList<Process>()

    /** True selama proot sesi aplikasi (Xwayland) masih hidup. */
    val appSessionStarted: Boolean get() = appSession?.isAlive == true

    /** Berkas log peluncuran ([LaunchLog]) per sesi proot: shell kontainer (+ Open desktop) dan sesi aplikasi Shortcut. */
    @Volatile private var containerLog: LaunchLog.Session? = null
    @Volatile private var appSessionLog: LaunchLog.Session? = null

    /** Menulis ke log sesi Shortcut yang sedang berjalan (atau log kontainer); dipakai [BwrapSpawner]. */
    internal fun logApp(area: String, msg: String) = LaunchLog.write(appSessionLog ?: containerLog, area, msg)

    /** Layanan sisi Android (getifaddrs bridge, virgl) yang dinyalakan sekali per proses aplikasi. */
    private val services = HashMap<String, Process>()
    private val spawnLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Aplikasi Shortcut terakhir yang langsung berhenti dengan kode galat (untuk pesan ke pengguna). */
    @Volatile private var lastFailedApp: String? = null

    /**
     * Dipanggil di thread utama ketika semua aplikasi Shortcut sudah selesai (atau Xwayland sesi mati)
     * dan sesi aplikasi ditutup; argumennya id aplikasi yang gagal start, atau null. Didaftarkan oleh
     * layar aplikasi ([com.example.tiny_computer.display.DisplayActivity]) untuk kembali ke menu Shortcut.
     */
    @Volatile var onAppSessionEnded: ((String?) -> Unit)? = null

    /**
     * Generasi sesi aplikasi. Naik setiap Shutdown; peluncuran yang sudah diminta sebelum Shutdown
     * (tiketnya lama) dibatalkan, dan pemantau progres lama berhenti.
     */
    @Volatile private var epoch = 0

    /** Tahap sesi aplikasi Shortcut yang ditampilkan layar progres di layar aplikasi. */
    enum class AppPhase { IDLE, STARTING, RUNNING, STOPPING }

    /** STARTING: menunggu proot/Xwayland/aplikasi menyala. STOPPING: menunggu semua proses benar-benar mati. */
    var appPhase by mutableStateOf(AppPhase.IDLE)
        private set

    /** Indeks segmen progres (0-based) untuk [appPhase]; tidak pernah mundur selama satu fase. */
    var appStage by mutableIntStateOf(0)
        private set

    /**
     * Aplikasi Shortcut sudah selesai sendiri dan sesi akan ditutup beberapa detik lagi (menunggu pemeriksaan
     * "sesi kosong"). Layar progres Shutdown tampil sejak saat itu, bukan layar hitam kosong.
     */
    var appClosing by mutableStateOf(false)

    /** Tahap layar progres Shutdown (hanya bila sedang [AppPhase.STOPPING]; tahap Start tidak ikut berubah). */
    private fun stopStage(to: Int) {
        if (appPhase == AppPhase.STOPPING) advanceStage(to)
    }

    private fun advanceStage(to: Int) {
        if (to > appStage) appStage = to
    }

    /**
     * Cek "sesi aplikasi sudah kosong?" setelah jeda. Jeda perlu karena peluncur/aplikasi single-instance
     * (mis. Firefox menyerahkan ke jendela lama) bisa keluar sebentar lalu aplikasinya muncul lagi.
     */
    private val idleCheck = Runnable {
        if (guiStarted || appPhase == AppPhase.STOPPING) return@Runnable
        val session = appSession ?: return@Runnable
        if (session.isAlive && appsBusy()) { appClosing = false; return@Runnable }
        val failed = lastFailedApp
        // matikan dan tunggu sampai benar-benar mati sebelum layar ditutup; bila sementara itu pengguna
        // sudah membuka aplikasi lain (STARTING), layar tidak boleh ikut ditutup
        stopAndWait { if (appPhase != AppPhase.STARTING) onAppSessionEnded?.invoke(failed) }
    }

    private fun scheduleIdleCheck(delayMs: Long) {
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.postDelayed(idleCheck, delayMs)
    }

    private fun watch(name: String, block: () -> Unit) {
        Thread({ block() }, name).apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ pohon proses guest

    /** PID proot tiap proses terlepas (dibaca dari baris `TINYPID=` di keluarannya). */
    private val pids = java.util.concurrent.ConcurrentHashMap<Process, Int>()

    private class ProcInfo(val ppid: Int, val tracer: Int, val zombie: Boolean, val comm: String)

    /** Memindai /proc: induk, tracer (proot menjadi tracer semua proses guest), status zombie, dan nama proses. */
    private fun scanProcs(): Map<Int, ProcInfo> {
        val out = HashMap<Int, ProcInfo>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            runCatching {
                var ppid = 0
                var tracer = 0
                var zombie = false
                File(d, "status").forEachLine { l ->
                    when {
                        l.startsWith("PPid:") -> ppid = l.substring(5).trim().toIntOrNull() ?: 0
                        l.startsWith("TracerPid:") -> tracer = l.substring(10).trim().toIntOrNull() ?: 0
                        l.startsWith("State:") -> zombie = l.substring(6).trim().startsWith("Z")
                    }
                }
                val comm = runCatching { File(d, "comm").readText().trim() }.getOrDefault("")
                out[pid] = ProcInfo(ppid, tracer, zombie, comm)
            }
        }
        return out
    }

    /**
     * [root] beserta semua keturunannya. Proses yang ditinggal induknya (daemon, aplikasi yang
     * melepaskan diri) tetap ketemu lewat TracerPid = proot, jadi tidak lolos hanya karena PPid-nya 1.
     */
    private fun treeOf(root: Int, procs: Map<Int, ProcInfo>): LinkedHashSet<Int> {
        val me = android.os.Process.myPid()
        val tree = LinkedHashSet<Int>()
        tree.add(root)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, i) in procs) {
                if (pid != me && pid !in tree && (i.ppid in tree || i.tracer in tree)) { tree.add(pid); grew = true }
            }
        }
        return tree
    }

    /** SIGKILL [root] dan seluruh pohonnya (anak dulu, akar terakhir); mengembalikan PID yang dikirimi sinyal. */
    private fun killTree(root: Int): List<Int> {
        val order = treeOf(root, scanProcs()).toList().reversed()
        order.forEach { runCatching { android.os.Process.killProcess(it) } }
        return order
    }

    /**
     * Menunggu PID-PID itu benar-benar hilang. Penting sebelum Xwayland baru dinyalakan: Xwayland lama
     * yang masih sekarat menghapus soket `/tmp/.X11-unix/X4` milik Xwayland baru.
     */
    private fun waitGone(targets: Collection<Int>, timeoutMs: Long) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            val procs = scanProcs()
            if (targets.none { procs[it]?.zombie == false }) return
            Thread.sleep(50)
        }
    }

    // ------------------------------------------------------------------ penutupan halus aplikasi

    /** Waktu tunggu setelah jendela X11 diminta menutup, lalu setelah SIGTERM, sebelum SIGKILL. */
    private const val CLOSE_X11_WAIT_MS = 3500L
    private const val CLOSE_TERM_WAIT_MS = 2000L

    /** Menunggu semua [targets] hilang; true bila semuanya sudah hilang sebelum [timeoutMs]. */
    private fun waitGoneUntil(targets: Collection<Int>, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val procs = scanProcs()
            if (targets.none { procs[it]?.zombie == false }) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(50)
        }
    }

    /**
     * Proses aplikasi yang boleh diminta menutup diri: keturunan sandbox Flatpak (BUKAN proot-nya) bila ada, kalau
     * tidak keturunan proot aplikasi Shortcut. Proot, shim `tiny-bwrap`, dan `flatpak run` sengaja tidak disentuh:
     * begitu shim berhenti, [BwrapSpawner] mematikan sandbox, dan aplikasinya dibunuh sebelum sempat menyimpan.
     */
    private fun gracefulTargets(appRoots: List<Int>): List<Int> {
        val procs = scanProcs()
        val sandboxes = BwrapSpawner.sandboxPids().filter { procs[it]?.zombie == false }
        val roots = if (sandboxes.isNotEmpty()) sandboxes else appRoots
        val out = LinkedHashSet<Int>()
        for (r in roots) {
            if (procs[r] == null) continue
            for (p in treeOf(r, procs)) {
                val i = procs[p] ?: continue
                if (p == r || i.zombie || i.comm.startsWith("proot")) continue
                out += p
            }
        }
        return out.toList()
    }

    /**
     * Menjalankan `tiny-close-windows.py` di proot sekali pakai: mengirim WM_DELETE_WINDOW ke jendela X11 sesi.
     * Mengembalikan jumlah jendela yang diminta menutup (0 bila python3/libX11 tidak ada atau tidak ada jendela).
     */
    private fun closeX11Windows(): Int {
        val ctx = TinyEnv.app
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        runCatching {
            val script = File(TinyEnv.dataPath, "tiny/shortcuts/tiny-close-windows.py")
            script.parentFile?.mkdirs()
            val text = ctx.assets.open("tiny/tiny-close-windows.py").bufferedReader().use { it.readText() }
            if (!script.isFile || script.readText() != text) script.writeText(text)
            script.setReadable(true, false)
            val t = Thread({
                runCatching {
                    runGuest(ctx, listOf("/usr/bin/python3", "/home/tiny/.local/share/tiny/shortcuts/tiny-close-windows.py"), "DISPLAY=:4") { line ->
                        if (line.startsWith("CLOSED=")) count.set(line.substringAfter('=').trim().toIntOrNull() ?: 0)
                    }
                }
            }, "tiny-close-x11").apply { isDaemon = true; start() }
            t.join(5000)
        }.onFailure { Log.w("Launcher", "closeX11Windows: ${it.message}") }
        return count.get()
    }

    /**
     * Meminta compositor mengirim `xdg_toplevel.close` ke semua jendela program Wayland-native (Qt `wayland`, GTK,
     * SDL, Flatpak di jalur Wayland). Jendela Xwayland sengaja dilewati (lihat compositor.c). Mengembalikan jumlah
     * jendela yang dijangkau; 0 bila compositor tidak berjalan.
     */
    private fun closeWaylandWindows(): Int = runCatching {
        com.droiddeck.launcher.wayland.WaylandCompositor.nativeCloseToplevels()
        Thread.sleep(250) // thread compositor menjawab lewat pipa; jumlahnya tersedia sebentar kemudian
        com.droiddeck.launcher.wayland.WaylandCompositor.nativeCloseCount()
    }.getOrDefault(0)

    /**
     * Meminta aplikasi sesi Shortcut menutup dirinya sendiri SEBELUM proses dibunuh. Dulu sesi langsung di-SIGKILL
     * sehingga aplikasi yang menulis konfigurasi saat keluar (Azahar/Dolphin qt-config.ini, ppsspp.ini, ...) tidak
     * pernah sempat menyimpannya dan pengaturan kembali ke awal pada peluncuran berikutnya. Urutan: tutup jendela X11
     * (jalur keluar normal Qt/GTK/SDL) -> SIGTERM -> (pemanggil) SIGKILL untuk yang masih tersisa.
     * Memblokir sampai ~5,5 detik; panggil hanya dari thread latar.
     */
    private fun closeGracefully(appRoots: List<Int>, log: LaunchLog.Session?) {
        // aplikasi sudah keluar sendiri (tinggal proses pendukung seperti dbus): tidak perlu menunggu apa pun
        val procs = scanProcs()
        if ((appRoots + BwrapSpawner.sandboxPids()).none { treeBusy(it, procs) }) {
            LaunchLog.write(log, "stop", "tidak ada aplikasi yang masih berjalan; tidak perlu ditutup halus")
            return
        }
        var targets = gracefulTargets(appRoots)
        if (targets.isEmpty()) return
        LaunchLog.write(log, "stop", "meminta ${targets.size} proses aplikasi menutup diri supaya konfigurasi tersimpan")
        val wl = closeWaylandWindows()
        if (wl > 0) LaunchLog.write(log, "stop", "$wl jendela Wayland diminta menutup (xdg_toplevel.close)")
        run {
            val x11 = if (File(TinyEnv.runtimeDir(), ".X11-unix/X4").exists()) closeX11Windows() else 0
            if (x11 > 0) LaunchLog.write(log, "stop", "$x11 jendela X11 diminta menutup (WM_DELETE_WINDOW)")
            val closed = wl + x11
            stopStage(1) // permintaan tutup sudah terkirim: sekarang menunggu aplikasi menyimpan pengaturannya
            if (closed > 0) {
                // hanya aplikasi utama yang ditunggu; daemon pendukung (dbus, dst.) tidak ikut menutup bersama jendelanya
                val procsNow = scanProcs()
                val main = targets.filter { pid -> procsNow[pid]?.let { !isHelper(it.comm) } == true }
                if (waitGoneUntil(main, CLOSE_X11_WAIT_MS)) {
                    LaunchLog.write(log, "stop", "aplikasi keluar sendiri setelah jendela ditutup")
                    return
                }
            }
        }
        stopStage(2)
        targets = gracefulTargets(appRoots)
        targets.forEach { runCatching { android.system.Os.kill(it, android.system.OsConstants.SIGTERM) } }
        val ok = waitGoneUntil(targets, CLOSE_TERM_WAIT_MS)
        LaunchLog.write(log, "stop", if (ok) "aplikasi keluar sendiri setelah SIGTERM" else "sebagian proses masih jalan; dimatikan paksa (SIGKILL)")
    }

    // proses pendukung yang sering menetap setelah aplikasinya keluar; tidak dihitung sebagai "aplikasi masih jalan"
    private val HELPER_PREFIXES = listOf(
        "dbus", "at-spi", "dconf", "gvfs", "fcitx", "ibus", "pulseaudio", "pipewire", "wireplumber",
        "xdg-", "xfce4-notifyd", "notify", "gnome-keyring", "chrome_crashpad", "crashpad", "proot",
    )

    private fun isHelper(comm: String) = HELPER_PREFIXES.any { comm.startsWith(it) }

    /** True bila di bawah proot [root] masih ada proses selain pendukung (aplikasinya, atau peluncurnya yang membuat aplikasi). */
    private fun treeBusy(root: Int, procs: Map<Int, ProcInfo>): Boolean =
        treeOf(root, procs).any { pid ->
            pid != root && procs[pid]?.let { !it.zombie && !isHelper(it.comm) } == true
        }

    /**
     * Ada aplikasi Shortcut yang masih bekerja? Tanpa `--kill-on-exit` proot aplikasi tetap hidup selama
     * ada proses guest apa pun, jadi "proot hidup" tidak cukup: daemon pendukung membuatnya hidup terus.
     */
    private fun appsBusy(): Boolean {
        val live = synchronized(appProcs) {
            appProcs.removeAll { !it.isAlive }
            appProcs.toList()
        }
        if (live.isEmpty()) return false
        val procs = scanProcs()
        return live.any { p ->
            val pid = pids[p] ?: 0
            pid == 0 || treeBusy(pid, procs)
        }
    }

    /** proot Shortcut yatim: sisa proses dari masa hidup aplikasi sebelumnya yang tidak lagi kita pantau. */
    private fun sweepStale(): List<Int> {
        val killed = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.contains("/tiny/shortcuts/") && cmd.contains("proot")) killed += killTree(pid)
        }
        return killed
    }
    @Volatile private var padCache: PadSetup.Result? = null

    /** Controller virtual sesi (dibuat sekali, dipakai ulang semua proot); dibuat bila belum ada. Untuk sandbox Flatpak. */
    fun padResult(ctx: Context): PadSetup.Result = padCache ?: PadSetup.prepare(ctx).also { padCache = it }

    /** Hasil yang sudah ada tanpa membuat baru; null bila sesi belum menyiapkan controller. */
    fun padPeek(): PadSetup.Result? = padCache

    /** True setelah perintah boot ditulis ke terminal (kontainer sedang/ sudah berjalan). */
    val isStarted: Boolean get() = started

    /**
     * Audio langsung ke Android: plugin ALSA `tinyaudio` di guest -> soket unix -> AAudio (cpp/tinyaudio),
     * tanpa PulseAudio. Aktif bila toggle menyala dan plugin ada di folder extra (dibangun CI); selain itu
     * jalur lama (pulseaudio + OpenSL ES) dipakai seperti biasa.
     */
    fun directAudioActive(): Boolean =
        Cfg.directAudio && TinyAudio.available &&
            File(TinyEnv.dataPath, "tiny/extra/libasound_module_pcm_tinyaudio.so").isFile

    internal const val ASOUND_MARK = "# tiny-direct-audio"

    internal fun asoundConf(): String = """
$ASOUND_MARK (dibuat otomatis oleh Tiny Computer; matikan "Direct audio" di Pengaturan untuk kembali ke PulseAudio)
pcm_type.tinyaudio {
    lib "/home/tiny/.local/share/tiny/extra/libasound_module_pcm_tinyaudio.so"
}
pcm.tinyaudio_raw {
    type tinyaudio
    socket "${TinyAudio.GUEST_SOCKET}"
}
pcm.tinyaudio {
    type plug
    slave.pcm "tinyaudio_raw"
}
# Keluaran lewat tinyaudio (AAudio langsung). Plugin tinyaudio hanya untuk output.
# MASUKAN bawaan (default) sengaja berupa senyap yang selalu siap (plugin null), BUKAN pulse: aplikasi PortAudio seperti
# Audacity membuka stream duplex (output + input) walau hanya memutar, dan callback-nya menunggu data input di setiap
# siklus. Sumber default pulse di mode direct adalah monitor sink null yang di-suspend (suspend-on-idle) sehingga tidak
# pernah mengirim data -> stream "berjalan" tetapi playhead diam dan tidak ada suara. Untuk merekam dari mikrofon
# virtual pilih perangkat "pulse" (atau "tinymic" di bawah) secara eksplisit di aplikasinya.
pcm.tinyaudio_silent_in {
    type plug
    slave.pcm "null"
}
pcm.tinymic {
    type plug
    slave.pcm "pulse"
}
pcm.tinyaudio_duplex {
    type asym
    playback.pcm "tinyaudio"
    capture.pcm "tinyaudio_silent_in"
}
pcm.!default {
    type plug
    slave.pcm "tinyaudio_duplex"
}
""".trimStart()

    /** Menulis / menghapus /etc/asound.conf di rootfs kontainer saat ini (hanya berkas buatan kita yang disentuh). */
    private fun installGuestAudio(on: Boolean) {
        val f = File(TinyEnv.containerDir(), "etc/asound.conf")
        // asound.conf bawaan rootfs lain (mis. ekspor Tiny Container master) disimpan di sini dan dikembalikan
        // saat Direct audio dimatikan; tanpa ini ALSA tidak pernah diarahkan ke tinyaudio
        val backup = File(f.parentFile, "asound.conf.pre-tiny")
        runCatching {
            val mine = f.isFile && f.readText().startsWith(ASOUND_MARK)
            if (on) {
                if (f.exists() && !mine) {
                    if (backup.exists()) backup.delete()
                    f.renameTo(backup)
                    Log.i("Launcher", "asound.conf milik rootfs dicadangkan ke asound.conf.pre-tiny")
                }
                f.parentFile?.mkdirs()
                val want = asoundConf()
                if (!f.isFile || f.readText() != want) f.writeText(want)
            } else {
                if (mine) f.delete()
                if (!f.exists() && backup.isFile) backup.renameTo(f)
            }
        }.onFailure { Log.w("Launcher", "asound.conf: ${it.message}") }
        // ~/.asoundrc buatan kita (ditulis FlatpakWayland.directAudio) dibaca SEMUA aplikasi lewat $HOME dan menimpa
        // /etc/asound.conf. Tanpa disamakan di sini: (a) Direct audio dimatikan -> berkas tertinggal dan ALSA default
        // tetap menunjuk tinyaudio padahal server native sudah berhenti; (b) isi lama (mis. input pulse) tetap dipakai
        // walau asoundConf() sudah diperbarui. Berkas milik pengguna (tanpa ASOUND_MARK) tidak pernah disentuh.
        runCatching {
            val rc = FlatpakWayland.asoundrcFile()
            if (rc.isFile && rc.readText().startsWith(ASOUND_MARK)) {
                if (!on) {
                    rc.delete()
                } else {
                    val want = asoundConf()
                    if (rc.readText() != want) { rc.writeText(want); rc.setReadable(true, false) }
                }
            }
        }.onFailure { Log.w("Launcher", "~/.asoundrc: ${it.message}") }
    }

    /**
     * pulseaudio.conf ditulis ulang di sini (bukan dari assets.zip) tanpa modul yang membuat suara hilang:
     * suspend-on-idle (sink tertidur dan tidak bangun lagi), role-cork, *-restore (volume lama 0 / mute), filter-*.
     * Mode langsung: tanpa module-opensles. Sink null `tinydirect` menjadi sink bawaan dan monitor-nya dibuka lewat
     * module-simple-protocol-tcp (port pulse + 1, hanya 127.0.0.1) supaya server audio native (AAudio) mengambilnya:
     * aplikasi yang memaksa pulse (SDL, Firefox) dan mixer desktop tetap bekerja tanpa OpenSL ES. Sink null aman diberi
     * suspend-on-idle (tak ada perangkat keras yang perlu dibangunkan) sehingga stream AAudio ditutup saat hening.
     */
    private fun pulseConf(direct: Boolean): String = buildString {
        appendLine("# dibuat otomatis oleh Tiny Computer")
        appendLine(".fail")
        appendLine("load-module module-native-protocol-unix")
        appendLine("load-module module-native-protocol-tcp auth-anonymous=true port=${Cfg.defaultAudioPort}")
        appendLine("load-module module-always-sink")
        if (direct) {
            appendLine("load-module module-null-sink sink_name=tinydirect rate=48000 channels=2 format=s16le")
            appendLine("set-default-sink tinydirect")
            appendLine("load-module module-suspend-on-idle timeout=3")
            appendLine("load-module module-simple-protocol-tcp rate=48000 format=s16le channels=2 source=tinydirect.monitor record=true port=${Cfg.defaultAudioPort + 1} listen=127.0.0.1")
        } else {
            appendLine("load-module module-opensles")
            appendLine("set-default-sink opensles")
        }
    }

    fun setupAudio() {
        audioProcess?.destroy()
        val data = TinyEnv.dataPath
        runCatching { File(data, "bin/pulseaudio.conf.tmp").writeText(pulseConf(directAudioActive())) }
            .onFailure { Log.w("Launcher", "pulseaudio.conf.tmp: ${it.message}") }
        // --exit-idle-time=-1: bawaan pulseaudio mati sendiri setelah 20 dtk tanpa klien; sesudahnya semua suara
        // hilang sampai aplikasi diluncurkan ulang.
        audioProcess = Shell.spawn(
            """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
rm -rf ${'$'}DATA_DIR/pulseaudio_tmp/*
TMPDIR=${'$'}DATA_DIR/pulseaudio_tmp HOME=${'$'}DATA_DIR/pulseaudio_tmp XDG_CONFIG_HOME=${'$'}DATA_DIR/pulseaudio_tmp LD_LIBRARY_PATH=${'$'}DATA_DIR/bin:${'$'}LD_LIBRARY_PATH ${'$'}DATA_DIR/bin/pulseaudio --exit-idle-time=-1 -F ${'$'}DATA_DIR/bin/pulseaudio.conf.tmp
""".trimIndent(),
        )
    }

    /** Output suara: server native + asound.conf (langsung) ATAU pulseaudio (cadangan). */
    private fun ensureAudio() {
        val direct = directAudioActive()
        installGuestAudio(direct)
        if (direct) {
            // pulseaudio tetap dinyalakan: mixer desktop dan aplikasi pulse-only memakainya (keluaran lewat jembatan)
            if (audioProcess?.isAlive != true) setupAudio()
            if (!TinyAudio.start()) Log.w("Launcher", "server audio native gagal start; suara tidak keluar")
            return
        }
        if (TinyAudio.running) TinyAudio.stop()
        if (audioProcess?.isAlive != true) setupAudio()
    }

    /** Mikrofon virtual memakai pulseaudio (pactl/pacat); pastikan menyala. */
    fun ensurePulseForMic() {
        if (audioProcess?.isAlive != true) setupAudio()
    }

    private fun ensureService(name: String, script: String) {
        synchronized(services) {
            if (services[name]?.isAlive == true) return
            services[name] = Shell.spawn(script)
        }
    }

    /**
     * Ukuran output compositor: pengaturan pengguna, atau 75% panel (landscape) bila 0. Bila [appId]
     * diberikan dan aplikasi Shortcut itu punya ukuran layar sendiri ([ShortcutPrefs.Settings.screen]),
     * ukuran itulah yang dipakai.
     */
    fun displaySize(ctx: Context, appId: String? = null): Pair<Int, Int> {
        val custom = ShortcutPrefs.screenSize(appId)
        var w = custom?.first ?: Cfg.displayWidth
        var h = custom?.second ?: Cfg.displayHeight
        if (w <= 0 || h <= 0) {
            val dm: DisplayMetrics = ctx.resources.displayMetrics
            w = (max(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
            h = (min(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
        }
        // lebar/tinggi genap, seperti yang diharapkan Xwayland dan encoder
        return (w and 1.inv()).coerceAtLeast(320) to (h and 1.inv()).coerceAtLeast(240)
    }

    /** Ukuran Xwayland sesi aplikasi yang sedang berjalan (ditetapkan saat sesi dibuat). */
    @Volatile private var appSessionSize: Pair<Int, Int>? = null

    /**
     * Ukuran layar yang benar-benar dipakai untuk [appId]. Desktop yang menyala memegang X, jadi
     * aplikasi di dalamnya ikut ukuran desktop. Sesi aplikasi yang sudah berjalan juga tidak bisa
     * diubah ukurannya, jadi aplikasi berikutnya ikut ukuran sesi itu. Selain itu ukuran khusus
     * aplikasi dipakai untuk sesi baru ([launchAppLocked] memakai fungsi yang sama).
     */
    fun sessionSize(ctx: Context, appId: String?): Pair<Int, Int> {
        if (appId == null || guiStarted) return displaySize(ctx)
        if (appSessionStarted) appSessionSize?.let { return it }
        return displaySize(ctx, appId)
    }

    /**
     * Opsi lingkungan Turnip/Zink untuk guest. ICD diambil dari driver Linux hasil impor
     * ([VulkanDrivers.activeIcd]); bila tidak ada, ICD bawaan di `tiny/extra` dipakai SELAMA berkasnya
     * ada. Dulu path ke `freedreno_icd.aarch64.json` yang tidak ada tetap dikirim, sehingga vulkaninfo
     * gagal "Failed to open JSON file" -> "Found no drivers".
     */
    fun turnipOpt(ctx: Context): String {
        val re = Regex("""\s*VK_(?:ICD_FILENAMES|DRIVER_FILES)=\S+""")
        val base = Cfg.defaultTurnipOpt
        val imported = VulkanDrivers.activeIcd(ctx)
        if (imported != null) {
            Log.i("Launcher", "driver Vulkan Linux hasil impor: $imported")
            return re.replace(base, "").trim() + " VK_ICD_FILENAMES=$imported VK_DRIVER_FILES=$imported"
        }
        val guestPrefix = "/home/tiny/.local/share/tiny/"
        var out = base
        for (m in re.findAll(base)) {
            val value = m.value.substringAfter('=')
            val host = if (value.startsWith(guestPrefix)) File(TinyEnv.dataPath, "tiny/" + value.removePrefix(guestPrefix)) else File(value)
            if (!host.isFile) {
                Log.w("Launcher", "ICD $value tidak ada ($host); impor driver Vulkan Linux di Pengaturan")
                out = out.replace(m.value, "")
            }
        }
        return out.trim()
    }

    /**
     * Perintah di dalam kontainer yang menyiapkan mikrofon virtual: sink null + source remap, lalu klien
     * soket. Memakai `tiny_virtual_mic` bila ada; bila tidak, `socat` + `pacat` (paket socat dan
     * pulseaudio-utils); bila keduanya tidak ada, mencetak petunjuk (tanpa membuat aplikasi macet).
     *
     * `set-default-source AndroidMic` penting: pulseaudio.conf memuat module-opensles yang sudah punya
     * source `input` (mik Android langsung, tidak bisa dipakai dari proot). Tanpa ini aplikasi di
     * guest merekam dari `input`, bukan dari mikrofon virtual, sehingga suara "tidak tertangkap".
     */
    fun micCommand(socketPath: String): String = """
export PULSE_SERVER=tcp:127.0.0.1:${Cfg.defaultAudioPort}
pactl list short sinks | grep -q AndroidSink || pactl load-module module-null-sink sink_name=AndroidSink sink_properties=device.description="Android_Audio_Stream" >/dev/null
pactl list short sources | grep -q AndroidMic || pactl load-module module-remap-source master=AndroidSink.monitor source_name=AndroidMic source_properties=device.description="Android_Virtual_Mic" >/dev/null
pactl set-default-source AndroidMic
pkill -f tiny_virtual_mic 2>/dev/null; pkill -f 'UNIX-CONNECT:$socketPath' 2>/dev/null
for i in ${'$'}(seq 1 25); do [ -S $socketPath ] && break; sleep 0.2; done
if command -v tiny_virtual_mic >/dev/null 2>&1; then
  tiny_virtual_mic $socketPath AndroidSink &
elif command -v socat >/dev/null 2>&1 && command -v pacat >/dev/null 2>&1; then
  (socat -u UNIX-CONNECT:$socketPath - | pacat --playback --device=AndroidSink --rate=44100 --channels=1 --format=s16le --latency-msec=60) &
else
  echo "Klien mikrofon tidak ada. Jalankan: sudo apt install -y socat pulseaudio-utils"
fi
pactl list short source-outputs | cut -f1 | xargs -r -I{} pactl move-source-output {} AndroidMic >/dev/null 2>&1
""".trimIndent()

    /** Kebalikan [micCommand]: hentikan klien, bongkar modul Android* (dan loopback monitor), kembalikan default. */
    fun micStopCommand(socketPath: String): String = """
export PULSE_SERVER=tcp:127.0.0.1:${Cfg.defaultAudioPort}
pkill -f tiny_virtual_mic 2>/dev/null; pkill -f 'UNIX-CONNECT:$socketPath' 2>/dev/null
pactl list short modules | grep Android | cut -f1 | tac | xargs -r -n1 pactl unload-module 2>/dev/null
""".trimIndent()

    /**
     * Monitor mik waktu nyata (padanan "Mic Realtime Audio Monitoring" di versi lama): loopback dari
     * AndroidMic ke sink fisik. Sink dipilih eksplisit (bukan AndroidSink) agar tidak terjadi umpan balik
     * AndroidMic -> AndroidSink -> AndroidMic.
     */
    fun micMonitorCommand(on: Boolean): String =
        if (on) """
export PULSE_SERVER=tcp:127.0.0.1:${Cfg.defaultAudioPort}
pactl list short modules | grep -q 'module-loopback.*AndroidMic' || pactl load-module module-loopback source=AndroidMic sink=${'$'}(pactl list short sinks | cut -f2 | grep -v AndroidSink | head -n1) latency_msec=1 >/dev/null
""".trimIndent()
        else """
export PULSE_SERVER=tcp:127.0.0.1:${Cfg.defaultAudioPort}
pactl list short modules | grep 'module-loopback.*AndroidMic' | cut -f1 | xargs -r -n1 pactl unload-module 2>/dev/null
""".trimIndent()

    /** Hasil [guestSpec]: nilai `EXTRA_MOUNT` dan `EXTRA_OPT` untuk perintah boot proot. */
    private class GuestSpec(val mount: String, val opt: String)

    /**
     * Menyusun `EXTRA_MOUNT` / `EXTRA_OPT` (bridge jaringan, controller, HiDPI, virgl, Turnip, ...) dan
     * menyalakan layanan sisi Android yang dibutuhkannya bila belum berjalan. Dipakai bersama oleh shell
     * kontainer ([launchCurrentContainer]) dan proot terpisah milik Shortcut ([launchApp]).
     */
    private fun guestSpec(ctx: Context, log: LaunchLog.Session? = null): GuestSpec {
        val data = TinyEnv.dataPath
        val idx = TinyEnv.currentContainer
        var extraMount = ""
        val extraOpt = StringBuilder()
        val preload = ArrayList<String>()

        // Isi folder `extra` berasal dari patch.tzst; berkas yang tidak ada TIDAK boleh masuk
        // LD_PRELOAD karena setiap proses guest lalu mencetak "cannot be preloaded" (lihat terminal).
        val extraDir = File(data, "tiny/extra")
        if (Cfg.getifaddrsBridge) {
            ensureService("getifaddrs-$idx", "$data/bin/getifaddrs_bridge_server $data/containers/$idx/tmp/.getifaddrs-bridge")
            if (File(extraDir, "getifaddrs_bridge_client_lib.so").isFile) {
                preload += "/home/tiny/.local/share/tiny/extra/getifaddrs_bridge_client_lib.so"
            } else Log.w("Launcher", "getifaddrs_bridge_client_lib.so tidak ada di $extraDir, bridge dilewati")
        }
        // Controller (evdev + hidraw) dari DroidDeck: libfakeinput.so melayani /dev/input dan /dev/hidraw16.
        // Hasilnya dipakai ulang oleh semua proot selama sesi berjalan; menyiapkan ulang akan menghapus
        // node/ring pad yang sedang dipakai aplikasi lain.
        val pad = padCache ?: PadSetup.prepare(ctx).also { padCache = it }
        if (File(data, "tiny/extra/libfakeinput.so").isFile) {
            preload += "/home/tiny/.local/share/tiny/extra/libfakeinput.so"
            pad.env.forEach { extraOpt.append(it).append(' ') }
            pad.mounts.forEach { extraMount += "$it " }
        }
        if (preload.isNotEmpty()) extraOpt.append("LD_PRELOAD=").append(preload.joinToString(":")).append(' ')

        // Sengaja TIDAK mengekspor WAYLAND_DISPLAY ke seluruh guest: aplikasi GTK (mis. xfce4-notifyd)
        // lalu memakai backend Wayland dan gagal karena compositor tidak punya wlr-layer-shell.
        // Hanya Xwayland yang diberi variabel Wayland (lihat guiCommand); desktop tetap murni X11.
        extraOpt.append("GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb SDL_VIDEODRIVER=x11 ")

        if (Cfg.isHidpiEnabled) extraOpt.append(Cfg.defaultHidpiOpt).append(' ')
        if (Cfg.virgl) {
            ensureService(
                "virgl-$idx",
                """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
export CONTAINER_DIR=${'$'}DATA_DIR/containers/$idx
$data/bin/virgl_test_server ${Cfg.defaultVirglCommand}""".trimIndent(),
            )
            extraOpt.append(Cfg.defaultVirglOpt).append(' ')
        }
        if (Cfg.turnip) {
            extraOpt.append(turnipOpt(ctx)).append(' ')
            // Xwayland (X server desktop) tidak punya DRI3. Tanpa dua variabel ini Zink gagal membuat swapchain
            // ("No DRI3 support detected ... zink: could not create swapchain", glxinfo "couldn't find RGB GLX visual").
            // MESA_VK_WSI_DEBUG=sw: presentasi WSI Vulkan lewat jalur software; LIBGL_KOPPER_DRI2: Kopper (GL di atas
            // Vulkan) memakai jalur DRI2 tanpa DRI3, seperti DroidDeck. Diuji: glxgears ~110-140 FPS.
            // Dengan "Enable DRI3" ON (Xwayland hasil patch, extra/xwayland-dri3-nodrm) MESA_VK_WSI_DEBUG=sw tidak
            // dipasang supaya presentasi Vulkan memakai DRI3. LIBGL_KOPPER_DRI2 tetap dipasang: EGL di atas X11
            // (mis. OpenGL milik Wine) memanggil DRI3Open, yang sengaja ditolak server tanpa DRM, lalu Mesa diam-diam
            // jatuh ke llvmpipe. Variabel ini melewati DRI3Open (platform_x11.c) dan Kopper tetap memakai WSI DRI3.
            extraOpt.append("LIBGL_KOPPER_DRI2=true ")
            if (!Cfg.dri3) extraOpt.append("MESA_VK_WSI_DEBUG=sw ")
        }
        // GTK4 (mis. pavucontrol "Volume Control" di Debian 13) memakai renderer GL secara bawaan; di
        // Xwayland/proot tanpa EGL yang sehat jendelanya tampil kosong. Paksa renderer cairo (perangkat lunak).
        if (!extraOpt.contains("GSK_RENDERER=")) extraOpt.append("GSK_RENDERER=cairo ")
        // Batas memori Vulkan per aplikasi (lapisan VK_LAYER_TINY_memlimit); berlaku untuk shell kontainer dan Shortcut.
        VkMemLimit.envOpt()?.let { extraOpt.append(it).append(' ') }
        if (Cfg.isJpEnabled) extraOpt.append("LANG=ja_JP.UTF-8 ")
        extraMount += "--mount=\$DATA_DIR/tiny/font:/usr/share/fonts/tiny "
        extraMount += "--mount=\$DATA_DIR/tiny/extra/cmatrix:/home/tiny/.local/bin/cmatrix "
        // proot tidak membuat titik mount untuk sumber yang tidak ada; tanpa berkas ini perintah
        // `tiny_virtual_mic` "command not found" (ada cadangan socat/pacat di [micCommand]).
        if (File(extraDir, "tiny_virtual_mic").isFile) {
            extraMount += "--mount=\$DATA_DIR/tiny/extra/tiny_virtual_mic:/home/tiny/.local/bin/tiny_virtual_mic "
        }
        // Audio langsung: klien pulse meminta latency kecil (membuat jembatan pulse mengirim potongan ~10 ms, bukan ~300 ms).
        // Bila "Prefer ALSA" menyala, SDL (PPSSPP, dll.; nama env SDL2 dan SDL3) dipaksa ke ALSA yang diarahkan
        // asound.conf ke plugin tinyaudio. Tanpa itu SDL memilih pulse lebih dulu dan tetap bersuara lewat jembatan.
        if (directAudioActive()) {
            extraOpt.append("PULSE_LATENCY_MSEC=40 ")
            if (Cfg.directAudioPreferAlsa) extraOpt.append("SDL_AUDIODRIVER=alsa SDL_AUDIO_DRIVER=alsa AUDIODRIVER=alsa ")
        }
        // Flathub Store (opsional): flatpak diarahkan ke tiny-bwrap (Android tanpa user namespace) dan menu desktop
        // ikut membaca ekspor flatpak pengguna, jadi aplikasi Flathub tampil di grid Shortcut dan menu desktop.
        if (FlatpakManager.enabled) extraOpt.append(FlatpakManager.sessionEnv(ctx)).append(' ')
        // log peluncuran: jalur berkas sesi untuk skrip Shortcut, shim Wine, dan Xsession desktop
        extraOpt.append(LaunchLog.envFor(log))
        return GuestSpec(extraMount, extraOpt.toString())
    }

    /** Baris `export ...` yang dibutuhkan perintah boot proot (`$DATA_DIR`, `$CONTAINER_DIR`, `$EXTRA_*`, `PROOT_*`). */
    private fun guestEnvScript(spec: GuestSpec): String {
        val data = TinyEnv.dataPath
        val idx = TinyEnv.currentContainer
        return """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
export CONTAINER_DIR=${'$'}DATA_DIR/containers/$idx
export EXTRA_MOUNT="${spec.mount}"
export EXTRA_OPT="${spec.opt}"
cd ${'$'}DATA_DIR
export PROOT_TMP_DIR=${'$'}DATA_DIR/proot_tmp
export PROOT_LOADER=${'$'}DATA_DIR/applib/libproot-loader.so
export PROOT_LOADER_32=${'$'}DATA_DIR/applib/libproot-loader32.so""".trimIndent()
    }

    fun launchCurrentContainer(ctx: Context) {
        val idx = TinyEnv.currentContainer
        // pad virtual disiapkan ulang hanya bila tidak ada aplikasi Shortcut yang sedang memakainya
        if (!appSessionStarted) padCache = null
        LaunchLog.write(containerLog, "session", "diganti oleh sesi proot kontainer baru")
        val log = LaunchLog.begin("container")
        containerLog = log
        LaunchLog.write(log, "session", "proot kontainer #$idx dimulai (shell kontainer + Open desktop)")
        val spec = guestSpec(ctx, log)
        val boot = Containers.boot(idx, TinyEnv.defaultBoot)
        val term = TermSessions.forCurrent(ctx)
        term.writeLine(guestEnvScript(spec) + "\n" + boot + "\n" + TinyEnv.postCommand + "\nclear")
        started = true
        guiStarted = false
    }

    /**
     * Perintah di dalam kontainer yang menyalakan desktop di atas compositor Wayland:
     * Xwayland berjalan rootful (satu jendela xdg-toplevel di compositor) lalu sesi X11
     * Debian (`/etc/X11/xinit/Xsession`, tak berubah) dijalankan di DISPLAY=:4.
     */
    fun guiCommand(ctx: Context, replaceAppSession: Boolean = false): String {
        val (w, h) = displaySize(ctx)
        // Aplikasi Vulkan/SDL di dalam desktop memakai WSI Wayland langsung ke compositor: lewat Xwayland (xcb)
        // Turnip gagal ("demo_select_physical_device"), sedangkan `vkcube --wsi wayland` jalan. Variabel ini
        // hanya dipasang pada proses Xsession (bukan shell terminal), dan toolkit lain dipaku ke X11 supaya
        // GTK/Qt/Firefox/Electron tidak ikut pindah ke Wayland (compositor tanpa wlr-layer-shell).
        // SDL: "wayland,x11" = Wayland dulu, X11 sebagai cadangan.
        val sessionEnv = if (Cfg.desktopWayland) {
            "XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 SDL_VIDEODRIVER=wayland,x11 " +
                "GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb MOZ_ENABLE_WAYLAND=0 ELECTRON_OZONE_PLATFORM_HINT=x11 CLUTTER_BACKEND=x11 "
        } else ""
        // sesi aplikasi dari menu Shortcut memegang display :4; hentikan dulu sebelum desktop memakainya
        val stopApps = if (replaceAppSession) "pkill -x Xwayland 2>/dev/null; sleep 1\n" else ""
        // pemilih sesi desktop: rootfs ekspor master tidak punya /etc/X11/xinit/Xsession (lihat assets/tiny/xsession.sh)
        runCatching {
            val vnc = File(TinyEnv.containerDir(), "home/tiny/.vnc").apply { mkdirs() }
            File(vnc, "tiny-session.sh").writeText(Rootfs.asset(ctx, "tiny/xsession.sh"))
        }
        return stopApps + """
mkdir -p "${'$'}HOME/.vnc"
command -v Xwayland >/dev/null 2>&1 || { echo "Memasang xwayland..."; sudo apt update && sudo apt install -y xwayland; }
unset WAYLAND_DISPLAY
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
for i in ${'$'}(seq 1 150); do [ -S /tmp/wayland-0 ] && break; sleep 0.2; done
# MIT-SHM dimatikan seperti versi original: SysV shm tidak bisa di-attach lewat proot, sehingga Mesa/Qt/Wine
# mencetak "Failed to attach to x11 shm" berulang bila X mengiklankan ekstensi ini.
(XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 ${xwaylandEnv()}${xwaylandBin()} :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx -extension MIT-SHM > "${'$'}HOME/.vnc/xwayland.log" 2>&1 &)
for i in ${'$'}(seq 1 150); do [ -S /tmp/.X11-unix/X4 ] && break; sleep 0.2; done
if [ -n "${'$'}TINY_LAUNCH_LOG" ] && [ -w "${'$'}TINY_LAUNCH_LOG" ]; then
(${sessionEnv}bash "${'$'}HOME/.vnc/tiny-session.sh" xinit/Xsession 2>&1 | tee "${'$'}HOME/.vnc/x.log" >> "${'$'}TINY_LAUNCH_LOG") &
else
${sessionEnv}bash "${'$'}HOME/.vnc/tiny-session.sh" xinit/Xsession &> "${'$'}HOME/.vnc/x.log" &
fi
${WsiWayland.probeForDesktop(sessionEnv)}
""".trimIndent()
    }

    /**
     * Menulis [command] ke shell kontainer (terminal) supaya prosesnya terlihat di tab Terminal.
     * Kontainer harus sudah berjalan ([isStarted]); pemanggil menyalakannya lewat [Startup.beginForTerminal].
     */
    fun runInTerminal(ctx: Context, command: String) {
        TermSessions.forCurrent(ctx).writeLine(command)
    }

    /**
     * Terminal tanpa kontainer, seperti Termux: shell Android (`/system/bin/sh`) dengan PATH ke proot, tar, xz,
     * gzip, dan busybox dari paket boot. Tidak membutuhkan data sistem, sehingga pengguna bisa menyiapkan rootfs
     * Linux sendiri (`tiny_extract`, `tiny_enter`, lihat assets/tiny/host_terminal.sh). Hanya bagian dasar paket
     * boot yang dipasang (tanpa patch). Dipanggil di thread latar oleh [Startup.beginHostTerminal].
     */
    fun startHostTerminal(ctx: Context) {
        TinyEnv.currentContainer = Cfg.defaultContainer
        Rootfs.linkApplib(ctx)
        if (!Rootfs.bootstrapReady()) Rootfs.unpackBootstrap(ctx)
        val data = TinyEnv.dataPath
        val script = Rootfs.asset(ctx, "tiny/host_terminal.sh")
            .replace("@DATA_PATH@", data)
            .replace("@CONTAINER_INDEX@", TinyEnv.currentContainer.toString())
            .replace("@GENERIC_BOOT@", TinyEnv.bootGeneric)
        val file = File(data, "host_terminal.sh")
        file.writeText(script)
        TermSessions.forCurrent(ctx).writeLine(". ${shq(file.absolutePath)}")
    }

    fun launchGuiBackend(ctx: Context) {
        val term = TermSessions.forCurrent(ctx)
        val replace = appSessionStarted
        // Xwayland milik Shortcut ada di proot terpisah: matikan dari sisi Android (EXITKILL ikut
        // mematikan anak-anaknya); `pkill` di guiCommand hanya jaring pengaman + jeda.
        if (replace) stopAppSession()
        guiStarted = true
        LaunchLog.write(containerLog, "desktop", "Open desktop: Xwayland + Xsession dinyalakan" + if (replace) " (menggantikan sesi aplikasi Shortcut)" else "")
        // syarat ikon muncul di menu Shortcut: desktop pernah dibuka sekali
        Cfg.desktopOpenedOnce = true
        WsiWayland.install() // pembungkus vkcube/vkcubepp: --wsi wayland
        term.writeLine(guiCommand(ctx, replace))
        term.writeLine("clear")
    }

    /**
     * Shell kontainer (PTY) sudah berakhir: reset penanda supaya peluncuran berikutnya benar-benar
     * menyalakan kontainer lagi, bukan menulis perintah ke terminal yang sudah mati. Proot terpisah
     * milik Shortcut tidak ikut dihentikan.
     */
    fun markStopped() {
        started = false
        guiStarted = false
        LaunchLog.write(containerLog, "session", "shell kontainer berakhir")
        containerLog = null
    }

    // ------------------------------------------------------------------ Shortcut: proot terpisah

    /** Proses terlepas (stdin = /dev/null) dengan keluaran dialirkan ke logcat; `exec` di skrip membuat prosesnya proot itu sendiri. */
    private fun spawnDetached(script: String, tag: String): Process {
        // `$$` = PID shell; setelah `exec` itulah PID proot. Dipakai untuk mematikan seluruh pohonnya.
        val p = ProcessBuilder("/system/bin/sh", "-c", "echo TINYPID=\$\$\n" + script)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("TINYPID=")) {
                            pids[p] = line.substring(8).trim().toIntOrNull() ?: 0
                        } else {
                            Log.d(tag, line)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return p
    }

    /**
     * Skrip host yang meluncurkan proot BARU di rootfs kontainer ini dan menjalankan [guestScript]
     * (path di dalam guest) lewat `/bin/sh`. Memakai perintah boot yang sama dengan kontainer (mount,
     * `env -i`) kecuali program terakhirnya `/bin/bash -l` diganti skrip itu.
     * Bila perintah boot buatan pengguna tidak diakhiri `/bin/bash`, perintah boot bawaan yang dipakai.
     *
     * [killOnExit] = false membuang `--kill-on-exit`: proot lalu menunggu SEMUA proses guest, bukan hanya
     * program pertama. Perlu untuk aplikasi berbentuk peluncur (mis. `code` milik VS Code menjalankan
     * Electron terlepas lalu keluar); dengan `--kill-on-exit`, Electron ikut dibunuh begitu peluncurnya
     * selesai dan layar langsung kembali ke menu Shortcut.
     */
    private fun appProotScript(ctx: Context, guestScript: String, killOnExit: Boolean, log: LaunchLog.Session? = null): String {
        val spec = guestSpec(ctx, log)
        val tail = Regex("""/bin/bash(\s+-l)?\s*$""")
        val custom = Containers.boot(TinyEnv.currentContainer, TinyEnv.defaultBoot).trim()
        val base = if (tail.containsMatchIn(custom)) custom else TinyEnv.bootForRootfs(TinyEnv.containerDir(), TinyEnv.defaultBoot).trim()
        var prefix = tail.replace(base, "").trimEnd()
        if (!killOnExit) prefix = prefix.replace(" --kill-on-exit", "")
        return guestEnvScript(spec) + "\nexec " + prefix + " /bin/sh " + guestScript + "\n"
    }

    /** Kutip satu argumen untuk /system/bin/sh. */
    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Menjalankan [argv] (path guest) di proot sekali pakai pada rootfs kontainer ini, tanpa terminal dan tanpa
     * menyalakan layanan sesi (virgl, pad, bridge). Dipakai Flathub Store ([FlatpakManager]) untuk memasang dan
     * menghapus aplikasi. [env] = tambahan `KEY=VALUE` (dipisah spasi) untuk `env -i`. [onLine] menerima tiap baris
     * keluaran (stdout + stderr). Mengembalikan kode keluar. Memblokir: panggil dari thread latar.
     *
     * `--link2symlink` TETAP dipakai (seperti perintah boot): tanpa itu ostree gagal dengan "linkat: Permission
     * denied" karena Android menolak hardlink dari aplikasi ini (terlihat di log pemasangan).
     */
    fun runGuest(ctx: Context, argv: List<String>, env: String, onLine: (String) -> Unit): Int {
        val tail = Regex("""/bin/bash(\s+-l)?\s*$""")
        val custom = Containers.boot(TinyEnv.currentContainer, TinyEnv.defaultBoot).trim()
        val base = if (tail.containsMatchIn(custom)) custom else TinyEnv.bootForRootfs(TinyEnv.containerDir(), TinyEnv.defaultBoot).trim()
        val prefix = tail.replace(base, "").trimEnd()
        val script = guestEnvScript(GuestSpec("", env)) + "\nexec " + prefix + " " + argv.joinToString(" ") { shq(it) } + "\n"
        val p = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return p.waitFor()
    }

    /**
     * Skrip host untuk satu sandbox flatpak ([BwrapSpawner]): proot BARU dengan akar [root] (folder kosong buatan
     * spawner) dan hanya bind dari [binds] (`host:guest`), lalu [argv] dengan lingkungan [env] saja. Opsi proot dan
     * mount sistem (`/proc`, `/dev`, `/sys` beserta tiruan tmoe) diambil dari perintah boot bawaan; mount folder
     * rumah, font, dan penyimpanan dibuang supaya tidak bocor ke dalam sandbox.
     *
     * `/data` TETAP di-mount: proot sesi memakai `--link2symlink`, jadi hardlink hasil checkout ostree/Flatpak
     * (seluruh isi runtime dan aplikasi) berupa symlink `.l2s.*` dengan target path HOST absolut
     * (`/data/user/0/<paket>/files/containers/...`). Tanpa `/data`, semua berkas itu menggantung di dalam sandbox
     * dan proot gagal dengan "'/usr/bin/env' not found" (lalu "ldconfig failed, exit status 256").
     */
    fun sandboxScript(root: File, cwd: String, binds: List<String>, env: Map<String, String>, argv: List<String>): String {
        val head = TinyEnv.bootForRootfs(TinyEnv.containerDir(), TinyEnv.defaultBoot).trim().substringBefore(" /usr/bin/env -i ")
        fun keep(mount: String): Boolean {
            val spec = mount.removePrefix("--mount=")
            val guest = if (':' in spec) spec.substringAfterLast(':') else spec
            return guest == "/sys" || guest == "/data" || guest.startsWith("/proc") || guest.startsWith("/dev")
        }
        val options = head.split(Regex("\\s+")).filter { it.isNotEmpty() }.filterNot {
            it == "\$EXTRA_MOUNT" || it.startsWith("--pwd=") || it.startsWith("--rootfs=") || it == "--link2symlink" ||
                (it.startsWith("--mount=") && !keep(it))
        }
        val cmd = StringBuilder(options.joinToString(" "))
        cmd.append(" --pwd=").append(shq(cwd)).append(" --rootfs=").append(shq(root.path))
        binds.forEach { cmd.append(" --mount=").append(shq(it)) }
        cmd.append(" /usr/bin/env -i")
        env.forEach { (k, v) -> cmd.append(' ').append(shq("$k=$v")) }
        argv.forEach { cmd.append(' ').append(shq(it)) }
        return guestEnvScript(GuestSpec("", "")) + "\necho TINYPID=\$\$\nexec " + cmd + "\n"
    }

    /**
     * Menjalankan aplikasi dari menu Shortcut seperti DroidDeck: sebagai proses proot sendiri, tanpa
     * bergantung pada shell kontainer/terminal, jadi tetap jalan walau desktop belum dibuka atau
     * kontainer sudah di-exit. Bila desktop sedang berjalan, aplikasi muncul di dalamnya (DISPLAY=:4
     * yang sama lewat /tmp bersama); bila tidak, satu proot lain menyalakan Xwayland + window manager
     * ringan ([ShortcutRunner.prepareSession]) dan aplikasi berikutnya ikut sesi itu. Dijalankan di
     * thread latar karena menulis berkas dan membaca daftar aplikasi.
     */
    fun launchApp(ctx: Context, appId: String) {
        val app = ctx.applicationContext
        val ticket = epoch
        // layar progres langsung tampil, bahkan sebelum thread latar sempat berjalan
        appStage = 0
        appClosing = false
        appPhase = AppPhase.STARTING
        Thread({
            synchronized(spawnLock) {
                if (ticket == epoch) {
                    runCatching { launchAppLocked(app, appId) }
                        .onFailure {
                            Log.e("Launcher", "gagal menjalankan shortcut $appId", it)
                            mainHandler.post {
                                if (appPhase == AppPhase.STARTING) appPhase = AppPhase.IDLE
                                onAppSessionEnded?.invoke(appId)
                            }
                        }
                }
            }
        }, "tiny-shortcut").start()
    }

    /**
     * Tombol Shutdown: matikan sesi aplikasi Shortcut SEKARANG (semua aplikasi + Xwayland), tanpa
     * menunggu aplikasinya keluar sendiri. Tidak memblokir pemanggil; [appPhase] menjadi STOPPING
     * sampai semua proses benar-benar hilang, lalu [onDone] dipanggil di thread utama. Peluncuran
     * berikutnya menunggu lewat kunci [spawnLock]. Desktop (kontainer PTY) tidak disentuh.
     */
    fun shutdownAppSession(onDone: () -> Unit = {}) {
        epoch++
        stopAndWait(onDone)
    }

    /**
     * Shutdown untuk desktop yang dibuka lewat Open desktop: hentikan Xwayland + sesi X11 (Xsession beserta
     * seluruh keturunannya), semua aplikasi Shortcut yang menempel di desktop itu, DAN shell kontainer
     * (terminal + proot beserta seluruh prosesnya), lalu tunggu sampai prosesnya benar-benar hilang.
     * Setelah itu [started] dan [guiStarted] false dan [Startup.phase] kembali IDLE: terminal tidak lagi
     * berjalan, penanda "Container running" di Beranda tidak hijau lagi, dan Open desktop / Start container
     * berikutnya menyalakan kontainer dari awal. Tidak memblokir pemanggil: [appPhase] menjadi STOPPING
     * (layar progres) dan [onDone] dipanggil di thread utama.
     */
    fun shutdownDesktop(onDone: () -> Unit = {}) {
        epoch++
        mainHandler.removeCallbacks(idleCheck)
        appStage = 0
        appPhase = AppPhase.STOPPING
        Thread({
            synchronized(spawnLock) {
                val killed = ArrayList<Int>()
                killed += stopAppSession(graceful = true)
                stopStage(2)
                killed += killDesktopProcesses()
                killed += killContainerShell()
                stopStage(3)
                waitGone(killed, 3000)
                guiStarted = false
            }
            LaunchLog.write(containerLog, "desktop", "Shutdown: Xwayland + sesi X11 + shell kontainer dihentikan")
            mainHandler.post {
                // jangan menunggu callback onSessionFinished: status harus sudah benar saat layar desktop ditutup
                markStopped()
                Startup.reset()
                appClosing = false
                if (appPhase == AppPhase.STOPPING) appPhase = AppPhase.IDLE
                onDone()
            }
        }, "tiny-stop-desktop").start()
    }

    /**
     * Mematikan HANYA shell kontainer (terminal + proot di dalamnya), mis. setelah pemasangan AppImage selesai.
     * Berbeda dengan [shutdownDesktop], sesi aplikasi Shortcut tidak disentuh dan layar progres tidak muncul.
     * Setelah itu [started] false dan [Startup.phase] kembali IDLE (Beranda tidak lagi hijau). Jangan dipanggil
     * saat desktop berjalan ([guiStarted]): desktop hidup di dalam kontainer yang sama. [onDone] di thread utama.
     */
    fun shutdownTerminal(onDone: () -> Unit = {}) {
        Thread({
            synchronized(spawnLock) {
                val killed = killContainerShell()
                waitGone(killed, 3000)
            }
            LaunchLog.write(containerLog, "session", "shell kontainer dimatikan setelah pasang AppImage")
            mainHandler.post {
                markStopped()
                Startup.reset()
                onDone()
            }
        }, "tiny-stop-terminal").start()
    }

    /**
     * Mematikan shell kontainer (terminal): SIGKILL shell `/system/bin/sh` beserta seluruh pohonnya (proot
     * dan semua proses guest). Sesi ditandai [TermSession.stoppedByUser] supaya kode keluar -9 tidak
     * dianggap batas proses anak Android 12+ (halaman Signal9). Mengembalikan PID yang dikirimi sinyal.
     */
    private fun killContainerShell(): List<Int> {
        val term = TermSessions.peek() ?: return emptyList()
        if (term.exitCode != null) return emptyList()
        term.stoppedByUser = true
        val pid = term.session.pid
        val killed = if (pid > 0) killTree(pid) else emptyList()
        runCatching { term.session.finishIfRunning() }
        return killed
    }

    /**
     * Xwayland desktop dan Xsession (beserta pohon prosesnya). Shell kontainer sendiri tidak disentuh:
     * boot mengekspor DISPLAY=:4 ke seluruh shell, jadi proses tidak bisa dikenali dari lingkungannya.
     * Yang dicari adalah baris perintahnya; proot (host) dilewati.
     */
    private fun killDesktopProcesses(): List<Int> {
        val me = android.os.Process.myPid()
        val killed = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            if (cmd.contains("--rootfs") || cmd.contains("proot")) return@forEach
            val xwayland = cmd.contains("Xwayland") && cmd.contains(" :4")
            val session = cmd.contains("xinit/Xsession")
            if (xwayland || session) killed += killTree(pid)
        }
        return killed
    }

    /** Mematikan sesi aplikasi di thread latar dan menunggu prosesnya hilang; [onDone] jalan di thread utama. */
    private fun stopAndWait(onDone: () -> Unit) {
        appStage = 0
        appPhase = AppPhase.STOPPING
        Thread({
            synchronized(spawnLock) {
                val killed = stopAppSession(graceful = true)
                stopStage(3)
                waitGone(killed, 3000)
            }
            mainHandler.post {
                appClosing = false
                // aplikasi baru yang sempat diminta selama menunggu (STARTING) tidak boleh ditimpa
                if (appPhase == AppPhase.STOPPING) appPhase = AppPhase.IDLE
                onDone()
            }
        }, "tiny-stop").start()
    }

    private fun launchAppLocked(ctx: Context, appId: String) {
        val myEpoch = epoch
        val script = ShortcutRunner.prepare(ctx, appId) ?: run {
            mainHandler.post {
                if (appPhase == AppPhase.STARTING) appPhase = AppPhase.IDLE
                onAppSessionEnded?.invoke(null)
            }
            return
        }
        ensureAudio()
        // ada aplikasi baru: batalkan penutupan sesi yang mungkin sudah dijadwalkan
        mainHandler.removeCallbacks(idleCheck)
        lastFailedApp = null
        // Tidak ada yang kita pantau tapi proot Shortcut masih ada = sisa masa hidup sebelumnya
        // (aplikasi di-kill Android, crash). Bersihkan agar tidak menimpa sesi baru.
        val gone = ArrayList<Int>()
        val tracked = appSessionStarted || synchronized(appProcs) { appProcs.any { it.isAlive } }
        if (!tracked) gone += sweepStale()
        advanceStage(0)
        if (!guiStarted && !appSessionStarted) {
            // seluruh pohon proses sesi lama (Xwayland, aplikasi, anak-anaknya) dimatikan dan DITUNGGU
            // sampai hilang sebelum Xwayland baru dibuat
            gone += stopAppSession(graceful = true)
            waitGone(gone, 2000)
            advanceStage(1)
            val size = displaySize(ctx, appId)
            appSessionSize = size
            val sLog = LaunchLog.begin("shortcut")
            appSessionLog = sLog
            LaunchLog.write(sLog, "session", "sesi aplikasi Shortcut dimulai (Xwayland ${size.first}x${size.second}), dipicu oleh $appId")
            // sesi yang dimulai aplikasi Wine (.exe) mulai tanpa window manager (layar penuh); aplikasi Linux menyalakannya lagi
            val withWm = !ShortcutRunner.isWineApp(appId)
            if (!withWm) LaunchLog.write(sLog, "session", "window manager dijeda (sesi dimulai aplikasi Wine, layar penuh)")
            val session = spawnDetached(appProotScript(ctx, ShortcutRunner.prepareSession(ctx, size, withWm), killOnExit = true, log = sLog), "TinyAppSession")
            appSession = session
            // Xwayland mati sendiri (crash): aplikasinya ikut putus, tutup sesi dan kembali ke Shortcut
            watch("tiny-session-wait") {
                runCatching { session.waitFor() }
                pids.remove(session)
                LaunchLog.write(sLog, "session", "proot sesi aplikasi berakhir (Xwayland keluar)")
                if (appSession === session) scheduleIdleCheck(0)
            }
            // proot aplikasi tidak lagi berhenti sendiri saat aplikasinya selesai (tanpa --kill-on-exit),
            // jadi "sesi kosong" diperiksa berkala lewat pohon prosesnya; 3 pemeriksaan berturut-turut = kosong
            watch("tiny-idle-poll") {
                var idleTicks = 0
                while (appSession === session) {
                    Thread.sleep(1000)
                    if (appSession !== session) break
                    if (guiStarted) { idleTicks = 0; appClosing = false; continue }
                    idleTicks = if (appsBusy()) 0 else idleTicks + 1
                    // dua pemeriksaan kosong berturut-turut: layar progres Shutdown sudah tampil selama menunggu pemeriksaan
                    // ketiga (peluncur single-instance yang hanya berpindah jendela tidak sempat memunculkannya)
                    appClosing = idleTicks >= 2 && appPhase == AppPhase.RUNNING
                    if (idleTicks >= 3) { scheduleIdleCheck(0); break }
                }
            }
        } else {
            // desktop yang menyala memegang X: hanya pastikan sisa proses lama sudah hilang
            waitGone(gone, 2000)
        }
        // aplikasi ikut berkas log sesi tempatnya berjalan: shell kontainer bila desktop menyala, kalau tidak sesi Shortcut
        // (saklar baru dinyalakan saat sesi Shortcut sudah berjalan: aplikasi ini membuka berkas log baru sendiri)
        val appLog = if (guiStarted) containerLog else (appSessionLog ?: LaunchLog.begin("shortcut")?.also { appSessionLog = it })
        LaunchLog.write(appLog, "launch", LaunchLog.describe(appId))
        val proc = spawnDetached(appProotScript(ctx, script, killOnExit = false, log = appLog), "TinyApp-$appId")
        synchronized(appProcs) {
            appProcs.removeAll { !it.isAlive }
            appProcs.add(proc)
        }
        mainHandler.removeCallbacks(idleCheck)
        // progres: tahap 2 = menunggu X (soket :4), tahap 3 = menunggu proses aplikasi di proot benar-benar jalan;
        // selesai (RUNNING) setelah keduanya, proot aplikasi mati (gagal start, ditangani auto-close), atau 40 detik
        watch("tiny-ready-poll") {
            val sock = File(TinyEnv.runtimeDir(), ".X11-unix/X4")
            val end = SystemClock.elapsedRealtime() + 40_000
            while (SystemClock.elapsedRealtime() < end && appPhase == AppPhase.STARTING && myEpoch == epoch && proc.isAlive) {
                if (!sock.exists()) advanceStage(1)
                else if (!appsBusy()) advanceStage(2)
                else break
                Thread.sleep(200)
            }
            if (appPhase == AppPhase.STARTING && myEpoch == epoch) appPhase = AppPhase.RUNNING
        }
        val startedAt = SystemClock.elapsedRealtime()
        watch("tiny-app-wait-$appId") {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            pids.remove(proc)
            LaunchLog.write(appLog, "exit", "$appId keluar dengan kode $code")
            // galat dalam 5 detik pertama = gagal start; appSession null = kita sendiri yang mematikannya
            if (code != 0 && SystemClock.elapsedRealtime() - startedAt < 5000 && appSession != null) lastFailedApp = appId
            scheduleIdleCheck(2000)
        }
    }

    /**
     * Menghentikan sesi aplikasi Shortcut: SELURUH pohon proses tiap proot (aplikasi dulu, Xwayland
     * terakhir), bukan hanya proot-nya. Mematikan proot tanpa `--kill-on-exit` meninggalkan proses guest
     * yatim yang terus berjalan/crash dan menimpa sesi berikutnya. Tidak menunggu; kembaliannya daftar PID
     * yang dikirimi sinyal untuk [waitGone].
     */
    fun stopAppSession(graceful: Boolean = false): List<Int> {
        mainHandler.removeCallbacks(idleCheck)
        val log = appSessionLog
        LaunchLog.write(log, "session", "sesi aplikasi dihentikan" + if (graceful) " (ditutup halus dulu)" else "")
        appSessionLog = null
        lastFailedApp = null
        val guests = ArrayList<Process>()
        synchronized(appProcs) {
            guests += appProcs
            appProcs.clear()
        }
        val appRoots = guests.mapNotNull { pids[it] }.filter { it > 0 }
        appSession?.let { guests += it }
        appSession = null
        // Xwayland + window manager (proot sesi) tetap hidup selama fase ini: aplikasi butuh X untuk menutup jendelanya.
        if (graceful) runCatching { closeGracefully(appRoots, log) }.onFailure { Log.w("Launcher", "closeGracefully", it) }
        stopStage(2)
        val killed = ArrayList<Int>()
        for (g in guests) {
            val pid = pids.remove(g) ?: 0
            if (pid > 0) killed += killTree(pid)
            g.destroyForcibly()
        }
        // sandbox Flatpak hidup di luar pohon proot sesi; yang masih tersisa setelah masa tunggu dimatikan di sini
        if (graceful) BwrapSpawner.sandboxPids().forEach { killed += killTree(it) }
        return killed
    }

    /** Port `workflow()`: siapkan data, terminal, audio, lalu nyalakan kontainer. */
    fun start(ctx: Context) {
        TermSessions.forCurrent(ctx)
        ensureAudio()
        launchCurrentContainer(ctx)
    }
}
