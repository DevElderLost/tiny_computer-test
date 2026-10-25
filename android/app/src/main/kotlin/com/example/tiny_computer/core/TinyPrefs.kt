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
    /** Audio native langsung ke Android (AAudio) lewat plugin ALSA di guest; mematikan pulseaudio untuk output. */
    var directAudio: Boolean
        get() = TinyPrefs.getBool("directAudio", true)
        set(v) = TinyPrefs.setBool("directAudio", v)
    /** Paksa SDL (PPSSPP, dll.) dan Wine memakai ALSA langsung, bukan pulse. Bila gagal di guest, matikan: pulse tetap jalan lewat jembatan. */
    var directAudioPreferAlsa: Boolean
        get() = TinyPrefs.getBool("directAudioPreferAlsa", false)
        set(v) = TinyPrefs.setBool("directAudioPreferAlsa", v)
    /** Buffer awal stream AAudio (ms). Naik otomatis bila terjadi xrun (maks 150 ms). */
    var directAudioBufferMs: Int
        get() = TinyPrefs.getInt("directAudioBufferMs", 40)
        set(v) = TinyPrefs.setInt("directAudioBufferMs", v.coerceIn(10, 200))
    /** Kunci lama `autoLaunchVnc`. Tidak dipakai lagi: desktop hanya dibuka lewat tombol Open desktop. */
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
    /** DRM palsu (extra/fakedrm/libfakedrm.so) supaya glamor Xwayland punya render node. Eksperimental; butuh DRI3 + Turnip. */
    var fakeDrm: Boolean
        get() = TinyPrefs.getBool("fakeDrm", false)
        set(v) = TinyPrefs.setBool("fakeDrm", v)
    /** Desktop (Open desktop): aplikasi SDL/Vulkan memakai WSI Wayland ke compositor (Xwayland tanpa DRI3 gagal di Turnip). */
    var desktopWayland: Boolean
        get() = TinyPrefs.getBool("desktopWayland", true)
        set(v) = TinyPrefs.setBool("desktopWayland", v)
    /** Catat peluncuran aplikasi/.exe (Shortcut + Open desktop) ke Download/Wayland-logs, satu berkas per sesi proot. */
    var launchLog: Boolean
        get() = TinyPrefs.getBool("launchLog", false)
        set(v) = TinyPrefs.setBool("launchLog", v)
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
    /** Present mode swapchain layar compositor: 0 auto (FIFO), 1 FIFO, 2 FIFO relaxed, 3 Mailbox, 4 Immediate. */
    var presentMode: Int
        get() = TinyPrefs.getInt("presentMode", 0)
        set(v) = TinyPrefs.setInt("presentMode", v)
    /** Jumlah buffer swapchain layar: 0 otomatis, 2 double, 3 triple, 4 quad buffering. */
    var swapBuffers: Int
        get() = TinyPrefs.getInt("swapBuffers", 0)
        set(v) = TinyPrefs.setInt("swapBuffers", v)
    /** Shader ReShade FX (.fx, jalur relatif terhadap folder Shaders) yang aktif di compositor; kosong = mati. */
    var reshadeShader: String
        get() = TinyPrefs.getString("reshadeShader", "")
        set(v) = TinyPrefs.setString("reshadeShader", v)
    /** Kosong = pilih otomatis menurut GPU; selain itu id driver di files/graphics_driver. */
    var androidDriver: String
        get() = TinyPrefs.getString("androidDriver", "")
        set(v) = TinyPrefs.setString("androidDriver", v)
    /** Id driver Vulkan Linux (glibc) hasil impor yang dipakai di dalam kontainer; kosong = bawaan/otomatis. */
    var linuxDriver: String
        get() = TinyPrefs.getString("linuxDriver", "")
        set(v) = TinyPrefs.setString("linuxDriver", v)
    /** 0 None 1 Linear 2 Nearest 3 SGSR 4 FSR 5 FSR Fit 6 Sharpen 7 NIS 8 SGSR HQ */
    var upscaler: Int
        get() = TinyPrefs.getInt("upscaler", 1)
        set(v) = TinyPrefs.setInt("upscaler", v)

    // ---- Efek layar compositor dan HDR (lihat display/ScreenFx) ----
    /** HDR10 sungguhan: gerbang HDR compositor. Berlaku saat compositor dinyalakan. */
    /** Tab Flathub Store (Flatpak + Flathub di kontainer Debian trixie) tampil bila true. */
    var flathubStore: Boolean
        get() = TinyPrefs.getBool("flathubStore", false)
        set(v) = TinyPrefs.setBool("flathubStore", v)
    var hdrOutput: Boolean
        get() = TinyPrefs.getBool("hdrOutput", false)
        set(v) = TinyPrefs.setBool("hdrOutput", v)
    /** Tingkat putih SDR (nit) saat compositor menyusun gambar HDR; 203 = BT.2408. */
    var hdrSdrWhite: Int
        get() = TinyPrefs.getInt("hdrSdrWhite", 203)
        set(v) = TinyPrefs.setInt("hdrSdrWhite", v)
    /** Efek "HDR" laci DroidDeck: filter bloom/kontras pada keluaran SDR (bukan HDR sungguhan). */
    var hdrEffect: Boolean
        get() = TinyPrefs.getBool("hdrEffect", false)
        set(v) = TinyPrefs.setBool("hdrEffect", v)
    /** Ketajaman upscaler 0..100; kunci tidak ada = bawaan compositor. */
    var upscaleSharpness: Int
        get() = TinyPrefs.getInt("upscaleSharpness", 50)
        set(v) = TinyPrefs.setInt("upscaleSharpness", v)
    var casOn: Boolean
        get() = TinyPrefs.getBool("casOn", false)
        set(v) = TinyPrefs.setBool("casOn", v)
    var casLevel: Int
        get() = TinyPrefs.getInt("casLevel", 50)
        set(v) = TinyPrefs.setInt("casLevel", v)
    var debandOn: Boolean
        get() = TinyPrefs.getBool("debandOn", false)
        set(v) = TinyPrefs.setBool("debandOn", v)
    var debandLevel: Int
        get() = TinyPrefs.getInt("debandLevel", 100)
        set(v) = TinyPrefs.setInt("debandLevel", v)
    /** -100..100 (0 = netral). */
    var fxBrightness: Int
        get() = TinyPrefs.getInt("fxBrightness", 0)
        set(v) = TinyPrefs.setInt("fxBrightness", v)
    /** -100..100 (0 = netral). */
    var fxContrast: Int
        get() = TinyPrefs.getInt("fxContrast", 0)
        set(v) = TinyPrefs.setInt("fxContrast", v)
    /** Gamma x100: 50..300 (100 = netral). */
    var fxGamma: Int
        get() = TinyPrefs.getInt("fxGamma", 100)
        set(v) = TinyPrefs.setInt("fxGamma", v)
    /** Saturasi persen 0..200 (100 = netral). */
    var fxSaturation: Int
        get() = TinyPrefs.getInt("fxSaturation", 100)
        set(v) = TinyPrefs.setInt("fxSaturation", v)
    var fxFxaa: Boolean
        get() = TinyPrefs.getBool("fxFxaa", false)
        set(v) = TinyPrefs.setBool("fxFxaa", v)

    /** Id palet: graphite (bawaan), paper, blue, electric. Sama dengan pilihan tema DroidDeck. */
    var theme: String
        get() = TinyPrefs.getString("theme", "graphite")
        set(v) = TinyPrefs.setString("theme", v)

    // ---- Baru: controller ----
    /** "xbox360" (evdev) atau "deck" (hidraw Steam Deck + evdev). */
    var padIdentity: String
        get() = TinyPrefs.getString("padIdentity", "xbox360")
        set(v) = TinyPrefs.setString("padIdentity", v)
    var padOverlay: Boolean
        get() = TinyPrefs.getBool("padOverlay", false)
        set(v) = TinyPrefs.setBool("padOverlay", v)
    /**
     * Mode touchpad (kursor digerakkan relatif) untuk layar desktop/aplikasi; bawaan aktif, false = sentuh langsung.
     * Kunci diganti ke "touchpadModeV2" supaya nilai false lama dari versi sebelumnya tidak menahan mode ini mati:
     * semua pengguna mulai dengan touchpad aktif, lalu pilihan baru disimpan seperti biasa.
     */
    var touchpadMode: Boolean
        get() = TinyPrefs.getBool("touchpadModeV2", true)
        set(v) = TinyPrefs.setBool("touchpadModeV2", v)
    var touchpadSpeed: Double
        get() = TinyPrefs.getDouble("touchpadSpeed", 1.6)
        set(v) = TinyPrefs.setDouble("touchpadSpeed", v)
    /** Kecepatan gulir dua jari dan arahnya (true = isi mengikuti jari, seperti ponsel). */
    var touchpadScroll: Double
        get() = TinyPrefs.getDouble("touchpadScroll", 1.0)
        set(v) = TinyPrefs.setDouble("touchpadScroll", v)
    var touchpadNatural: Boolean
        get() = TinyPrefs.getBool("touchpadNatural", true)
        set(v) = TinyPrefs.setBool("touchpadNatural", v)
    /** Gambar kursor bawaan rootfs (dari wl_pointer.set_cursor) di atas layar. */
    var showCursor: Boolean
        get() = TinyPrefs.getBool("showCursor", true)
        set(v) = TinyPrefs.setBool("showCursor", v)
    var cursorScale: Double
        get() = TinyPrefs.getDouble("cursorScale", 1.0)
        set(v) = TinyPrefs.setDouble("cursorScale", v)
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

    /** Desktop pernah dibuka sekali (Xwayland + sesi X11 dinyalakan); syarat ikon tampil di menu Shortcut. */
    var desktopOpenedOnce: Boolean
        get() = TinyPrefs.getBool("desktopOpenedOnce", false)
        set(v) = TinyPrefs.setBool("desktopOpenedOnce", v)

    /** Layar awal (info pemasangan + izin) sudah dilewati; tidak ditampilkan lagi. */
    var onboardingDone: Boolean
        get() = TinyPrefs.getBool("onboardingDone", false)
        set(v) = TinyPrefs.setBool("onboardingDone", v)
}

