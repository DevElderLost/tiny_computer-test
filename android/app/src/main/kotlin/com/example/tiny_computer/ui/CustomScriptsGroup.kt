package com.example.tiny_computer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tiny_computer.R
import com.example.tiny_computer.core.CustomScripts

/**
 * Grup "Custom scripts" di Beranda, tepat di bawah kartu Open desktop. Tiap skrip tampil sebagai baris (nama,
 * deskripsi, ikon pensil untuk mengedit, tombol Run); tombol ikon + di bawah daftar membuka dialog untuk menambah
 * skrip baru. Run menjalankan skrip di terminal kontainer ([CustomScripts.run]) lalu membuka tab Terminal.
 */
@Composable
fun CustomScriptsGroup(onShowTerminal: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    // null = dialog tertutup; id kosong = skrip baru; selain itu = edit skrip dengan id tersebut
    var editing by remember { mutableStateOf<CustomScripts.Item?>(null) }
    var adding by remember { mutableStateOf(false) }
    val items = CustomScripts.items

    DeckGroup(stringResource(R.string.tc_cs_group), help = stringResource(R.string.tc_cs_group_help)) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.tc_cs_empty), fontSize = 13.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp),
            )
        }
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (item.description.isNotEmpty()) {
                        Text(
                            item.description, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                ScriptIconButton(Icons.Filled.Edit, stringResource(R.string.tc_cs_edit)) { editing = item }
                DeckButton(stringResource(R.string.tc_cs_run), Modifier.widthIn(min = 72.dp)) { CustomScripts.run(ctx, item, onShowTerminal) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
        // tombol image + di bawah daftar: membuka dialog tambah skrip
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            ScriptIconButton(Icons.Filled.Add, stringResource(R.string.tc_cs_add)) { adding = true }
        }
    }

    if (adding) {
        CustomScriptDialog(
            initial = null, onDismiss = { adding = false },
            onSave = { name, desc, script -> CustomScripts.add(name, desc, script); adding = false },
            onDelete = null,
        )
    }
    editing?.let { item ->
        CustomScriptDialog(
            initial = item, onDismiss = { editing = null },
            onSave = { name, desc, script -> CustomScripts.update(item.id, name, desc, script); editing = null },
            onDelete = { CustomScripts.remove(item.id); editing = null },
        )
    }
}

/** Tombol ikon kotak 48 dp, gaya sama dengan roda gigi pengaturan di kartu Open desktop. */
@Composable
private fun ScriptIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(shape).background(Color.White.copy(alpha = 0.03f)).border(1.dp, pal.line2, shape)
            .clickable(onClick = onClick),
    ) { Icon(icon, description, tint = pal.onBackground, modifier = Modifier.size(20.dp)) }
}

/**
 * Dialog tambah/edit skrip (padanan QuickNodeEditDialogFragment di master): nama (wajib), deskripsi, dan isi skrip.
 * Kartu membulat di tengah layar dan naik di atas papan ketik; ketukan di luar kartu diabaikan supaya ketikan tidak
 * hilang tak sengaja. [onDelete] hanya ada saat mengedit.
 */
@Composable
private fun CustomScriptDialog(
    initial: CustomScripts.Item?, onDismiss: () -> Unit,
    onSave: (name: String, description: String, script: String) -> Unit, onDelete: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var script by remember { mutableStateOf(initial?.script ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).heightIn(max = maxH)
                    .clip(shape).background(pal.background, shape).border(1.dp, pal.line2, shape).padding(16.dp),
            ) {
                Text(
                    stringResource(if (initial == null) R.string.tc_cs_add else R.string.tc_cs_edit),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScriptField(stringResource(R.string.tc_cs_name), name, singleLine = true) { name = it }
                    ScriptField(stringResource(R.string.tc_cs_desc), desc, singleLine = false, minHeight = 60.dp, maxHeight = 110.dp) { desc = it }
                    ScriptField(stringResource(R.string.tc_cs_script), script, singleLine = false, mono = true, minHeight = 120.dp, maxHeight = 240.dp) { script = it }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onDelete != null) {
                        DeckButton(stringResource(R.string.tc_cs_delete), Modifier.weight(1f), primary = false) { confirmDelete = true }
                    }
                    DeckButton(stringResource(R.string.tc_btn_cancel), Modifier.weight(1f), primary = false, onClick = onDismiss)
                    DeckButton(stringResource(R.string.tc_btn_save), Modifier.weight(1f)) {
                        when {
                            name.isBlank() -> Toast.makeText(ctx, R.string.tc_cs_name_empty, Toast.LENGTH_SHORT).show()
                            script.isBlank() -> Toast.makeText(ctx, R.string.tc_cs_script_empty, Toast.LENGTH_SHORT).show()
                            else -> onSave(name, desc, script)
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.tc_cs_delete_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground) },
            text = { Text(stringResource(R.string.tc_cs_delete_body, initial?.name ?: ""), fontSize = 14.sp, color = colors.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.tc_cs_delete), color = pal.signal) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.tc_btn_cancel), color = pal.signal) } },
        )
    }
}

/** Kolom isian berlabel di dalam dialog: kotak membulat, bingkai berwarna sinyal saat fokus. */
@Composable
private fun ScriptField(
    label: String, value: String, singleLine: Boolean, mono: Boolean = false,
    minHeight: Dp = 44.dp, maxHeight: Dp = Dp.Unspecified,
    onChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = TextStyle(
                color = colors.onBackground, fontSize = 13.5.sp, lineHeight = 19.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(pal.signal),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = minHeight, max = maxHeight).clip(shape).background(colors.surfaceVariant)
                        .border(1.dp, if (focused) pal.signal else pal.line2, shape).padding(horizontal = 12.dp, vertical = 11.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) { inner() }
            },
        )
    }
}
