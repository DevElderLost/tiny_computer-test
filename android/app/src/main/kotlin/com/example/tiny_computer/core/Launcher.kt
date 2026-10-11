package com.example.tiny_computer.core

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import com.example.tiny_computer.display.PadSetup
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Port `launchCurrentContainer`, `setupAudio`, `launchGUIBackend` dari workflow.dart. */
object Launcher {
    @Volatile private var audioProcess: Process? = null
    @Volatile private var started = false

    /** True setelah perintah Xwayland + Xsession dikirim ke kontainer yang sedang berjalan. */
    @Volatile var guiStarted = false

    /** True setelah perintah boot ditulis ke terminal (kontainer sedang/ sudah berjalan). */
    val isStarted: Boolean get() = started

    fun setupAudio() {
        audioProcess?.destroy()
        val data = TinyEnv.dataPath
        audioProcess = Shell.spawn(
            """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
${'$'}DATA_DIR/bin/busybox sed "s/4713/${Cfg.defaultAudioPort}/g" ${'$'}DATA_DIR/bin/pulseaudio.conf > ${'$'}DATA_DIR/bin/pulseaudio.conf.tmp
rm -rf ${'$'}DATA_DIR/pulseaudio_tmp/*
TMPDIR=${'$'}DATA_DIR/pulseaudio_tmp HOME=${'$'}DATA_DIR/pulseaudio_tmp XDG_CONFIG_HOME=${'$'}DATA_DIR/pulseaudio_tmp LD_LIBRARY_PATH=${'$'}DATA_DIR/bin:${'$'}LD_LIBRARY_PATH ${'$'}DATA_DIR/bin/pulseaudio -F ${'$'}DATA_DIR/bin/pulseaudio.conf.tmp
""".trimIndent(),
        )
    }

