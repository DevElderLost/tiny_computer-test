package com.example.tiny_computer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.WineContents
import com.example.tiny_computer.core.WineContents.Type
import com.example.tiny_computer.core.WineSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pengaturan Wine/Proton arm64ec + FEXCore + DXVK/VKD3D berformat .wcp Winlator. Pemasangan dan pilihan ada di
 * sini; [WineSetup] menyiapkan pustaka, prefix, dan shim `wine` supaya program Windows bisa dijalankan.
 */
@Composable
fun WineGroup() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val revision = WineContents.revision // dibaca agar daftar dibaca ulang setiap ada perubahan
    var tick by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf(false) }

    val wines = remember(revision, tick) { WineContents.installed(Type.WINE) + WineContents.installed(Type.PROTON) }
    val fexes = remember(revision, tick) { WineContents.installed(Type.FEXCORE) }
    val dxvks = remember(revision, tick) { WineContents.installed(Type.DXVK) }
    val vkd3ds = remember(revision, tick) { WineContents.installed(Type.VKD3D) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) WineContents.importUri(ctx, uri)
    }

    DeckGroup(stringResource(R.string.tc_wine_title)) {
        DeckNote(stringResource(R.string.tc_wine_hint))

        val task = WineContents.taskText
        if (task.isNotEmpty()) {
            val pct = WineContents.taskPercent
            DeckNote(if (pct >= 0) stringResource(R.string.tc_wine_task, task, pct) else stringResource(R.string.tc_wine_task_unknown, task))
        }
        WineContents.taskError?.let { DeckNote(stringResource(R.string.tc_wine_failed, it), pal.error) }
        DeckActionRow(stringResource(R.string.tc_wine_import), null, stringResource(R.string.tc_btn_import)) {
            if (!WineContents.isBusy) picker.launch(arrayOf("*/*"))
        }
        DeckActionRow(stringResource(R.string.tc_wine_download), null, stringResource(R.string.tc_btn_download)) {
            if (!WineContents.isBusy) catalog = true
        }

        if (wines.isEmpty()) DeckNote(stringResource(R.string.tc_wine_no_wine))
        else Picker(R.string.tc_wine_pick_wine, Type.WINE, wines, false) { tick++ }
        Picker(R.string.tc_wine_pick_fex, Type.FEXCORE, fexes, true) { tick++ }
        Picker(R.string.tc_wine_pick_dxvk, Type.DXVK, dxvks, true) { tick++ }
        Picker(R.string.tc_wine_pick_vkd3d, Type.VKD3D, vkd3ds, true) { tick++ }

        val activeWine = WineContents.activeWine()
        if (activeWine != null) {
            val wayland = WineContents.supportsWayland(activeWine)
            var disp by remember(revision, tick) { mutableStateOf(if (Cfg.wineDisplay == "x11" || !wayland) "x11" else "wayland") }
            DeckChoiceRow(
                stringResource(R.string.tc_wine_display),
                stringResource(if (wayland) R.string.tc_wine_display_hint else R.string.tc_wine_display_unsupported),
                listOf("wayland" to stringResource(R.string.tc_wine_display_wayland), "x11" to stringResource(R.string.tc_wine_display_x11)),
                disp,
            ) { id ->
                disp = id
                Cfg.wineDisplay = id
                Thread { runCatching { WineContents.onSelectionChanged() }; WineSetup.applyDisplayAsync(ctx) }.start()
            }
        }

        val running = WineSetup.phase == WineSetup.Phase.RUNNING
        DeckActionRow(stringResource(R.string.tc_wine_setup), stringResource(R.string.tc_wine_setup_hint), stringResource(R.string.tc_btn_run)) {
            if (!running && wines.isNotEmpty()) WineSetup.start(ctx)
        }
        if (running) {
            DeckNote(
                stringResource(
                    when (WineSetup.stage) {
                        "deps" -> R.string.tc_wine_stage_deps
                        "prefix" -> R.string.tc_wine_stage_prefix
                        "applying" -> R.string.tc_wine_stage_applying
                        else -> R.string.tc_wine_stage_writing
                    },
                ),
            )
        }
        when (WineSetup.phase) {
            WineSetup.Phase.DONE -> DeckNote(stringResource(R.string.tc_wine_ready))
            WineSetup.Phase.FAILED -> DeckNote(stringResource(R.string.tc_wine_failed, WineSetup.message.orEmpty()), pal.error)
            else -> {}
        }
    }

    if (catalog) CatalogDialog(onDismiss = { catalog = false }) { r ->
        catalog = false
        WineContents.downloadAndInstall(ctx, r)
    }
}

