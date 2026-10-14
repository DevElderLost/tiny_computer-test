package com.example.tiny_computer.core

import java.io.File

/**
 * Mengenali arsip DXVK mentah (rilis resmi `dxvk-x.y.z.tar.gz` / `.zip`, build arm64ec buatan sendiri) yang tidak
 * punya `profile.json` seperti paket .wcp, dan menyusun daftar berkasnya. Tanpa ketergantungan Android.
 *
 * Aturan pemetaan (nama folder tidak peka huruf besar/kecil):
 *   64-bit -> ${system32}: `system32`, `arm64ec`, `aarch64`/`arm64`, `x64`/`x86_64` (yang paling asli dipilih,
 *             urut seperti tertulis; hanya satu folder 64-bit dipakai)
 *   32-bit -> ${syswow64}: `syswow64`, `x32`, `x86`, `i386`, `i686`, `win32`
 *   DLL tanpa folder yang dikenal (arsip datar) dianggap 64-bit.
 */
object DxvkImport {
    /** DLL milik DXVK; berkas lain di arsip (README, .so, setup script) diabaikan. */
    val DLLS = setOf("d3d8", "d3d9", "d3d10", "d3d10_1", "d3d10core", "d3d11", "dxgi")

    class Plan(
        val name: String,
        /** (path sumber relatif terhadap akar arsip, template target `${system32}/x.dll` atau `${syswow64}/x.dll`) */
        val files: List<Pair<String, String>>,
        val description: String,
    )

    private val BITS64 = listOf("system32", "arm64ec", "aarch64", "arm64", "x64", "x86_64", "x86-64")
    private val BITS32 = listOf("syswow64", "x32", "x86", "i386", "i686", "win32")
    private val EXTS = listOf(".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.zst", ".tzst", ".tar.zstd", ".wcp.xz", ".wcp", ".zip", ".xz", ".gz", ".zst")

    /** Nama versi dari nama berkas: buang ekstensi arsip, sisakan karakter aman. */
    fun versionName(displayName: String): String {
        var n = displayName.trim()
        val low = n.lowercase()
        for (e in EXTS) if (low.endsWith(e)) { n = n.substring(0, n.length - e.length); break }
        return n.replace(Regex("[^A-Za-z0-9._+-]+"), "_").trim('.', '_').take(60).ifEmpty { "dxvk-import" }
    }

    private class Found(val rel: String, val dll: String, val group: String?)

    /** null bila [root] tidak berisi DXVK (tidak ada d3d11.dll/d3d9.dll untuk 64-bit). */
    fun plan(root: File, displayName: String): Plan? {
        val found = ArrayList<Found>()
        fun walk(dir: File, rel: String, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (f in kids.sortedBy { it.name }) {
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) { if (depth < 4) walk(f, r, depth + 1) }
                else if (f.isFile && f.name.lowercase().endsWith(".dll")) {
                    val base = f.name.substring(0, f.name.length - 4).lowercase()
                    if (base in DLLS) {
                        val parent = r.substringBeforeLast('/', "").substringAfterLast('/').lowercase()
                        found += Found(r, base, parent.ifEmpty { null })
                    }
                }
            }
        }
        walk(root, "", 0)
        if (found.isEmpty()) return null

        val dirs64 = found.mapNotNull { it.group }.filter { it in BITS64 }.distinct()
        val use64 = BITS64.firstOrNull { it in dirs64 }
        val flat = found.filter { it.group == null || (it.group !in BITS64 && it.group !in BITS32) }
        val use32 = BITS32.firstOrNull { g -> found.any { it.group == g } }

        val files = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        fun add(f: Found, tpl: String) { val t = "$tpl/${f.dll}.dll"; if (seen.add(t)) files += f.rel to t }
        if (use64 != null) found.filter { it.group == use64 }.forEach { add(it, "\${system32}") }
        else flat.forEach { add(it, "\${system32}") }
        if (use32 != null) found.filter { it.group == use32 }.forEach { add(it, "\${syswow64}") }

        val has64 = files.any { it.second.startsWith("\${system32}/") && (it.second.endsWith("/d3d11.dll") || it.second.endsWith("/d3d9.dll")) }
        if (!has64) return null

        val src64 = use64 ?: "(top level)"
        val note = if (use64 == null || use64 in listOf("system32", "arm64ec", "aarch64", "arm64")) ""
        else " Note: x64 build, not arm64ec; it may not load in an arm64ec prefix."
        val desc = "Imported archive: $src64 -> system32" + (if (use32 != null) ", $use32 -> syswow64" else "") + "." + note
        return Plan(versionName(displayName), files, desc)
    }
}
