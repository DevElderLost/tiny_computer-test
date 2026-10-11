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

    data class Settings(
        /** Nama tampilan; kosong = nama dari berkas .desktop. */
        val name: String = "",
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
                name = o.optString("name"), args = o.optString("args"), env = o.optString("env"),
                workDir = o.optString("workDir"), gpu = o.optString("gpu", DEFAULT), hidpi = o.optString("hidpi", DEFAULT),
                pad = o.optString("pad", DEFAULT), screen = o.optString("screen"),
                hidden = o.optBoolean("hidden", false),
            )
        }.getOrDefault(Settings())
    }

    fun save(id: String, s: Settings) {
        if (s == Settings()) sp.edit().remove("app.$id").apply()
        else {
            val o = JSONObject()
                .put("name", s.name).put("args", s.args).put("env", s.env).put("workDir", s.workDir)
                .put("gpu", s.gpu).put("hidpi", s.hidpi).put("pad", s.pad).put("screen", s.screen)
                .put("hidden", s.hidden)
            sp.edit().putString("app.$id", o.toString()).apply()
        }
        version++
    }

    fun reset(id: String) = save(id, Settings())

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
