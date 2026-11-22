package com.example.tiny_computer.controls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Model profil controller bergaya Winlator (port `ControlsProfile`, `ControlElement` (data saja), `ExternalController`,
 * `ExternalControllerBinding`). Format JSON `.icp` sama dengan Winlator: koordinat elemen = titik tengah, dinormalkan
 * terhadap lebar/tinggi layar (0..1), ukuran dasar elemen dalam satuan lebar/100 dikali [Element.scale].
 */
class Element(
    var type: Type = Type.BUTTON,
    var shape: Shape = Shape.CIRCLE,
) {
    enum class Type { BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, DYNAMIC_STICK }
    enum class Shape { CIRCLE, RECT, ROUND_RECT, SQUARE }
    enum class Range(val max: Int, val first: Binding) {
        FROM_A_TO_Z(26, Binding.KEY_A), FROM_0_TO_9(10, Binding.KEY_0),
        FROM_F1_TO_F12(12, Binding.KEY_F1), FROM_NP0_TO_NP9(10, Binding.KEY_KP_0),
    }

    var bindings: Array<Binding> = arrayOf(Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE)
    var scale = 1f
    /** Titik tengah, dinormalkan 0..1 terhadap lebar dan tinggi layar. */
    var x = 0.5f
    var y = 0.5f
    var toggleSwitch = false
    var text = ""
    var opacity = 1f
    var customColor = 0
    /** BUTTON: sambil ditahan, geser jari menggerakkan kursor (seperti Winlator). */
    var mouseMoveMode = false
    var deadZone = DEAD_ZONE
    var range: Range? = null
    /** RANGE_BUTTON: 0 mendatar, 1 tegak. */
    var orientation = 0
    // DYNAMIC_STICK: area sentuh (rasio layar) dan jari-jari stik (rasio lebar).
    var areaWidthRatio = 0.38f
    var areaHeightRatio = 0.64f
    var stickRadiusRatio = 0.14f

    /** Setel jenis dan isi binding bawaannya (sama dengan ControlElement.reset Winlator). */
    fun applyType(t: Type) {
        type = t
        bindings = when (t) {
            Type.STICK, Type.DYNAMIC_STICK -> arrayOf(Binding.KEY_W, Binding.KEY_D, Binding.KEY_S, Binding.KEY_A)
            Type.D_PAD -> arrayOf(Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_RIGHT, Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT)
            Type.TRACKPAD -> arrayOf(Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_RIGHT, Binding.GAMEPAD_RIGHT_THUMB_DOWN, Binding.GAMEPAD_RIGHT_THUMB_LEFT)
            Type.RANGE_BUTTON -> {
                if (range == null) range = Range.FROM_A_TO_Z
                Array(range!!.max.coerceAtMost(26)) { i -> rangeBinding(range!!, i) }
            }
            Type.BUTTON -> arrayOf(Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE)
        }
        if (t == Type.BUTTON && bindings.size != 1) bindings = arrayOf(Binding.NONE)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("shape", shape.name)
        put("bindings", JSONArray().also { a -> bindings.forEach { a.put(it.name) } })
        put("scale", scale.toDouble())
        if ((type == Type.STICK || type == Type.DYNAMIC_STICK) && deadZone != DEAD_ZONE) put("deadZone", deadZone.toDouble())
        if (type == Type.DYNAMIC_STICK) {
            put("areaWidthRatio", areaWidthRatio.toDouble())
            put("areaHeightRatio", areaHeightRatio.toDouble())
            put("stickRadiusRatio", stickRadiusRatio.toDouble())
        }
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("toggleSwitch", toggleSwitch)
        put("text", text)
        put("iconId", 0)
        if (opacity != 1f) put("opacity", opacity.toDouble())
        if (customColor != 0) put("customColor", customColor)
        if (mouseMoveMode) put("mouseMoveMode", true)
        if (type == Type.RANGE_BUTTON) {
            range?.let { put("range", it.name) }
            if (orientation != 0) put("orientation", orientation)
        }
    }

    companion object {
        const val DEAD_ZONE = 0.15f

        fun rangeBinding(range: Range, index: Int): Binding {
            val all = Binding.entries
            return all[(all.indexOf(range.first) + index).coerceIn(0, all.size - 1)]
        }

        fun fromJson(o: JSONObject): Element? = runCatching {
            Element(Type.valueOf(o.getString("type")), Shape.valueOf(o.optString("shape", "CIRCLE"))).apply {
                val arr = o.getJSONArray("bindings")
                bindings = Array(arr.length()) { Binding.fromString(arr.getString(it)) }
                scale = o.optDouble("scale", 1.0).toFloat()
                x = o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f)
                y = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                toggleSwitch = o.optBoolean("toggleSwitch", false)
                text = o.optString("text", "")
                opacity = o.optDouble("opacity", 1.0).toFloat()
                customColor = o.optInt("customColor", 0)
                mouseMoveMode = o.optBoolean("mouseMoveMode", false)
                deadZone = o.optDouble("deadZone", DEAD_ZONE.toDouble()).toFloat()
                if (o.has("range")) range = runCatching { Range.valueOf(o.getString("range")) }.getOrNull()
                orientation = o.optInt("orientation", 0)
                areaWidthRatio = o.optDouble("areaWidthRatio", 0.38).toFloat()
                areaHeightRatio = o.optDouble("areaHeightRatio", 0.64).toFloat()
                stickRadiusRatio = o.optDouble("stickRadiusRatio", 0.14).toFloat()
                // elemen tanpa 4 binding (tombol lama) tetap aman dibaca di mana pun
                if (bindings.isEmpty()) bindings = arrayOf(Binding.NONE)
            }
        }.getOrNull()
    }
}

