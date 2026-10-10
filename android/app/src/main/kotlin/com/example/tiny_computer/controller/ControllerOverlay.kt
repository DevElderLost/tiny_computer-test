package com.example.tiny_computer.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.input.DeckControls
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.PadState
import com.example.tiny_computer.core.Cfg
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Controller di layar: sumbernya PadState -> FakeInputWriter -> ring -> libfakeinput -> /dev/input
 * (evdev) dan /dev/hidraw16 (Steam Deck) di guest. Tanpa Steam dan tanpa Steam Input: game dan
 * emulator membaca pad langsung lewat SDL. Dengan identitas "deck" ditambah trackpad dan
 * grip belakang (L4/R4/L5/R5) yang hanya ada di controller Deck.
 */
@Composable
fun ControllerOverlay(pad: PadBridge, deck: Boolean, modifier: Modifier = Modifier) {
    val scale = Cfg.padOverlayScale.toFloat().coerceIn(0.6f, 1.6f)
    val alpha = Cfg.padOverlayOpacity.toFloat().coerceIn(0.2f, 1f)
    Box(modifier.fillMaxSize().alpha(alpha)) {
        // sisi kiri
        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp)) {
            Row {
                PadButton("LB", pad, PadState.LB, 64.dp * scale, 36.dp * scale)
                Spacer(Modifier.width(8.dp))
                Trigger("LT", pad, left = true, 64.dp * scale, 36.dp * scale)
            }
            if (deck) {
                Spacer(Modifier.height(6.dp))
                Row {
                    Grip("L4", DeckControls.L4, 44.dp * scale)
                    Spacer(Modifier.width(6.dp))
                    Grip("L5", DeckControls.L5, 44.dp * scale)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, bottom = 90.dp * scale)) {
            DPad(pad, 124.dp * scale)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 20.dp)) {
            Stick(pad, left = true, 112.dp * scale)
        }
        // sisi kanan
        Column(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 8.dp), horizontalAlignment = Alignment.End) {
            Row {
                Trigger("RT", pad, left = false, 64.dp * scale, 36.dp * scale)
                Spacer(Modifier.width(8.dp))
                PadButton("RB", pad, PadState.RB, 64.dp * scale, 36.dp * scale)
            }
            if (deck) {
                Spacer(Modifier.height(6.dp))
                Row {
                    Grip("R5", DeckControls.R5, 44.dp * scale)
                    Spacer(Modifier.width(6.dp))
                    Grip("R4", DeckControls.R4, 44.dp * scale)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp, bottom = 90.dp * scale)) {
            FaceButtons(pad, 52.dp * scale)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 20.dp)) {
            Stick(pad, left = false, 112.dp * scale)
        }
        // tengah
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PadButton("SEL", pad, PadState.SELECT, 56.dp * scale, 30.dp * scale)
            Spacer(Modifier.width(8.dp))
            PadButton("●", pad, PadState.GUIDE, 40.dp * scale, 30.dp * scale)
            Spacer(Modifier.width(8.dp))
            PadButton("START", pad, PadState.START, 56.dp * scale, 30.dp * scale)
        }
        if (deck) {
            Box(Modifier.align(Alignment.Center).offset(x = (-110).dp * scale, y = 70.dp * scale)) { TrackPad(right = false, size = 96.dp * scale) }
            Box(Modifier.align(Alignment.Center).offset(x = 110.dp * scale, y = 70.dp * scale)) { TrackPad(right = true, size = 96.dp * scale) }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp * scale)) {
                PadButton("QAM", pad, PadState.QAM, 48.dp * scale, 26.dp * scale)
            }
        }
    }
}

private val chip = Color(0xCC202020)

@Composable
private fun haptic(): () -> Unit {
    val h = LocalHapticFeedback.current
    return { if (Cfg.padHaptics) h.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

/** Tombol sentuh: menekan saat jari turun, melepas saat naik atau batal. Tiap tombol multi-touch sendiri. */
@Composable
private fun PadButton(
    label: String, pad: PadBridge, button: Int, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
    round: Boolean = false,
) {
    var down by remember { mutableStateOf(false) }
    val buzz = haptic()
    Box(
        Modifier.size(w, h)
            .background(if (down) Color(0xFF7C4DFF) else chip, if (round) CircleShape else RoundedCornerShape(10.dp))
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true; buzz()
                    pad.applyTouch { it.press(button, true) }
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                    } while (ev.changes.any { it.pressed })
                    down = false
                    pad.applyTouch { it.press(button, false) }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 12.sp) }
}

