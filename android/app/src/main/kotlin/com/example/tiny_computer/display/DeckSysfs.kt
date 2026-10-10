package com.example.tiny_computer.display

import android.system.Os
import android.util.Log
import java.io.File

/**
 * Menggambarkan pad sebagai controller Steam Deck (28de:1205) lewat hidraw, tanpa Steam.
 * Port dari SteamDeckPad.kt (DroidDeck) dengan bind proot milik Tiny Computer. libfakeinput
 * menyajikan node /dev/hidraw16; yang dibuat di sini adalah sysfs/udev yang dicari SDL/libudev.
 */
object DeckSysfs {
    private const val TAG = "DeckSysfs"
    private const val MAJOR = 240
    private const val MINOR = 16
    private const val NODE = "hidraw$MINOR"
    private const val GUEST_DEVICES = "/sys/devices/droiddeck"
    private const val USB = "$GUEST_DEVICES/usb1"
    private const val INTERFACE = "$USB/1-1:1.2"
    private const val HID = "$INTERFACE/0003:28DE:1205.0001"
    private const val HIDRAW = "$HID/hidraw/$NODE"
    private const val SERIAL = "TINYDECK0001"

    /** InputPlumber CONTROLLER_DESCRIPTOR, dijawab libfakeinput untuk HIDIOCGRDESC. */
    private val REPORT_DESCRIPTOR = intArrayOf(
        0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
        0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    fun listingDir(root: File) = File(root, "sys/deck/listing")

    /**
     * Tulis pohon sysfs dan kembalikan argumen `--mount=host:guest` proot, atau kosong jika gagal.
     * [root] = folder sesi; [containerDir] = rootfs (untuk /run/udev/data).
     */
    fun prepare(root: File, containerDir: File): List<String> {
        val base = File(root, "sys/deck")
        val devices = File(base, "devices")
        val hidrawClass = File(base, "class-hidraw")
        val devChar = File(base, "dev-char")
        return try {
            base.deleteRecursively()
            fun dir(guest: String) = File(devices, guest.removePrefix("$GUEST_DEVICES/")).apply { mkdirs() }
            fun write(guest: String, name: String, text: String) = File(dir(guest), name).writeText(text)
            fun link(file: File, target: String) {
                file.parentFile?.mkdirs()
                file.delete()
                Os.symlink(target, file.path)
            }

            write(USB, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1205/100\nTYPE=0/0/0\nBUSNUM=001\nDEVNUM=002\n")
            write(USB, "idVendor", "28de\n")
            write(USB, "idProduct", "1205\n")
            write(USB, "bcdDevice", "0100\n")
            write(USB, "manufacturer", "Valve Software\n")
            write(USB, "product", "Steam Deck Controller\n")
            write(USB, "serial", "$SERIAL\n")
            link(File(dir(USB), "subsystem"), "/sys/bus/usb")

            write(INTERFACE, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1205/100\nINTERFACE=3/0/0\n")
            write(INTERFACE, "bInterfaceNumber", "02\n")
            write(INTERFACE, "bInterfaceClass", "03\n")
            link(File(dir(INTERFACE), "subsystem"), "/sys/bus/usb")

            write(HID, "uevent", "DRIVER=hid-steam\nHID_ID=0003:000028DE:00001205\n" +
                "HID_NAME=Valve Software Steam Deck Controller\nHID_PHYS=usb-tiny-1/input2\n" +
                "HID_UNIQ=$SERIAL\nMODALIAS=hid:b0003g0001v000028DEp00001205\n")
            File(dir(HID), "report_descriptor").writeBytes(REPORT_DESCRIPTOR)
            link(File(dir(HID), "subsystem"), "/sys/bus/hid")

            write(HIDRAW, "uevent", "MAJOR=$MAJOR\nMINOR=$MINOR\nDEVNAME=$NODE\n")
            write(HIDRAW, "dev", "$MAJOR:$MINOR\n")
            link(File(dir(HIDRAW), "subsystem"), "/sys/class/hidraw")
            link(File(dir(HIDRAW), "device"), HID)

            hidrawClass.mkdirs()
            link(File(hidrawClass, NODE), HIDRAW)

            // Hanya nama yang penting: libudev membaca daftar subsistem lalu membuka /sys/class/hidraw sendiri.
            val listing = listingDir(root)
            for (n in listOf("sys/bus", "sys/class", "sys/devices", "class/hidraw", "bus")) File(listing, n).mkdirs()

            // proot tidak bisa mem-bind path berisi ':' (240:16), jadi seluruh /sys/dev/char diganti folder ini.
            devChar.mkdirs()
            link(File(devChar, "$MAJOR:$MINOR"), HIDRAW)

            val udevData = File(containerDir, "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")

            Log.i(TAG, "/dev/$NODE digambarkan sebagai controller Steam Deck (28de:1205)")
            listOf(
                "--mount=${devices.path}:$GUEST_DEVICES",
                "--mount=${hidrawClass.path}:/sys/class/hidraw",
                "--mount=${devChar.path}:/sys/dev/char",
            )
        } catch (e: Exception) {
            Log.w(TAG, "tidak bisa membuat sysfs Deck: $e")
            emptyList()
        }
    }
}