/** Satu tombol/sumbu controller fisik yang diarahkan ke [Binding] (port ExternalControllerBinding). */
class PadBinding(var keyCode: Int, var binding: Binding = Binding.NONE) {
    fun toJson() = JSONObject().put("keyCode", keyCode).put("binding", binding.name)

    /** Teks pendek tombol fisik: "AXIS X-" untuk sumbu, selain itu nama KEYCODE Android. */
    val label: String
        get() = when (keyCode) {
            AXIS_X_NEG -> "AXIS X-"; AXIS_X_POS -> "AXIS X+"
            AXIS_Y_NEG -> "AXIS Y-"; AXIS_Y_POS -> "AXIS Y+"
            AXIS_Z_NEG -> "AXIS Z-"; AXIS_Z_POS -> "AXIS Z+"
            AXIS_RZ_NEG -> "AXIS RZ-"; AXIS_RZ_POS -> "AXIS RZ+"
            else -> android.view.KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "").replace("_", " ")
        }

    companion object {
        // nilai negatif sama dengan Winlator (ExternalControllerBinding.AXIS_*)
        const val AXIS_X_NEG = -1
        const val AXIS_X_POS = -2
        const val AXIS_Y_NEG = -3
        const val AXIS_Y_POS = -4
        const val AXIS_Z_NEG = -5
        const val AXIS_Z_POS = -6
        const val AXIS_RZ_NEG = -7
        const val AXIS_RZ_POS = -8

        fun axisKeyCode(axis: Int, positive: Boolean): Int = when (axis) {
            android.view.MotionEvent.AXIS_X -> if (positive) AXIS_X_POS else AXIS_X_NEG
            android.view.MotionEvent.AXIS_Y -> if (positive) AXIS_Y_POS else AXIS_Y_NEG
            android.view.MotionEvent.AXIS_Z -> if (positive) AXIS_Z_POS else AXIS_Z_NEG
            android.view.MotionEvent.AXIS_RZ -> if (positive) AXIS_RZ_POS else AXIS_RZ_NEG
            android.view.MotionEvent.AXIS_HAT_X -> if (positive) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
            android.view.MotionEvent.AXIS_HAT_Y -> if (positive) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP
            else -> android.view.KeyEvent.KEYCODE_UNKNOWN
        }
    }
}

/** Satu controller fisik (dikenali lewat descriptor Android) dan daftar pemetaan tombolnya. */
class PadController(val id: String, var name: String) {
    val bindings = ArrayList<PadBinding>()

    fun find(keyCode: Int): PadBinding? = bindings.firstOrNull { it.keyCode == keyCode }

