package com.example.tiny_computer.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Pengaturan per aplikasi di menu Shortcut (tekan lama pada ikon). Disimpan di berkas
 * SharedPreferences tersendiri, TERPISAH dari [TinyPrefs]/[Cfg]: tidak ada yang mengubah pengaturan
 * desktop utama, dan pengaturan desktop utama tidak ikut terbawa ke aplikasi.
 */
object ShortcutPrefs {
    const val DEFAULT = "default"
    const val ON = "on"
    const val OFF = "off"
    const val GPU_SOFTWARE = "software"
    const val GPU_TURNIP = "turnip"
    const val DISPLAY_XWAYLAND = "xwayland"
    const val DISPLAY_WAYLAND = "wayland"
    const val DISPLAY_XPP = "xpp"
    const val SOUND_PULSE = "pulse"
    const val SOUND_ALSA = "alsa"

    data class Settings(
        /** Nama tampilan; kosong = nama dari berkas .desktop. */
        val name: String = "",
        /** Perintah peluncuran pengganti baris Exec; kosong = Exec dari berkas .desktop (tanpa awalan `env KEY=VALUE`). */
        val command: String = "",
        /** Argumen tambahan di belakang perintah Exec. */
        val args: String = "",
        /** Variabel lingkungan, satu `KEY=VALUE` per baris. */
        val env: String = "",
        /** Direktori kerja di dalam kontainer; kosong = `Path=` dari .desktop, lalu rumah pengguna. */
        val workDir: String = "",
        /** default | software | turnip */
        val gpu: String = DEFAULT,
        /** default | on | off */
        val hidpi: String = DEFAULT,
        /** Controller di layar saat aplikasi ini berjalan: default (ikut global) | on | off */
        val pad: String = DEFAULT,
        /** Ukuran layar sesi aplikasi `LEBARxTINGGI`; kosong = ikuti pengaturan global (Settings > Output resolution). */
        val screen: String = "",
        /** Display server khusus aplikasi ini: default (ikut Settings desktop) | xwayland | wayland | xpp */
        val display: String = DEFAULT,
        /** Mode tampilan X++ khusus aplikasi ini: default | vulkan | egl | displayx | old */
        val xppBackend: String = DEFAULT,
        /** Compositor xfwm4 di desktop sesi Shortcut: default | on | off */
        val compositor: String = DEFAULT,
        /** Shortcut membuka desktop XFCE lengkap atau sesi minimal: default | on | off */
        val desktopMode: String = DEFAULT,
        /** Qt memakai EGL + Vulkan di X++: default | on | off */
        val qtGpu: String = DEFAULT,
        /** Batas FPS (0 = laju panel); -1 = ikut Settings. */
        val fps: Int = -1,
        /** Present mode layar/X++ (0 auto, 1 FIFO, 2 FIFO relaxed, 3 Mailbox, 4 Immediate); -1 = ikut Settings. */
        val presentMode: Int = -1,
        /** Jumlah buffer swapchain compositor Wayland (0 auto, 2, 3, 4); -1 = ikut Settings. */
        val buffering: Int = -1,
        /** Batas memori Vulkan compositor Wayland (MiB, 0 = tanpa batas); -1 = ikut Settings. */
        val compositorMem: Int = -1,
        /** Batas memori Vulkan aplikasi ini (MiB, 0 = tanpa batas); -1 = ikut Settings. */
        val vulkanMem: Int = -1,
        /** Sound mode khusus aplikasi ini: default (ikut Settings) | pulse (PulseAudio) | alsa (ALSA langsung ke suara Android). */
        val sound: String = DEFAULT,
        val hidden: Boolean = false,
    )

