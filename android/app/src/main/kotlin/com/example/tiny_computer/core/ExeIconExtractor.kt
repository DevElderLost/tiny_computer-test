package com.example.tiny_computer.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Mengambil ikon dari berkas Windows (.exe) dengan membaca resource PE-nya (RT_GROUP_ICON + RT_ICON),
 * seperti `ExeIconExtractor` di Winlator. Hanya bagian yang dibutuhkan yang dibaca (header, direktori
 * resource, dan satu gambar ikon), bukan seluruh berkas, jadi aman untuk .exe besar. Ikon PNG di dalam
 * resource (Vista ke atas) dan DIB klasik (1/4/8/24/32 bit + AND mask) sama-sama didukung.
 *
 * Tidak ada ikon (resource kosong, program ter-pack, bukan PE) -> null, dan kartu tetap menampilkan
 * huruf pertama namanya.
 */
object ExeIconExtractor {
    private const val TAG = "ExeIcon"

    /** Sisi ikon hasil (piksel), sama dengan Winlator. */
    const val ICON_SIZE = 256

    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_ENTRIES = 4096
    private const val MAX_ICON_BYTES = 4 * 1024 * 1024
    private const val MAX_DIM = 1024

    /** Ikon dari berkas yang bisa dibaca langsung (path penyimpanan perangkat). */
    fun extract(file: File): Bitmap? = runCatching {
        if (!file.isFile || !file.canRead()) return@runCatching null
        FileInputStream(file).use { parse(it.channel) }
    }.onFailure { Log.w(TAG, "gagal membaca ${file.name}: ${it.message}") }.getOrNull()

