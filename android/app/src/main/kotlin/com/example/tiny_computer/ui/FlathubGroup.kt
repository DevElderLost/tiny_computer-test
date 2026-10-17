package com.example.tiny_computer.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.tiny_computer.R
import com.example.tiny_computer.core.FlatpakManager

/**
 * Saklar Flathub Store. Menyalakan saklar memasang Flatpak + remote Flathub untuk Debian trixie (skrip berjalan di
 * Terminal) dan menampilkan tab Flathub; mematikannya hanya menyembunyikan tab, aplikasi terpasang tetap ada.
 */
@Composable
fun FlathubGroup() {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(FlatpakManager.ready()) }
    fun setup() {
        FlatpakManager.beginSetup(ctx)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        ready = FlatpakManager.ready()
    }
    DeckGroup(stringResource(R.string.tc_fh_group), help = stringResource(R.string.tc_fh_toggle_hint)) {
        DeckToggleRow(stringResource(R.string.tc_fh_toggle), null, FlatpakManager.enabled) { on ->
            FlatpakManager.enable(on)
            if (on && !FlatpakManager.ready()) setup()
        }
        DeckInfoRow(stringResource(R.string.tc_fh_status), stringResource(if (ready) R.string.tc_fh_status_ready else R.string.tc_fh_status_missing))
        if (FlatpakManager.enabled) {
            DeckActionRow(stringResource(R.string.tc_fh_reinstall), null, stringResource(R.string.tc_fh_run_setup)) { setup() }
        }
    }
}
