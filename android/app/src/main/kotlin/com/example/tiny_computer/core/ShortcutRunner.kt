package com.example.tiny_computer.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Menjalankan aplikasi dari menu Shortcut TANPA membuka desktop, seperti DroidDeck menjalankan satu
 * program penuh layar (di DroidDeck lewat gamescope; di sini lewat Xwayland + window manager ringan
 * di atas compositor yang sama). Skrip peluncur ditulis ke `$DATA_DIR/tiny/shortcuts`, yang di dalam
 * kontainer terlihat sebagai `~/.local/share/tiny/shortcuts`. Skrip itu dijalankan oleh proot BARU
 * ([Launcher.launchApp]), bukan diketik ke terminal kontainer, sehingga tidak bergantung pada shell
 * kontainer yang hidup. Pengaturan per aplikasi ([ShortcutPrefs]) hanya hidup di skrip itu, tidak
 * bocor ke desktop.
 */
object ShortcutRunner {
    private const val TAG = "TinyShortcut"
    private const val GUEST_DIR = "/home/tiny/.local/share/tiny/shortcuts"
    private const val SESSION_SCRIPT = "app-session.sh"

    private fun hostDir() = File(TinyEnv.dataPath, "tiny/shortcuts").apply { mkdirs() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun write(name: String, text: String) {
        val f = File(hostDir(), name)
        f.writeText(text)
        f.setReadable(true, false)
        f.setExecutable(true, false)
    }

    /** Kutip tunggal yang aman untuk sh: `it's` -> `'it'\''s'`. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Membuang kode field dari baris Exec (spesifikasi Desktop Entry): %f %F %u %U %d %D %n %N %i %c %k %v %m.
     * Kode yang diapit kutip ikut dibuang agar tidak tersisa argumen kosong; `%%` menjadi `%`.
     */
    fun cleanExec(exec: String): String {
        var s = exec.replace("%%", "\u0000")
        s = s.replace(Regex("\"%[fFuUdDnNickvm]\""), "")
        s = s.replace(Regex("%[fFuUdDnNickvm]"), "")
        return s.replace("\u0000", "%").replace(Regex("\\s+"), " ").trim()
    }

    /** Nilai bawaan satu shortcut menurut berkas .desktop-nya; kosong bila berkasnya tidak memuat bagian itu. */
    class DesktopDefaults(val name: String, val command: String, val env: String, val workDir: String)

    /** Satu token sh mulai [from]: (indeks akhir, nilai tanpa kutip). Kutip tunggal/ganda dan backslash dihormati. */
    private fun shToken(s: String, from: Int): Pair<Int, String> {
        var i = from
        val sb = StringBuilder()
        var quote = '\u0000'
        while (i < s.length) {
            val c = s[i]
            when {
                quote != '\u0000' -> when {
                    c == quote -> quote = '\u0000'
                    c == '\\' && quote == '"' && i + 1 < s.length -> sb.append(s[++i])
                    else -> sb.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c == '\\' && i + 1 < s.length -> sb.append(s[++i])
                c.isWhitespace() -> return i to sb.toString()
                else -> sb.append(c)
            }
            i++
        }
        return i to sb.toString()
    }

    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

    /**
     * Memisahkan awalan variabel lingkungan dari baris Exec yang sudah dibersihkan: `env A=1 B="x y" prog arg` atau
     * `A=1 prog arg` -> ("A=1\nB=x y", "prog arg"). Awalan hanya dipisah bila setelahnya masih ada perintah dan
     * semua token awalnya berbentuk KEY=VALUE polos; `env` dengan opsi (`-i`, `-u`, dst) dibiarkan utuh di perintah.
     */
    fun splitEnvPrefix(cmd: String): Pair<String, String> {
        var j = 0
        fun skipWs() { while (j < cmd.length && cmd[j].isWhitespace()) j++ }
        skipWs()
        val (e0, t0) = shToken(cmd, j)
        if (t0 == "env") j = e0
        val lines = ArrayList<String>()
        while (true) {
            skipWs()
            if (j >= cmd.length) break
            val (e, t) = shToken(cmd, j)
            if (!ENV_ASSIGN.containsMatchIn(cmd.substring(j, e))) break
            lines += t
            j = e
        }
        skipWs()
        if (lines.isEmpty() || j >= cmd.length) return "" to cmd
        return lines.joinToString("\n") to cmd.substring(j)
    }

    /** Nama, perintah, variabel lingkungan, dan direktori kerja dari berkas .desktop (Name, Exec, Path). */
    fun desktopDefaults(app: DesktopApps.App): DesktopDefaults {
        val (env, command) = splitEnvPrefix(cleanExec(app.exec))
        // AppImage: tanpa direktori kerja bawaan (lihat script()); Path= lama menunjuk ke folder hasil ekstraksi
        val workDir = if (AppImageInstaller.isAppImageShortcut(app.id)) "" else app.path
        return DesktopDefaults(app.name, command, env, workDir)
    }

    /** Perintah yang dijalankan: isian pengguna bila ada, kalau tidak Exec dari .desktop (tanpa awalan env). */
    fun effectiveCommand(app: DesktopApps.App, s: ShortcutPrefs.Settings): String =
        cleanExec(s.command).ifEmpty { splitEnvPrefix(cleanExec(app.exec)).second }

    /** Variabel lingkungan yang diekspor: isian pengguna bila ada, kalau tidak awalan `env` di Exec .desktop. */
    fun effectiveEnv(app: DesktopApps.App, s: ShortcutPrefs.Settings): String =
        if (s.env.isNotBlank()) s.env else splitEnvPrefix(cleanExec(app.exec)).first

    /** Aplikasi Wine (shortcut .exe atau perintah `wine ...`)? Dipakai untuk memutuskan window manager sesi Shortcut. */
    fun isWineApp(appId: String): Boolean {
        val app = DesktopApps.find(appId) ?: return false
        val prog = effectiveCommand(app, ShortcutPrefs.get(appId)).substringBefore(' ').substringAfterLast('/')
        return prog == "wine" || prog == "wine64"
    }

    /** `K=V K=V` -> pasangan; token tanpa `=` dibuang. */
    private fun pairs(opts: String): List<Pair<String, String>> =
        opts.trim().split(Regex("\\s+")).mapNotNull { t ->
            val i = t.indexOf('=')
            if (i > 0) t.substring(0, i) to t.substring(i + 1) else null
        }

    private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Isi skrip peluncur untuk satu aplikasi dengan pengaturannya. */
    private fun script(ctx: Context, app: DesktopApps.App, s: ShortcutPrefs.Settings, id: String): String {
        val sb = StringBuilder()
        sb.append("#!/bin/sh\n")
        sb.append("# Ditulis ulang oleh menu Shortcut Tiny Computer setiap kali aplikasi dijalankan.\n")
        // proot terpisah tidak punya terminal: keluaran aplikasi masuk ke berkas log
        sb.append("mkdir -p \"\$HOME/.vnc\"\n")
        // Log peluncuran aktif (TINY_LAUNCH_LOG diisi Launcher): keluaran aplikasi juga ditulis ke berkas sesi di
        // Download/Wayland-logs lewat tee + FIFO. ~/.vnc/shortcut-<id>.log tetap ditulis (dipakai pesan galat start).
        sb.append(
            """
if [ -n "${'$'}TINY_LAUNCH_LOG" ] && [ -w "${'$'}TINY_LAUNCH_LOG" ] && command -v mkfifo >/dev/null 2>&1 && command -v tee >/dev/null 2>&1; then
  TINY_FIFO="${'$'}HOME/.vnc/.shortcut-$id.fifo"
  rm -f "${'$'}TINY_FIFO"
  if mkfifo "${'$'}TINY_FIFO" 2>/dev/null; then
    tee "${'$'}HOME/.vnc/shortcut-$id.log" < "${'$'}TINY_FIFO" >> "${'$'}TINY_LAUNCH_LOG" &
    exec > "${'$'}TINY_FIFO" 2>&1
  else
    exec > "${'$'}HOME/.vnc/shortcut-$id.log" 2>&1
  fi
  rm -f "${'$'}TINY_FIFO"
  export TINY_LAUNCH_TEE=1
else
  exec > "${'$'}HOME/.vnc/shortcut-$id.log" 2>&1
fi
""".trimStart(),
        )
        sb.append("export DISPLAY=:4\n")
        // sesi X (Xwayland milik desktop atau milik sesi aplikasi) bisa baru menyala: tunggu soketnya
        sb.append("i=0; while [ \$i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=\$((i+1)); done\n")

        // Aplikasi Wine? (dipakai untuk memilih jalur Wayland di bawah)
        val prog = effectiveCommand(app, s).substringBefore(' ').substringAfterLast('/')
        val isWine = prog == "wine" || prog == "wine64"
        // Window manager sesi Shortcut (xfwm4 dst.) bawaannya menyala untuk aplikasi Linux. Aplikasi Wine butuh layar penuh
        // (WM memberi bingkai dan menahan jendela game), jadi aplikasi Wine mematikan WM dan menulis ~/.vnc/no-wm; penjaga
        // di skrip sesi ([prepareSession]) menjeda WM selama berkas itu ada. Aplikasi Linux menghapus berkas itu sehingga WM
        // menyala lagi, kecuali masih ada Wine yang berjalan (WM tidak boleh mengambil alih jendela game).
        // Desktop yang menyala punya WM sendiri (xfwm4 desktop): tidak disentuh.
        if (!Launcher.guiStarted) {
            if (isWine) {
                sb.append(": > \"\$HOME/.vnc/no-wm\"\n")
                sb.append("for w in xfwm4 openbox metacity marco icewm fluxbox; do pkill -x \$w 2>/dev/null; done\n")
            } else {
                sb.append("pgrep -x wineserver >/dev/null 2>&1 || rm -f \"\$HOME/.vnc/no-wm\"\n")
            }
        }
        // Turnip aktif bila dipilih untuk aplikasi ini, atau (GPU "default") bila Turnip global menyala
        val turnipOn = when (s.gpu) {
            ShortcutPrefs.GPU_TURNIP -> true
            ShortcutPrefs.GPU_SOFTWARE -> false
            else -> Cfg.turnip
        }

        // Bawaan: semua aplikasi Shortcut (Linux dan .exe Wine) mendapat XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0
        // seperti Open desktop (Cfg.desktopWayland, bawaan aktif). Hanya dua variabel itu; pengaturan GPU dan env
        // buatan pengguna di bawah tetap bisa menimpanya.
        if (Cfg.desktopWayland) {
            sb.append("if [ -S /tmp/wayland-0 ]; then export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0; fi\n")
        }
        // grafis khusus aplikasi ini
        when (s.gpu) {
            ShortcutPrefs.GPU_SOFTWARE -> {
                sb.append("unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES MESA_VK_WSI_DEBUG LIBGL_KOPPER_DRI2 GALLIUM_DRIVER\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=1\n")
            }
            ShortcutPrefs.GPU_TURNIP -> {
                for ((k, v) in pairs(Launcher.turnipOpt(ctx))) sb.append("export $k=${q(v)}\n")
                // lihat alasan di Launcher.guestSpec: Xwayland tanpa DRI3
                // LIBGL_KOPPER_DRI2 selalu: EGL di atas X11 butuh ini agar tidak jatuh ke llvmpipe saat DRI3Open ditolak
                sb.append("export LIBGL_KOPPER_DRI2=true\n")
                if (!Cfg.dri3) sb.append("export MESA_VK_WSI_DEBUG=sw\n")
            }
        }
        // Program Vulkan/SDL memakai WSI Wayland langsung ke compositor, bukan X11 lewat Xwayland (tanpa DRI3):
        // itu jalur yang terbukti jalan (vkcube --wsi wayland dengan Turnip). Berlaku untuk GPU Turnip yang dipilih
        // per aplikasi DAN untuk GPU "default" saat Turnip global menyala (dulu hanya yang pertama, sehingga aplikasi
        // dengan GPU default tetap lewat X11 dan Vulkan-nya gagal). Variabel ini hanya untuk proses aplikasi ini, tidak
        // diekspor ke seluruh guest (GTK/Qt/Firefox tetap X11 lewat GDK_BACKEND/QT_QPA_PLATFORM). vkcube/vkcubepp
        // mendapat `--wsi wayland` dari pembungkus [WsiWayland]. SDL_VIDEODRIVER tidak diatur untuk program .exe (Wine).
        if (turnipOn) {
            val sdl = if (isWine) "" else " SDL_VIDEODRIVER=wayland"
            sb.append("if [ -S /tmp/wayland-0 ]; then export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0$sdl; fi\n")
        }
        // HiDPI khusus aplikasi ini
        when (s.hidpi) {
            ShortcutPrefs.ON -> for ((k, v) in pairs(Cfg.defaultHidpiOpt)) sb.append("export $k=${q(v)}\n")
            ShortcutPrefs.OFF -> sb.append("unset GDK_SCALE QT_FONT_DPI\n")
        }
        // variabel lingkungan buatan pengguna
        for (line in effectiveEnv(app, s).lineSequence()) {
            val t = line.trim()
            val i = t.indexOf('=')
            if (i <= 0) continue
            val key = t.substring(0, i).trim()
            if (!ENV_KEY.matches(key)) continue
            sb.append("export $key=${q(t.substring(i + 1))}\n")
        }

        // direktori kerja: pengaturan > Path= di .desktop > rumah
        // AppImage: bawaannya rumah, seperti AppImage yang dibuka dari pengelola berkas. Dulu Path= menunjuk ke folder
        // hasil ekstraksi (`app/`): aplikasi yang menulis konfigurasi/cache relatif terhadap direktori kerja menulis ke
        // dalam folder aplikasi (yang bisa berizin baca-saja), gagal diam-diam, dan pengaturannya hilang.
        val appImage = AppImageInstaller.isAppImageShortcut(app.id)
        val dir = s.workDir.trim().ifEmpty { if (appImage) "" else app.path }
        if (dir.isNotEmpty()) sb.append("cd ${q(dir)} 2>/dev/null || cd \"\$HOME\"\n") else sb.append("cd \"\$HOME\"\n")
        if (appImage) {
            // tercatat di log shortcut: tempat konfigurasi aplikasi ini dan apakah bisa ditulis
            sb.append("mkdir -p \"\$HOME/.config\" \"\$HOME/.local/share\" \"\$HOME/.cache\" 2>/dev/null\n")
            sb.append("echo \"[tiny] AppImage: HOME=\$HOME cwd=\$(pwd) ~/.config bisa ditulis: \$([ -w \"\$HOME/.config\" ] && echo ya || echo TIDAK)\"\n")
            // dialog berkas (Import shader/mod dst.) memanggil zenity/kdialog: catat apa yang tersedia untuk diagnosis
            sb.append("echo \"[tiny] dialog berkas: zenity=\$(command -v zenity || echo TIDAK ADA) kdialog=\$(command -v kdialog || echo TIDAK ADA) DISPLAY=\$DISPLAY WAYLAND_DISPLAY=\$WAYLAND_DISPLAY GDK_BACKEND=\$GDK_BACKEND SDL_VIDEODRIVER=\$SDL_VIDEODRIVER\"\n")
        }

        // bus sesi D-Bus: pakai milik desktop bila sedang berjalan (cari di /proc), kalau tidak, buat sendiri
        sb.append(
            """
if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
  n=0
  for e in /proc/[0-9]*/environ; do
    n=${'$'}((n+1)); [ ${'$'}n -gt 80 ] && break
    v=${'$'}(grep -a -m1 -o 'DBUS_SESSION_BUS_ADDRESS=[^[:cntrl:]]*' "${'$'}e" 2>/dev/null)
    [ -n "${'$'}v" ] && { export "${'$'}v"; break; }
  done
fi
""".trimStart(),
        )

        // tanda driver Vulkan: 20 baris pertama ringkasan vulkaninfo masuk ke log shortcut (lihat WsiWayland)
        if (turnipOn) sb.append(WsiWayland.probeForScript())

        var cmd = effectiveCommand(app, s)
        if (s.args.isNotBlank()) cmd += " " + s.args.trim()
        // Aplikasi Flathub (`flatpak run ...`): Wayland dulu, X11/Xwayland hanya cadangan, plus Turnip bila aktif.
        // `flatpak run` membersihkan lingkungan, jadi ini harus berupa opsi di baris perintah (lihat FlatpakWayland).
        FlatpakWayland.rewrite(ctx, cmd, FlatpakWayland.mode(effectiveEnv(app, s)), turnipOn)?.let { r ->
            cmd = r.command
            sb.append("echo ${q(r.note)}\n")
        }
        if (app.terminal) cmd = "x-terminal-emulator -e sh -c ${q("exec $cmd")}"
        sb.append("TINY_CMD=${q(cmd)}\n")
        sb.append(
            """
if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
  exec dbus-launch --exit-with-session sh -c "${'$'}TINY_CMD"
fi
exec sh -c "${'$'}TINY_CMD"
""".trimStart(),
        )
        return sb.toString()
    }

    /**
     * Menulis skrip peluncur untuk [appId] dan mengembalikan path-nya DI DALAM guest (untuk
     * `/bin/sh <path>` di proot baru), atau null bila aplikasinya sudah tidak ada. Log ada di
     * `~/.vnc/shortcut-<id>.log`.
     */
    fun prepare(ctx: Context, appId: String): String? {
        val app = DesktopApps.find(appId)
        if (app == null) {
            Log.w(TAG, "aplikasi $appId tidak ditemukan")
            return null
        }
        WsiWayland.install() // pembungkus vkcube/vkcubepp: --wsi wayland
        val id = safe(appId)
        write("run-$id.sh", script(ctx, app, ShortcutPrefs.get(appId), id))
        return "$GUEST_DIR/run-$id.sh"
    }

    /**
     * Menulis skrip sesi yang menyalakan Xwayland (+ window manager ringan bila ada, diawasi dan dicoba bergantian) tanpa sesi desktop
     * dan mengembalikan path-nya di dalam guest. Ukuran layar = [size] (ukuran khusus aplikasi yang
     * memulai sesi, atau ukuran desktop; lihat [Launcher.displaySize]). Skrip menunggu soket Wayland, lalu MENJADI Xwayland (`exec`): proot-nya
     * hidup selama X hidup dan, karena `--kill-on-exit`, window manager ikut berhenti bersamanya.
     * [withWm] false (sesi dimulai aplikasi Wine): window manager dijeda supaya game bisa layar penuh; aplikasi Linux
     * yang dijalankan kemudian menyalakannya lagi.
     */
    fun prepareSession(ctx: Context, size: Pair<Int, Int>, withWm: Boolean = true): String {
        val (w, h) = size
        // berkas penanda: penjaga window manager di skrip sesi tidak menyalakannya (aplikasi Wine juga membuatnya sendiri)
        val noWm = if (withWm) "" else ": > \"${'$'}HOME/.vnc/no-wm\"\n"
        write(
            SESSION_SCRIPT,
            """
#!/bin/sh
mkdir -p "${'$'}HOME/.vnc"
command -v Xwayland >/dev/null 2>&1 || { echo "Xwayland belum terpasang"; exit 1; }
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/wayland-0 ]; do sleep 0.2; i=${'$'}((i+1)); done
rm -f "${'$'}HOME/.vnc/no-wm"
${noWm}# window manager (title bar + tombol tutup/maksimalkan/kecilkan) untuk aplikasi Shortcut.
# Berkas ~/.vnc/no-wm (dibuat aplikasi Wine) = window manager dijeda; aplikasi Linux menghapusnya agar WM menyala lagi.
# - berjalan di bus D-Bus sendiri: xfwm4 butuh xfconf lewat D-Bus, tanpa itu langsung mati dan jendela
#   tampil tanpa bingkai;
# - tiap kandidat diperiksa masih hidup setelah 2 detik, kalau tidak lanjut ke kandidat berikutnya;
# - bila WM yang sudah berjalan berhenti (crash), dinyalakan lagi (maks 20 kali).
(
  i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
  export DISPLAY=:4
  WMLOG="${'$'}HOME/.vnc/wm.log"
  : > "${'$'}WMLOG"
  tries=0
  idle=0
  while [ ${'$'}tries -lt 20 ]; do
    if [ -e "${'$'}HOME/.vnc/no-wm" ]; then
      [ "${'$'}idle" = 1 ] || echo "[wm] dijeda: aplikasi Wine butuh layar penuh tanpa window manager" >> "${'$'}WMLOG"
      idle=1; tries=0; sleep 1; continue
    fi
    [ "${'$'}idle" = 1 ] && echo "[wm] dilanjutkan" >> "${'$'}WMLOG"
    idle=0
    tries=${'$'}((tries+1))
    started=0
    for wm in xfwm4 openbox metacity marco icewm fluxbox; do
      command -v ${'$'}wm >/dev/null 2>&1 || continue
      args=
      [ "${'$'}wm" = xfwm4 ] && args=--compositor=off
      echo "[wm] mulai ${'$'}wm" >> "${'$'}WMLOG"
      if command -v dbus-launch >/dev/null 2>&1; then
        dbus-launch --exit-with-session ${'$'}wm ${'$'}args >> "${'$'}WMLOG" 2>&1 &
      else
        ${'$'}wm ${'$'}args >> "${'$'}WMLOG" 2>&1 &
      fi
      pid=${'$'}!
      sleep 2
      if kill -0 ${'$'}pid 2>/dev/null; then
        started=1
        wait ${'$'}pid
        echo "[wm] ${'$'}wm berhenti" >> "${'$'}WMLOG"
        break
      fi
      echo "[wm] ${'$'}wm langsung berhenti, coba berikutnya" >> "${'$'}WMLOG"
    done
    [ ${'$'}started = 1 ] || break
    sleep 1
  done
) &
# Paksa mode layar = ${w}x$h bila xrandr ada (Xwayland rootful bisa menyala dengan mode lain); hasilnya dicatat.
(
  i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
  if command -v xrandr >/dev/null 2>&1; then
    export DISPLAY=:4
    sleep 1
    out=${'$'}(xrandr 2>/dev/null | awk '/ connected/{print ${'$'}1; exit}')
    [ -n "${'$'}out" ] && xrandr --output "${'$'}out" --mode ${w}x$h >> "${'$'}HOME/.vnc/xrandr.log" 2>&1
    xrandr --current >> "${'$'}HOME/.vnc/xrandr.log" 2>&1
  fi
) &
XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 ${Launcher.xwaylandEnv()}exec ${Launcher.xwaylandBin()} :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx -extension MIT-SHM > "${'$'}HOME/.vnc/xwayland.log" 2>&1
""".trimStart(),
        )
        return "$GUEST_DIR/$SESSION_SCRIPT"
    }
}
