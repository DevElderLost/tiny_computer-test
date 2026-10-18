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
import com.example.tiny_computer.core.ReshadeShaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shader ReShade FX (vkBasalt) untuk layar compositor, seperti impor ReShade di Winlator Ludashi: satu tombol Import
 * menerima berkas .fx/.fxh, gambar tekstur (LUT dll.), atau zip paket shader; lalu satu pilihan shader aktif.
 * Shader yang dipilih berlaku di compositor yang sedang hidup atau sesi berikutnya; ganti saat game berjalan
 * lewat menu sesi > Effect.
 */
@Composable
fun ReshadeGroup() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var effects by remember { mutableStateOf(ReshadeShaders.list()) }
    var sel by remember { mutableStateOf(Cfg.reshadeShader) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ReshadeShaders.import(ctx, uris) } }
            busy = false
            effects = ReshadeShaders.list()
            r.onSuccess {
                Toast.makeText(ctx, ctx.getString(R.string.tc_rs_imported, it.effects, it.includes, it.textures), Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(ctx, ctx.getString(R.string.tc_rs_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    DeckGroup(stringResource(R.string.tc_rs_title), help = stringResource(R.string.tc_rs_hint)) {
        DeckActionRow(
            stringResource(R.string.tc_rs_import), null,
            stringResource(if (busy) R.string.tc_drv_importing else R.string.tc_btn_import),
        ) { if (!busy) picker.launch(arrayOf("*/*")) }

        DeckChoiceRow(
            stringResource(R.string.tc_rs_active), null,
            listOf("" to stringResource(R.string.tc_rs_off)) + effects.map { it to ReshadeShaders.label(it) },
            if (sel in effects) sel else "",
        ) { sel = it; ReshadeShaders.select(it) }

        if (sel.isNotEmpty() && sel in effects) {
            DeckActionRow(stringResource(R.string.tc_drv_remove), ReshadeShaders.label(sel), stringResource(R.string.tc_btn_remove)) {
                ReshadeShaders.remove(sel); sel = ""; effects = ReshadeShaders.list()
            }
        }
        DeckNote(if (effects.isEmpty()) stringResource(R.string.tc_rs_none) else stringResource(R.string.tc_rs_count, effects.size))
    }
}
