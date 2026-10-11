package com.example.tiny_computer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Baris pengaturan bergaya DroidDeck (ui/SettingsWidgets.kt): judul kelompok + garis tipis, label dan
// petunjuk di kiri, kontrol di kanan; kontrol turun ke bawah label bila lajurnya sempit.

private val DeckGroupShape = RoundedCornerShape(14.dp)

@Composable
fun DeckGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
        ) {
            Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant)
            Box(Modifier.weight(1f).height(1.dp).background(pal.line))
        }
        Column(
            Modifier.fillMaxWidth().clip(DeckGroupShape).background(colors.surface).border(1.dp, pal.line, DeckGroupShape),
            content = content,
        )
    }
}

/** Satu pengaturan: label + [hint] dengan [control] di sampingnya (atau di bawahnya bila sempit). */
@Composable
fun DeckRow(label: String, hint: String?, control: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 400.dp
        Column(Modifier.fillMaxWidth()) {
            if (narrow) {
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    if (hint != null) Text(hint, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp)) { control() }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                        if (hint != null) Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                    control()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}

@Composable
fun DeckToggleRow(label: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    DeckRow(label, hint) { DeckToggle(checked, enabled, onChange) }

/** Saklar sekali ketuk (ToggleSwitch milik DroidDeck): trek terisi warna sinyal, kenop bergeser. */
@Composable
fun DeckToggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val track by animateColorAsState(if (checked) pal.signal else colors.surfaceVariant, tween(180), label = "switchTrack")
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "switchKnob")
    val shape = RoundedCornerShape(99.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).alpha(if (enabled) 1f else 0.5f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Switch) { onChange(!checked) },
    ) {
        Box(Modifier.size(width = 52.dp, height = 30.dp).clip(shape).background(track).border(1.dp, if (checked) pal.signal else pal.line2, shape)) {
            Box(
                Modifier.padding(4.dp).size(22.dp)
                    .graphicsLayer { translationX = knob * 22.dp.toPx() }
                    .clip(CircleShape)
                    .background(if (checked) pal.onSignal else colors.onSurfaceVariant),
            )
        }
    }
}

/** Kotak nilai dengan panah (ValueChip milik DroidDeck). */
@Composable
fun DeckValueChip(text: String, open: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    val rot by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chipCaret")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.widthIn(min = 150.dp).heightIn(min = 44.dp).clip(shape).background(colors.surfaceVariant)
            .border(1.dp, if (open) pal.signal else pal.line2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text("▾", fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.rotate(rot))
    }
}

/** Pilihan tunggal sebagai kotak nilai + menu turun (ChoiceRow DroidDeck). */
@Composable
fun <T> DeckChoiceRow(label: String, hint: String?, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    DeckRow(label, hint) {
        Box {
            DeckValueChip(options.firstOrNull { it.first == selected }?.second ?: "-", open) { open = !open }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    val on = value == selected
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("✓", fontSize = 12.sp, color = pal.signal, modifier = Modifier.width(18.dp).alpha(if (on) 1f else 0f))
                                Text(text, fontSize = 13.5.sp, color = if (on) pal.signal else colors.onBackground)
                            }
                        },
                        onClick = { onPick(value); open = false },
                    )
                }
            }
        }
    }
}

/** Nilai berbatas sebagai seekbar + hint + garis pemisah (SliderRow DroidDeck). */
@Composable
fun DeckSliderRow(
    label: String, hint: String?, value: Int, range: IntRange, step: Int = 1,
    format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        DeckSlider(value, range, step, label, format, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), onChange)
        if (hint != null) Text(hint, fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** Seekbar yang bisa diketuk dan digeser; nilai selalu mengikuti [step] (port ValueSlider DroidDeck). */
@Composable
fun DeckSlider(
    value: Int, range: IntRange, step: Int, label: String?, format: (Int) -> String,
    modifier: Modifier = Modifier, onChange: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val currentOnChange by rememberUpdatedState(onChange)
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    val thumbInset = with(LocalDensity.current) { 8.dp.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    val text = format(value)
    fun stepped(v: Int) = (((v - range.first).toFloat() / step).roundToInt() * step + range.first).coerceIn(range.first, range.last)
    fun fromX(x: Float) {
        val width = trackPx - 2 * thumbInset
        if (width > 0) {
            val position = ((x - thumbInset) / width).coerceIn(0f, 1f)
            currentOnChange(stepped(range.first + (position * span).roundToInt()))
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)
            .semantics {
                stateDescription = text
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
                if (label != null) contentDescription = label
                setProgress { next -> currentOnChange(stepped(next.roundToInt())); true }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (label != null) Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1)
        }
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).onSizeChanged { trackPx = it.width }
                .pointerInput(range, step, thumbInset) { detectTapGestures { fromX(it.x) } }
                .pointerInput(range, step, thumbInset) {
                    detectHorizontalDragGestures { change, _ -> fromX(change.position.x); change.consume() }
                },
        ) {
            val start = Offset(thumbInset, size.height / 2f)
            val end = Offset(size.width - thumbInset, start.y)
            val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.35f), start, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            if (fraction > 0) drawLine(pal.signal, start, thumb, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(pal.signal, radius = 7.dp.toPx(), center = thumb)
        }
    }
}

/** Catatan di dalam kartu [DeckGroup] (teks petunjuk + garis tipis), pengganti HintText di gaya DroidDeck. */
@Composable
fun DeckNote(text: String, color: Color? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text, fontSize = 13.sp, lineHeight = 18.sp, color = color ?: colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** Kolom isian ringkas bergaya DroidDeck: kotak membulat, bingkai berubah warna sinyal saat fokus. */
@Composable
private fun DeckField(value: String, number: Boolean, multiline: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = !multiline,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        textStyle = TextStyle(color = colors.onBackground, fontSize = 13.5.sp),
        cursorBrush = SolidColor(pal.signal),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(colors.surfaceVariant)
                    .border(1.dp, if (focused) pal.signal else pal.line2, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/**
 * Isian teks sebagai baris pengaturan: label di kiri dan kolom isian ringkas di kanan; bila [multiline]
 * kolomnya melebar di bawah label. Nilai langsung disimpan setiap diubah, sama seperti TextRow lama.
 */
@Composable
fun DeckTextRow(label: String, value: String, hint: String? = null, number: Boolean = false, multiline: Boolean = false, onCommit: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var text by remember(value) { mutableStateOf(value) }
    if (multiline) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                if (hint != null) Text(hint, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                DeckField(text, number, true, Modifier.fillMaxWidth().padding(top = 8.dp)) { text = it; onCommit(it) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    } else {
        DeckRow(label, hint) {
            DeckField(text, number, false, Modifier.widthIn(min = 150.dp, max = 220.dp)) { text = it; onCommit(it) }
        }
    }
}

/** Baris dengan tombol sekunder di kanan (pengganti ActionRow selebar kartu). */
@Composable
fun DeckActionRow(label: String, hint: String?, button: String, onClick: () -> Unit) {
    DeckRow(label, hint) { DeckButton(button, Modifier.widthIn(min = 120.dp), primary = false, onClick = onClick) }
}

/** Saklar yang menyimpan posisinya sendiri: untuk nilai Cfg yang tidak memicu rekomposisi saat ditulis. */
@Composable
fun DeckSwitchRow(label: String, hint: String?, initial: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    DeckToggleRow(label, hint, v, enabled) { v = it; onChange(it) }
}

/** Baris informasi baca-saja: label di kiri, nilai di kanan. */
@Composable
fun DeckInfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    DeckRow(label, null) { Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground) }
}
