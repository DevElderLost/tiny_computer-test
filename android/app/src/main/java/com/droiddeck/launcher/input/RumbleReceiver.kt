package com.droiddeck.launcher.input

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * Penerima rumble dari guest: libfakeinput.so (evdev FF_RUMBLE dan feature report 0xEB hidraw Steam Deck)
 * mengirim 4 nilai uint16 little-endian ke soket abstrak "droiddeck-rumble":
 * kekuatan motor kuat, kekuatan motor lemah, lama (ms, 0 = sampai dihentikan), dan nomor slot.
 *
 * Nilainya diubah menjadi getaran:
 *  - ada controller fisik yang memegang slot itu dan punya motor getar -> controller itu bergetar
 *    (dua motor terpisah bila API 31+ dan controller punya dua motor);
 *  - selain itu getaran HP: amplitudo 1..255 dari motor yang lebih kuat, dinaikkan supaya getaran
 *    kecil tetap terasa.
 * Kekuatan 0 menghentikan getaran. Mengikuti saklar "Game vibration" ([ControllerPrefs.rumbleEnabled]).
 */
object RumbleReceiver {
    private const val TAG = "RumbleReceiver"
    private const val SOCKET_NAME = "droiddeck-rumble"
    private const val MESSAGE_BYTES = 8
    /** Lama 0 berarti "sampai dihentikan": batasi supaya getaran tidak tertinggal bila game mati. */
    private const val OPEN_ENDED_MS = 10_000L
    private const val MAX_MS = 30_000L

    private val lock = Any()
    private var appContext: Context? = null
    private var server: LocalServerSocket? = null
    @Volatile private var running = false
    /** Perangkat getar yang sedang dipakai, supaya bisa dihentikan. */
    private val active = LinkedHashSet<Vibrator>()

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            appContext = context.applicationContext
            val socket = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (e: IOException) {
                Log.w(TAG, "soket $SOCKET_NAME tidak bisa dibuka: ${e.message}")
                return
            }
            server = socket
            running = true
            Thread({ acceptLoop(socket) }, "rumble-rx").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            runCatching { server?.close() }
            server = null
        }
        cancelAll()
    }

    private fun acceptLoop(socket: LocalServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            runCatching { serve(client) }
            runCatching { client.close() }
        }
    }

    private fun serve(client: LocalSocket) {
        val input = client.inputStream
        val buffer = ByteArray(MESSAGE_BYTES)
        while (readFully(input, buffer)) {
            val strong = u16(buffer, 0)
            val weak = u16(buffer, 2)
            val duration = u16(buffer, 4)
            val slot = u16(buffer, 6)
            handle(strong, weak, duration, slot)
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun handle(strong: Int, weak: Int, durationMs: Int, slot: Int) {
        val context = appContext ?: return
        if (strong == 0 && weak == 0) {
            cancelAll()
            return
        }
        if (!ControllerPrefs.rumbleEnabled(context)) return
        val ms = if (durationMs == 0) OPEN_ENDED_MS else durationMs.toLong().coerceIn(1L, MAX_MS)
        val padVibrators = padVibrators(slot)
        if (padVibrators != null) {
            vibratePad(padVibrators, strong, weak, ms)
        } else {
            vibratePhone(context, max(strong, weak * 7 / 10), ms)
        }
    }

    // ---------------------------------------------------------------- controller fisik

    private class PadVibrators(val manager: VibratorManager?, val vibrators: List<Vibrator>)

    private fun padVibrators(slot: Int): PadVibrators? {
        val id = PadBridge.controllerForSlot(slot)
        if (id == PadBridge.NO_CONTROLLER) return null
        val device = InputDevice.getDevice(id) ?: return null
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = device.vibratorManager
            val list = manager.vibratorIds.map { manager.getVibrator(it) }.filter { it.hasVibrator() }
            return if (list.isEmpty()) null else PadVibrators(manager, list)
        }
        @Suppress("DEPRECATION")
        val single = device.vibrator
        return if (single != null && single.hasVibrator()) PadVibrators(null, listOf(single)) else null
    }

    private fun vibratePad(pad: PadVibrators, strong: Int, weak: Int, ms: Long) {
        val manager = pad.manager
        if (Build.VERSION.SDK_INT >= 31 && manager != null && pad.vibrators.size >= 2) {
            // dua motor: kuat -> motor pertama, lemah -> motor kedua (urutan XInput: kiri besar, kanan kecil)
            val ids = manager.vibratorIds
            val combined = CombinedVibration.startParallel().apply {
                addVibrator(ids[0], effect(strong, ms, pad.vibrators[0]))
                addVibrator(ids[1], effect(weak, ms, pad.vibrators[1]))
            }.combine()
            runCatching {
                cancelAll()
                manager.vibrate(combined)
                synchronized(active) { active.addAll(pad.vibrators) }
            }
            return
        }
        val vibrator = pad.vibrators[0]
        runCatching {
            vibrator.vibrate(effect(max(strong, weak * 7 / 10), ms, vibrator))
            synchronized(active) { active.add(vibrator) }
        }
    }

    // ---------------------------------------------------------------- getaran HP

    private fun vibratePhone(context: Context, magnitude: Int, ms: Long) {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(effect(magnitude, ms, vibrator))
            synchronized(active) { active.add(vibrator) }
        }
    }

    /** 0..65535 -> amplitudo 1..255; motor HP yang tidak bisa diatur amplitudonya bergetar penuh. */
    private fun effect(magnitude: Int, ms: Long, vibrator: Vibrator): VibrationEffect {
        val fraction = (magnitude.coerceIn(0, 65535) / 65535f)
        if (fraction <= 0f) return VibrationEffect.createOneShot(1, VibrationEffect.DEFAULT_AMPLITUDE)
        val amplitude = if (vibrator.hasAmplitudeControl()) (60 + 195 * fraction).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(ms, amplitude)
    }

    private fun cancelAll() {
        val list = synchronized(active) { active.toList().also { active.clear() } }
        for (vibrator in list) runCatching { vibrator.cancel() }
    }
}
