package com.example.tiny_computer.controls

import android.content.Intent
import android.net.Uri
import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tiny_computer.R
import com.example.tiny_computer.ui.ImmersiveWindow
import com.example.tiny_computer.ui.DeckButton
import com.example.tiny_computer.ui.DeckGroup
import com.example.tiny_computer.ui.DeckNote
import com.example.tiny_computer.ui.DeckRow
import com.example.tiny_computer.ui.DeckToggle
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.onSignal
import com.example.tiny_computer.ui.ScreenTitle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Pengaturan controller gaya Winlator, tinggal di Pengaturan > Controller (ControllerScreen) bersama controller
 * DroidDeck. Tampilannya mengikuti layar Input Controls Winlator 4.1 (`InputControlsComposeHost`): kartu Profil
 * (pemilih + tombol bulat tambah/ubah/gandakan/hapus), Overlay Opacity, Import/Export, tombol Controls Editor, dan
 * daftar External Controllers; potret satu kolom, lanskap dua kolom. Editor tata letak layar penuh.
 */

/** Penangkap tombol/sumbu controller fisik untuk penetapan binding; diisi halaman, dipicu MainActivity. */
object ControllerCapture {
    @Volatile var callback: ((Int) -> Unit)? = null

    /** True bila event dipakai (tombol/sumbu controller dicatat). */
    fun onKey(e: android.view.KeyEvent): Boolean {
        val cb = callback ?: return false
        val code = ExternalControllerHandler.captureKey(e) ?: return false
        callback = null
        cb(code)
        return true
    }

    fun onMotion(e: android.view.MotionEvent): Boolean {
        val cb = callback ?: return false
        val code = ExternalControllerHandler.captureAxis(e) ?: return false
        callback = null
        cb(code)
        return true
    }
}

/**
 * Penanda perubahan profil yang dibagi daftar controller (kiri) dan panel penetapan tombol (kanan, atau halaman penuh di
 * potret): keduanya memuat profil dari berkas sendiri-sendiri, jadi setiap simpan menaikkan [version] supaya yang lain
 * memuat ulang dan tidak menimpa perubahan satu sama lain.
 */
internal object PadSync {
    var version by mutableIntStateOf(0)
}

/**
 * Tema Material untuk halaman pengaturan Winlator: warna "primary" Material diganti warna sinyal palet (yang dipakai DroidDeck untuk
 * yang aktif). Tanpa ini saklar, slider, dan tombol utama memakai primary palet, yang putih di tema Paper, sehingga tidak
 * mengikuti warna tema seperti baris DroidDeck lainnya.
 */
@Composable
internal fun WlTheme(content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    val base = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = base.copy(
            primary = pal.signal, onPrimary = pal.onSignal,
            primaryContainer = pal.signal.copy(alpha = 0.25f), onPrimaryContainer = pal.onBackground,
            secondaryContainer = pal.signal.copy(alpha = 0.25f), onSecondaryContainer = pal.onBackground,
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** Kartu gaya Winlator (`SettingsCard` di InputControlsComposeHost). */
@Composable
internal fun WlCard(title: String?, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun WlSwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DeckToggle(checked = checked, onChange = onChange)   // saklar yang sama dengan baris DroidDeck lain (warna sinyal tema)
    }
}

/** Tombol besar "Controls Editor" (`EditorButton` Winlator). */
@Composable
internal fun WlEditorButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(Icons.Outlined.SportsEsports, null)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.wl_controls_editor), style = MaterialTheme.typography.titleMedium)
    }
}

private fun openEditor(ctx: android.content.Context, profileId: Int) {
    ctx.startActivity(Intent(ctx, ControlsEditorActivity::class.java).putExtra(ControlsEditorActivity.EXTRA_PROFILE, profileId))
}

/**
 * Grup di halaman Controller: saklar mode, controller eksternal, lalu (bila mode menyala) seluruh pengaturan profil
 * langsung di bawah saklar: pilih/buat/ganti nama/duplikat/hapus profil, opacity overlay, kecepatan kursor,
 * impor/ekspor profil, tombol Controls Editor (di bawah impor/ekspor), dan daftar controller eksternal. Tombol Manage profiles dan halaman
 * lamanya sudah dihapus. [onOpenController] membuka halaman pemetaan tombol satu controller.
 */
@Composable
fun WinlatorControlsGroup(onOpenController: (String) -> Unit) = WlTheme { WinlatorControlsGroupBody(onOpenController) }

@Composable
private fun WinlatorControlsGroupBody(onOpenController: (String) -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(ProfileStore.enabled(ctx)) }
    var ext by remember { mutableStateOf(ProfileStore.externalEnabled(ctx)) }
    // controller DroidDeck dinyalakan di halaman yang sama: Winlator ikut mati (ControllerMode), saklar ini menyusul
    androidx.compose.runtime.LaunchedEffect(ControllerMode.version) { enabled = ProfileStore.enabled(ctx) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WlCard(stringResource(R.string.wl_title)) {
            WlSwitchRow(stringResource(R.string.wl_enable), stringResource(R.string.wl_enable_hint), enabled) {
                // menyalakan Winlator mematikan controller layar DroidDeck (tidak pernah aktif bersamaan)
                enabled = it; ControllerMode.enableWinlator(ctx, it)
            }
            if (enabled) {
                WlSwitchRow(stringResource(R.string.wl_external), stringResource(R.string.wl_external_hint), ext) {
                    ext = it; ProfileStore.setExternalEnabled(ctx, it)
                }
            }
        }
        if (enabled) ProfileSection(onOpenController)
    }
}

