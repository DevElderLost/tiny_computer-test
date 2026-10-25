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
 * Per aplikasi dapat diatur dengan env Shortcut `TINY_FLATPAK_DISPLAY=`:
 *  - `auto` (bawaan): X11/Xwayland bila Turnip aktif DAN Xwayland DRI3 hasil patch terpasang, selain itu Wayland;
 *  - `wayland`: paksa Wayland dulu; `x11`: paksa X11 (Java, Tk); `off`: tidak mengubah apa pun.
 *
 * Kenapa `auto` tidak lagi selalu Wayland: platform EGL Wayland di Mesa tidak bisa memuat Zink tanpa node DRM
 * sungguhan, lalu diam-diam jatuh ke llvmpipe (OpenGL software; log: GL_RENDERER "llvmpipe"). Aplikasi OpenGL Flathub (Qt/GTK/SDL, mis. emulator) yang dipaksa `wayland;xcb` /
 * `wayland,x11` karena itu jalan di CPU, sedangkan AppImage di sesi yang sama memakai X11 (`GDK_BACKEND=x11`,
 * `QT_QPA_PLATFORM=xcb`) dengan Zink + Kopper di Turnip (GPU). Sejak Xwayland punya DRI3 (extra/xwayland-dri3-nodrm),
 * Vulkan lewat xcb juga jalan (vkcube --wsi xcb), jadi jalur X11 tidak lagi mengorbankan aplikasi Vulkan.
 */
object FlatpakWayland {
    enum class Mode { AUTO, WAYLAND, X11, OFF }

    class Result(val command: String, val note: String)

    private val RUN = Regex("^((?:\\S*/)?flatpak)\\s+run(\\s.*)?$", RegexOption.DOT_MATCHES_ALL)
    private const val GUEST_TINY = "/home/tiny/.local/share/tiny"