    fun toJson(): JSONObject? {
        if (bindings.isEmpty()) return null
        return JSONObject().put("id", id).put("name", name)
            .put("controllerBindings", JSONArray().also { a -> bindings.forEach { a.put(it.toJson()) } })
    }

    /** Descriptor perangkat Android yang sedang tersambung (null bila tidak ada). */
    fun isConnected(): Boolean = android.view.InputDevice.getDeviceIds().any {
        android.view.InputDevice.getDevice(it)?.descriptor == id
    }

    companion object {
        fun fromJson(o: JSONObject): PadController? = runCatching {
            PadController(o.getString("id"), o.optString("name", "Controller")).also { c ->
                val arr = o.getJSONArray("controllerBindings")
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val k = b.getInt("keyCode")
                    if (c.find(k) == null) c.bindings.add(PadBinding(k, Binding.fromString(b.getString("binding"))))
                }
            }
        }.getOrNull()
    }
}

class Profile(val id: Int, var name: String) {
    var cursorSpeed = 1f
    var themeColor = 0
    val elements = ArrayList<Element>()
    val controllers = ArrayList<PadController>()

    /** True bila ada elemen yang hanya berisi binding gamepad (profil ini gamepad virtual). */
    val isVirtualGamepad: Boolean
        get() = elements.any { e -> e.bindings.isNotEmpty() && e.bindings.all { it.isGamepad } }

    fun controller(descriptor: String): PadController? = controllers.firstOrNull { it.id == descriptor }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("cursorSpeed", cursorSpeed.toDouble())
        if (themeColor != 0) put("themeColor", themeColor)
        put("elements", JSONArray().also { a -> elements.forEach { a.put(it.toJson()) } })
        val cs = JSONArray().also { a -> controllers.forEach { c -> c.toJson()?.let(a::put) } }
        if (cs.length() > 0) put("controllers", cs)
    }

    fun save(context: Context) {
        val f = ProfileStore.file(context, id)
        f.parentFile?.mkdirs()
        f.writeText(toJson().toString())
    }

    companion object {
        fun fromJson(o: JSONObject, forcedId: Int? = null): Profile? = runCatching {
            Profile(forcedId ?: o.getInt("id"), o.optString("name", "Profile")).apply {
                cursorSpeed = o.optDouble("cursorSpeed", 1.0).toFloat()
                themeColor = o.optInt("themeColor", 0)
                val es = o.optJSONArray("elements")
                if (es != null) for (i in 0 until es.length()) Element.fromJson(es.getJSONObject(i))?.let(elements::add)
                val cs = o.optJSONArray("controllers")
                if (cs != null) for (i in 0 until cs.length()) PadController.fromJson(cs.getJSONObject(i))?.let(controllers::add)
            }
        }.getOrNull()
    }
}

/**
 * Daftar profil di `files/inputcontrols/profiles/controls-<id>.icp` (port InputControlsManager) dan profil yang aktif.
 * Berkas `.icp` Winlator bisa disalin/diimpor langsung.
 */
object ProfileStore {
    private const val PREFS = "winlator_controls"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(c: Context): File = File(c.filesDir, "inputcontrols/profiles").also { it.mkdirs() }
    fun file(c: Context, id: Int) = File(dir(c), "controls-$id.icp")

    /** Mode controller gaya Winlator (profil) menggantikan controller layar DroidDeck di sesi desktop. */
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    /** Tombol mengambang kecil untuk menampilkan/menyembunyikan controller dan membuka keyboard PC. */
    fun floatingButton(c: Context) = prefs(c).getBoolean("floatingButton", true)
    fun setFloatingButton(c: Context, on: Boolean) = prefs(c).edit().putBoolean("floatingButton", on).apply()

    /** Controller fisik memakai pemetaan profil (tombol tanpa pemetaan tetap diteruskan ke pad Deck/Xbox). */
    fun externalEnabled(c: Context) = prefs(c).getBoolean("external", true)
    fun setExternalEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("external", on).apply()

    /** Opasitas overlay controller layar, 0..100 (Winlator: overlay_opacity, bawaan 85%). */
    fun overlayOpacity(c: Context) = (prefs(c).getFloat("overlayOpacity", 0.85f) * 100f).roundToInt().coerceIn(0, 100)
    fun setOverlayOpacity(c: Context, percent: Int) = prefs(c).edit().putFloat("overlayOpacity", percent.coerceIn(0, 100) / 100f).apply()

