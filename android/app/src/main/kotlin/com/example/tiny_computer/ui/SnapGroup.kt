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
import com.example.tiny_computer.core.SnapManager

/** Pengaturan > Snap: memasang systemd palsu, squashfs palsu, tiny-snapd, dan Ubuntu App Center lewat Terminal. */
@Composable
fun SnapGroup() {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(SnapManager.ready()) }
    DeckGroup(stringResource(R.string.tc_snap_group), help = stringResource(R.string.tc_snap_hint)) {
        DeckInfoRow(stringResource(R.string.tc_snap_status), stringResource(if (ready) R.string.tc_fh_status_ready else R.string.tc_fh_status_missing))
        DeckInfoRow(stringResource(R.string.tc_snap_appcenter), stringResource(if (SnapManager.appCenterInstalled()) R.string.tc_fh_status_ready else R.string.tc_fh_status_missing))
        DeckActionRow(stringResource(R.string.tc_snap_install), null, stringResource(R.string.tc_fh_run_setup)) {
            SnapManager.beginSetup(ctx)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            ready = SnapManager.ready()
        }
    }
}
