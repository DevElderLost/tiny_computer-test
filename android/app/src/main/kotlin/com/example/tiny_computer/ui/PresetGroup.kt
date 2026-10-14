package com.example.tiny_computer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tiny_computer.R
import com.example.tiny_computer.core.EmuPresets

/**
 * Preset emulator x86: Box64 (`BOX64_*`) dan FEXCore (`FEX_*`). Tiap kartu menampilkan preset terpilih, daftar
 * preset (bawaan hanya-baca + milik pengguna) dan menu ⋮: Create new, Clone, Edit, Delete, Import, Export.
 * Logika penyimpanan ada di [EmuPresets]; preset terpilih ditulis ke env.sh Wine.
 */
@Composable
fun PresetGroup() {
    val pal = LocalPalette.current
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    DeckGroup(stringResource(R.string.tc_preset_title)) {
        DeckNote(stringResource(R.string.tc_preset_hint))
        PresetCard(EmuPresets.Kind.BOX64, Icons.Outlined.Memory) { result = it }
        PresetCard(EmuPresets.Kind.FEXCORE, Icons.Outlined.Speed) { result = it }
        result?.let { (text, isError) -> DeckNote(text, if (isError) pal.error else pal.good) }
    }
}

/** Keadaan dialog editor: [id] null = preset baru (atau bawaan yang hanya dilihat). */
private class EditorState(val id: String?, val name: String, val env: Map<String, String>, val readOnly: Boolean)

@Composable
private fun PresetCard(kind: EmuPresets.Kind, icon: ImageVector, onResult: (Pair<String, Boolean>) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val rev = EmuPresets.revision // dibaca agar kartu dibaca ulang saat daftar/pilihan berubah
    val presets = remember(rev) { EmuPresets.list(kind) }
    val selectedId = remember(rev) { EmuPresets.selectedId(kind) }
    val selected = presets.firstOrNull { it.id == selectedId } ?: presets.first()

    var expanded by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<EmuPresets.Preset?>(null) }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "presetCaret")

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread({
                val r = EmuPresets.importFrom(ctx, uri, kind)
                val err = r.error
                onResult(if (err != null) Pair(err, true) else Pair(ctx.getString(R.string.tc_preset_imported, r.count), false))
            }, "tiny-preset-import").start()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exportTarget
        if (uri != null && p != null) {
            val err = runCatching { EmuPresets.exportTo(ctx, uri, kind, p) }.exceptionOrNull()
            onResult(if (err == null) Pair(ctx.getString(R.string.tc_preset_exported, p.name), false) else Pair(err.message ?: "Export failed.", true))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = pal.onSurfaceVariant) }
            Column(Modifier.weight(1f).padding(start = 12.dp).clickableNoRipple { expanded = !expanded }) {
                Text(stringResource(R.string.tc_preset_card_title, kind.title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(selected.name, fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(rot))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.onBackground) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    PresetMenuItem(stringResource(R.string.tc_preset_create), Icons.Outlined.Add) {
                        menu = false
                        val base = EmuPresets.find(kind, EmuPresets.DEFAULT_ID)?.env ?: emptyMap()
                        editor = EditorState(null, "", base, false)
                    }
                    PresetMenuItem(stringResource(R.string.tc_preset_clone), Icons.Outlined.ContentCopy) {
                        menu = false
                        EmuPresets.clone(kind, selected)?.let { c ->
                            EmuPresets.select(kind, c.id)
                            editor = EditorState(c.id, c.name, c.env, false)
                        }
                    }
                    PresetMenuItem(stringResource(R.string.tc_preset_edit), Icons.Outlined.Edit) {
                        menu = false
                        editor = EditorState(if (selected.bundled) null else selected.id, selected.name, selected.env, selected.bundled)
                    }
                    PresetMenuItem(stringResource(R.string.tc_preset_delete), Icons.Outlined.Delete, enabled = !selected.bundled) {
                        menu = false
                        confirmDelete = true
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                    PresetMenuItem(stringResource(R.string.tc_preset_import), Icons.Outlined.FileDownload) {
                        menu = false
                        importer.launch(arrayOf("*/*"))
                    }
                    PresetMenuItem(stringResource(R.string.tc_preset_export), Icons.Outlined.FileUpload) {
                        menu = false
                        exportTarget = selected
                        exporter.launch("${kind.dir}-${selected.id}.json")
                    }
                }
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(pal.line))
            presets.forEach { p ->
                val on = p.id == selectedId
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { EmuPresets.select(kind, p.id) }
                        .padding(start = 70.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        p.name, Modifier.weight(1f), fontSize = 16.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) colors.onBackground else colors.onSurfaceVariant,
                    )
                    if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onBackground)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }

    editor?.let { st ->
        PresetEditorDialog(kind, st, onDismiss = { editor = null }) { name, env ->
            val saved = EmuPresets.save(kind, st.id, name, env)
            if (saved != null && st.id == null) EmuPresets.select(kind, saved.id)
            editor = null
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = pal.surface,
            title = { Text(stringResource(R.string.tc_preset_delete_title), color = pal.onBackground) },
            text = { Text(stringResource(R.string.tc_preset_delete_msg, selected.name), color = pal.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; EmuPresets.delete(kind, selected.id) }) {
                    Text(stringResource(R.string.tc_preset_delete), color = pal.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.tc_preset_cancel), color = pal.signal) }
            },
        )
    }
}

