package com.example.tiny_computer.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Menyiapkan Wine arm64ec yang sudah dipasang ([WineContents]) di Debian trixie arm64 supaya benar-benar jalan:
 *  1. tulis env.sh + shim `wine`/`wineserver`/`winecfg`/... ke /usr/local/bin kontainer, dan taruh `.so` FEX di lib Wine;
 *  2. pasang pustaka sistem yang dibutuhkan binari Wine lewat apt (hanya yang belum ada; butuh internet sekali);
 *  3. buat prefix `~/.wine` dengan `wineboot -u`;
 *  4. terapkan FEX/DXVK/VKD3D terpilih ke prefix (system32 / syswow64).
 * Langkah 2-3 dijalankan di terminal kontainer seperti [ExtraSetup], jadi kontainer harus sudah menyala.
 */
object WineSetup {
    private const val TAG = "WineSetup"

    /** Pustaka wajib (Wine tidak mau start tanpa ini). Nama paket Debian trixie (transisi t64). */
    private val REQUIRED = listOf(
        "libfreetype6" to "libfreetype.so.6", "libfontconfig1" to "libfontconfig.so.1",
        "libx11-6" to "libX11.so.6", "libxext6" to "libXext.so.6", "libxrandr2" to "libXrandr.so.2",
        "libxi6" to "libXi.so.6", "libxcursor1" to "libXcursor.so.1", "libxfixes3" to "libXfixes.so.3",
        "libxrender1" to "libXrender.so.1", "libxcomposite1" to "libXcomposite.so.1",
        "libxinerama1" to "libXinerama.so.1", "libxkbcommon0" to "libxkbcommon.so.0",
        "libvulkan1" to "libvulkan.so.1", "libasound2t64" to "libasound.so.2", "libpulse0" to "libpulse.so.0",
        "libgnutls30t64" to "libgnutls.so.30", "libunwind8" to "libunwind.so.8", "libsdl2-2.0-0" to "libSDL2-2.0.so.0",
        // winewayland.so
        "libwayland-client0" to "libwayland-client.so.0", "libwayland-cursor0" to "libwayland-cursor.so.0",
    )

    /** Pustaka tambahan (fitur Wine: USB, kamera, cetak, GStreamer, dll). Gagal memasangnya tidak menggagalkan setup. */
    private val OPTIONAL = listOf(
        "libxxf86vm1", "libxkbcommon-x11-0", "libgl1", "libglu1-mesa", "libopenal1", "libusb-1.0-0", "libudev1",
        "libdbus-1-3", "libcups2t64", "libpcap0.8t64", "libv4l-0t64", "libpng16-16t64", "libjpeg62-turbo", "libtiff6",
        "libxml2", "libxslt1.1", "libgstreamer1.0-0", "libgstreamer-plugins-base1.0-0", "libodbc2", "libsane1",
        "libgphoto2-6t64", "libldap2", "libkrb5-3", "libgssapi-krb5-2", "libcap2", "fonts-liberation",
        "cabextract",
    )

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    /** Tahap berjalan: "writing", "deps", "prefix", "applying". */
    var stage by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private fun installedPackages(): Set<String>? {
        val status = File(TinyEnv.containerDir(), "var/lib/dpkg/status")
        if (!status.isFile) return null
        return runCatching {
            val out = HashSet<String>()
            var name: String? = null
            status.bufferedReader().useLines { lines ->
                for (line in lines) when {
                    line.startsWith("Package: ") -> name = line.substring(9).trim()
                    line.startsWith("Status: ") -> if (line.endsWith(" installed")) name?.let { out.add(it) }
                }
            }
            out
        }.getOrNull()
    }

    private fun missing(all: List<String>): List<String> {
        val have = installedPackages() ?: return all
        return all.filter { it !in have }
    }

