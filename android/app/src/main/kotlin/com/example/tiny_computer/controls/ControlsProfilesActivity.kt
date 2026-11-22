package com.example.tiny_computer.controls

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.ui.DeckActionRow
import com.example.tiny_computer.ui.DeckButton
import com.example.tiny_computer.ui.DeckChoiceRow
import com.example.tiny_computer.ui.DeckGroup
import com.example.tiny_computer.ui.DeckNote
import com.example.tiny_computer.ui.DeckRow
import com.example.tiny_computer.ui.DeckSliderRow
import com.example.tiny_computer.ui.DeckToggleRow
import com.example.tiny_computer.ui.ScreenTitle
import com.example.tiny_computer.ui.TinyTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Manajer controller gaya Winlator: saklar mode profil + tombol mengambang, daftar profil (pilih, edit di editor,
 * ganti nama, duplikat, impor/ekspor `.icp`, hapus), pengaturan profil (kecepatan kursor, warna), dan controller
 * eksternal per profil (port `InputControlsFragment` + `ExternalControllerBindingsActivity` Winlator).
 *
 * Penetapan tombol fisik: saat [capture] terisi, tombol/sumbu controller berikutnya dicatat (dispatchKeyEvent /
 * dispatchGenericMotionEvent di bawah) alih-alih dipakai menavigasi layar.
 */
class ControlsProfilesActivity : ComponentActivity() {
    private var capture: ((Int) -> Unit)? = null
    private var capturedDevice: InputDevice? = null
    private var axisArmed = true
    private var resumeRev by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TinyTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ProfilesRoot(setCapture = { capturedDevice = null; capture = it }, lastDevice = { capturedDevice })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRev++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cb = capture
        if (cb != null) {
            val code = ExternalControllerHandler.captureKey(event)
            if (code != null) { capturedDevice = event.device; capture = null; cb(code); return true }
            // tombol lain (mis. Back) tetap membatalkan lewat UI; hanya tombol controller yang dicatat
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val cb = capture
        if (cb != null) {
            val code = ExternalControllerHandler.captureAxis(event)
            if (code != null && axisArmed) { capturedDevice = event.device; capture = null; cb(code); return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private enum class Page { LIST, DETAIL, CONTROLLER }

    @Composable
    private fun ProfilesRoot(setCapture: (((Int) -> Unit)?) -> Unit, lastDevice: () -> InputDevice?) {
        val ctx = LocalContext.current
        var rev by remember { mutableIntStateOf(0) }
        var page by remember { mutableStateOf(Page.LIST) }
        var openId by remember { mutableIntStateOf(0) }
        var openController by remember { mutableStateOf<String?>(null) }
        var enabled by remember { mutableStateOf(ProfileStore.enabled(ctx)) }
        var fab by remember { mutableStateOf(ProfileStore.floatingButton(ctx)) }
        var ext by remember { mutableStateOf(ProfileStore.externalEnabled(ctx)) }
        var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
        // resumeRev naik tiap layar ini kembali tampil (mis. dari editor): daftar dibaca ulang
        val list = remember(rev, resumeRev) { ProfileStore.profiles(ctx) }
        val activeId = remember(rev, resumeRev) { ProfileStore.active(ctx).id }

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            val p = text?.let { ProfileStore.import(ctx, it) }
            if (p == null) {
                dialog = { AlertDialog(onDismissRequest = { dialog = null }, text = { Text(stringResource(R.string.wl_import_failed)) }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.wl_ok)) } }) }
            }
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

        BackHandler(enabled = page != Page.LIST) {
            setCapture(null)
            page = if (page == Page.CONTROLLER) Page.DETAIL else Page.LIST
        }