    /** Ukuran output compositor: pengaturan pengguna, atau 75% panel (landscape) bila 0. */
    fun displaySize(ctx: Context): Pair<Int, Int> {
        var w = Cfg.displayWidth
        var h = Cfg.displayHeight
        if (w <= 0 || h <= 0) {
            val dm: DisplayMetrics = ctx.resources.displayMetrics
            w = (max(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
            h = (min(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
        }
        // lebar/tinggi genap, seperti yang diharapkan Xwayland dan encoder
        return (w and 1.inv()).coerceAtLeast(320) to (h and 1.inv()).coerceAtLeast(240)
    }

    /**
     * Opsi lingkungan Turnip/Zink untuk guest. ICD diambil dari driver Linux hasil impor
     * ([VulkanDrivers.activeIcd]); bila tidak ada, ICD bawaan di `tiny/extra` dipakai SELAMA berkasnya
     * ada. Dulu path ke `freedreno_icd.aarch64.json` yang tidak ada tetap dikirim, sehingga vulkaninfo
     * gagal "Failed to open JSON file" -> "Found no drivers".
     */
    fun turnipOpt(ctx: Context): String {
        val re = Regex("""\s*VK_(?:ICD_FILENAMES|DRIVER_FILES)=\S+""")
        val base = Cfg.defaultTurnipOpt
        val imported = VulkanDrivers.activeIcd(ctx)
        if (imported != null) {
            Log.i("Launcher", "driver Vulkan Linux hasil impor: $imported")
            return re.replace(base, "").trim() + " VK_ICD_FILENAMES=$imported VK_DRIVER_FILES=$imported"
        }
        val guestPrefix = "/home/tiny/.local/share/tiny/"
        var out = base
        for (m in re.findAll(base)) {
            val value = m.value.substringAfter('=')
            val host = if (value.startsWith(guestPrefix)) File(TinyEnv.dataPath, "tiny/" + value.removePrefix(guestPrefix)) else File(value)
            if (!host.isFile) {
                Log.w("Launcher", "ICD $value tidak ada ($host); impor driver Vulkan Linux di Pengaturan")
                out = out.replace(m.value, "")
            }
        }
        return out.trim()
    }

    /**
     * Perintah di dalam kontainer yang menyiapkan mikrofon virtual: sink null + source remap, lalu klien
     * soket. Memakai `tiny_virtual_mic` bila ada; bila tidak, `socat` + `pacat` (paket socat dan
     * pulseaudio-utils); bila keduanya tidak ada, mencetak petunjuk (tanpa membuat aplikasi macet).
     */
    fun micCommand(socketPath: String): String = """
pactl list short sinks | grep -q AndroidSink || pactl load-module module-null-sink sink_name=AndroidSink sink_properties=device.description="Android_Audio_Stream" >/dev/null
pactl list short sources | grep -q AndroidMic || pactl load-module module-remap-source master=AndroidSink.monitor source_name=AndroidMic source_properties=device.description="Android_Virtual_Mic" >/dev/null
pkill -f tiny_virtual_mic 2>/dev/null; pkill -f 'UNIX-CONNECT:$socketPath' 2>/dev/null
if command -v tiny_virtual_mic >/dev/null 2>&1; then
  tiny_virtual_mic $socketPath AndroidSink &
elif command -v socat >/dev/null 2>&1 && command -v pacat >/dev/null 2>&1; then
  (socat -u UNIX-CONNECT:$socketPath - | pacat --playback --device=AndroidSink --rate=44100 --channels=1 --format=s16le --latency-msec=60) &
else
  echo "Klien mikrofon tidak ada. Jalankan: sudo apt install -y socat pulseaudio-utils"
fi
""".trimIndent()

    fun launchCurrentContainer(ctx: Context) {
        val data = TinyEnv.dataPath
        val idx = TinyEnv.currentContainer
        var extraMount = ""
        val extraOpt = StringBuilder()
        val preload = ArrayList<String>()

        // Isi folder `extra` berasal dari patch.tar.gz; berkas yang tidak ada TIDAK boleh masuk
        // LD_PRELOAD karena setiap proses guest lalu mencetak "cannot be preloaded" (lihat terminal).
        val extraDir = File(data, "tiny/extra")
        if (Cfg.getifaddrsBridge) {
            Shell.spawn("$data/bin/getifaddrs_bridge_server $data/containers/$idx/tmp/.getifaddrs-bridge")
            if (File(extraDir, "getifaddrs_bridge_client_lib.so").isFile) {
                preload += "/home/tiny/.local/share/tiny/extra/getifaddrs_bridge_client_lib.so"
            } else Log.w("Launcher", "getifaddrs_bridge_client_lib.so tidak ada di $extraDir, bridge dilewati")
        }
        // Controller (evdev + hidraw) dari DroidDeck: libfakeinput.so melayani /dev/input dan /dev/hidraw16.
        val pad = PadSetup.prepare(ctx)
        if (File(data, "tiny/extra/libfakeinput.so").isFile) {
            preload += "/home/tiny/.local/share/tiny/extra/libfakeinput.so"
            pad.env.forEach { extraOpt.append(it).append(' ') }
            pad.mounts.forEach { extraMount += "$it " }
        }
        if (preload.isNotEmpty()) extraOpt.append("LD_PRELOAD=").append(preload.joinToString(":")).append(' ')

        // Sengaja TIDAK mengekspor WAYLAND_DISPLAY ke seluruh guest: aplikasi GTK (mis. xfce4-notifyd)
        // lalu memakai backend Wayland dan gagal karena compositor tidak punya wlr-layer-shell.
        // Hanya Xwayland yang diberi variabel Wayland (lihat guiCommand); desktop tetap murni X11.
        extraOpt.append("GDK_BACKEND=x11 QT_QPA_PLATFORM=xcb SDL_VIDEODRIVER=x11 ")

        if (Cfg.isHidpiEnabled) extraOpt.append(Cfg.defaultHidpiOpt).append(' ')
        if (Cfg.virgl) {
            Shell.spawn(
                """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
export CONTAINER_DIR=${'$'}DATA_DIR/containers/$idx
$data/bin/virgl_test_server ${Cfg.defaultVirglCommand}""".trimIndent(),
            )
            extraOpt.append(Cfg.defaultVirglOpt).append(' ')
        }
        if (Cfg.turnip) {
            extraOpt.append(turnipOpt(ctx)).append(' ')
            if (!Cfg.dri3) extraOpt.append("MESA_VK_WSI_DEBUG=sw ")
        }
        if (Cfg.isJpEnabled) extraOpt.append("LANG=ja_JP.UTF-8 ")
        extraMount += "--mount=\$DATA_DIR/tiny/font:/usr/share/fonts/tiny "
        extraMount += "--mount=\$DATA_DIR/tiny/extra/cmatrix:/home/tiny/.local/bin/cmatrix "
        // proot tidak membuat titik mount untuk sumber yang tidak ada; tanpa berkas ini perintah
        // `tiny_virtual_mic` "command not found" (ada cadangan socat/pacat di [micCommand]).
        if (File(extraDir, "tiny_virtual_mic").isFile) {
            extraMount += "--mount=\$DATA_DIR/tiny/extra/tiny_virtual_mic:/home/tiny/.local/bin/tiny_virtual_mic "
        }

        val boot = Containers.boot(idx, TinyEnv.defaultBoot)
        val term = TermSessions.forCurrent(ctx)
        term.writeLine(
            """
export DATA_DIR=$data
export PATH=${'$'}DATA_DIR/bin:${'$'}PATH
export LD_LIBRARY_PATH=${'$'}DATA_DIR/lib
export CONTAINER_DIR=${'$'}DATA_DIR/containers/$idx
export EXTRA_MOUNT="$extraMount"
export EXTRA_OPT="$extraOpt"
cd ${'$'}DATA_DIR
export PROOT_TMP_DIR=${'$'}DATA_DIR/proot_tmp
export PROOT_LOADER=${'$'}DATA_DIR/applib/libproot-loader.so
export PROOT_LOADER_32=${'$'}DATA_DIR/applib/libproot-loader32.so
$boot
${TinyEnv.postCommand}
clear""".trimIndent(),
        )
        started = true
        guiStarted = false
    }

    /**
     * Perintah di dalam kontainer yang menyalakan desktop di atas compositor Wayland:
     * Xwayland berjalan rootful (satu jendela xdg-toplevel di compositor) lalu sesi X11
     * Debian (`/etc/X11/xinit/Xsession`, tak berubah) dijalankan di DISPLAY=:4.
     */
    fun guiCommand(ctx: Context): String {
        val (w, h) = displaySize(ctx)
        return """
mkdir -p "${'$'}HOME/.vnc"
command -v Xwayland >/dev/null 2>&1 || { echo "Memasang xwayland..."; sudo apt update && sudo apt install -y xwayland; }
unset WAYLAND_DISPLAY
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
for i in ${'$'}(seq 1 150); do [ -S /tmp/wayland-0 ] && break; sleep 0.2; done
(XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 Xwayland :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx > "${'$'}HOME/.vnc/xwayland.log" 2>&1 &)
for i in ${'$'}(seq 1 150); do [ -S /tmp/.X11-unix/X4 ] && break; sleep 0.2; done
bash /etc/X11/xinit/Xsession &> "${'$'}HOME/.vnc/x.log" &
""".trimIndent()
    }

    fun launchGuiBackend(ctx: Context) {
        val term = TermSessions.forCurrent(ctx)
        guiStarted = true
        term.writeLine(guiCommand(ctx))
        term.writeLine("clear")
    }

    /** Port `workflow()`: siapkan data, terminal, audio, lalu nyalakan kontainer. */
    fun start(ctx: Context) {
        TermSessions.forCurrent(ctx)
        setupAudio()
        launchCurrentContainer(ctx)
    }
}
