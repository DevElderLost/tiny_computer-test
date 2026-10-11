package com.example.tiny_computer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.VulkanDrivers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Impor + pilih driver seperti halaman Driver DroidDeck: satu tombol "Impor" menerima zip/tzst
 * (Linux, Android, atau bundel keduanya), lalu dua daftar pilihan:
 * driver Vulkan di dalam kontainer (Linux/glibc) dan driver tampilan compositor (Android/Turnip).
 * Berlaku pada peluncuran berikutnya.
 */
@Composable
fun DriverGroup() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var linux by remember { mutableStateOf(VulkanDrivers.installedLinux(ctx)) }
    var android by remember { mutableStateOf(VulkanDrivers.installedAndroid(ctx)) }
    var linuxSel by remember { mutableStateOf(Cfg.linuxDriver) }
    var androidSel by remember { mutableStateOf(Cfg.androidDriver) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { VulkanDrivers.importDriver(ctx, uri) } }
            busy = false
            r.onSuccess { res ->
                res.linuxId?.let { Cfg.linuxDriver = it; linuxSel = it }
                res.androidId?.let { Cfg.androidDriver = it; androidSel = it }
                linux = VulkanDrivers.installedLinux(ctx)
                android = VulkanDrivers.installedAndroid(ctx)
                Toast.makeText(ctx, ctx.getString(R.string.tc_drv_imported, listOfNotNull(res.linuxId, res.androidId).joinToString(" + ")), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(ctx, ctx.getString(R.string.tc_drv_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.tc_drv_title)) {
        HintText(stringResource(R.string.tc_drv_hint))
        ActionRow(stringResource(if (busy) R.string.tc_drv_importing else R.string.tc_drv_import)) {
            if (!busy) picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zstd", "*/*"))
        }
        Div()
        ChipChoiceRow(
            stringResource(R.string.tc_drv_linux),
            listOf("" to stringResource(R.string.tc_drv_auto)) + linux.map { it to VulkanDrivers.label(ctx, it, true) },
            if (linuxSel in linux) linuxSel else "",
        ) { linuxSel = it; Cfg.linuxDriver = it }
        if (linuxSel.isNotEmpty() && linuxSel in linux) {
            ActionRow(stringResource(R.string.tc_drv_remove)) {
                VulkanDrivers.removeLinux(ctx, linuxSel); linuxSel = ""; linux = VulkanDrivers.installedLinux(ctx)
            }
        }
        Div()
        ChipChoiceRow(
            stringResource(R.string.tc_drv_android),
            listOf("" to stringResource(R.string.tc_drv_auto)) + android.map { it to VulkanDrivers.label(ctx, it, false) },
            if (androidSel in android) androidSel else "",
        ) { androidSel = it; Cfg.androidDriver = it }
        // driver bawaan APK (turnip25.1.0 / turnip-sdk36) tidak dihapus; hanya hasil impor
        if (androidSel.isNotEmpty() && androidSel in android && androidSel !in BUNDLED) {
            ActionRow(stringResource(R.string.tc_drv_remove)) {
                VulkanDrivers.removeAndroid(ctx, androidSel); androidSel = ""; android = VulkanDrivers.installedAndroid(ctx)
            }
        }
    }
}

private val BUNDLED = setOf("turnip25.1.0", "turnip-sdk36")