@Composable
private fun Picker(title: Int, type: Type, list: List<WineContents.Profile>, noneAllowed: Boolean, onChanged: () -> Unit) {
    if (list.isEmpty()) return
    val sel = WineContents.active(type)?.id ?: ""
    val none = stringResource(R.string.tc_wine_none)
    val options = (if (noneAllowed) listOf("" to none) else emptyList()) + list.map { it.id to it.title }
    DeckChoiceRow(stringResource(title), null, options, if (sel.isEmpty() && !noneAllowed) options.first().first else sel) { id ->
        WineContents.select(type, id)
        onChanged()
        Thread { runCatching { WineContents.onSelectionChanged() } }.start()
    }
    val chosen = list.firstOrNull { it.id == sel }
    if (chosen != null) {
        DeckActionRow(stringResource(R.string.tc_wine_remove), chosen.title, stringResource(R.string.tc_btn_remove)) {
            WineContents.remove(chosen)
            onChanged()
        }
    }
}

@Composable
private fun CatalogDialog(onDismiss: () -> Unit, onPick: (WineContents.Remote) -> Unit) {
    val pal = LocalPalette.current
    var items by remember { mutableStateOf<List<WineContents.Remote>?>(null) }
    var filter by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { WineContents.catalog() } }

    val filters = listOf(
        stringResource(R.string.tc_wine_filter_wine) to setOf(Type.WINE, Type.PROTON),
        "FEXCore" to setOf(Type.FEXCORE),
        "DXVK" to setOf(Type.DXVK),
        "VKD3D" to setOf(Type.VKD3D),
    )
    val shape = RoundedCornerShape(18.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(pal.surface, shape).border(1.dp, pal.line, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.tc_wine_catalog_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground)
            DeckChoiceRow(stringResource(R.string.tc_wine_filter_label), null, filters.mapIndexed { i, f -> i to f.first }, filter) { filter = it }
            val list = items
            if (list == null) {
                Text(stringResource(R.string.tc_wine_catalog_loading), fontSize = 13.sp, color = pal.onSurfaceVariant)
            } else {
                val types = filters[filter].second
                // FEXCore & DXVK terbaru ada di akhir katalog
                val shown = list.filter { it.type in types }.let { if (Type.WINE in types) it else it.reversed() }
                val have = remember(WineContents.revision) {
                    types.flatMap { WineContents.installed(it) }.map { it.type to (it.name to it.code) }.toSet()
                }
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.tc_wine_catalog_empty), fontSize = 13.sp, color = pal.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown) { r ->
                            val installed = (r.type to (r.name to r.code)) in have
                            Row(
                                Modifier.fillMaxWidth().clickableNoRipple { onPick(r) }.padding(vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(r.name, fontSize = 14.sp, color = pal.onBackground, modifier = Modifier.weight(1f))
                                if (installed) Text(stringResource(R.string.tc_wine_installed_badge), fontSize = 12.sp, color = pal.good)
                            }
                        }
                    }
                }
            }
            DeckButton(stringResource(R.string.tc_wine_close), Modifier.fillMaxWidth(), primary = false, onClick = onDismiss)
        }
    }
}
