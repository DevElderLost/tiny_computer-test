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
        sb.append("exec > \"\$HOME/.vnc/shortcut-$id.log\" 2>&1\n")
        sb.append("export DISPLAY=:4\n")
        // sesi X (Xwayland milik desktop atau milik sesi aplikasi) bisa baru menyala: tunggu soketnya
        sb.append("i=0; while [ \$i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=\$((i+1)); done\n")

        // grafis khusus aplikasi ini
        when (s.gpu) {
            ShortcutPrefs.GPU_SOFTWARE -> {
                sb.append("unset MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES MESA_VK_WSI_DEBUG GALLIUM_DRIVER\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=1\n")
            }
            ShortcutPrefs.GPU_TURNIP -> {
                for ((k, v) in pairs(Launcher.turnipOpt(ctx))) sb.append("export $k=${q(v)}\n")
                if (!Cfg.dri3) sb.append("export MESA_VK_WSI_DEBUG=sw\n")
            }
        }
        // HiDPI khusus aplikasi ini
        when (s.hidpi) {
            ShortcutPrefs.ON -> for ((k, v) in pairs(Cfg.defaultHidpiOpt)) sb.append("export $k=${q(v)}\n")
            ShortcutPrefs.OFF -> sb.append("unset GDK_SCALE QT_FONT_DPI\n")
        }
        // variabel lingkungan buatan pengguna
        for (line in s.env.lineSequence()) {
            val t = line.trim()
            val i = t.indexOf('=')
            if (i <= 0) continue
            val key = t.substring(0, i).trim()
            if (!ENV_KEY.matches(key)) continue
            sb.append("export $key=${q(t.substring(i + 1))}\n")
        }

        // direktori kerja: pengaturan > Path= di .desktop > rumah
        val dir = s.workDir.trim().ifEmpty { app.path }
        if (dir.isNotEmpty()) sb.append("cd ${q(dir)} 2>/dev/null || cd \"\$HOME\"\n") else sb.append("cd \"\$HOME\"\n")

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

        var cmd = cleanExec(app.exec)
        if (s.args.isNotBlank()) cmd += " " + s.args.trim()
        if (app.terminal) cmd = "x-terminal-emulator -e sh -c ${q("exec $cmd")}"
        // Wine: matikan XRandR di driver X11. Xwayland menawarkan daftar mode "emulasi" sampai 5K/8K, sehingga
        // Wine melaporkan mode saat ini jauh melebihi layar (mis. 5120x2880 pada layar 960x544) dan game
        // membuka jendela sebesar itu. Dengan UseXRandR=N Wine hanya melihat satu mode = ukuran layar X
        // sesi ini (-geometry dari prepareSession). Idempoten: hanya menulis bila belum ada di prefix.
        if (Regex("^(\\S*/)?wine(64)?(\\s|$)").containsMatchIn(cmd)) {
            sb.append(
                """
WPFX="${'$'}{WINEPREFIX:-${'$'}HOME/.wine}"
if ! grep -qs '"UseXRandR"="N"' "${'$'}WPFX/user.reg" 2>/dev/null; then
  echo "[tiny] Wine: UseXRandR=N (ukuran layar mengikuti sesi X)"
  wine reg add 'HKCU\Software\Wine\X11 Driver' /v UseXRandR /t REG_SZ /d N /f
  wineserver -w 2>/dev/null
fi
""".trimStart(),
            )
        }
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
        val id = safe(appId)
        write("run-$id.sh", script(ctx, app, ShortcutPrefs.get(appId), id))
        return "$GUEST_DIR/run-$id.sh"
    }

    /**
     * Menulis skrip sesi yang menyalakan Xwayland (+ window manager ringan bila ada, diawasi dan dicoba bergantian) tanpa sesi desktop
     * dan mengembalikan path-nya di dalam guest. Ukuran layar = [size] (ukuran khusus aplikasi yang
     * memulai sesi, atau ukuran desktop; lihat [Launcher.displaySize]). Skrip menunggu soket Wayland, lalu MENJADI Xwayland (`exec`): proot-nya
     * hidup selama X hidup dan, karena `--kill-on-exit`, window manager ikut berhenti bersamanya.
     */
    fun prepareSession(ctx: Context, size: Pair<Int, Int>): String {
        val (w, h) = size
        write(
            SESSION_SCRIPT,
            """
#!/bin/sh
mkdir -p "${'$'}HOME/.vnc"
command -v Xwayland >/dev/null 2>&1 || { echo "Xwayland belum terpasang"; exit 1; }
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/wayland-0 ]; do sleep 0.2; i=${'$'}((i+1)); done
# window manager (title bar + tombol tutup/maksimalkan/kecilkan) untuk aplikasi Shortcut.
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
  while [ ${'$'}tries -lt 20 ]; do
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
XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 exec Xwayland :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx -extension MIT-SHM > "${'$'}HOME/.vnc/xwayland.log" 2>&1
""".trimStart(),
        )
        return "$GUEST_DIR/$SESSION_SCRIPT"
    }
}
