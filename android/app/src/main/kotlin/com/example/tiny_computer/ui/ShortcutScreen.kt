package com.example.tiny_computer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tiny_computer.R
import com.example.tiny_computer.core.AppImageInstaller
import com.example.tiny_computer.core.DesktopApps
import com.example.tiny_computer.core.ExeShortcuts
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.core.ShortcutPrefs
import com.example.tiny_computer.core.Startup
import com.example.tiny_computer.core.TinyEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menu Shortcut: daftar aplikasi Linux yang terpasang (berkas .desktop), dengan ikon yang sama
 * seperti di desktop tetapi kecil dan bersudut membulat. Ketuk = jalankan langsung tanpa Open
 * desktop (logika DroidDeck); tekan lama = pengaturan khusus aplikasi itu ([ShortcutPrefs]), tidak
 * berhubungan dengan pengaturan desktop utama.
 *
 * Tombol Add (+) menambah shortcut program Windows (.exe): berkas dipilih lewat pemilih berkas sistem lalu
 * dijalankan dengan `wine` di sesi proot baru, sama seperti aplikasi lain ([ExeShortcuts]).
 *
 * Daftar baru muncul setelah sistem selesai dipasang DAN desktop pernah dibuka sekali
 * ([DesktopApps.ready]); sebelum itu halaman hanya menampilkan petunjuk.
 */
