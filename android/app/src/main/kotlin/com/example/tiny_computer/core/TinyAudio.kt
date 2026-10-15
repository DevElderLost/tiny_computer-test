package com.example.tiny_computer.core

import android.util.Log
import java.io.File

/**
 * Server audio native: menerima PCM dari plugin ALSA `tinyaudio` di guest lewat soket unix lalu memutarnya
 * lewat AAudio. Tidak memakai PulseAudio/OpenSL ES. Implementasi ada di cpp/tinyaudio.
 */
object TinyAudio {
    private const val TAG = "TinyAudio"

    /** Jalur soket DI DALAM guest (container/tmp di sisi Android). Harus sama dengan asound.conf. */
    const val GUEST_SOCKET = "/tmp/.tinyaudio"

    val available: Boolean = runCatching { System.loadLibrary("tinyaudio") }
        .onFailure { Log.w(TAG, "libtinyaudio.so tidak termuat: ${it.message}") }.isSuccess

    @Volatile private var path: String? = null

    private external fun nativeStart(path: String, prefillMs: Int, bufferMs: Int): Boolean
    private external fun nativeStop()
    private external fun nativeRunning(): Boolean
    private external fun nativeStats(): LongArray

    val running: Boolean get() = available && nativeRunning()

    fun hostSocket(): String = File(TinyEnv.runtimeDir(), ".tinyaudio").absolutePath

    /** Menyalakan server untuk kontainer saat ini; bila sudah jalan di kontainer lain, dipindahkan. */
    @Synchronized
    fun start(): Boolean {
        if (!available) return false
        val want = hostSocket()
        if (running) {
            if (path == want) return true
            nativeStop()
        }
        File(want).parentFile?.mkdirs()
        val buffer = Cfg.directAudioBufferMs
        val ok = nativeStart(want, minOf(30, buffer), buffer)
        path = if (ok) want else null
        Log.i(TAG, "start($want) -> $ok")
        return ok
    }

    @Synchronized
    fun stop() {
        if (available && nativeRunning()) nativeStop()
        path = null
    }

    /** [klien, underrun server, xrun AAudio, buffer (frame), stream terbuka 0/1] */
    fun stats(): LongArray = if (running) nativeStats() else LongArray(5)
}