/** Satu kontainer di `containersInfo` (JSON): name, boot, vnc, vncUrl, vncUri, commands. */
object Containers {
    fun list(): List<String> = TinyPrefs.getStringList("containersInfo") ?: emptyList()

    fun count() = list().size

    // Sebelum sistem terpasang daftar masih kosong; Beranda/Pengaturan membacanya, jadi jangan melempar.
    fun json(index: Int): JSONObject =
        list().getOrNull(index)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun name(index: Int): String = json(index).optString("name", "Debian Trixie")

    /** True untuk sistem hasil import yang bukan rootfs Tiny (root, tanpa desktop Tiny): hanya terminal. */
    fun isGeneric(index: Int): Boolean = json(index).optBoolean("generic", false)

    fun boot(index: Int, defaultBoot: String): String =
        json(index).optString("boot", "").ifEmpty { defaultBoot }

    fun commands(index: Int, fallback: JSONArray): JSONArray =
        json(index).optJSONArray("commands") ?: fallback

    fun put(index: Int, key: String, value: Any) {
        val all = list().toMutableList()
        if (index !in all.indices) return
        val obj = JSONObject(all[index])
        obj.put(key, value)
        all[index] = obj.toString()
        TinyPrefs.setStringList("containersInfo", all)
    }

    fun replaceAll(items: List<String>) = TinyPrefs.setStringList("containersInfo", items)
}
