package com.example.tiny_computer.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log peluncuran aplikasi / .exe (menu Shortcut dan Open desktop) ke berkas teks. Logikanya mengikuti log
 * sesi compositor Wayland (waylandcomp/src/compositor.c): folder yang sama `Download/Wayland-logs`, satu berkas
 * BARU per sesi, baris berformat `jam  area  isi`. Satu "sesi" = satu proot: shell kontainer (tempat Open desktop
 * berjalan; berkas `launch-<waktu>-container.log`) atau sesi aplikasi Shortcut tanpa desktop
 * (`launch-<waktu>-shortcut.log`). Aplikasi yang dijalankan di dalam sesi itu menambah baris ke berkas yang sama.
 *
 * Isi: baris dari sisi Android (aplikasi apa, perintahnya, kode keluar) ditambah keluaran aplikasi dari dalam
 * guest lewat variabel `TINY_LAUNCH_LOG` (jalur berkas; /storage dimount apa adanya ke guest). Saklar ada di
 * Pengaturan > Log; berlaku mulai sesi proot berikutnya.
 */
object LaunchLog {
    private const val TAG = "TinyLaunchLog"

    /** Folder yang sama dengan SESSION_LOG_DIR di compositor.c. */
    const val DIR = "/storage/emulated/0/Download/Wayland-logs"

    class Session internal constructor(val file: File, val kind: String)

    private val lock = Any()

    val enabled: Boolean get() = Cfg.launchLog

    /** `TINY_LAUNCH_LOG=<jalur> ` untuk `EXTRA_OPT` proot; kosong bila tidak ada sesi. */
    fun envFor(s: Session?): String = if (s == null) "" else "TINY_LAUNCH_LOG=${s.file.absolutePath} "

    /** Membuat berkas sesi baru; null bila saklar mati atau folder tidak bisa ditulis. */
    fun begin(kind: String): Session? {
        if (!enabled) return null
        return synchronized(lock) {
            runCatching {
                val dir = File(DIR)
                if (!dir.isDirectory) dir.mkdirs()
                if (!dir.isDirectory) {
                    Log.w(TAG, "tidak bisa membuat $DIR")
                    null
                } else {
                    val now = Date()
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
                    var f = File(dir, "launch-$stamp-$kind.log")
                    var n = 2
                    while (f.exists()) f = File(dir, "launch-$stamp-$kind-${n++}.log")
                    f.writeText(
                        "Tiny Computer launch log\n" +
                            "========================\n" +
                            "Started   ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}\n" +
                            "Session   $kind (satu berkas per sesi proot)\n" +
                            "Log       ${f.absolutePath}\n\n" +
                            "Time          Area      Event\n" +
                            "------------  --------  -----------------------------------------------------\n",
                    )
                    Log.i(TAG, "launch log: ${f.absolutePath}")
                    Session(f, kind)
                }
            }.onFailure { Log.w(TAG, "gagal membuat log peluncuran", it) }.getOrNull()
        }
    }

    /** Menambah satu baris ke berkas sesi; tidak melakukan apa pun bila [s] null. */
    fun write(s: Session?, area: String, msg: String) {
        if (s == null) return
        synchronized(lock) {
            runCatching {
                val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                s.file.appendText(String.format(Locale.US, "%s  %-9s %s\n", t, area, msg))
            }
        }
    }

    /** Satu baris ringkas tentang aplikasi Shortcut yang akan dijalankan: id, nama, perintah + argumen. */
    fun describe(appId: String): String {
        val app = runCatching { DesktopApps.find(appId) }.getOrNull() ?: return appId
        val args = ShortcutPrefs.get(appId).args.trim()
        val cmd = ShortcutRunner.cleanExec(app.exec) + if (args.isNotEmpty()) " $args" else ""
        return "${app.name} [$appId]: $cmd"
    }

    /**
     * Dipanggil saat saklar diubah. Shim Wine di kontainer (`/usr/local/bin/wine`, dipakai peluncuran .exe dari
     * menu desktop) ditulis ulang supaya memuat kode log; tidak berpengaruh bila Wine belum disiapkan.
     */
    fun onToggled() {
        Thread({ runCatching { WineContents.onSelectionChanged() } }, "tiny-launchlog-shim").apply { isDaemon = true }.start()
    }
}
