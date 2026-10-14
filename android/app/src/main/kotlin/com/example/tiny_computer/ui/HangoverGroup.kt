package com.example.tiny_computer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import com.example.tiny_computer.R
import com.example.tiny_computer.core.HangoverSetup
import com.example.tiny_computer.core.WineContents

/**
 * Hangover lewat apt (logika tiny_computer original), terpisah dari Wine/Proton .wcp di [WineGroup]. Tombol pasang
 * hanya aktif bila tidak ada lagi Wine/Proton .wcp yang terpasang; Uninstall selalu bisa dipakai.
 */
@Composable
fun HangoverGroup() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val revision = WineContents.revision // berubah saat Wine .wcp dipasang/dihapus
    val blocked = remember(revision) { HangoverSetup.oldWineInstalled() }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    fun go(block: () -> String?) {
        Thread({
            val err = block()
            error = err
            sent = err == null
        }, "tiny-hangover").start()
    }

    DeckGroup(stringResource(R.string.tc_hangover_title)) {
        DeckNote(stringResource(R.string.tc_ui_hangover_description))
        if (blocked) DeckNote(stringResource(R.string.tc_hangover_blocked))
        error?.let { DeckNote(stringResource(R.string.tc_wine_failed, it), pal.error) }
        if (sent && error == null) DeckNote(stringResource(R.string.tc_hangover_sent))

        val install = stringResource(R.string.tc_btn_install)
        DeckRow("${stringResource(R.string.tc_ui_install_hangover_stable)} (11.0)", null) {
            DeckButton(install, Modifier.widthIn(min = 120.dp), primary = false, enabled = !blocked) {
                go { HangoverSetup.install(ctx, HangoverSetup.Mode.STABLE) }
            }
        }
        DeckRow(stringResource(R.string.tc_ui_install_hangover_latest), null) {
            DeckButton(install, Modifier.widthIn(min = 120.dp), primary = false, enabled = !blocked) {
                go { HangoverSetup.install(ctx, HangoverSetup.Mode.LATEST) }
            }
        }
        DeckActionRow(stringResource(R.string.tc_ui_uninstall_hangover), null, stringResource(R.string.tc_btn_remove)) {
            go { HangoverSetup.uninstall(ctx) }
        }
    }
}
