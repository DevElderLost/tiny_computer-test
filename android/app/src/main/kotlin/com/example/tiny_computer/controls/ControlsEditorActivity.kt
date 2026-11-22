package com.example.tiny_computer.controls

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.ui.DeckRow
import com.example.tiny_computer.ui.DeckSliderRow
import com.example.tiny_computer.ui.DeckToggleRow
import com.example.tiny_computer.ui.LocalPalette
import com.example.tiny_computer.ui.TinyTheme
import com.example.tiny_computer.ui.onSignal
import kotlin.math.roundToInt

/**
 * Editor controller gaya Winlator (port `ControlsEditorComposeHost`): layar penuh dengan grid; ketuk elemen untuk memilih,
 * seret untuk memindahkan, ubah jenis/bentuk/binding/ukuran/opasitas/warna di panel kanan, tambah atau hapus elemen,
 * lalu Simpan. Profil yang diedit dikirim lewat [EXTRA_PROFILE]; tanpa itu profil aktif.
 */
class ControlsEditorActivity : ComponentActivity() {
    private lateinit var view: ProfileControlsView
    private lateinit var prof: Profile
    private var selected by mutableStateOf<Element?>(null)
    private var rev by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        prof = ProfileStore.get(this, intent.getIntExtra(EXTRA_PROFILE, ProfileStore.activeId(this))) ?: ProfileStore.active(this)
        val p = prof
        view = ProfileControlsView(this, null, editing = true).apply {
            profile = p
            onSelect = { selected = it; rev++ }
        }
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val bar = ComposeView(this).apply { setContent { TinyTheme { TopBar() } } }
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 8 })

        val density = resources.displayMetrics.density
        val panel = ComposeView(this).apply { setContent { TinyTheme { SidePanel() } } }
        root.addView(panel, FrameLayout.LayoutParams((300 * density).toInt(), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = 8 })
        setContentView(root)
    }

    private fun changed() {
        rev++
        view.invalidate()
    }

    @Composable
    private fun TopBar() {
        val r = rev // dibaca supaya bilah ikut diperbarui saat pilihan berubah
        var menu by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Text(prof.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            Box {
                PillButton(stringResource(R.string.wl_add), false) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((t, label) in TYPE_LABELS) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; addElement(t) })
                    }
                }
            }
            if (selected != null && r >= 0) {
                PillButton(stringResource(R.string.wl_duplicate), false) { duplicateSelected() }
                PillButton(stringResource(R.string.wl_delete), false) { deleteSelected() }
            }
            PillButton(stringResource(R.string.wl_cancel), false) { finish() }
            PillButton(stringResource(R.string.wl_save), true) {
                prof.save(this@ControlsEditorActivity)
                finish()
            }
        }
    }

    @Composable
    private fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
        val pal = LocalPalette.current
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (primary) pal.onSignal else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clip(RoundedCornerShape(50))
                .background(if (primary) pal.signal else Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    private fun addElement(t: Element.Type) {
        val e = Element(t, Element.Shape.CIRCLE).apply { applyType(t); x = 0.5f; y = 0.5f }
        prof.elements.add(e)
        selected = e
        view.selected = e
        changed()
    }

    private fun duplicateSelected() {
        val s = selected ?: return
        val c = Element.fromJson(s.toJson()) ?: return
        c.x = (s.x + 0.04f).coerceAtMost(1f); c.y = (s.y + 0.06f).coerceAtMost(1f)
        prof.elements.add(c)
        selected = c
        view.selected = c
        changed()
    }

    private fun deleteSelected() {
        val s = selected ?: return
        prof.elements.remove(s)
        selected = null
        view.selected = null
        changed()
    }

    @Composable
    private fun SidePanel() {
        val e = selected ?: return
        val r = rev
        val maxH = (LocalConfiguration.current.screenHeightDp - 80).dp
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxH)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            if (r < 0) return@Column
            ElementPanel(e) { changed() }
        }
    }

    @Composable
    private fun ElementPanel(e: Element, onChange: () -> Unit) {
        ChipRow(TYPE_LABELS, e.type) { e.applyType(it); onChange() }
        var textDialog by remember { mutableStateOf(false) }
        when (e.type) {
            Element.Type.BUTTON -> {
                ChipRow(SHAPE_LABELS, e.shape) { e.shape = it; onChange() }
                DeckRow(stringResource(R.string.wl_text), null) {
                    Text(
                        e.text.ifEmpty { "-" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = LocalPalette.current.signal, modifier = Modifier.clickable { textDialog = true }.padding(8.dp),
                    )
                }
                BindingRow(stringResource(R.string.wl_binding), e.bindings.firstOrNull() ?: Binding.NONE) {
                    e.bindings = arrayOf(it); onChange()
                }
                DeckToggleRow(stringResource(R.string.wl_toggle), null, e.toggleSwitch) { e.toggleSwitch = it; onChange() }
                DeckToggleRow(stringResource(R.string.wl_mouse_move), null, e.mouseMoveMode) { e.mouseMoveMode = it; onChange() }
            }
            Element.Type.RANGE_BUTTON -> {
                ChipRow(
                    Element.Range.entries.map { it to it.name.removePrefix("FROM_").replace("_TO_", "-") },
                    e.range ?: Element.Range.FROM_A_TO_Z,
                ) {
                    e.range = it
                    e.bindings = Array(it.max) { i -> Element.rangeBinding(it, i) }
                    onChange()
                }
                ChipRow(listOf(0 to "—", 1 to "|"), e.orientation) { e.orientation = it; onChange() }
            }
            else -> {
                val names = listOf(R.string.wl_up, R.string.wl_right, R.string.wl_down, R.string.wl_left)
                for (i in 0 until 4) {
                    BindingRow(stringResource(names[i]), e.bindings.getOrElse(i) { Binding.NONE }) { b ->
                        if (e.bindings.size < 4) e.bindings = Array(4) { j -> e.bindings.getOrElse(j) { Binding.NONE } }
                        e.bindings[i] = b
                        onChange()
                    }
                }
                if (e.type == Element.Type.STICK || e.type == Element.Type.DYNAMIC_STICK) {
                    DeckSliderRow(stringResource(R.string.wl_deadzone), null, (e.deadZone * 100).roundToInt(), 0..50, 5, { "$it%" }) {
                        e.deadZone = it / 100f; onChange()
                    }
                }
                if (e.type == Element.Type.DYNAMIC_STICK) {
                    DeckSliderRow(stringResource(R.string.wl_area_w), null, (e.areaWidthRatio * 100).roundToInt(), 10..100, 5, { "$it%" }) {
                        e.areaWidthRatio = it / 100f; onChange()
                    }
                    DeckSliderRow(stringResource(R.string.wl_area_h), null, (e.areaHeightRatio * 100).roundToInt(), 10..100, 5, { "$it%" }) {
                        e.areaHeightRatio = it / 100f; onChange()
                    }
                    DeckSliderRow(stringResource(R.string.wl_stick_radius), null, (e.stickRadiusRatio * 100).roundToInt(), 5..30, 1, { "$it%" }) {
                        e.stickRadiusRatio = it / 100f; onChange()
                    }
                }
            }
        }
        if (e.type != Element.Type.DYNAMIC_STICK) {
            DeckSliderRow(stringResource(R.string.wl_scale), null, (e.scale * 100).roundToInt(), 50..300, 10, { "$it%" }) {
                e.scale = it / 100f; onChange()
            }
        }
        DeckSliderRow(stringResource(R.string.wl_opacity), null, (e.opacity * 100).roundToInt(), 10..100, 5, { "$it%" }) {
            e.opacity = it / 100f; onChange()
        }
        ChipRow(COLOR_LABELS, e.customColor) { e.customColor = it; onChange() }
        if (textDialog) NameDialog(stringResource(R.string.wl_text), e.text, { textDialog = false }) {
            e.text = it; textDialog = false; onChange()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // sama dengan DisplayActivity supaya koordinat ternormalisasi (0..1) cocok dengan layar sesi
    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE = "tiny.controls.profile"

        private val TYPE_LABELS = listOf(
            Element.Type.BUTTON to "BUTTON", Element.Type.D_PAD to "D-PAD", Element.Type.STICK to "STICK",
            Element.Type.DYNAMIC_STICK to "DYN STICK", Element.Type.TRACKPAD to "TRACKPAD", Element.Type.RANGE_BUTTON to "RANGE",
        )
        private val SHAPE_LABELS = listOf(
            Element.Shape.CIRCLE to "CIRCLE", Element.Shape.RECT to "RECT",
            Element.Shape.ROUND_RECT to "ROUND RECT", Element.Shape.SQUARE to "SQUARE",
        )
        private val COLOR_LABELS = listOf(
            0 to "DEFAULT", 0xFF1A9FFF.toInt() to "BLUE", 0xFF3DDC84.toInt() to "GREEN", 0xFFFF5252.toInt() to "RED",
            0xFFFFA726.toInt() to "AMBER", 0xFFE8EEF4.toInt() to "WHITE", 0xFFC77DFF.toInt() to "VIOLET", 0xFFFF6FB5.toInt() to "PINK",
        )
    }
}
