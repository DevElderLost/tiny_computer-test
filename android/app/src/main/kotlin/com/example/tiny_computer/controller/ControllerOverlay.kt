package com.example.tiny_computer.controller

import androidx.compose.foundation.background
import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.DeckControls
import com.droiddeck.launcher.input.OnScreenControls
import com.droiddeck.launcher.input.PadBridge

/**
 * Controller di layar: tampilan, tata letak otomatis, warna, mapping tombol, stick adaptif, dan
 * posisi buatan sendiri semuanya dari [OnScreenControls] DroidDeck (satu View kanvas, sama persis
 * dengan di DroidDeck). Sumbernya PadState -> FakeInputWriter -> ring -> libfakeinput -> /dev/input
 * (evdev) dan /dev/hidraw16 (Steam Deck) di guest.
 *
 * Dengan identitas "deck" ditambah grip belakang (L4/L5/R4/R5) dan trackpad yang tidak ada di
 * OnScreenControls; keduanya memakai warna, opasitas, dan ukuran yang sama dengan pengaturan DroidDeck.
 */
@Composable
fun ControllerOverlay(pad: PadBridge, deck: Boolean, modifier: Modifier = Modifier, onKeyboard: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                OnScreenControls(ctx, pad, onKeyboard = onKeyboard).also { v ->
                    // inset poni/cutout: AndroidView di dalam Compose tidak selalu menerimanya sendiri
                    v.post { v.rootWindowInsets?.let { v.onApplyWindowInsets(it) } }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (deck) DeckExtras(pad)
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
private fun BoxScope.DeckExtras(pad: PadBridge) {
    val ctx = LocalContext.current
    val settings = remember { ControllerPrefs.read(ctx) }
    val look = remember { Look(settings.tint, settings.opacity) }
    val scale = settings.size / 100f
    // grip di bawah bahu (bahu OnScreenControls ada di sudut atas)
    Row(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 76.dp)) {
        Grip("L4", DeckControls.L4, 44.dp * scale, look)
        Box(Modifier.width(6.dp))
        Grip("L5", DeckControls.L5, 44.dp * scale, look)
    }
    Row(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 76.dp)) {
        Grip("R5", DeckControls.R5, 44.dp * scale, look)
        Box(Modifier.width(6.dp))
        Grip("R4", DeckControls.R4, 44.dp * scale, look)
    }
    // Mode trackpad: jadi stik kiri/kanan (default, game Wine membacanya sebagai stik) atau hanya laporan hidraw Deck.
    val asStick = remember { ControllerPrefs.trackpadMode(ctx) == ControllerPrefs.PAD_STICK }
    Box(Modifier.align(Alignment.Center).offset(x = (-122).dp * scale, y = 36.dp * scale)) { TrackPad(false, 112.dp * scale, 92.dp * scale, look, pad, asStick) }
    Box(Modifier.align(Alignment.Center).offset(x = 122.dp * scale, y = 36.dp * scale)) { TrackPad(true, 112.dp * scale, 92.dp * scale, look, pad, asStick) }
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
 */
@Composable
private fun TrackPad(right: Boolean, width: Dp, height: Dp, look: Look, pad: PadBridge, asStick: Boolean) {
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
        }
    }
    Box(
        Modifier.size(width, height)
            .background(if (touching) look.heldFill else look.idleFill, shape)
            .border(if (clicking) 2.5.dp else 1.5.dp, if (touching) look.heldStroke else look.idleStroke, shape)
            .pointerInput(right, asStick) {
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
                            // jari diam cukup lama: klik trackpad
                            longPressed = true
                            clicking = true
                            DeckControls.setClick(right, true)
                            continue
                        }
                        val change = ev.changes.firstOrNull { it.id == pointer } ?: ev.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        report(change.position.x, change.position.y)
                        if (!moved && (change.position - first.position).getDistance() > slop * 2f) moved = true
                        change.consume()
                    }
                    if (longPressed) DeckControls.setClick(right, false)
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
