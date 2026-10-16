package com.example.tiny_computer.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.input.ControllerPrefs
import com.example.tiny_computer.R
import com.example.tiny_computer.controller.ControllerEditorActivity
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Startup
import com.example.tiny_computer.core.TinyEnv
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

// Jangkauan seekbar: opasitas 0.2..1.0, ukuran 0.6..1.6 (sama dengan batas ControllerPrefs.read).
private val OPACITY_RANGE = 20..100
private val SIZE_RANGE = 60..160

/**
 * Pengaturan controller, isi dan urutannya sama dengan ControllerRows DroidDeck: warna, opasitas,
 * ukuran, klik stik, stik adaptif, tombol Steam / QAM / keyboard, editor posisi, mapping tombol, dan
 * reset. Semua disimpan di ControllerPrefs, yang juga dibaca OnScreenControls saat sesi berjalan.
 * Di lanskap dua kolom: dasar + tampilan di kiri, perilaku + tata letak di kanan.
 */
@Composable
fun ControllerScreen() {
    val ctx = LocalContext.current
    var identity by remember { mutableStateOf(Cfg.padIdentity) }
    var overlay by remember { mutableStateOf(Cfg.padOverlay) }
    var c by remember { mutableStateOf(ControllerPrefs.read(ctx)) }
    var opacity by remember { mutableIntStateOf((Cfg.padOverlayOpacity * 100).roundToInt().coerceIn(OPACITY_RANGE.first, OPACITY_RANGE.last)) }
    var size by remember { mutableIntStateOf((Cfg.padOverlayScale * 100).roundToInt().coerceIn(SIZE_RANGE.first, SIZE_RANGE.last)) }
    var mappingOpen by remember { mutableStateOf(false) }
    var gyro by remember { mutableStateOf(ControllerPrefs.gyroEnabled(ctx)) }
    var gyroTarget by remember { mutableStateOf(ControllerPrefs.gyroTarget(ctx)) }
    var gyroSens by remember { mutableIntStateOf(ControllerPrefs.gyroSensitivity(ctx)) }
    var trackpad by remember { mutableStateOf(ControllerPrefs.trackpadMode(ctx)) }
    var trackpadSpeed by remember { mutableIntStateOf(ControllerPrefs.trackpadMouseSpeed(ctx)) }
    val hasLib = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile
    val land = isLandscape()

    fun refresh() { c = ControllerPrefs.read(ctx) }

    // Menggeser seekbar memanggil onChange berkali-kali; tulis ke penyimpanan setelah berhenti sejenak.
    LaunchedEffect(opacity) {
        delay(200)
        if ((Cfg.padOverlayOpacity * 100).roundToInt() != opacity) Cfg.padOverlayOpacity = opacity / 100.0
    }
    LaunchedEffect(size) {
        delay(200)
        if ((Cfg.padOverlayScale * 100).roundToInt() != size) Cfg.padOverlayScale = size / 100.0
    }

    LaunchedEffect(trackpadSpeed) {
        delay(150)
        if (ControllerPrefs.trackpadMouseSpeed(ctx) != trackpadSpeed) ControllerPrefs.setTrackpadMouseSpeed(ctx, trackpadSpeed)
    }

    LaunchedEffect(gyroSens) {
        delay(150)
        if (ControllerPrefs.gyroSensitivity(ctx) != gyroSens) ControllerPrefs.setGyroSensitivity(ctx, gyroSens)
    }

    if (mappingOpen) {
        MappingPage(c.mapping, onBack = { mappingOpen = false }) { id, target ->
            ControllerPrefs.setTarget(ctx, id, target); refresh()
        }
        return
    }

    val basics: @Composable () -> Unit = {
        // libfakeinput.so baru disalin saat pemasangan; sebelum itu bukan berarti hilang dari APK.
        if (Startup.installed && !hasLib) {
            Text(
                stringResource(R.string.tc_ctrl_missing), fontSize = 13.sp, color = LocalPalette.current.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        DeckGroup(stringResource(R.string.tc_pad_overlay)) {
            DeckToggleRow(stringResource(R.string.tc_pad_overlay), null, overlay) { overlay = it; Cfg.padOverlay = it }
            DeckChoiceRow(
                stringResource(R.string.tc_ctrl_identity), stringResource(R.string.tc_ctrl_hint),
                listOf("xbox360" to stringResource(R.string.tc_ctrl_xbox), "deck" to stringResource(R.string.tc_ctrl_deck)),
                identity,
            ) { identity = it; Cfg.padIdentity = it }
        }
        DeckGroup(stringResource(R.string.ctrl_onscreen_buttons)) {
            DeckRow(stringResource(R.string.ctrl_color), stringResource(R.string.ctrl_color_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControllerPrefs.tints.forEach { (color, _) ->
                        val on = c.tint == color
                        Box(
                            Modifier.size(24.dp).background(Color(color), CircleShape)
                                .border(if (on) 2.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickableNoRipple { ControllerPrefs.setTint(ctx, color); refresh() },
                        )
                    }
                }
            }
            DeckSliderRow(
                stringResource(R.string.ctrl_opacity), null, opacity, OPACITY_RANGE, step = 5,
                format = { "$it%" },
            ) { opacity = it }
            DeckSliderRow(
                stringResource(R.string.ctrl_size), stringResource(R.string.ctrl_size_hint), size, SIZE_RANGE, step = 5,
                format = { "$it%" },
            ) { size = it }
        }
    }

    val behaviour: @Composable () -> Unit = {
        DeckGroup(stringResource(R.string.ctrl_defaults)) {
            DeckToggleRow(stringResource(R.string.ctrl_stick_click), stringResource(R.string.ctrl_stick_click_hint), c.stickClick) {
                ControllerPrefs.setStickClick(ctx, it); refresh()
            }
            DeckToggleRow(stringResource(R.string.ctrl_adaptive), stringResource(R.string.ctrl_adaptive_hint), c.adaptiveSticks) {
                ControllerPrefs.setAdaptiveSticks(ctx, it); refresh()
            }
            DeckToggleRow(stringResource(R.string.ctrl_steam_button), null, c.steamButton) { ControllerPrefs.setSteamButton(ctx, it); refresh() }
            DeckToggleRow(stringResource(R.string.ctrl_qam_button), null, c.qamButton) { ControllerPrefs.setQamButton(ctx, it); refresh() }
            DeckToggleRow(stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton) { ControllerPrefs.setKeyboardButton(ctx, it); refresh() }
            DeckToggleRow(stringResource(R.string.ctrl_rumble), stringResource(R.string.tc_ctrl_rumble_hint), c.rumble) {
                ControllerPrefs.setRumble(ctx, it); refresh()
            }
        }
        DeckGroup(stringResource(R.string.tc_ctrl_motion_group)) {
            DeckToggleRow(stringResource(R.string.tc_ctrl_gyro), stringResource(R.string.tc_ctrl_gyro_hint), gyro) {
                gyro = it; ControllerPrefs.setGyro(ctx, it)
            }
            DeckChoiceRow(
                stringResource(R.string.tc_ctrl_gyro_map), stringResource(R.string.tc_ctrl_gyro_map_hint),
                listOf(
                    ControllerPrefs.GYRO_OFF to stringResource(R.string.tc_gyro_off),
                    ControllerPrefs.GYRO_LEFT to stringResource(R.string.tc_gyro_left),
                    ControllerPrefs.GYRO_RIGHT to stringResource(R.string.tc_gyro_right),
                ),
                gyroTarget,
            ) { gyroTarget = it; ControllerPrefs.setGyroTarget(ctx, it) }
            DeckSliderRow(stringResource(R.string.tc_ctrl_gyro_sens), null, gyroSens, 1..10) { gyroSens = it }
            DeckChoiceRow(
                stringResource(R.string.tc_ctrl_trackpad), stringResource(R.string.tc_ctrl_trackpad_hint),
                listOf(
                    ControllerPrefs.PAD_STICK to stringResource(R.string.tc_trackpad_stick),
                    ControllerPrefs.PAD_HID to stringResource(R.string.tc_trackpad_hid),
                    ControllerPrefs.PAD_MOUSE to stringResource(R.string.tc_trackpad_mouse),
                ),
                trackpad,
            ) { trackpad = it; ControllerPrefs.setTrackpadMode(ctx, it) }
            if (trackpad == ControllerPrefs.PAD_MOUSE) {
                DeckSliderRow(stringResource(R.string.tc_ctrl_trackpad_speed), null, trackpadSpeed, 1..10) { trackpadSpeed = it }
            }
        }
        DeckGroup(stringResource(R.string.ctrl_layout)) {
            DeckRow(
                stringResource(R.string.ctrl_layout),
                stringResource(if (c.customLayout) R.string.ctrl_layout_custom else R.string.ctrl_layout_default),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckButton(stringResource(R.string.ctrl_edit), primary = false) {
                        ctx.startActivity(Intent(ctx, ControllerEditorActivity::class.java))
                    }
                    if (c.customLayout) DeckButton(stringResource(R.string.ctrl_reset), primary = false) {
                        ControllerPrefs.resetAllLayouts(ctx); refresh()
                    }
                }
            }
            val remapped = c.mapping.count { (id, target) -> id != target }
            DeckRow(
                stringResource(R.string.ctrl_mapping),
                if (remapped == 0) stringResource(R.string.ctrl_mapping_default) else stringResource(R.string.ctrl_mapping_count, remapped, c.mapping.size),
            ) { DeckButton(stringResource(R.string.ctrl_configure), primary = false) { mappingOpen = true } }
            DeckRow(stringResource(R.string.ctrl_reset_all), stringResource(R.string.ctrl_reset_all_hint)) {
                DeckButton(stringResource(R.string.ctrl_reset), primary = false) {
                    ControllerPrefs.resetAll(ctx)
                    gyro = false; gyroTarget = ControllerPrefs.GYRO_RIGHT; gyroSens = 5; trackpad = ControllerPrefs.PAD_STICK; trackpadSpeed = 5
                    identity = "xbox360"; Cfg.padIdentity = identity
                    overlay = false; Cfg.padOverlay = overlay
                    opacity = 65; size = 100
                    Cfg.padOverlayOpacity = 0.65; Cfg.padOverlayScale = 1.0
                    refresh()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ctrl_title), stringResource(R.string.tc_ctrl_title_hint))
        if (land) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { basics() }
                Column(Modifier.weight(1f)) { behaviour() }
            }
        } else {
            basics()
            behaviour()
        }
    }
}

/** Mapping tombol (ControllerMappingPage DroidDeck): apa yang dikirim tiap tombol layar ke game. */
@Composable
private fun MappingPage(mapping: Map<String, String>, onBack: () -> Unit, onPick: (String, String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.ctrl_mapping), stringResource(R.string.ctrl_mapping_lede))
        val targets = ControllerPrefs.targets.map { (id, label) -> id to stringResource(label) }
        DeckGroup(stringResource(R.string.ctrl_onscreen_buttons)) {
            for ((id, name) in ControllerPrefs.mappable) {
                DeckChoiceRow(stringResource(name), null, targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        DeckGroup(stringResource(R.string.ctrl_defaults)) {
            DeckRow(stringResource(R.string.ctrl_reset_mapping), stringResource(R.string.ctrl_mapping_default)) {
                DeckButton(stringResource(R.string.ctrl_reset), primary = false) {
                    ControllerPrefs.resetMapping(ctx)
                    for ((id, _) in ControllerPrefs.mappable) onPick(id, id)
                }
            }
        }
        DeckButton(stringResource(R.string.ctrl_back), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), onClick = onBack)
    }
}
