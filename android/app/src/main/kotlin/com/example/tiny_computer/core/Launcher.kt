package com.example.tiny_computer.core

import android.content.Context
import android.util.DisplayMetrics
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

    fun launchCurrentContainer(ctx: Context) {
        val data = TinyEnv.dataPath
        val idx = TinyEnv.currentContainer
        var extraMount = ""
        val extraOpt = StringBuilder()
        val preload = ArrayList<String>()

        if (Cfg.getifaddrsBridge) {
            Shell.spawn("$data/bin/getifaddrs_bridge_server $data/containers/$idx/tmp/.getifaddrs-bridge")
            preload += "/home/tiny/.local/share/tiny/extra/getifaddrs_bridge_client_lib.so"
        }
        // Controller (evdev + hidraw) dari DroidDeck: libfakeinput.so melayani /dev/input dan /dev/hidraw16.
        val pad = PadSetup.prepare(ctx)
        if (File(data, "tiny/extra/libfakeinput.so").isFile) {
            preload += "/home/tiny/.local/share/tiny/extra/libfakeinput.so"
            pad.env.forEach { extraOpt.append(it).append(' ') }
            pad.mounts.forEach { extraMount += "$it " }
        }
        if (preload.isNotEmpty()) extraOpt.append("LD_PRELOAD=").append(preload.joinToString(":")).append(' ')

        // Wayland: soket compositor ada di folder tmp kontainer = /tmp di guest
        extraOpt.append("XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0 ")

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
            extraOpt.append(Cfg.defaultTurnipOpt).append(' ')
            if (!Cfg.dri3) extraOpt.append("MESA_VK_WSI_DEBUG=sw ")
        }
        if (Cfg.isJpEnabled) extraOpt.append("LANG=ja_JP.UTF-8 ")
        extraMount += "--mount=\$DATA_DIR/tiny/font:/usr/share/fonts/tiny "
        extraMount += "--mount=\$DATA_DIR/tiny/extra/cmatrix:/home/tiny/.local/bin/cmatrix "
        extraMount += "--mount=\$DATA_DIR/tiny/extra/tiny_virtual_mic:/home/tiny/.local/bin/tiny_virtual_mic "

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
export XDG_RUNTIME_DIR=/tmp WAYLAND_DISPLAY=wayland-0
rm -f /tmp/.X11-unix/X4 /tmp/.X4-lock
for i in ${'$'}(seq 1 150); do [ -S /tmp/wayland-0 ] && break; sleep 0.2; done
(Xwayland :4 -fullscreen -geometry ${w}x$h -noreset -ac +iglx > "${'$'}HOME/.vnc/xwayland.log" 2>&1 &)
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
