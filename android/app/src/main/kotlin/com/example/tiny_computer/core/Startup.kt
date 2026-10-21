package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.R

/**
 * Alur "Open desktop" seperti DroidDeck. Pemasangan data sistem (peluncuran pertama) dan start
 * kontainer TIDAK lagi berjalan sendiri begitu aplikasi dibuka; keduanya baru dimulai ketika
 * pengguna menekan tombol Open desktop (atau "Start container" di tab Terminal).
 */
object Startup {
    private const val TAG = "TinyStartup"

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    var phase by mutableStateOf(if (Launcher.isStarted) Phase.DONE else Phase.IDLE)
        private set

    /** True bila alur yang sedang/terakhir berjalan memasang sistem dari nol. */
    var firstInstall by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Peringatan (bahasa Inggris) untuk Beranda bila tahap tambahan gagal, mis. internet tidak aktif. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Tahap Wayland + mikrofon ikut ditampilkan di layar progres (ada paket yang belum terpasang). */
    var withExtras by mutableStateOf(false)
        private set

    /** Kontainer sudah berjalan saat alur dimulai: ulang setelah tahap tambahan gagal, hanya tahap itu. */
    var resumed by mutableStateOf(false)
        private set

    /** Pengguna menekan Open desktop: buka layar desktop begitu kontainer siap. */
    var openWhenReady by mutableStateOf(false)
        private set

    /** Shortcut yang diketuk saat kontainer belum menyala: dijalankan begitu kontainer siap, tanpa membuka desktop. */
    var pendingApp by mutableStateOf<String?>(null)
        private set

    /**
     * Perintah untuk terminal kontainer yang menunggu kontainer siap (mis. pasang AppImage dari menu Shortcut
     * saat kontainer belum menyala): ditulis ke terminal begitu siap, lalu tab Terminal dibuka agar prosesnya terlihat.
     */
    var pendingTerminal by mutableStateOf<String?>(null)
        private set

    /** Mode terminal tanpa kontainer: shell Android + proot/tar/busybox (seperti Termux), lihat [beginHostTerminal]. */
    var hostMode by mutableStateOf(false)
        private set

    /** Alur yang sedang/terakhir berjalan adalah import data sistem ([beginImport]); tetap true saat gagal agar Coba lagi mengulang import. */
    var importing by mutableStateOf(false)
        private set

    private var importUri: Uri? = null
    private var importName: String = ""

    /** Alur terakhir adalah terminal tanpa sistem: Coba lagi mengulang [beginHostTerminal]. */
    private var lastWasHost = false

    /** APK tanpa asset patch (patch.tzst): beri tahu di Beranda apa yang hilang dan cara memperbaikinya. */
    private fun patchWarning() {
        if (Rootfs.patchAssetMissing) {
            notice = "This APK has no patch.tzst (assets), so the bundled Turnip driver, virtual microphone and getifaddrs bridge are missing. " +
                "Rebuild the APK with release_tag v1.1.0 (not latest)."
        }
    }

    /** Data sistem sudah pernah dipasang (penanda yang sama dengan `Rootfs.initData`). */
    val installed: Boolean get() = TinyPrefs.contains("defaultContainer")

    /**
     * APK tidak membawa data sistem (xa*) dan belum ada sistem terpasang: Beranda menampilkan tombol Import
     * di samping roda gigi, dan Open desktop / Start container tidak bisa dijalankan.
     */
    fun needsImport(ctx: Context): Boolean = !installed && !Rootfs.hasBundledSystem(ctx)

    /** Menampilkan peringatan di Beranda / tab Terminal (kartu merah yang sama dengan peringatan tahap tambahan). */
    fun warn(message: String) { notice = message }

    /** Data sistem sudah disiapkan (`Rootfs.initData`) di proses ini. */
    private val dataReady = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Dari menu Shortcut saat sistem sudah terpasang: TIDAK menyalakan kontainer. Aplikasi berjalan di
     * proot terpisah ([Launcher.launchApp]), jadi cukup memastikan data siap (symlink applib, libfakeinput)
     * sekali per proses di thread latar, lalu [onReady] dipanggil di thread utama.
     */
    fun prepareForApp(ctx: Context, onReady: () -> Unit) {
        if (dataReady.get() || Launcher.isStarted) { onReady(); return }
        val app = ctx.applicationContext
        Thread({
            runCatching { Rootfs.initData(app) }.onFailure { Log.e(TAG, "initData gagal", it) }
            patchWarning()
            dataReady.set(true)
            Handler(Looper.getMainLooper()).post { onReady() }
        }, "tiny-app-prepare").start()
    }

