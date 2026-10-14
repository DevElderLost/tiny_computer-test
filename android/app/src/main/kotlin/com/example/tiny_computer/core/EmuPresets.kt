package com.example.tiny_computer.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Preset lingkungan emulator x86 untuk Wine arm64ec: **Box64/WOWBox64** (`BOX64_*`) dan **FEXCore** (`FEX_*`).
 *
 * Empat preset bawaan (Stability, Compatibility, Intermediate, Performance) hanya-baca; pengguna dapat membuat,
 * menggandakan, mengubah, menghapus, mengimpor dan mengekspor preset sendiri. Preset terpilih ditulis sebagai
 * `export KEY="${KEY:-nilai}"` ke `env.sh` Wine ([WineContents.writeRuntime]); karena memakai `:-`, variabel yang
 * sudah diset per-Shortcut tetap menang.
 *
 * Penyimpanan preset pengguna: `$DATA_DIR/tiny/wine/presets/<box64|fexcore>/<id>.json`
 *   `{"name": "...", "env": {"BOX64_...": "1", ...}}`
 */
object EmuPresets {
    enum class Kind(val dir: String, val prefix: String, val title: String) {
        BOX64("box64", "BOX64_", "Box64"),
        FEXCORE("fexcore", "FEX_", "FEXCore");
    }

    enum class VarType { BOOL, ENUM, INT }

    /** Definisi satu variabel yang dikenal editor (untuk saklar/pilihan + teks bantuan). */
    class VarDef(val key: String, val type: VarType, val default: String, val help: String, val options: List<String> = emptyList())

    class Preset(val id: String, val name: String, val bundled: Boolean, val env: Map<String, String>)

    // ------------------------------------------------------------------ katalog variabel

    private val BOX64_VARS = listOf(
        VarDef("BOX64_DYNAREC_SAFEFLAGS", VarType.ENUM, "2", "How CPU flags are kept across calls/returns. 0 = never saved (fastest, can break), 1 = saved only when needed, 2 = always saved (safest).", listOf("0", "1", "2")),
        VarDef("BOX64_DYNAREC_FASTNAN", VarType.BOOL, "0", "Faster NaN handling in float math. Faster, but a few programs that depend on exact NaN behaviour misbehave."),
        VarDef("BOX64_DYNAREC_FASTROUND", VarType.BOOL, "0", "Faster float rounding that ignores the exact x86 rounding mode. Faster, less exact."),
        VarDef("BOX64_DYNAREC_X87DOUBLE", VarType.BOOL, "1", "Use 64-bit double precision for x87 instead of emulating 80-bit. Faster; turn on if a game needs it for stability."),
        VarDef("BOX64_DYNAREC_BIGBLOCK", VarType.ENUM, "0", "Size of translated code blocks. 0 = small blocks (safest), 3 = largest blocks (fastest, can break self-modifying code).", listOf("0", "1", "2", "3")),
        VarDef("BOX64_DYNAREC_STRONGMEM", VarType.ENUM, "1", "Strength of x86 memory-ordering emulation. 0 = none (fastest), 3 = strongest (slowest). Helps multithreaded games that crash or hang.", listOf("0", "1", "2", "3")),
        VarDef("BOX64_DYNAREC_FORWARD", VarType.INT, "128", "How far ahead (in bytes) the translator follows forward jumps when building a block. Larger values can be faster."),
        VarDef("BOX64_DYNAREC_CALLRET", VarType.ENUM, "0", "Optimises call/return pairs. 0 = off (safest), 1 = on (faster).", listOf("0", "1", "2")),
        VarDef("BOX64_DYNAREC_WAIT", VarType.BOOL, "1", "Wait for the translator to finish a block instead of falling back to the interpreter in the meantime. Safer when on."),
        VarDef("BOX64_AVX", VarType.ENUM, "0", "Expose AVX instructions to the program. 0 = off, 1 = AVX, 2 = AVX and AVX2. Some games need it, others crash with it.", listOf("0", "1", "2")),
    )

