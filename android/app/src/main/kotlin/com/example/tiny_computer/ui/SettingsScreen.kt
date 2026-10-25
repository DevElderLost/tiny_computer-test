package com.example.tiny_computer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.LaunchLog
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.SoundControl
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.ScreenFx

// (lebar, tinggi); 0 x 0 = otomatis
private val RESOLUTIONS = listOf(0 to 0, 1024 to 576, 1280 to 720, 1600 to 900, 1920 to 1080, 2560 to 1440)
private val FPS_PRESETS = listOf(0, 30, 45, 60, 90, 120, 144)
private val UPSCALERS = listOf("None", "Linear", "Nearest", "SGSR", "FSR", "FSR Fit", "Sharpen", "NIS", "SGSR HQ")

/** Pengaturan global + kontainer (padanan SettingPage di main.dart; opsi VNC/AVNC/X11 diganti pengaturan Layar). */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(ThemeState.id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_nav_settings))

        DeckGroup(stringResource(R.string.tc_group_appearance)) {
            DeckChoiceRow(stringResource(R.string.tc_theme_label), null, Themes.all.map { it.id to stringResource(it.label) }, theme) {
                theme = it; Cfg.theme = it; ThemeState.id = it
            }
        }

        DeckGroup(stringResource(R.string.tc_disp_title), help = stringResource(R.string.tc_disp_restart)) {
            // resolusi: pratetap lewat dropdown; "Custom" membuka isian lebar x tinggi
            var w by remember { mutableStateOf(Cfg.displayWidth) }
            var h by remember { mutableStateOf(Cfg.displayHeight) }
            var custom by remember { mutableStateOf(RESOLUTIONS.none { it.first == w && it.second == h }) }
            val sel = if (custom) "custom" else "$w x $h"
            DeckChoiceRow(
                stringResource(R.string.tc_disp_resolution), stringResource(R.string.tc_disp_size),
                RESOLUTIONS.map { (rw, rh) -> "$rw x $rh" to (if (rw == 0) stringResource(R.string.tc_opt_automatic) else "$rw × $rh") } +
                    ("custom" to stringResource(R.string.tc_opt_custom)),
                sel,
            ) { pick ->
                if (pick == "custom") custom = true
                else {
                    custom = false
                    RESOLUTIONS.first { "${it.first} x ${it.second}" == pick }.let { (rw, rh) -> w = rw; h = rh; Cfg.displayWidth = rw; Cfg.displayHeight = rh }
                }
            }
            if (custom) {
                DeckTextRow(stringResource(R.string.tc_ui_width), w.toString(), number = true) { it.toIntOrNull()?.let { v -> w = v.coerceIn(0, 7680); Cfg.displayWidth = w } }
                DeckTextRow(stringResource(R.string.tc_ui_height), h.toString(), number = true) { it.toIntOrNull()?.let { v -> h = v.coerceIn(0, 4320); Cfg.displayHeight = h } }
            }
            var fps by remember { mutableStateOf(Cfg.fpsLimit) }
            DeckChoiceRow(
                stringResource(R.string.tc_disp_fps), null,
                (FPS_PRESETS + fps).distinct().sorted().map { it to (if (it == 0) stringResource(R.string.tc_opt_panel_rate) else "$it FPS") },
                fps,
            ) { fps = it; Cfg.fpsLimit = it }
            var pm by remember { mutableStateOf(Cfg.presentMode) }
            DeckChoiceRow(
                stringResource(R.string.tc_disp_vsync), stringResource(R.string.tc_disp_vsync_hint),
                listOf(
                    0 to stringResource(R.string.tc_vsync_auto),
                    1 to stringResource(R.string.tc_vsync_fifo),
                    2 to stringResource(R.string.tc_vsync_fifo_relaxed),
                    3 to stringResource(R.string.tc_vsync_mailbox),
                    4 to stringResource(R.string.tc_vsync_immediate),
                ),
                pm,
            ) { pm = it; Cfg.presentMode = it; ScreenFx.applyPresentationIfRunning() }
            var buf by remember { mutableStateOf(Cfg.swapBuffers) }
            DeckChoiceRow(
                stringResource(R.string.tc_disp_buffering), stringResource(R.string.tc_disp_buffering_hint),
                listOf(
                    0 to stringResource(R.string.tc_vsync_auto),
                    2 to stringResource(R.string.tc_buffering_double),
                    3 to stringResource(R.string.tc_buffering_triple),
                    4 to stringResource(R.string.tc_buffering_quad),
                ),
                buf,
            ) { buf = it; Cfg.swapBuffers = it; ScreenFx.applyPresentationIfRunning() }
            var up by remember { mutableStateOf(Cfg.upscaler) }
            DeckChoiceRow(stringResource(R.string.tc_disp_upscaler), null, UPSCALERS.mapIndexed { i, n -> i to n }, up) { up = it; Cfg.upscaler = it }
            // HDR10 diputuskan saat compositor dinyalakan, jadi hanya bisa diubah di sini (efek lain ada di menu sesi > Effect)
            DeckSwitchRow(stringResource(R.string.tc_fx_hdr_output), stringResource(R.string.tc_fx_hdr_output_hint), Cfg.hdrOutput) { Cfg.hdrOutput = it }
            DeckTextRow(stringResource(R.string.tc_disp_driver), Cfg.androidDriver) { Cfg.androidDriver = it.trim() }
            DeckSwitchRow(stringResource(R.string.tc_ui_hidpi_support), stringResource(R.string.tc_ui_apply_on_next_launch), Cfg.isHidpiEnabled) { Cfg.isHidpiEnabled = it }
            DeckTextRow(stringResource(R.string.tc_ui_hidpi_env_var), Cfg.defaultHidpiOpt) { Cfg.defaultHidpiOpt = it }
        }

        ReshadeGroup()

        DriverGroup()

        FlathubGroup()

        DeckGroup(stringResource(R.string.tc_group_terminal)) {
            DeckTextRow(stringResource(R.string.tc_ui_terminal_max_lines), Cfg.termMaxLines.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.termMaxLines = v.coerceIn(100, 50000) } }
            DeckSwitchRow(stringResource(R.string.tc_ui_enable_terminal_editing), null, Cfg.isTerminalWriteEnabled) { Cfg.isTerminalWriteEnabled = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_enable_terminal_keypad), null, Cfg.isTerminalCommandsEnabled) { Cfg.isTerminalCommandsEnabled = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_terminal_sticky_keys), null, Cfg.isStickyKey) { Cfg.isStickyKey = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_keep_screen_on), null, Cfg.wakelock) { Cfg.wakelock = it }
        }

        DeckGroup(stringResource(R.string.tc_group_startup), help = stringResource(R.string.tc_ui_restart_required_hint)) {
            DeckSwitchRow(stringResource(R.string.tc_ui_reinstall_boot_package), null, Cfg.reinstallBootstrap) { Cfg.reinstallBootstrap = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_getifaddrs_bridge), stringResource(R.string.tc_ui_fix_getifaddrs_permission), Cfg.getifaddrsBridge) { Cfg.getifaddrsBridge = it }
            var bootKey by remember { mutableStateOf(0) }
            key(bootKey) {
                DeckTextRow(stringResource(R.string.tc_ui_startup_command), Containers.boot(TinyEnv.currentContainer, TinyEnv.defaultBoot), multiline = true) {
                    Containers.put(TinyEnv.currentContainer, "boot", it)
                }
            }
            DeckActionRow(stringResource(R.string.tc_ui_reset_startup_command), null, stringResource(R.string.tc_btn_reset)) {
                Containers.put(TinyEnv.currentContainer, "boot", TinyEnv.defaultBoot); bootKey++
            }
        }

        DeckGroup(stringResource(R.string.tc_ui_graphics_acceleration), help = stringResource(R.string.tc_ui_graphics_acceleration_hint)) {
            DeckTextRow(stringResource(R.string.tc_ui_virgl_server_params), Cfg.defaultVirglCommand, multiline = true) { Cfg.defaultVirglCommand = it }
            DeckTextRow(stringResource(R.string.tc_ui_virgl_env_var), Cfg.defaultVirglOpt, multiline = true) { Cfg.defaultVirglOpt = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_enable_virgl), stringResource(R.string.tc_ui_apply_on_next_launch), Cfg.virgl) { Cfg.virgl = it }
            DeckTextRow(stringResource(R.string.tc_ui_turnip_env_var), Cfg.defaultTurnipOpt, multiline = true) { Cfg.defaultTurnipOpt = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_enable_turnip_zink), stringResource(R.string.tc_ui_apply_on_next_launch), Cfg.turnip) { Cfg.turnip = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_enable_d_r_i3), null, Cfg.dri3) { Cfg.dri3 = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_fake_drm), stringResource(R.string.tc_ui_fake_drm_hint), Cfg.fakeDrm) { Cfg.fakeDrm = it }
            DeckSwitchRow(stringResource(R.string.tc_ui_desktop_wayland), stringResource(R.string.tc_ui_desktop_wayland_hint), Cfg.desktopWayland) { Cfg.desktopWayland = it }
        }

        DeckGroup(stringResource(R.string.tc_group_logs)) {
            DeckSwitchRow(stringResource(R.string.tc_ui_launch_log), stringResource(R.string.tc_ui_launch_log_hint), Cfg.launchLog) {
                Cfg.launchLog = it
            }
        }

        DeckGroup(stringResource(R.string.tc_group_audio)) {
            DeckSwitchRow(stringResource(R.string.tc_ui_direct_audio), stringResource(R.string.tc_ui_direct_audio_hint), Cfg.directAudio) { SoundControl.setDirect(ctx, it) }
            DeckSwitchRow(stringResource(R.string.tc_ui_direct_audio_alsa), stringResource(R.string.tc_ui_direct_audio_alsa_hint), Cfg.directAudioPreferAlsa) { Cfg.directAudioPreferAlsa = it }
            DeckTextRow(stringResource(R.string.tc_ui_direct_audio_buffer), Cfg.directAudioBufferMs.toString(), number = true) { it.toIntOrNull()?.let { v -> if (v in 10..200) Cfg.directAudioBufferMs = v } }
            DeckTextRow(stringResource(R.string.tc_ui_pulseaudio_port), Cfg.defaultAudioPort.toString(), number = true) { it.toIntOrNull()?.let { v -> if (v in 1024..65535) Cfg.defaultAudioPort = v } }
            // Mikrofon hanya bisa hidup pada mode PulseAudio (aturan dan keadaannya ada di SoundControl,
            // dipakai bersama tab Sound di menu sesi).
            LaunchedEffect(SoundControl.micOn) { if (SoundControl.micOn) SoundControl.watchMic(ctx) }
            DeckToggleRow(
                stringResource(R.string.tc_ui_start_streaming),
                if (SoundControl.micAllowed) null else stringResource(R.string.tc_sound_mic_need_pulse),
                SoundControl.micOn,
                enabled = SoundControl.micAllowed,
            ) { on -> if (on) SoundControl.startMic(ctx) else SoundControl.stopMic(ctx) }
            DeckToggleRow(
                stringResource(R.string.tc_ui_mic_monitoring),
                if (SoundControl.micOn) null else stringResource(R.string.tc_ui_mic_monitoring_hint),
                SoundControl.monitorOn && SoundControl.micOn,
                enabled = SoundControl.micOn,
            ) { on -> SoundControl.setMonitor(ctx, on) }
        }

    }
}
