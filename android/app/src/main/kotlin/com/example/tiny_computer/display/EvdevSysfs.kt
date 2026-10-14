package com.example.tiny_computer.display

import android.system.Os
import android.util.Log
import java.io.File

/**
 * Sysfs/udev palsu untuk pad evdev (identitas Xbox 360, slot 0 = /dev/input/event0).
 *
 * libudev (dipakai SDL dan winebus milik Wine) tidak membaca /dev/input, melainkan menelusuri
 * /sys/class/input lalu membaca `uevent`, `subsystem` dan /run/udev/data. libfakeinput hanya
 * menyajikan node-nya; pohon sysfs yang dicari libudev dibuat di sini dan di-bind ke guest.
 * Pasangannya adalah FAKE_UEVENT_SOCKET=1, supaya monitor hotplug libudev tidak gagal.
 * Pad virtual dari /dev/uinput (event16 ke atas) tidak dicakup: node itu dibuat dinamis.
 */
object EvdevSysfs {
    private const val TAG = "EvdevSysfs"
    private const val SLOT = 0
    private const val MAJOR = 13
    private const val MINOR = 64 + SLOT // FAKE_INPUT_EVENT_MINOR_BASE + nomor event
    private const val EVENT = "event$SLOT"
    private const val INPUT = "input$SLOT"
    private const val GUEST_DEVICES = "/sys/devices/droiddeck-input"
    private const val INPUT_DEV = "$GUEST_DEVICES/virtual/input/$INPUT"
    private const val EVENT_DEV = "$INPUT_DEV/$EVENT"

    // Sama dengan yang dijawab libfakeinput untuk EVIOCGID/EVIOCGNAME/EVIOCGPHYS/EVIOCGUNIQ.
    private const val NAME = "Xbox 360 Controller ($SLOT)"
    private const val PHYS = "usb-fakeinput/input$SLOT"
    private const val UNIQ = "0000000000%02d"

    /**
     * Tulis pohon sysfs dan kembalikan argumen `--mount=host:guest` proot, atau kosong jika gagal.
     * [root] = folder sesi; [containerDir] = rootfs (untuk /run/udev/data).
     */
    fun prepare(root: File, containerDir: File): List<String> {
        val base = File(root, "sys/evdev")
        val devices = File(base, "devices")
        val inputClass = File(base, "class-input")
        val devChar = File(base, "dev-char")
        return try {
            base.deleteRecursively()
            fun dir(guest: String) = File(devices, guest.removePrefix("$GUEST_DEVICES/")).apply { mkdirs() }
            fun write(guest: String, name: String, text: String) {
                val file = File(dir(guest), name)
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            fun link(file: File, target: String) {
                file.parentFile?.mkdirs()
                file.delete()
                Os.symlink(target, file.path)
            }

            // Perangkat input (induk node event): identitas dibaca winebus/SDL dari sini.
            val uniq = UNIQ.format(SLOT)
            write(INPUT_DEV, "uevent", "PRODUCT=3/45e/28e/110\nNAME=\"$NAME\"\nPHYS=\"$PHYS\"\nUNIQ=\"$uniq\"\nPROP=0\n")
            write(INPUT_DEV, "name", "$NAME\n")
            write(INPUT_DEV, "phys", "$PHYS\n")
            write(INPUT_DEV, "uniq", "$uniq\n")
            write(INPUT_DEV, "id/bustype", "0003\n")
            write(INPUT_DEV, "id/vendor", "045e\n")
            write(INPUT_DEV, "id/product", "028e\n")
            write(INPUT_DEV, "id/version", "0110\n")
            link(File(dir(INPUT_DEV), "subsystem"), "/sys/class/input")

            // Node event0 itu sendiri.
            write(EVENT_DEV, "uevent", "MAJOR=$MAJOR\nMINOR=$MINOR\nDEVNAME=input/$EVENT\n")
            write(EVENT_DEV, "dev", "$MAJOR:$MINOR\n")
            link(File(dir(EVENT_DEV), "subsystem"), "/sys/class/input")

            inputClass.mkdirs()
            link(File(inputClass, INPUT), INPUT_DEV)
            link(File(inputClass, EVENT), EVENT_DEV)

            // proot tidak bisa mem-bind path berisi ':' (13:64), jadi seluruh /sys/dev/char diganti folder ini.
            devChar.mkdirs()
            link(File(devChar, "$MAJOR:$MINOR"), EVENT_DEV)

            val udevData = File(containerDir, "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\nE:ID_BUS=usb\n")

            Log.i(TAG, "/dev/input/$EVENT digambarkan lewat sysfs palsu (045e:028e)")
            listOf(
                "--mount=${devices.path}:$GUEST_DEVICES",
                "--mount=${inputClass.path}:/sys/class/input",
                "--mount=${devChar.path}:/sys/dev/char",
            )
        } catch (e: Exception) {
            Log.w(TAG, "tidak bisa membuat sysfs evdev: $e")
            emptyList()
        }
    }
}