/** Isi pengaturan profil (dulu halaman Manage profiles), dipasang langsung di bawah saklar controller eksternal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSection(onOpenController: (String) -> Unit) {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    var resumeRev by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val sync = PadSync.version
    val list = remember(rev, resumeRev, sync) { ProfileStore.profiles(ctx) }
    val activeId = remember(rev, resumeRev, sync) { ProfileStore.active(ctx).id }

    // kembali dari editor tata letak atau halaman pemetaan: daftar dibaca ulang
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) resumeRev++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val imported = text?.let { ProfileStore.import(ctx, it) }
        if (imported == null) {
            dialog = { AlertDialog(onDismissRequest = { dialog = null }, text = { ImmersiveWindow(); Text(stringResource(R.string.wl_import_failed)) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.wl_ok)) } }) }
        } else ProfileStore.setActiveId(ctx, imported.id)
        rev++
    }
    var exporting by remember { mutableStateOf<Profile?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val prof = exporting
        exporting = null
        if (uri != null && prof != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(prof.toJson().toString().toByteArray()) } }
        }
    }

    val p = remember(rev, resumeRev, activeId, sync) { ProfileStore.get(ctx, activeId) ?: ProfileStore.active(ctx) }
    var opacity by remember { mutableIntStateOf(ProfileStore.overlayOpacity(ctx)) }
    var speed by remember(p.id) { mutableIntStateOf((p.cursorSpeed * 10).roundToInt().coerceIn(2, 50)) }
    LaunchedEffect(speed) {
        delay(200)
        if ((p.cursorSpeed * 10).roundToInt() != speed) { p.cursorSpeed = speed / 10f; p.save(ctx); PadSync.version++ }
    }
    val pads = run {
        val added = p.controllers.map { PadItem(it.id, it.name, it.bindings.size, it.isConnected(), true) }
        val extra = connectedPads().filter { d -> p.controllers.none { it.id == d.descriptor } }
            .map { PadItem(it.descriptor, it.name, 0, true, false) }
        added + extra
    }

    WlCard(stringResource(R.string.wl_profile)) {
        ProfilePicker(list, activeId) { ProfileStore.setActiveId(ctx, it); rev++ }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RoundAction(Icons.Outlined.Add, stringResource(R.string.wl_new)) {
                dialog = {
                    NameDialog(stringResource(R.string.wl_new), "", { dialog = null }) { n ->
                        dialog = null
                        if (n.isNotEmpty()) { val c = ProfileStore.create(ctx, n); ProfileStore.setActiveId(ctx, c.id); rev++ }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.Edit, stringResource(R.string.wl_rename)) {
                dialog = {
                    NameDialog(stringResource(R.string.wl_rename), p.name, { dialog = null }) { n ->
                        dialog = null; if (n.isNotEmpty()) { ProfileStore.rename(ctx, p, n); rev++ }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.ContentCopy, stringResource(R.string.wl_duplicate)) {
                val c = ProfileStore.duplicate(ctx, p); ProfileStore.setActiveId(ctx, c.id); rev++
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(Icons.Outlined.Delete, stringResource(R.string.wl_delete)) {
                dialog = {
                    ConfirmDialog(stringResource(R.string.wl_confirm_delete, p.name), { dialog = null }) {
                        dialog = null; ProfileStore.remove(ctx, p); rev++
                    }
                }
            }
        }
    }
    WlCard(stringResource(R.string.wl_overlay_opacity)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = opacity.toFloat(),
                onValueChange = { opacity = (it / 5f).roundToInt() * 5 },
                onValueChangeFinished = { ProfileStore.setOverlayOpacity(ctx, opacity) },
                modifier = Modifier.weight(1f), valueRange = 0f..100f, steps = 19,
            )
            Spacer(Modifier.width(14.dp))
            Text("$opacity%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    WlCard(stringResource(R.string.wl_cursor_speed)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = speed.toFloat(), onValueChange = { speed = it.roundToInt() },
                modifier = Modifier.weight(1f), valueRange = 2f..50f, steps = 47,
            )
            Spacer(Modifier.width(14.dp))
            Text("%.1fx".format(speed / 10f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wl_import)) }
        OutlinedButton(
            onClick = { exporting = p; exporter.launch(p.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".icp") },
            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wl_export)) }
    }
    // tombol Controls Editor tepat di bawah Import/Export, sebelum daftar controller eksternal
    WlEditorButton { openEditor(ctx, p.id) }
    Text(
        stringResource(R.string.wl_ext_controllers),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (pads.isEmpty()) EmptyControllers()
    for (pad in pads) {
        ControllerCard(
            pad,
            // controller yang baru tersambung belum punya binding sehingga tidak ikut tersimpan di berkas profil;
            // halaman penetapan tombol membuatnya sendiri di memori, jadi di sini cukup membukanya
            onOpen = { onOpenController(pad.id) },
            onRemove = { p.controller(pad.id)?.let { c -> p.controllers.remove(c); p.save(ctx); PadSync.version++; rev++ } },
        )
    }
    dialog?.invoke()
}

private data class PadItem(val id: String, val name: String, val bindings: Int, val connected: Boolean, val added: Boolean)

private fun connectedPads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter {
    it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        IconButton(onClick = onClick) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePicker(profiles: List<Profile>, activeId: Int, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = profiles.firstOrNull { it.id == activeId }?.name ?: profiles.firstOrNull()?.name ?: "-"
    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.82f)) {
            ImmersiveWindow()
            for (p in profiles) {
                DropdownMenuItem(
                    text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onPick(p.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerCard(item: PadItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Gamepad, null, tint = if (item.connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.bindings} ${stringResource(R.string.wl_bindings)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.bindings > 0) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, stringResource(R.string.wl_remove_controller)) }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun EmptyControllers() {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Gamepad, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.wl_no_controller), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Penetapan tombol controller fisik [padId] (dibuka dari daftar controller eksternal di halaman Controller). Di potret
 * berupa halaman penuh; di lanskap [embedded] = true: dipasang di kolom kanan yang kosong di samping daftar, tanpa
 * scroll/ukuran penuhnya sendiri karena sudah berada di dalam kolom yang bisa digulir.
 */