    fun activeId(c: Context) = prefs(c).getInt("activeProfile", 0)
    fun setActiveId(c: Context, id: Int) = prefs(c).edit().putInt("activeProfile", id).apply()

    fun floatingPos(c: Context): Pair<Float, Float> = prefs(c).let { it.getFloat("fabX", 0.96f) to it.getFloat("fabY", 0.12f) }
    fun setFloatingPos(c: Context, x: Float, y: Float) = prefs(c).edit().putFloat("fabX", x).putFloat("fabY", y).apply()

    fun profiles(c: Context): List<Profile> {
        val list = (dir(c).listFiles { f -> f.name.startsWith("controls-") && f.name.endsWith(".icp") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Profile.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.id }
        if (list.isNotEmpty()) return list
        // pertama kali: satu profil contoh supaya layar tidak kosong
        return listOf(createDefault(c))
    }

    fun get(c: Context, id: Int): Profile? =
        runCatching { Profile.fromJson(JSONObject(file(c, id).readText())) }.getOrNull()

    /** Profil yang sedang dipakai; bila hilang jatuh ke profil pertama. */
    fun active(c: Context): Profile {
        val want = activeId(c)
        return get(c, want) ?: profiles(c).first().also { setActiveId(c, it.id) }
    }

    private fun nextId(c: Context): Int =
        (dir(c).listFiles()?.mapNotNull { it.name.removePrefix("controls-").removeSuffix(".icp").toIntOrNull() }?.maxOrNull() ?: 0) + 1

    fun create(c: Context, name: String): Profile = Profile(nextId(c), name).also { it.save(c) }

    fun duplicate(c: Context, src: Profile): Profile {
        val copy = Profile.fromJson(src.toJson(), nextId(c))!!
        copy.name = src.name + " (copy)"
        copy.save(c)
        return copy
    }

    fun rename(c: Context, p: Profile, name: String) { p.name = name; p.save(c) }

    fun remove(c: Context, p: Profile) {
        file(c, p.id).delete()
        if (activeId(c) == p.id) setActiveId(c, 0)
    }

    /** Impor satu berkas `.icp` (id baru supaya tidak menimpa profil lain). */
    fun import(c: Context, json: String): Profile? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val p = Profile.fromJson(o, nextId(c)) ?: return null
        p.save(c)
        return p
    }

    /** Profil bawaan: stik WASD kiri, tombol mouse kanan, beberapa tombol keyboard, d-pad gamepad dan tombol A/B/X/Y. */
    private fun createDefault(c: Context): Profile {
        val p = Profile(1, "Default")
        fun btn(x: Float, y: Float, text: String, b: Binding, shape: Element.Shape = Element.Shape.CIRCLE) =
            Element(Element.Type.BUTTON, shape).apply { this.x = x; this.y = y; this.text = text; bindings = arrayOf(b) }
        p.elements += Element(Element.Type.STICK).apply { applyType(type); x = 0.14f; y = 0.70f }
        p.elements += Element(Element.Type.D_PAD).apply { applyType(type); x = 0.30f; y = 0.78f }
        p.elements += btn(0.90f, 0.78f, "A", Binding.GAMEPAD_BUTTON_A)
        p.elements += btn(0.95f, 0.64f, "B", Binding.GAMEPAD_BUTTON_B)
        p.elements += btn(0.85f, 0.64f, "X", Binding.GAMEPAD_BUTTON_X)
        p.elements += btn(0.90f, 0.50f, "Y", Binding.GAMEPAD_BUTTON_Y)
        p.elements += btn(0.08f, 0.40f, "LMB", Binding.MOUSE_LEFT_BUTTON, Element.Shape.ROUND_RECT)
        p.elements += btn(0.18f, 0.40f, "RMB", Binding.MOUSE_RIGHT_BUTTON, Element.Shape.ROUND_RECT)
        p.elements += btn(0.30f, 0.40f, "ESC", Binding.KEY_ESC, Element.Shape.ROUND_RECT)
        p.save(c)
        setActiveId(c, p.id)
        return p
    }
}
