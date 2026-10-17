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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.AppImageInstaller
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Containers
import com.example.tiny_computer.core.FlatpakManager
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.NavRequest
import com.example.tiny_computer.core.Startup
import com.example.tiny_computer.core.TermSessions
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.display.DisplayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val INSTALL_POLL_MS = 500L
private const val INSTALL_DONE_PAUSE_MS = 1200L
private const val INSTALL_WAIT_MS = 30 * 60 * 1000L

private enum class Page(val icon: ImageVector, val label: Int) {
    HOME(Icons.Filled.Computer, R.string.tc_nav_home),
    SHORTCUT(Icons.Filled.Apps, R.string.tc_nav_shortcut),
    TERMINAL(Icons.Filled.Terminal, R.string.tc_nav_terminal),
    CONTROLLER(Icons.Filled.SportsEsports, R.string.tc_nav_controller),
    FLATHUB(Icons.Filled.Store, R.string.tc_nav_flathub),
}

/** Tab yang tampil: Flathub hanya bila saklarnya di Pengaturan menyala. */
private fun visiblePages(): List<Page> = Page.values().filter { it != Page.FLATHUB || FlatpakManager.enabled }

/**
 * Kerangka UI Compose: navigasi (Desktop, Shortcut, Terminal, Controller, Pengaturan). Pemasangan sistem dan start
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
    // Shortcut: jalankan aplikasi langsung (tanpa Open desktop) di proot terpisah; kontainer/terminal tidak perlu hidup
    val openApp = { id: String ->
        ctx.startActivity(Intent(ctx, DisplayActivity::class.java).putExtra(DisplayActivity.EXTRA_APP, id))
    }
    val requestApp: (String) -> Unit = { id ->
        when {
            // kontainer sedang dinyalakan oleh alur lain: ikut menunggu, lalu buka aplikasinya
            Startup.phase == Startup.Phase.RUNNING -> Startup.beginForApp(ctx, id)
            // sistem sudah terpasang: tidak perlu kontainer, cukup siapkan data lalu buka layar aplikasi
            Startup.installed -> Startup.prepareForApp(ctx) { openApp(id) }
            else -> Startup.beginForApp(ctx, id)
        }
    }

    // Desktop TIDAK lagi dibuka otomatis saat aplikasi dimulai: kontainer + desktop hanya dinyalakan
    // dari tombol Open desktop (atau Start container di tab Terminal). Shortcut berjalan tanpa keduanya.
    LaunchedEffect(phase) {
        if (phase == Startup.Phase.DONE) {
            // proses shell keluar normal (kode 0) -> tutup aplikasi, seperti SystemNavigator.pop di versi Dart
            TermSessions.forCurrent(ctx).onExit = { code -> if (code == 0) ctx.findActivity()?.finish() }
            val app = Startup.consumeApp()
            val terminalCommand = Startup.consumeTerminal()
            if (terminalCommand != null) {
                // mis. pasang AppImage dari menu Shortcut: tulis ke terminal dan tampilkan tab Terminal
                Startup.consumeOpen()
                Launcher.runInTerminal(ctx, terminalCommand)
                showSettings = false
                page = Page.TERMINAL.ordinal
            } else if (app != null) { Startup.consumeOpen(); openApp(app) } else if (Startup.consumeOpen()) openDesktop()
        }
    }
    // "Back to terminal" di menu samping layar desktop: langsung buka tab Terminal
    LaunchedEffect(NavRequest.terminal) {
        if (NavRequest.terminal) {
            NavRequest.terminal = false
            showSettings = false
            page = Page.TERMINAL.ordinal
        }
    }
    // pemasangan AppImage selesai: tutup tampilan terminal, kembali ke tab Shortcut, dan matikan terminalnya juga
    // (setelah jeda singkat supaya pesan "Selesai" sempat terbaca). Bila gagal, penanda tidak pernah ada dan
    // terminal tetap terbuka agar galatnya terbaca.
    val installing = AppImageInstaller.watching
    LaunchedEffect(installing) {
        val id = installing ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + INSTALL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { AppImageInstaller.isDone(id) }) {
                delay(INSTALL_DONE_PAUSE_MS)
                if (page == Page.TERMINAL.ordinal) page = Page.SHORTCUT.ordinal
                // shell kontainer ikut dimatikan; bila desktop sedang berjalan di kontainer yang sama, biarkan hidup
                if (!Launcher.guiStarted) Launcher.shutdownTerminal()
                AppImageInstaller.stopWatching(id)
                return@LaunchedEffect
            }
            delay(INSTALL_POLL_MS)
        }
        AppImageInstaller.stopWatching(id)
    }
    // uninstall AppImage selesai: sama seperti pemasangan, terminal ditutup dan tampilan kembali ke tab Shortcut.
    // Sisa pembersihan sisi Android (pengaturan, log, cache ikon) dikerjakan setelah skrip menulis penanda selesai.
    val uninstalling = AppImageInstaller.removing
    LaunchedEffect(uninstalling) {
        val id = uninstalling ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + INSTALL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { AppImageInstaller.isRemoved(id) }) {
                withContext(Dispatchers.IO) { AppImageInstaller.finishRemove(id) }
                delay(INSTALL_DONE_PAUSE_MS)
                if (page == Page.TERMINAL.ordinal) page = Page.SHORTCUT.ordinal
                if (!Launcher.guiStarted) Launcher.shutdownTerminal()
                AppImageInstaller.stopWatchingRemove(id)
                return@LaunchedEffect
            }
            delay(INSTALL_POLL_MS)
        }
        AppImageInstaller.stopWatchingRemove(id)
    }
    // skrip pasang Flatpak + Flathub di terminal selesai: tutup terminal dan buka tab Flathub. Bila gagal, penanda
    // gagal muncul dan terminal dibiarkan terbuka agar galatnya terbaca.
    val flatpakSetup = FlatpakManager.setupWatching
    LaunchedEffect(flatpakSetup) {
        if (!flatpakSetup) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + INSTALL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { FlatpakManager.isSetupDone() }) {
                delay(INSTALL_DONE_PAUSE_MS)
                if (page == Page.TERMINAL.ordinal) page = Page.FLATHUB.ordinal
                if (!Launcher.guiStarted) Launcher.shutdownTerminal()
                FlatpakManager.stopWatchingSetup()
                return@LaunchedEffect
            }
            if (withContext(Dispatchers.IO) { FlatpakManager.setupFailure() } != null) {
                FlatpakManager.stopWatchingSetup()
                return@LaunchedEffect
            }
            delay(INSTALL_POLL_MS)
        }
        FlatpakManager.stopWatchingSetup()
    }
    // jangan keluar dari aplikasi di tengah pemasangan
    BackHandler(enabled = phase == Startup.Phase.RUNNING) {}
    // Pengaturan: dibuka dari ikon roda gigi di samping Open desktop, tampil di area isi dengan rel navigasi tetap terlihat
    BackHandler(enabled = showSettings && phase != Startup.Phase.RUNNING) { showSettings = false }

    Box(Modifier.fillMaxSize()) {
        TinyShell(
            page, { page = it; showSettings = false }, requestDesktop, requestApp, { showSettings = true },
            phase == Startup.Phase.DONE, showSettings, { showSettings = false },
        )
        // tombol mengambang kembali ke desktop: hanya saat desktop sudah jalan, dan tidak saat memasang AppImage
        if (phase == Startup.Phase.DONE && Launcher.guiStarted && AppImageInstaller.watching == null && AppImageInstaller.removing == null && !FlatpakManager.setupWatching) {
            BackToDesktopButton(openDesktop)
        }
        if (phase == Startup.Phase.RUNNING || phase == Startup.Phase.FAILED) StartupOverlay(onClose = { Startup.dismiss() })
    }
}

/** Tombol mengambang di pojok kanan atas: membuka lagi layar desktop yang sedang berjalan. */
@Composable
private fun BackToDesktopButton(onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        FloatingActionButton(
            onClick = onClick, containerColor = pal.signal, contentColor = pal.onSignal,
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.Filled.DesktopWindows, stringResource(R.string.tc_fab_back_desktop)) }
    }
}