    /** Dari menu Shortcut saat sistem belum terpasang / kontainer sedang dinyalakan: tunggu sampai siap lalu jalankan [appId]. */
    fun beginForApp(ctx: Context, appId: String) {
        if (needsImport(ctx)) { notice = ctx.getString(R.string.tc_no_system_notice); return }
        if (hostMode) { notice = ctx.getString(R.string.tc_host_busy); return }
        pendingApp = appId
        begin(ctx, openDesktop = false)
    }

    /** Dari menu Shortcut: jalankan [command] di terminal; kontainer dinyalakan dulu bila belum berjalan. */
    fun beginForTerminal(ctx: Context, command: String) {
        if (needsImport(ctx)) { notice = ctx.getString(R.string.tc_no_system_notice); return }
        if (hostMode) { notice = ctx.getString(R.string.tc_host_busy); return }
        pendingTerminal = command
        begin(ctx, openDesktop = false)
    }

    /**
     * Shell kontainer berakhir: kembali ke IDLE supaya Open desktop / Start container menyalakan
     * ulang kontainer. Saat RUNNING dibiarkan; [ExtraSetup.run] berhenti sendiri bila shell mati.
     */
    fun reset() {
        if (phase == Phase.RUNNING) return
        hostMode = false
        phase = Phase.IDLE
        openWhenReady = false
        pendingApp = null
        pendingTerminal = null
    }

    @Synchronized
    fun begin(ctx: Context, openDesktop: Boolean) {
        if (phase == Phase.RUNNING || phase == Phase.DONE) return
        // tanpa data sistem di APK dan belum ada sistem terpasang: arahkan ke tombol Import, jangan memasang rootfs kosong
        if (!Launcher.isStarted && needsImport(ctx)) { notice = ctx.getString(R.string.tc_no_system_notice); return }
        val app = ctx.applicationContext
        importing = false
        hostMode = false
        lastWasHost = false
        resumed = Launcher.isStarted
        firstInstall = !installed && !resumed
        withExtras = ExtraSetup.missing().isNotEmpty()
        notice = null
        error = null
        openWhenReady = openDesktop
        Rootfs.percent = -1
        Rootfs.statusText = ""
        phase = Phase.RUNNING
        Thread({
            try {
                if (!Launcher.isStarted) {
                    Rootfs.initData(app)
                    patchWarning()
                    dataReady.set(true)
                    Rootfs.percent = -1
                    Rootfs.statusText = "startingContainer"
                    Launcher.start(app)
                }
                // Jangan langsung masuk desktop: pasang dulu Wayland dan paket mikrofon yang kurang.
                // Gagal (mis. internet mati) -> kembali ke Beranda dengan peringatan, bukan layar galat.
                val problem = ExtraSetup.run(app)
                if (problem == null) {
                    phase = Phase.DONE
                } else {
                    notice = problem
                    openWhenReady = false
                    pendingApp = null
                    pendingTerminal = null
                    phase = Phase.IDLE
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startup gagal", t)
                error = t.message ?: t.javaClass.simpleName
                pendingApp = null
                pendingTerminal = null
                phase = Phase.FAILED
            }
        }, "tiny-startup").start()
    }

    /**
     * Import data sistem dari berkas pilihan pengguna ([SystemImport]); layar progres yang sama dengan Open desktop.
     * Selesai -> kembali ke IDLE dengan sistem terpasang (tombol Import hilang); gagal -> FAILED dengan pesan,
     * sisa ekstraksi dibersihkan sehingga tombol Import tetap ada.
     */
    @Synchronized
    fun beginImport(ctx: Context, uri: Uri, displayName: String) {
        if (phase == Phase.RUNNING || phase == Phase.DONE) return
        val app = ctx.applicationContext
        importUri = uri
        importName = displayName
        importing = true
        lastWasHost = false
        hostMode = false
        resumed = false
        firstInstall = true
        withExtras = false
        notice = null
        error = null
        openWhenReady = false
        Rootfs.percent = -1
        Rootfs.statusText = ""
        phase = Phase.RUNNING
        Thread({
            try {
                SystemImport.run(app, uri, displayName)
                importing = false
                importUri = null
                phase = Phase.IDLE
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, app.getString(R.string.tc_import_done, SystemImport.systemName(displayName)), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "import gagal", t)
                SystemImport.cleanup()
                error = t.message ?: t.javaClass.simpleName
                phase = Phase.FAILED
            }
        }, "tiny-import").start()
    }

