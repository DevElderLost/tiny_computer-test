package com.example.tiny_computer.controller

import androidx.compose.foundation.background
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
        if (deck) DeckExtras()
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
private fun BoxScope.DeckExtras() {
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
    Box(Modifier.align(Alignment.Center).offset(x = (-110).dp * scale, y = 40.dp * scale)) { TrackPad(false, 96.dp * scale, look) }
    Box(Modifier.align(Alignment.Center).offset(x = 110.dp * scale, y = 40.dp * scale)) { TrackPad(true, 96.dp * scale, look) }
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

/** Trackpad Steam Deck: posisi -1..1 (y ke atas); ketuk lama = klik. */
@Composable
private fun TrackPad(right: Boolean, size: Dp, look: Look) {
    var touching by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size)
            .background(if (touching) look.heldFill else look.idleFill, CircleShape)
            .border(1.5.dp, if (touching) look.heldStroke else look.idleStroke, CircleShape)
            .pointerInput(right) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val w = this.size.width.toFloat()
                    val h = this.size.height.toFloat()
                    fun report(px: Float, py: Float) {
                        val x = ((px / w) * 2f - 1f).coerceIn(-1f, 1f)
                        val y = (1f - (py / h) * 2f).coerceIn(-1f, 1f)
                        DeckControls.setPad(right, true, x, y)
                    }
                    touching = true
                    report(first.position.x, first.position.y)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.firstOrNull()?.let { report(it.position.x, it.position.y) }
                    } while (ev.changes.any { it.pressed })
                    touching = false
                    DeckControls.setPad(right, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(if (right) "R" else "L", color = look.idleText, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
