package com.example.tiny_computer.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Controller layar gaya Winlator (port `InputControlsView` + `ControlElement`): tombol keyboard/mouse/gamepad,
 * D-pad, stik, stik dinamis, trackpad, dan tombol rentang (A-Z, 0-9, F1-F12, numpad), semuanya dari [Profile].
 * Ditaruh sebagai View saudara di atas layar compositor (bukan di dalam Compose), sehingga sentuhan di luar elemen
 * jatuh ke layar compositor seperti biasa.
 *
 * [editing] = mode editor ([ControlsEditorActivity]): tidak mengirim masukan; seret elemen untuk memindahkan, ketuk
 * untuk memilih ([onSelect]).
 */
class ProfileControlsView(
    context: Context,
    private val dispatcher: BindingDispatcher?,
    private val editing: Boolean = false,
) : View(context) {

    var profile: Profile? = null
        set(value) { field = value; releaseAll(); rangeScroll.clear(); selected = null; invalidate() }

    /** Elemen terpilih di editor. */
    var selected: Element? = null
        set(value) { field = value; invalidate() }
    var onSelect: (Element?) -> Unit = {}
    /** Editor: dipanggil setelah elemen selesai digeser (jari diangkat), supaya riwayat dan simpan otomatis jalan. */
    var onEdited: () -> Unit = {}
    var swallowEmptyTouches = false

    /**
     * Sentuhan yang tidak dipakai elemen atau tombol mengambang diteruskan ke sini (layar sesi), seperti
     * `touchpadView.onTouchEvent` di InputControlsView Winlator. Tanpa ini, begitu satu jari menahan tombol atau
     * tombol mengambang, View ini (match_parent) menjadi sasaran sentuh dan jari kedua tidak pernah sampai ke layar,
     * sehingga kursor tidak bisa digeser.
     */
    var passthrough: ((MotionEvent) -> Boolean)? = null
    private val passIds = LinkedHashSet<Int>()
    private var passDownTime = 0L

    /** Tombol profil tampil; dimatikan lewat tombol mengambang. Di editor selalu tampil. */
    var controlsShown = true
        set(value) { if (field != value) { releaseAll(); field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Keadaan sentuhan satu elemen. */
    private class Touch(val e: Element, var pointer: Int) {
        var startX = 0f; var startY = 0f
        var lastX = 0f; var lastY = 0f
        var thumbX = 0f; var thumbY = 0f
        var releaseTask: Runnable? = null
        val active = BooleanArray(8)
        // editor: selisih sentuhan terhadap titik tengah elemen
        var offX = 0f; var offY = 0f
        // tombol rentang: geser = gulir, ketuk = tekan huruf di bawah jari (seperti RangeScroller Winlator)
        var scrolling = false
        var rBinding = Binding.NONE
        var rPressed = false
        var downTime = 0L
        var lastPos = 0f
        var pressTask: Runnable? = null
    }

    /** Offset gulir (`currentOffset` Winlator) tiap tombol rentang; bertahan antar sentuhan. */
    private val rangeScroll = HashMap<Element, Float>()

    private val touches = HashMap<Int, Touch>()
    private val toggled = HashSet<Element>()
    /** Sama dengan `snappingSize` Winlator: lebar / 100 dengan pembagian bilangan bulat. */
    private val snap: Float get() = max(1, width / 100).toFloat()

    /** Jari-jari stik dinamis dalam piksel (sama dengan `getDynamicStickRadiusPixels` Winlator). */
    private fun dynRadius(e: Element): Float =
        max(1, (min(width, height) * e.stickRadiusRatio * e.scale).toInt()).toFloat()

    // ---- geometri

    private fun half(e: Element): Pair<Float, Float> {
        val s = snap
        var hw = 0f
        var hh = 0f
        when (e.type) {
            Element.Type.BUTTON -> when (e.shape) {
                Element.Shape.RECT, Element.Shape.ROUND_RECT -> { hw = s * 4; hh = s * 2 }
                Element.Shape.SQUARE -> { hw = s * 2.5f; hh = s * 2.5f }
                Element.Shape.CIRCLE -> { hw = s * 3; hh = s * 3 }
            }
            Element.Type.D_PAD -> { hw = s * 8; hh = s * 8 }
            Element.Type.STICK, Element.Type.TRACKPAD -> { hw = s * 6; hh = s * 6 }
            Element.Type.DYNAMIC_STICK -> return width * e.areaWidthRatio * 0.5f to height * e.areaHeightRatio * 0.5f
            Element.Type.RANGE_BUTTON -> {
                hw = s * ((e.bindings.size * 4) / 2)
                hh = s * 2
                if (e.orientation == 1) { val t = hw; hw = hh; hh = t }
            }
        }
        return hw * e.scale to hh * e.scale
    }

    private fun bounds(e: Element, out: RectF = rect): RectF {
        val (hw, hh) = half(e)
        val cx = e.x * width
        val cy = e.y * height
        out.set(cx - hw, cy - hh, cx + hw, cy + hh)
        return out
    }

    private fun hit(e: Element, x: Float, y: Float) = bounds(e, RectF()).contains(x, y)

    private fun topHit(x: Float, y: Float): Element? =
        profile?.elements?.lastOrNull { hit(it, x, y) }

    // ---- sentuhan

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val prof = profile
        val idx = ev.actionIndex
        val id = ev.getPointerId(idx)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = ev.getX(idx)
                val y = ev.getY(idx)
                if (prof == null || (!controlsShown && !editing)) return emptyTouch(ev, id)
                val e = topHit(x, y)
                if (editing) {
                    selected = e
                    onSelect(e)
                    if (e != null) touches[id] = Touch(e, id).also {
                        it.lastX = x; it.lastY = y; it.startX = x; it.startY = y
                        it.offX = x - e.x * width; it.offY = y - e.y * height
                    }
                    return true
                }
                if (e == null) {
                    // stik dinamis: sentuhan di area kosong yang cocok membuat stik di titik itu
                    val dyn = prof.elements.lastOrNull { it.type == Element.Type.DYNAMIC_STICK && hit(it, x, y) }
                    if (dyn != null && touches.values.none { it.e === dyn }) {
                        touches[id] = Touch(dyn, id).also { it.startX = x; it.startY = y; it.lastX = x; it.lastY = y; it.thumbX = x; it.thumbY = y }
                        invalidate()
                        return true
                    }
                    return emptyTouch(ev, id)
                }
                if (touches.values.any { it.e === e }) return true
                val t = Touch(e, id).also { it.startX = x; it.startY = y; it.lastX = x; it.lastY = y; it.thumbX = x; it.thumbY = y }
                touches[id] = t
                down(t, x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var used = passIds.isNotEmpty()
                for (i in 0 until ev.pointerCount) {
                    val pid = ev.getPointerId(i)
                    val t = touches[pid] ?: continue
                    used = true
                    if (editing) editMove(t, ev.getX(i), ev.getY(i)) else move(t, ev.getX(i), ev.getY(i))
                }
                if (passIds.isNotEmpty()) forward(ev, MotionEvent.ACTION_MOVE, -1)
                if (used) invalidate()
                return used
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = ev.actionMasked == MotionEvent.ACTION_CANCEL
                if (cancel) {
                    if (passIds.isNotEmpty()) { forward(ev, MotionEvent.ACTION_CANCEL, -1); passIds.clear() }
                    releaseAll()
                    return true
                }
                if (id in passIds) {
                    forward(ev, if (passIds.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id)
                    passIds.remove(id)
                    return true
                }
                val t = touches.remove(id) ?: return false
                if (!editing) up(t) else onEdited()
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * Sentuhan di area kosong. Jari pertama (ACTION_DOWN): bila View ini belum menjadi sasaran sentuh, layar di bawahnya
     * menerima sendiri (false). Jari berikutnya saat View ini sudah menahan tombol/tombol mengambang: sistem sudah
     * menyerahkannya ke sini, jadi diteruskan ke layar lewat [passthrough] (seperti touchpadView.onTouchEvent Winlator).
     */
    private fun emptyTouch(ev: MotionEvent, id: Int): Boolean {
        if (swallowEmptyTouches || editing) return swallowEmptyTouches
        val isTarget = touches.isNotEmpty() || passIds.isNotEmpty()
        if (!isTarget || passthrough == null) return false
        if (passIds.isEmpty()) passDownTime = ev.eventTime
        passIds.add(id)
        forward(ev, if (passIds.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
        return true
    }

    /**
     * Kirim ke [passthrough] MotionEvent yang hanya berisi jari-jari teruskan ([passIds]). [actionId] = id jari yang
     * turun/naik (-1 untuk MOVE/CANCEL); ACTION_DOWN/UP tunggal tidak membawa indeks jari.
     */
    private fun forward(ev: MotionEvent, action: Int, actionId: Int) {
        val sink = passthrough ?: return
        val n = ev.pointerCount
        val props = ArrayList<MotionEvent.PointerProperties>(n)
        val coords = ArrayList<MotionEvent.PointerCoords>(n)
        var actionIndexInSub = 0
        for (i in 0 until n) {
            val pid = ev.getPointerId(i)
            if (pid !in passIds) continue
            if (pid == actionId) actionIndexInSub = props.size
            props.add(MotionEvent.PointerProperties().also { ev.getPointerProperties(i, it) })
            coords.add(MotionEvent.PointerCoords().also { ev.getPointerCoords(i, it) })
        }
        if (props.isEmpty()) return
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndexInSub shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val sub = MotionEvent.obtain(
            passDownTime, ev.eventTime, a, props.size,
            props.toTypedArray(), coords.toTypedArray(),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags,
        )
        try { sink(sub) } finally { sub.recycle() }
    }

    private fun editMove(t: Touch, x: Float, y: Float) {
        val e = t.e
        if (width <= 0 || height <= 0) return
        // Elemen mengikuti jari tanpa putus dan tanpa snap ke grid sama sekali: posisi = sentuhan dikurangi selisih
        // awal terhadap titik tengah elemen.
        e.x = ((x - t.offX) / width).coerceIn(0f, 1f)
        e.y = ((y - t.offY) / height).coerceIn(0f, 1f)
        t.lastX = x; t.lastY = y
    }

    private fun bind(t: Touch, i: Int, on: Boolean) {
        if (i !in t.e.bindings.indices) return
        val was = if (i < t.active.size) t.active[i] else false
        if (was == on) return
        if (i < t.active.size) t.active[i] = on
        dispatcher?.handle(t.e.bindings[i], on)
    }

    private fun down(t: Touch, x: Float, y: Float) {
        val e = t.e
        when (e.type) {
            Element.Type.BUTTON -> {
                if (e.mouseMoveMode) { for (i in e.bindings.indices) bind(t, i, true); return }
                if (e.toggleSwitch && e in toggled) return
                for (i in e.bindings.indices) bind(t, i, true)
            }
            Element.Type.RANGE_BUTTON -> rangeDown(t, x, y)
            else -> move(t, x, y)
        }
    }

    private fun move(t: Touch, x: Float, y: Float) {
        val e = t.e
        val b = bounds(e, RectF())
        when (e.type) {
            Element.Type.BUTTON -> {
                if (e.mouseMoveMode) dispatcher?.moveCursor(x - t.lastX, y - t.lastY)
            }
            Element.Type.D_PAD -> {
                val hw = b.width() / 2f
                val hh = b.height() / 2f
                val nx = (x - b.centerX()) / hw
                val ny = (y - b.centerY()) / hh
                val cross = 0.3f
                bind(t, 0, ny < -cross)
                bind(t, 1, nx > cross)
                bind(t, 2, ny > cross)
                bind(t, 3, nx < -cross)
            }
            Element.Type.STICK, Element.Type.DYNAMIC_STICK -> {
                val cx: Float; val cy: Float; val radius: Float
                if (e.type == Element.Type.STICK) {
                    cx = b.centerX(); cy = b.centerY(); radius = b.width() / 2f
                } else {
                    cx = t.startX; cy = t.startY; radius = dynRadius(e)
                }
                var dx = (x - cx) / radius
                var dy = (y - cy) / radius
                val len = hypot(dx, dy)
                if (len > 1f) { dx /= len; dy /= len }
                t.thumbX = cx + dx * radius
                t.thumbY = cy + dy * radius
                applyAxis(t, dx, dy, e.deadZone)
            }
            Element.Type.TRACKPAD -> {
                val ddx = x - t.lastX
                val ddy = y - t.lastY
                val hasMove = e.bindings.any { it.isMouseMove }
                if (hasMove) {
                    dispatcher?.moveCursor(ddx, ddy)
                } else {
                    // delta jari -> sumbu analog sesaat (mis. kamera/stik kanan), dilepas bila jari diam
                    val k = 1f / (snap * 1.2f)
                    applyAxis(t, (ddx * k).coerceIn(-1f, 1f), (ddy * k).coerceIn(-1f, 1f), 0f)
                    t.releaseTask?.let { main.removeCallbacks(it) }
                    val r = Runnable { applyAxis(t, 0f, 0f, 0f) }
                    t.releaseTask = r
                    main.postDelayed(r, 40)
                }
            }
            Element.Type.RANGE_BUTTON -> rangeMove(t, x, y)
        }
        t.lastX = x; t.lastY = y
    }

    /** [dx]/[dy] -1..1 (y ke bawah) ke empat binding [atas, kanan, bawah, kiri]. */
    private fun applyAxis(t: Touch, dx: Float, dy: Float, dead: Float) {
        val e = t.e
        if (e.bindings.size < 4) return
        fun axis(i: Int, v: Float) {
            val b = e.bindings[i]
            val mag = abs(v)
            if (mag <= dead || mag == 0f) { bind(t, i, false); return }
            val analog = b.isGamepad && b.name.contains("THUMB") || b.isMouseMove
            if (analog) {
                // dengan zona mati: skala ulang sisa jangkauan supaya mulai dari 0
                val scaled = ((mag - dead) / (1f - dead)).coerceIn(0f, 1f)
                dispatcher?.handle(b, true, if (scaled == 0f) 0.001f else scaled)
                if (i < t.active.size) t.active[i] = true
            } else {
                bind(t, i, mag > max(dead, 0.3f))
            }
        }
        axis(0, if (dy < 0) dy else 0f)
        axis(1, if (dx > 0) dx else 0f)
        axis(2, if (dy > 0) dy else 0f)
        axis(3, if (dx < 0) dx else 0f)
    }

    // ---- tombol rentang (port RangeScroller Winlator)

    private fun rangeOf(e: Element) = e.range ?: Element.Range.FROM_A_TO_Z
    private fun rangeCount(e: Element) = max(1, e.bindings.size)
    private fun rangeCell(e: Element): Float {
        val b = bounds(e, RectF())
        return max(b.width(), b.height()) / rangeCount(e)
    }

    /** Offset gulir 0..ukuran gulir (`getScrollOffset` Winlator). */
    private fun rangeOffset(e: Element): Float {
        val size = rangeCell(e) * rangeOf(e).max
        var o = -(rangeScroll[e] ?: 0f) % size
        if (o < 0) o += size
        return o
    }

    /** Huruf/angka/F/NP di bawah titik ([x],[y]) (`getBindingByPosition` Winlator). */
    private fun rangeBindingAt(e: Element, x: Float, y: Float): Binding {
        val b = bounds(e, RectF())
        val range = rangeOf(e)
        val off = (if (e.orientation == 1) y - b.top else x - b.left) - (rangeScroll[e] ?: 0f)
        var index = floor((off / rangeCell(e)) % range.max).toInt()
        if (index < 0) index += range.max
        return Element.rangeBinding(range, index)
    }

    private fun rangeDown(t: Touch, x: Float, y: Float) {
        val e = t.e
        t.scrolling = false
        t.rPressed = false
        t.rBinding = rangeBindingAt(e, x, y)
        t.downTime = SystemClock.uptimeMillis()
        t.lastPos = if (e.orientation == 1) y else x
        t.pressTask?.let { main.removeCallbacks(it) }
        // tahan lebih dari 200 md = tombol ditahan; ketuk singkat ditangani saat jari diangkat
        val task = Runnable {
            if (!t.scrolling && !t.rPressed) { t.rPressed = true; dispatcher?.handle(t.rBinding, true) }
        }
        t.pressTask = task
        main.postDelayed(task, 200)
    }

    private fun rangeMove(t: Touch, x: Float, y: Float) {
        val e = t.e
        val pos = if (e.orientation == 1) y else x
        val delta = pos - t.lastPos
        if (!t.scrolling && abs(delta) >= 10f) {
            t.scrolling = true
            t.pressTask?.let { main.removeCallbacks(it) }
        }
        if (t.scrolling) {
            rangeScroll[e] = (rangeScroll[e] ?: 0f) + delta
            t.lastPos = pos
        }
    }

    private fun rangeUp(t: Touch) {
        t.pressTask?.let { main.removeCallbacks(it) }
        val b = t.rBinding
        val tap = SystemClock.uptimeMillis() - t.downTime < 200
        if (tap && !t.scrolling && !t.rPressed) {
            dispatcher?.handle(b, true)
            main.postDelayed({ dispatcher?.handle(b, false) }, 30)
        } else if (t.rPressed) {
            dispatcher?.handle(b, false)
        }
        t.rPressed = false
    }

    private fun up(t: Touch) {
        val e = t.e
        t.releaseTask?.let { main.removeCallbacks(it) }
        when (e.type) {
            Element.Type.BUTTON -> {
                if (e.toggleSwitch) {
                    // ketuk pertama menahan tombol, ketuk kedua melepas
                    if (e in toggled) { toggled.remove(e); for (b in e.bindings) dispatcher?.handle(b, false) }
                    else toggled.add(e)
                } else for (i in e.bindings.indices) bind(t, i, false)
            }
            Element.Type.RANGE_BUTTON -> rangeUp(t)
            else -> for (i in e.bindings.indices) bind(t, i, false)
        }
    }

    /** Lepas semua tombol/sumbu yang masih ditahan (profil diganti, controller disembunyikan, layar berhenti). */
    fun releaseAll() {
        for (t in touches.values.toList()) up(t)
        touches.clear()
        for (e in toggled.toList()) for (b in e.bindings) dispatcher?.handle(b, false)
        toggled.clear()
        passIds.clear()
        dispatcher?.releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    // ---- gambar

    private val darkSurface = 0xFF06111D.toInt()
    private val edgeSoft = 0xFF7FA8D8.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val path = Path()

    /** Warna tema: warna elemen, lalu warna profil, lalu putih (sama dengan `getThemeColor` Winlator). */
    private fun themeColor(e: Element): Int =
        if (e.customColor != 0) e.customColor else (profile?.themeColor?.takeIf { it != 0 } ?: white)

    /** Opasitas elemen x opasitas overlay; di editor minimal 0,35 (`getEffectiveOpacity` Winlator). */
    private fun effOpacity(e: Element): Float {
        val combined = e.opacity * overlayAlpha
        return if (editing) max(0.35f, combined) else combined
    }

    /** `withAlpha` Winlator: [alpha] 0..255 dikali opasitas efektif. */
    private fun wa(e: Element, color: Int, alpha: Int): Int {
        val a = (alpha * effOpacity(e)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** `withStickFeedbackAlpha` Winlator: umpan balik stik yang disentuh tetap terbaca di overlay transparan. */
    private fun sa(e: Element, color: Int, alpha: Int): Int {
        var eff = effOpacity(e)
        if (e.opacity > 0f && overlayAlpha > 0f) eff = max(eff, 0.7f)
        val a = min(255, (alpha * eff).toInt())
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun setup(style: Paint.Style, color: Int, strokeWidth: Float) {
        paint.style = style
        paint.color = color
        paint.strokeWidth = strokeWidth
        paint.textAlign = Paint.Align.CENTER
        paint.isAntiAlias = true
    }

    /** Opasitas overlay global (pengaturan "Overlay Opacity" seperti Winlator), dikalikan opasitas tiap elemen. */
    private var overlayAlpha = 0.85f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        overlayAlpha = ProfileStore.overlayOpacity(context) / 100f
        val prof = profile
        if (prof != null && (controlsShown || editing)) {
            if (editing) drawGrid(c)
            for (e in prof.elements) drawElement(c, e)
        }
    }

    private fun drawGrid(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x22FFFFFF
        val s = snap * 5
        if (s <= 0f) return
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), paint); x += s }
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, paint); y += s }
    }

    private fun drawElement(c: Canvas, e: Element) {
        val b = bounds(e, RectF())
        val pointer = !editing && touches.values.any { it.e === e }
        // sama dengan isVisuallyPressed Winlator: terpilih (editor) atau sedang disentuh/ditahan
        val active = (editing && e === selected) || pointer || e in toggled
        val theme = themeColor(e)
        val sn = snap
        val sc = e.scale

        when (e.type) {
            Element.Type.BUTTON -> {
                val cx = b.centerX(); val cy = b.centerY()
                val w = b.width(); val h = b.height()
                val label = displayText(e)
                when (e.shape) {
                    Element.Shape.CIRCLE -> softCircle(c, e, cx, cy, w * 0.5f, active, theme, true)
                    Element.Shape.RECT, Element.Shape.ROUND_RECT ->
                        softRoundRect(c, e, b.left, b.top, b.right, b.bottom, h * 0.45f, active)
                    Element.Shape.SQUARE ->
                        softRoundRect(c, e, b.left, b.top, b.right, b.bottom, sn * 1.05f * sc, active)
                }
                centeredText(c, e, label, cx, cy, w * 0.55f, sn * 2.1f * sc, if (active) white else theme)
            }
            Element.Type.D_PAD -> {
                val cx = b.centerX(); val cy = b.centerY()
                val base = min(b.width(), b.height())
                val piece = base * 0.30f
                val dist = piece * 1.15f
                val centerSize = piece * 0.26f
                setup(Paint.Style.FILL, wa(e, darkSurface, if (active) 72 else 42), 0f)
                c.drawRoundRect(
                    cx - centerSize * 0.5f, cy - centerSize * 0.5f, cx + centerSize * 0.5f, cy + centerSize * 0.5f,
                    centerSize * 0.22f, centerSize * 0.22f, paint,
                )
                dpadPiece(c, e, cx, cy - dist, piece, 0, isDir(e, 0))
                dpadPiece(c, e, cx + dist, cy, piece, 1, isDir(e, 1))
                dpadPiece(c, e, cx, cy + dist, piece, 2, isDir(e, 2))
                dpadPiece(c, e, cx - dist, cy, piece, 3, isDir(e, 3))
            }
            Element.Type.STICK -> {
                val cx = b.centerX(); val cy = b.centerY()
                val outer = b.height() * 0.5f
                val innerRing = outer * 0.52f
                val thumbRadius = outer * 0.42f
                setup(Paint.Style.FILL, wa(e, darkSurface, if (active) 155 else 118), 0f)
                c.drawCircle(cx, cy, outer, paint)
                setup(Paint.Style.STROKE, wa(e, edgeSoft, if (active) 62 else 26), max(1f, sn * 0.20f * sc))
                c.drawCircle(cx, cy, outer * 0.96f, paint)
                setup(Paint.Style.STROKE, wa(e, theme, if (active) 72 else 20), max(1f, sn * 0.50f * sc))
                c.drawCircle(cx, cy, outer * 0.98f, paint)
                setup(
                    Paint.Style.STROKE,
                    if (pointer) sa(e, theme, 235) else wa(e, theme, if (active) 235 else 205),
                    max(1f, sn * 0.22f * sc),
                )
                c.drawCircle(cx, cy, innerRing, paint)
                val t = if (pointer) touches.values.firstOrNull { it.e === e } else null
                val tx = t?.thumbX ?: cx
                val ty = t?.thumbY ?: cy
                setup(Paint.Style.FILL, if (pointer) sa(e, theme, 180) else wa(e, darkSurface, 190), 0f)
                c.drawCircle(tx, ty, thumbRadius, paint)
                setup(
                    Paint.Style.STROKE,
                    if (pointer) sa(e, white, 245) else wa(e, edgeSoft, 45),
                    max(1f, sn * 0.12f * sc),
                )
                c.drawCircle(tx, ty, thumbRadius * 0.96f, paint)
            }
            Element.Type.DYNAMIC_STICK -> {
                if (editing) {
                    val corner = sn * 1.5f
                    val sel = e === selected
                    setup(Paint.Style.FILL, wa(e, theme, if (sel) 48 else 24), 0f)
                    c.drawRoundRect(b, corner, corner, paint)
                    setup(Paint.Style.STROKE, wa(e, theme, if (sel) 210 else 100), max(1f, sn * 0.15f))
                    c.drawRoundRect(b, corner, corner, paint)
                    centeredText(c, e, "Dynamic Stick", b.centerX(), b.centerY(), b.width() * 0.8f, sn * 1.8f, wa(e, theme, 210))
                }
                val t = if (pointer) touches.values.firstOrNull { it.e === e } else null
                if (t != null) {
                    val r = dynRadius(e)
                    setup(Paint.Style.FILL, wa(e, darkSurface, 118), 0f)
                    c.drawCircle(t.startX, t.startY, r, paint)
                    setup(Paint.Style.STROKE, sa(e, theme, 225), max(1f, sn * 0.22f * sc))
                    c.drawCircle(t.startX, t.startY, r, paint)
                    setup(Paint.Style.FILL, sa(e, theme, 180), 0f)
                    c.drawCircle(t.thumbX, t.thumbY, r * 0.42f, paint)
                    setup(Paint.Style.STROKE, sa(e, white, 245), max(1f, sn * 0.12f * sc))
                    c.drawCircle(t.thumbX, t.thumbY, r * 0.42f, paint)
                }
            }
            Element.Type.TRACKPAD -> {
                softRoundRect(c, e, b.left, b.top, b.right, b.bottom, b.height() * 0.18f, active)
                mouseGlyph(c, e, b.centerX(), b.centerY(), min(b.width(), b.height()) * 0.45f, wa(e, theme, if (active) 220 else 95))
            }
            Element.Type.RANGE_BUTTON -> {
                val radius = sn * 0.9f * sc
                val n = rangeCount(e)
                val range = rangeOf(e)
                val vertical = e.orientation == 1
                val minText = sn * 2f * sc
                val stroke = max(1f, sn * 0.16f * sc)
                val elementSize = rangeCell(e)
                val offset = rangeOffset(e)
                val rt = if (pointer) touches.values.firstOrNull { it.e === e } else null
                softRoundRect(c, e, b.left, b.top, b.right, b.bottom, radius, active)
                c.save()
                path.reset()
                path.addRoundRect(b, radius, radius, Path.Direction.CW)
                c.clipPath(path)
                var from = floor((offset / elementSize) % range.max).toInt()
                if (from < 0) from += range.max
                val begin = if (vertical) b.top else b.left
                val end = if (vertical) b.bottom else b.right
                var start = begin - offset % elementSize
                for (k in 0..n) {
                    val index = (from + k) % range.max
                    if (start < end && start + elementSize > begin) {
                        val pressed = rt != null && !rt.scrolling && rt.rBinding == Element.rangeBinding(range, index)
                        if (pressed) {
                            setup(Paint.Style.FILL, wa(e, theme, 150), 0f)
                            if (vertical) c.drawRect(b.left, start, b.right, start + elementSize, paint)
                            else c.drawRect(start, b.top, start + elementSize, b.bottom, paint)
                        }
                        val tx = if (vertical) b.centerX() else start + elementSize * 0.5f
                        val ty = if (vertical) start + elementSize * 0.5f else b.centerY()
                        val maxW = if (vertical) b.width() - stroke * 2 else elementSize - stroke * 2
                        centeredText(c, e, rangeText(e, index), tx, ty, maxW, minText, if (pressed) white else theme)
                    }
                    start += elementSize
                }
                c.restore()
            }
        }
    }

    private fun isDir(e: Element, i: Int): Boolean = touches.values.any { it.e === e && it.active.getOrElse(i) { false } }

    /** `drawSoftRoundRect` Winlator: halo tema, tepi lembut, isi gelap/tema saat aktif, garis dalam tipis. */
    private fun softRoundRect(c: Canvas, e: Element, l: Float, t: Float, r: Float, b: Float, radius: Float, active: Boolean) {
        val theme = themeColor(e)
        val sn = snap
        val sc = e.scale
        setup(Paint.Style.STROKE, wa(e, theme, if (active) 64 else 16), max(1f, sn * 0.55f * sc))
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
        setup(Paint.Style.STROKE, wa(e, edgeSoft, if (active) 70 else 28), max(1f, sn * 0.22f * sc))
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
        setup(Paint.Style.FILL, if (active) wa(e, theme, 190) else wa(e, darkSurface, 135), 0f)
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
        val inset = max(1f, sn * 0.35f * sc)
        val inner = max(0f, radius - inset)
        setup(Paint.Style.STROKE, wa(e, white, if (active) 58 else 24), max(1f, sn * 0.08f * sc))
        c.drawRoundRect(l + inset, t + inset, r - inset, b - inset, inner, inner, paint)
    }

    /** `drawSoftCircle` Winlator. */
    private fun softCircle(c: Canvas, e: Element, cx: Float, cy: Float, radius: Float, active: Boolean, accent: Int, fillActive: Boolean) {
        val theme = themeColor(e)
        val sn = snap
        val sc = e.scale
        setup(Paint.Style.STROKE, wa(e, theme, if (active) 72 else 18), max(1f, sn * 0.55f * sc))
        c.drawCircle(cx, cy, radius, paint)
        setup(Paint.Style.STROKE, wa(e, edgeSoft, if (active) 74 else 30), max(1f, sn * 0.20f * sc))
        c.drawCircle(cx, cy, radius, paint)
        setup(Paint.Style.FILL, if (active && fillActive) wa(e, accent, 190) else wa(e, darkSurface, 140), 0f)
        c.drawCircle(cx, cy, radius, paint)
        setup(Paint.Style.STROKE, wa(e, white, if (active) 44 else 18), max(1f, sn * 0.08f * sc))
        c.drawCircle(cx, cy, radius * 0.88f, paint)
    }

    /** `drawTriangle` Winlator; [dir] 0 atas, 1 kanan, 2 bawah, 3 kiri. */
    private fun triangle(c: Canvas, cx: Float, cy: Float, size: Float, dir: Int, color: Int) {
        path.reset()
        when (dir) {
            0 -> { path.moveTo(cx, cy - size); path.lineTo(cx - size * 0.85f, cy + size * 0.65f); path.lineTo(cx + size * 0.85f, cy + size * 0.65f) }
            1 -> { path.moveTo(cx + size, cy); path.lineTo(cx - size * 0.65f, cy - size * 0.85f); path.lineTo(cx - size * 0.65f, cy + size * 0.85f) }
            2 -> { path.moveTo(cx, cy + size); path.lineTo(cx - size * 0.85f, cy - size * 0.65f); path.lineTo(cx + size * 0.85f, cy - size * 0.65f) }
            else -> { path.moveTo(cx - size, cy); path.lineTo(cx + size * 0.65f, cy - size * 0.85f); path.lineTo(cx + size * 0.65f, cy + size * 0.85f) }
        }
        path.close()
        setup(Paint.Style.FILL, color, 0f)
        c.drawPath(path, paint)
    }

    /** `drawDPadPiece` Winlator. */
    private fun dpadPiece(c: Canvas, e: Element, cx: Float, cy: Float, size: Float, dir: Int, active: Boolean) {
        softRoundRect(c, e, cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f, size * 0.28f, active)
        triangle(c, cx, cy, size * 0.22f, dir, if (active) wa(e, white, 255) else wa(e, themeColor(e), 255))
    }

    /** `drawMouseGlyph` Winlator (lambang mouse di trackpad). */
    private fun mouseGlyph(c: Canvas, e: Element, cx: Float, cy: Float, size: Float, color: Int) {
        val w = size * 0.42f
        val h = size * 0.62f
        val r = w * 0.5f
        setup(Paint.Style.STROKE, color, max(1f, size * 0.035f))
        c.drawRoundRect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f, r, r, paint)
        c.drawLine(cx, cy - h * 0.48f, cx, cy - h * 0.15f, paint)
    }

    /** `drawCenteredText` Winlator: ukuran pas lebar [maxW], dibatasi [maxSize], warna ikut opasitas efektif. */
    private fun centeredText(c: Canvas, e: Element, text: String, cx: Float, cy: Float, maxW: Float, maxSize: Float, color: Int) {
        if (text.isEmpty()) return
        setup(Paint.Style.FILL, wa(e, color, (color ushr 24) and 0xFF), 0f)
        paint.textSize = 48f
        val w = paint.measureText(text)
        if (w <= 0f) return
        paint.textSize = min(48f * maxW / w, maxSize)
        c.drawText(text, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint)
    }

    /** `getBindingTextAt` Winlator. */
    private fun bindingText(e: Element, i: Int): String {
        val b = e.bindings.getOrElse(i) { Binding.NONE }
        val text = b.label.replace("NUMPAD ", "NP").replace("BUTTON ", "")
        if (text.length > 7) {
            val sb = StringBuilder()
            for (part in text.split(" ")) if (part.isNotEmpty()) sb.append(part[0])
            return (if (b.isMouse) "M" else "") + sb
        }
        return text
    }

    /** `getDisplayText` Winlator. */
    private fun displayText(e: Element): String {
        if (e.text.isNotEmpty()) return e.text
        if (e.type == Element.Type.BUTTON) {
            val sb = StringBuilder()
            for (i in e.bindings.indices) {
                if (e.bindings[i] != Binding.NONE) {
                    if (sb.isNotEmpty()) sb.append("+")
                    sb.append(bindingText(e, i))
                }
            }
            return if (sb.isNotEmpty()) sb.toString() else bindingText(e, 0)
        }
        return bindingText(e, 0)
    }

    /** `getRangeTextForIndex` Winlator. */
    private fun rangeText(e: Element, i: Int): String = when (rangeOf(e)) {
        Element.Range.FROM_A_TO_Z -> (65 + i).toChar().toString()
        Element.Range.FROM_0_TO_9 -> ((i + 1) % 10).toString()
        Element.Range.FROM_F1_TO_F12 -> "F${i + 1}"
        Element.Range.FROM_NP0_TO_NP9 -> "NP${(i + 1) % 10}"
    }

    /** Ukuran bantu untuk editor: tinggi/lebar elemen dalam piksel layar. */
    fun elementBounds(e: Element): RectF = bounds(e, RectF())
}
