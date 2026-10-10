package com.example.tiny_computer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.TinyEnv
import java.io.File

/** Pengaturan controller: identitas pad (evdev Xbox 360 atau hidraw Steam Deck) dan overlay di layar. */
@Composable
fun ControllerScreen() {
    val ctx = LocalContext.current
    var identity by remember { mutableStateOf(Cfg.padIdentity) }
    val hasLib = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle(stringResource(R.string.tc_ctrl_title))
        HintText(stringResource(R.string.tc_ctrl_hint))
        if (!hasLib) HintText(stringResource(R.string.tc_ctrl_missing))

        SectionTitle(stringResource(R.string.tc_ctrl_identity))
        listOf("xbox360" to R.string.tc_ctrl_xbox, "deck" to R.string.tc_ctrl_deck).forEach { (id, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = identity == id, onClick = { identity = id; Cfg.padIdentity = id })
                Text(stringResource(label))
            }
        }
        SwitchRow(stringResource(R.string.tc_pad_overlay), Cfg.padOverlay) { Cfg.padOverlay = it }
        SwitchRow(stringResource(R.string.tc_ctrl_haptics), Cfg.padHaptics) { Cfg.padHaptics = it }
        SliderRow(stringResource(R.string.tc_ctrl_opacity), Cfg.padOverlayOpacity.toFloat(), 0.2f..1f) { Cfg.padOverlayOpacity = it.toDouble() }
        SliderRow(stringResource(R.string.tc_ctrl_scale), Cfg.padOverlayScale.toFloat(), 0.6f..1.6f) { Cfg.padOverlayScale = it.toDouble() }
    }
}