@Composable
private fun TinyShell(
    page: Int, onPage: (Int) -> Unit, onOpenDesktop: () -> Unit, onLaunchApp: (String) -> Unit,
    onOpenSettings: () -> Unit, started: Boolean, showSettings: Boolean, onCloseSettings: () -> Unit,
) {
    val pal = LocalPalette.current
    val landscape = isLandscape()
    // papan ketik terbuka: isi naik, dan di mode potret bilah bawah disembunyikan agar terminal tidak tertutup
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val content: @Composable () -> Unit = {
        if (showSettings) SettingsPage()
        else when (Page.values()[page].takeIf { it != Page.FLATHUB || FlatpakManager.enabled } ?: Page.HOME) {
            Page.HOME -> HomeScreen(onOpenDesktop, onOpenSettings)
            Page.SHORTCUT -> ShortcutScreen(onLaunchApp, onShowTerminal = { onPage(Page.TERMINAL.ordinal) })
            Page.TERMINAL -> if (started) TerminalScreen() else TerminalGate()
            Page.CONTROLLER -> ControllerScreen()
            Page.FLATHUB -> FlathubScreen(onLaunchApp)
        }
    }

    if (landscape) {
        // Lanskap seperti DroidDeck: rel navigasi di kiri (bukan bilah bawah yang memakan tinggi
        // layar), isi halaman di sisi kanan dengan inset sistem (tombol navigasi, poni) dihormati.
        Row(Modifier.fillMaxSize().background(pal.background)) {
            NavigationRail(
                containerColor = pal.surface, modifier = Modifier.fillMaxHeight(),
                // layar penuh: bilah sistem disembunyikan, jadi yang dihormati hanya poni/lubang kamera
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
            ) {
                // rata ke atas (gaya rel Material 3); ruang kosong tersisa di bawah, jadi tab baru muncul di bawah item yang ada
                Spacer(Modifier.height(4.dp))
                visiblePages().forEach { p ->
                    NavigationRailItem(
                        selected = !showSettings && page == p.ordinal, onClick = { onPage(p.ordinal) },
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
            modifier = Modifier.imePadding(),
            containerColor = pal.background,
            bottomBar = {
                if (!(imeOpen && !showSettings && page == Page.TERMINAL.ordinal)) NavigationBar(containerColor = pal.surface, tonalElevation = 0.dp) {
                    visiblePages().forEach { p ->
                        NavigationBarItem(
                            selected = !showSettings && page == p.ordinal, onClick = { onPage(p.ordinal) },
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
        DeckGroup(stringResource(R.string.tc_group_session)) {
            DeckInfoRow(stringResource(R.string.tc_home_resolution), "$w × $h")
            DeckInfoRow(stringResource(R.string.tc_home_pad), stringResource(if (Cfg.padIdentity == "deck") R.string.tc_ctrl_deck else R.string.tc_ctrl_xbox))
            DeckInfoRow(stringResource(R.string.tc_home_driver), Cfg.androidDriver.ifEmpty { stringResource(R.string.tc_home_driver_auto) })
            DeckInfoRow(stringResource(R.string.tc_home_linux_driver), Cfg.linuxDriver.ifEmpty { stringResource(R.string.tc_home_driver_auto) })
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

/**
 * Halaman Pengaturan; tampil di area isi, navigasi (rel/bilah bawah) tetap terlihat. Tanpa panah kembali:
 * keluar dengan tombol Back sistem (BackHandler di [TinyApp]) atau dengan memilih salah satu tab navigasi.
 */
@Composable
private fun SettingsPage() {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().background(pal.background)) {
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
