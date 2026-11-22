package com.example.tiny_computer.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.onSignal

/** Deretan pilihan (chip) yang bisa digeser mendatar; satu terpilih. */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val pal = LocalPalette.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (on) pal.onSignal else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) pal.signal else Color.White.copy(alpha = 0.07f))
                    .clickable { onPick(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Dialog satu isian teks (nama profil, teks tombol). */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }) { Text(stringResource(R.string.wl_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wl_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(message: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.wl_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wl_cancel)) } },
    )
}

private enum class BindingTab { KEYBOARD, MOUSE, GAMEPAD }

/**
 * Pemilih binding (keyboard / mouse / gamepad), sama dengan dialog binding Winlator. [allowed] membatasi tab bila perlu
 * (mis. tombol rentang hanya keyboard).
 */
@Composable
fun BindingPickerDialog(current: Binding, onDismiss: () -> Unit, onPick: (Binding) -> Unit) {
    var tab by remember {
        mutableStateOf(if (current.isGamepad) BindingTab.GAMEPAD else if (current.isMouse) BindingTab.MOUSE else BindingTab.KEYBOARD)
    }
    val items = when (tab) {
        BindingTab.KEYBOARD -> Binding.keyboardValues
        BindingTab.MOUSE -> Binding.mouseValues
        BindingTab.GAMEPAD -> Binding.gamepadValues
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wl_pick_binding)) },
        text = {
            Column {
                ChipRow(
                    listOf(
                        BindingTab.KEYBOARD to stringResource(R.string.wl_tab_keyboard),
                        BindingTab.MOUSE to stringResource(R.string.wl_tab_mouse),
                        BindingTab.GAMEPAD to stringResource(R.string.wl_tab_gamepad),
                    ),
                    tab,
                ) { tab = it }
                LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                    items(items) { b ->
                        val label = if (b == Binding.NONE) stringResource(R.string.wl_none) else b.label
                        Box(
                            Modifier.fillMaxWidth().clickable { onPick(b) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(
                                label, fontSize = 14.sp,
                                fontWeight = if (b == current) FontWeight.Bold else FontWeight.Normal,
                                color = if (b == current) LocalPalette.current.signal else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wl_cancel)) } },
    )
}

/** Baris kecil "label  [nilai]" untuk memilih binding; mengetuknya membuka [BindingPickerDialog]. */
@Composable
fun BindingRow(label: String, value: Binding, onPick: (Binding) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(
            if (value == Binding.NONE) stringResource(R.string.wl_none) else value.label,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.signal,
        )
    }
    if (open) BindingPickerDialog(value, { open = false }) { open = false; onPick(it) }
}
