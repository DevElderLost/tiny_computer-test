package com.example.tiny_computer.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
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
import com.example.tiny_computer.core.Startup
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
}

/**
 * Kerangka UI Compose: navigasi (Desktop, Terminal, Controller, Pengaturan). Pemasangan sistem dan start
 * kontainer tidak jalan otomatis; keduanya dimulai dari tombol Open desktop (lihat [Startup]) dengan
 * layar progres [StartupOverlay] di atas seluruh UI.
 */
@Composable
fun TinyApp() {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(Page.HOME.ordinal) }
    var showSettings by remember { mutableStateOf(false) }
    val phase = Startup.phase

    val openDesktop = { ctx.startActivity(Intent(ctx, DisplayActivity::class.java)) }
    val requestDesktop: () -> Unit = {
        if (Startup.phase == Startup.Phase.DONE) openDesktop() else Startup.begin(ctx, openDesktop = true)
    }

    LaunchedEffect(Unit) {
        // Sistem sudah terpasang dan "Mulai dengan GUI" dinyalakan: langsung masuk desktop seperti sebelumnya.
        // Peluncuran pertama (belum terpasang) selalu menunggu tombol Open desktop.
        if (Startup.phase == Startup.Phase.IDLE && Startup.installed && Cfg.autoLaunchGui) Startup.begin(ctx, openDesktop = true)
    }
    LaunchedEffect(phase) {
        if (phase == Startup.Phase.DONE) {
            // proses shell keluar normal (kode 0) -> tutup aplikasi, seperti SystemNavigator.pop di versi Dart
            TermSessions.forCurrent(ctx).onExit = { code -> if (code == 0) ctx.findActivity()?.finish() }
            if (Startup.consumeOpen()) openDesktop()
        }
    }
    // jangan keluar dari aplikasi di tengah pemasangan
    BackHandler(enabled = phase == Startup.Phase.RUNNING) {}
    // Pengaturan: halaman penuh dari ikon roda gigi di samping Open desktop (bukan lagi tab navigasi)
    BackHandler(enabled = showSettings && phase != Startup.Phase.RUNNING) { showSettings = false }

    Box(Modifier.fillMaxSize()) {
        TinyShell(page, { page = it }, requestDesktop, { showSettings = true }, phase == Startup.Phase.DONE)
        if (showSettings) SettingsPage(onBack = { showSettings = false })
        if (phase == Startup.Phase.RUNNING || phase == Startup.Phase.FAILED) StartupOverlay(onClose = { Startup.dismiss() })
    }
}

