package com.example.tiny_computer.ui

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
import com.example.tiny_computer.core.DesktopApps
import com.example.tiny_computer.core.ShortcutPrefs
import com.example.tiny_computer.core.TinyEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Menu Shortcut: daftar aplikasi Linux yang terpasang (berkas .desktop), dengan ikon yang sama
 * seperti di desktop tetapi kecil dan bersudut membulat. Ketuk = jalankan langsung tanpa Open
 * desktop (logika DroidDeck); tekan lama = pengaturan khusus aplikasi itu ([ShortcutPrefs]), tidak
 * berhubungan dengan pengaturan desktop utama.
 *
 * Daftar baru muncul setelah sistem selesai dipasang DAN desktop pernah dibuka sekali
 * ([DesktopApps.ready]); sebelum itu halaman hanya menampilkan petunjuk.
 */
@Composable
fun ShortcutScreen(onLaunch: (String) -> Unit) {
    val pal = LocalPalette.current
    val ready = remember { DesktopApps.ready() }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DesktopApps.App?>(null) }

    val apps by produceState<List<DesktopApps.App>?>(initialValue = null, ready, refresh) {
        value = if (ready) withContext(Dispatchers.IO) { DesktopApps.scan() } else emptyList()
    }
    val version = ShortcutPrefs.version
    val list = apps
    val settings = remember(list, version) { list.orEmpty().associate { it.id to ShortcutPrefs.get(it.id) } }
    val hiddenCount = settings.values.count { it.hidden }
    val q = query.trim().lowercase()
    val visible = remember(list, settings, q, showHidden) {
        fun label(a: DesktopApps.App) = settings[a.id]?.name?.ifBlank { null } ?: a.name
        list.orEmpty().filter { a ->
            (showHidden || settings[a.id]?.hidden != true) &&
                (q.isEmpty() || label(a).lowercase().contains(q) || a.comment.lowercase().contains(q) ||
                    a.id.lowercase().contains(q) || a.exec.lowercase().contains(q))
        }.sortedBy { label(it).lowercase() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.tc_nav_shortcut),
            if (ready && list != null) stringResource(R.string.tc_sc_count, list.size) else stringResource(R.string.tc_sc_hint),
        )
        when {
            !ready -> Group { HintText(stringResource(R.string.tc_sc_not_ready)) }
            list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = pal.signal)
            }
            else -> {
                SearchBar(query, { query = it }, onRefresh = { refresh++ })
                if (hiddenCount > 0) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        KeyChip(
                            if (showHidden) stringResource(R.string.tc_sc_hide_hidden) else stringResource(R.string.tc_sc_show_hidden, hiddenCount),
                            selected = showHidden,
                        ) { showHidden = !showHidden }
                    }
                }
                if (visible.isEmpty()) {
                    HintText(stringResource(if (list.isEmpty()) R.string.tc_sc_empty else R.string.tc_sc_no_match))
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
                                app = app, label = s?.name?.ifBlank { null } ?: app.name, dimmed = s?.hidden == true,
                                onClick = { onLaunch(app.id) }, onLongClick = { editing = app },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { app -> AppSettingsDialog(app) { editing = null } }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onRefresh: () -> Unit) {
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
    val bitmap by produceState<ImageBitmap?>(initialValue = null, app.icon, px) {
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

/**
 * Pengaturan satu aplikasi (tekan lama). Semua pilihan hanya berlaku untuk aplikasi ini dan
 * disimpan terpisah ([ShortcutPrefs]); pengaturan desktop utama tidak disentuh.
 */
@Composable
private fun AppSettingsDialog(app: DesktopApps.App, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    var s by remember(app.id) { mutableStateOf(ShortcutPrefs.get(app.id)) }
    fun update(n: ShortcutPrefs.Settings) { s = n; ShortcutPrefs.save(app.id, n) }

    val def = stringResource(R.string.tc_sc_opt_default)
    val on = stringResource(R.string.tc_sc_opt_on)
    val off = stringResource(R.string.tc_sc_opt_off)
    val shape = RoundedCornerShape(20.dp)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

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

                Group(stringResource(R.string.tc_sc_group_launch)) {
                    TextRow(stringResource(R.string.tc_sc_name), s.name) { update(s.copy(name = it)) }
                    TextRow(stringResource(R.string.tc_sc_args), s.args) { update(s.copy(args = it)) }
                    TextRow(stringResource(R.string.tc_sc_workdir), s.workDir) { update(s.copy(workDir = it)) }
                    TextRow(stringResource(R.string.tc_sc_env), s.env, multiline = true) { update(s.copy(env = it)) }
                }
                Group(stringResource(R.string.tc_sc_group_display)) {
                    ChipChoiceRow(
                        stringResource(R.string.tc_sc_gpu),
                        listOf(
                            ShortcutPrefs.DEFAULT to def,
                            ShortcutPrefs.GPU_SOFTWARE to stringResource(R.string.tc_sc_gpu_software),
                            ShortcutPrefs.GPU_TURNIP to stringResource(R.string.tc_sc_gpu_turnip),
                        ),
                        s.gpu,
                    ) { update(s.copy(gpu = it)) }
                    Div()
                    ChipChoiceRow(
                        stringResource(R.string.tc_sc_hidpi),
                        listOf(ShortcutPrefs.DEFAULT to def, ShortcutPrefs.ON to on, ShortcutPrefs.OFF to off),
                        s.hidpi,
                    ) { update(s.copy(hidpi = it)) }
                    Div()
                    ChipChoiceRow(
                        stringResource(R.string.tc_pad_overlay),
                        listOf(ShortcutPrefs.DEFAULT to def, ShortcutPrefs.ON to on, ShortcutPrefs.OFF to off),
                        s.pad,
                    ) { update(s.copy(pad = it)) }
                }
                Group {
                    SwitchRow(stringResource(R.string.tc_sc_hide), s.hidden) { update(s.copy(hidden = it)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DeckButton(stringResource(R.string.tc_sc_reset), Modifier.weight(1f), primary = false) {
                        ShortcutPrefs.reset(app.id)
                        s = ShortcutPrefs.Settings()
                    }
                    DeckButton(stringResource(R.string.tc_close), Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}