    private val FEX_VARS = listOf(
        VarDef("FEX_TSOENABLED", VarType.BOOL, "1", "Emulate x86 Total Store Ordering (memory ordering). Needed for many multithreaded programs; turning it off is faster but can cause crashes or glitches."),
        VarDef("FEX_VECTORTSOENABLED", VarType.BOOL, "1", "Apply Total Store Ordering to vector (SSE/AVX) loads and stores as well."),
        VarDef("FEX_HALFBARRIERTSOENABLED", VarType.BOOL, "1", "Use cheaper half-barrier atomics to implement Total Store Ordering where the CPU supports it."),
        VarDef("FEX_MEMCPYSETTSOENABLED", VarType.BOOL, "1", "Apply Total Store Ordering to REP MOVS / REP STOS (memcpy / memset style) operations."),
        VarDef("FEX_X87REDUCEDPRECISION", VarType.BOOL, "0", "Run x87 floating point at 64-bit instead of 80-bit precision. Faster, but breaks programs that rely on extended precision."),
        VarDef("FEX_MULTIBLOCK", VarType.BOOL, "1", "Translate several basic blocks together (multiblock). Usually faster; turn off if a program is unstable."),
        VarDef("FEX_MAXINST", VarType.INT, "5000", "Maximum number of x86 instructions translated in one block."),
    )

    fun defs(kind: Kind): List<VarDef> = if (kind == Kind.BOX64) BOX64_VARS else FEX_VARS

    // ------------------------------------------------------------------ preset bawaan

    private fun map(defs: List<VarDef>, vararg values: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        defs.forEachIndexed { i, d -> m[d.key] = values[i] }
        return m
    }

    private val BOX64_BUNDLED = listOf(
        // SAFEFLAGS FASTNAN FASTROUND X87DOUBLE BIGBLOCK STRONGMEM FORWARD CALLRET WAIT AVX
        Preset("stability", "Stability", true, map(BOX64_VARS, "2", "0", "0", "1", "0", "2", "128", "0", "1", "0")),
        Preset("compatibility", "Compatibility", true, map(BOX64_VARS, "2", "0", "0", "1", "0", "1", "128", "0", "1", "0")),
        Preset("intermediate", "Intermediate", true, map(BOX64_VARS, "1", "1", "0", "1", "1", "0", "128", "0", "1", "0")),
        Preset("performance", "Performance", true, map(BOX64_VARS, "1", "1", "1", "0", "3", "0", "512", "1", "0", "0")),
    )

    private val FEX_BUNDLED = listOf(
        // TSO VECTORTSO HALFBARRIER MEMCPYSET X87REDUCED MULTIBLOCK MAXINST
        Preset("stability", "Stability", true, map(FEX_VARS, "1", "1", "1", "1", "0", "0", "5000")),
        Preset("compatibility", "Compatibility", true, map(FEX_VARS, "1", "1", "1", "1", "0", "1", "5000")),
        Preset("intermediate", "Intermediate", true, map(FEX_VARS, "1", "0", "1", "0", "1", "1", "5000")),
        Preset("performance", "Performance", true, map(FEX_VARS, "0", "0", "0", "0", "1", "1", "5000")),
    )

    const val DEFAULT_ID = "compatibility"

    private fun bundled(kind: Kind) = if (kind == Kind.BOX64) BOX64_BUNDLED else FEX_BUNDLED

    // ------------------------------------------------------------------ status UI

    /** Naik setiap kali daftar atau pilihan preset berubah, supaya UI membaca ulang. */
    var revision by mutableStateOf(0)
        private set

    // ------------------------------------------------------------------ penyimpanan

    private fun dir(kind: Kind) = File(File(TinyEnv.dataPath, "tiny/wine/presets"), kind.dir)

    private val KEY_RE = Regex("^[A-Z][A-Z0-9_]{2,63}$")

    /** Kunci hanya boleh berawalan milik jenisnya (BOX64_ / FEX_), supaya isi preset tidak bisa menyisipkan variabel lain. */
    fun validKey(kind: Kind, key: String) = key.startsWith(kind.prefix) && KEY_RE.matches(key)

    /** Nilai pendek tanpa spasi/kutip/baris baru, aman dituliskan ke shell. */
    private fun cleanValue(v: String): String = v.trim().filter { it.isLetterOrDigit() || it in "._-+:,/=@" }.take(120)

