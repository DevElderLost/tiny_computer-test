package com.example.tiny_computer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lanskap: navigasi pindah ke rel samping dan halaman memakai dua kolom (seperti DroidDeck). */
@Composable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

private val CardShape = RoundedCornerShape(14.dp)
private val ControlShape = RoundedCornerShape(12.dp)

/** Judul halaman: besar, dengan satu baris keterangan. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    val land = isLandscape()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (land) 10.dp else 20.dp, bottom = if (land) 2.dp else 8.dp)) {
        Text(title, style = if (land) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Kelompok pengaturan: judul kecil berwarna sinyal di atas kartu permukaan berbingkai garis tipis. */
@Composable
fun Group(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = pal.signal,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(pal.surface, CardShape).border(1.dp, pal.line, CardShape),
            content = content,
        )
    }
}

/** Garis pemisah antar baris di dalam [Group]. */
@Composable
fun Div() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(LocalPalette.current.line))
}

@Composable
fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    var v by remember(checked) { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) pal.onBackground else pal.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant)
        }
        Switch(
            checked = v, enabled = enabled, onCheckedChange = { v = it; onChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = pal.onSignal, checkedTrackColor = pal.signal, checkedBorderColor = pal.signal,
                uncheckedThumbColor = pal.onSurfaceVariant, uncheckedTrackColor = pal.surfaceVariant, uncheckedBorderColor = pal.line2,
            ),
        )
    }
}

@Composable
fun TextRow(label: String, value: String, number: Boolean = false, multiline: Boolean = false, onCommit: (String) -> Unit) {
    val pal = LocalPalette.current
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        singleLine = !multiline,
        shape = ControlShape,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = pal.signal, unfocusedBorderColor = pal.line2,
            focusedLabelColor = pal.signal, unfocusedLabelColor = pal.onSurfaceVariant,
            focusedTextColor = pal.onBackground, unfocusedTextColor = pal.onBackground,
            cursorColor = pal.signal, focusedContainerColor = pal.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val pal = LocalPalette.current
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = pal.onBackground)
            Text("%.2f".format(v), style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant)
        }
        Slider(
            value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onChange(v) },
            colors = SliderDefaults.colors(thumbColor = pal.signal, activeTrackColor = pal.signal, inactiveTrackColor = pal.line2),
        )
    }
}

/** Tombol utama (terisi warna sinyal) atau sekunder (permukaan berbingkai). */
@Composable
fun DeckButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) pal.signal else pal.surfaceVariant,
            contentColor = if (primary) pal.onSignal else pal.onBackground,
        ),
        border = if (primary) null else BorderStroke(1.dp, pal.line2),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Baris tombol sekunder selebar kartu, dipakai di dalam [Group]. */
@Composable
fun ActionRow(text: String, onClick: () -> Unit) {
    DeckButton(text, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), primary = false, onClick = onClick)
}

/** Pilihan tunggal berupa pil, mis. identitas controller atau tema. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Row(
                Modifier.fillMaxWidth().clip(ControlShape)
                    .background(if (on) pal.signal.copy(alpha = 0.16f) else pal.surfaceVariant, ControlShape)
                    .border(1.dp, if (on) pal.signal else pal.line, ControlShape)
                    .clickableNoRipple { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.dot(10).background(if (on) pal.signal else pal.line2, CircleShape))
                Text(label, Modifier.padding(start = 12.dp), color = if (on) pal.onBackground else pal.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * Pilihan tunggal ringkas: judul di atas, pil bergulir mendatar di bawahnya. Menggantikan
 * [ChoiceRow] (tumpukan baris tinggi) di tempat yang butuh hemat tinggi, terutama lanskap.
 */
@Composable
fun <T> ChipChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val pal = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = pal.onBackground)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (id, label) ->
                val on = id == selected
                Box(
                    Modifier.clip(pill)
                        .background(if (on) pal.signal.copy(alpha = 0.16f) else pal.surfaceVariant, pill)
                        .border(1.dp, if (on) pal.signal else pal.line, pill)
                        .clickableNoRipple { onSelect(id) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = if (on) pal.onBackground else pal.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Chip status kecil (titik berwarna + teks). */
@Composable
fun StatusChip(text: String, color: Color) {
    val pal = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.dot(8).background(color, CircleShape))
        Text(text, Modifier.padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground)
    }
}

/** Satu baris info "label ... nilai" di kartu beranda. */
@Composable
fun InfoRow(label: String, value: String) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = pal.onBackground, fontWeight = FontWeight.SemiBold)
    }
}

/** Tombol kecil bergaya kunci (keypad terminal, Ctrl+C, dst). */
@Composable
fun KeyChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.heightIn(min = 38.dp).clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.22f) else pal.surfaceVariant, shape)
            .border(1.dp, if (selected) pal.signal else pal.line2, shape)
            .clickableNoRipple(onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) pal.signal else pal.onBackground) }
}
