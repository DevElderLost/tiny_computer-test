package com.example.tiny_computer.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Menjalankan aplikasi dari menu Shortcut TANPA membuka desktop, seperti DroidDeck menjalankan satu
 * program penuh layar (di DroidDeck lewat gamescope; di sini lewat Xwayland + window manager ringan
 * di atas compositor yang sama). Skrip peluncur ditulis ke `$DATA_DIR/tiny/shortcuts`, yang di dalam
 * kontainer terlihat sebagai `~/.local/share/tiny/shortcuts`, lalu cukup satu baris `sh <skrip>`
 * yang dikirim ke terminal kontainer. Dengan begitu tidak ada masalah kutip/escape di PTY, dan
 * pengaturan per aplikasi ([ShortcutPrefs]) hanya hidup di skrip itu, tidak bocor ke desktop.
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
    private fun script(ctx: Context, app: DesktopApps.App, s: ShortcutPrefs.Settings): String {
        val sb = StringBuilder()
        sb.append("#!/bin/sh\n")
        sb.append("# Ditulis ulang oleh menu Shortcut Tiny Computer setiap kali aplikasi dijalankan.\n")
        sb.append("export DISPLAY=:4\n")

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
     * Menulis skrip peluncur untuk [appId] dan mengembalikan baris perintah untuk terminal kontainer,
     * atau null bila aplikasinya sudah tidak ada. Berjalan di latar belakang kontainer dengan log di
     * `~/.vnc/shortcut-<id>.log`.
     */
    fun prepare(ctx: Context, appId: String): String? {
        val app = DesktopApps.find(appId)
        if (app == null) {
            Log.w(TAG, "aplikasi $appId tidak ditemukan")
            return null
        }
        val id = safe(appId)
        write("run-$id.sh", script(ctx, app, ShortcutPrefs.get(appId)))
        return "mkdir -p \"\$HOME/.vnc\"; (setsid sh $GUEST_DIR/run-$id.sh > \"\$HOME/.vnc/shortcut-$id.log\" 2>&1 &)"
    }

    /**
     * Perintah yang menyalakan Xwayland (+ window manager ringan bila ada) tanpa sesi desktop.
     * Ukuran layar sama dengan desktop ([Launcher.displaySize]). Skrip menunggu soket Wayland dan X
     * sehingga perintah berikutnya (peluncur aplikasi) baru jalan setelah X siap.
     */
    fun sessionCommand(ctx: Context): String {
        val (w, h) = Launcher.displaySize(ctx)
        write(
            SESSION_SCRIPT,
            """
#!/bin/sh
mkdir -p "${'$'}HOME/.vnc"
command -v Xwayland >/dev/null 2>&1 || { echo "Xwayland belum terpasang"; exit 1; }
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/wayland-0 ]; do sleep 0.2; i=${'$'}((i+1)); done
(XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 Xwayland :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx > "${'$'}HOME/.vnc/xwayland.log" 2>&1 &)
i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
for wm in xfwm4 openbox metacity marco icewm fluxbox; do
  if command -v ${'$'}wm >/dev/null 2>&1; then
    if [ "${'$'}wm" = xfwm4 ]; then args=--compositor=off; else args=; fi
    (DISPLAY=:4 ${'$'}wm ${'$'}args > "${'$'}HOME/.vnc/wm.log" 2>&1 &)
    break
  fi
done
""".trimStart(),
        )
        return "sh $GUEST_DIR/$SESSION_SCRIPT"
    }
}