@Composable
private fun PresetMenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val pal = LocalPalette.current
    DropdownMenuItem(
        text = { Text(text, fontSize = 15.sp, color = if (enabled) pal.onBackground else pal.onSurfaceVariant.copy(alpha = 0.5f)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = pal.onSurfaceVariant) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun presetFieldColors(): TextFieldColors {
    val pal = LocalPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = pal.signal, unfocusedBorderColor = pal.line2, disabledBorderColor = pal.line2,
        focusedLabelColor = pal.signal, unfocusedLabelColor = pal.onSurfaceVariant, disabledLabelColor = pal.onSurfaceVariant,
        focusedTextColor = pal.onBackground, unfocusedTextColor = pal.onBackground, disabledTextColor = pal.onSurfaceVariant,
        cursorColor = pal.signal,
    )
}

/**
 * Editor preset: nama + daftar variabel lingkungan (saklar untuk 0/1, pilihan untuk nilai bertingkat, isian angka).
 * Preset bawaan dibuka baca-saja (Save mati). Variabel di luar katalog (hasil impor) tampil sebagai baris teks yang bisa dihapus.
 */
@Composable
private fun PresetEditorDialog(kind: EmuPresets.Kind, st: EditorState, onDismiss: () -> Unit, onSave: (String, Map<String, String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val defs = remember(kind) { EmuPresets.defs(kind) }
    val ro = st.readOnly

    var name by remember { mutableStateOf(st.name) }
    val values = remember {
        mutableStateMapOf<String, String>().also { m ->
            defs.forEach { d -> m[d.key] = st.env[d.key] ?: d.default }
            st.env.forEach { (k, v) -> if (defs.none { it.key == k }) m[k] = v }
        }
    }
    val extraKeys = remember { mutableStateListOf<String>().also { l -> st.env.keys.filter { k -> defs.none { it.key == k } }.forEach { l.add(it) } } }
    var newKey by remember { mutableStateOf("") }
    var newVal by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf(false) }

    fun addVar() {
        val k = newKey.trim().uppercase()
        if (!EmuPresets.validKey(kind, k) || values.containsKey(k)) { keyError = true; return }
        values[k] = newVal.trim()
        extraKeys.add(k)
        newKey = ""; newVal = ""; keyError = false
    }

    val shape = RoundedCornerShape(24.dp)
    val boxShape = RoundedCornerShape(18.dp)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).heightIn(max = maxH)
                .clip(shape).background(pal.surface, shape).border(1.dp, pal.line2, shape).padding(20.dp),
        ) {
            Text(stringResource(R.string.tc_preset_card_title, kind.title), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            Text(
                stringResource(if (ro) R.string.tc_preset_bundled_ro else R.string.tc_preset_custom),
                fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60) }, enabled = !ro, singleLine = true,
                label = { Text(stringResource(R.string.tc_preset_name)) }, shape = RoundedCornerShape(12.dp),
                colors = presetFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.tc_preset_env), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().clip(boxShape).border(1.dp, pal.line2, boxShape)
                    .verticalScroll(rememberScrollState()),
            ) {
                defs.forEach { d -> VarRow(d, values[d.key] ?: d.default, ro) { values[d.key] = it } }
                extraKeys.toList().forEach { k ->
                    ExtraRow(k, values[k].orEmpty(), ro, onChange = { values[k] = it }, onRemove = { extraKeys.remove(k); values.remove(k) })
                }
                if (!ro) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.tc_preset_add_var), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniField(newKey, true, number = false, hint = kind.prefix, modifier = Modifier.weight(1f)) { newKey = it.uppercase().take(64); keyError = false }
                            MiniField(newVal, true, number = false, hint = "1", modifier = Modifier.width(76.dp)) { newVal = it.take(40) }
                            TextButton(onClick = { addVar() }) { Text(stringResource(R.string.tc_preset_add), color = pal.signal) }
                        }
                        if (keyError) {
                            Text(stringResource(R.string.tc_preset_bad_key, kind.prefix), fontSize = 12.5.sp, color = pal.error, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tc_preset_cancel), color = pal.signal, fontSize = 16.sp) }
                Button(
                    enabled = !ro && name.isNotBlank(),
                    onClick = {
                        val env = LinkedHashMap<String, String>()
                        defs.forEach { d -> env[d.key] = values[d.key] ?: d.default }
                        extraKeys.forEach { k -> env[k] = values[k].orEmpty() }
                        onSave(name.trim(), env)
                    },
                    shape = RoundedCornerShape(99.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = pal.signal, contentColor = pal.onSignal,
                        disabledContainerColor = pal.surfaceVariant, disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier.widthIn(min = 150.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.tc_preset_save), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun VarRow(d: EmuPresets.VarDef, value: String, ro: Boolean, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.key, Modifier.weight(1f).padding(end = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            IconButton(onClick = { help = !help }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = stringResource(R.string.tc_preset_help), tint = if (help) pal.signal else colors.onBackground)
            }
            when (d.type) {
                EmuPresets.VarType.BOOL -> DeckToggle(value == "1", enabled = !ro) { onChange(if (it) "1" else "0") }
                EmuPresets.VarType.ENUM -> MiniChoice(value, d.options, !ro, onChange)
                EmuPresets.VarType.INT -> MiniField(value, !ro, number = true, modifier = Modifier.width(96.dp)) { onChange(it.filter { c -> c.isDigit() }.take(8)) }
            }
        }
        if (help) {
            Text(d.help, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

@Composable
private fun ExtraRow(key: String, value: String, ro: Boolean, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(key, Modifier.weight(1f).padding(end = 8.dp), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onBackground)
            MiniField(value, !ro, number = false, modifier = Modifier.width(96.dp)) { onChange(it.take(40)) }
            if (!ro) {
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** Kotak nilai kecil dengan menu turun untuk variabel bertingkat (mis. 0..3). */
@Composable
private fun MiniChoice(value: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier.widthIn(min = 72.dp).heightIn(min = 40.dp).alpha(if (enabled) 1f else 0.5f)
                .clip(shape).background(colors.surfaceVariant).border(1.dp, if (open) pal.signal else pal.line2, shape)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            Spacer(Modifier.width(10.dp))
            Text("▾", fontSize = 10.sp, color = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, fontSize = 14.sp, color = if (o == value) pal.signal else pal.onBackground) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

/** Isian ringkas satu baris (angka atau teks) bergaya DeckField. */
@Composable
private fun MiniField(
    value: String, enabled: Boolean, number: Boolean, modifier: Modifier = Modifier, hint: String = "", onChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        textStyle = TextStyle(color = colors.onBackground, fontSize = 14.sp),
        cursorBrush = SolidColor(pal.signal),
        modifier = modifier.alpha(if (enabled) 1f else 0.5f).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(shape).background(colors.surfaceVariant)
                    .border(1.dp, if (focused) pal.signal else pal.line2, shape).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 14.sp, color = colors.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1)
                inner()
            }
        },
    )
}