    private const val FILE = "tiny_shortcuts"
    private val sp: SharedPreferences by lazy { TinyEnv.app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /** Naik setiap ada perubahan supaya layar Shortcut yang membacanya ikut menyusun ulang. */
    var version by mutableIntStateOf(0)
        private set

    fun get(id: String): Settings {
        val raw = sp.getString("app.$id", null) ?: return Settings()
        return runCatching {
            val o = JSONObject(raw)
            Settings(
                name = o.optString("name"), command = o.optString("command"), args = o.optString("args"), env = o.optString("env"),
                workDir = o.optString("workDir"), gpu = o.optString("gpu", DEFAULT), hidpi = o.optString("hidpi", DEFAULT),
                pad = o.optString("pad", DEFAULT), screen = o.optString("screen"),
                display = o.optString("display", DEFAULT), xppBackend = o.optString("xppBackend", DEFAULT),
                compositor = o.optString("compositor", DEFAULT), desktopMode = o.optString("desktopMode", DEFAULT),
                qtGpu = o.optString("qtGpu", DEFAULT),
                fps = o.optInt("fps", -1), presentMode = o.optInt("presentMode", -1), buffering = o.optInt("buffering", -1),
                compositorMem = o.optInt("compositorMem", -1), vulkanMem = o.optInt("vulkanMem", -1),
                sound = o.optString("sound", DEFAULT),
                hidden = o.optBoolean("hidden", false),
            )
        }.getOrDefault(Settings())
    }

    fun save(id: String, s: Settings) {
        if (s == Settings()) sp.edit().remove("app.$id").apply()
        else {
            val o = JSONObject()
                .put("name", s.name).put("command", s.command).put("args", s.args).put("env", s.env).put("workDir", s.workDir)
                .put("gpu", s.gpu).put("hidpi", s.hidpi).put("pad", s.pad).put("screen", s.screen)
                .put("display", s.display).put("xppBackend", s.xppBackend).put("compositor", s.compositor)
                .put("desktopMode", s.desktopMode).put("qtGpu", s.qtGpu)
                .put("fps", s.fps).put("presentMode", s.presentMode).put("buffering", s.buffering)
                .put("compositorMem", s.compositorMem).put("vulkanMem", s.vulkanMem).put("sound", s.sound)
                .put("hidden", s.hidden)
            sp.edit().putString("app.$id", o.toString()).apply()
        }
        version++
    }

    fun reset(id: String) = save(id, Settings())

    /** on -> true, off -> false, lainnya (default) -> null. */
    fun triState(v: String): Boolean? = when (v) { ON -> true; OFF -> false; else -> null }

    /**
     * Pengaturan desktop EFEKTIF sebuah aplikasi: pilihan khusus Shortcut bila ada, kalau tidak pengaturan desktop
     * kontainer utama ([Cfg]). Dengan begitu pengaturan desktop bisa dibedakan per Shortcut tanpa bolak-balik ke
     * Settings. [id] null = tanpa pilihan khusus (pengaturan utama).
     */
    fun displayServer(id: String?): String = when (id?.let { get(it).display }) {
        DISPLAY_XWAYLAND -> Cfg.DISPLAY_XWAYLAND
        DISPLAY_WAYLAND -> Cfg.DISPLAY_WAYLAND
        DISPLAY_XPP -> Cfg.DISPLAY_XPP
        else -> Cfg.displayServer
    }

    fun xppBackend(id: String?): Int = when (id?.let { get(it).xppBackend }) {
        "vulkan" -> Cfg.XPP_VULKAN
        "egl" -> Cfg.XPP_EGL
        "displayx" -> Cfg.XPP_DISPLAYX
        "old" -> Cfg.XPP_OLD_DISPLAY
        else -> Cfg.xppBackend
    }

    fun compositor(id: String?): Boolean = id?.let { triState(get(it).compositor) } ?: Cfg.xfceCompositor
    fun desktopMode(id: String?): Boolean = id?.let { triState(get(it).desktopMode) } ?: Cfg.shortcutDesktop
    fun qtGpu(id: String?): Boolean = id?.let { triState(get(it).qtGpu) } ?: Cfg.xppQtGpu

    // Present / FPS / memori: pilihan khusus Shortcut (>= 0) atau pengaturan Display dan Batas memori utama.
    // Berlaku untuk compositor Wayland (Xwayland/Wayland) dan server X++.
    fun fpsLimit(id: String?): Int = id?.let { get(it).fps }?.takeIf { it >= 0 } ?: Cfg.fpsLimit
    fun presentMode(id: String?): Int = id?.let { get(it).presentMode }?.takeIf { it >= 0 } ?: Cfg.presentMode
    fun swapBuffers(id: String?): Int = id?.let { get(it).buffering }?.takeIf { it >= 0 } ?: Cfg.swapBuffers
    fun compositorMemMb(id: String?): Int = id?.let { get(it).compositorMem }?.takeIf { it >= 0 } ?: Cfg.compositorMemMb

    /** Controller di layar untuk aplikasi ini; null = ikuti pengaturan global. */
    fun padOverlay(id: String?): Boolean? {
        if (id == null) return null
        return when (get(id).pad) { ON -> true; OFF -> false; else -> null }
    }

    fun setPadOverlay(id: String, on: Boolean) = save(id, get(id).copy(pad = if (on) ON else OFF))

    /** `1280x720` (huruf x kecil/besar, boleh spasi) -> (1280, 720); null bila bukan ukuran yang wajar. */
    fun parseSize(text: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{3,5})\s*[xX×]\s*(\d{3,5})\s*$""").matchEntire(text) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        return if (w in 320..7680 && h in 240..4320) w to h else null
    }

    /** Ukuran layar khusus aplikasi ini; null = ikuti pengaturan global. */
    fun screenSize(id: String?): Pair<Int, Int>? = if (id == null) null else parseSize(get(id).screen)
}
