package com.example.tiny_computer.controller

import androidx.compose.foundation.background
import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.DeckControls
import com.droiddeck.launcher.input.DeckLayout
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.wayland.WaylandCompositor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Controller di layar: tampilan, tata letak otomatis, warna, mapping tombol, stick adaptif, dan
 * posisi buatan sendiri semuanya dari [OnScreenControls] DroidDeck (satu View kanvas, sama persis
 * dengan di DroidDeck). Sumbernya PadState -> FakeInputWriter -> ring -> libfakeinput -> /dev/input
 * (evdev) dan /dev/hidraw16 (Steam Deck) di guest.
 *
 * Dengan identitas "deck" ditambah grip belakang (L4/L5/R4/R5) dan trackpad yang tidak ada di
 * OnScreenControls; keduanya memakai warna, opasitas, dan ukuran yang sama dengan pengaturan DroidDeck, dan posisinya
 * mengikuti editor tata letak ([DeckLayout]). [reloadKey] dinaikkan saat kembali dari editor supaya posisi dibaca ulang;
 * [sceneSize] = ukuran scene compositor, dipakai mode trackpad "kursor mouse" untuk menyamakan kecepatan.
 */
@Composable
fun ControllerOverlay(
    pad: PadBridge, deck: Boolean, modifier: Modifier = Modifier, onKeyboard: (() -> Unit)? = null,
    reloadKey: Int = 0, sceneSize: Pair<Int, Int> = 1280 to 720,
) {
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                OnScreenControls(ctx, pad, onKeyboard = onKeyboard).also { v ->
                    // inset poni/cutout: AndroidView di dalam Compose tidak selalu menerimanya sendiri
                    v.post { v.rootWindowInsets?.let { v.onApplyWindowInsets(it) } }
                }
            },
            // kembali dari editor tata letak: baca ulang pengaturan dan posisi
            update = { v -> if (reloadKey > 0) v.reload() },
            modifier = Modifier.fillMaxSize(),
        )
        if (deck) DeckExtras(pad, reloadKey, sceneSize)
    }
}

/** Warna turunan tint seperti OnScreenControls.applySettings (shade = gelap, light = terang). */
private class Look(tint: Int, opacity: Int) {
    private val c = Color(tint)
    private val a = opacity / 100f
    private fun shade(alpha: Int, f: Float) = Color(c.red * f, c.green * f, c.blue * f, alpha / 255f * a)
    private fun light(alpha: Int, f: Float) =
        Color(c.red + (1f - c.red) * f, c.green + (1f - c.green) * f, c.blue + (1f - c.blue) * f, alpha / 255f * a)
    val idleFill = shade(80, 0.12f)
    val heldFill = Color(c.red, c.green, c.blue, 160 / 255f * a)
    val idleStroke = light(150, 0.25f)
    val heldStroke = light(230, 0.7f)
    val idleText = light(210, 0.75f)
}

@Composable
private fun DeckExtras(pad: PadBridge, reloadKey: Int, sceneSize: Pair<Int, Int>) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val settings = remember(reloadKey) { ControllerPrefs.read(ctx) }
    val look = remember(reloadKey) { Look(settings.tint, settings.opacity) }
    val scale = settings.size / 100f
    // Mode trackpad: stik kiri/kanan (default, game Wine membacanya sebagai stik), hanya laporan hidraw Deck, atau kursor mouse.
    val mode = remember(reloadKey) { ControllerPrefs.trackpadMode(ctx) }
    val mouseSpeed = remember(reloadKey) { ControllerPrefs.trackpadMouseSpeed(ctx) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // grip dan trackpad ditaruh di titik tengah tersimpan (editor) atau bawaan, sama dengan yang ditampilkan editor
        val placed = remember(w, h, scale, density, reloadKey) { DeckLayout.resolve(ctx, w, h, density, scale) }
        // piksel layar per piksel scene (letterbox FIT), untuk kecepatan kursor mode mouse
        val viewScale = min(w.toFloat() / max(1, sceneSize.first), h.toFloat() / max(1, sceneSize.second))
        for (spec in DeckLayout.specs) {
            val c = placed[spec.id] ?: continue
            val wDp = (spec.wDp * scale).dp
            val hDp = (spec.hDp * scale).dp
            val x = (c.first - spec.wDp * scale * density / 2f).roundToInt()
            val y = (c.second - spec.hDp * scale * density / 2f).roundToInt()
            Box(Modifier.offset { IntOffset(x, y) }) {
                when (spec.id) {
                    DeckLayout.L4 -> Grip(spec.label, DeckControls.L4, wDp, look)
                    DeckLayout.L5 -> Grip(spec.label, DeckControls.L5, wDp, look)
                    DeckLayout.R4 -> Grip(spec.label, DeckControls.R4, wDp, look)
                    DeckLayout.R5 -> Grip(spec.label, DeckControls.R5, wDp, look)
                    else -> TrackPad(spec.id == DeckLayout.PAD_R, wDp, hDp, look, pad, mode, mouseSpeed, viewScale)
                }
            }
        }
    }
}

