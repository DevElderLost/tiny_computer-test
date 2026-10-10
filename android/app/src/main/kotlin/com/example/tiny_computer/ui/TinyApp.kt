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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.Rootfs
import com.example.tiny_computer.core.TermSessions
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.DisplayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Page(val icon: ImageVector, val label: Int) {
    HOME(Icons.Filled.Computer, R.string.tc_nav_home),
    TERMINAL(Icons.Filled.Terminal, R.string.tc_nav_terminal),
    CONTROLLER(Icons.Filled.SportsEsports, R.string.tc_nav_controller),
    SETTINGS(Icons.Filled.Settings, R.string.tc_nav_settings),
}

/** Kerangka UI Compose: layar pemuatan -> navigasi bawah (Desktop, Terminal, Controller, Pengaturan). */
@Composable
fun TinyApp() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    var ready by remember { mutableStateOf(Launcher.isStarted) }
    var status by remember { mutableStateOf(Rootfs.statusText) }
    var page by remember { mutableIntStateOf(Page.HOME.ordinal) }

    LaunchedEffect(Unit) {
        if (!ready) {
            val first = withContext(Dispatchers.IO) {
                var result = false
                val t = Thread { result = Rootfs.initData(ctx); Launcher.start(ctx) }
                t.start()
                while (t.isAlive) { status = Rootfs.statusText; delay(300) }
                result
            }
            ready = true
            // proses shell keluar normal (kode 0) -> tutup aplikasi, seperti SystemNavigator.pop di versi Dart
            TermSessions.forCurrent(ctx).onExit = { code -> if (code == 0) ctx.findActivity()?.finish() }
            if (Cfg.autoLaunchGui && !first) ctx.startActivity(Intent(ctx, DisplayActivity::class.java))
        }
    }

    if (!ready) { LoadingScreen(status); return }

    Scaffold(
        containerColor = pal.background,
        bottomBar = {
            NavigationBar(containerColor = pal.surface, tonalElevation = 0.dp) {
                Page.values().forEach { p ->
                    NavigationBarItem(
                        selected = page == p.ordinal, onClick = { page = p.ordinal },
                        icon = { Icon(p.icon, null) },
                        label = { Text(stringResource(p.label), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = pal.signal, selectedTextColor = pal.signal,
                            indicatorColor = pal.signal.copy(alpha = 0.16f),
                            unselectedIconColor = pal.onSurfaceVariant, unselectedTextColor = pal.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when (Page.values()[page]) {
                Page.HOME -> HomeScreen { ctx.startActivity(Intent(ctx, DisplayActivity::class.java)) }
                Page.TERMINAL -> TerminalScreen()
                Page.CONTROLLER -> ControllerScreen()
                Page.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpen: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val (w, h) = Launcher.displaySize(ctx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ui_app_title), Containers.name(TinyEnv.currentContainer))

        // kartu utama: gradasi lembut dari warna sinyal
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape)
                .background(Brush.linearGradient(listOf(pal.signal.copy(alpha = 0.28f), pal.surface, pal.surface)), shape)
                .border(1.dp, pal.line2, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusChip(
                stringResource(if (Launcher.isStarted) R.string.tc_status_running else R.string.tc_status_starting),
                if (Launcher.isStarted) pal.good else pal.signal,
            )
            Text(stringResource(R.string.tc_home_hint), style = MaterialTheme.typography.bodyMedium, color = pal.onBackground)
            DeckButton(stringResource(R.string.tc_home_start), Modifier.fillMaxWidth(), onClick = onOpen)
        }

        Group(stringResource(R.string.tc_group_session)) {
            InfoRow(stringResource(R.string.tc_home_resolution), "$w × $h")
            Div()
            InfoRow(stringResource(R.string.tc_home_pad), stringResource(if (Cfg.padIdentity == "deck") R.string.tc_ctrl_deck else R.string.tc_ctrl_xbox))
            Div()
            InfoRow(stringResource(R.string.tc_home_driver), Cfg.androidDriver.ifEmpty { stringResource(R.string.tc_home_driver_auto) })
        }
        Text(
            stringResource(R.string.tc_ui_update_request),
            style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LoadingScreen(status: String) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val text = if (status.isEmpty()) ctx.getString(R.string.tc_ui_app_title)
    else ctx.resources.getIdentifier("tc_ui_" + status.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }, "string", ctx.packageName)
        .takeIf { it != 0 }?.let { ctx.getString(it) } ?: status
    Box(Modifier.fillMaxSize().background(pal.background).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = pal.signal, trackColor = pal.line2)
            Text(text, Modifier.padding(top = 28.dp), style = MaterialTheme.typography.titleMedium, color = pal.onBackground)
            Column(
                Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(pal.surface).border(1.dp, pal.line, RoundedCornerShape(14.dp)).padding(16.dp),
            ) {
                Text(stringResource(R.string.tc_ui_first_load_instructions), style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant)
            }
        }
    }
}
