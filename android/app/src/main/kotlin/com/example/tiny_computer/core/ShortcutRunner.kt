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

    /**
     * Sound mode khusus aplikasi: PulseAudio atau ALSA langsung ke suara Android (plugin tinyaudio -> AAudio), apa pun mode
     * global Direct audio. ALSA di guest mengikuti /etc/asound.conf dan ~/.asoundrc yang dibaca lewat alsa.conf sistem; skrip
     * membuat salinan alsa.conf yang mengalihkan dua berkas itu ke konfigurasi mode ini lalu menunjuknya dengan ALSA_CONFIG_PATH.
     * Hanya proses aplikasi ini yang terpengaruh (asound.conf global dan aplikasi lain tidak disentuh). Bila alsa.conf sistem tidak
     * bisa dialihkan, hanya variabel lingkungan (SDL dan Pulse) yang dipasang.
     */
    private fun soundEnv(mode: String): String {
        val direct = when (mode) {
            ShortcutPrefs.SOUND_ALSA -> true
            ShortcutPrefs.SOUND_PULSE -> false
            else -> return ""
        }
        if (direct && !Launcher.directAudioAvailable()) return ""
        val name = if (direct) "direct" else "pulse"
        val conf = if (direct) Launcher.asoundConf() else "pcm.!default {\n    type pulse\n}\nctl.!default {\n    type pulse\n}\n"
        val env = if (direct) "export SDL_AUDIODRIVER=alsa SDL_AUDIO_DRIVER=alsa AUDIODRIVER=alsa\n"
        else "export PULSE_SERVER=tcp:127.0.0.1:${Cfg.defaultAudioPort} SDL_AUDIODRIVER=pulseaudio SDL_AUDIO_DRIVER=pulseaudio AUDIODRIVER=pulseaudio\n"
        return """
tiny_snd=/tmp/.tiny-alsa
mkdir -p "${'$'}tiny_snd" 2>/dev/null
cat > "${'$'}tiny_snd/$name.conf" <<'TINY_ALSA_EOF'
$conf
TINY_ALSA_EOF
tiny_base=""
for f in /usr/share/alsa/alsa.conf /usr/local/share/alsa/alsa.conf; do [ -r "${'$'}f" ] && { tiny_base="${'$'}f"; break; }; done
if [ -n "${'$'}tiny_base" ] && sed -e "s#\"/etc/asound.conf\"#\"${'$'}tiny_snd/$name.conf\"#" -e 's#"~/.asoundrc"#"/dev/null"#' "${'$'}tiny_base" > "${'$'}tiny_snd/alsa-$name.conf" && grep -q "${'$'}tiny_snd/$name.conf" "${'$'}tiny_snd/alsa-$name.conf"; then
  export ALSA_CONFIG_PATH="${'$'}tiny_snd/alsa-$name.conf"
  echo "[tiny] sound mode $name: ALSA_CONFIG_PATH=${'$'}ALSA_CONFIG_PATH"
else
  echo "[tiny] sound mode $name: alsa.conf sistem tidak bisa dialihkan; hanya variabel lingkungan yang dipakai"
fi
""".trimStart() + env
    }

    /** Berkas (di ~/.vnc guest) yang ditulis skrip aplikasi setelah desktop sesi Shortcut siap; dihapus Launcher sebelum peluncuran. */
    const val DESKTOP_READY_FILE = "shortcut-desktop-ready"

    private val DESKTOP_WAIT: String = """
i=0
while [ ${'$'}i -lt 200 ]; do
  if pgrep -x xfwm4 >/dev/null 2>&1 && { pgrep -x xfdesktop >/dev/null 2>&1 || pgrep -x xfce4-panel >/dev/null 2>&1; }; then break; fi
  [ -e "${'$'}HOME/.vnc/desktop-failed" ] && break
  sleep 0.2; i=${'$'}((i+1))
done
sleep 1
echo "[tiny] desktop sesi Shortcut: xfwm4=${'$'}(pgrep -x xfwm4 | head -1) panel=${'$'}(pgrep -x xfce4-panel | head -1) xfdesktop=${'$'}(pgrep -x xfdesktop | head -1) (${'$'}i x 0,2 dtk)"
: > "${'$'}HOME/.vnc/$DESKTOP_READY_FILE"
""".trimStart()

    /**
     * Sesi Shortcut dengan desktop XFCE penuh: aplikasi berjalan di proot TERPISAH yang tidak lewat Xsession, jadi tidak
     * mewarisi lingkungan sesi desktop (modul input method GTK/Qt/XIM, bus D-Bus sesi, XDG_*). Aplikasi yang dibuka dari
     * Open desktop mewarisinya. SDL milik Ren'Py mati dengan SIGSEGV tepat setelah inisialisasi keyboard (GetKeyboardMapping +
     * GetModifierMapping, sebelum CreateWindow) bila lingkungan itu tidak ada; di Open desktop jalan. Di sini daftar
     * variabel yang sama disalin dari proses sesi (xfce4-session, cadangan xfwm4) sebelum aplikasi dijalankan. Hanya
     * variabel yang ada di sesi yang disalin; variabel tampilan/GPU/SDL milik skrip ini dan env buatan pengguna di bawahnya
     * tetap menimpa karena diekspor sesudahnya.
     */
    private val DESKTOP_ENV_SYNC: String = """
tiny_sp="${'$'}(pgrep -x xfce4-session | head -1)"
[ -n "${'$'}tiny_sp" ] || tiny_sp="${'$'}(pgrep -x xfwm4 | head -1)"
if [ -n "${'$'}tiny_sp" ] && [ -r "/proc/${'$'}tiny_sp/environ" ]; then
  tiny_synced=""
  for k in DBUS_SESSION_BUS_ADDRESS XDG_CURRENT_DESKTOP DESKTOP_SESSION XDG_MENU_PREFIX XDG_DATA_DIRS XDG_CONFIG_DIRS GTK_IM_MODULE QT_IM_MODULE XMODIFIERS SDL_IM_MODULE SESSION_MANAGER; do
    v="${'$'}(tr '\0' '\n' < "/proc/${'$'}tiny_sp/environ" 2>/dev/null | sed -n "s/^${'$'}k=//p" | head -1)"
    [ -n "${'$'}v" ] || continue
    export "${'$'}k=${'$'}v"
    tiny_synced="${'$'}tiny_synced ${'$'}k"
  done
  echo "[tiny] env sesi desktop disalin dari pid ${'$'}tiny_sp:${'$'}{tiny_synced:- (tidak ada)}"
else
  echo "[tiny] env sesi desktop: proses sesi tidak ditemukan, env tidak disalin"
fi
# Variabel lain di sesi yang belum ada di proot aplikasi (mis. XDG_RUNTIME_DIR, LANG, XDG_SESSION_*) diisi juga. Yang sudah
  # ada tidak ditimpa, dan variabel tampilan/GPU/audio/preload milik skrip ini dilewati.
if [ -n "${'$'}tiny_sp" ] && [ -r "/proc/${'$'}tiny_sp/environ" ]; then
  tiny_filled=""
  tiny_ef="/tmp/.tiny-sess-env.${'$'}${'$'}"
  tr '\0' '\n' < "/proc/${'$'}tiny_sp/environ" 2>/dev/null | grep -E '^[A-Za-z_][A-Za-z0-9_]*=' > "${'$'}tiny_ef" 2>/dev/null
  while IFS= read -r line; do
    k="${'$'}{line%%=*}"; v="${'$'}{line#*=}"
    case "${'$'}k" in
      _|SHLVL|PWD|OLDPWD|TERM|HOME|USER|LOGNAME|SHELL|PATH|DISPLAY|WAYLAND_*|TINY_*|LD_*|SDL_*|PULSE_*|ALSA_*|GDK_*|QT_QPA_*|QT_XCB_*|QSG_*|MESA_*|VK_*|LIBGL_*|GALLIUM_*|XDG_VTNR|XDG_SESSION_ID|XDG_SEAT) continue ;;
    esac
    eval "tiny_isset=\${'$'}{${'$'}k+x}"
    [ -n "${'$'}tiny_isset" ] && continue
    export "${'$'}k=${'$'}v"
    tiny_filled="${'$'}tiny_filled ${'$'}k"
  done < "${'$'}tiny_ef"
  rm -f "${'$'}tiny_ef"
  echo "[tiny] env sesi desktop (isi yang kosong):${'$'}{tiny_filled:- (tidak ada)}"
fi
# Sesi X++ tidak punya compositor Wayland sehingga skrip ini tidak mengatur XDG_RUNTIME_DIR; di Open desktop nilainya /tmp.
[ -n "${'$'}XDG_RUNTIME_DIR" ] || export XDG_RUNTIME_DIR=/tmp
echo "[tiny] XDG_RUNTIME_DIR=${'$'}XDG_RUNTIME_DIR LANG=${'$'}{LANG:-unset} XMODIFIERS=${'$'}{XMODIFIERS:-unset} GTK_IM_MODULE=${'$'}{GTK_IM_MODULE:-unset}"
unset tiny_sp tiny_synced tiny_filled tiny_ef tiny_isset line k v
""".trimStart()

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

    /**
     * Skrip pemeriksa pustaka sistem yang dibutuhkan AppImage X11 tetapi tidak selalu ada di rootfs (terutama sistem hasil
     * import, yang tidak melewati [ExtraSetup]). Tanpa `libxkbcommon-x11.so.0`, aplikasi berbasis winit (PhotoCraft) menolak
     * mulai dan keluar dengan kode 0 sebelum membuat window: layar hitam, lalu sesi berakhir sendiri. Pustaka dicari di
     * direktori umum dan cache ldconfig; bila kurang, dipasang lewat apt (butuh internet; hasilnya tercatat di log shortcut).
     */
    private fun libGuardScript(): String = """
tiny_need_lib() {
  _so="${'$'}1"; _pkg="${'$'}2"
  _has() {
    for _d in /usr/lib/aarch64-linux-gnu /lib/aarch64-linux-gnu /usr/lib /usr/lib64 /lib; do [ -e "${'$'}_d/${'$'}_so" ] && return 0; done
    { ldconfig -p || /sbin/ldconfig -p; } 2>/dev/null | grep -q "${'$'}_so"
  }
  _has && return 0
  echo "[tiny] pustaka ${'$'}_so tidak ada; memasang paket ${'$'}_pkg"
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    export DEBIAN_FRONTEND=noninteractive
    timeout 120 sudo -n apt-get install -y "${'$'}_pkg" >/dev/null 2>&1 ||
      { timeout 120 sudo -n apt-get update >/dev/null 2>&1 && timeout 180 sudo -n apt-get install -y "${'$'}_pkg" >/dev/null 2>&1; }
  fi
  if _has; then echo "[tiny] paket ${'$'}_pkg terpasang"
  else echo "[tiny] PERINGATAN: ${'$'}_so masih tidak ada (internet mati?). Aplikasi bisa keluar sebelum menampilkan window. Pasang manual: sudo apt install ${'$'}_pkg"; fi
}
tiny_need_lib libxkbcommon-x11.so.0 libxkbcommon-x11-0
""".trimStart()

    /**
     * Isi skrip peluncur untuk satu aplikasi dengan pengaturannya. [xpp] = aplikasi berjalan di atas X++ native X server
     * (pengaturan Display server desktop = X++; lihat [Launcher.shortcutXpp]): tidak ada compositor Wayland, jadi tidak ada
     * variabel Wayland, dan Turnip/Zink memakai WSI software seperti desktop X++.
     */
    private fun script(ctx: Context, app: DesktopApps.App, s: ShortcutPrefs.Settings, id: String, xpp: Boolean, desktop: Boolean, wayland: Boolean): String {
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
        if (xpp) {
            // sama dengan desktop X++ (Launcher.guiCommandXpp): tanpa Wayland, kursor/masukan lewat event inti X
            sb.append("unset WAYLAND_DISPLAY\n")
            sb.append("export TINY_NO_WAYLAND=1 GDK_CORE_DEVICE_EVENTS=1 QT_XCB_NO_XI2=1\n")
            // Diagnosis layar hitam (hanya bila Pengaturan > Logs > Launch log menyala): log SDL3 rinci, jejak protokol server X++
            // (/tmp/.xpp-trace -> /tmp/xpp-trace.log) dan, 15 detik setelah mulai, status proses + jendela X ke log shortcut.
            sb.append(
                """
if [ -n "${'$'}TINY_LAUNCH_LOG" ]; then
  export SDL_LOGGING='*=debug'
  : > /tmp/xpp-trace.log 2>/dev/null
  : > /tmp/.xpp-trace 2>/dev/null
  (
    sleep 15
    echo "[tiny] diag X++ (15 dtk): soket=${'$'}(ls /tmp/.X11-unix 2>&1 | tr '\n' ' ')"
    command -v xdpyinfo >/dev/null 2>&1 && xdpyinfo 2>&1 | grep -E 'number of extensions|dimensions:|^    (GLX|RANDR|XInputExtension|Present|DRI3|XFIXES|Composite)$' | head -12
    command -v xwininfo >/dev/null 2>&1 && xwininfo -root -tree 2>&1 | head -30
    echo "[tiny] diag shortcut env xfwm4: ${'$'}(tr '\0' '\n' < /proc/${'$'}(pgrep -x xfwm4 | head -1)/environ 2>/dev/null | grep -E '^(GDK|QT_|GTK|XDG|DBUS|WAYLAND|DISPLAY|SESSION|DESKTOP|TINY)' | tr '\n' ' ')"
    echo "[tiny] diag shortcut proses WM/sesi: ${'$'}(ps -eo comm 2>/dev/null | grep -E '^(xfwm4|xfsettingsd|xfce4-session|xfdesktop|xfce4-panel)${'$'}' | sort -u | tr '\n' ' ')"
    # status peta (Map State) tiap window tingkat atas: window yang dibuat tetapi tidak pernah di-map tidak tampil (layar hitam)
    for w in ${'$'}(xwininfo -root -children 2>/dev/null | awk '/^ +0x[0-9a-f]+ /{print ${'$'}1}' | head -20); do
      echo "[tiny] diag win ${'$'}w ${'$'}(xwininfo -id ${'$'}w 2>/dev/null | awk -F': +' '/Map State|Override Redirect State|Depth/{printf "%s=%s ", ${'$'}1, ${'$'}2}')"
    done
    for p in ${'$'}(ps -eo pid,comm 2>/dev/null | awk 'NR>1 && ${'$'}2 !~ /^(sh|ps|awk|sleep|tee|dbus|xfwm4)/ {print ${'$'}1}' | head -12); do
      echo "[tiny] diag proc ${'$'}p ${'$'}(cat /proc/${'$'}p/comm 2>/dev/null) state=${'$'}(awk '/^State:/{print ${'$'}2}' /proc/${'$'}p/status 2>/dev/null) wchan=${'$'}(cat /proc/${'$'}p/wchan 2>/dev/null)"
    done
    # status tiap thread proses aplikasi: thread utama yang menunggu futex berarti menunggu thread lain; thread yang tidur di
    # poll/recvmsg berarti menunggu balasan server X (lihat juga baris REQ di bawah)
    for p in ${'$'}(ps -eo pid,comm 2>/dev/null | awk 'NR>1 && ${'$'}2 ~ /ld-linux|ppsspp|PPSSPP/ {print ${'$'}1}' | head -4); do
      for t in /proc/${'$'}p/task/*; do
        echo "[tiny] diag thr ${'$'}p/${'$'}{t##*/} ${'$'}(cat ${'$'}t/comm 2>/dev/null) state=${'$'}(awk '/^State:/{print ${'$'}2}' ${'$'}t/status 2>/dev/null) wchan=${'$'}(cat ${'$'}t/wchan 2>/dev/null) syscall=${'$'}(cut -d' ' -f1-3 ${'$'}t/syscall 2>/dev/null)"
      done
    done
    # sampel CPU/context switch tiap thread PPSSPP: dua kali (15 dtk dan 5 dtk kemudian). Bila thread emu/render terus berputar
    # (sleep 16 ms per frame) angka vol bertambah ratusan; bila benar-benar macet angkanya tidak berubah.
    tiny_cpu_sample() {
      for p in ${'$'}(ps -eo pid,comm 2>/dev/null | awk 'NR>1 && ${'$'}2 ~ /ld-linux|ppsspp|PPSSPP/ {print ${'$'}1}' | head -4); do
        for t in /proc/${'$'}p/task/*; do
          c=${'$'}(cat ${'$'}t/comm 2>/dev/null)
          case "${'$'}c" in PoolW*) continue;; esac
          echo "[tiny] diag cpu ${'$'}1 ${'$'}p/${'$'}{t##*/} ${'$'}c ticks=${'$'}(awk '{print ${'$'}14+${'$'}15}' ${'$'}t/stat 2>/dev/null) vol=${'$'}(awk '/^voluntary_ctxt_switches/{print ${'$'}2}' ${'$'}t/status 2>/dev/null)"
        done
      done
    }
    tiny_cpu_sample t15
    # backtrace kasar tiap thread: ambil sp dari /proc/PID/task/TID/syscall, baca stack lewat /proc/PID/mem, lalu tampilkan
    # nilai yang menunjuk ke kode (modul+offset). Menunjukkan di perpustakaan mana thread yang macet berada.
    for p in ${'$'}(ps -eo pid,comm 2>/dev/null | awk 'NR>1 && ${'$'}2 ~ /ld-linux|ppsspp|PPSSPP/ {print ${'$'}1}' | head -4); do
      command -v python3 >/dev/null 2>&1 && python3 - "${'$'}p" 2>&1 <<'PYSTACK'
import os, sys
pid = sys.argv[1]
maps = []
for line in open('/proc/%s/maps' % pid):
    f = line.split(None, 5)
    if len(f) < 5 or 'x' not in f[1]:
        continue
    a, b = [int(x, 16) for x in f[0].split('-')]
    full = f[5].strip() if len(f) > 5 else '[anon]'
    maps.append((a, b, int(f[2], 16), full.split('/')[-1], full))
import bisect, glob, struct, subprocess
symcache = {}
def find_file(full, base):
    # path di /proc/PID/maps adalah path host; dari dalam proot berkas yang sama dicari lewat nama
    if os.path.isfile(full):
        return full
    for pat in ('/home/tiny/.local/share/appimages/*/app/**/' + base, '/usr/lib/**/' + base, '/usr/local/**/' + base):
        for c in glob.glob(pat, recursive=True):
            if os.path.isfile(c):
                return c
    return None
def load_syms(full, base):
    # tabel simbol FUNC (.symtab bila ada, selain itu .dynsym) + peta offset berkas -> vaddr; None bila tidak terbaca
    if base in symcache:
        return symcache[base]
    res = None
    try:
        path = find_file(full, base)
        if not path:
            raise ValueError('berkas tidak ketemu')
        d = open(path, 'rb').read()
        if d[:4] != b'\x7fELF' or d[4] != 2:
            raise ValueError('bukan ELF64')
        phoff, shoff = struct.unpack_from('<QQ', d, 0x20)[0], struct.unpack_from('<Q', d, 0x28)[0]
        phentsize, phnum, shentsize, shnum = struct.unpack_from('<HHHH', d, 0x36)
        loads = []
        for i in range(phnum):
            t, fl, po, pv, pp, pfs = struct.unpack_from('<IIQQQQ', d, phoff + i * phentsize)
            if t == 1:
                loads.append((po, po + pfs, pv))
        secs = [struct.unpack_from('<IIQQQQIIQQ', d, shoff + i * shentsize) for i in range(shnum)]
        syms = []
        kinds = [s for s in secs if s[1] == 2] or [s for s in secs if s[1] == 11]
        for sh in kinds:
            stro = secs[sh[6]][4]
            for o in range(sh[4], sh[4] + sh[5], 24):
                nm, info, oth, shn, val, size = struct.unpack_from('<IBBHQQ', d, o)
                if (info & 15) == 2 and val:
                    e = d.index(b'\0', stro + nm)
                    syms.append((val, size, d[stro + nm:e].decode('latin1')))
        syms.sort()
        res = (loads, [x[0] for x in syms], syms, 'symtab' if kinds and kinds[0][1] == 2 else 'dynsym')
    except Exception as e:
        print('[tiny] diag sym %s: tidak bisa dibaca: %s' % (base, e))
    symcache[base] = res
    return res
def demangle(n):
    try:
        return subprocess.run(['c++filt', n], capture_output=True, text=True, timeout=5).stdout.strip() or n
    except Exception:
        return n
def symbol(full, base, fileoff):
    r = load_syms(full, base)
    if not r:
        return ''
    loads, keys, syms, kind = r
    va = None
    for lo, hi, pv in loads:
        if lo <= fileoff < hi:
            va = fileoff - lo + pv
            break
    if va is None:
        return ''
    i = bisect.bisect_right(keys, va) - 1
    if i < 0:
        return ''
    val, size, name = syms[i]
    if size and va >= val + size:
        return ''
    return ' {%s+0x%x}' % (demangle(name)[:90], va - val)
def where(v):
    for a, b, off, name, full in maps:
        if a <= v < b:
            fo = v - a + off
            extra = symbol(full, name, fo) if (name.startswith('PPSSPP') or name.startswith('libGLX')) else ''
            return '%s+0x%x%s' % (name, fo, extra)
    return None
try:
    mem = open('/proc/%s/mem' % pid, 'rb', 0)
except Exception as e:
    print('[tiny] diag stack: /proc/%s/mem tidak bisa dibuka: %s' % (pid, e))
    sys.exit(0)
for t in sorted(os.listdir('/proc/%s/task' % pid), key=int):
    try:
        comm = open('/proc/%s/task/%s/comm' % (pid, t)).read().strip()
        if comm.startswith('PoolW'):
            continue
        f = open('/proc/%s/task/%s/syscall' % (pid, t)).read().split()
        if len(f) < 3:
            print('[tiny] diag stack %s/%s %s: %s' % (pid, t, comm, ' '.join(f)))
            continue
        if f[0] == '115' and len(f) >= 5:
            # clock_nanosleep(clock, flags, req, rem): baca timespec yang diminta thread untuk melihat lama tidurnya
            try:
                import struct
                mem.seek(int(f[3], 16))
                sec, nsec = struct.unpack('<qq', mem.read(16))
                print('[tiny] diag sleep %s/%s %s clock_nanosleep clock=%s flags=%s req=%d.%09d detik' % (pid, t, comm, f[1], f[2], sec, nsec))
            except Exception as e:
                print('[tiny] diag sleep %s/%s %s: gagal baca timespec: %s' % (pid, t, comm, e))
        sp, pc = int(f[-2], 16), int(f[-1], 16)
        mem.seek(sp)
        data = mem.read(6144)
        hits = []
        for i in range(0, len(data) - 7, 8):
            w = where(int.from_bytes(data[i:i + 8], 'little'))
            if w and (not hits or hits[-1] != w):
                hits.append(w)
            if len(hits) >= 24:
                break
        print('[tiny] diag stack %s/%s %s pc=%s <- %s' % (pid, t, comm, where(pc), ' < '.join(hits)))
    except Exception as e:
        print('[tiny] diag stack %s/%s %s gagal: %s' % (pid, t, comm, e))
PYSTACK
    done
    command -v xprop >/dev/null 2>&1 && for w in ${'$'}(xwininfo -root -children 2>/dev/null | awk '/^ +0x[0-9a-f]+ "/{print ${'$'}1}' | head -6); do
      echo "[tiny] diag prop ${'$'}w ${'$'}(xprop -id ${'$'}w WM_STATE _NET_WM_STATE WM_HINTS _NET_WM_WINDOW_TYPE _NET_FRAME_EXTENTS 2>&1 | tr '\n' ' ')"
    done
    command -v xprop >/dev/null 2>&1 && echo "[tiny] diag root ${'$'}(xprop -root _NET_CLIENT_LIST _NET_ACTIVE_WINDOW _NET_SUPPORTING_WM_CHECK 2>&1 | tr '\n' ' ')"
    command -v xprop >/dev/null 2>&1 && for w in ${'$'}(xprop -root _NET_CLIENT_LIST 2>/dev/null | grep -o '0x[0-9a-f]*'); do
      echo "[tiny] diag client ${'$'}w ${'$'}(xprop -id ${'$'}w WM_STATE _NET_WM_STATE _NET_FRAME_EXTENTS WM_PROTOCOLS _NET_WM_SYNC_REQUEST_COUNTER 2>&1 | tr '\n' ' ')"
    done
    echo "[tiny] diag xpp-trace klien (pid/proses yang tersambung ke server X++):"
    grep " CLIENT " /tmp/xpp-trace.log 2>/dev/null | tail -n 24
    # request SEMUA klien PPSSPP (proses ini punya lebih dari satu sambungan X): jumlah per opcode dan 40 request terakhir
    # (hanya 4000 request pertama tiap klien yang dicatat). Opcode ekstensi (>=128) diikuti data = nomor request ekstensi.
    for cb in ${'$'}(grep " CLIENT .*comm=ld-linux" /tmp/xpp-trace.log 2>/dev/null | sed 's/.*base=\(0x[0-9a-f]*\).*/\1/' | sort -u); do
      echo "[tiny] diag xpp-trace klien ${'$'}cb: ${'$'}(grep -c " REQ c=${'$'}cb " /tmp/xpp-trace.log 2>/dev/null) request; jumlah per op:"
      grep " REQ c=${'$'}cb " /tmp/xpp-trace.log 2>/dev/null | sed 's/.* op=\([0-9]*\) data=\([0-9]*\) .*/\1\/\2/' | sort | uniq -c | sort -rn | head -14 | tr '\n' ' '
      echo
      echo "[tiny] diag xpp-trace 40 request terakhir klien ${'$'}cb:"
      grep " REQ c=${'$'}cb " /tmp/xpp-trace.log 2>/dev/null | tail -n 40
      echo "[tiny] diag xpp-trace event yang DIKIRIM server ke klien ${'$'}cb (jumlah per type; type 12=Expose 19=MapNotify 18=UnmapNotify 22=ConfigureNotify 9=FocusIn 10=FocusOut 15=Visibility 28=PropertyNotify 35=Generic):"
      grep " EVT c=${'$'}cb " /tmp/xpp-trace.log 2>/dev/null | sed 's/.* type=\([0-9]*\) .*/\1/' | sort -n | uniq -c | tr '\n' ' '
      echo
      echo "[tiny] diag xpp-trace 40 event terakhir klien ${'$'}cb:"
      grep " EVT c=${'$'}cb " /tmp/xpp-trace.log 2>/dev/null | grep -v " type=\(6\|7\|8\) " | tail -n 40
    done
    command -v xrandr >/dev/null 2>&1 && echo "[tiny] diag xrandr: ${'$'}(xrandr --current 2>&1 | head -8 | tr '\n' '|')"
    echo "[tiny] diag xpp-trace (80 baris terakhir, tanpa Unsupported grab dan REQ):"
    grep -v -e "Unsupported grab request" -e " REQ c=" -e " CLIENT " /tmp/xpp-trace.log 2>/dev/null | tail -n 80
    sleep 5
    tiny_cpu_sample t20
    echo "[tiny] diag t20 PutImage-frame terakhir: ${'$'}(grep -c 'PutImage-frame' /tmp/xpp-trace.log 2>/dev/null) frame; request klien utama: ${'$'}(grep -c ' REQ c=0x3400000 ' /tmp/xpp-trace.log 2>/dev/null)"
  ) &
fi
""".trimStart(),
            )
        }
        // sesi X (Xwayland atau X++ milik desktop atau milik sesi aplikasi) bisa baru menyala: tunggu soketnya
        sb.append("i=0; while [ \$i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=\$((i+1)); done\n")
        // Sesi Shortcut berisi desktop XFCE lengkap ([prepareSession] desktop=true): aplikasi baru dibuka setelah window manager
        // dan panel/wallpaper hidup (maks 40 detik), supaya jendelanya tidak muncul di layar kosong lalu tertimpa wallpaper,
        // dan supaya bus D-Bus milik sesi XFCE sudah ada untuk dipakai aplikasi (pencarian di bawah). Berkas penanda
        // ~/.vnc/shortcut-desktop-ready dibaca Launcher untuk layar progres.
        if (desktop) {
            sb.append(DESKTOP_WAIT)
            sb.append(DESKTOP_ENV_SYNC)
        }

        // Aplikasi Wine? (dipakai untuk memilih jalur Wayland di bawah)
        val prog = effectiveCommand(app, s).substringBefore(' ').substringAfterLast('/')
        val isWine = prog == "wine" || prog == "wine64"
        // Window manager sesi Shortcut (xfwm4 dst.) bawaannya menyala untuk aplikasi Linux. Aplikasi Wine butuh layar penuh
        // (WM memberi bingkai dan menahan jendela game), jadi aplikasi Wine mematikan WM dan menulis ~/.vnc/no-wm; penjaga
        // di skrip sesi ([prepareSession]) menjeda WM selama berkas itu ada. Aplikasi Linux menghapus berkas itu sehingga WM
        // menyala lagi, kecuali masih ada Wine yang berjalan (WM tidak boleh mengambil alih jendela game).
        // Desktop yang menyala punya WM sendiri (xfwm4 desktop): tidak disentuh.
        // PPSSPP di X++ (layar hitam: jendela utama dibuat tetapi tidak pernah tampil): emulator ini memakai satu jendela
        // 960x544 layar penuh dan tidak butuh bingkai. Di log, xfwm4 sempat membuat bingkai 960x544 untuk jendela itu lalu
        // menghilang, dan jendela tetap tidak ter-map. Window manager mengambil alih setiap jendela baru lewat
        // SubstructureRedirect, jadi PPSSPP dijalankan seperti aplikasi Wine: WM dijeda dan jendela di-map langsung oleh klien.
        // Ini mitigasi: bila layar tetap hitam, log diagnosis 15 detik (thread, properti jendela, request klien) menunjukkan sebabnya.
        // Kembali ke perilaku lama: tambahkan TINY_PPSSPP_WM=1 di Environment Shortcut PPSSPP.
        val ppssppNoWm = xpp &&
            (app.id.contains("ppsspp", ignoreCase = true) || effectiveCommand(app, s).contains("ppsspp", ignoreCase = true)) &&
            effectiveEnv(app, s).lineSequence().none { it.trim() == "TINY_PPSSPP_WM=1" }
        // Desktop XFCE lengkap: window manager-nya milik sesi XFCE dan tidak dijeda (aplikasi Wine/PPSSPP berjalan sebagai window biasa).
        if (!Launcher.guiStarted && !desktop) {
            if (isWine || ppssppNoWm) {
                if (ppssppNoWm && !isWine) sb.append("echo \"[tiny] X++: window manager dijeda untuk PPSSPP (TINY_PPSSPP_WM=1 = nyalakan lagi)\"\n")
                sb.append(": > \"\$HOME/.vnc/no-wm\"\n")
                sb.append("for w in xfwm4 openbox metacity marco icewm fluxbox; do pkill -x \$w 2>/dev/null; done\n")
                if (ppssppNoWm && !isWine) {
                    // Balapan: penjaga WM di skrip sesi bisa memulai xfwm4 SETELAH pkill di atas (dbus-launch butuh waktu sebelum
                    // proses xfwm4 ada), jadi xfwm4 hidup dan membingkai jendela PPSSPP. Tegakkan lagi no-wm dan matikan WM selama
                    // 15 detik pertama (WM harus sudah hilang sebelum PPSSPP membuat jendela).
                    sb.append("( i=0; while [ \$i -lt 60 ]; do : > \"\$HOME/.vnc/no-wm\"; for w in xfwm4 openbox metacity marco icewm fluxbox; do pkill -x \$w 2>/dev/null; done; sleep 0.25; i=\$((i+1)); done ) >/dev/null 2>&1 &\n")
                }
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
        // buatan pengguna di bawah tetap bisa menimpanya. Tidak untuk X++ (tidak ada compositor Wayland).
        if (wayland && !xpp) {
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
                // X++ tidak punya DRI3 yang bisa dipakai Mesa biasa: WSI software (GPU merender, PutImage menampilkan)
                if (!Cfg.dri3 || xpp) sb.append("export MESA_VK_WSI_DEBUG=sw\n")
            }
        }
        sb.append(soundEnv(s.sound))
        // Batas memori Vulkan khusus aplikasi ini (lapisan VK_LAYER_TINY_memlimit): menimpa Pengaturan > Batas memori. Tanpa pilihan
        // khusus (-1) env dari pengaturan utama dibiarkan; 0 = tanpa batas (lapisan meneruskan semuanya bila batas 0).
        if (s.vulkanMem >= 0) {
            val layerEnv = if (s.vulkanMem > 0) VkMemLimit.envList(s.vulkanMem) else null
            if (layerEnv != null) layerEnv.forEach { sb.append("export $it\n") } else sb.append("export TINY_VK_MEM_LIMIT_MB=0\n")
        }
        // Qt di X++: EGL + Vulkan RHI (bukan GLX + Zink/Kopper, yang membuat jendela Qt Quick hitam karena tidak ada DRI3).
        // Hanya bila GPU Turnip dipakai aplikasi ini; env buatan pengguna di bawah bisa menimpa (mis. QT_QUICK_BACKEND=software).
        if (xpp && turnipOn && (ShortcutPrefs.triState(s.qtGpu) ?: Cfg.xppQtGpu)) {
            sb.append("export QT_XCB_GL_INTEGRATION=xcb_egl QSG_RHI_BACKEND=vulkan\n")
        }
        // Program Vulkan/SDL memakai WSI Wayland langsung ke compositor, bukan X11 lewat Xwayland (tanpa DRI3):
        // itu jalur yang terbukti jalan (vkcube --wsi wayland dengan Turnip). Berlaku untuk GPU Turnip yang dipilih
        // per aplikasi DAN untuk GPU "default" saat Turnip global menyala (dulu hanya yang pertama, sehingga aplikasi
        // dengan GPU default tetap lewat X11 dan Vulkan-nya gagal). Variabel ini hanya untuk proses aplikasi ini, tidak
        // diekspor ke seluruh guest (GTK/Qt/Firefox tetap X11 lewat GDK_BACKEND/QT_QPA_PLATFORM). vkcube/vkcubepp
        // mendapat `--wsi wayland` dari pembungkus [WsiWayland]. SDL_VIDEODRIVER tidak diatur untuk program .exe (Wine).
        // Mengikuti Display server desktop: hanya bila mode Wayland (Cfg.desktopWayland); mode Xwayland dan X++ tetap X11.
        if (turnipOn && !xpp && wayland) {
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
            // pustaka X11 yang wajib ada sebelum aplikasi dijalankan (lihat libGuardScript)
            if (!isWine) sb.append(libGuardScript())
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
        // PPSSPP: bersihkan keadaan sisa dari proses yang dimatikan paksa, lalu paksa backend Vulkan (lihat komentar di skrip).
        // Cara menggantinya: TINY_PPSSPP_GRAPHICS di Environment Shortcut (opengl / software / none = pakai pengaturan PPSSPP).
        val isPpsspp = app.id.contains("ppsspp", ignoreCase = true) || cmd.contains("ppsspp", ignoreCase = true)
        if (isPpsspp) {
            sb.append(
                """
# PPSSPP menyimpan dua keadaan yang bertahan walau prosesnya mati paksa (SIGKILL / kode keluar 137):
# 1) FailedGraphicsBackends.txt (di PSP/SYSTEM): ditulis sebelum sebuah backend dicoba dan baru dihapus setelah berhasil.
#    Bila tertinggal, peluncuran berikutnya menambah ",ALL" dan memilih backend sendiri. "IGNORE" mematikan pelacakan itu.
# 2) shm /PPSSPP_ID: penghitung instance. Bila tertinggal, nomor instance naik terus dan suara dibisukan ("Secondary instance N").
for d in "${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/ppsspp/PSP/SYSTEM" "${'$'}HOME/.config/ppsspp/PSP/SYSTEM"; do
  mkdir -p "${'$'}d" 2>/dev/null || continue
  f="${'$'}d/FailedGraphicsBackends.txt"
  cur="${'$'}(cat "${'$'}f" 2>/dev/null)"
  if [ "${'$'}cur" != "IGNORE" ]; then
    echo "[tiny] heal: ${'$'}f: ${'$'}{cur:-kosong} -> IGNORE"
    printf IGNORE > "${'$'}f" 2>/dev/null
  fi
done
for f in "${'$'}HOME/.var/app/org.ppsspp.PPSSPP/config/ppsspp/PSP/SYSTEM/FailedGraphicsBackends.txt" "${'$'}HOME/.var/app/org.ppsspp.PPSSPP/cache/ppsspp/FailedGraphicsBackends.txt"; do
  [ -f "${'$'}f" ] && [ "${'$'}(cat "${'$'}f" 2>/dev/null)" != "IGNORE" ] && { echo "[tiny] heal: ${'$'}f -> IGNORE"; printf IGNORE > "${'$'}f" 2>/dev/null; }
done
find "${'$'}HOME/.local/share/appimages" -maxdepth 7 -name FailedGraphicsBackends.txt 2>/dev/null | while IFS= read -r f; do
  [ "${'$'}(cat "${'$'}f" 2>/dev/null)" != "IGNORE" ] && { echo "[tiny] heal: ${'$'}f -> IGNORE"; printf IGNORE > "${'$'}f" 2>/dev/null; }
done
if [ -e /dev/shm/PPSSPP_ID ] && ! grep -qsa '[P]PSSPP' /proc/[0-9]*/cmdline 2>/dev/null; then
  rm -f /dev/shm/PPSSPP_ID && echo "[tiny] heal: /dev/shm/PPSSPP_ID (penghitung instance sisa) dihapus"
fi
""".trimStart(),
            )
            sb.append(
                """
# Log PPSSPP ke stdout yang berupa pipa (berkas log shortcut) disangga penuh oleh libc, dan bagian yang belum ter-flush
# HILANG saat sesi ditutup paksa (SIGKILL). Itu sebabnya log berhenti di "Registered VFS ..." walau PPSSPP terus berjalan.
# libstdbuf (coreutils) membuat stdout/stderr per baris. Dilewati bila berkasnya tidak ada.
for L in /usr/libexec/coreutils/libstdbuf.so /usr/lib/coreutils/libstdbuf.so /usr/lib/aarch64-linux-gnu/coreutils/libstdbuf.so; do
  if [ -f "${'$'}L" ]; then
    export _STDBUF_O=L _STDBUF_E=L LD_PRELOAD="${'$'}L${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
    echo "[tiny] log PPSSPP per baris: ${'$'}L"
    break
  fi
done
""".trimStart(),
            )
            if (appImage && !isWine && !cmd.contains("--graphics")) cmd += " --graphics=\${TINY_PPSSPP_GRAPHICS:-vulkan}"
        }
        // Aplikasi Flathub (`flatpak run ...`): Wayland dulu, X11/Xwayland hanya cadangan, plus Turnip bila aktif.
        // `flatpak run` membersihkan lingkungan, jadi ini harus berupa opsi di baris perintah (lihat FlatpakWayland).
        FlatpakWayland.rewrite(ctx, cmd, FlatpakWayland.mode(effectiveEnv(app, s)), turnipOn, xpp)?.let { r ->
            cmd = r.command
            sb.append("echo ${q(r.note)}\n")
        }
        if (app.terminal) cmd = "x-terminal-emulator -e sh -c ${q("exec $cmd")}"
        sb.append("TINY_CMD=${q(cmd)}\n")
        sb.append(
            """
# Dengan launch log menyala aplikasi tidak di-exec: kode keluarnya dicatat (139 = SIGSEGV) supaya crash klien X tidak
# tampak sebagai "keluar normal".
if [ -n "${'$'}TINY_LAUNCH_LOG" ]; then
  if [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
    dbus-launch --exit-with-session sh -c "${'$'}TINY_CMD"
  else
    sh -c "${'$'}TINY_CMD"
  fi
  tiny_rc=${'$'}?
  if [ ${'$'}tiny_rc -gt 128 ]; then
    echo "[tiny] aplikasi mati karena sinyal ${'$'}((tiny_rc-128)) (rc=${'$'}tiny_rc; 11 = SIGSEGV, 6 = SIGABRT)"
  else
    echo "[tiny] aplikasi selesai rc=${'$'}tiny_rc"
  fi
  exit ${'$'}tiny_rc
fi
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
    fun prepare(
        ctx: Context, appId: String, xpp: Boolean = Launcher.shortcutXpp(appId), desktop: Boolean = Launcher.shortcutDesktop(appId),
        wayland: Boolean = Launcher.shortcutWayland(appId),
    ): String? {
        val app = DesktopApps.find(appId)
        if (app == null) {
            Log.w(TAG, "aplikasi $appId tidak ditemukan")
            return null
        }
        WsiWayland.install() // pembungkus vkcube/vkcubepp: --wsi wayland
        val id = safe(appId)
        write("run-$id.sh", script(ctx, app, ShortcutPrefs.get(appId), id, xpp, desktop, wayland))
        return "$GUEST_DIR/run-$id.sh"
    }

    /**
     * Menulis skrip sesi yang menyalakan Xwayland (+ window manager ringan bila ada, diawasi dan dicoba bergantian) tanpa sesi desktop
     * dan mengembalikan path-nya di dalam guest. Ukuran layar = [size] (ukuran khusus aplikasi yang
     * memulai sesi, atau ukuran desktop; lihat [Launcher.displaySize]). Skrip menunggu soket Wayland, lalu MENJADI Xwayland (`exec`): proot-nya
     * hidup selama X hidup dan, karena `--kill-on-exit`, window manager ikut berhenti bersamanya.
     * [withWm] false (sesi dimulai aplikasi Wine): window manager dijeda supaya game bisa layar penuh; aplikasi Linux
     * yang dijalankan kemudian menyalakannya lagi.
     * [xpp] true (Display server desktop = X++): server X sudah menyala di proses aplikasi ([XppHost.ensureStarted]) dan
     * mendengarkan di X4, jadi tidak ada Xwayland: soket X4 tidak dihapus, tidak menunggu compositor Wayland, tidak
     * memakai xrandr, dan proot ini hanya menjaga window manager lalu berakhir bila soket X++ hilang (server dimatikan).
     */
    fun prepareSession(ctx: Context, size: Pair<Int, Int>, withWm: Boolean = true, xpp: Boolean = false, desktop: Boolean = false,
        wayland: Boolean = false, compositor: Boolean = Cfg.xfceCompositor, qtGpu: Boolean = Cfg.xppQtGpu,
    ): String {
        val (w, h) = size
        // [desktop] true: setelah server X menyala, sesi XFCE penuh (Xsession yang sama dengan Open desktop: xfwm4, panel, wallpaper)
        // dijalankan di DISPLAY=:4. Hanya desktopnya; aplikasi yang dipilih dijalankan terpisah ([script]) sehingga cuma satu
        // aplikasi yang berjalan. Window manager ringan di bawah hanya jadi cadangan bila sesi XFCE gagal (tidak ada XFCE di rootfs).
        if (desktop) runCatching {
            val vnc = File(TinyEnv.containerDir(), "home/tiny/.vnc").apply { mkdirs() }
            File(vnc, "tiny-session.sh").writeText(Rootfs.asset(ctx, "tiny/xsession.sh"))
            File(vnc, DESKTOP_READY_FILE).delete()
            File(vnc, "desktop-failed").delete()
        }
        val xsEnv = buildString {
            if (!xpp) append(Launcher.waylandSessionEnv(wayland))
            if (compositor) append("TINY_XFCE_COMPOSITOR=1 ")
            append("TINY_SHORTCUT_SESSION=1 ")
        }
        val xsGpu = if (xpp) {
            (if (Cfg.turnip) "export ${Launcher.turnipOpt(ctx)} LIBGL_KOPPER_DRI2=true MESA_VK_WSI_DEBUG=sw\n" else "") +
                Launcher.qtGpuEnv(qtGpu) +
                "export TINY_NO_WAYLAND=1 GDK_CORE_DEVICE_EVENTS=1 QT_XCB_NO_XI2=1\nunset WAYLAND_DISPLAY\n"
        } else ""
        val desktopBlock = if (!desktop) "" else """
(
  i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
  [ -S /tmp/.X11-unix/X4 ] || { : > "${'$'}HOME/.vnc/desktop-failed"; exit 1; }
  export DISPLAY=:4
  ${xsGpu.trimEnd().replace("\n", "\n  ")}
  if [ -n "${'$'}TINY_LAUNCH_LOG" ] && [ -w "${'$'}TINY_LAUNCH_LOG" ]; then
    env DISPLAY=:4 ${xsEnv}bash "${'$'}HOME/.vnc/tiny-session.sh" xinit/Xsession 2>&1 | tee "${'$'}HOME/.vnc/x.log" >> "${'$'}TINY_LAUNCH_LOG"
  else
    env DISPLAY=:4 ${xsEnv}bash "${'$'}HOME/.vnc/tiny-session.sh" xinit/Xsession > "${'$'}HOME/.vnc/x.log" 2>&1
  fi
  : > "${'$'}HOME/.vnc/desktop-failed"
) &
"""
        // pada sesi desktop window manager ringan menunggu xfwm4 milik XFCE; baru dipakai bila sesi XFCE gagal
        val wmWait = if (!desktop) "" else """
  i=0
  while [ ${'$'}i -lt 300 ]; do
    pgrep -x xfwm4 >/dev/null 2>&1 && exit 0
    [ -e "${'$'}HOME/.vnc/desktop-failed" ] && { echo "[wm] sesi XFCE gagal; memakai window manager ringan" >> "${'$'}WMLOG"; break; }
    sleep 0.2; i=${'$'}((i+1))
  done
""".trimEnd()
        // berkas penanda: penjaga window manager di skrip sesi tidak menyalakannya (aplikasi Wine juga membuatnya sendiri)
        val noWm = if (withWm) "" else ": > \"${'$'}HOME/.vnc/no-wm\"\n"
        // Xwayland (bawaan) atau X++ (server di proses aplikasi): bagian skrip yang berbeda
        val cleanSock = if (xpp) "rm -f /tmp/.X4-lock" else "rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock"
        val waitWayland = if (xpp) "" else "i=0; while [ \$i -lt 150 ] && [ ! -S /tmp/wayland-0 ]; do sleep 0.2; i=\$((i+1)); done\n"
        val xrandrCond = if (xpp) "false" else "command -v xrandr >/dev/null 2>&1"
        // Window manager sesi Shortcut di atas X++ harus punya lingkungan yang sama dengan xfwm4 milik Open desktop
        // (Launcher.guiCommandXpp): tanpa Wayland dan dengan event inti X untuk pointer. Sebelumnya hanya skrip aplikasi
        // yang mengekspor GDK_CORE_DEVICE_EVENTS/QT_XCB_NO_XI2, sedangkan xfwm4 sesi Shortcut berjalan tanpa keduanya
        // (beda dengan xfwm4 Open desktop), sehingga bingkai jendela tampil tetapi tidak bisa digeser, ditutup,
        // dimaksimalkan, atau dikecilkan. Xwayland tidak membutuhkannya. Nilainya dicatat di wm.log.
        // Compositor xfwm4 sesi Shortcut: bawaannya mati (--compositor=off). Bila "Allow XFCE compositor" menyala dan server
        // X++, xfwm4 dijalankan dengan compositor menyala seperti xfwm4 Open desktop (Launcher.guiCommandXpp memakai
        // TINY_XFCE_COMPOSITOR=1). Jejak desktop yang window-nya bisa digeser memakai compositor menyala, sedangkan xfwm4
        // Shortcut (compositor mati) tidak menanggapi klik di bingkai; ini cara menguji apakah compositor penyebabnya.
        val wmCompArg = if (xpp && compositor) "--compositor=on" else "--compositor=off"
        val wmEnv = if (xpp) {
            "unset WAYLAND_DISPLAY\nexport TINY_NO_WAYLAND=1 GDK_CORE_DEVICE_EVENTS=1 QT_XCB_NO_XI2=1\n"
        } else ""
        val serverTail = if (xpp) {
            "i=0; while [ \$i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=\$((i+1)); done\n" +
                "echo \"[tiny] sesi X++ aktif ${w}x$h\" > \"\$HOME/.vnc/xwayland.log\"\n" +
                "while [ -S /tmp/.X11-unix/X4 ]; do sleep 2; done\n"
        } else {
            "XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 ${Launcher.xwaylandEnv()}exec ${Launcher.xwaylandBin()} :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx -extension MIT-SHM > \"\$HOME/.vnc/xwayland.log\" 2>&1\n"
        }
        write(
            SESSION_SCRIPT,
            """
#!/bin/sh
mkdir -p "${'$'}HOME/.vnc"
${if (xpp) "" else "command -v Xwayland >/dev/null 2>&1 || { echo \"Xwayland belum terpasang\"; exit 1; }\n"}${cleanSock}
${waitWayland}rm -f "${'$'}HOME/.vnc/no-wm"
${noWm}# window manager (title bar + tombol tutup/maksimalkan/kecilkan) untuk aplikasi Shortcut.
# Berkas ~/.vnc/no-wm (dibuat aplikasi Wine) = window manager dijeda; aplikasi Linux menghapusnya agar WM menyala lagi.
# - berjalan di bus D-Bus sendiri: xfwm4 butuh xfconf lewat D-Bus, tanpa itu langsung mati dan jendela
#   tampil tanpa bingkai;
# - tiap kandidat diperiksa masih hidup setelah 2 detik, kalau tidak lanjut ke kandidat berikutnya;
# - bila WM yang sudah berjalan berhenti (crash), dinyalakan lagi (maks 20 kali).
(
  i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
  export DISPLAY=:4
  ${wmEnv.trimEnd().replace("\n", "\n  ")}
  WMLOG="${'$'}HOME/.vnc/wm.log"
  : > "${'$'}WMLOG"
  echo "[wm] env: GDK_CORE_DEVICE_EVENTS=${'$'}{GDK_CORE_DEVICE_EVENTS:-unset} QT_XCB_NO_XI2=${'$'}{QT_XCB_NO_XI2:-unset} WAYLAND_DISPLAY=${'$'}{WAYLAND_DISPLAY:-unset}" >> "${'$'}WMLOG"
${wmWait}
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
      [ "${'$'}wm" = xfwm4 ] && args=${wmCompArg}
      echo "[wm] mulai ${'$'}wm ${'$'}args" >> "${'$'}WMLOG"
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
${desktopBlock}# Paksa mode layar = ${w}x$h bila xrandr ada (Xwayland rootful bisa menyala dengan mode lain); hasilnya dicatat.
(
  i=0; while [ ${'$'}i -lt 150 ] && [ ! -S /tmp/.X11-unix/X4 ]; do sleep 0.2; i=${'$'}((i+1)); done
  if ${xrandrCond}; then
    export DISPLAY=:4
    sleep 1
    out=${'$'}(xrandr 2>/dev/null | awk '/ connected/{print ${'$'}1; exit}')
    [ -n "${'$'}out" ] && xrandr --output "${'$'}out" --mode ${w}x$h >> "${'$'}HOME/.vnc/xrandr.log" 2>&1
    xrandr --current >> "${'$'}HOME/.vnc/xrandr.log" 2>&1
  fi
) &
${serverTail}""".trimStart(),
        )
        return "$GUEST_DIR/$SESSION_SCRIPT"
    }
}
