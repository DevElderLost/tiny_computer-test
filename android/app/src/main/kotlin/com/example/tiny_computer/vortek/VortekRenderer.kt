package com.example.tiny_computer.vortek

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.TurnipLoader
import com.example.tiny_computer.xpp.NativeX11
import com.example.tiny_computer.xpp.XppHost
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vortek (dari Winlator): ICD Vulkan di guest (libvulkan_vortek.so, glibc) meneruskan perintah Vulkan lewat soket
 * unix `/tmp/.vortek/V0` ke renderer native di proses aplikasi (libvortekrenderer.so, bionic), yang menjalankannya di
 * driver Vulkan Android (Turnip lewat adrenotools, driver yang sama dengan compositor) dan menulis hasilnya ke
 * AHardwareBuffer window X++ ([NativeX11.nativeVortekWindowBuffer]). Hanya jalan dengan Display server X++.
 *
 * Nama field [Options] dan method callback dibaca native lewat nama (lihat vortek/src/vk_context.c): jangan diubah.
 */
class VortekRenderer private constructor(private val options: Options) {
    class Options {
        @JvmField var vkMaxVersion: Int = (1 shl 22) or (3 shl 12) or 128 // 1.3.128
        @JvmField var maxDeviceMemory: Short = 0
        @JvmField var imageCacheSize: Short = 256
        @JvmField var resourceMemoryType: Byte = 0
        @JvmField var presentMode: Byte = 1 // MAILBOX; urutan: IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED
        @JvmField var maxFramerate: Short = 0
        @JvmField var exposedDeviceExtensions: Array<String>? = null
        @JvmField var libvulkanPath: String? = null
    }

    // ---- JNI (libvortekrenderer.so) ----
    private external fun createVkContext(clientFd: Int, options: Options): Long
    private external fun destroyVkContext(contextPtr: Long)
    private external fun initVulkanWrapper(nativeLibraryDir: String, libvulkanPath: String?)
    private external fun handleExtraDataRequest(contextPtr: Long, requestCode: Int, requestLength: Int): Boolean

    // ---- Callback dari native (nama dan signature dicari lewat GetMethodID) ----
    private fun getWindowWidth(windowId: Int): Short = NativeX11.nativeVortekWindowWidth(XppHost.nativeHandle, windowId).toShort()
    private fun getWindowHeight(windowId: Int): Short = NativeX11.nativeVortekWindowHeight(XppHost.nativeHandle, windowId).toShort()
    private fun getWindowHardwareBuffer(windowId: Int, bgra: Boolean): Long =
        NativeX11.nativeVortekWindowBuffer(XppHost.nativeHandle, windowId, bgra)
    private fun updateWindowContent(windowId: Int) = NativeX11.nativeVortekWindowUpdate(XppHost.nativeHandle, windowId)

    @Volatile private var server: LocalServerSocket? = null
    private val clients = HashSet<LocalSocket>()

