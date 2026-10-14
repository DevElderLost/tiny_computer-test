package com.example.tiny_computer.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Pemasangan Hangover lewat apt (skrip `install-hangover` / `install-hangover-stable`, logika tiny_computer original).
 * Berdampingan dengan Wine/Proton .wcp ([WineContents]), tetapi keduanya tidak boleh aktif bersamaan: pemasangan
 * ini baru boleh dipakai setelah SEMUA build Wine/Proton .wcp dihapus ([oldWineInstalled] false), supaya shim `wine`
 * di /usr/local/bin kontainer tidak menutupi `wine` milik paket Hangover.
 *
 * Skrip dijalankan di terminal kontainer (hasilnya terlihat di tab Terminal), jadi kontainer harus sudah menyala.
 */
object HangoverSetup {
    private const val TAG = "HangoverSetup"
    private const val ASSET_DIR = "tiny/hangover"
    private const val GUEST_EXTRA = "~/.local/share/tiny/extra"

    enum class Mode(val script: String) { STABLE("install-hangover-stable"), LATEST("install-hangover") }

    /** Berkas yang disalin ke `~/.local/share/tiny/extra`; chn_fonts.reg tidak menimpa berkas yang sudah ada. */
    private val FILES = listOf("install-hangover", "install-hangover-stable", "chn_fonts.reg")

    /** Masih ada Wine/Proton versi lama (.wcp) yang terpasang? */
    fun oldWineInstalled(): Boolean =
        WineContents.installed(WineContents.Type.WINE).isNotEmpty() ||
            WineContents.installed(WineContents.Type.PROTON).isNotEmpty()

    /** Mengembalikan pesan galat, atau null bila perintah sudah dikirim ke terminal. */
    fun install(ctx: Context, mode: Mode): String? {
        if (oldWineInstalled()) return "Remove every Wine/Proton build above first, then install Hangover."
        if (WineSetup.phase == WineSetup.Phase.RUNNING) return "Wait for Prepare to finish, then try again."
        if (!Launcher.isStarted) return "Start the container first (Open desktop), then run this again."
        try {
            val dir = File(TinyEnv.dataPath, "tiny/extra").apply { mkdirs() }
            for (name in FILES) {
                val dst = File(dir, name)
                if (name.endsWith(".reg") && dst.isFile) continue
                ctx.assets.open("$ASSET_DIR/$name").use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
            }
            // env.sh + shim lama dibuang supaya `wine` hasil apt yang dipakai
            WineContents.removeRuntime()
        } catch (t: Throwable) {
            Log.e(TAG, "menyiapkan skrip Hangover gagal", t)
            return t.message ?: t.javaClass.simpleName
        }
        TermSessions.forCurrent(ctx).writeLine("bash $GUEST_EXTRA/${mode.script}")
        return null
    }

    /** Sama dengan tombol Uninstall Hangover versi original. */
    fun uninstall(ctx: Context): String? {
        if (!Launcher.isStarted) return "Start the container first (Open desktop), then run this again."
        TermSessions.forCurrent(ctx).writeLine("sudo apt autoremove --purge -y hangover*")
        return null
    }
}