    private fun slug(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "preset" }

    private fun read(kind: Kind, f: File): Preset? = runCatching {
        val o = JSONObject(f.readText())
        val env = LinkedHashMap<String, String>()
        val e = o.getJSONObject("env")
        for (k in e.keys()) if (validKey(kind, k)) env[k] = cleanValue(e.get(k).toString())
        Preset(f.nameWithoutExtension, o.getString("name").take(60), false, env)
    }.getOrNull()

    fun custom(kind: Kind): List<Preset> =
        (dir(kind).listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }.mapNotNull { read(kind, it) }

    /** Bawaan lebih dulu, lalu preset pengguna. */
    fun list(kind: Kind): List<Preset> = bundled(kind) + custom(kind)

    fun find(kind: Kind, id: String): Preset? = list(kind).firstOrNull { it.id == id }

    fun selectedId(kind: Kind): String {
        val id = if (kind == Kind.BOX64) Cfg.box64Preset else Cfg.fexPreset
        return if (find(kind, id) != null) id else DEFAULT_ID
    }

    fun selected(kind: Kind): Preset = find(kind, selectedId(kind)) ?: bundled(kind).first { it.id == DEFAULT_ID }

    /** Memilih preset lalu menulis ulang env.sh (di thread latar) supaya berlaku di peluncuran berikutnya. */
    fun select(kind: Kind, id: String) {
        if (kind == Kind.BOX64) Cfg.box64Preset = id else Cfg.fexPreset = id
        revision++
        applyAsync()
    }

    private fun applyAsync() {
        Thread({ runCatching { WineContents.onSelectionChanged() } }, "tiny-preset-apply").apply { isDaemon = true }.start()
    }