@Composable
fun ShortcutScreen(onLaunch: (String) -> Unit, onShowTerminal: () -> Unit = {}) {
    val pal = LocalPalette.current
    val ready = remember { DesktopApps.ready() }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DesktopApps.App?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // pilih .exe atau .AppImage: pemilih sistem tidak bisa menyaring ekstensi, jadi pilihan divalidasi
    // di ExeShortcuts / AppImageInstaller (AppImage: cek arsitektur dulu, hanya ARM64 yang dipasang)
    val exePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { AppImageInstaller.displayName(ctx, uri) }
            if (AppImageInstaller.isAppImageName(picked)) {
                Toast.makeText(ctx, R.string.tc_sc_ai_checking, Toast.LENGTH_SHORT).show()
                val r = withContext(Dispatchers.IO) { AppImageInstaller.prepare(ctx, uri) }
                val msg = when (r) {
                    is AppImageInstaller.Outcome.Ready -> {
                        val command = AppImageInstaller.terminalCommand(r.id)
                        if (Startup.phase == Startup.Phase.DONE && Launcher.isStarted) {
                            // kontainer sudah berjalan: skrip pasang langsung dijalankan di terminal
                            Launcher.runInTerminal(ctx, command)
                            onShowTerminal()
                        } else {
                            // kontainer dinyalakan dulu; perintahnya ditulis ke terminal begitu siap
                            Startup.beginForTerminal(ctx, command)
                        }
                        ctx.getString(R.string.tc_sc_ai_installing, r.name)
                    }
                    AppImageInstaller.Outcome.NotAppImage -> ctx.getString(R.string.tc_sc_ai_not_appimage)
                    is AppImageInstaller.Outcome.WrongArch -> ctx.getString(R.string.tc_sc_ai_wrong_arch, r.arch)
                    is AppImageInstaller.Outcome.BadType -> ctx.getString(R.string.tc_sc_ai_bad_type, r.type)
                    AppImageInstaller.Outcome.NoSystem -> ctx.getString(R.string.tc_sc_ai_no_system)
                    is AppImageInstaller.Outcome.NoSpace -> ctx.getString(R.string.tc_sc_ai_no_space, r.needMb)
                    is AppImageInstaller.Outcome.Failed -> ctx.getString(R.string.tc_sc_add_failed, r.message)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                return@launch
            }
            val r = withContext(Dispatchers.IO) { ExeShortcuts.addFromUri(ctx, uri) }
            val msg = when (r) {
                is ExeShortcuts.Outcome.Ok -> { refresh++; ctx.getString(R.string.tc_sc_added, r.name) }
                ExeShortcuts.Outcome.NotExe -> ctx.getString(R.string.tc_sc_add_not_exe)
                ExeShortcuts.Outcome.BadLocation -> ctx.getString(R.string.tc_sc_add_bad_location)
                ExeShortcuts.Outcome.NeedWine -> ctx.getString(R.string.tc_sc_add_need_wine)
                is ExeShortcuts.Outcome.Failed -> ctx.getString(R.string.tc_sc_add_failed, r.message)
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val apps by produceState<List<DesktopApps.App>?>(initialValue = null, ready, refresh) {
        value = if (ready) withContext(Dispatchers.IO) { DesktopApps.scan() } else emptyList()
    }
    val version = ShortcutPrefs.version
    val list = apps
    val settings = remember(list, version) { list.orEmpty().associate { it.id to ShortcutPrefs.get(it.id) } }
    // disembunyikan = disembunyikan pengguna ATAU aplikasi sistem; keduanya bisa ditampilkan lewat "Show hidden"
    fun isHidden(a: DesktopApps.App) = a.system || settings[a.id]?.hidden == true
    val hiddenCount = list.orEmpty().count { isHidden(it) }
    val q = query.trim().lowercase()
    val visible = remember(list, settings, q, showHidden) {
        fun label(a: DesktopApps.App) = settings[a.id]?.name?.ifBlank { null } ?: a.name
        list.orEmpty().filter { a ->
            (showHidden || !isHidden(a)) &&
                (q.isEmpty() || label(a).lowercase().contains(q) || a.comment.lowercase().contains(q) ||
                    a.id.lowercase().contains(q) || a.exec.lowercase().contains(q))
        }.sortedBy { label(it).lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.tc_nav_shortcut),
            if (ready && list != null) stringResource(R.string.tc_sc_count, list.count { !it.system }) else stringResource(R.string.tc_sc_hint),
        )
        when {
            !ready -> DeckGroup(stringResource(R.string.tc_nav_shortcut)) { DeckNote(stringResource(R.string.tc_sc_not_ready)) }
            list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = pal.signal)
            }
            else -> {
                SearchBar(
                    query, { query = it }, onRefresh = { refresh++ },
                    onAdd = { exePicker.launch(arrayOf("*/*")) },
                )
                if (hiddenCount > 0) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        KeyChip(
                            if (showHidden) stringResource(R.string.tc_sc_hide_hidden) else stringResource(R.string.tc_sc_show_hidden, hiddenCount),
                            selected = showHidden,
                        ) { showHidden = !showHidden }
                    }
                }
                if (visible.isEmpty()) {
                    HintText(stringResource(if (list.none { !it.system }) R.string.tc_sc_empty else R.string.tc_sc_no_match))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 88.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible, key = { it.id }) { app ->
                            val s = settings[app.id]
                            AppTile(
                                app = app, label = s?.name?.ifBlank { null } ?: app.name, dimmed = isHidden(app),
                                onClick = { onLaunch(app.id) }, onLongClick = { editing = app },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { app ->
        AppSettingsDialog(app, onRemoved = { editing = null; refresh++ }) { editing = null }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, shape = shape,
            label = { Text(stringResource(R.string.tc_sc_search)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = pal.signal, unfocusedBorderColor = pal.line2,
                focusedLabelColor = pal.signal, unfocusedLabelColor = pal.onSurfaceVariant,
                focusedTextColor = pal.onBackground, unfocusedTextColor = pal.onBackground,
                cursorColor = pal.signal, focusedContainerColor = pal.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(shape).background(Color.White.copy(alpha = 0.03f), shape)
                .border(1.dp, pal.line2, shape).clickableNoRipple(onRefresh),
        ) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.tc_sc_refresh), tint = pal.onBackground, modifier = Modifier.size(20.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(shape).background(Color.White.copy(alpha = 0.03f), shape)
                .border(1.dp, pal.line2, shape).clickableNoRipple(onAdd),
        ) {
            Icon(Icons.Filled.Add, stringResource(R.string.tc_sc_add), tint = pal.onBackground, modifier = Modifier.size(22.dp))
        }
    }
}

/** Satu aplikasi: ikon kecil bersudut membulat dengan nama di bawahnya. Ketuk = jalankan, tekan lama = pengaturan. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: DesktopApps.App, label: String, dimmed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val pal = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, 56.dp, label)
        Text(
            label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (dimmed) pal.onSurfaceVariant else pal.onBackground, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Ikon dari tema ikon Linux (dimuat di thread latar); tanpa ikon: huruf pertama nama di atas permukaan yang sama. */
@Composable
private fun AppIcon(app: DesktopApps.App, size: Dp, label: String) {
    val pal = LocalPalette.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, app.id, app.icon, px) {
        value = withContext(Dispatchers.IO) { DesktopApps.icon(TinyEnv.containerDir(), app, px)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        Modifier.size(size).clip(shape).background(pal.surfaceVariant, shape).border(1.dp, pal.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(size * 0.12f))
        } else {
            Text(label.firstOrNull()?.uppercase() ?: "?", color = pal.signal, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
}

/** Ukuran layar siap pilih untuk sesi aplikasi (sama dengan daftar ukuran layar Winlator). */
private val SCREEN_PRESETS = listOf(
    "640x360", "780x360", "640x480", "800x600", "854x480", "960x544", "1024x768", "1280x576",
    "1280x720", "1280x1024", "1366x768", "1440x900", "1600x720", "1600x900", "1920x1080",
)

private const val SCREEN_CUSTOM = "custom"

/**
 * Pengaturan satu aplikasi (tekan lama). Semua pilihan hanya berlaku untuk aplikasi ini dan
 * disimpan terpisah ([ShortcutPrefs]); pengaturan desktop utama tidak disentuh.
 */
@Composable
private fun AppSettingsDialog(app: DesktopApps.App, onRemoved: () -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    var s by remember(app.id) { mutableStateOf(ShortcutPrefs.get(app.id)) }
    fun update(n: ShortcutPrefs.Settings) { s = n; ShortcutPrefs.save(app.id, n) }

    val def = stringResource(R.string.tc_sc_opt_default)
    val on = stringResource(R.string.tc_sc_opt_on)
    val off = stringResource(R.string.tc_sc_opt_off)
    val shape = RoundedCornerShape(20.dp)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    // ukuran layar: pratetap atau isian sendiri; dinaikkan ke sini agar Reset ikut mengosongkannya
    var customScreen by remember(app.id) { mutableStateOf(s.screen.isNotEmpty() && s.screen !in SCREEN_PRESETS) }
    var cw by remember(app.id) { mutableStateOf(ShortcutPrefs.parseSize(s.screen)?.first?.toString().orEmpty()) }
    var ch by remember(app.id) { mutableStateOf(ShortcutPrefs.parseSize(s.screen)?.second?.toString().orEmpty()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickableNoRipple(onDismiss), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).heightIn(max = maxH)
                    .clip(shape).background(pal.background, shape).border(1.dp, pal.line2, shape)
                    .clickableNoRipple {}
                    .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 52.dp, s.name.ifBlank { app.name })
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(s.name.ifBlank { app.name }, style = MaterialTheme.typography.titleMedium, color = pal.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.exec, style = MaterialTheme.typography.bodySmall, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HintText(stringResource(R.string.tc_sc_settings_hint))

                DeckGroup(stringResource(R.string.tc_sc_group_launch)) {
                    DeckTextRow(stringResource(R.string.tc_sc_name), s.name) { update(s.copy(name = it)) }
                    DeckTextRow(stringResource(R.string.tc_sc_args), s.args) { update(s.copy(args = it)) }
                    DeckTextRow(stringResource(R.string.tc_sc_workdir), s.workDir) { update(s.copy(workDir = it)) }
                    DeckTextRow(stringResource(R.string.tc_sc_env), s.env, multiline = true) { update(s.copy(env = it)) }
                }
                DeckGroup(stringResource(R.string.tc_sc_group_display)) {
                    DeckChoiceRow(
                        stringResource(R.string.tc_sc_gpu), null,
                        listOf(
                            ShortcutPrefs.DEFAULT to def,
                            ShortcutPrefs.GPU_SOFTWARE to stringResource(R.string.tc_sc_gpu_software),
                            ShortcutPrefs.GPU_TURNIP to stringResource(R.string.tc_sc_gpu_turnip),
                        ),
                        s.gpu,
                    ) { update(s.copy(gpu = it)) }
                    DeckChoiceRow(
                        stringResource(R.string.tc_sc_hidpi), null,
                        listOf(ShortcutPrefs.DEFAULT to def, ShortcutPrefs.ON to on, ShortcutPrefs.OFF to off),
                        s.hidpi,
                    ) { update(s.copy(hidpi = it)) }
                    // ukuran layar: bawaan (ikut Settings), pratetap, atau isian sendiri (lebar x tinggi)
                    DeckChoiceRow(
                        stringResource(R.string.tc_sc_screen), stringResource(R.string.tc_sc_screen_note),
                        listOf(ShortcutPrefs.DEFAULT to def) +
                            SCREEN_PRESETS.map { it to it.replace('x', '×') } +
                            (SCREEN_CUSTOM to stringResource(R.string.tc_opt_custom)),
                        when {
                            customScreen -> SCREEN_CUSTOM
                            s.screen.isEmpty() -> ShortcutPrefs.DEFAULT
                            else -> s.screen
                        },
                    ) { pick ->
                        when (pick) {
                            ShortcutPrefs.DEFAULT -> { customScreen = false; update(s.copy(screen = "")) }
                            SCREEN_CUSTOM -> {
                                customScreen = true
                                // mulai dari ukuran yang sedang berlaku supaya isiannya tidak kosong
                                if (ShortcutPrefs.parseSize(s.screen) == null) {
                                    val (dw, dh) = Launcher.displaySize(ctx)
                                    cw = dw.toString(); ch = dh.toString()
                                    update(s.copy(screen = "${dw}x$dh"))
                                }
                            }
                            else -> { customScreen = false; update(s.copy(screen = pick)) }
                        }
                    }
                    if (customScreen) {
                        fun commit() {
                            val w = cw.toIntOrNull()
                            val h = ch.toIntOrNull()
                            if (w != null && h != null) {
                                ShortcutPrefs.parseSize("${w}x$h")?.let { (nw, nh) -> update(s.copy(screen = "${nw}x$nh")) }
                            }
                        }
                        DeckTextRow(stringResource(R.string.tc_ui_width), cw, number = true) { cw = it; commit() }
                        DeckTextRow(stringResource(R.string.tc_ui_height), ch, number = true) { ch = it; commit() }
                    }
                    DeckChoiceRow(
                        stringResource(R.string.tc_pad_overlay), null,
                        listOf(ShortcutPrefs.DEFAULT to def, ShortcutPrefs.ON to on, ShortcutPrefs.OFF to off),
                        s.pad,
                    ) { update(s.copy(pad = it)) }
                    DeckToggleRow(stringResource(R.string.tc_sc_hide), null, s.hidden) { update(s.copy(hidden = it)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DeckButton(stringResource(R.string.tc_sc_reset), Modifier.weight(1f), primary = false) {
                        ShortcutPrefs.reset(app.id)
                        s = ShortcutPrefs.Settings()
                        customScreen = false; cw = ""; ch = ""
                    }
                    DeckButton(stringResource(R.string.tc_close), Modifier.weight(1f), onClick = onDismiss)
                }
                // hanya shortcut .exe / AppImage buatan pengguna yang bisa dihapus; aplikasi Linux tidak disentuh
                if (ExeShortcuts.isExeShortcut(app.id) || AppImageInstaller.isAppImageShortcut(app.id)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                        DeckButton(stringResource(R.string.tc_sc_remove), Modifier.weight(1f), primary = false) {
                            if (AppImageInstaller.isAppImageShortcut(app.id)) AppImageInstaller.remove(app.id) else ExeShortcuts.remove(app.id)
                            onRemoved()
                        }
                    }
                }
            }
        }
    }
}