    /**
     * Menjalankan [command] di shell kontainer dan menunggu kodenya lewat berkas status di `~/.local/share/tiny`
     * (terlihat dari Android di `$DATA_DIR/tiny`). Mengembalikan kode keluar, atau null bila shell mati / waktu habis.
     */
    private fun guestRun(ctx: Context, command: String, timeoutMs: Long): Int? {
        val status = File(TinyEnv.dataPath, "tiny/wine-setup.status")
        status.parentFile?.mkdirs()
        status.delete()
        TermSessions.forCurrent(ctx).writeLine(
            "rm -f ~/.local/share/tiny/wine-setup.status; ($command; echo \$? > ~/.local/share/tiny/wine-setup.status)",
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!(status.isFile && status.length() > 0)) {
            if (TermSessions.peek()?.exitCode != null) return null
            if (SystemClock.elapsedRealtime() > deadline) { Log.w(TAG, "waktu habis: $command"); return null }
            Thread.sleep(500)
        }
        val code = runCatching { status.readText().trim().toInt() }.getOrDefault(-1)
        status.delete()
        return code
    }

    fun start(ctx: Context) {
        if (phase == Phase.RUNNING) return
        val app = ctx.applicationContext
        phase = Phase.RUNNING
        message = null
        Thread({
            val err = try { execute(app) } catch (t: Throwable) {
                Log.e(TAG, "setup Wine gagal", t)
                t.message ?: t.javaClass.simpleName
            }
            if (err == null) { phase = Phase.DONE } else { message = err; phase = Phase.FAILED }
            stage = ""
        }, "tiny-wine-setup").start()
    }

    private fun execute(ctx: Context): String? {
        if (!Launcher.isStarted) return "Start the container first (Open desktop), then run this again."

        stage = "writing"
        WineContents.writeRuntime()?.let { return it }

        stage = "deps"
        val need = missing(REQUIRED.map { it.first })
        val extra = missing(OPTIONAL)
        if (need.isNotEmpty() || extra.isNotEmpty()) {
            if (!ExtraSetup.internetActive(ctx)) {
                if (need.isNotEmpty()) return "Internet is needed once to install: ${need.joinToString(", ")}."
            } else {
                // satu per satu: nama paket yang tidak ada di mirror tidak menggagalkan sisanya
                val list = (need + extra).joinToString(" ")
                val cmd = "sudo apt-get update; for p in $list; do sudo apt-get install -y --no-install-recommends \$p || echo \"skip \$p\"; done"
                guestRun(ctx, cmd, 40 * 60_000L) ?: return "Installing libraries was interrupted (container closed or timed out)."
                val still = missing(REQUIRED.map { it.first })
                if (still.isNotEmpty()) return "Could not install: ${still.joinToString(", ")}. Check internet and try again."
            }
        }

        stage = "prefix"
        if (!WineContents.prefixReady()) {
            // HODLL (FEX) belum berguna sebelum dll-nya disalin ke prefix; wineboot native arm64ec tidak membutuhkannya
            val boot = "unset HODLL; wineboot -u; wineserver -w"
            guestRun(ctx, ". ~/.local/share/tiny/wine/env.sh; $boot", 20 * 60_000L)
            if (!WineContents.prefixReady()) {
                return "wineboot did not create the prefix. Open Terminal and run: wineboot -u (see the error it prints)."
            }
        }

        stage = "applying"
        applyDisplayDriver(ctx)
        WineContents.applyAll()
        return null
    }

    /**
     * Menulis driver tampilan Wine ke registri prefix: "wayland,x11" (Wayland dulu, X11 cadangan bila compositor
     * belum menyala) atau "x11". Memakai shell kontainer, jadi hanya jalan saat kontainer menyala dan prefix ada.
     */
    private fun applyDisplayDriver(ctx: Context) {
        val value = WineContents.displayDriverValue()
        val cmd = ". ~/.local/share/tiny/wine/env.sh; unset HODLL; " +
            "wine reg add 'HKCU\\Software\\Wine\\Drivers' /v Graphics /d $value /f; wineserver -w"
        guestRun(ctx, cmd, 5 * 60_000L)
    }

    /** Dipanggil UI saat pengguna mengganti driver tampilan Wine; bila belum bisa dijalankan, berlaku di "Run" berikutnya. */
    fun applyDisplayAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            if (phase == Phase.RUNNING || !Launcher.isStarted || !WineContents.prefixReady()) return@Thread
            runCatching { applyDisplayDriver(app) }
        }, "tiny-wine-display").start()
    }
}
