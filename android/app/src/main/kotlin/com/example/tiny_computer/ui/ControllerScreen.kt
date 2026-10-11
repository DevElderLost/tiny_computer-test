package com.example.tiny_computer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.TinyEnv
import java.io.File
import kotlin.math.abs

// Pratetap seperti ControllerPrefs.opacities / sizes di DroidDeck (persen).
private val OPACITIES = listOf(40, 60, 80, 100)
private val SIZES = listOf(80, 90, 100, 110, 125)

private fun nearest(options: List<Int>, percent: Int) = options.minByOrNull { abs(it - percent) } ?: options.first()

/**
 * Pengaturan controller bergaya DroidDeck: pilihan berupa pil ringkas (bukan tumpukan baris tinggi),
 * dan di lanskap dua kolom: identitas + status di kiri, overlay di kanan.
 */
@Composable
fun ControllerScreen() {
    var identity by remember { mutableStateOf(Cfg.padIdentity) }
    var overlay by remember { mutableStateOf(Cfg.padOverlay) }
    var haptics by remember { mutableStateOf(Cfg.padHaptics) }
    var opacity by remember { mutableStateOf(nearest(OPACITIES, (Cfg.padOverlayOpacity * 100).toInt())) }
    var size by remember { mutableStateOf(nearest(SIZES, (Cfg.padOverlayScale * 100).toInt())) }
    val hasLib = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile
    val land = isLandscape()

    val identityGroup: @Composable () -> Unit = {
        if (!hasLib) Group { HintText(stringResource(R.string.tc_ctrl_missing)) }
        Group(stringResource(R.string.tc_ctrl_identity)) {
            ChipChoiceRow(
                stringResource(R.string.tc_ctrl_identity),
                listOf("xbox360" to stringResource(R.string.tc_ctrl_xbox), "deck" to stringResource(R.string.tc_ctrl_deck)),
                identity,
            ) { identity = it; Cfg.padIdentity = it }
        }
    }
    val overlayGroup: @Composable () -> Unit = {
        Group(stringResource(R.string.tc_pad_overlay)) {
            SwitchRow(stringResource(R.string.tc_pad_overlay), overlay) { overlay = it; Cfg.padOverlay = it }
            Div()
            ChipChoiceRow(stringResource(R.string.tc_ctrl_opacity), OPACITIES.map { it to "$it%" }, opacity) {
                opacity = it; Cfg.padOverlayOpacity = it / 100.0
            }
            Div()
            ChipChoiceRow(stringResource(R.string.tc_ctrl_scale), SIZES.map { it to "$it%" }, size) {
                size = it; Cfg.padOverlayScale = it / 100.0
            }
            Div()
            SwitchRow(stringResource(R.string.tc_ctrl_haptics), haptics) { haptics = it; Cfg.padHaptics = it }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ctrl_title), stringResource(R.string.tc_ctrl_hint))
        if (land) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { identityGroup() }
                Column(Modifier.weight(1f)) { overlayGroup() }
            }
        } else {
            identityGroup()
            overlayGroup()
        }
    }
}
