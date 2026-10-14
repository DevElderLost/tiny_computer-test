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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TouchApp
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
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.ui.AppProgressOverlay
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.PcKeyboard
import com.example.tiny_computer.ui.SliderRow
import com.example.tiny_computer.ui.SwitchRow
import com.example.tiny_computer.ui.clickableNoRipple

/** Lapisan Compose di atas SurfaceView: tombol menu kecil, controller layar, keyboard PC, dan dialog menu. */
@Composable
fun DisplayOverlay(
    state: DisplayUiState, pad: PadBridge, sendKey: (Int, Boolean) -> Unit, onClose: () -> Unit,
    onShutdown: () -> Unit = {},
    onPadOverlayChange: (Boolean) -> Unit = { Cfg.padOverlay = it },
) {
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
            // pintasan mode penunjuk: sentuh langsung <-> touchpad (relatif)
            FloatingButton(active = state.touchpad, onClick = { state.touchpad = !state.touchpad; Cfg.touchpadMode = state.touchpad }) {
                Icon(
                    if (state.touchpad) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                    stringResource(R.string.tc_display_touchpad),
                    tint = if (state.touchpad) pal.signal else pal.onBackground,
                )
            }
            // Shutdown: hanya untuk layar aplikasi Shortcut, tepat di samping tombol keyboard PC
            if (state.appMode) {
                FloatingButton(active = false, onClick = onShutdown) {
                    Icon(Icons.Filled.PowerSettingsNew, stringResource(R.string.tc_display_shutdown), tint = pal.onBackground)
                }
            }
        }
        if (state.pcKeyboard) {
            PcKeyboard(sendKey = sendKey, onClose = { state.pcKeyboard = false })
        }
        // paling atas: progres menunggu proot menyala / menunggu semua proses mati (menutup tombol dan menelan sentuhan)
        val phase = Launcher.appPhase
        if (state.appMode && (phase == Launcher.AppPhase.STARTING || phase == Launcher.AppPhase.STOPPING)) {
            AppProgressOverlay(stopping = phase == Launcher.AppPhase.STOPPING, stage = Launcher.appStage)
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
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SwitchRow(stringResource(R.string.tc_display_touchpad), state.touchpad) {
                        state.touchpad = it
                        Cfg.touchpadMode = it
                    }
                    if (state.touchpad) {
                        SliderRow(stringResource(R.string.tc_display_touchpad_speed), state.touchpadSpeed, 0.5f..4f) {
                            state.touchpadSpeed = it
                            Cfg.touchpadSpeed = it.toDouble()
                        }
                    }
                    SwitchRow(stringResource(R.string.tc_display_cursor), state.showCursor) {
                        state.showCursor = it
                        Cfg.showCursor = it
                    }
                    if (state.showCursor) {
                        SliderRow(stringResource(R.string.tc_display_cursor_size), state.cursorScale, 0.5f..4f) {
                            state.cursorScale = it
                            Cfg.cursorScale = it.toDouble()
                        }
                    }
                    SwitchRow(stringResource(R.string.tc_pad_overlay), state.padOverlay) {
                        state.padOverlay = it
                        onPadOverlayChange(it)
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