@Composable
private fun Trigger(label: String, pad: PadBridge, left: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    var down by remember { mutableStateOf(false) }
    val buzz = haptic()
    Box(
        Modifier.size(w, h)
            .background(if (down) Color(0xFF7C4DFF) else chip, RoundedCornerShape(10.dp))
            .pointerInput(left) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true; buzz()
                    pad.applyTouch { if (left) it.leftTrigger = 1f else it.rightTrigger = 1f }
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                    } while (ev.changes.any { it.pressed })
                    down = false
                    pad.applyTouch { if (left) it.leftTrigger = 0f else it.rightTrigger = 0f }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 12.sp) }
}

@Composable
private fun Grip(label: String, bit: Int, size: androidx.compose.ui.unit.Dp) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size, size * 0.7f)
            .background(if (down) Color(0xFF7C4DFF) else chip, RoundedCornerShape(8.dp))
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
    ) { Text(label, color = Color.White, fontSize = 11.sp) }
}

@Composable
private fun FaceButtons(pad: PadBridge, s: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(s * 3)) {
        Box(Modifier.align(Alignment.TopCenter)) { PadButton("Y", pad, PadState.Y, s, s, true) }
        Box(Modifier.align(Alignment.BottomCenter)) { PadButton("A", pad, PadState.A, s, s, true) }
        Box(Modifier.align(Alignment.CenterStart)) { PadButton("X", pad, PadState.X, s, s, true) }
        Box(Modifier.align(Alignment.CenterEnd)) { PadButton("B", pad, PadState.B, s, s, true) }
    }
}

@Composable
private fun DPad(pad: PadBridge, size: androidx.compose.ui.unit.Dp) {
    val s = size / 3
    Box(Modifier.size(size)) {
        DirButton("▲", pad, s, Modifier.align(Alignment.TopCenter)) { st, d -> st.up = d }
        DirButton("▼", pad, s, Modifier.align(Alignment.BottomCenter)) { st, d -> st.down = d }
        DirButton("◀", pad, s, Modifier.align(Alignment.CenterStart)) { st, d -> st.left = d }
        DirButton("▶", pad, s, Modifier.align(Alignment.CenterEnd)) { st, d -> st.right = d }
    }
}

@Composable
private fun DirButton(
    label: String, pad: PadBridge, s: androidx.compose.ui.unit.Dp, modifier: Modifier, set: (PadState, Boolean) -> Unit,
) {
    var down by remember { mutableStateOf(false) }
    val buzz = haptic()
    Box(
        modifier.size(s)
            .background(if (down) Color(0xFF7C4DFF) else chip, RoundedCornerShape(8.dp))
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    down = true; buzz()
                    pad.applyTouch { set(it, true) }
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                    } while (ev.changes.any { it.pressed })
                    down = false
                    pad.applyTouch { set(it, false) }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}

/** Stick analog: geser dari tengah; L3/R3 tidak ada (gunakan tombol fisik). */
@Composable
private fun Stick(pad: PadBridge, left: Boolean, size: androidx.compose.ui.unit.Dp) {
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    Box(
        Modifier.size(size).background(chip, CircleShape)
            .pointerInput(left) {
                val radius = size.toPx() / 2f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        dx = 0f; dy = 0f
                        pad.applyTouch { if (left) { it.leftX = 0f; it.leftY = 0f } else { it.rightX = 0f; it.rightY = 0f } }
                    },
                    onDragCancel = {
                        dx = 0f; dy = 0f
                        pad.applyTouch { if (left) { it.leftX = 0f; it.leftY = 0f } else { it.rightX = 0f; it.rightY = 0f } }
                    },
                ) { change, drag ->
                    change.consume()
                    var nx = dx + drag.x
                    var ny = dy + drag.y
                    val len = hypot(nx, ny)
                    if (len > radius) { nx = nx / len * radius; ny = ny / len * radius }
                    dx = nx; dy = ny
                    val ax = nx / radius
                    val ay = ny / radius
                    pad.applyTouch { if (left) { it.leftX = ax; it.leftY = ay } else { it.rightX = ax; it.rightY = ay } }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }.size(size * 0.45f).background(Color(0xFF9E9E9E), CircleShape))
    }
}

/** Trackpad Steam Deck (hanya identitas "deck"): posisi -1..1 (y ke atas), ketuk lama = klik. */
@Composable
private fun TrackPad(right: Boolean, size: androidx.compose.ui.unit.Dp) {
    var touching by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size).background(if (touching) Color(0xCC4A3A8C) else chip, CircleShape)
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
    ) { Text(if (right) "R" else "L", color = Color(0x88FFFFFF), fontSize = 12.sp) }
}
