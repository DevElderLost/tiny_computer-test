package com.example.tiny_computer.controls

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Controller fisik -> binding profil (port `ExternalController` / `ExternalControllerBinding` Winlator).
 * Dipanggil DisplayActivity SEBELUM PadBridge: tombol/sumbu yang dipetakan di profil aktif menjadi binding (keyboard,
 * mouse, atau tombol gamepad lain); yang tidak dipetakan tetap mengalir ke pad Deck/Xbox 360 seperti biasa.
 *
 * Catatan sumbu: bila satu sumbu (stik/hat) dipetakan, seluruh event gerak controller itu ditangani profil, jadi
 * sumbu lain yang tidak dipetakan ikut berhenti; petakan semua sumbu yang dipakai.
 */
class ExternalControllerHandler(private val dispatcher: BindingDispatcher) {
    var profile: Profile? = null

    /** Kekuatan terakhir tiap (perangkat, kode) supaya hanya perubahan yang dikirim. */
    private val lastStrength = HashMap<Long, Float>()

    private fun usable(): Boolean = profile?.controllers?.isNotEmpty() == true

    private fun controllerOf(device: InputDevice?): PadController? {
        val d = device ?: return null
        return profile?.controller(d.descriptor)
    }

    fun onKey(e: KeyEvent): Boolean {
        if (!usable()) return false
        val c = controllerOf(e.device) ?: return false
        val b = c.find(e.keyCode) ?: return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) return true
        when (e.action) {
            KeyEvent.ACTION_DOWN -> dispatcher.handle(b.binding, true)
            KeyEvent.ACTION_UP -> dispatcher.handle(b.binding, false)
        }
        return true
    }

    fun onMotion(e: MotionEvent): Boolean {
        if (!usable()) return false
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (e.actionMasked != MotionEvent.ACTION_MOVE) return false
        val c = controllerOf(e.device) ?: return false
        var any = false
        for (axis in AXES) {
            val v = centered(e, axis)
            for (positive in booleanArrayOf(false, true)) {
                val key = PadBinding.axisKeyCode(axis, positive)
                val bind = c.find(key) ?: continue
                any = true
                val strength = if (positive) (if (v > 0f) v else 0f) else (if (v < 0f) -v else 0f)
                val hat = axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y
                val eff = if (hat) (if (strength > 0.5f) 1f else 0f) else if (strength < DEAD) 0f else strength
                val id = (e.deviceId.toLong() shl 32) or (key.toLong() and 0xFFFFFFFFL)
                val prev = lastStrength[id] ?: 0f
                if (eff == prev || (eff > 0f && prev > 0f && abs(eff - prev) < 0.02f)) continue
                lastStrength[id] = eff
                if (eff > 0f) {
                    val analog = bind.binding.isMouseMove || (bind.binding.isGamepad && bind.binding.name.contains("THUMB"))
                    // sumbu analog: skala ulang dari zona mati; tombol biasa cukup ditekan
                    dispatcher.handle(bind.binding, true, if (analog && !hat) ((eff - DEAD) / (1f - DEAD)).coerceIn(0.001f, 1f) else 0f)
                } else {
                    dispatcher.handle(bind.binding, false)
                }
            }
        }
        return any
    }

    /** Controller dicabut: lepas apa pun yang masih ditahan dari perangkat itu. */
    fun onDeviceRemoved(deviceId: Int) {
        val gone = lastStrength.keys.filter { (it shr 32).toInt() == deviceId }
        val c = profile?.controllers.orEmpty()
        for (k in gone) {
            val code = (k and 0xFFFFFFFFL).toInt()
            c.firstNotNullOfOrNull { it.find(code) }?.let { dispatcher.handle(it.binding, false) }
            lastStrength.remove(k)
        }
    }

    fun reset() { lastStrength.clear() }

    companion object {
        private const val DEAD = 0.15f
        private val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )

        private fun centered(e: MotionEvent, axis: Int): Float {
            val range = e.device?.getMotionRange(axis, e.source) ?: return 0f
            val v = e.getAxisValue(axis)
            return if (abs(v) <= range.flat) 0f else v
        }

        /** Untuk layar penetapan tombol: kode tombol fisik dari event, atau null bila bukan tombol controller. */
        fun captureKey(e: KeyEvent): Int? {
            if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return null
            val src = e.source
            val pad = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            return if (pad && e.keyCode != KeyEvent.KEYCODE_BACK) e.keyCode else null
        }

        /** Untuk layar penetapan tombol: sumbu yang digerakkan melewati ambang (kode seperti PadBinding.AXIS_*), atau null. */
        fun captureAxis(e: MotionEvent): Int? {
            if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
            for (axis in AXES) {
                val v = centered(e, axis)
                val hat = axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y
                if (abs(v) > (if (hat) 0.5f else 0.6f)) return PadBinding.axisKeyCode(axis, v > 0f)
            }
            return null
        }
    }
}
