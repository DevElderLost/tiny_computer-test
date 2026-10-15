package com.example.tiny_computer.core

import java.io.File

/**
 * Mode renderer Direct3D di Wine: **DXVK** (Direct3D -> Vulkan) atau **WineD3D** (bawaan Wine, OpenGL).
 *
 * Beralih mode melakukan tiga hal yang saling menguatkan:
 *  1. berkas DLL DXVK dipasang ke prefix (mode DXVK) atau dikembalikan ke DLL asli Wine (mode WineD3D) lewat [WineContents.applyAll];
 *  2. `WINEDLLOVERRIDES` di env.sh memaksa `native,builtin` (DXVK) atau `builtin` (WineD3D) untuk DLL Direct3D;
 *  3. bagian `[Software\\Wine\\DllOverrides]` di `user.reg` prefix ditulis langsung ([writeRegistry]) supaya pilihan
 *     juga tampil di winecfg > Libraries dan berlaku untuk program yang dijalankan tanpa shim Tiny.
 *
 * `user.reg` hanya aman ditulis saat wineserver mati (Wine menulis ulang berkasnya saat flush/keluar), jadi
 * [WineSetup.applyD3dModeAsync] menghentikan wineserver dulu.
 */
object D3dMode {
    const val DXVK = "dxvk"
    const val WINED3D = "wined3d"

    /**
     * Nilai DXVK_CONFIG hasil uji di Adreno 610 (tanpa spasi: dipakai lewat $EXTRA_OPT proot yang tidak diberi kutip,
     * spasi akan memecah argumen; `;` aman). Dipasang Launcher.guestSpec bila [Cfg.dxvkTune] aktif.
     */
    const val TUNE_CONFIG = "dxgi.maxDeviceMemory=512;dxgi.maxSharedMemory=512;dxvk.maxChunkSize=16;" +
        "dxvk.numCompilerThreads=1;dxvk.enableGraphicsPipelineLibrary=False;dxvk.enableStateCache=False"

    /** Teks bagian di user.reg: dua garis miring terbalik antar-segmen. */
    private const val SECTION = "Software\\\\Wine\\\\DllOverrides"
    private val DEFAULT_DLLS = listOf("d3d8", "d3d9", "d3d10core", "d3d11", "dxgi")
    private val NAME_RE = Regex("^[A-Za-z0-9_.-]{1,40}$")
    private val HEADER_RE = Regex("""^\[Software\\\\Wine\\\\DllOverrides\](\s+\d+)?\s*$""", RegexOption.IGNORE_CASE)
    private val VALUE_RE = Regex("""^"([^"]+)"\s*=""")

    fun mode(): String = if (Cfg.d3dMode == WINED3D) WINED3D else DXVK
    fun isDxvk(): Boolean = mode() == DXVK

    /** Nilai DllOverrides untuk [m]. */
    fun overrideValue(m: String): String = if (m == DXVK) "native,builtin" else "builtin"

    /** DLL Direct3D yang dikelola: isi paket DXVK aktif, atau daftar umum bila belum ada paket. */
    fun dlls(): List<String> {
        val fromPackage = WineContents.active(WineContents.Type.DXVK)?.files
            ?.map { it.target.substringAfterLast('/') }
            ?.filter { it.endsWith(".dll", ignoreCase = true) }
            ?.map { it.dropLast(4) }
            ?.filter { NAME_RE.matches(it) }
            ?.distinct()
            .orEmpty()
        return fromPackage.ifEmpty { DEFAULT_DLLS }
    }

    fun dllCsv(): String = dlls().joinToString(",")

    fun regFile() = File(WineContents.prefixDir(), "user.reg")

    // ------------------------------------------------------------------ user.reg

    /**
     * Mengembalikan [src] (isi user.reg) dengan nilai di bagian DllOverrides diatur ke [values] (nama DLL -> nilai).
     * Nilai yang sudah ada diganti di tempat (huruf nama asli dipertahankan), yang belum ada ditambahkan di akhir
     * bagian; bila bagiannya tidak ada, dibuat di akhir berkas. Bagian lain tidak disentuh.
     */
    internal fun editDllOverrides(src: String, values: Map<String, String>, nowSec: Long): String {
        val crlf = src.contains("\r\n")
        val lines = src.split("\n").map { it.removeSuffix("\r") }.toMutableList()
        fun entry(k: String, v: String) = "\"$k\"=\"$v\""
        val start = lines.indexOfFirst { HEADER_RE.matches(it) }
        if (start < 0) {
            while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines.add("")
            lines.add("[$SECTION] $nowSec")
            lines.add("#time=" + ((nowSec + 11644473600L) * 10_000_000L).toString(16))
            for ((k, v) in values) lines.add(entry(k, v))
            lines.add("")
        } else {
            var end = start + 1
            while (end < lines.size && !lines[end].startsWith("[")) end++
            val pending = LinkedHashMap(values)
            val byLower = values.keys.associateBy { it.lowercase() }
            for (i in start + 1 until end) {
                val name = VALUE_RE.find(lines[i])?.groupValues?.get(1) ?: continue
                val key = byLower[name.lowercase()] ?: continue
                lines[i] = entry(name, values.getValue(key))
                pending.remove(key)
            }
            var at = end
            while (at > start + 1 && lines[at - 1].isBlank()) at--
            for ((k, v) in pending) {
                lines.add(at, entry(k, v))
                at++
            }
        }
        return lines.joinToString(if (crlf) "\r\n" else "\n")
    }

    /**
     * Menulis DllOverrides ke user.reg sesuai [mode] saat ini. Mengembalikan pesan galat atau null bila berhasil.
     * Salinan asli dicadangkan sekali sebagai `user.reg.tiny-orig`. Panggil hanya saat wineserver mati.
     */
    @Synchronized
    fun writeRegistry(): String? {
        val f = regFile()
        if (!f.isFile) return "Wine prefix is not created yet (user.reg is missing)."
        return try {
            val value = overrideValue(mode())
            val old = f.readText()
            val updated = editDllOverrides(old, dlls().associateWith { value }, System.currentTimeMillis() / 1000)
            if (updated != old) {
                val bak = File(f.path + ".tiny-orig")
                if (!bak.exists()) f.copyTo(bak)
                val tmp = File(f.path + ".tiny-tmp")
                tmp.writeText(updated)
                if (!tmp.renameTo(f)) {
                    f.writeText(updated)
                    tmp.delete()
                }
            }
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /** Mode yang tertulis di user.reg (dibaca dari nilai d3d11): [DXVK], [WINED3D], atau null bila belum ada / tidak dikenal. */
    fun regState(): String? {
        val f = regFile()
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        val start = lines.indexOfFirst { HEADER_RE.matches(it) }
        if (start < 0) return null
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (l.startsWith("[")) break
            val name = VALUE_RE.find(l)?.groupValues?.get(1) ?: continue
            if (!name.equals("d3d11", ignoreCase = true)) continue
            val v = l.substringAfter('=').trim().trim('"').lowercase()
            return when {
                v.startsWith("native") -> DXVK
                v == "builtin" -> WINED3D
                else -> null
            }
        }
        return null
    }
}