    /** Ikon dari URI pemilih berkas (SAF); tidak butuh izin penyimpanan tambahan. */
    fun extract(ctx: Context, uri: Uri): Bitmap? = runCatching {
        // fd dimiliki pfd: FileInputStream di sini tidak ditutup sendiri supaya fd tidak ditutup dua kali
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> parse(FileInputStream(pfd.fileDescriptor).channel) }
    }.onFailure { Log.w(TAG, "gagal membaca $uri: ${it.message}") }.getOrNull()

    /** Menyimpan sebagai PNG. */
    fun save(bitmap: Bitmap, dest: File): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ pembaca PE

    private class Src(val ch: FileChannel) {
        val size: Long = ch.size()

        fun bytes(pos: Long, len: Int): ByteBuffer {
            require(pos >= 0 && len >= 0 && pos + len <= size) { "di luar berkas" }
            val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            var p = pos
            while (b.hasRemaining()) {
                val n = ch.read(b, p)
                require(n > 0) { "berkas terpotong" }
                p += n
            }
            b.flip()
            return b
        }

        fun u16(pos: Long): Int = bytes(pos, 2).short.toInt() and 0xFFFF
        fun u32(pos: Long): Long = bytes(pos, 4).int.toLong() and 0xFFFFFFFFL
    }

    private class Section(val va: Long, val span: Long, val raw: Long)

    private class Entry(val id: Int, val isDir: Boolean, val off: Long)

    private fun parse(ch: FileChannel): Bitmap? {
        val s = Src(ch)
        if (s.size < 0x40 || s.u16(0) != 0x5A4D) return null // "MZ"
        val pe = s.u32(0x3C)
        if (pe <= 0 || pe + 24 > s.size || s.u32(pe) != 0x00004550L) return null // "PE\0\0"
        val sectionCount = s.u16(pe + 6)
        val optSize = s.u16(pe + 20)
        val opt = pe + 24
        val dataDirs = when (s.u16(opt)) {
            0x20B -> opt + 112 // PE32+
            0x10B -> opt + 96 // PE32
            else -> return null
        }
        val rsrcRva = s.u32(dataDirs + 2 * 8) // direktori ke-2 = resource
        if (rsrcRva == 0L) return null

        val sections = (0 until minOf(sectionCount, 96)).map { i ->
            val at = opt + optSize + i * 40L
            val virtualSize = s.u32(at + 8)
            val rawSize = s.u32(at + 16)
            Section(va = s.u32(at + 12), span = maxOf(virtualSize, rawSize), raw = s.u32(at + 20))
        }

        fun fileOffset(rva: Long): Long? =
            sections.firstOrNull { rva >= it.va && rva < it.va + it.span }?.let { it.raw + (rva - it.va) }

        val rsrc = fileOffset(rsrcRva) ?: return null // tidak ketemu: biasanya program ter-pack (UPX, Themida, ...)

        /** Isi satu direktori resource; [dir] relatif terhadap awal resource. Entri bernama didahulukan, id = -1. */
        fun entries(dir: Long): List<Entry> {
            val base = rsrc + dir
            val named = s.u16(base + 12)
            val ids = s.u16(base + 14)
            val total = named + ids
            if (total > MAX_ENTRIES) return emptyList()
            return (0 until total).map { i ->
                val e = base + 16 + i * 8L
                val name = s.u32(e)
                val off = s.u32(e + 4)
                Entry(
                    id = if (i < named) -1 else (name and 0xFFFFL).toInt(),
                    isDir = (off and 0x80000000L) != 0L,
                    off = off and 0x7FFFFFFFL,
                )
            }
        }

        /** Turun lewat sub-direktori (nama -> bahasa) mengambil entri pertama, lalu baca datanya. */
        fun leafData(start: Entry): ByteArray? {
            var cur = start
            var depth = 0
            while (cur.isDir) {
                if (++depth > 4) return null
                cur = entries(cur.off).firstOrNull() ?: return null
            }
            val at = rsrc + cur.off
            val dataRva = s.u32(at)
            val size = s.u32(at + 4)
            if (size <= 0 || size > MAX_ICON_BYTES) return null
            val pos = fileOffset(dataRva) ?: return null
            return s.bytes(pos, size.toInt()).array()
        }

        val types = entries(0)
        val groupDir = types.firstOrNull { it.id == RT_GROUP_ICON && it.isDir } ?: return null
        val iconDir = types.firstOrNull { it.id == RT_ICON && it.isDir } ?: return null
        val iconEntries = entries(iconDir.off)

        // grup pertama = ikon utama (yang dipakai Explorer); grup berikutnya hanya cadangan bila gagal didekode
        for (group in entries(groupDir.off)) {
            val dir = leafData(group) ?: continue
            if (dir.size < 6) continue
            val bb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.getShort(4).toInt() and 0xFFFF
            class Candidate(val w: Int, val bpp: Int, val id: Int)
            val candidates = ArrayList<Candidate>()
            for (i in 0 until count) {
                val at = 6 + i * 14
                if (at + 14 > dir.size) break
                val w = (dir[at].toInt() and 0xFF).let { if (it == 0) 256 else it }
                candidates += Candidate(w, bb.getShort(at + 6).toInt() and 0xFFFF, bb.getShort(at + 12).toInt() and 0xFFFF)
            }
            // terbesar dulu; pada ukuran sama, warna terbanyak
            for (c in candidates.sortedWith(compareByDescending<Candidate> { it.w }.thenByDescending { it.bpp })) {
                val entry = iconEntries.firstOrNull { it.id == c.id } ?: continue
                val data = runCatching { leafData(entry) }.getOrNull() ?: continue
                val bmp = decode(data) ?: continue
                return if (bmp.width == ICON_SIZE && bmp.height == ICON_SIZE) bmp
                else Bitmap.createScaledBitmap(bmp, ICON_SIZE, ICON_SIZE, true)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ dekode gambar ikon

    private fun decode(data: ByteArray): Bitmap? {
        // PNG tertanam (ikon 256 px Vista ke atas)
        if (data.size > 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()) {
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        return decodeDib(data)
    }

    /**
     * DIB di dalam ICO/RT_ICON: tanpa BITMAPFILEHEADER, tinggi disimpan 2x (gambar XOR + AND mask).
     * Ikon 32-bit lama sering beralpha 0 semua: transparansi lalu diambil dari AND mask.
     */
    private fun decodeDib(d: ByteArray): Bitmap? {
        if (d.size < 40) return null
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val header = bb.getInt(0)
        val w = bb.getInt(4)
        val rawH = bb.getInt(8)
        val bpp = bb.getShort(14).toInt() and 0xFFFF
        val compression = bb.getInt(16)
        val colorsUsed = bb.getInt(32)
        if (header < 40 || w <= 0 || w > MAX_DIM || rawH <= 0) return null
        if (compression != 0 && compression != 3) return null // BI_RGB / BI_BITFIELDS saja
        val h = if (rawH == w) w else rawH / 2
        if (h <= 0 || h > MAX_DIM) return null

        var off = header
        if (compression == 3 && header == 40) off += 12 // tiga mask warna menyusul header
        val palette = IntArray(if (bpp <= 8) (if (colorsUsed > 0) colorsUsed else 1 shl bpp).coerceAtMost(256) else 0)
        if (off + palette.size * 4 > d.size) return null
        for (i in palette.indices) {
            val p = off + i * 4
            palette[i] = (0xFF shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
        }
        off += palette.size * 4

        val stride = ((w * bpp + 31) / 32) * 4
        if (bpp !in intArrayOf(1, 4, 8, 24, 32) || off + stride.toLong() * h > d.size) return null
        val maskStride = ((w + 31) / 32) * 4
        val maskOff = off + stride * h
        val hasMask = maskOff + maskStride.toLong() * h <= d.size

        val px = IntArray(w * h)
        var anyAlpha = false
        for (y in 0 until h) {
            val row = off + (h - 1 - y) * stride // baris disimpan dari bawah ke atas
            for (x in 0 until w) {
                px[y * w + x] = when (bpp) {
                    32 -> {
                        val p = row + x * 4
                        val a = d[p + 3].toInt() and 0xFF
                        if (a != 0) anyAlpha = true
                        (a shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
                    }
                    24 -> {
                        val p = row + x * 3
                        (0xFF shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
                    }
                    8 -> palette[(d[row + x].toInt() and 0xFF).coerceAtMost(palette.size - 1)]
                    4 -> {
                        val b = d[row + x / 2].toInt() and 0xFF
                        palette[(if (x % 2 == 0) b shr 4 else b and 0x0F).coerceAtMost(palette.size - 1)]
                    }
                    else -> { // 1 bit
                        val b = d[row + x / 8].toInt() and 0xFF
                        palette[((b shr (7 - x % 8)) and 1).coerceAtMost(palette.size - 1)]
                    }
                }
            }
        }
        // alpha 32-bit sah dipakai apa adanya; selain itu transparansi dari AND mask (bit 1 = transparan)
        if (!(bpp == 32 && anyAlpha)) {
            for (y in 0 until h) {
                val maskRow = maskOff + (h - 1 - y) * maskStride
                for (x in 0 until w) {
                    val transparent = hasMask && ((d[maskRow + x / 8].toInt() shr (7 - x % 8)) and 1) == 1
                    px[y * w + x] = if (transparent) 0 else px[y * w + x] or (0xFF shl 24)
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }
}
