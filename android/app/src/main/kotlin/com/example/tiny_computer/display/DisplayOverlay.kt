package com.example.tiny_computer.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.PadBridge
import com.example.tiny_computer.R
import com.example.tiny_computer.controller.ControllerOverlay
import com.example.tiny_computer.core.Cfg

/** Lapisan Compose di atas SurfaceView: tombol menu kecil, controller layar, dan dialog menu. */
@Composable
fun DisplayOverlay(state: DisplayUiState, pad: PadBridge, onKeyboard: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (state.padOverlay) {
            ControllerOverlay(pad = pad, deck = state.padIdentity == "deck")
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { state.menuOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.tc_display_menu))
            }
            FilledTonalIconButton(onClick = onKeyboard) {
                Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.tc_display_keyboard))
            }
        }
    }
    if (state.menuOpen) {
        AlertDialog(
            onDismissRequest = { state.menuOpen = false },
            title = { Text(stringResource(R.string.tc_display_menu)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(stringResource(R.string.tc_display_touchpad), state.touchpad) { state.touchpad = it }
                    ToggleRow(stringResource(R.string.tc_pad_overlay), state.padOverlay) {
                        state.padOverlay = it
                        Cfg.padOverlay = it
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { state.menuOpen = false; onClose() }) { Text(stringResource(R.string.tc_display_back)) }
            },
            dismissButton = {
                TextButton(onClick = { state.menuOpen = false }) { Text(stringResource(R.string.tc_close)) }
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.width(200.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