    /** `TINY_FLATPAK_DISPLAY` dari env Shortcut (baris `KEY=VALUE`); bawaan AUTO. */
    fun mode(env: String): Mode {
        val v = env.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("TINY_FLATPAK_DISPLAY=") }
            ?.substringAfter('=')?.trim()?.trim('\'', '"')?.lowercase()
        return when (v) {
            "x11" -> Mode.X11
            "wayland" -> Mode.WAYLAND
            "off", "0", "none" -> Mode.OFF
            else -> Mode.AUTO
        }
    }

    /** Xwayland hasil patch (DRI3 tanpa DRM) terpasang dan dinyalakan: syarat jalur X11 memberi GPU untuk GL dan Vulkan. */
    private fun dri3Xwayland(): Boolean = Cfg.dri3 && File(TinyEnv.dataPath, "tiny/extra/Xwayland").isFile

    /** AUTO -> X11 (Zink/Turnip untuk OpenGL) bila Turnip aktif dan DRI3 ada, selain itu Wayland seperti sebelumnya. */
    internal fun resolve(mode: Mode, turnipOn: Boolean): Mode =
        if (mode == Mode.AUTO) (if (turnipOn && dri3Xwayland()) Mode.X11 else Mode.WAYLAND) else mode

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** `K=V K=V` -> pasangan; token tanpa `=` dibuang. */
    private fun pairs(opts: String): List<Pair<String, String>> =
        opts.trim().split(Regex("\\s+")).mapNotNull { t ->
            val i = t.indexOf('=')
            if (i > 0) t.substring(0, i) to t.substring(i + 1) else null
        }

    /** `~/.asoundrc` di rootfs: tempat ALSA di dalam sandbox Flatpak membaca konfigurasi pengguna. */
    internal fun asoundrcFile() = File(TinyEnv.containerDir(), "home/tiny/.asoundrc")

    /**
     * Audio langsung (plugin ALSA `tinyaudio` -> soket -> AAudio) untuk sandbox Flatpak. Sandbox punya `/etc` dan
     * `/tmp` sendiri, jadi `/etc/asound.conf` milik guest tidak terlihat dan ALSA bawaan runtime hanya punya
     * perangkat "null" (aplikasi yang tidak sampai ke PulseAudio diam). Di sini: tulis `~/.asoundrc` (isinya sama
     * dengan asound.conf guest; dibaca libasound runtime lewat `$HOME`), lalu [BwrapSpawner] mem-bind berkas itu dan
     * soket `/tmp/.tinyaudio` ke sandbox di semua tingkat preflight. Mengembalikan keterangan untuk log, dan
     * menambah opsi `flatpak run` ke [opts]. `~/.asoundrc` buatan pengguna tidak pernah ditimpa.
     */
    private fun directAudio(opts: MutableList<String>): String {
        val f = asoundrcFile()
        val on = Launcher.directAudioActive()
        val mine = runCatching { f.isFile && f.readText().startsWith(Launcher.ASOUND_MARK) }.getOrDefault(false)
        if (!on) {
            if (mine) runCatching { f.delete() }
            return "audio: PulseAudio (direct audio mati)"
        }
        if (f.exists() && !mine) return "audio: ~/.asoundrc milik pengguna sudah ada; direct audio tidak dipasang ke sandbox"
        val ok = runCatching {
            f.parentFile?.mkdirs()
            val want = Launcher.asoundConf()
            if (!f.isFile || f.readText() != want) f.writeText(want)
            f.setReadable(true, false)
        }.isSuccess
        if (!ok) return "audio: gagal menulis ~/.asoundrc"
        // plugin ada di folder extra: harus terlihat oleh sandbox (juga bila Turnip/controller tidak aktif)
        opts += "--filesystem=$GUEST_TINY/extra:ro"
        if (Cfg.directAudioPreferAlsa) {
            // SDL dan OpenAL memilih PulseAudio lebih dulu; tanpa server pulse di sandbox mereka diam. Paksa ALSA.
            opts += listOf("--env=SDL_AUDIODRIVER=alsa", "--env=ALSOFT_DRIVERS=alsa")
        }
        return "audio: ALSA langsung (tinyaudio)" + if (Cfg.directAudioPreferAlsa) ", SDL/OpenAL dipaksa ALSA" else ""
    }

    /**
     * Menulis ulang [cmd] bila itu `flatpak run ...`; null bila bukan flatpak atau [mode] OFF.
     * [turnipOn] = Turnip aktif untuk aplikasi ini (lihat ShortcutRunner).
     */
    fun rewrite(ctx: Context, cmd: String, requested: Mode, turnipOn: Boolean): Result? {
        if (requested == Mode.OFF) return null
        val mode = resolve(requested, turnipOn)
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
            Mode.OFF, Mode.AUTO -> return null
        }
        // Tanpa xdg-desktop-portal di kontainer, dialog berkas bawaan Flatpak (Qt tema "flatpak", GTK) tidak pernah
        // muncul: "org.freedesktop.portal.Desktop was not provided by any .service files". Pakai dialog Qt/GTK sendiri.
        opts += listOf("--env=QT_QPA_PLATFORMTHEME=generic", "--env=GTK_USE_PORTAL=0")
        // Tanpa portal, dialog berkas membaca sistem berkas sandbox apa adanya: beri akses folder rumah (Downloads,
        // Documents, ... dari mount sesi diteruskan oleh BwrapSpawner.homeMounts). Aplikasi yang sudah `host` tetap sama.
        opts += "--filesystem=home"
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
        // Controller virtual: libfakeinput melayani /dev/input (dipasang BwrapSpawner.padBinds); variabelnya harus
        // lewat `--env` karena `flatpak run` membersihkan lingkungan. Pustakanya ada di folder `tiny/extra`.
        var pad = "tanpa controller (libfakeinput.so tidak ada)"
        if (File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile) {
            runCatching { Launcher.padResult(ctx) }.getOrNull()?.let { r ->
                if (!turnipOn) opts += "--filesystem=$GUEST_TINY/extra:ro"
                opts += "--device=all"
                for (e in r.env) opts += "--env=$e"
                opts += "--env=LD_PRELOAD=$GUEST_TINY/extra/libfakeinput.so"
                pad = "controller virtual aktif"
            }
        }
        // Batas memori Vulkan (lapisan di folder extra): `flatpak run` membersihkan lingkungan, jadi lewat `--env`.
        VkMemLimit.envList()?.let { env ->
            opts += "--filesystem=$GUEST_TINY/extra:ro"
            for (e in env) opts += "--env=$e"
        }
        val audio = directAudio(opts)
        opts.distinct().let { opts.clear(); opts.addAll(it) }
        val command = prog + " run " + opts.joinToString(" ") { q(it) } + rest
        val why = when {
            requested != Mode.AUTO -> if (mode == Mode.WAYLAND) " (dipilih: Wayland dulu, X11 hanya cadangan)" else " (dipilih: X11/Xwayland)"
            mode == Mode.X11 -> " (otomatis: X11 + Xwayland DRI3 supaya OpenGL memakai Zink/Turnip, bukan llvmpipe)"
            else -> " (otomatis: Wayland, karena Turnip/Xwayland DRI3 tidak aktif)"
        }
        val note = "[tiny] Flatpak display=${mode.name.lowercase()}" + why +
            "; GPU: $gpu; $pad; $audio"
        return Result(command, note)
    }
}
