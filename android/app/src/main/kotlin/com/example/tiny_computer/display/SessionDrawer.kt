package com.example.tiny_computer.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.droiddeck.launcher.input.ControllerPrefs
import androidx.compose.ui.platform.LocalContext
import com.example.tiny_computer.ui.DeckChoiceRow
import com.example.tiny_computer.ui.DeckGroup
import com.example.tiny_computer.ui.DeckSliderRow
import com.example.tiny_computer.ui.DeckToggle
import com.example.tiny_computer.ui.LocalPalette
import kotlin.math.roundToInt

/**
 * Menu sesi bergaya DroidDeck: lembar yang meluncur dari tepi kiri ketika jari digeser ke kanan dari
 * tepi layar (pemicunya ada di DisplayActivity), dengan latar redup di belakangnya.
 *
 * Tata letak mengikuti SessionDrawer DroidDeck: judul + tombol Stop di satu baris, deretan tab ikon
 * (Pointer, Input, Session) yang tab terpilihnya menyala, lalu pengaturan per halaman dalam kartu
 * [DeckGroup]. Tutup dengan ketuk di luar lembar, geser lembar ke kiri, atau tombol Back.
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
    var page by rememberSaveable { mutableStateOf(DrawerPage.POINTER) }
    val pageScroll = remember { DrawerPage.entries.associateWith { androidx.compose.foundation.ScrollState(0) } }
    val veil by animateFloatAsState(if (open) 0.45f else 0f, tween(220), label = "veil")

    // latar redup: ketukan di luar lembar menutup menu (juga selama animasi menutup, menelan sentuhan)
    if (open || veil > 0.01f) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.menuOpen = false },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        // layar pendek (lanskap): padding dan deretan tab dipadatkan supaya pengaturan dapat tinggi terbanyak
        val short = maxHeight < 440.dp
        val sheetWidth = minOf(360.dp, maxWidth * 0.92f)
        val sheetShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(180)),
        ) {
            Column(
                Modifier.fillMaxHeight().width(sheetWidth)
                    .clip(sheetShape)
                    .background(pal.background.copy(alpha = 0.97f))
                    .border(1.dp, pal.line, sheetShape)
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
                    .padding(if (short) 10.dp else 14.dp),
            ) {
                // judul + Stop
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.tc_display_menu),
                        fontSize = if (short) 18.sp else 20.sp, fontWeight = FontWeight.Bold, color = pal.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    StopPill(stringResource(R.string.tc_display_shutdown)) { confirmStop = true }
                }

                // tab halaman
                Row(
                    Modifier.fillMaxWidth().padding(top = if (short) 4.dp else 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DrawerPage.entries.forEach { item ->
                        DrawerTab(item, stringResource(item.title), item == page, short, Modifier.weight(1f)) { page = item }
                    }
                }

                // isi halaman (gulir sendiri-sendiri, posisi tiap halaman diingat)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll.getValue(page)).padding(bottom = 8.dp)) {
                    when (page) {
                        DrawerPage.POINTER -> PointerPage(state)
                        DrawerPage.INPUT -> InputPage(state, onPadOverlayChange)
                        DrawerPage.SESSION -> SessionPage(
                            onBack = { state.menuOpen = false; onBackToTerminal() },
                        )
                    }
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
                    Text(stringResource(R.string.tc_display_shutdown), color = pal.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.tc_close), color = pal.onSurfaceVariant) }
            },
        )
    }
}

private const val CLOSE_DRAG_PX = 48f

/** Halaman menu sesi; urutannya = urutan tab. */
private enum class DrawerPage(val title: Int, val icon: ImageVector) {
    POINTER(R.string.tc_drawer_pointer, Icons.Outlined.Mouse),
    INPUT(R.string.tc_drawer_input, Icons.Outlined.Keyboard),
    SESSION(R.string.tc_drawer_session, Icons.Outlined.Settings),
}

@Composable
private fun PointerPage(state: DisplayUiState) {
    DeckGroup(stringResource(R.string.tc_drawer_pointer)) {
        DrawerToggleRow(stringResource(R.string.tc_display_touchpad), state.touchpad) {
            state.touchpad = it
            Cfg.touchpadMode = it
        }
        if (state.touchpad) {
            DeckSliderRow(
                stringResource(R.string.tc_display_touchpad_speed), null,
                (state.touchpadSpeed * 100).roundToInt(), 50..400, step = 5, format = { "%.2f".format(it / 100f) },
            ) {
                state.touchpadSpeed = it / 100f
                Cfg.touchpadSpeed = it / 100.0
            }
            DeckSliderRow(
                stringResource(R.string.tc_display_touchpad_scroll), null,
                (state.touchpadScroll * 100).roundToInt(), 30..300, step = 5, format = { "%.2f".format(it / 100f) },
            ) {
                state.touchpadScroll = it / 100f
                Cfg.touchpadScroll = it / 100.0
            }
            DrawerToggleRow(stringResource(R.string.tc_display_touchpad_natural), state.touchpadNatural) {
                state.touchpadNatural = it
                Cfg.touchpadNatural = it
            }
        }
        DrawerToggleRow(stringResource(R.string.tc_display_cursor), state.showCursor) {
            state.showCursor = it
            Cfg.showCursor = it
        }
        if (state.showCursor) {
            DeckSliderRow(
                stringResource(R.string.tc_display_cursor_size), null,
                (state.cursorScale * 100).roundToInt(), 50..400, step = 5, format = { "%.2f".format(it / 100f) },
            ) {
                state.cursorScale = it / 100f
                Cfg.cursorScale = it / 100.0
            }
        }
    }
}

