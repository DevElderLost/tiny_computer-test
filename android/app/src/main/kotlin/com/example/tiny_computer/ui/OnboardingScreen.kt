package com.example.tiny_computer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.AppPermissions

/**
 * Layar awal: info pemasangan pertama + tiga izin. Penyimpanan dan mikrofon wajib, baterai opsional;
 * Next di kanan bawah baru muncul bila yang wajib sudah aktif. [resumeTick] berubah setiap activity
 * kembali ke depan, sehingga status izin dihitung ulang setelah pengguna menyetujui di dialog / Pengaturan.
 */
@Composable
fun OnboardingScreen(resumeTick: Int, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val activity = ctx.findActivity()
    val land = isLandscape()
    var local by remember { mutableIntStateOf(0) }
    val refresh = resumeTick + local
    val storage = remember(refresh) { AppPermissions.storageGranted(ctx) }
    val mic = remember(refresh) { AppPermissions.micGranted(ctx) }
    val battery = remember(refresh) { AppPermissions.batteryIgnored(ctx) }
    val ready = storage && mic

    // ditolak dengan "jangan tanya lagi": dialog tidak muncul lagi, arahkan ke Pengaturan aplikasi
    var micBlocked by remember { mutableStateOf(false) }
    var storageBlocked by remember { mutableStateOf(false) }
    val pkgUri = Uri.parse("package:${ctx.packageName}")

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        local++
        if (!ok && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) micBlocked = true
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        local++
        val ok = result.isNotEmpty() && result.values.all { it }
        if (!ok && activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) == false) storageBlocked = true
    }

    val openAppSettings: () -> Unit = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
    }
    val onStorage: () -> Unit = {
        when {
            storageBlocked && Build.VERSION.SDK_INT < 33 -> openAppSettings()
            Build.VERSION.SDK_INT >= 33 -> {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            }
            else -> storageLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    val onMic: () -> Unit = { if (micBlocked) openAppSettings() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    val onBattery: () -> Unit = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    val permissions: @Composable () -> Unit = {
        DeckGroup(stringResource(R.string.tc_ob_group_permissions)) {
            PermissionRow(stringResource(R.string.tc_ob_storage), stringResource(R.string.tc_ob_storage_hint), storage, storageBlocked && Build.VERSION.SDK_INT < 33, onStorage)
            PermissionRow(stringResource(R.string.tc_ob_mic), stringResource(R.string.tc_ob_mic_hint), mic, micBlocked, onMic)
            PermissionRow(stringResource(R.string.tc_ob_battery), stringResource(R.string.tc_ob_battery_hint), battery, false, onBattery)
        }
    }
    val info: @Composable () -> Unit = {
        DeckGroup(stringResource(R.string.tc_ob_group_info)) {
            Text(
                stringResource(R.string.tc_ob_info), fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(pal.background).systemBarsPadding()) {
        // ruang di bawah supaya tombol Next tidak menutupi baris terakhir
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
            ScreenTitle(stringResource(R.string.tc_ob_title), stringResource(R.string.tc_ob_subtitle))
            if (land) {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) { permissions() }
                    Column(Modifier.weight(1f)) { info() }
                }
            } else {
                permissions()
                info()
            }
        }
        AnimatedVisibility(
            visible = ready,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
        ) {
            DeckButton(stringResource(R.string.tc_ob_next), onClick = onNext)
        }
        if (!ready) Text(
            stringResource(R.string.tc_ob_need), fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 120.dp, bottom = 28.dp),
        )
    }
}

@Composable
private fun PermissionRow(label: String, hint: String, granted: Boolean, blocked: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    DeckRow(label, hint) {
        if (granted) StatusChip(stringResource(R.string.tc_ob_granted), pal.good)
        else DeckButton(stringResource(if (blocked) R.string.tc_ob_open_settings else R.string.tc_ob_allow), primary = false, onClick = onClick)
    }
}
