package com.droiddeck.launcher.input

import android.content.Context
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import kotlin.math.roundToInt

object ControllerPrefs {
    const val STEAM_BLUE = 0xFF1A9FFF.toInt()
    const val OFF = "off"

    val tints = listOf(
        STEAM_BLUE to R.string.ctrl_tint_steam_blue,
        0xFF66C0F4.toInt() to R.string.ctrl_tint_sky,
        0xFFE8EEF4.toInt() to R.string.ctrl_tint_white,
        0xFFC77DFF.toInt() to R.string.ctrl_tint_violet,
        0xFF3DDC84.toInt() to R.string.ctrl_tint_green,
        0xFFFFA726.toInt() to R.string.ctrl_tint_amber,
        0xFFFF5252.toInt() to R.string.ctrl_tint_red,
        0xFFFF6FB5.toInt() to R.string.ctrl_tint_pink,
    )

    val opacities = listOf(40, 60, 80, 100)

    val sizes = listOf(80, 90, 100, 110, 125)

    val mappable = listOf(
        "a" to R.string.ctrl_button_a, "b" to R.string.ctrl_button_b, "x" to R.string.ctrl_button_x, "y" to R.string.ctrl_button_y,
        "lb" to R.string.ctrl_button_lb, "rb" to R.string.ctrl_button_rb, "lt" to R.string.ctrl_button_lt, "rt" to R.string.ctrl_button_rt,
        "select" to R.string.ctrl_button_view, "start" to R.string.ctrl_button_menu,
    )

    val targets = listOf(
        "a" to R.string.ctrl_target_a, "b" to R.string.ctrl_target_b, "x" to R.string.ctrl_target_x, "y" to R.string.ctrl_target_y,
        "lb" to R.string.ctrl_target_lb, "rb" to R.string.ctrl_target_rb, "lt" to R.string.ctrl_target_lt, "rt" to R.string.ctrl_target_rt,
        "l3" to R.string.ctrl_target_l3, "r3" to R.string.ctrl_target_r3, "select" to R.string.ctrl_target_view, "start" to R.string.ctrl_target_menu, "guide" to R.string.ctrl_target_steam,
        "up" to R.string.ctrl_target_up, "down" to R.string.ctrl_target_down, "left" to R.string.ctrl_target_left, "right" to R.string.ctrl_target_right,
        OFF to R.string.ctrl_target_hidden,
    )

    class Settings(
        val tint: Int,
        val opacity: Int,
        val size: Int,
        val stickClick: Boolean,
        val adaptiveSticks: Boolean,
        val rumble: Boolean,
        val steamButton: Boolean,
        val qamButton: Boolean,
        val keyboardButton: Boolean,
        val customLayout: Boolean,
        val mapping: Map<String, String>,
    )

