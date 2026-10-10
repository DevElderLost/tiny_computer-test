package com.example.tiny_computer.core

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port `Workflow.setupBootstrap`, `initForFirstTime` dan `initData` dari workflow.dart.
 * Logika Linux/Debian (proot, rootfs `xa*`, assets.zip, patch.tar.gz) tidak diubah:
 * skrip shell diekstrak langsung dari kode Dart (assets/tiny/*.sh).
 */
object Rootfs {
    private const val TAG = "TinyRootfs"

    /** Teks status untuk layar pemuatan. */
    @Volatile
    var statusText: String = ""

    private fun asset(ctx: Context, name: String) =
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun copyAsset(ctx: Context, src: String, dst: File) {
        ctx.assets.open(src).use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }

    fun setupBootstrap(ctx: Context) {
        val data = TinyEnv.dataPath
        for (d in listOf("share", "bin", "lib", "tmp", "proot_tmp", "pulseaudio_tmp")) File(data, d).mkdirs()
        // assets.zip: bin/ (proot, pulseaudio, tar, ...) dan libexec/ (proot loader)
        copyAsset(ctx, "assets.zip", File(data, "assets.zip"))
        // patch.tar.gz: folder tiny yang dimount ke ~/.local/share/tiny. Disalin CI dari rilis tiny_container.
        runCatching { copyAsset(ctx, "patch.tar.gz", File(data, "patch.tar.gz")) }
            .onFailure { Log.w(TAG, "patch.tar.gz tidak ada di assets (CI menyalinnya dari rilis tiny_container): ${it.message}") }
        val script = asset(ctx, "tiny/setup_bootstrap.sh").replace("@DATA_PATH@", data)
        val code = Shell.execute(script)
        if (code != 0) Log.w(TAG, "setup_bootstrap.sh exit=$code")
        ensureFakeInput(ctx)
    }

    /**
     * libfakeinput.so (dari DroidDeck: fakeinput_steam.cpp) dibuat CI untuk aarch64 glibc dan
     * diletakkan di assets. Disalin ke folder `tiny/extra` yang dimount ke guest, seperti
     * getifaddrs_bridge_client_lib.so. Dipanggil juga di setiap peluncuran agar selalu terbaru.
     */
    fun ensureFakeInput(ctx: Context) {
        val dst = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so")
        runCatching {
            dst.parentFile?.mkdirs()
            copyAsset(ctx, "libfakeinput.so", dst)
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
        }.onFailure { Log.w(TAG, "libfakeinput.so tidak ada di assets: controller tidak aktif (${it.message})") }
    }

    private fun initForFirstTime(ctx: Context) {
        val data = TinyEnv.dataPath
        statusText = "installingBootPackage"
        setupBootstrap(ctx)

        statusText = "copyingContainerSystem"
        File(data, "containers/0/.l2s").mkdirs()
        // rootfs dipecah dengan `split` menjadi xa*, ditaruh di assets
        val parts = (ctx.assets.list("") ?: emptyArray()).filter { it.startsWith("xa") }.sorted()
        for (name in parts) copyAsset(ctx, name, File(data, name))

        statusText = "installingContainerSystem"
        val localeLine = if (TinyEnv.isChineseLocale) ""
        else "echo 'LANG=en_US.UTF-8' > \$CONTAINER_DIR/usr/local/etc/tmoe-linux/locale.txt"
        val script = asset(ctx, "tiny/install_rootfs.sh")
            .replace("@DATA_PATH@", data)
            .replace("@LOCALE_LINE@", localeLine)
        val code = Shell.execute(script)
        if (code != 0) Log.w(TAG, "install_rootfs.sh exit=$code")

        val zh = TinyEnv.isChineseLocale
        val info = JSONObject()
            .put("name", "Debian Trixie")
            .put("boot", TinyEnv.defaultBoot)
            .put("vnc", "startnovnc &")
            .put("vncUrl", "http://localhost:36082/vnc.html?host=localhost&port=36082&autoconnect=true&resize=remote&password=12345678")
            .put("vncUri", "vnc://127.0.0.1:5904?VncPassword=12345678&SecurityType=2")
            .put("commands", TinyEnv.defaultCommands)
        Containers.replaceAll(listOf(info.toString()))
        if (!zh) Log.i(TAG, "locale non-zh: boot disesuaikan ke en_US")
        statusText = "installationComplete"
    }

    /** Port `initData`. Dipanggil di thread latar. Mengembalikan true bila ini peluncuran pertama. */
    fun initData(ctx: Context): Boolean {
        TinyEnv.currentContainer = 0
        // symlink applib -> nativeLibraryDir (proot, busybox, dll. dipaketkan sebagai .so)
        Shell.execute("ln -sf ${ctx.applicationInfo.nativeLibraryDir} ${TinyEnv.dataPath}/applib")

        var first = false
        if (!TinyPrefs.contains("defaultContainer")) {
            first = true
            initForFirstTime(ctx)
            // Dulu: sesuaikan resolusi VNC dengan layar pengguna. Kini resolusi layar diatur di
            // pengaturan Layar (0 = otomatis 75% dari ukuran panel), jadi cukup tulis nilainya.
            val dm: DisplayMetrics = ctx.resources.displayMetrics
            val w = (max(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
            val h = (min(dm.widthPixels, dm.heightPixels) * 0.75).roundToInt()
            Cfg.displayWidth = w
            Cfg.displayHeight = h
            TinyEnv.postCommand = ""
            if (!TinyEnv.isChineseLocale) {
                TinyEnv.postCommand += "localedef -c -i en_US -f UTF-8 en_US.UTF-8"
                // Pengguna non-Mandarin diasumsikan perlu menulis ke terminal
                Cfg.isTerminalWriteEnabled = true
                Cfg.isTerminalCommandsEnabled = true
                Cfg.isStickyKey = false
                Cfg.wakelock = true
            }
            Cfg.getifaddrsBridge = Build.VERSION.SDK_INT >= 31
            Cfg.defaultContainer = 0
        }
        TinyEnv.currentContainer = Cfg.defaultContainer

        if (Cfg.reinstallBootstrap) {
            statusText = "reinstallingBootPackage"
            setupBootstrap(ctx)
            Cfg.reinstallBootstrap = false
        }
        ensureFakeInput(ctx)
        return first
    }
}
