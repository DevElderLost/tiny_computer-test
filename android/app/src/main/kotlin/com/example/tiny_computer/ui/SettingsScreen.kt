package com.example.tiny_computer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.AudioStream
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.TermSessions
import com.example.tiny_computer.core.TinyEnv

private var micStreaming = false

/** Pengaturan global + kontainer (padanan SettingPage di main.dart; opsi VNC/AVNC/X11 diganti pengaturan Layar). */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(ThemeState.id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_nav_settings))

        Group(stringResource(R.string.tc_group_appearance)) {
            ChoiceRow(Themes.all.map { it.id to stringResource(it.label) }, theme) {
                theme = it; Cfg.theme = it; ThemeState.id = it
            }
        }

        Group(stringResource(R.string.tc_disp_title)) {
            HintText(stringResource(R.string.tc_disp_restart))
            TextRow("${stringResource(R.string.tc_ui_width)} (${stringResource(R.string.tc_disp_size)})", Cfg.displayWidth.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.displayWidth = v.coerceIn(0, 7680) } }
            TextRow(stringResource(R.string.tc_ui_height), Cfg.displayHeight.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.displayHeight = v.coerceIn(0, 4320) } }
            TextRow(stringResource(R.string.tc_disp_fps), Cfg.fpsLimit.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.fpsLimit = v.coerceIn(0, 240) } }
            TextRow(stringResource(R.string.tc_disp_upscaler), Cfg.upscaler.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.upscaler = v.coerceIn(0, 8) } }
            TextRow(stringResource(R.string.tc_disp_driver), Cfg.androidDriver) { Cfg.androidDriver = it.trim() }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_hidpi_support), Cfg.isHidpiEnabled, stringResource(R.string.tc_ui_apply_on_next_launch)) { Cfg.isHidpiEnabled = it }
            TextRow(stringResource(R.string.tc_ui_hidpi_env_var), Cfg.defaultHidpiOpt) { Cfg.defaultHidpiOpt = it }
        }

        Group(stringResource(R.string.tc_group_terminal)) {
            TextRow(stringResource(R.string.tc_ui_terminal_max_lines), Cfg.termMaxLines.toString(), number = true) { it.toIntOrNull()?.let { v -> Cfg.termMaxLines = v.coerceIn(100, 50000) } }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_enable_terminal_editing), Cfg.isTerminalWriteEnabled) { Cfg.isTerminalWriteEnabled = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_enable_terminal_keypad), Cfg.isTerminalCommandsEnabled) { Cfg.isTerminalCommandsEnabled = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_terminal_sticky_keys), Cfg.isStickyKey) { Cfg.isStickyKey = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_keep_screen_on), Cfg.wakelock) { Cfg.wakelock = it }
        }

        Group(stringResource(R.string.tc_group_startup)) {
            HintText(stringResource(R.string.tc_ui_restart_required_hint))
            SwitchRow(stringResource(R.string.tc_ui_start_with_g_u_i), Cfg.autoLaunchGui) { Cfg.autoLaunchGui = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_reinstall_boot_package), Cfg.reinstallBootstrap) { Cfg.reinstallBootstrap = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_getifaddrs_bridge), Cfg.getifaddrsBridge, stringResource(R.string.tc_ui_fix_getifaddrs_permission)) { Cfg.getifaddrsBridge = it }
            Div()
            TextRow(stringResource(R.string.tc_ui_startup_command), Containers.boot(TinyEnv.currentContainer, TinyEnv.defaultBoot), multiline = true) {
                Containers.put(TinyEnv.currentContainer, "boot", it)
            }
            ActionRow(stringResource(R.string.tc_ui_reset_startup_command)) { Containers.put(TinyEnv.currentContainer, "boot", TinyEnv.defaultBoot) }
        }

        Group(stringResource(R.string.tc_ui_graphics_acceleration)) {
            HintText(stringResource(R.string.tc_ui_graphics_acceleration_hint))
            TextRow(stringResource(R.string.tc_ui_virgl_server_params), Cfg.defaultVirglCommand, multiline = true) { Cfg.defaultVirglCommand = it }
            TextRow(stringResource(R.string.tc_ui_virgl_env_var), Cfg.defaultVirglOpt, multiline = true) { Cfg.defaultVirglOpt = it }
            SwitchRow(stringResource(R.string.tc_ui_enable_virgl), Cfg.virgl, stringResource(R.string.tc_ui_apply_on_next_launch)) { Cfg.virgl = it }
            Div()
            TextRow(stringResource(R.string.tc_ui_turnip_env_var), Cfg.defaultTurnipOpt, multiline = true) { Cfg.defaultTurnipOpt = it }
            SwitchRow(stringResource(R.string.tc_ui_enable_turnip_zink), Cfg.turnip, stringResource(R.string.tc_ui_apply_on_next_launch)) { Cfg.turnip = it }
            Div()
            SwitchRow(stringResource(R.string.tc_ui_enable_d_r_i3), Cfg.dri3) { Cfg.dri3 = it }
        }

        Group(stringResource(R.string.tc_group_audio)) {
            TextRow(stringResource(R.string.tc_ui_pulseaudio_port), Cfg.defaultAudioPort.toString(), number = true) { it.toIntOrNull()?.let { v -> if (v in 1024..65535) Cfg.defaultAudioPort = v } }
            Div()
            var mic by remember { mutableStateOf(micStreaming) }
            SwitchRow(stringResource(R.string.tc_ui_start_streaming), mic) { on ->
                val term = TermSessions.forCurrent(ctx)
                if (on) {
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        ctx.findActivity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                        mic = false
                    } else {
                        val path = "/tmp/android_audio"
                        AudioStream.startStreaming("${TinyEnv.dataPath}/containers/${TinyEnv.currentContainer}$path")
                        term.writeLine("""pactl load-module module-null-sink sink_name=AndroidSink sink_properties=device.description="Android_Audio_Stream"
pactl load-module module-remap-source master=AndroidSink.monitor source_name=AndroidMic source_properties=device.description="Android_Virtual_Mic"
pkill -f tiny_virtual_mic
tiny_virtual_mic $path AndroidSink &""")
                        micStreaming = true
                    }
                } else {
                    term.writeLine("pkill -f tiny_virtual_mic")
                    AudioStream.stopStreaming()
                    micStreaming = false
                }
            }
        }

        Group(stringResource(R.string.tc_ui_file_access)) {
            HintText(stringResource(R.string.tc_ui_file_access_hint))
            ActionRow(stringResource(R.string.tc_ui_request_all_files_access)) {
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            ActionRow(stringResource(R.string.tc_ui_ignore_battery_optimization)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        Group(stringResource(R.string.tc_ui_project_url)) {
            TinyEnv.links.forEach { (key, url) ->
                val id = ctx.resources.getIdentifier("tc_ui_" + key.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }, "string", ctx.packageName)
                ActionRow(if (id != 0) ctx.getString(id) else url) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}
