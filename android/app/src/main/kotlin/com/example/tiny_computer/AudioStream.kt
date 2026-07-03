package com.example.tiny_computer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

object AudioStream {
    init {
        System.loadLibrary("native-socket")
    }

    private var isStreaming = false
    private var recordingThread: Thread? = null

    // Batas waktu tunggu thread berhenti sebelum kita lepaskan referensinya (ms).
    // Mencegah caller (misal MethodChannel handler di main thread) nge-hang
    // selamanya kalau nativeClose() gagal meng-unblock nativeAccept()/nativeSend()
    // yang sedang blocking di sisi native (native-socket).
    private const val JOIN_TIMEOUT_MS = 1500L

    // Native functions
    private external fun nativeInit(path: String): Int
    private external fun nativeAccept(): Int
    private external fun nativeSend(data: ByteArray, size: Int): Int
    private external fun nativeClose()

    @SuppressLint("MissingPermission") // Ensure RECORD_AUDIO is granted in Manifest
    fun startStreaming(path: String) {
        if (isStreaming) return
        isStreaming = true

        recordingThread = Thread {
            // 1. Initialize Socket Server
            if (nativeInit(path) < 0) {
                Log.e("AudioStream", "Failed to bind socket")
                isStreaming = false
                return@Thread
            }

            // 2. Wait for Linux client to connect (Blocking)
            Log.d("AudioStream", "Waiting for connection on $path...")
            if (nativeAccept() < 0) {
                Log.e("AudioStream", "Accept failed")
                isStreaming = false
                return@Thread
            }
            Log.d("AudioStream", "Client connected!")

            // 3. Setup AudioRecord
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val data = ByteArray(bufferSize)
            recorder.startRecording()

            val discardMillis = 5000 // 丢弃前5秒
            val discardBytes = (sampleRate * 2 * discardMillis / 1000).toInt() // 16bit = 2字节
            var bytesRead = 0

            // 先读取并丢弃初始数据
            while (bytesRead < discardBytes && isStreaming) {
                val readBytes = recorder.read(data, 0, minOf(bufferSize, discardBytes - bytesRead))
                if (readBytes > 0) {
                    bytesRead += readBytes
                }
            }

            // 4. Streaming Loop
            while (isStreaming) {
                val readBytes = recorder.read(data, 0, bufferSize)
                if (readBytes > 0) {
                    val sent = nativeSend(data, readBytes)
                    if (sent < 0) break // Socket broken
                }
            }

            // Cleanup
            recorder.stop()
            recorder.release()
            nativeClose()
        }
        recordingThread?.isDaemon = true
        recordingThread?.start()
    }

    fun stopStreaming() {
        isStreaming = false
        nativeClose() // Coba unblock native Accept/Send yang sedang blocking

        val thread = recordingThread
        if (thread != null && thread.isAlive) {
            thread.join(JOIN_TIMEOUT_MS)
            if (thread.isAlive) {
                // nativeClose() gagal meng-unblock native thread dalam batas
                // waktu. Daripada nge-hang selamanya (yang berujung ANR dan
                // proses di-kill signal 9 oleh Android, memutus koneksi
                // socket X11/termux-x11 di container), kita lepaskan
                // referensi dan biarkan thread itu mati sendiri di
                // background begitu native call akhirnya return.
                Log.w("AudioStream", "recordingThread tidak berhenti dalam ${JOIN_TIMEOUT_MS}ms, dilepas paksa (kemungkinan native accept/send masih blocking)")
            }
        }
        recordingThread = null
    }
}