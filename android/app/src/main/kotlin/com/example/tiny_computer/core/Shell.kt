package com.example.tiny_computer.core

import android.util.Log
import java.io.File

/** Pengganti `Util.execute`: jalankan skrip lewat /system/bin/sh dan tunggu kode keluarnya. */
object Shell {
    private const val TAG = "TinyShell"

    /** Menjalankan [script] dengan `sh`, mengembalikan kode keluar. Keluaran dibuang ke logcat. */
    fun execute(script: String, workDir: File? = null): Int {
        val pb = ProcessBuilder("/system/bin/sh").redirectErrorStream(true)
        if (workDir != null) pb.directory(workDir)
        val p = pb.start()
        // Skrip dikirim lewat stdin, persis seperti PTY di versi Dart; "exit $?" meneruskan status terakhir.
        Thread {
            p.outputStream.use { it.write((script + "\nexit \$?\n").toByteArray(Charsets.UTF_8)) }
        }.start()
        p.inputStream.bufferedReader().use { r ->
            var line = r.readLine()
            while (line != null) {
                Log.d(TAG, line)
                line = r.readLine()
            }
        }
        return p.waitFor()
    }

    /** Menjalankan perintah di latar belakang dan mengembalikan prosesnya (virgl, getifaddrs bridge, pulseaudio). */
    fun spawn(script: String): Process {
        val p = ProcessBuilder("/system/bin/sh").redirectErrorStream(true).start()
        Thread {
            p.outputStream.use { it.write((script + "\nexit \$?\n").toByteArray(Charsets.UTF_8)) }
        }.start()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        Log.d(TAG, line)
                        line = r.readLine()
                    }
                }
            }
        }.start()
        return p
    }
}
