package com.example.tiny_computer.core

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port `Workflow.setupBootstrap`, `initForFirstTime` dan `initData` dari workflow.dart.
 * Logika Linux/Debian (proot, rootfs `xa*`, assets.zip, patch.tar.gz) tidak diubah:
 * skrip shell diekstrak langsung dari kode Dart (berkas .sh di assets/tiny).
 */
object Rootfs {
    private const val TAG = "TinyRootfs"

    /** Teks status untuk layar pemuatan. */
    @Volatile
    var statusText: String = ""

    /** Kemajuan tahap yang sedang berjalan (0..100), atau -1 bila tidak terukur. */
    @Volatile
    var percent: Int = -1

    // persen direset lebih dulu agar layar progres tidak sempat memadukan tahap baru dengan persen lama
    private fun step(name: String) {
        percent = -1
        statusText = name
    }

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
     * Berkas yang HARUS ada setelah `patch.tar.gz` diekstrak (`tiny/extra`): driver Vulkan Linux bawaan
     * (Turnip + ICD-nya), klien mikrofon virtual, dan jembatan getifaddrs.
     */
    private val PATCH_MARKERS = listOf(
        "tiny/extra/libvulkan_freedreno.so", "tiny/extra/freedreno_icd.aarch64.json",
        "tiny/extra/tiny_virtual_mic", "tiny/extra/getifaddrs_bridge_client_lib.so",
    )

    /** `patch.tar.gz` tidak ada di APK (CI gagal menyalinnya dari rilis tiny_container). */
    @Volatile
    var patchAssetMissing = false
        private set

