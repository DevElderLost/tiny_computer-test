package com.example.tiny_computer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** APK tanpa patch.tar.gz: beri tahu di Beranda apa yang hilang dan cara memperbaikinya. */
    private fun patchWarning() {
        if (Rootfs.patchAssetMissing) {
            notice = "This APK has no patch.tar.gz, so the bundled Turnip driver, virtual microphone and getifaddrs bridge are missing. " +
                "Rebuild the APK with release_tag v1.1.0 (not latest)."
        }
    }

    /** Data sistem sudah pernah dipasang (penanda yang sama dengan `Rootfs.initData`). */
    val installed: Boolean get() = TinyPrefs.contains("defaultContainer")

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
        pendingApp = appId
        begin(ctx, openDesktop = false)
    }

    /**
     * Shell kontainer berakhir: kembali ke IDLE supaya Open desktop / Start container menyalakan
     * ulang kontainer. Saat RUNNING dibiarkan; [ExtraSetup.run] berhenti sendiri bila shell mati.
     */
    fun reset() {
        if (phase == Phase.RUNNING) return
        phase = Phase.IDLE
        openWhenReady = false
        pendingApp = null
    }

    @Synchronized
    fun begin(ctx: Context, openDesktop: Boolean) {
        if (phase == Phase.RUNNING || phase == Phase.DONE) return
        val app = ctx.applicationContext
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
                    phase = Phase.IDLE
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startup gagal", t)
                error = t.message ?: t.javaClass.simpleName
                pendingApp = null
                phase = Phase.FAILED
            }
        }, "tiny-startup").start()
    }

    /** Menutup layar galat dan kembali ke Beranda. */
    fun dismiss() {
        if (phase == Phase.FAILED) {
            phase = Phase.IDLE
            error = null
            openWhenReady = false
            pendingApp = null
        }
    }

    fun clearNotice() { notice = null }

    /** Mengembalikan true sekali bila desktop harus dibuka sekarang. */
    fun consumeOpen(): Boolean {
        val o = openWhenReady
        openWhenReady = false
        return o
    }

    /** Mengembalikan id Shortcut yang menunggu kontainer siap (sekali), atau null. */
    fun consumeApp(): String? {
        val a = pendingApp
        pendingApp = null
        return a
    }

    /** Tahap yang ditampilkan di layar progres; kuncinya sama dengan `Rootfs.statusText`. */
    fun stageKeys(): List<String> {
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
