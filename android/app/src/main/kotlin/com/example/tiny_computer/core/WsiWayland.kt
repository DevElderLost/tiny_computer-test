package com.example.tiny_computer.core

import android.util.Log
import java.io.File

/**
 * Membuat aplikasi Vulkan memakai driver Vulkan (Turnip) LANGSUNG lewat WSI Wayland ke compositor, jalur yang
 * terbukti jalan di terminal: `vkcube --wsi wayland 2>&1 | head -20` -> "Selected GPU 0: Turnip Adreno".
 *
 * Tanpa itu `vkcube` memilih WSI xcb (Xwayland tidak punya DRI3) dan gagal dengan
 * "demo_select_physical_device: Assertion `!err' failed". Dipakai oleh Open Desktop ([Launcher.guiCommand]),
 * Shortcut aplikasi Linux dan Shortcut .exe Wine ([ShortcutRunner]).
 *
 *  - [install]: pembungkus `vkcube`/`vkcubepp` di `/usr/local/bin` kontainer (lebih dulu di PATH daripada
 *    /usr/bin, jadi berlaku di terminal, menu desktop, dan Shortcut) yang menambahkan `--wsi wayland`.
 *  - [probeForScript] / [probeForDesktop]: ringkasan `vulkaninfo` (20 baris pertama, seperti `| head -20`) sebagai
 *    tanda driver Vulkan benar-benar terlihat. Dibatasi `head` di pipa vulkaninfo yang singkat, BUKAN di pipa
 *    aplikasi: `app | head -20` mematikan aplikasi dengan SIGPIPE begitu keluarannya melewati 20 baris.
 */
object WsiWayland {
    private const val TAG = "TinyWsiWayland"
    private const val MARK = "# tiny-wsi-wayland"

    /** Program uji Vulkan yang memahami `--wsi <platform>`. Aplikasi lain tidak diberi argumen ini. */
    private val TOOLS = listOf("vkcube", "vkcubepp")

    /** Tulis shell dengan `@@` sebagai pengganti `$` (menghindari `${'$'}` di string mentah Kotlin). */
    private fun sh(text: String) = text.trimStart().replace("@@", "\$")

    private val WRAPPER = sh(
        """
#!/bin/sh
$MARK
# vkcube memilih WSI xcb secara bawaan; lewat Xwayland (tanpa DRI3) Turnip gagal. Pakai WSI Wayland ke compositor.
n=@@{0##*/}
real=
for d in /usr/bin /bin /usr/local/bin; do
  [ -x "@@d/@@n" ] && [ "@@d/@@n" != "@@0" ] && { real="@@d/@@n"; break; }
done
[ -n "@@real" ] || { echo "@@n: not found" >&2; exit 127; }
for a; do
  case "@@a" in --wsi|--wsi=*) exec "@@real" "@@@" ;; esac
done
rt=@@{XDG_RUNTIME_DIR:-/tmp}
wl=@@{WAYLAND_DISPLAY:-wayland-0}
if [ -S "@@rt/@@wl" ]; then
  export XDG_RUNTIME_DIR="@@rt" WAYLAND_DISPLAY="@@wl"
  # jalur WSI software hanya untuk X11 (Xwayland tanpa DRI3); di Wayland Turnip memakai dmabuf langsung
  unset MESA_VK_WSI_DEBUG
  exec "@@real" --wsi wayland "@@@"
fi
exec "@@real" "@@@"
""",
    ) + "\n"

    private fun binDir() = File(TinyEnv.containerDir(), "usr/local/bin")

    /** Memasang pembungkus; aman dipanggil berulang. Berkas milik paket/pengguna lain tidak ditimpa. */
    fun install() {
        runCatching {
            for (n in TOOLS) {
                if (!File(TinyEnv.containerDir(), "usr/bin/$n").exists()) continue // vulkan-tools belum terpasang
                val f = File(binDir(), n)
                if (f.exists()) {
                    val old = f.readText()
                    if (!old.contains(MARK) || old == WRAPPER) continue
                }
                binDir().mkdirs()
                f.writeText(WRAPPER)
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
        }.onFailure { Log.w(TAG, "gagal memasang pembungkus WSI Wayland", it) }
    }

    /**
     * Untuk skrip Shortcut (keluarannya sudah dialihkan ke `~/.vnc/shortcut-<id>.log`): 20 baris pertama
     * ringkasan Vulkan di latar belakang. Matikan dengan `TINY_VK_PROBE=0` (Pengaturan shortcut > Env).
     */
    fun probeForScript(): String = sh(
        """
if command -v vulkaninfo >/dev/null 2>&1 && [ "@@{TINY_VK_PROBE:-1}" != 0 ]; then
  (echo "[tiny] vulkan probe: WAYLAND_DISPLAY=@@{WAYLAND_DISPLAY:-unset}"; vulkaninfo --summary 2>&1 | grep -E 'deviceName|driverName|ERROR|failed' | head -20) &
fi
""",
    ) + "\n"

    /** Untuk Open Desktop: baris yang sama, tampil di terminal dengan awalan `[tiny] vulkan:`. [env] = variabel sesi. */
    fun probeForDesktop(env: String): String = sh(
        """
command -v vulkaninfo >/dev/null 2>&1 && (${env}vulkaninfo --summary 2>&1 | grep -E 'deviceName|driverName|ERROR|failed' | head -20 | sed 's/^[[:space:]]*/[tiny] vulkan: /' &)
""",
    )
}