@Composable
fun WinlatorControlsPage(padId: String, onBack: () -> Unit, embedded: Boolean = false) = WlTheme { WinlatorControlsPageBody(padId, onBack, embedded) }

/** Nama controller yang sedang tersambung dengan descriptor [id]. */
private fun padName(id: String): String =
    connectedPads().firstOrNull { it.descriptor == id }?.name ?: "Controller"

@Composable
private fun WinlatorControlsPageBody(padId: String, onBack: () -> Unit, embedded: Boolean) {
    val ctx = LocalContext.current
    // dimuat ulang setiap [PadSync.version] berubah; controller tanpa binding tidak pernah ada di berkas (PadController.toJson
    // mengembalikan null), jadi dibuat kosong di memori. Dulu ini dianggap "tidak ada" lalu halaman langsung menutup diri,
    // tampak seperti layar hanya ter-refresh.
    val ver = PadSync.version
    val p = remember(ver) { ProfileStore.active(ctx) }
    val c = remember(ver, padId) { p.controller(padId) ?: PadController(padId, padName(padId)).also { p.controllers.add(it) } }
    DisposableEffect(Unit) { onDispose { ControllerCapture.callback = null } }
    BackHandler { ControllerCapture.callback = null; onBack() }
    var waiting by remember(padId) { mutableStateOf(false) }
    var lastBind by remember { mutableIntStateOf(0) }
    fun commit() { p.save(ctx); lastBind++; PadSync.version++ }
    val frame = if (embedded) Modifier.fillMaxWidth().padding(bottom = 24.dp)
    else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    Column(frame) {
        ScreenTitle(c.name, stringResource(R.string.wl_ctrl_hint))
        DeckGroup(stringResource(R.string.wl_bindings)) {
            for (b in c.bindings) {
                var open by remember(b, lastBind) { mutableStateOf(false) }
                DeckRow(b.label, null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeckButton(if (b.binding == Binding.NONE) stringResource(R.string.wl_none) else b.binding.label, primary = false) { open = true }
                        DeckButton(stringResource(R.string.wl_delete), primary = false) {
                            c.bindings.remove(b); commit()
                        }
                    }
                }
                if (open) BindingPickerDialog(b.binding, { open = false }) {
                    b.binding = it; open = false; commit()
                }
            }
            if (c.bindings.isEmpty()) DeckNote(stringResource(R.string.wl_no_bindings))
            DeckButton(
                if (waiting) stringResource(R.string.wl_press_button) else stringResource(R.string.wl_add_binding),
                Modifier.padding(14.dp), primary = !waiting,
            ) {
                waiting = true
                ControllerCapture.callback = { code ->
                    waiting = false
                    if (c.find(code) == null) { c.bindings.add(PadBinding(code)); commit() }
                }
            }
            if (waiting) DeckNote(stringResource(R.string.wl_capture_hint))
        }
        DeckGroup(stringResource(R.string.wl_manage)) {
            DeckButton(stringResource(R.string.wl_remove_controller), Modifier.padding(14.dp), primary = false) {
                p.controllers.remove(c); p.save(ctx); PadSync.version++; ControllerCapture.callback = null; onBack()
            }
        }
        DeckButton(stringResource(R.string.wl_back), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ControllerCapture.callback = null; onBack() }
    }
}