    /**
     * Terminal tanpa data sistem: hanya paket boot (proot, tar, busybox) yang dipasang, lalu shell Android dibuka
     * di tab Terminal, seperti Termux. Tidak perlu data sistem; bisa dipakai juga bila sistem sudah terpasang.
     * Mengetik `exit` menutup terminal dan kembali ke tombol Start.
     */
    @Synchronized
    fun beginHostTerminal(ctx: Context) {
        if (phase == Phase.RUNNING || phase == Phase.DONE) return
        val app = ctx.applicationContext
        importing = false
        lastWasHost = true
        resumed = false
        firstInstall = false
        withExtras = false
        notice = null
        error = null
        openWhenReady = false
        Rootfs.percent = -1
        Rootfs.statusText = ""
        hostMode = true
        phase = Phase.RUNNING
        Thread({
            try {
                Launcher.startHostTerminal(app)
                phase = Phase.DONE
            } catch (t: Throwable) {
                Log.e(TAG, "terminal gagal", t)
                hostMode = false
                error = t.message ?: t.javaClass.simpleName
                phase = Phase.FAILED
            }
        }, "tiny-host-terminal").start()
    }

    /** Coba lagi dari layar galat: mengulang import yang gagal, atau Open desktop / Start container seperti semula. */
    fun retry(ctx: Context) {
        val uri = importUri
        when {
            importing && uri != null -> beginImport(ctx, uri, importName)
            lastWasHost -> beginHostTerminal(ctx)
            else -> begin(ctx, openWhenReady)
        }
    }

    /** Menutup layar galat dan kembali ke Beranda. */
    fun dismiss() {
        if (phase == Phase.FAILED) {
            importing = false
            importUri = null
            hostMode = false
            phase = Phase.IDLE
            error = null
            openWhenReady = false
            pendingApp = null
            pendingTerminal = null
        }
    }

    fun clearNotice() { notice = null }

    /** Mengembalikan true sekali bila desktop harus dibuka sekarang. */
    fun consumeOpen(): Boolean {
        val o = openWhenReady
        openWhenReady = false
        return o
    }

    /** Mengembalikan perintah terminal yang menunggu kontainer siap (sekali), atau null. */
    fun consumeTerminal(): String? {
        val c = pendingTerminal
        pendingTerminal = null
        return c
    }

    /** Mengembalikan id Shortcut yang menunggu kontainer siap (sekali), atau null. */
    fun consumeApp(): String? {
        val a = pendingApp
        pendingApp = null
        return a
    }

    /** Tahap yang ditampilkan di layar progres; kuncinya sama dengan `Rootfs.statusText`. */
    fun stageKeys(): List<String> {
        if (importing) return listOf("installingBootPackage", "importingSystem")
        if (hostMode) return listOf("preparing")
        val base = when {
            resumed -> emptyList()
            firstInstall -> listOf("installingBootPackage", "copyingContainerSystem", "installingContainerSystem", "startingContainer")
            else -> listOf("preparing", "startingContainer")
        }
        val all = if (withExtras) base + "installingExtras" else base
        return all.ifEmpty { listOf("installingExtras") }
    }

    /** Indeks tahap untuk [status]; tidak pernah mundur karena pemanggil menyimpan nilai tertinggi. */
    fun stageIndex(status: String): Int {
        val keys = stageKeys()
        val i = keys.indexOf(status)
        return when {
            i >= 0 -> i
            status == "installationComplete" -> keys.indexOf("installingContainerSystem").coerceAtLeast(0)
            else -> 0
        }
    }
}