    private fun uniqueName(kind: Kind, wanted: String, ignoreId: String? = null): String {
        val base = wanted.trim().ifEmpty { "Preset" }.take(60)
        val taken = list(kind).filter { it.id != ignoreId }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    private fun uniqueId(kind: Kind, name: String): String {
        val ids = list(kind).map { it.id }.toSet()
        val base = slug(name)
        if (base !in ids) return base
        var n = 2
        while ("$base-$n" in ids) n++
        return "$base-$n"
    }

    /** Menyimpan preset pengguna. [id] null = buat baru. Preset bawaan tidak bisa ditimpa. Mengembalikan preset tersimpan. */
    fun save(kind: Kind, id: String?, name: String, env: Map<String, String>): Preset? {
        if (id != null && bundled(kind).any { it.id == id }) return null
        val clean = LinkedHashMap<String, String>()
        for ((k, v) in env) if (validKey(kind, k)) clean[k] = cleanValue(v)
        val finalName = uniqueName(kind, name, id)
        val finalId = id ?: uniqueId(kind, finalName)
        val o = JSONObject().put("name", finalName).put("env", JSONObject(clean as Map<*, *>))
        val d = dir(kind).apply { mkdirs() }
        File(d, "$finalId.json").writeText(o.toString(2))
        revision++
        if (selectedId(kind) == finalId) applyAsync()
        return Preset(finalId, finalName, false, clean)
    }

    /** Menggandakan [src] menjadi preset pengguna baru bernama "<nama> copy". */
    fun clone(kind: Kind, src: Preset): Preset? = save(kind, null, "${src.name} copy", src.env)

    fun delete(kind: Kind, id: String) {
        if (bundled(kind).any { it.id == id }) return
        File(dir(kind), "$id.json").delete()
        val cur = if (kind == Kind.BOX64) Cfg.box64Preset else Cfg.fexPreset
        if (cur == id) {
            if (kind == Kind.BOX64) Cfg.box64Preset = DEFAULT_ID else Cfg.fexPreset = DEFAULT_ID
            applyAsync()
        }
        revision++
    }

    // ------------------------------------------------------------------ impor / ekspor

    /** Ekspor satu preset sebagai JSON: `{"type":"box64","name":"...","env":{...}}`. */
    fun toJson(kind: Kind, p: Preset): String =
        JSONObject().put("type", kind.dir).put("name", p.name).put("env", JSONObject(LinkedHashMap(p.env) as Map<*, *>)).toString(2)

    fun exportTo(ctx: Context, uri: Uri, kind: Kind, p: Preset) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(toJson(kind, p).toByteArray()) }
            ?: throw IllegalStateException("Cannot write the file.")
    }

    class ImportResult(val count: Int, val error: String?)

    /**
     * Mengimpor preset dari berkas. Format yang diterima:
     *  - JSON satu preset `{"name": "...", "env": {...}}` (boleh ada `"type"`), atau JSON objek datar `{"BOX64_X": "1"}`
     *  - JSON larik preset, atau `{"presets": [...]}`
     *  - teks `KEY=VALUE` per baris (boleh berawalan `export `, baris `#` diabaikan); nama diambil dari nama berkas
     * Hanya variabel berawalan milik [kind] yang diambil; preset yang tidak punya satu pun variabel valid ditolak.
     */
    fun importFrom(ctx: Context, uri: Uri, kind: Kind): ImportResult {
        var fileName = "Imported"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) fileName = (it.getString(0) ?: fileName).substringBeforeLast('.').ifEmpty { fileName }
            }
        }
        val limit = 1 shl 20
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val r = s.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    if (out.size() > limit) return ImportResult(0, "File is too large for a preset.")
                }
                out.toString("UTF-8")
            } ?: return ImportResult(0, "Cannot open the file.")
        } catch (t: Throwable) {
            return ImportResult(0, t.message ?: "Cannot read the file.")
        }
        val parsed = runCatching { parse(kind, text.trim(), fileName) }.getOrElse { return ImportResult(0, it.message ?: "Invalid preset file.") }
        if (parsed.isEmpty()) return ImportResult(0, "No ${kind.prefix}* variables found in this file.")
        var n = 0
        for ((name, env) in parsed) if (save(kind, null, name, env) != null) n++
        return ImportResult(n, null)
    }

    private fun envOf(kind: Kind, o: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) if (validKey(kind, k)) out[k] = cleanValue(o.get(k).toString())
        return out
    }

    private fun parse(kind: Kind, text: String, fallbackName: String): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, Map<String, String>>>()
        fun one(o: JSONObject, nameFallback: String) {
            val type = o.optString("type", "")
            if (type.isNotEmpty() && !type.equals(kind.dir, true) && !type.equals(kind.title, true)) {
                throw IllegalArgumentException("This preset is for ${type}, not ${kind.title}.")
            }
            val env = envOf(kind, o.optJSONObject("env") ?: o)
            if (env.isNotEmpty()) out += o.optString("name", "").ifBlank { nameFallback } to env
        }
        when {
            text.startsWith("[") -> {
                val a = JSONArray(text)
                for (i in 0 until a.length()) one(a.getJSONObject(i), "$fallbackName ${i + 1}")
            }
            text.startsWith("{") -> {
                val o = JSONObject(text)
                val arr = o.optJSONArray("presets")
                if (arr != null) {
                    for (i in 0 until arr.length()) one(arr.getJSONObject(i), "$fallbackName ${i + 1}")
                } else {
                    one(o, fallbackName)
                }
            }
            else -> {
                val env = LinkedHashMap<String, String>()
                for (raw in text.lineSequence()) {
                    val line = raw.trim().removePrefix("export ").trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val i = line.indexOf('=')
                    if (i <= 0) continue
                    val k = line.substring(0, i).trim()
                    if (validKey(kind, k)) env[k] = cleanValue(line.substring(i + 1).trim().trim('"', '\''))
                }
                if (env.isNotEmpty()) out += fallbackName to env
            }
        }
        return out
    }

    // ------------------------------------------------------------------ env.sh

    /** Baris `export` untuk env.sh: preset Box64 dan FEXCore terpilih. Variabel yang sudah diset (mis. per-Shortcut) menang. */
    fun envLines(): String = buildString {
        for (kind in Kind.values()) {
            val p = selected(kind)
            append("# ${kind.title} preset: ${p.name.replace(Regex("[\\r\\n]"), " ")}\n")
            for ((k, v) in p.env) if (validKey(kind, k)) append("export $k=\"\${$k:-${v.replace("'", "")}}\"\n")
        }
    }
}
