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
 *  - libxkbregistry0   : pustaka registri keymap xkb
 *  - libasound2-plugins: plugin ALSA `pulse` (aplikasi ALSA/PortAudio seperti Audacity bisa merekam dari
 *                        mikrofon virtual PulseAudio dan memutar lewat pulse)
 *  - alsa-utils        : `aplay` / `arecord` / `alsamixer` untuk menguji suara
 *  - libxkbcommon-x11-0: pustaka keyboard xkb untuk klien X11 (winit/Rust seperti PhotoCraft, Qt xcb). Tanpa ini aplikasi
 *                        keluar sendiri sebelum membuat window: layar tetap hitam dan sesi Shortcut berakhir.
 * Semuanya dipasang ONLINE lewat apt (butuh internet sekali, saat instalasi pertama). Hanya paket yang
 * BELUM terpasang yang dipasang, sehingga peluncuran berikutnya tidak butuh internet.
 *
 * Berlaku sama untuk semua Display server (Xwayland, Wayland, X++): X++ tidak memakai Xwayland untuk desktop-nya,
 * tetapi sesi Shortcut dan Flatpak tetap membutuhkan paket yang sama, jadi tahap ini tidak boleh dilewati.
 * [guardScript] adalah jaring pengaman di sisi shell kontainer bila tahap ini terlewat.
 */
object ExtraSetup {
    private const val TAG = "TinyExtras"
    private const val TIMEOUT_MS = 20 * 60 * 1000L

    // nama paket dan satu berkas penanda bila daftar dpkg tidak terbaca
    private val PACKAGES = listOf(
        "xwayland" to "usr/bin/Xwayland",
        "socat" to "usr/bin/socat",
        "pulseaudio-utils" to "usr/bin/pacat",
        "libxkbregistry0" to "usr/lib/aarch64-linux-gnu/libxkbregistry.so.0",
        "libasound2-plugins" to "usr/lib/aarch64-linux-gnu/alsa-lib/libasound_module_pcm_pulse.so",
        "alsa-utils" to "usr/bin/aplay",
        "libxkbcommon-x11-0" to "usr/lib/aarch64-linux-gnu/libxkbcommon-x11.so.0",
    )

    /** Paket yang belum ada di kontainer saat ini; dibaca langsung dari rootfs, tanpa menjalankan apa pun. */
    fun missing(): List<String> {
        // sistem hasil import (bukan rootfs Tiny) tidak punya desktop Tiny: tidak ada yang perlu dipasang
        if (Containers.isGeneric(TinyEnv.currentContainer)) return emptyList()
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

    /**
     * Ada jaringan aktif yang berkemampuan internet. Penanda VALIDATED sengaja TIDAK disyaratkan: ROM tanpa layanan Google
     * (atau jaringan dengan DNS pribadi / VPN / portal) tidak pernah menandai jaringan sebagai tervalidasi walau apt
     * bisa mengunduh. Syarat lama membuat tahap ini berhenti dengan pesan "Internet is not active" tanpa pernah mencoba.
     * Bila memang tidak ada koneksi, apt sendiri gagal dan [failure] yang melaporkannya.
     */
    fun internetActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Skrip shell (dijalankan di terminal kontainer) yang memasang paket yang masih kurang, memeriksa berkas penanda tiap
     * paket di [PACKAGES]. Dipasang di awal perintah desktop ([Launcher.guiCommand] dan guiCommandXpp) sebagai jaring
     * pengaman bila [run] terlewat atau gagal. Tidak melakukan apa pun bila semuanya sudah ada.
     */
    fun guardScript(): String {
        val sb = StringBuilder("_tiny_need=\"\"\n")
        for ((pkg, probe) in PACKAGES) {
            sb.append("[ -e /$probe ] || [ -L /$probe ] || _tiny_need=\"\$_tiny_need $pkg\"\n")
        }
        sb.append("if [ -n \"\$_tiny_need\" ]; then\n")
        sb.append("  echo \"[tiny] memasang paket pendukung:\$_tiny_need\"\n")
        sb.append("  export DEBIAN_FRONTEND=noninteractive\n")
        sb.append("  timeout 300 sudo apt update; timeout 1200 sudo apt install -y \$_tiny_need\n")
        sb.append("fi\n")
        sb.append("unset _tiny_need\n")
        return sb.toString()
    }

    /**
     * Memasang paket yang kurang lewat terminal kontainer (`sudo apt update && sudo apt install -y ...`) dan menunggu
     * selesai. Mengembalikan null bila semuanya sudah ada, atau pesan peringatan berbahasa Inggris untuk Beranda bila
     * gagal (internet mati, koneksi putus, atau paket tidak ditemukan). Dipanggil di thread latar.
     */
    fun run(ctx: Context): String? {
        val need = missing()
        if (need.isEmpty()) return null
        Rootfs.percent = -1
        Rootfs.statusText = "installingExtras"

        if (!internetActive(ctx)) return ctx.getString(R.string.tc_extras_no_internet)
        // `;` bukan `&&`: apt update bisa berstatus gagal hanya karena satu mirror, padahal daftar paket sudah cukup untuk install
        val code = runInTerminal(ctx, "sudo apt update; sudo apt install -y ${need.joinToString(" ")}")
        Log.i(TAG, "apt kode=$code, diminta=$need, masih kurang=${missing()}, xpp=${Cfg.desktopXpp}")
        if (code == -2) return failure(ctx)
        return if (missing().isEmpty()) null else failure(ctx)
    }

    /** Menjalankan [command] di terminal kontainer dan menunggu kode keluarnya; -2 bila shell mati / waktu habis. */
    private fun runInTerminal(ctx: Context, command: String): Int {
        // `$DATA_DIR/tiny` dimount ke ~/.local/share/tiny di guest: berkas status dibaca dari sisi Android
        val status = File(TinyEnv.dataPath, "tiny/setup.status")
        status.parentFile?.mkdirs()
        status.delete()
        TermSessions.forCurrent(ctx).writeLine(
            "rm -f ~/.local/share/tiny/setup.status; " +
                "($command; echo ${'$'}? > ~/.local/share/tiny/setup.status)",
        )
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        // tunggu sampai berkas berisi (echo membuat berkas kosong sesaat sebelum menulis)
        while (!(status.isFile && status.length() > 0)) {
            // shell kontainer mati di tengah jalan: tidak akan ada berkas status, jangan menunggu 20 menit
            if (TermSessions.peek()?.exitCode != null) return -2
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "waktu habis menunggu pemasangan paket")
                return -2
            }
            Thread.sleep(500)
        }
        val code = runCatching { status.readText().trim().toInt() }.getOrDefault(-1)
        status.delete()
        return code
    }

    // gagal di tengah jalan biasanya karena koneksi putus; bedakan supaya pesannya tepat
    private fun failure(ctx: Context): String =
        ctx.getString(if (internetActive(ctx)) R.string.tc_extras_failed else R.string.tc_extras_no_internet)
}
