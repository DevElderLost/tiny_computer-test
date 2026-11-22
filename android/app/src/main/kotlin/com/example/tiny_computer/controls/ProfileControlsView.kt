package com.example.tiny_computer.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Controller layar gaya Winlator (port `InputControlsView` + `ControlElement`): tombol keyboard/mouse/gamepad,
 * D-pad, stik, stik dinamis, trackpad, dan tombol rentang (A-Z, 0-9, F1-F12, numpad), semuanya dari [Profile].
 * Ditaruh sebagai View saudara di atas layar compositor (bukan di dalam Compose), sehingga sentuhan di luar elemen
 * jatuh ke layar compositor seperti biasa.
 *
 * Tombol mengambang kecil ([ProfileStore.floatingButton]): ketuk = tampil/sembunyikan semua tombol profil, tekan
 * lama = buka keyboard PC ([onKeyboard]), seret = pindahkan.
 *
 * [editing] = mode editor ([ControlsEditorActivity]): tidak mengirim masukan; seret elemen untuk memindahkan, ketuk
 * untuk memilih ([onSelect]).
 */
class ProfileControlsView(
    context: Context,
    private val dispatcher: BindingDispatcher?,
    private val editing: Boolean = false,
    private val onKeyboard: () -> Unit = {},
) : View(context) {

    var profile: Profile? = null
        set(value) { field = value; releaseAll(); selected = null; invalidate() }

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
        var cell = -1
        var releaseTask: Runnable? = null
        val active = BooleanArray(8)
    }

    private val touches = HashMap<Int, Touch>()
    private val toggled = HashSet<Element>()
    // tombol mengambang
    private var fabPointer = -1
    private var fabMoved = false
    private var fabLong = false
    private var fabDownX = 0f
    private var fabDownY = 0f
    private val fabLongTask = Runnable {
        if (fabPointer != -1 && !fabMoved) {
            fabLong = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onKeyboard()
        }
    }

    private val snap: Float get() = width / 100f

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

    // ---- tombol mengambang

    private fun fabRadius() = 20f * density
    private fun fabCenter(): Pair<Float, Float> {
        val (px, py) = ProfileStore.floatingPos(context)
        val r = fabRadius() + 4 * density
        return (px * width).coerceIn(r, width - r) to (py * height).coerceIn(r, height - r)
    }

    private fun fabVisible() = !editing && ProfileStore.floatingButton(context)

    private fun fabHit(x: Float, y: Float): Boolean {
        if (!fabVisible()) return false
        val (cx, cy) = fabCenter()
        return hypot(x - cx, y - cy) <= fabRadius() * 1.25f
    }

    // ---- sentuhan

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val prof = profile
        val idx = ev.actionIndex
        val id = ev.getPointerId(idx)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = ev.getX(idx)
                val y = ev.getY(idx)
                if (fabPointer == -1 && fabHit(x, y)) {
                    fabPointer = id; fabMoved = false; fabLong = false
                    fabDownX = x; fabDownY = y
                    main.postDelayed(fabLongTask, 450)
                    return true
                }
                if (prof == null || (!controlsShown && !editing)) return emptyTouch(ev, id)
                val e = topHit(x, y)
                if (editing) {
                    selected = e
                    onSelect(e)
                    if (e != null) touches[id] = Touch(e, id).also { it.lastX = x; it.lastY = y }
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
                var used = fabPointer != -1 || passIds.isNotEmpty()
                for (i in 0 until ev.pointerCount) {
                    val pid = ev.getPointerId(i)
                    if (pid == fabPointer) { fabMove(ev.getX(i), ev.getY(i)); continue }
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
                if (id == fabPointer) { fabUp(); return true }
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
        val isTarget = fabPointer != -1 || touches.isNotEmpty() || passIds.isNotEmpty()
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

    private fun fabMove(x: Float, y: Float) {
        if (!fabMoved && hypot(x - fabDownX, y - fabDownY) > slop * 1.5f) {
            fabMoved = true
            main.removeCallbacks(fabLongTask)
        }
        if (fabMoved && width > 0 && height > 0) ProfileStore.setFloatingPos(context, x / width, y / height)
    }

    private fun fabUp() {
        main.removeCallbacks(fabLongTask)
        if (!fabMoved && !fabLong) {
            controlsShown = !controlsShown
        }
        fabPointer = -1
        invalidate()
    }

    private fun editMove(t: Touch, x: Float, y: Float) {
        val e = t.e
        if (width <= 0 || height <= 0) return
        // geser mengikuti jari, ke kelipatan grid 1% lebar supaya rapi seperti editor Winlator
        val gx = snap
        val nx = (e.x * width + (x - t.lastX))
        val ny = (e.y * height + (y - t.lastY))
        t.lastX = x; t.lastY = y
        e.x = (Math.round(nx / gx) * gx / width).coerceIn(0f, 1f)
        e.y = (Math.round(ny / gx) * gx / height).coerceIn(0f, 1f)
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
            Element.Type.RANGE_BUTTON -> rangeMove(t, x, y)
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
                    cx = t.startX; cy = t.startY; radius = width * e.stickRadiusRatio
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

    private fun rangeMove(t: Touch, x: Float, y: Float) {
        val e = t.e
        val b = bounds(e, RectF())
        val n = e.bindings.size
        val cell = if (e.orientation == 1) ((y - b.top) / b.height() * n).toInt() else ((x - b.left) / b.width() * n).toInt()
        val c = cell.coerceIn(0, n - 1)
        if (c == t.cell) return
        if (t.cell >= 0) dispatcher?.handle(e.bindings[t.cell], false)
        t.cell = c
        dispatcher?.handle(e.bindings[c], true)
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
            Element.Type.RANGE_BUTTON -> if (t.cell >= 0) dispatcher?.handle(e.bindings[t.cell], false)
            else -> for (i in e.bindings.indices) bind(t, i, false)
        }
    }

    /** Lepas semua tombol/sumbu yang masih ditahan (profil diganti, controller disembunyikan, layar berhenti). */
    fun releaseAll() {
        main.removeCallbacks(fabLongTask)
        for (t in touches.values.toList()) up(t)
        touches.clear()
        for (e in toggled.toList()) for (b in e.bindings) dispatcher?.handle(b, false)
        toggled.clear()
        fabPointer = -1
        passIds.clear()
        dispatcher?.releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    // ---- gambar

    private fun themeColor(e: Element): Int =
        if (e.customColor != 0) e.customColor else (profile?.themeColor?.takeIf { it != 0 } ?: 0xFF1A9FFF.toInt())

    private fun withAlpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0xFFFFFF)

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
        if (fabVisible()) drawFab(c)
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
        val on = touches.values.any { it.e === e } || e in toggled
        val color = themeColor(e)
        val a = if (editing) 1f else e.opacity * overlayAlpha
        val fill = if (on) withAlpha(color, 0.63f * a) else withAlpha(darken(color, 0.12f), 0.31f * a)
        val stroke = if (on) withAlpha(lighten(color, 0.7f), 0.9f * a) else withAlpha(lighten(color, 0.25f), 0.59f * a)
        val sw = max(1.5f, snap * 0.3f * e.scale)
        val text = withAlpha(lighten(color, 0.75f), 0.82f * a)

        fun shape(r: RectF, circle: Boolean, radius: Float) {
            paint.style = Paint.Style.FILL; paint.color = fill
            if (circle) c.drawOval(r, paint) else c.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke
            if (circle) c.drawOval(r, paint) else c.drawRoundRect(r, radius, radius, paint)
        }

        when (e.type) {
            Element.Type.BUTTON -> {
                shape(b, e.shape == Element.Shape.CIRCLE, if (e.shape == Element.Shape.RECT) 4f else b.height() * 0.3f)
                label(c, e.text.ifEmpty { e.bindings.firstOrNull()?.label ?: "" }, b.centerX(), b.centerY(), b.width() * 0.8f, text)
            }
            Element.Type.D_PAD -> {
                val arm = b.width() / 3f
                val cx = b.centerX(); val cy = b.centerY()
                val on0 = isDir(e, 0); val on1 = isDir(e, 1); val on2 = isDir(e, 2); val on3 = isDir(e, 3)
                fun arrow(l: Float, t: Float, r: Float, bt: Float, pressed: Boolean) {
                    rect.set(l, t, r, bt)
                    paint.style = Paint.Style.FILL; paint.color = if (pressed) withAlpha(color, 0.63f * a) else fill
                    c.drawRoundRect(rect, arm * 0.2f, arm * 0.2f, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke
                    c.drawRoundRect(rect, arm * 0.2f, arm * 0.2f, paint)
                }
                arrow(cx - arm / 2, b.top, cx + arm / 2, cy - arm / 2, on0)
                arrow(cx + arm / 2, cy - arm / 2, b.right, cy + arm / 2, on1)
                arrow(cx - arm / 2, cy + arm / 2, cx + arm / 2, b.bottom, on2)
                arrow(b.left, cy - arm / 2, cx - arm / 2, cy + arm / 2, on3)
            }
            Element.Type.STICK -> {
                shape(b, true, 0f)
                val t = touches.values.firstOrNull { it.e === e }
                val tx = t?.thumbX ?: b.centerX()
                val ty = t?.thumbY ?: b.centerY()
                paint.style = Paint.Style.FILL; paint.color = withAlpha(color, (if (t != null) 0.78f else 0.47f) * a)
                c.drawCircle(tx, ty, b.width() * 0.22f, paint)
            }
            Element.Type.DYNAMIC_STICK -> {
                val t = touches.values.firstOrNull { it.e === e }
                if (editing) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = withAlpha(color, 0.5f)
                    paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
                    c.drawRoundRect(b, 16f, 16f, paint)
                    paint.pathEffect = null
                    label(c, "DYNAMIC STICK", b.centerX(), b.centerY(), b.width() * 0.6f, text)
                } else if (t != null) {
                    val r = width * e.stickRadiusRatio
                    rect.set(t.startX - r, t.startY - r, t.startX + r, t.startY + r)
                    shape(rect, true, 0f)
                    paint.style = Paint.Style.FILL; paint.color = withAlpha(color, 0.78f * a)
                    c.drawCircle(t.thumbX, t.thumbY, r * 0.4f, paint)
                }
            }
            Element.Type.TRACKPAD -> {
                shape(b, false, b.width() * 0.12f)
                label(c, e.text.ifEmpty { "PAD" }, b.centerX(), b.centerY(), b.width() * 0.6f, text)
            }
            Element.Type.RANGE_BUTTON -> {
                shape(b, false, b.height() * 0.2f)
                val n = e.bindings.size
                val cur = touches.values.firstOrNull { it.e === e }?.cell ?: -1
                for (i in 0 until n) {
                    val cx = if (e.orientation == 1) b.centerX() else b.left + b.width() * (i + 0.5f) / n
                    val cy = if (e.orientation == 1) b.top + b.height() * (i + 0.5f) / n else b.centerY()
                    val cell = if (e.orientation == 1) b.height() / n else b.width() / n
                    if (i == cur) {
                        paint.style = Paint.Style.FILL; paint.color = withAlpha(color, 0.6f * a)
                        c.drawCircle(cx, cy, cell * 0.45f, paint)
                    }
                    label(c, e.bindings[i].label, cx, cy, cell * 0.85f, text)
                }
            }
        }
        if (editing && e === selected) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0xFFFFD54F.toInt()
            c.drawRoundRect(RectF(b).apply { inset(-6f, -6f) }, 10f, 10f, paint)
        }
    }

    private fun isDir(e: Element, i: Int): Boolean = touches.values.any { it.e === e && it.active.getOrElse(i) { false } }

    private fun label(c: Canvas, text: String, cx: Float, cy: Float, maxW: Float, color: Int) {
        if (text.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        var size = snap * 2.2f
        paint.textSize = size
        val w = paint.measureText(text)
        if (w > maxW && w > 0f) { size *= maxW / w; paint.textSize = size }
        c.drawText(text, cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
    }

    private fun drawFab(c: Canvas) {
        val (cx, cy) = fabCenter()
        val r = fabRadius()
        paint.style = Paint.Style.FILL
        paint.color = if (controlsShown) 0xAA1A9FFF.toInt() else 0x88444A55.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.color = 0xCCFFFFFF.toInt()
        c.drawCircle(cx, cy, r, paint)
        // lambang keyboard kecil: tiga baris titik
        paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt()
        val d = r * 0.12f
        for (row in -1..1) for (col in -2..2) {
            if (row == 1 && (col == -2 || col == 2)) continue
            c.drawCircle(cx + col * r * 0.28f, cy + row * r * 0.3f, d, paint)
        }
    }

    private fun darken(c: Int, f: Float): Int {
        val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lighten(c: Int, f: Float): Int {
        val r = ((c shr 16 and 0xFF) + (255 - (c shr 16 and 0xFF)) * f).toInt()
        val g = ((c shr 8 and 0xFF) + (255 - (c shr 8 and 0xFF)) * f).toInt()
        val b = ((c and 0xFF) + (255 - (c and 0xFF)) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Ukuran bantu untuk editor: tinggi/lebar elemen dalam piksel layar. */
    fun elementBounds(e: Element): RectF = bounds(e, RectF())
}
