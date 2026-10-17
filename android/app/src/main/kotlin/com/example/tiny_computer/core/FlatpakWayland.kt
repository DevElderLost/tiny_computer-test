package com.example.tiny_computer.core

import android.content.Context
import java.io.File

/**
 * Memilih jalur tampilan untuk aplikasi Flathub yang dijalankan dari menu Shortcut: Wayland (compositor
 * DroidDeck) lebih dulu, X11 lewat Xwayland HANYA sebagai cadangan.
 *
 * Latar belakang (log Azahar + Audacity, 2026-10-17):
 *  - Azahar: Flatpak-nya mendapat soket Wayland (flatpak sendiri tercatat terhubung ke compositor, dan `zenity`
 *    di dalam sandbox menggambar lewat Wayland), sehingga `--socket=fallback-x11` TIDAK memberi DISPLAY. Tetapi
 *    Qt dipaksa `xcb` (`QT_QPA_PLATFORM=xcb`), jadi "qt.qpa.xcb: could not connect to display" lalu abort.
 *  - Audacity: manifestnya hanya `--socket=x11`, jadi selalu lewat Xwayland (compositor hanya melihat klien
 *    "Xwayland", bukan Audacity).
 *  - `flatpak run` membersihkan lingkungan, jadi `GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb` milik guest dan variabel
 *    Turnip milik Shortcut tidak pernah sampai ke aplikasi: grafisnya llvmpipe.
 *
 * Karena itu opsi ditambahkan langsung ke baris `flatpak run` (bukan ke lingkungan guest):
 *  - soket: `--nosocket=x11 --socket=wayland --socket=fallback-x11` (X11 hanya bila Wayland tidak ada);
 *  - toolkit: daftar cadangan `wayland` lalu `x11` (Qt `wayland;xcb`, GTK `wayland,x11`, SDL `wayland,x11`);
 *  - GPU: variabel Turnip/Zink + folder `tiny/extra` (ICD bawaan) bila Turnip aktif untuk aplikasi ini;
 *  - dialog berkas: tanpa portal di kontainer, Qt/GTK dipaksa memakai dialog sendiri (QT_QPA_PLATFORMTHEME=generic).
 *
 * Per aplikasi dapat dimatikan dengan env Shortcut `TINY_FLATPAK_DISPLAY=x11` (paksa X11, perilaku lama untuk
 * aplikasi yang tidak punya Wayland: Java, Tk, Wine) atau `TINY_FLATPAK_DISPLAY=off` (tidak mengubah apa pun).
 */
object FlatpakWayland {
    enum class Mode { WAYLAND, X11, OFF }

    class Result(val command: String, val note: String)

    private val RUN = Regex("^((?:\\S*/)?flatpak)\\s+run(\\s.*)?$", RegexOption.DOT_MATCHES_ALL)
    private const val GUEST_TINY = "/home/tiny/.local/share/tiny"

    /** `TINY_FLATPAK_DISPLAY` dari env Shortcut (baris `KEY=VALUE`); bawaan WAYLAND. */
    fun mode(env: String): Mode {
        val v = env.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("TINY_FLATPAK_DISPLAY=") }
            ?.substringAfter('=')?.trim()?.trim('\'', '"')?.lowercase()
        return when (v) {
            "x11" -> Mode.X11
            "off", "0", "none" -> Mode.OFF
            else -> Mode.WAYLAND
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** `K=V K=V` -> pasangan; token tanpa `=` dibuang. */
    private fun pairs(opts: String): List<Pair<String, String>> =
        opts.trim().split(Regex("\\s+")).mapNotNull { t ->
            val i = t.indexOf('=')
            if (i > 0) t.substring(0, i) to t.substring(i + 1) else null
        }

    /**
     * Menulis ulang [cmd] bila itu `flatpak run ...`; null bila bukan flatpak atau [mode] OFF.
     * [turnipOn] = Turnip aktif untuk aplikasi ini (lihat ShortcutRunner).
     */
    fun rewrite(ctx: Context, cmd: String, mode: Mode, turnipOn: Boolean): Result? {
        if (mode == Mode.OFF) return null
        val m = RUN.matchEntire(cmd.trim()) ?: return null
        val prog = m.groupValues[1]
        val rest = m.groupValues[2]
        val opts = ArrayList<String>()
        when (mode) {
            Mode.WAYLAND -> {
                opts += listOf("--nosocket=x11", "--socket=wayland", "--socket=fallback-x11")
                opts += listOf(
                    "--env=QT_QPA_PLATFORM=wayland;xcb",
                    "--env=GDK_BACKEND=wayland,x11",
                    "--env=SDL_VIDEODRIVER=wayland,x11",
                    "--env=CLUTTER_BACKEND=wayland",
                    "--env=MOZ_ENABLE_WAYLAND=1",
                    "--env=ELECTRON_OZONE_PLATFORM_HINT=auto",
                )
            }
            Mode.X11 -> {
                opts += listOf("--nosocket=wayland", "--socket=x11")
                opts += listOf(
                    "--env=QT_QPA_PLATFORM=xcb",
                    "--env=GDK_BACKEND=x11",
                    "--env=SDL_VIDEODRIVER=x11",
                    "--env=MOZ_ENABLE_WAYLAND=0",
                    "--env=ELECTRON_OZONE_PLATFORM_HINT=x11",
                )
            }
            Mode.OFF -> return null
        }
        // Tanpa xdg-desktop-portal di kontainer, dialog berkas bawaan Flatpak (Qt tema "flatpak", GTK) tidak pernah
        // muncul: "org.freedesktop.portal.Desktop was not provided by any .service files". Pakai dialog Qt/GTK sendiri.
        opts += listOf("--env=QT_QPA_PLATFORMTHEME=generic", "--env=GTK_USE_PORTAL=0")
        var gpu = "llvmpipe (Turnip tidak aktif untuk aplikasi ini)"
        if (turnipOn) {
            for ((k, v) in pairs(Launcher.turnipOpt(ctx))) opts += "--env=$k=$v"
            // Xwayland tanpa DRI3 (hanya jalur X11): presentasi Vulkan lewat WSI software
            if (mode == Mode.X11) {
                opts += "--env=LIBGL_KOPPER_DRI2=true"
                if (!Cfg.dri3) opts += "--env=MESA_VK_WSI_DEBUG=sw"
            }
            // ICD bawaan (`$GUEST_TINY/extra/...`) ada di luar sandbox; BwrapSpawner menerjemahkan path guest ini ke
            // host lewat bind sesi. Driver hasil impor ada di /data, yang selalu di-bind ke sandbox.
            if (File(TinyEnv.dataPath, "tiny/extra").isDirectory) opts += "--filesystem=$GUEST_TINY/extra:ro"
            gpu = "Turnip/Zink (VK_ICD dari Pengaturan)"
        }
        val command = prog + " run " + opts.joinToString(" ") { q(it) } + rest
        val note = "[tiny] Flatpak display=${mode.name.lowercase()}" +
            (if (mode == Mode.WAYLAND) " (Wayland dulu, X11 hanya cadangan)" else " (paksa X11/Xwayland)") +
            "; GPU: $gpu"
        return Result(command, note)
    }
}
