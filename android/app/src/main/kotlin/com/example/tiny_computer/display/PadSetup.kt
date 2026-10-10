package com.example.tiny_computer.display

import android.content.Context
import com.droiddeck.launcher.input.FakeInputWriter
import com.droiddeck.launcher.session.SessionState
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.TinyEnv
import java.io.File

/**
 * Menyiapkan controller palsu (evdev + hidraw) untuk guest, tanpa Steam. Hasilnya: variabel
 * lingkungan untuk libfakeinput.so dan opsi `--mount` proot. Port dari addControllerEnvironment
 * di SessionService.kt (DroidDeck), dengan bagian Steam Input/touch Steam dibuang.
 */
object PadSetup {
    class Result(val env: List<String>, val mounts: List<String>)

    fun sessionRoot(ctx: Context) = File(ctx.filesDir, "session")

    fun prepare(ctx: Context): Result {
        val root = sessionRoot(ctx)
        val fakeInputDir = File(root, "dev/input").apply { mkdirs() }
        FakeInputWriter.prepareRingSlots(fakeInputDir, 4)
        // pad virtual sesi lalu (event16+ dan ring tersembunyinya) sudah tidak ada
        fakeInputDir.listFiles()?.forEach { f ->
            val node = Regex("event(\\d+)").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (f.name.startsWith(".uinput-") || (node != null && node >= 16)) f.delete()
        }
        val env = ArrayList<String>()
        val mounts = ArrayList<String>()
        env += "FAKE_EVDEV_DIR=${fakeInputDir.path}"
        FakeInputWriter.getRingEnv(fakeInputDir)?.takeIf { it.isNotEmpty() }?.let { env += "FAKE_EVDEV_MEMFD_PATHS=$it" }
        // SDL mengenali pad lewat bus+vendor+product: identitas Xbox 360 memakai mapping standar.
        env += "FAKE_EVDEV_IDENTITY=xbox360"
        env += "FAKE_EVDEV_VIBRATION=1"
        env += "FAKE_EVDEV_UINPUT=1"
        mounts += "--mount=${fakeInputDir.path}:/dev/input"

        val deck = Cfg.padIdentity == "deck"
        val binds = if (deck) DeckSysfs.prepare(root, TinyEnv.containerDir()) else emptyList()
        SessionState.deckPad = binds.isNotEmpty()
        if (SessionState.deckPad) {
            mounts += binds
            env += "FAKE_EVDEV_DECK=1"
            env += "FAKE_DECK_SYSFS_LISTING=${DeckSysfs.listingDir(root).path}"
        } else {
            // tanpa hidraw Deck: SDL memakai node evdev saja
            env += "SDL_JOYSTICK_HIDAPI=0"
        }
        env += "SDL_JOYSTICK_DISABLE_UDEV=1"
        env += "SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1"
        env += "FAKE_EVDEV_LOG=1"
        env += "FAKE_EVDEV_LOG_FILE=${File(root, "pad.log").path}"
        SessionState.fakeInputDir = fakeInputDir
        return Result(env, mounts)
    }
}
