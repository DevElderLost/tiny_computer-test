package com.example.tiny_computer.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Snap palsu untuk kontainer Ubuntu/Debian arm64 (mis. Ubuntu 24.04 di proot): systemd palsu, squashfs palsu
 * (mount = ekstrak dengan unsquashfs), `tiny-snapd` (pengganti snapd + perintah `snap`) dan Ubuntu App Center.
 *
 * Pola sama dengan [FlatpakManager]: berkas pendukung ada di assets `tiny/snap`, disalin ke `$DATA_DIR/tiny/snap`
 * (di kontainer: `~/.local/share/tiny/snap`), dan skrip pasang `tiny-snap-setup` dijalankan di TERMINAL kontainer.
 */
object SnapManager {
    private const val TAG = "TinySnap"
    private const val ASSET_DIR = "tiny/snap"
    const val GUEST_DIR = "/home/tiny/.local/share/tiny/snap"
    private const val SETUP = "$GUEST_DIR/tiny-snap-setup"
    private val FILES = listOf("tiny-snap-setup", "tiny-snapd.py", "tiny-systemctl", "tiny-mount")

    fun hostDir(): File = File(TinyEnv.dataPath, "tiny/snap")

    /** Menyalin skrip dari assets (selalu menimpa supaya pembaruan aplikasi ikut). Mengembalikan galat, atau null. */
    fun stageFiles(ctx: Context): String? = try {
        val dir = hostDir().apply { mkdirs() }
        for (name in FILES) {
            val dst = File(dir, name)
            ctx.assets.open("$ASSET_DIR/$name").use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
        }
        null
    } catch (t: Throwable) {
        Log.e(TAG, "menyalin skrip snap gagal", t)
        t.message ?: t.javaClass.simpleName
    }

    /** Infrastruktur snap palsu terpasang di rootfs. Dibaca langsung dari berkas, tanpa proses. */
    fun ready(): Boolean {
        val root = TinyEnv.containerDir()
        return File(root, "usr/local/bin/snap").exists() && File(root, "usr/local/lib/tiny-snap/tiny-snapd.py").isFile
    }

    fun appCenterInstalled(): Boolean = File(TinyEnv.containerDir(), "snap/snap-store/current").exists()

    fun isSetupDone(): Boolean = File(hostDir(), "setup.done").exists()

    fun setupFailure(): String? = File(hostDir(), "setup.failed").takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }

    /** Menjalankan skrip pasang di terminal kontainer (kontainer dinyalakan dulu bila belum jalan). Mengembalikan galat, atau null. */
    fun beginSetup(ctx: Context): String? {
        stageFiles(ctx)?.let { return it }
        File(hostDir(), "setup.done").delete()
        File(hostDir(), "setup.failed").delete()
        val log = LaunchLog.begin("snap-setup")
        LaunchLog.write(log, "snap", "pasang systemd palsu + squashfs palsu + tiny-snapd + App Center di terminal kontainer")
        val command = if (log == null) "clear; bash $SETUP" else "clear; bash $SETUP 2>&1 | tee -a '${log.file.absolutePath}'"
        if (Startup.phase == Startup.Phase.DONE && Launcher.isStarted) {
            Launcher.runInTerminal(ctx, command)
            NavRequest.terminal = true
        } else {
            Startup.beginForTerminal(ctx, command)
        }
        return null
    }
}
