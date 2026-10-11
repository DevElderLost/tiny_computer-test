package com.example.tiny_computer.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.PadBridge
import com.example.tiny_computer.R
import com.example.tiny_computer.controller.ControllerOverlay
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.PcKeyboard
import com.example.tiny_computer.ui.SwitchRow
import com.example.tiny_computer.ui.clickableNoRipple

/** Lapisan Compose di atas SurfaceView: tombol menu kecil, controller layar, keyboard PC, dan dialog menu. */
@Composable
fun DisplayOverlay(state: DisplayUiState, pad: PadBridge, sendKey: (Int, Boolean) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        if (state.padOverlay) {
            ControllerOverlay(pad = pad, deck = state.padIdentity == "deck", onKeyboard = { state.pcKeyboard = !state.pcKeyboard })
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FloatingButton(active = false, onClick = { state.menuOpen = true }) {
                Icon(Icons.Filled.Menu, stringResource(R.string.tc_display_menu), tint = pal.onBackground)
            }
            FloatingButton(active = state.pcKeyboard, onClick = { state.pcKeyboard = !state.pcKeyboard }) {
                Icon(Icons.Filled.Keyboard, stringResource(R.string.tc_display_keyboard), tint = if (state.pcKeyboard) pal.signal else pal.onBackground)
            }
        }
        if (state.pcKeyboard) {
            PcKeyboard(sendKey = sendKey, onClose = { state.pcKeyboard = false })
        }
    }
    if (state.menuOpen) {
        AlertDialog(
            onDismissRequest = { state.menuOpen = false },
            containerColor = pal.surface,
            titleContentColor = pal.onBackground,
            textContentColor = pal.onBackground,
            title = { Text(stringResource(R.string.tc_display_menu)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SwitchRow(stringResource(R.string.tc_display_touchpad), state.touchpad) { state.touchpad = it }
                    SwitchRow(stringResource(R.string.tc_pad_overlay), state.padOverlay) {
                        state.padOverlay = it
                        Cfg.padOverlay = it
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { state.menuOpen = false; onClose() }) { Text(stringResource(R.string.tc_display_back), color = pal.signal) }
            },
            dismissButton = {
                TextButton(onClick = { state.menuOpen = false }) { Text(stringResource(R.string.tc_close), color = pal.onSurfaceVariant) }
            },
        )
    }
}

/** Tombol bulat mengambang bergaya DroidDeck: permukaan hampir hitam, bingkai tipis, sinyal saat aktif. */
@Composable
private fun FloatingButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Color(0xCC101418), CircleShape)
            .border(1.dp, if (active) pal.signal else pal.line2, CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
