package com.example.tiny_computer.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Membaca header PE untuk membedakan DLL **ARM64EC** dari x64 murni. Di prefix Wine arm64ec (Hangover/Proton arm64ec),
 * DLL 64-bit di system32 harus ARM64EC. DXVK resmi (`Build: x86_64 gcc ...`) adalah x64 murni: ia ikut termuat
 * ("native") tetapi dijalankan lewat emulasi FEX dan memanggil winevulkan lewat thunk, lalu macet di loader lock.
 *
 * ARM64EC memakai Machine 0x8664 (sama dengan x64) dan dibedakan oleh `CHPEMetadataPointer` (offset 0xC8) di
 * IMAGE_LOAD_CONFIG_DIRECTORY64: bukan nol untuk ARM64EC, nol/tidak ada untuk x64 biasa.
 */
object PeArch {
    enum class Kind { ARM64EC, X64, X86, UNKNOWN }

    fun kind(f: File): Kind = runCatching { read(f) }.getOrDefault(Kind.UNKNOWN)

    private fun read(f: File): Kind = RandomAccessFile(f, "r").use { r ->
        fun u16(o: Long): Int { r.seek(o); return r.read() or (r.read() shl 8) }
        fun u32(o: Long): Long { r.seek(o); var v = 0L; for (i in 0 until 4) v = v or (r.read().toLong() shl (8 * i)); return v }
        fun u64(o: Long): Long { r.seek(o); var v = 0L; for (i in 0 until 8) v = v or (r.read().toLong() shl (8 * i)); return v }

        if (u16(0) != 0x5A4D) return@use Kind.UNKNOWN
        val pe = u32(0x3C)
        if (u32(pe) != 0x00004550L) return@use Kind.UNKNOWN
        val machine = u16(pe + 4)
        val nSec = u16(pe + 6)
        val optSize = u16(pe + 20)
        val opt = pe + 24
        if (machine == 0x014C) return@use Kind.X86
        if (machine != 0x8664) return@use Kind.UNKNOWN // arm64 / ARM64X: tidak dinilai
        if (u16(opt) != 0x20B) return@use Kind.UNKNOWN
        val lcRva = u32(opt + 112 + 10 * 8)
        if (lcRva == 0L) return@use Kind.X64
        val sec = opt + optSize
        var off = -1L
        for (i in 0 until nSec) {
            val s = sec + i * 40L
            val vsize = u32(s + 8)
            val va = u32(s + 12)
            val rawSize = u32(s + 16)
            val raw = u32(s + 20)
            if (lcRva >= va && lcRva < va + maxOf(vsize, rawSize)) { off = raw + (lcRva - va); break }
        }
        if (off < 0) return@use Kind.UNKNOWN
        if (u32(off) < 0xD0L) return@use Kind.X64 // struktur terlalu pendek untuk CHPEMetadataPointer
        if (u64(off + 0xC8) != 0L) Kind.ARM64EC else Kind.X64
    }
}