        when (page) {
            Page.LIST -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ScreenTitle(stringResource(R.string.wl_title), stringResource(R.string.wl_title_hint))
                DeckGroup(stringResource(R.string.wl_mode)) {
                    DeckToggleRow(stringResource(R.string.wl_enable), stringResource(R.string.wl_enable_hint), enabled) {
                        enabled = it; ProfileStore.setEnabled(ctx, it)
                    }
                    DeckToggleRow(stringResource(R.string.wl_fab), stringResource(R.string.wl_fab_hint), fab) {
                        fab = it; ProfileStore.setFloatingButton(ctx, it)
                    }
                    DeckToggleRow(stringResource(R.string.wl_external), stringResource(R.string.wl_external_hint), ext) {
                        ext = it; ProfileStore.setExternalEnabled(ctx, it)
                    }
                }
                DeckGroup(stringResource(R.string.wl_profiles)) {
                    for (p in list) {
                        DeckRow(
                            p.name + if (p.id == activeId) "  ✓" else "",
                            "${p.elements.size} ${stringResource(R.string.wl_elements)}",
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (p.id != activeId) DeckButton(stringResource(R.string.wl_use), primary = false) {
                                    ProfileStore.setActiveId(ctx, p.id); rev++
                                }
                                DeckButton(stringResource(R.string.wl_open), primary = false) { openId = p.id; page = Page.DETAIL }
                            }
                        }
                    }
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckButton(stringResource(R.string.wl_new)) {
                            dialog = {
                                NameDialog(stringResource(R.string.wl_new), "", { dialog = null }) { n ->
                                    dialog = null
                                    if (n.isNotEmpty()) { ProfileStore.create(ctx, n); rev++ }
                                }
                            }
                        }
                        DeckButton(stringResource(R.string.wl_import), primary = false) {
                            importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
                        }
                    }
                }
            }

            Page.DETAIL -> {
                val p = remember(rev, resumeRev, openId) { ProfileStore.get(ctx, openId) }
                if (p == null) { page = Page.LIST; return }
                var speed by remember(openId) { mutableIntStateOf((p.cursorSpeed * 10).roundToInt().coerceIn(2, 50)) }
                LaunchedEffect(speed) {
                    delay(200)
                    if ((p.cursorSpeed * 10).roundToInt() != speed) { p.cursorSpeed = speed / 10f; p.save(ctx) }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    ScreenTitle(p.name, stringResource(R.string.wl_profile_hint))
                    DeckGroup(stringResource(R.string.wl_profile)) {
                        DeckActionRow(stringResource(R.string.wl_edit_layout), stringResource(R.string.wl_edit_layout_hint), stringResource(R.string.wl_edit)) {
                            startActivity(Intent(ctx, ControlsEditorActivity::class.java).putExtra(ControlsEditorActivity.EXTRA_PROFILE, p.id))
                        }
                        DeckSliderRow(stringResource(R.string.wl_cursor_speed), null, speed, 2..50, 1, { "%.1fx".format(it / 10f) }) { speed = it }
                        DeckChoiceRow(
                            stringResource(R.string.wl_theme), null,
                            THEME_COLORS.map { (c, n) -> c to n }, p.themeColor,
                        ) { p.themeColor = it; p.save(ctx); rev++ }
                    }
                    DeckGroup(stringResource(R.string.wl_ext_controllers), help = stringResource(R.string.wl_ext_help)) {
                        for (c in p.controllers) {
                            DeckRow(c.name, "${c.bindings.size} ${stringResource(R.string.wl_bindings)}" + if (c.isConnected()) "" else " · ${stringResource(R.string.wl_disconnected)}") {
                                DeckButton(stringResource(R.string.wl_open), primary = false) { openController = c.id; page = Page.CONTROLLER }
                            }
                        }
                        val connected = connectedPads().filter { d -> p.controllers.none { it.id == d.descriptor } }
                        if (connected.isEmpty() && p.controllers.isEmpty()) DeckNote(stringResource(R.string.wl_no_controller))
                        for (d in connected) {
                            DeckRow(d.name, stringResource(R.string.wl_connected)) {
                                DeckButton(stringResource(R.string.wl_add), primary = false) {
                                    p.controllers.add(PadController(d.descriptor, d.name))
                                    p.save(ctx); openController = d.descriptor; page = Page.CONTROLLER
                                }
                            }
                        }
                    }
                    DeckGroup(stringResource(R.string.wl_manage)) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeckButton(stringResource(R.string.wl_rename), primary = false) {
                                dialog = { NameDialog(stringResource(R.string.wl_rename), p.name, { dialog = null }) { n ->
                                    dialog = null; if (n.isNotEmpty()) { ProfileStore.rename(ctx, p, n); rev++ }
                                } }
                            }
                            DeckButton(stringResource(R.string.wl_duplicate), primary = false) { ProfileStore.duplicate(ctx, p); page = Page.LIST; rev++ }
                        }
                        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DeckButton(stringResource(R.string.wl_export), primary = false) {
                                exporting = p
                                exporter.launch(p.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".icp")
                            }
                            DeckButton(stringResource(R.string.wl_delete), primary = false) {
                                dialog = { ConfirmDialog(stringResource(R.string.wl_confirm_delete, p.name), { dialog = null }) {
                                    dialog = null; ProfileStore.remove(ctx, p); page = Page.LIST; rev++
                                } }
                            }
                        }
                    }
                    DeckButton(stringResource(R.string.wl_back), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { page = Page.LIST }
                }
            }

            Page.CONTROLLER -> {
                val p = remember(rev, resumeRev, openId) { ProfileStore.get(ctx, openId) }
                val c = p?.controller(openController ?: "")
                if (p == null || c == null) { page = Page.DETAIL; return }
                var waiting by remember { mutableStateOf(false) }
                var lastBind by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    ScreenTitle(c.name, stringResource(R.string.wl_ctrl_hint))
                    DeckGroup(stringResource(R.string.wl_bindings)) {
                        for (b in c.bindings) {
                            var open by remember(b, lastBind) { mutableStateOf(false) }
                            DeckRow(b.label, null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DeckButton(if (b.binding == Binding.NONE) stringResource(R.string.wl_none) else b.binding.label, primary = false) { open = true }
                                    DeckButton(stringResource(R.string.wl_delete), primary = false) {
                                        c.bindings.remove(b); p.save(ctx); lastBind++; rev++
                                    }
                                }
                            }
                            if (open) BindingPickerDialog(b.binding, { open = false }) {
                                b.binding = it; p.save(ctx); open = false; lastBind++; rev++
                            }
                        }
                        if (c.bindings.isEmpty()) DeckNote(stringResource(R.string.wl_no_bindings))
                        DeckButton(
                            if (waiting) stringResource(R.string.wl_press_button) else stringResource(R.string.wl_add_binding),
                            Modifier.padding(14.dp), primary = !waiting,
                        ) {
                            waiting = true
                            setCapture { code ->
                                waiting = false
                                if (c.find(code) == null) { c.bindings.add(PadBinding(code)); p.save(ctx); lastBind++; rev++ }
                            }
                        }
                        if (waiting) DeckNote(stringResource(R.string.wl_capture_hint))
                    }
                    DeckGroup(stringResource(R.string.wl_manage)) {
                        DeckButton(stringResource(R.string.wl_remove_controller), Modifier.padding(14.dp), primary = false) {
                            p.controllers.remove(c); p.save(ctx); setCapture(null); page = Page.DETAIL; rev++
                        }
                    }
                    DeckButton(stringResource(R.string.wl_back), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { setCapture(null); page = Page.DETAIL }
                }
            }
        }
        dialog?.invoke()
    }

    private fun connectedPads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter {
        it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    companion object {
        private val THEME_COLORS = listOf(
            0 to "Default", 0xFF1A9FFF.toInt() to "Blue", 0xFF3DDC84.toInt() to "Green", 0xFFFF5252.toInt() to "Red",
            0xFFFFA726.toInt() to "Amber", 0xFFC77DFF.toInt() to "Violet", 0xFFFF6FB5.toInt() to "Pink",
        )
    }
}
