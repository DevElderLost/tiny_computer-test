package com.example.tiny_computer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.DxvkConfig
import com.example.tiny_computer.core.WineSystem

/**
 * Pengaturan DXVK untuk Wine Hangover: tiap opsi `dxvk.conf` ditampilkan sebagai saklar (benar/salah), menu turun
 * (banyak pilihan, mis. mode presentasi dan batas FPS), atau kolom isian (angka/teks). Nilai dikirim lewat DXVK_CONFIG
 * ([DxvkConfig.envOpt]) dan berlaku di peluncuran Shortcut / kontainer berikutnya.
 */
@Composable
fun DxvkGroup() {
    var rev by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(setOf<String>()) }
    @Suppress("UNUSED_VARIABLE") val tick = rev // dibaca agar seluruh grup digambar ulang setelah perubahan
    val change: () -> Unit = { rev++ }
    val ctx = LocalContext.current

    DeckGroup(stringResource(R.string.tc_dxvk_title), help = stringResource(R.string.tc_dxvk_hint)) {
        DeckSwitchRow(stringResource(R.string.tc_d3d_dxvk), stringResource(R.string.tc_d3d_dxvk_hint), WineSystem.d3dModeIsDxvk()) {
            Cfg.d3dMode = if (it) "dxvk" else "wined3d"
            WineSystem.applyD3dModeAsync(ctx)
            change()
        }
        DeckSwitchRow(stringResource(R.string.tc_dxvk_apply), stringResource(R.string.tc_dxvk_apply_hint), Cfg.dxvkConfigOn) {
            Cfg.dxvkConfigOn = it
            change()
        }
        DeckSwitchRow(stringResource(R.string.tc_dxvk_tune), stringResource(R.string.tc_dxvk_tune_hint), Cfg.dxvkTune) {
            Cfg.dxvkTune = it // dasar nilai berubah: baris di bawah dibaca ulang
            change()
        }

        val pm = DxvkConfig.presentModeId()
        val presentOptions = DxvkConfig.PRESENT.map { it.id to it.label } + (if (pm == "custom") listOf("custom" to "Custom") else emptyList())
        DeckChoiceRow(stringResource(R.string.tc_dxvk_present), stringResource(R.string.tc_dxvk_present_hint), presentOptions, pm) { id ->
            DxvkConfig.setPresentMode(id)
            change()
        }

        val text = DxvkConfig.configText()
        DeckNote(
            if (text == null) stringResource(R.string.tc_dxvk_preview_none) else stringResource(R.string.tc_dxvk_preview, text),
        )
        DeckActionRow(stringResource(R.string.tc_dxvk_reset), stringResource(R.string.tc_dxvk_reset_hint), stringResource(R.string.tc_btn_reset)) {
            DxvkConfig.reset()
            change()
        }
    }

    DxvkConfig.SECTIONS.forEach { sec ->
        val shown = sec.id in open
        val changed = sec.opts.count { DxvkConfig.isChanged(it) }
        DeckGroup("DXVK: ${sec.title}") {
            DeckActionRow(
                if (changed > 0) stringResource(R.string.tc_dxvk_changed, changed) else stringResource(R.string.tc_dxvk_all_default),
                sec.note,
                stringResource(if (shown) R.string.tc_dxvk_hide else R.string.tc_dxvk_show),
            ) { open = if (shown) open - sec.id else open + sec.id }
            if (shown) {
                sec.opts.forEach { OptRow(it, change) }
                if (changed > 0) {
                    DeckActionRow(stringResource(R.string.tc_dxvk_reset_section), null, stringResource(R.string.tc_btn_reset)) {
                        DxvkConfig.resetSection(sec)
                        change()
                    }
                }
            }
        }
    }

    HudGroup(open, { open = it }, change)
}

@Composable
private fun OptRow(o: DxvkConfig.Opt, onChange: () -> Unit) {
    val cur = DxvkConfig.value(o)
    when (o.kind) {
        DxvkConfig.Kind.SWITCH ->
            DeckSwitchRow(o.label, o.hint, cur.equals("True", ignoreCase = true)) {
                DxvkConfig.set(o, if (it) "True" else "False")
                onChange()
            }
        DxvkConfig.Kind.CHOICE -> {
            // nilai lama yang tidak ada di daftar tetap ditampilkan apa adanya
            val options = if (o.choices.any { it.first == cur }) o.choices else o.choices + (cur to cur)
            DeckChoiceRow(o.label, o.hint, options, cur) {
                DxvkConfig.set(o, it)
                onChange()
            }
        }
        // isian: tidak menggambar ulang grup tiap ketukan supaya kolom tidak melompat ke nilai bawaan saat dikosongkan
        DxvkConfig.Kind.NUMBER ->
            DeckTextRow(o.label, cur, o.hint, number = true) { DxvkConfig.set(o, it.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }) }
        DxvkConfig.Kind.TEXT ->
            DeckTextRow(o.label, cur, o.hint) { DxvkConfig.set(o, it) }
    }
}

@Composable
private fun HudGroup(open: Set<String>, setOpen: (Set<String>) -> Unit, onChange: () -> Unit) {
    val shown = "hud" in open
    val items = DxvkConfig.hud()
    DeckGroup(stringResource(R.string.tc_dxvk_hud_title)) {
        DeckActionRow(
            if (items.isEmpty()) stringResource(R.string.tc_dxvk_all_default) else items.joinToString(", "),
            stringResource(R.string.tc_dxvk_hud_hint),
            stringResource(if (shown) R.string.tc_dxvk_hide else R.string.tc_dxvk_show),
        ) { setOpen(if (shown) open - "hud" else open + "hud") }
        if (shown) {
            val full = "full" in items
            DeckSwitchRow(stringResource(R.string.tc_dxvk_hud_full), "full", full) {
                DxvkConfig.setHud(if (it) setOf("full") else emptySet())
                onChange()
            }
            DxvkConfig.HUD.forEach { (id, label) ->
                DeckSwitchRow(label, id, !full && id in items) {
                    DxvkConfig.setHud(if (it) (items - "full") + id else items - id)
                    onChange()
                }
            }
        }
    }
}
