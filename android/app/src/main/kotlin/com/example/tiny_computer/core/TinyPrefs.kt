package com.example.tiny_computer.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan pengaturan. Memakai berkas dan format yang sama dengan plugin shared_preferences
 * Flutter (`FlutterSharedPreferences`, kunci berawalan `flutter.`), sehingga pengaturan dan daftar
 * kontainer dari versi Flutter tetap terbaca setelah pembaruan ke versi Compose.
 */
object TinyPrefs {
    private const val FILE = "FlutterSharedPreferences"
    private const val PFX = "flutter."
    private const val LIST_JSON = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!"
    private const val DOUBLE_PFX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun contains(key: String) = sp.contains(PFX + key)

    fun getBool(key: String, def: Boolean): Boolean =
        runCatching { sp.getBoolean(PFX + key, def) }.getOrDefault(def)

    fun setBool(key: String, value: Boolean) = sp.edit().putBoolean(PFX + key, value).apply()

    fun getInt(key: String, def: Int): Int =
        runCatching { sp.getLong(PFX + key, def.toLong()).toInt() }.getOrDefault(def)

    fun setInt(key: String, value: Int) = sp.edit().putLong(PFX + key, value.toLong()).apply()

    fun getString(key: String, def: String): String =
        runCatching { sp.getString(PFX + key, null) }.getOrNull() ?: def

    fun setString(key: String, value: String) = sp.edit().putString(PFX + key, value).apply()

    fun getDouble(key: String, def: Double): Double {
        val raw = runCatching { sp.getString(PFX + key, null) }.getOrNull() ?: return def
        return raw.removePrefix(DOUBLE_PFX).toDoubleOrNull() ?: def
    }

    fun setDouble(key: String, value: Double) =
        sp.edit().putString(PFX + key, DOUBLE_PFX + value.toString()).apply()

    fun getStringList(key: String): List<String>? {
        val raw = runCatching { sp.getString(PFX + key, null) }.getOrNull() ?: return null
        if (!raw.startsWith(LIST_JSON)) return null
        return runCatching {
            val arr = JSONArray(raw.removePrefix(LIST_JSON))
            List(arr.length()) { arr.getString(it) }
        }.getOrNull()
    }

    fun setStringList(key: String, value: List<String>) {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        sp.edit().putString(PFX + key, LIST_JSON + arr.toString()).apply()
    }
}

/** Kunci dan nilai bawaan yang sama dengan `Util.getGlobal` di workflow.dart. */
object Cfg {
    var defaultContainer: Int
        get() = TinyPrefs.getInt("defaultContainer", 0)
        set(v) = TinyPrefs.setInt("defaultContainer", v)
    var defaultAudioPort: Int
        get() = TinyPrefs.getInt("defaultAudioPort", 4718)
        set(v) = TinyPrefs.setInt("defaultAudioPort", v)
    /** Kunci lama `autoLaunchVnc`: kini berarti "buka desktop otomatis setelah kontainer siap". */
    var autoLaunchGui: Boolean
        get() = TinyPrefs.getBool("autoLaunchVnc", true)
        set(v) = TinyPrefs.setBool("autoLaunchVnc", v)
    var isTerminalWriteEnabled: Boolean
        get() = TinyPrefs.getBool("isTerminalWriteEnabled", false)
        set(v) = TinyPrefs.setBool("isTerminalWriteEnabled", v)
    var isTerminalCommandsEnabled: Boolean
        get() = TinyPrefs.getBool("isTerminalCommandsEnabled", false)
        set(v) = TinyPrefs.setBool("isTerminalCommandsEnabled", v)
    var termMaxLines: Int
        get() = TinyPrefs.getInt("termMaxLines", 4095)
        set(v) = TinyPrefs.setInt("termMaxLines", v)
    var termFontScale: Double
        get() = TinyPrefs.getDouble("termFontScale", 1.0)
        set(v) = TinyPrefs.setDouble("termFontScale", v)
    var isStickyKey: Boolean
        get() = TinyPrefs.getBool("isStickyKey", true)
        set(v) = TinyPrefs.setBool("isStickyKey", v)
    var reinstallBootstrap: Boolean
        get() = TinyPrefs.getBool("reinstallBootstrap", false)
        set(v) = TinyPrefs.setBool("reinstallBootstrap", v)
    var getifaddrsBridge: Boolean
        get() = TinyPrefs.getBool("getifaddrsBridge", false)
        set(v) = TinyPrefs.setBool("getifaddrsBridge", v)
    var virgl: Boolean
        get() = TinyPrefs.getBool("virgl", false)
        set(v) = TinyPrefs.setBool("virgl", v)
    var turnip: Boolean
        get() = TinyPrefs.getBool("turnip", false)
        set(v) = TinyPrefs.setBool("turnip", v)
    var dri3: Boolean
        get() = TinyPrefs.getBool("dri3", false)
        set(v) = TinyPrefs.setBool("dri3", v)
    var wakelock: Boolean
        get() = TinyPrefs.getBool("wakelock", false)
        set(v) = TinyPrefs.setBool("wakelock", v)
    var isHidpiEnabled: Boolean
        get() = TinyPrefs.getBool("isHidpiEnabled", false)
        set(v) = TinyPrefs.setBool("isHidpiEnabled", v)
    var isJpEnabled: Boolean
        get() = TinyPrefs.getBool("isJpEnabled", false)
        set(v) = TinyPrefs.setBool("isJpEnabled", v)
    var defaultVirglCommand: String
        get() = TinyPrefs.getString("defaultVirglCommand", "--use-egl-surfaceless --use-gles --socket-path=\$CONTAINER_DIR/tmp/.virgl_test")
        set(v) = TinyPrefs.setString("defaultVirglCommand", v)
    var defaultVirglOpt: String
        get() = TinyPrefs.getString("defaultVirglOpt", "GALLIUM_DRIVER=virpipe")
        set(v) = TinyPrefs.setString("defaultVirglOpt", v)
    var defaultTurnipOpt: String
        get() = TinyPrefs.getString(
            "defaultTurnipOpt",
            "MESA_LOADER_DRIVER_OVERRIDE=zink VK_ICD_FILENAMES=/home/tiny/.local/share/tiny/extra/freedreno_icd.aarch64.json TU_DEBUG=noconform",
        )
        set(v) = TinyPrefs.setString("defaultTurnipOpt", v)
    var defaultHidpiOpt: String
        get() = TinyPrefs.getString("defaultHidpiOpt", "GDK_SCALE=2 QT_FONT_DPI=192")
        set(v) = TinyPrefs.setString("defaultHidpiOpt", v)

