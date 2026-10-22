package com.example.tiny_computer.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.AudioStream
import com.example.tiny_computer.ui.findActivity
import kotlinx.coroutines.delay

/**
 * Keadaan dan aturan mikrofon yang dipakai bersama oleh tab Sound di menu sesi (SessionDrawer) dan
 * Pengaturan > Audio, supaya keduanya selalu sejalan.
 *
 * Aturan utama: mikrofon (dan monitor realtime-nya) hanya boleh hidup pada mode PulseAudio. Mikrofon
 * virtual dibuat dari modul pulseaudio (AndroidSink/AndroidMic); pada mode Direct (ALSA native Android)
 * jalur itu tidak dipakai aplikasi, jadi saklarnya dikunci mati dan mikrofon yang sedang berjalan
 * dihentikan saat mode berpindah ke Direct.
 */
object SoundControl {
    const val MIC_SOCKET = "/tmp/android_audio"

    /** Mikrofon Android sedang dialirkan ke guest. */
    var micOn by mutableStateOf(false)
        private set

    /** Loopback mik -> speaker (monitor realtime) menyala; hanya bermakna bila [micOn]. */
    var monitorOn by mutableStateOf(false)
        private set

    /** Mode yang sebenarnya dipakai: Direct hanya bila dipilih DAN plugin tinyaudio tersedia. */
    var directMode by mutableStateOf(Launcher.directAudioActive())
        private set

    /** Mikrofon hanya bisa dihidupkan pada mode PulseAudio. */
    val micAllowed: Boolean get() = !directMode

    /**
     * Dipanggil Pengaturan > Audio saat saklar "Direct audio" berubah (mode keluaran hanya diganti dari sana).
     * Pindah ke Direct langsung mematikan mikrofon; mode keluaran berlaku penuh pada peluncuran berikutnya.
     */
    fun setDirect(ctx: Context, direct: Boolean) {
        Cfg.directAudio = direct
        directMode = Launcher.directAudioActive()
        if (directMode) stopMic(ctx)
    }

    /** Hidupkan mikrofon; false bila ditolak (mode Direct, atau izin RECORD_AUDIO belum diberikan). */
    fun startMic(ctx: Context): Boolean {
        if (!micAllowed) return false
        if (micOn) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ctx.findActivity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return false
        }
        // server soket di sisi Android dulu (guest menyambung), lalu klien di guest
        Launcher.ensurePulseForMic()
        AudioStream.startStreaming("${TinyEnv.dataPath}/containers/${TinyEnv.currentContainer}$MIC_SOCKET")
        TermSessions.forCurrent(ctx).writeLine(Launcher.micCommand(MIC_SOCKET))
        micOn = true
        return true
    }

    fun stopMic(ctx: Context) {
        if (!micOn && !monitorOn) return
        TermSessions.forCurrent(ctx).writeLine(Launcher.micStopCommand(MIC_SOCKET)) // modul loopback ikut dibongkar
        AudioStream.stopStreaming() // tidak memblokir thread UI
        micOn = false
        monitorOn = false
    }

    /** Monitor realtime; hanya bisa menyala selama mikrofon hidup. */
    fun setMonitor(ctx: Context, on: Boolean) {
        if (on && !micOn) return
        TermSessions.forCurrent(ctx).writeLine(Launcher.micMonitorCommand(on))
        monitorOn = on
    }

    /**
     * Thread perekam bisa berhenti sendiri (guest menutup soket, AudioRecord gagal). Tanpa pemantauan ini
     * saklar tetap "hidup" padahal tidak ada suara yang mengalir. Dipanggil dari LaunchedEffect(micOn).
     */
    suspend fun watchMic(ctx: Context) {
        while (micOn) {
            delay(1000)
            if (!AudioStream.running) stopMic(ctx)
        }
    }
}