    /**
     * Memastikan isi `patch.tar.gz` (folder `tiny`: extra, font, dst.) benar-benar ada di data aplikasi.
     * Dulu hanya diekstrak oleh setup_bootstrap.sh saat pemasangan pertama; kontainer yang dipasang oleh
     * APK tanpa patch itu (atau saat ekstraksi gagal tanpa suara) tidak pernah memperolehnya lagi, sehingga
     * `tiny/extra` hanya berisi libfakeinput.so: Vulkan jatuh ke llvmpipe, mikrofon dan getifaddrs hilang.
     * Dipanggil di setiap peluncuran lewat [initData]. Cukup memeriksa penanda; bila lengkap tidak
     * melakukan apa-apa. Berkas yang sudah ada tidak ditimpa (mis. driver hasil modifikasi pengguna).
     */
    fun ensurePatch(ctx: Context) {
        val data = File(TinyEnv.dataPath)
        if (PATCH_MARKERS.all { File(data, it).isFile }) { patchAssetMissing = false; return }
        val input = runCatching { ctx.assets.open("patch.tar.gz") }.getOrElse {
            patchAssetMissing = true
            Log.w(TAG, "tiny/extra tidak lengkap dan patch.tar.gz tidak ada di APK: ${it.message}")
            return
        }
        patchAssetMissing = false
        runCatching {
            val root = data.canonicalFile
            var written = 0
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input, 1 shl 16))).use { tar ->
                val buf = ByteArray(1 shl 16)
                var e = tar.nextTarEntry
                while (e != null) {
                    val rel = e.name.removePrefix("./").trimStart('/').trimEnd('/')
                    val out = File(root, rel)
                    val canon = (if (e.isSymbolicLink) out.parentFile ?: root else out).canonicalFile
                    val safe = rel.isNotEmpty() && rel.split('/').none { it == ".." } &&
                        (canon.path == root.path || canon.path.startsWith(root.path + File.separator))
                    if (safe) when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> if (!out.exists()) {
                            out.parentFile?.mkdirs()
                            Files.createSymbolicLink(out.toPath(), Paths.get(e.linkName))
                        }
                        e.isFile -> if (!out.exists()) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o ->
                                while (true) { val r = tar.read(buf); if (r < 0) break; o.write(buf, 0, r) }
                            }
                            out.setReadable(true, false)
                            if (e.mode and 0x49 != 0 || rel.startsWith("tiny/extra/") || rel.endsWith(".so")) out.setExecutable(true, false)
                            written++
                        }
                    }
                    e = tar.nextTarEntry
                }
            }
            Log.i(TAG, "patch.tar.gz: $written berkas diekstrak ke $root")
        }.onFailure { Log.w(TAG, "ekstraksi patch.tar.gz gagal: ${it.message}", it) }
        val still = PATCH_MARKERS.filter { !File(data, it).isFile }
        if (still.isNotEmpty()) Log.w(TAG, "masih hilang setelah ekstraksi: $still")
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

    /**
     * Mengekstrak rootfs (`xa*`, arsip tar.xz yang dipecah) ke containers/0. Dulu `cat xa* | tar` di
     * dalam skrip shell, sehingga tahap "Installing System Files" tidak punya persen. Sekarang bagian
     * xa* dialirkan dari Kotlin ke stdin tar: pipa hanya menampung 64 KB, jadi byte yang sudah
     * terkirim hampir persis sama dengan yang sudah dibaca tar (persen dari ukuran arsip terkompresi).
     * Sampai 99% saja selama berjalan; 100% baru terpasang bersama pindah ke tahap berikutnya.
     */
    private fun extractRootfs(data: String) {
        val parts = File(data).listFiles { f -> f.isFile && f.name.startsWith("xa") }?.sortedBy { it.name } ?: emptyList()
        val total = parts.sumOf { it.length() }
        val cmd = "$data/bin/proot --link2symlink sh -c \"$data/bin/tar x -J --delay-directory-restore --preserve-permissions -C containers/0\""
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(File(data)).redirectErrorStream(true)
        pb.environment().apply {
            put("PATH", "$data/bin:" + (get("PATH") ?: ""))
            put("LD_LIBRARY_PATH", "$data/lib")
            put("PROOT_TMP_DIR", "$data/proot_tmp")
            put("PROOT_LOADER", "$data/applib/libproot-loader.so")
            put("PROOT_LOADER_32", "$data/applib/libproot-loader32.so")
        }
        val p = pb.start()
        // keluaran (hanya galat, tanpa -v) harus tetap dibaca agar proses tidak macet
        val tail = ArrayDeque<String>()
        val drain = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) { if (tail.size >= 10) tail.removeFirst(); tail.addLast(line) }
                }
            }
        }.apply { start() }

        var fed = 0L
        try {
            p.outputStream.use { out ->
                val buf = ByteArray(1 shl 16)
                for (f in parts) f.inputStream().use { i ->
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        fed += n
                        if (total > 0) percent = (fed * 99 / total).toInt()
                    }
                }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "tar berhenti sebelum semua xa* terkirim ($fed/$total byte): ${e.message}")
        }
        val code = p.waitFor()
        drain.join(2000)
        if (code != 0) Log.w(TAG, "ekstraksi rootfs exit=$code: ${synchronized(tail) { tail.joinToString(" | ") }}")
    }

    private fun initForFirstTime(ctx: Context) {
        val data = TinyEnv.dataPath
        step("installingBootPackage")
        setupBootstrap(ctx)

        step("copyingContainerSystem")
        File(data, "containers/0/.l2s").mkdirs()
        // rootfs dipecah dengan `split` menjadi xa*, ditaruh di assets
        val parts = (ctx.assets.list("") ?: emptyArray()).filter { it.startsWith("xa") }.sorted()
        for ((i, name) in parts.withIndex()) {
            copyAsset(ctx, name, File(data, name))
            percent = (i + 1) * 100 / parts.size
        }

        step("installingContainerSystem")
        extractRootfs(data)
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
            step("reinstallingBootPackage")
            setupBootstrap(ctx)
            Cfg.reinstallBootstrap = false
        }
        ensureFakeInput(ctx)
        ensurePatch(ctx)
        return first
    }
}