    // ---- Baru: layar (compositor DroidDeck) ----
    /** 0 = otomatis (75% layar, seperti pengaturan awal Tiny Computer). */
    var displayWidth: Int
        get() = TinyPrefs.getInt("displayWidth", 0)
        set(v) = TinyPrefs.setInt("displayWidth", v)
    var displayHeight: Int
        get() = TinyPrefs.getInt("displayHeight", 0)
        set(v) = TinyPrefs.setInt("displayHeight", v)
    /** 0 = ikuti refresh rate panel. */
    var fpsLimit: Int
        get() = TinyPrefs.getInt("fpsLimit", 0)
        set(v) = TinyPrefs.setInt("fpsLimit", v)
    /** Kosong = pilih otomatis menurut GPU; selain itu id driver di files/graphics_driver. */
    var androidDriver: String
        get() = TinyPrefs.getString("androidDriver", "")
        set(v) = TinyPrefs.setString("androidDriver", v)
    /** 0 None 1 Linear 2 Nearest 3 SGSR 4 FSR 5 FSR Fit 6 Sharpen 7 NIS 8 SGSR HQ */
    var upscaler: Int
        get() = TinyPrefs.getInt("upscaler", 1)
        set(v) = TinyPrefs.setInt("upscaler", v)

    // ---- Baru: controller ----
    /** "xbox360" (evdev) atau "deck" (hidraw Steam Deck + evdev). */
    var padIdentity: String
        get() = TinyPrefs.getString("padIdentity", "xbox360")
        set(v) = TinyPrefs.setString("padIdentity", v)
    var padOverlay: Boolean
        get() = TinyPrefs.getBool("padOverlay", false)
        set(v) = TinyPrefs.setBool("padOverlay", v)
    var padOverlayOpacity: Double
        get() = TinyPrefs.getDouble("padOverlayOpacity", 0.65)
        set(v) = TinyPrefs.setDouble("padOverlayOpacity", v)
    var padOverlayScale: Double
        get() = TinyPrefs.getDouble("padOverlayScale", 1.0)
        set(v) = TinyPrefs.setDouble("padOverlayScale", v)
    var padHaptics: Boolean
        get() = TinyPrefs.getBool("padHaptics", true)
        set(v) = TinyPrefs.setBool("padHaptics", v)
    var padDeckPads: Boolean
        get() = TinyPrefs.getBool("padDeckPads", true)
        set(v) = TinyPrefs.setBool("padDeckPads", v)
}

/** Satu kontainer di `containersInfo` (JSON): name, boot, vnc, vncUrl, vncUri, commands. */
object Containers {
    fun list(): List<String> = TinyPrefs.getStringList("containersInfo") ?: emptyList()

    fun count() = list().size

    fun json(index: Int): JSONObject = JSONObject(list()[index])

    fun name(index: Int): String = json(index).optString("name", "Debian Trixie")

    fun boot(index: Int, defaultBoot: String): String =
        json(index).optString("boot", "").ifEmpty { defaultBoot }

    fun commands(index: Int, fallback: JSONArray): JSONArray =
        json(index).optJSONArray("commands") ?: fallback

    fun put(index: Int, key: String, value: Any) {
        val all = list().toMutableList()
        val obj = JSONObject(all[index])
        obj.put(key, value)
        all[index] = obj.toString()
        TinyPrefs.setStringList("containersInfo", all)
    }

    fun replaceAll(items: List<String>) = TinyPrefs.setStringList("containersInfo", items)
}