    internal fun prefs(context: Context) = context.getSharedPreferences("controller", Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            tint = p.getInt("tint", STEAM_BLUE),
            // opasitas & ukuran tetap dari seekbar Tiny (Cfg.padOverlayOpacity / padOverlayScale), bukan preset DroidDeck
            opacity = (Cfg.padOverlayOpacity * 100).roundToInt().coerceIn(20, 100),
            size = (Cfg.padOverlayScale * 100).roundToInt().coerceIn(60, 160),
            stickClick = p.getBoolean("stickClick", true),
            adaptiveSticks = p.getBoolean("adaptiveSticks", true),
            rumble = rumbleEnabled(context),
            steamButton = p.getBoolean("steamButton", true),
            qamButton = p.getBoolean("qamButton", true),
            keyboardButton = p.getBoolean("keyboardButton", true),
            customLayout = p.all.keys.any { it.startsWith("layout.") },
            mapping = mappable.associate { (id, _) -> id to target(context, id) },
        )
    }

    fun target(context: Context, id: String): String =
        prefs(context).getString("map.$id", id)?.takeIf { t -> targets.any { it.first == t } } ?: id

    fun setTarget(context: Context, id: String, target: String) {
        prefs(context).edit().putString("map.$id", target).apply()
    }

    fun resetMapping(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("map.") }.forEach { remove(it) } }.apply()
    }

    fun setTint(context: Context, tint: Int) {
        prefs(context).edit().putInt("tint", tint).apply()
    }

    fun setOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt("opacity", percent).apply()
    }

    fun setSize(context: Context, percent: Int) {
        prefs(context).edit().putInt("size", percent).apply()
    }

    fun setStickClick(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("stickClick", on).apply()
    }

    fun setAdaptiveSticks(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adaptiveSticks", on).apply()
    }

    fun rumbleEnabled(context: Context): Boolean = prefs(context).getBoolean("rumble", true)

    fun setRumble(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("rumble", on).apply()
    }

    // ---- gyro (hidraw Steam Deck + mapping ke stik) dan trackpad L/R

    const val GYRO_OFF = "off"
    const val GYRO_LEFT = "left"
    const val GYRO_RIGHT = "right"
    const val PAD_STICK = "stick"
    const val PAD_HID = "hid"

    /** Saklar gyro: bila mati sensor tidak dibaca sama sekali (hemat baterai). */
    fun gyroEnabled(context: Context): Boolean = prefs(context).getBoolean("gyro", false)

    fun setGyro(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("gyro", on).apply()
    }

    /** Gyro menggerakkan stik apa: [GYRO_OFF] (hanya dikirim ke hidraw), [GYRO_LEFT] atau [GYRO_RIGHT]. */
    fun gyroTarget(context: Context): String =
        prefs(context).getString("gyroTarget", GYRO_RIGHT)?.takeIf { it == GYRO_OFF || it == GYRO_LEFT || it == GYRO_RIGHT } ?: GYRO_RIGHT

    fun setGyroTarget(context: Context, target: String) {
        prefs(context).edit().putString("gyroTarget", target).apply()
    }

    /** 1..10; makin tinggi makin kecil putaran yang dibutuhkan untuk menggerakkan stik penuh. */
    fun gyroSensitivity(context: Context): Int = prefs(context).getInt("gyroSens", 5).coerceIn(1, 10)

    fun setGyroSensitivity(context: Context, value: Int) {
        prefs(context).edit().putInt("gyroSens", value.coerceIn(1, 10)).apply()
    }

    /** Trackpad L/R menjadi stik kiri/kanan ([PAD_STICK]) atau hanya dikirim ke laporan hidraw Deck ([PAD_HID]). */
    fun trackpadMode(context: Context): String =
        prefs(context).getString("trackpadMode", PAD_STICK)?.takeIf { it == PAD_STICK || it == PAD_HID } ?: PAD_STICK

    fun setTrackpadMode(context: Context, mode: String) {
        prefs(context).edit().putString("trackpadMode", mode).apply()
    }

    fun setSteamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("steamButton", on).apply()
    }

    fun setQamButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("qamButton", on).apply()
    }

    fun setKeyboardButton(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("keyboardButton", on).apply()
    }

    fun layout(context: Context, width: Int, height: Int): Map<String, Pair<Float, Float>> {
        val raw = prefs(context).getString("layout.${width}x$height", null) ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val (group, pos) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (x, y) = pos.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            group to (x to y)
        }.toMap()
    }

    fun setLayout(context: Context, width: Int, height: Int, positions: Map<String, Pair<Float, Float>>) {
        val raw = positions.entries.joinToString(";") { (group, pos) -> "$group:${pos.first},${pos.second}" }
        prefs(context).edit().putString("layout.${width}x$height", raw).apply()
    }

    fun resetLayout(context: Context, width: Int, height: Int) {
        prefs(context).edit().remove("layout.${width}x$height").apply()
    }

    fun resetAll(context: Context) {
        // On older Android versions clear() does not notify listeners; restore rumble explicitly.
        prefs(context).edit().clear().putBoolean("rumble", true).apply()
    }

    fun resetAllLayouts(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("layout.") }.forEach { remove(it) } }.apply()
    }
}
