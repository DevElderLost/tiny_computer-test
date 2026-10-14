package com.example.tiny_computer.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.ui.DeckButton
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.SliderRow
import com.example.tiny_computer.ui.SwitchRow

/**
 * Menu sesi bergaya DroidDeck: lembar yang meluncur dari tepi kiri ketika jari digeser ke kanan dari
 * tepi layar (pemicunya ada di DisplayActivity), dengan latar redup di belakangnya. Isinya menggantikan
 * keempat tombol mengambang lama: mode penunjuk, keyboard PC, kembali ke terminal, dan Shutdown.
 * Tutup dengan ketuk di luar lembar, geser lembar ke kiri, atau tombol Back.
 */
@Composable
fun SessionDrawer(
    state: DisplayUiState,
    onBackToTerminal: () -> Unit,
    onShutdown: () -> Unit,
    onPadOverlayChange: (Boolean) -> Unit,
) {
    val pal = LocalPalette.current
    val open = state.menuOpen
    var confirmStop by remember { mutableStateOf(false) }
    val veil by animateFloatAsState(if (open) 0.45f else 0f, tween(220), label = "veil")

    // latar redup: ketukan di luar lembar menutup menu (juga selama animasi menutup, menelan sentuhan)
    if (open || veil > 0.01f) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.menuOpen = false },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        val sheetWidth = minOf(360.dp, maxWidth * 0.92f)
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(180)),
        ) {
            Column(
                Modifier.fillMaxHeight().width(sheetWidth)
                    .background(pal.background.copy(alpha = 0.97f))
                    .pointerInput(Unit) {
                        // geser lembar ke kiri = tutup
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -CLOSE_DRAG_PX) state.menuOpen = false },
                            onDragCancel = { total = 0f },
                            onHorizontalDrag = { _, amount -> total += amount },
                        )
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .displayCutoutPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            ) {
                Text(
                    stringResource(R.string.tc_display_menu),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = pal.onBackground,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )

                Section(stringResource(R.string.tc_drawer_pointer))
                SwitchRow(stringResource(R.string.tc_display_touchpad), state.touchpad) {
                    state.touchpad = it
                    Cfg.touchpadMode = it
                }
                if (state.touchpad) {
                    SliderRow(stringResource(R.string.tc_display_touchpad_speed), state.touchpadSpeed, 0.5f..4f) {
                        state.touchpadSpeed = it
                        Cfg.touchpadSpeed = it.toDouble()
                    }
                    SliderRow(stringResource(R.string.tc_display_touchpad_scroll), state.touchpadScroll, 0.3f..3f) {
                        state.touchpadScroll = it
                        Cfg.touchpadScroll = it.toDouble()
                    }
                    SwitchRow(stringResource(R.string.tc_display_touchpad_natural), state.touchpadNatural) {
                        state.touchpadNatural = it
                        Cfg.touchpadNatural = it
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

                Section(stringResource(R.string.tc_drawer_input))
                SwitchRow(stringResource(R.string.tc_display_keyboard), state.pcKeyboard) {
                    state.pcKeyboard = it
                    // keyboard dibuka: menu menyingkir supaya layar terlihat
                    if (it) state.menuOpen = false
                }
                SwitchRow(stringResource(R.string.tc_pad_overlay), state.padOverlay) {
                    state.padOverlay = it
                    onPadOverlayChange(it)
                }

                Section(stringResource(R.string.tc_drawer_session))
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DeckButton(stringResource(R.string.tc_display_back), Modifier.fillMaxWidth(), primary = false) {
                        state.menuOpen = false
                        onBackToTerminal()
                    }
                    DeckButton(stringResource(R.string.tc_display_shutdown), Modifier.fillMaxWidth()) { confirmStop = true }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = pal.surface,
            titleContentColor = pal.onBackground,
            textContentColor = pal.onBackground,
            title = { Text(stringResource(R.string.tc_display_shutdown)) },
            text = { Text(stringResource(R.string.tc_display_shutdown_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; state.menuOpen = false; onShutdown() }) {
                    Text(stringResource(R.string.tc_display_shutdown), color = pal.signal)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.tc_close), color = pal.onSurfaceVariant) }
            },
        )
    }
}

private const val CLOSE_DRAG_PX = 48f

/** Judul bagian di dalam lembar. */
@Composable
private fun Section(text: String) {
    val pal = LocalPalette.current
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall, color = pal.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 2.dp),
    )
}
