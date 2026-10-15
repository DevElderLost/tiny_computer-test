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
        val prog = cleanExec(app.exec).substringBefore(' ').substringAfterLast('/')
        val isWine = prog == "wine" || prog == "wine64"
        // Driver tampilan Wine untuk peluncuran ini (lihat WineContents.x11Preferred): env.sh membacanya
        if (isWine) sb.append("export TINY_WINE_DISPLAY=${if (WineContents.displayWayland()) "wayland" else "x11"}\n")
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
        // mendapat `--wsi wayland` dari pembungkus [WsiWayland]. Wine: shim `wine` (env.sh) memilih driver
        // winewayland.drv dengan X11 sebagai cadangan; SDL_VIDEODRIVER tidak diatur untuk Wine (dipakai winebus).
        if (turnipOn) {
            val sdl = if (isWine) "" else " SDL_VIDEODRIVER=wayland"
            sb.append("if [ -S /tmp/wayland-0 ]; then export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0$sdl; fi\n")
            // WSI Wayland memakai dmabuf langsung; jalur WSI software (MESA_VK_WSI_DEBUG=sw) hanya untuk X11 tanpa DRI3
            // dan akan memaksa Vulkan/DXVK Wine menyalin lewat shm.
            if (isWine && WineContents.displayWayland())
                sb.append("if [ -S /tmp/wayland-0 ]; then unset MESA_VK_WSI_DEBUG; fi\n")
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

        // tanda driver Vulkan: 20 baris pertama ringkasan vulkaninfo masuk ke log shortcut (lihat WsiWayland)
        if (turnipOn) sb.append(WsiWayland.probeForScript())

        // Wine harus memakai winewayland.drv. Jika registri prefix masih "x11" (atau kosong), winevulkan membuat surface
        // lewat Xwayland tanpa DRI3: DXVK menemukan Turnip, device jadi, lalu presentasi macet (loader_section timeout)
        // dan layar hitam. Nilai registri sebelumnya hanya ditulis oleh Prepare / saat kontainer desktop menyala, jadi
        // Shortcut yang berjalan tanpa desktop tidak pernah memperbaikinya. Dicek tiap peluncuran (murah: satu grep);
        // `wine reg add` hanya dijalankan bila nilainya belum benar. Tanpa `wineserver -w`: bila wineserver prefix ini
        // sedang dipakai aplikasi lain, perintah itu akan menunggu sampai aplikasinya keluar.
        if (isWine && WineContents.displayWayland()) {
            // cabang .wcp: $TINY_WINE_ROOT/<lib>/...; cabang Wine sistem: /usr/lib/wine/...
            val soPath = WineContents.waylandSoShellPath()
            sb.append(
                """
if [ -S /tmp/wayland-0 ]; then
  (
    [ -f "${'$'}HOME/.local/share/tiny/wine/env.sh" ] && . "${'$'}HOME/.local/share/tiny/wine/env.sh"
    unset HODLL
    if ! grep -qs '^"Graphics"="wayland,x11"' "${'$'}WINEPREFIX/user.reg"; then
      echo "[tiny] Wine: driver tampilan di registri bukan wayland; ditulis ulang ke wayland,x11"
      wine reg add 'HKCU\Software\Wine\Drivers' /v Graphics /d wayland,x11 /f
    fi
    # Diagnosis: winewayland.drv yang dimuat lalu langsung dibuang berarti init Wayland gagal (pustaka .so tidak
    # termuat atau wl_display_connect gagal). Hasilnya dicatat di log supaya penyebabnya terlihat.
    echo "[tiny] wayland diag: XDG_RUNTIME_DIR=${'$'}XDG_RUNTIME_DIR WAYLAND_DISPLAY=${'$'}WAYLAND_DISPLAY DISPLAY=${'$'}DISPLAY"
    ls -l "${'$'}XDG_RUNTIME_DIR/${'$'}WAYLAND_DISPLAY" 2>&1 | sed 's/^/[tiny] wayland diag: socket /'
    so="$soPath"
    if [ -f "${'$'}so" ]; then
      if command -v ldd >/dev/null 2>&1; then
        miss=${'$'}(ldd "${'$'}so" 2>&1 | grep -i 'not found')
        if [ -n "${'$'}miss" ]; then echo "[tiny] wayland diag: winewayland.so kekurangan pustaka: ${'$'}miss"
        else echo "[tiny] wayland diag: winewayland.so, semua pustaka ada"; fi
      fi
    else
      echo "[tiny] wayland diag: ${'$'}so tidak ada"
    fi
  )
else
  echo "[tiny] Wine: soket /tmp/wayland-0 tidak ada; Wine memakai X11 (Vulkan/DXVK lewat Xwayland tanpa DRI3 bisa hang)"
fi
""".trimStart(),
            )
        } else if (isWine && WineContents.waylandCapable()) {
            // winewayland tersedia tetapi jalur grafisnya OpenGL (WineD3D): paksa X11 di registri, kalau
            // tidak Wine tetap memilih winewayland (daftar "wayland,x11") dan OpenGL jatuh ke llvmpipe.
            sb.append(
                """
(
  [ -f "${'$'}HOME/.local/share/tiny/wine/env.sh" ] && . "${'$'}HOME/.local/share/tiny/wine/env.sh"
  unset HODLL
  if ! grep -qs '^"Graphics"="x11"' "${'$'}WINEPREFIX/user.reg"; then
    echo "[tiny] Wine: driver tampilan di registri ditulis ulang ke x11"
    wine reg add 'HKCU\Software\Wine\Drivers' /v Graphics /d x11 /f
  fi
)
echo "[tiny] Wine: memakai X11 (Xwayland) + Zink/Turnip untuk OpenGL WineD3D; OpenGL lewat Wayland jatuh ke llvmpipe"
""".trimStart(),
            )
        } else if (isWine && Cfg.wineDisplay != "x11") {
            sb.append("echo \"[tiny] Wine: build Wine aktif tidak punya winewayland.so; memakai X11 (DXVK/Vulkan bisa hang tanpa DRI3)\"\n")
        }

        // Diagnosis DXVK: jenis DLL yang benar-benar ada di system32 prefix (arm64ec = benar; x64 = salah untuk prefix arm64ec).
        if (isWine && D3dMode.isDxvk()) {
            val sys32 = File(WineContents.prefixDir(), "drive_c/windows/system32")
            val kinds = listOf("dxgi", "d3d11", "d3d9").joinToString(", ") { n ->
                n + "=" + PeArch.kind(File(sys32, "$n.dll")).name.lowercase()
            }
            sb.append("echo \"[tiny] dxvk diag: system32 $kinds\"\n")
        }

        var cmd = cleanExec(app.exec)
        if (s.args.isNotBlank()) cmd += " " + s.args.trim()
        if (app.terminal) cmd = "x-terminal-emulator -e sh -c ${q("exec $cmd")}"
        // Wine: jalankan di dalam virtual desktop seukuran layar X sesi. Xwayland rootful menawarkan mode XRandR
        // "emulasi" sampai 5120x2880 (mode pilihannya malah yang terbesar), dan Wine versi baru mengabaikan
        // UseXRandR=N, sehingga game memilih 5K. Di virtual desktop Wine tidak memakai XRandR: game hanya
        // melihat satu mode = ukuran desktop ini. Penutup shell explorer tidak digambar oleh compositor.
        // Nonaktifkan dengan variabel lingkungan TINY_WINE_VDESKTOP=0 (Pengaturan shortcut > Env).
        val wineRe = Regex("^((?:\\S*/)?wine(?:64)?)(\\s.*)?$")
        val wm = wineRe.matchEntire(cmd)
        if (wm != null && !Regex("^\\s*explorer(\\s|$)").containsMatchIn(wm.groupValues[2]) && !app.terminal) {
            val (vw, vh) = Launcher.sessionSize(ctx, app.id)
            val rest = wm.groupValues[2]
            val vd = "${wm.groupValues[1]} explorer /desktop=tiny,${vw}x$vh$rest"
            // Di driver Wayland tidak ada XRandR 5K yang perlu dihindari, dan explorer /desktop tidak cocok dengan
            // winewayland.drv (jendela game bukan toplevel Wayland sendiri). Bawaan: tanpa virtual desktop bila Wayland
            // aktif; TINY_WINE_VDESKTOP=1 memaksanya menyala.
            if (WineContents.displayWayland()) sb.append("TINY_VD_DEF=1; [ -S /tmp/wayland-0 ] && TINY_VD_DEF=0\n")
            else sb.append("TINY_VD_DEF=1\n")
            sb.append("echo \"[tiny] Wine virtual desktop ${vw}x$vh (bawaan: \$TINY_VD_DEF, 1 = nyala)\"\n")
            sb.append("if [ \"\${TINY_WINE_VDESKTOP:-\$TINY_VD_DEF}\" = 0 ]; then TINY_CMD=${q(cmd)}; else TINY_CMD=${q(vd)}; fi\n")
        } else {
            sb.append("TINY_CMD=${q(cmd)}\n")
        }
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
XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 ${Launcher.xwaylandEnv()}exec ${Launcher.xwaylandBin()} :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx -extension MIT-SHM > "${'$'}HOME/.vnc/xwayland.log" 2>&1
""".trimStart(),
        )
        return "$GUEST_DIR/$SESSION_SCRIPT"
    }
}