@Composable
private fun InputPage(state: DisplayUiState, onPadOverlayChange: (Boolean) -> Unit) {
    DeckGroup(stringResource(R.string.tc_drawer_input)) {
        DrawerToggleRow(stringResource(R.string.tc_display_keyboard), state.pcKeyboard) {
            state.pcKeyboard = it
            // keyboard dibuka: menu menyingkir supaya layar terlihat
            if (it) state.menuOpen = false
        }
        DrawerToggleRow(stringResource(R.string.tc_pad_overlay), state.padOverlay) {
            state.padOverlay = it
            onPadOverlayChange(it)
        }
        // gyro: saklar yang sama dengan Pengaturan > Controller (ControllerPrefs); DisplayActivity menyalakan sensornya
        val ctx = LocalContext.current
        DrawerToggleRow(stringResource(R.string.tc_ctrl_gyro), state.gyro) {
            state.gyro = it
            ControllerPrefs.setGyro(ctx, it)
        }
        if (state.gyro) {
            DeckChoiceRow(
                stringResource(R.string.tc_ctrl_gyro_map), null,
                listOf(
                    ControllerPrefs.GYRO_OFF to stringResource(R.string.tc_gyro_off),
                    ControllerPrefs.GYRO_LEFT to stringResource(R.string.tc_gyro_left),
                    ControllerPrefs.GYRO_RIGHT to stringResource(R.string.tc_gyro_right),
                ),
                state.gyroTarget,
            ) {
                state.gyroTarget = it
                ControllerPrefs.setGyroTarget(ctx, it)
            }
            DeckSliderRow(stringResource(R.string.tc_ctrl_gyro_sens), null, state.gyroSens, 1..10) {
                state.gyroSens = it
                ControllerPrefs.setGyroSensitivity(ctx, it)
            }
        }
    }
}

@Composable
private fun SessionPage(onBack: () -> Unit) {
    val pal = LocalPalette.current
    DeckGroup(stringResource(R.string.tc_drawer_session)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DrawerActionButton(stringResource(R.string.tc_display_back), Icons.Outlined.Terminal, pal.onBackground, onBack)
        }
    }
}

/** Satu baris saklar: label di kiri, saklar selalu di kanan (lembar sempit, jadi tidak memakai DeckRow). */
@Composable
private fun DrawerToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, modifier = Modifier.weight(1f).padding(end = 10.dp))
            DeckToggle(checked, onChange = onChange)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** Tab ikon: yang terpilih menyala (cahaya sinyal + bingkai), yang lain memudar dan mengecil sedikit. */
@Composable
private fun DrawerTab(page: DrawerPage, label: String, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    val motion = tween<Float>(300, easing = FastOutSlowInEasing)
    val glow by animateFloatAsState(if (selected) 1f else 0f, motion, label = "tabGlow")
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, motion, label = "tabScale")
    val alpha by animateFloatAsState(if (selected) 1f else 0.65f, motion, label = "tabAlpha")
    val tint by animateColorAsState(if (selected) pal.onBackground else pal.onSurfaceVariant, tween(300), label = "tabTint")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.heightIn(min = if (compact) 46.dp else 62.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(shape)
            .background(Brush.radialGradient(listOf(pal.signal.copy(alpha = 0.34f * glow), pal.signal.copy(alpha = 0.10f * glow), Color.Transparent)))
            .border(1.dp, if (selected) pal.signal.copy(alpha = 0.7f) else Color.Transparent, shape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(page.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
        if (!compact) Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Tombol Stop kecil di sudut judul: garis dan ikon merah, isi merah tipis. */
@Composable
private fun StopPill(description: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = description }
            .clip(shape).background(pal.error.copy(alpha = 0.10f)).border(1.dp, pal.error.copy(alpha = 0.55f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
    ) {
        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = pal.error, modifier = Modifier.size(18.dp))
        Text(description, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.error)
    }
}

/** Tombol aksi selebar kartu: ikon + teks di atas permukaan membulat (DrawerActionButton DroidDeck). */
@Composable
private fun DrawerActionButton(text: String, icon: ImageVector, tint: Color, onClick: () -> Unit, danger: Boolean = false) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (danger) pal.error.copy(alpha = 0.10f) else pal.surfaceVariant)
            .border(1.dp, if (danger) pal.error.copy(alpha = 0.55f) else pal.line2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}
