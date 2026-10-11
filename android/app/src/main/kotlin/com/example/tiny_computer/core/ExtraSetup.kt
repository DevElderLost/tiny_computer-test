package com.example.tiny_computer.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.example.tiny_computer.R
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Tahap tambahan setelah sistem terpasang dan kontainer menyala, sebelum desktop dibuka: paket yang
 * dibutuhkan Wayland dan logika mikrofon, memakai perintah yang sudah tertanam di aplikasi
 * (`sudo apt update && sudo apt install -y ...` seperti di [Launcher.guiCommand] dan [Launcher.micCommand]):
 *  - xwayland          : Xwayland untuk desktop di atas compositor Wayland
 *  - socat             : klien soket mikrofon (cadangan bila `tiny_virtual_mic` tidak ada)
 *  - pulseaudio-utils  : `pactl` / `pacat` untuk sink + source mikrofon virtual
 * Hanya paket yang BELUM terpasang yang dipasang, sehingga peluncuran berikutnya tidak butuh internet.
 */
object ExtraSetup {
    private const val TAG = "TinyExtras"
    private const val TIMEOUT_MS = 20 * 60 * 1000L

    // nama paket dan satu berkas penanda bila daftar dpkg tidak terbaca
    private val PACKAGES = listOf(
        "xwayland" to "usr/bin/Xwayland",
        "socat" to "usr/bin/socat",
        "pulseaudio-utils" to "usr/bin/pacat",
    )

    /** Paket yang belum ada di kontainer saat ini; dibaca langsung dari rootfs, tanpa menjalankan apa pun. */
    fun missing(): List<String> {
        val root = TinyEnv.containerDir()
        val installed = installedPackages(root)
        return PACKAGES.filter { (pkg, probe) ->
            if (installed != null) pkg !in installed
            else !Files.exists(File(root, probe).toPath(), LinkOption.NOFOLLOW_LINKS)
        }.map { it.first }
    }

    private fun installedPackages(root: File): Set<String>? {
        val status = File(root, "var/lib/dpkg/status")
        if (!status.isFile) return null
        return runCatching {
            val out = HashSet<String>()
            var name: String? = null
            status.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("Package: ") -> name = line.substring(9).trim()
                        line.startsWith("Status: ") -> if (line.endsWith(" installed")) name?.let { out.add(it) }
                    }
                }
            }
            out
        }.getOrNull()
    }

    fun internetActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Memasang paket yang kurang lewat terminal kontainer dan menunggu selesai. Mengembalikan null bila
     * semuanya sudah ada, atau pesan peringatan berbahasa Inggris untuk Beranda bila gagal.
     * Dipanggil di thread latar.
     */
    fun run(ctx: Context): String? {
        val need = missing()
        if (need.isEmpty()) return null
        Rootfs.percent = -1
        Rootfs.statusText = "installingExtras"
        if (!internetActive(ctx)) return ctx.getString(R.string.tc_extras_no_internet)

        // `$DATA_DIR/tiny` dimount ke ~/.local/share/tiny di guest: berkas status dibaca dari sisi Android
        val status = File(TinyEnv.dataPath, "tiny/setup.status")
        status.parentFile?.mkdirs()
        status.delete()
        TermSessions.forCurrent(ctx).writeLine(
            "rm -f ~/.local/share/tiny/setup.status; " +
                "(sudo apt update && sudo apt install -y ${need.joinToString(" ")}; " +
                "echo ${'$'}? > ~/.local/share/tiny/setup.status)",
        )
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        // tunggu sampai berkas berisi (echo membuat berkas kosong sesaat sebelum menulis)
        while (!(status.isFile && status.length() > 0)) {
            // shell kontainer mati di tengah jalan: tidak akan ada berkas status, jangan menunggu 20 menit
            if (TermSessions.peek()?.exitCode != null) return failure(ctx)
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "waktu habis menunggu apt")
                return failure(ctx)
            }
            Thread.sleep(500)
        }
        val code = runCatching { status.readText().trim().toInt() }.getOrDefault(-1)
        status.delete()
        Log.i(TAG, "apt selesai, kode=$code, masih kurang=${missing()}")
        return if (code == 0 && missing().isEmpty()) null else failure(ctx)
    }

    // gagal di tengah jalan biasanya karena koneksi putus; bedakan supaya pesannya tepat
    private fun failure(ctx: Context): String =
        ctx.getString(if (internetActive(ctx)) R.string.tc_extras_failed else R.string.tc_extras_no_internet)
}