    private fun start(ctx: Context, socket: File) {
        initVulkanWrapper(ctx.applicationInfo.nativeLibraryDir, options.libvulkanPath)
        socket.parentFile?.mkdirs()
        socket.delete()
        val bound = LocalSocket()
        bound.bind(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.setReadable(true, false); socket.setWritable(true, false)
        val s = LocalServerSocket(bound.fileDescriptor)
        server = s
        Thread({
            while (server === s) {
                val c = try { s.accept() } catch (_: IOException) { break }
                synchronized(clients) { clients += c }
                vlog("klien terhubung")
                Thread({ serve(c) }, "Vortek-client").start()
            }
            runCatching { bound.close() }
        }, "Vortek-accept").start()
    }

    /** Satu klien = satu VkContext. Protokol sama dengan VortekRendererComponent.handleRequest: header 8 byte (kode, panjang). */
    private fun serve(client: LocalSocket) {
        var contextPtr = 0L
        var pfd: ParcelFileDescriptor? = null
        try {
            val input = client.inputStream
            val header = ByteArray(8)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                var n = 0
                while (n < 8) { val r = input.read(header, n, 8 - n); if (r < 0) return; n += r }
                val code = bb.getInt(0)
                val length = bb.getInt(4)
                if (contextPtr == 0L || code != REQUEST_CODE_CREATE_CONTEXT) vlog("header code=$code length=$length")
                if (code == REQUEST_CODE_CREATE_CONTEXT) {
                    // fd harus tetap terbuka selama context hidup (native tidak menutupnya)
                    pfd = ParcelFileDescriptor.dup(client.fileDescriptor)
                    vlog("createVkContext fd=${pfd.fd} driver=${options.libvulkanPath ?: "sistem"}")
                    contextPtr = createVkContext(pfd.fd, options)
                    vlog("createVkContext -> $contextPtr")
                    if (contextPtr <= 0L) { vlog("createVkContext gagal (ring buffer/ashmem/send_fds)"); return }
                } else if (code > Short.MAX_VALUE && (code shr 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
                    if (contextPtr == 0L || !handleExtraDataRequest(contextPtr, code and 0xffff, length)) {
                        vlog("handleExtraDataRequest gagal code=${code and 0xffff} length=$length"); return
                    }
                }
            }
        } catch (e: IOException) {
            vlog("klien putus: ${e.message}")
        } finally {
            vlog("klien selesai (context=$contextPtr)")
            if (contextPtr > 0L) runCatching { destroyVkContext(contextPtr) }
            runCatching { pfd?.close() }
            runCatching { client.close() }
            synchronized(clients) { clients -= client }
        }
    }

    private fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val TAG = "Vortek"

        /** Log ke logcat dan ke Download/Wayland-logs/vortek.log (agar bisa dikirim tanpa adb). */
        fun vlog(msg: String) {
            Log.i(TAG, msg)
            runCatching {
                val dir = File(com.example.tiny_computer.core.LaunchLog.DIR).apply { mkdirs() }
                File(dir, "vortek.log").appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
            }
        }
        private const val REQUEST_CODE_CREATE_CONTEXT = 1
        private const val REQUEST_CODE_SEND_EXTRA_DATA = 2
        // Soket di dalam kontainer: guest membukanya sebagai /tmp/.vortek/V0 (path ini ditanam di libvulkan_vortek.so aset).
        const val GUEST_ICD = "/usr/share/vulkan/icd.d/vortek_icd.aarch64.json"

        init { System.loadLibrary("vortekrenderer") }

        @Volatile private var instance: VortekRenderer? = null
        private var startedDriver: String? = null

        fun socketFile() = File(File(TinyEnv.runtimeDir(), ".vortek"), "V0")

        /** Salin ICD + libvulkan_vortek.so ke rootfs kontainer aktif (hanya bila berbeda). */
        fun installGuest(ctx: Context) {
            val root = TinyEnv.containerDir()
            fun copy(asset: String, dest: File) {
                val bytes = ctx.assets.open(asset).use { it.readBytes() }
                if (dest.isFile && dest.length() == bytes.size.toLong() && dest.readBytes().contentEquals(bytes)) return
                dest.parentFile?.mkdirs(); dest.writeBytes(bytes); dest.setReadable(true, false)
            }
            copy("vortek/libvulkan_vortek.so", File(root, "usr/lib/libvulkan_vortek.so"))
            copy("vortek/vortek_icd.aarch64.json", File(root, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json"))
        }

        /**
         * Opsi dari pengaturan. Driver host Vortek dipilih sendiri ([Cfg.vortekDriver]), terpisah dari driver compositor.
         * Kosong = libvulkan sistem, sama dengan bawaan Winlator ("System"); selain itu driver adrenotools hasil impor/bawaan APK.
         * Driver pilihan yang hilang/rusak jatuh ke driver sistem.
         */
        private fun buildOptions(ctx: Context): Pair<Options, String> {
            val o = Options()
            val id = Cfg.vortekDriver
            if (id.isNotEmpty() && !id.contains('/') && !id.contains("..")) {
                val loader = TurnipLoader(ctx)
                val lib = loader.libraryName(id)
                if (lib != null && File(File(ctx.filesDir, "graphics_driver/$id"), lib).isFile) o.libvulkanPath = loader.driverPath(id) + lib
                else Log.w(TAG, "driver Vortek '$id' tidak bisa dipakai, memakai driver sistem")
            }
            return o to (if (o.libvulkanPath != null) id else "")
        }

        @Synchronized fun ensureStarted(ctx: Context): Boolean {
            return try {
                installGuest(ctx)
                val (opts, driver) = buildOptions(ctx)
                if (instance != null && startedDriver == driver) return true
                instance?.stop()
                val r = VortekRenderer(opts)
                r.start(ctx.applicationContext, socketFile())
                instance = r; startedDriver = driver
                vlog("Vortek siap di ${socketFile()} (driver host: ${driver.ifEmpty { "libvulkan sistem" }})")
                true
            } catch (t: Throwable) {
                vlog("Vortek gagal start: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "Vortek gagal start", t)
                false
            }
        }

        @Synchronized fun stop() { instance?.stop(); instance = null; startedDriver = null }
    }
}
