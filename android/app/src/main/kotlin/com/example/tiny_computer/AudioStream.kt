package com.example.tiny_computer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Menyalurkan mikrofon Android ke guest lewat soket unix. Semua pekerjaan (accept, rekam, kirim)
 * ada di satu thread latar; [stopStreaming] hanya menurunkan bendera dan TIDAK menunggu thread itu,
 * jadi aman dipanggil dari thread UI (versi lama memanggil join() di UI dan membeku bila guest
 * belum pernah tersambung).
 */
object AudioStream {
    private const val TAG = "AudioStream"
    private const val SAMPLE_RATE = 44100

    init {
        System.loadLibrary("native-socket")
    }

    @Volatile private var isStreaming = false
    @Volatile private var recordingThread: Thread? = null

    private external fun nativeInit(path: String): Int
    private external fun nativeAccept(): Int
    private external fun nativeSend(data: ByteArray, size: Int): Int
    private external fun nativeClose()

    val running: Boolean get() = isStreaming

    @SuppressLint("MissingPermission") // RECORD_AUDIO diperiksa pemanggil (SettingsScreen)
    @Synchronized
    fun startStreaming(path: String) {
        if (isStreaming) return
        isStreaming = true
        val previous = recordingThread
        val t = Thread({
            try {
                // thread lama menutup fd di akhir; tunggu dulu supaya tidak menutup soket baru
                previous?.join(2000)
                streamLoop(path)
            } catch (e: Throwable) {
                Log.e(TAG, "streaming berhenti karena galat", e)
            } finally {
                nativeClose()
                synchronized(this) { if (recordingThread === Thread.currentThread()) isStreaming = false }
            }
        }, "tiny-audio-stream")
        recordingThread = t
        t.start()
    }

    @SuppressLint("MissingPermission")
    private fun streamLoop(path: String) {
        if (nativeInit(path) < 0) {
            Log.e(TAG, "Failed to bind socket $path")
            return
        }
        Log.d(TAG, "Waiting for connection on $path...")
        var accepted = 0
        while (isStreaming && accepted == 0) accepted = nativeAccept()
        if (!isStreaming) return
        if (accepted < 0) {
            Log.e(TAG, "Accept failed")
            return
        }
        Log.d(TAG, "Client connected!")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize = $bufferSize")
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2,
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord tidak siap (izin mikrofon / mikrofon dipakai aplikasi lain)")
                return
            }
            recorder.startRecording()
            val data = ByteArray(bufferSize)
            // buang 1 detik pertama (derau awal perekam), bukan 5 detik seperti dulu
            var skipped = 0
            val skipBytes = SAMPLE_RATE * 2
            while (isStreaming && skipped < skipBytes) {
                val n = recorder.read(data, 0, minOf(bufferSize, skipBytes - skipped))
                if (n > 0) skipped += n else if (n < 0) return
            }
            while (isStreaming) {
                val n = recorder.read(data, 0, bufferSize)
                if (n < 0) break
                if (n > 0 && nativeSend(data, n) < 0) break // guest menutup soket
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    fun stopStreaming() {
        isStreaming = false
    }
}
