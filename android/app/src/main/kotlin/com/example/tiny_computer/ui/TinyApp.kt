package com.example.tiny_computer.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.Rootfs
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.DisplayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Page { HOME, TERMINAL, CONTROLLER, SETTINGS }

/** Kerangka UI Compose: layar pemuatan -> navigasi bawah (Desktop, Terminal, Controller, Pengaturan). */
@Composable
fun TinyApp() {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(Launcher.isStarted) }
    var status by remember { mutableStateOf(Rootfs.statusText) }
    var page by remember { mutableIntStateOf(Page.HOME.ordinal) }

    LaunchedEffect(Unit) {
        if (!ready) {
            val first = withContext(Dispatchers.IO) {
                // jalankan inisialisasi berat di thread lain sambil memantau teks status
                var result = false
                val t = Thread { result = Rootfs.initData(ctx); Launcher.start(ctx) }
                t.start()
                while (t.isAlive) { status = Rootfs.statusText; delay(300) }
                result
            }
            ready = true
            // proses shell keluar normal (kode 0) -> tutup aplikasi, seperti SystemNavigator.pop di versi Dart
            com.example.tiny_computer.core.TermSessions.forCurrent(ctx).onExit = { code -> if (code == 0) ctx.findActivity()?.finish() }
            // pengguna lama / pengaturan "mulai dengan GUI": langsung ke desktop
            if (Cfg.autoLaunchGui && !first) ctx.startActivity(Intent(ctx, DisplayActivity::class.java))
        }
    }

    if (!ready) { LoadingScreen(status); return }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = page == Page.HOME.ordinal, onClick = { page = Page.HOME.ordinal },
                    icon = { Icon(Icons.Filled.Computer, null) }, label = { Text(stringResource(R.string.tc_nav_home)) })
                NavigationBarItem(selected = page == Page.TERMINAL.ordinal, onClick = { page = Page.TERMINAL.ordinal },
                    icon = { Icon(Icons.Filled.Terminal, null) }, label = { Text(stringResource(R.string.tc_nav_terminal)) })
                NavigationBarItem(selected = page == Page.CONTROLLER.ordinal, onClick = { page = Page.CONTROLLER.ordinal },
                    icon = { Icon(Icons.Filled.SportsEsports, null) }, label = { Text(stringResource(R.string.tc_nav_controller)) })
                NavigationBarItem(selected = page == Page.SETTINGS.ordinal, onClick = { page = Page.SETTINGS.ordinal },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tc_nav_settings)) })
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.tc_ui_app_title), style = MaterialTheme.typography.headlineMedium)
        Text(Containers.name(TinyEnv.currentContainer), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tc_home_hint), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onOpen) { Text(stringResource(R.string.tc_home_start)) }
        Text(stringResource(R.string.tc_ui_update_request), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoadingScreen(status: String) {
    val ctx = LocalContext.current
    val text = if (status.isEmpty()) ctx.getString(R.string.tc_ui_app_title)
    else ctx.resources.getIdentifier("tc_ui_" + status.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }, "string", ctx.packageName)
        .takeIf { it != 0 }?.let { ctx.getString(it) } ?: status
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tc_ui_first_load_instructions), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