@Composable
private fun TinyShell(page: Int, onPage: (Int) -> Unit, onOpenDesktop: () -> Unit, onOpenSettings: () -> Unit, started: Boolean) {
    val pal = LocalPalette.current
    val landscape = isLandscape()
    val content: @Composable () -> Unit = {
        when (Page.values()[page]) {
            Page.HOME -> HomeScreen(onOpenDesktop, onOpenSettings)
            Page.TERMINAL -> if (started) TerminalScreen() else TerminalGate()
            Page.CONTROLLER -> ControllerScreen()
        }
    }

    if (landscape) {
        // Lanskap seperti DroidDeck: rel navigasi di kiri (bukan bilah bawah yang memakan tinggi
        // layar), isi halaman di sisi kanan dengan inset sistem (tombol navigasi, poni) dihormati.
        Row(Modifier.fillMaxSize().background(pal.background)) {
            NavigationRail(containerColor = pal.surface, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.weight(1f))
                Page.values().forEach { p ->
                    NavigationRailItem(
                        selected = page == p.ordinal, onClick = { onPage(p.ordinal) },
                        icon = { Icon(p.icon, null) },
                        label = { Text(stringResource(p.label), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        alwaysShowLabel = true,
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = pal.signal, selectedTextColor = pal.signal,
                            indicatorColor = pal.signal.copy(alpha = 0.16f),
                            unselectedIconColor = pal.onSurfaceVariant, unselectedTextColor = pal.onSurfaceVariant,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)),
            ) { content() }
        }
    } else {
        Scaffold(
            containerColor = pal.background,
            bottomBar = {
                NavigationBar(containerColor = pal.surface, tonalElevation = 0.dp) {
                    Page.values().forEach { p ->
                        NavigationBarItem(
                            selected = page == p.ordinal, onClick = { onPage(p.ordinal) },
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
            Column(Modifier.fillMaxSize().padding(inner)) { content() }
        }
    }
}

@Composable
private fun HomeScreen(onOpen: () -> Unit, onSettings: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val land = isLandscape()
    val (w, h) = Launcher.displaySize(ctx)
    val mainCard: @Composable () -> Unit = {
        // kartu utama: gradasi lembut dari warna sinyal
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape)
                .background(Brush.linearGradient(listOf(pal.signal.copy(alpha = 0.28f), pal.surface, pal.surface)), shape)
                .border(1.dp, pal.line2, shape).padding(if (land) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (land) 10.dp else 14.dp),
        ) {
            val phase = Startup.phase
            StatusChip(
                stringResource(
                    when (phase) {
                        Startup.Phase.DONE -> R.string.tc_status_running
                        Startup.Phase.RUNNING -> R.string.tc_status_starting
                        else -> R.string.tc_status_idle
                    },
                ),
                when (phase) {
                    Startup.Phase.DONE -> pal.good
                    Startup.Phase.RUNNING -> pal.signal
                    else -> pal.onSurfaceVariant
                },
            )
            Text(stringResource(R.string.tc_home_hint), style = MaterialTheme.typography.bodyMedium, color = pal.onBackground)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DeckButton(stringResource(R.string.tc_home_start), Modifier.weight(1f), onClick = onOpen)
                SettingsCog(onSettings)
            }
        }
    }
    val session: @Composable () -> Unit = {
        Group(stringResource(R.string.tc_group_session)) {
            InfoRow(stringResource(R.string.tc_home_resolution), "$w × $h")
            Div()
            InfoRow(stringResource(R.string.tc_home_pad), stringResource(if (Cfg.padIdentity == "deck") R.string.tc_ctrl_deck else R.string.tc_ctrl_xbox))
            Div()
            InfoRow(stringResource(R.string.tc_home_driver), Cfg.androidDriver.ifEmpty { stringResource(R.string.tc_home_driver_auto) })
            Div()
            InfoRow(stringResource(R.string.tc_home_linux_driver), Cfg.linuxDriver.ifEmpty { stringResource(R.string.tc_home_driver_auto) })
        }
    }
    val note: @Composable () -> Unit = {
        Text(
            stringResource(R.string.tc_ui_update_request),
            style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ui_app_title), Containers.name(TinyEnv.currentContainer))
        if (land) {
            // dua kolom: kartu mulai di kiri, info sesi di kanan; tinggi layar lanskap sempit
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { StartupNotice(); mainCard(); note() }
                Column(Modifier.weight(1f)) { session() }
            }
        } else {
            StartupNotice(); mainCard(); session(); note()
        }
    }
}

/** Peringatan (bahasa Inggris) dari alur Open desktop, mis. internet tidak aktif; ketuk untuk menutup. */
@Composable
private fun StartupNotice() {
    val message = Startup.notice ?: return
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape)
            .background(pal.error.copy(alpha = 0.12f), shape).border(1.dp, pal.error.copy(alpha = 0.5f), shape)
            .clickableNoRipple { Startup.clearNotice() }.padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = pal.error, modifier = Modifier.size(20.dp))
        Text(message, fontSize = 13.sp, lineHeight = 18.sp, color = pal.onBackground)
    }
}

/** Roda gigi pengaturan di samping tombol utama (Cog milik DroidDeck): kotak 48 dp, berputar setengah putaran saat ditekan. */
@Composable
private fun SettingsCog(onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    var spins by remember { mutableIntStateOf(0) }
    val rot by animateFloatAsState(spins * 180f, tween(350), label = "cog")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(shape).background(Color.White.copy(alpha = 0.03f)).border(1.dp, pal.line2, shape)
            .clickable { spins++; onClick() },
    ) {
        Icon(Icons.Filled.Settings, stringResource(R.string.tc_nav_settings), tint = pal.onBackground, modifier = Modifier.size(20.dp).rotate(rot))
    }
}

/** Halaman Pengaturan penuh dengan tombol kembali; menutupi navigasi di bawahnya. */
@Composable
private fun SettingsPage(onBack: () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxSize().background(pal.background).clickableNoRipple {}
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.tc_start_back), tint = pal.onBackground)
            }
        }
        // formulir panjang: dibatasi lebarnya supaya baris tidak melebar sepanjang layar lanskap
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { SettingsScreen() }
        }
    }
}

/** Tab Terminal sebelum kontainer berjalan: penjelasan singkat + tombol untuk menyalakannya tanpa membuka desktop. */
@Composable
private fun TerminalGate() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_nav_terminal), stringResource(R.string.tc_start_terminal_hint))
        StartupNotice()
        Group {
            DeckButton(stringResource(R.string.tc_start_container_button), Modifier.fillMaxWidth().padding(14.dp)) {
                Startup.begin(ctx, openDesktop = false)
            }
        }
    }
}
