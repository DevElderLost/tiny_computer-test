package com.example.tiny_computer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

/** Pengaturan controller: identitas pad (evdev Xbox 360 atau hidraw Steam Deck) dan overlay di layar. */
@Composable
fun ControllerScreen() {
    var identity by remember { mutableStateOf(Cfg.padIdentity) }
    val hasLib = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ctrl_title), stringResource(R.string.tc_ctrl_hint))
        if (!hasLib) Group { HintText(stringResource(R.string.tc_ctrl_missing)) }

        Group(stringResource(R.string.tc_ctrl_identity)) {
            ChoiceRow(
                listOf("xbox360" to stringResource(R.string.tc_ctrl_xbox), "deck" to stringResource(R.string.tc_ctrl_deck)),
                identity,
            ) { identity = it; Cfg.padIdentity = it }
        }
        Group(stringResource(R.string.tc_pad_overlay)) {
            SwitchRow(stringResource(R.string.tc_pad_overlay), Cfg.padOverlay) { Cfg.padOverlay = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ctrl_haptics), Cfg.padHaptics) { Cfg.padHaptics = it }
            Div()
            SliderRow(stringResource(R.string.tc_ctrl_opacity), Cfg.padOverlayOpacity.toFloat(), 0.2f..1f) { Cfg.padOverlayOpacity = it.toDouble() }
            Div()
            SliderRow(stringResource(R.string.tc_ctrl_scale), Cfg.padOverlayScale.toFloat(), 0.6f..1.6f) { Cfg.padOverlayScale = it.toDouble() }
        }
    }
}