@Composable
private fun Grip(label: String, bit: Int, size: Dp, look: Look) {
    var down by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(size * 0.2f)
    Box(
        Modifier.size(size, size * 0.7f)
            .background(if (down) look.heldFill else look.idleFill, shape)
            .border(1.5.dp, if (down) look.heldStroke else look.idleStroke, shape)
            .pointerInput(bit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true
                    DeckControls.setGrip(bit, true)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                    } while (ev.changes.any { it.pressed })
                    down = false
                    DeckControls.setGrip(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (down) Color.White else look.idleText, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

/**
 * Trackpad Steam Deck: kotak bersudut agak melengkung, tanpa tulisan. Posisi jari -1..1 (y ke atas) masuk
 * ke laporan hidraw Deck ([DeckControls.setPad]); tahan agak lama tanpa menggeser = klik trackpad
 * ([DeckControls.setClick]). Bila [asStick], posisi jari juga menggerakkan stik di sisinya
 * (kiri = stik kiri, kanan = stik kanan) lewat [PadBridge.setAuxStick], dan stik kembali ke tengah saat
 * jari diangkat - tanpa ini game Wine tidak membaca trackpad sama sekali.
 *
 * Mode [ControllerPrefs.PAD_MOUSE]: pad menggerakkan kursor mouse sesi secara relatif (jalur yang sama dengan mode
 * touchpad layar). Ketuk pad kanan = klik kiri, ketuk pad kiri = klik kanan; tahan diam pad kanan = tombol kiri tertahan
 * (seret, dilepas saat jari diangkat), tahan diam pad kiri = klik tengah. Stik dan klik trackpad Deck tidak ikut dikirim.
 */
@Composable
private fun TrackPad(
    right: Boolean, width: Dp, height: Dp, look: Look, pad: PadBridge, mode: String, mouseSpeed: Int, viewScale: Float,
) {
    val asStick = mode == ControllerPrefs.PAD_STICK
    val asMouse = mode == ControllerPrefs.PAD_MOUSE
    // tombol mouse yang sedang tertahan (seret); dilepas bila pad hilang saat jari masih menahan
    val held = remember { intArrayOf(0) }
    var touching by remember { mutableStateOf(false) }
    var clicking by remember { mutableStateOf(false) }
    // titik jari (0..1 dari lebar/tinggi kotak) untuk penanda sentuhan
    var fx by remember { mutableFloatStateOf(0.5f) }
    var fy by remember { mutableFloatStateOf(0.5f) }
    val source = if (right) PadBridge.SRC_PAD_RIGHT else PadBridge.SRC_PAD_LEFT
    val stick = if (right) PadBridge.STICK_RIGHT else PadBridge.STICK_LEFT
    val shape = RoundedCornerShape(14.dp)
    // jari masih di atas trackpad saat panel hilang: lepaskan semuanya
    DisposableEffect(right) {
        onDispose {
            DeckControls.setPad(right, false)
            DeckControls.setClick(right, false)
            pad.clearAuxStick(source)
            if (held[0] != 0) { mouseButton(held[0], false); held[0] = 0 }
        }
    }
    Box(
        Modifier.size(width, height)
            .background(if (touching) look.heldFill else look.idleFill, shape)
            .border(if (clicking) 2.5.dp else 1.5.dp, if (touching) look.heldStroke else look.idleStroke, shape)
            .pointerInput(right, mode, mouseSpeed, viewScale) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val slop = viewConfiguration.touchSlop
                    val pointer = first.id
                    var longPressed = false
                    fun report(px: Float, py: Float) {
                        val x = ((px / w) * 2f - 1f).coerceIn(-1f, 1f)
                        val y = (1f - (py / h) * 2f).coerceIn(-1f, 1f)
                        fx = (px / w).coerceIn(0f, 1f)
                        fy = (py / h).coerceIn(0f, 1f)
                        DeckControls.setPad(right, true, x, y)
                        // evdev: y positif = ke bawah; sedikit area mati di tepi supaya stik bisa penuh
                        if (asStick) pad.setAuxStick(source, stick, (x * 1.15f).coerceIn(-1f, 1f), (-y * 1.15f).coerceIn(-1f, 1f))
                    }
                    // mode mouse: gerak kursor relatif dari titik sentuh awal, baru jalan setelah jari melewati ambang geser
                    var lastMx = first.position.x
                    var lastMy = first.position.y
                    var lastMt = first.uptimeMillis
                    var remX = 0f
                    var remY = 0f
                    fun mouseMove(px: Float, py: Float, t: Long) {
                        val dx = px - lastMx
                        val dy = py - lastMy
                        val dt = max(1L, t - lastMt)
                        lastMx = px; lastMy = py; lastMt = t
                        val accel = 1f + min(ACCEL_MAX, hypot(dx, dy) / dt * ACCEL_K)
                        // 1/256 piksel scene per piksel layar; kecepatan 5 = 4x
                        val k = mouseSpeed * 0.8f * accel * 256f / max(viewScale, 0.01f)
                        val fx = dx * k + remX
                        val fy = dy * k + remY
                        val ix = fx.toInt()
                        val iy = fy.toInt()
                        remX = fx - ix
                        remY = fy - iy
                        if (ix != 0 || iy != 0) WaylandCompositor.nativeSendSceneInput(SCENE_RELATIVE, ix, iy)
                    }
                    first.consume()
                    touching = true
                    report(first.position.x, first.position.y)
                    var moved = false
                    val startAt = SystemClock.uptimeMillis()
                    while (true) {
                        // Compose hanya mengirim kejadian bila jari berubah; tahan diam butuh batas waktu sendiri
                        val waitMs = if (longPressed || moved) 0L else LONG_PRESS_MS - (SystemClock.uptimeMillis() - startAt)
                        val ev = if (waitMs > 0L) withTimeoutOrNull(waitMs) { awaitPointerEvent(PointerEventPass.Main) }
                        else awaitPointerEvent(PointerEventPass.Main)
                        if (ev == null) {
                            // jari diam cukup lama: klik trackpad (mode mouse: tahan tombol kiri / klik tengah)
                            longPressed = true
                            clicking = true
                            if (asMouse) {
                                if (right) { mouseButton(BTN_LEFT, true); held[0] = BTN_LEFT }
                                else { mouseButton(BTN_MIDDLE, true); mouseButton(BTN_MIDDLE, false) }
                            } else DeckControls.setClick(right, true)
                            continue
                        }
                        val change = ev.changes.firstOrNull { it.id == pointer } ?: ev.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        report(change.position.x, change.position.y)
                        if (!moved && (change.position - first.position).getDistance() > slop * 2f) moved = true
                        if (asMouse && moved) mouseMove(change.position.x, change.position.y, change.uptimeMillis)
                        change.consume()
                    }
                    if (asMouse) {
                        if (held[0] != 0) { mouseButton(held[0], false); held[0] = 0 }
                        else if (!moved && !longPressed) { mouseButton(if (right) BTN_LEFT else BTN_RIGHT, true); mouseButton(if (right) BTN_LEFT else BTN_RIGHT, false) }
                    } else if (longPressed) DeckControls.setClick(right, false)
                    clicking = false
                    touching = false
                    DeckControls.setPad(right, false)
                    pad.clearAuxStick(source)
                }
            },
    ) {
        // penanda sentuhan: titik kecil di posisi jari (bukan tulisan)
        if (touching) {
            val dot = 12.dp
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset(x = width * fx - dot / 2, y = height * fy - dot / 2)
                    .size(dot)
                    .background(look.heldStroke, CircleShape),
            )
        }
    }
}

/** Tahan tanpa menggeser selama ini = klik trackpad. */
private const val LONG_PRESS_MS = 350L

// jenis input scene compositor dan kode tombol mouse (sama dengan TouchpadInput)
private const val SCENE_BUTTON = 3
private const val SCENE_RELATIVE = 6
private const val BTN_LEFT = 272
private const val BTN_RIGHT = 273
private const val BTN_MIDDLE = 274
private const val ACCEL_K = 0.4f
private const val ACCEL_MAX = 1.2f

private fun mouseButton(code: Int, down: Boolean) = WaylandCompositor.nativeSendSceneInput(SCENE_BUTTON, code, if (down) 1 else 0)
