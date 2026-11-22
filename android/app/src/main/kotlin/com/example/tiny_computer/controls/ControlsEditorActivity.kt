package com.example.tiny_computer.controls

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.tiny_computer.R
import com.example.tiny_computer.ui.TinyTheme
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Editor tata letak controller gaya Winlator 4.1 (port `ControlsEditorComposeHost`): kanvas layar penuh dengan grid;
 * bilah alat melayang yang bisa digeser (tambah, hapus, gandakan, urungkan, pengaturan elemen, warna skema), panel
 * pengaturan elemen di sisi layar yang berlawanan dengan elemen, palet 64 warna + kolom hex. Perubahan disimpan
 * otomatis ke profil (tanpa tombol Simpan/Batal di bilah); keluar dengan tombol Kembali.
 * Profil yang diedit dikirim lewat [EXTRA_PROFILE]; tanpa itu profil aktif.
 */
class ControlsEditorActivity : ComponentActivity() {
    private lateinit var view: ProfileControlsView
    private lateinit var prof: Profile
    private var selected by mutableStateOf<Element?>(null)
    private var panelElement by mutableStateOf<Element?>(null)
    private var showColors by mutableStateOf(false)
    private var rev by mutableIntStateOf(0)

    // riwayat urungkan: potret profil sebelum tiap rangkaian perubahan (digabung bila berdekatan < 600 md)
    private val undo = ArrayList<String>()
    private var lastSnap = ""
    private var lastPush = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        prof = ProfileStore.get(this, intent.getIntExtra(EXTRA_PROFILE, ProfileStore.activeId(this))) ?: ProfileStore.active(this)
        val p = prof
        view = ProfileControlsView(this, null, editing = true).apply {
            profile = p
            onSelect = { onSelected(it) }
            onEdited = { commit(); persist() }
        }
        lastSnap = snapshot()
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B0F14.toInt())
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        // chrome Compose di atas kanvas; sentuhan di luar bilah/panel jatuh ke kanvas
        val chrome = ComposeView(this).apply { setContent { TinyTheme { Editor() } } }
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onPause() {
        persist()
        super.onPause()
    }

    // ---- keadaan

    private fun onSelected(e: Element?) {
        selected = e
        if (e == null) { panelElement = null; showColors = false }
        else if (panelElement != null) panelElement = e
        rev++
    }

    private fun snapshot(): String = JSONObject().apply {
        put("t", prof.themeColor)
        put("e", JSONArray().also { a -> prof.elements.forEach { a.put(it.toJson()) } })
    }.toString()

    /** Catat perubahan ke riwayat urungkan dan gambar ulang; penyimpanan ke berkas lewat [persist]. */
    private fun commit() {
        val snap = snapshot()
        if (snap != lastSnap) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPush > 600) {
                undo.add(lastSnap)
                if (undo.size > 60) undo.removeAt(0)
            }
            lastPush = now
            lastSnap = snap
        }
        rev++
        view.invalidate()
    }

    private fun persist() { prof.save(this) }

    private fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        runCatching {
            val o = JSONObject(prev)
            prof.themeColor = o.getInt("t")
            val arr = o.getJSONArray("e")
            prof.elements.clear()
            for (i in 0 until arr.length()) Element.fromJson(arr.getJSONObject(i))?.let { prof.elements.add(it) }
        }
        lastSnap = prev
        lastPush = 0L
        selected = null; panelElement = null; view.selected = null
        persist()
        rev++
        view.invalidate()
    }

    private fun addElement() {
        val e = Element(Element.Type.BUTTON, Element.Shape.CIRCLE).apply { applyType(Element.Type.BUTTON); x = 0.5f; y = 0.5f }
        prof.elements.add(e)
        selected = e
        view.selected = e
        commit(); persist()
    }

    private fun duplicateSelected() {
        val s = selected ?: return
        val c = Element.fromJson(s.toJson()) ?: return
        c.x = (s.x + 0.04f).coerceAtMost(1f); c.y = (s.y + 0.06f).coerceAtMost(1f)
        prof.elements.add(c)
        selected = c
        view.selected = c
        commit(); persist()
    }

    private fun removeSelected() {
        val s = selected ?: return
        prof.elements.remove(s)
        selected = null; panelElement = null
        view.selected = null
        commit(); persist()
    }

    // ---- tampilan (port Editor Winlator)

    @Composable
    private fun Editor() {
        val r = rev // dibaca supaya bilah dan panel ikut diperbarui setiap perubahan
        val config = LocalConfiguration.current
        val density = LocalDensity.current
        var dragX by remember { mutableStateOf(0f) }
        var dragY by remember { mutableStateOf(0f) }
        var toolbarWidth by remember { mutableStateOf(0) }
        val screenWidth = with(density) { config.screenWidthDp.dp.toPx() }
        val screenHeight = with(density) { config.screenHeightDp.dp.toPx() }
        LaunchedEffect(screenWidth, screenHeight, toolbarWidth) {
            val maxX = ((screenWidth - toolbarWidth) / 2).coerceAtLeast(0f)
            dragX = dragX.coerceIn(-maxX, maxX)
            dragY = dragY.coerceIn(0f, (screenHeight - with(density) { 64.dp.toPx() }).coerceAtLeast(0f))
        }
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                    .padding(top = 8.dp).onSizeChanged { toolbarWidth = it.width },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Row(
                    Modifier.width((config.screenWidthDp - 16).coerceAtMost(460).dp).horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.DragIndicator, stringResource(R.string.wl_move_toolbar),
                        Modifier.size(28.dp).pointerInput(screenWidth, screenHeight, toolbarWidth) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                val maxX = ((screenWidth - toolbarWidth) / 2).coerceAtLeast(0f)
                                dragX = (dragX + delta.x).coerceIn(-maxX, maxX)
                                dragY = (dragY + delta.y).coerceIn(0f, (screenHeight - 64.dp.toPx()).coerceAtLeast(0f))
                            }
                        },
                    )
                    Column(Modifier.width(80.dp).padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.wl_profile), style = MaterialTheme.typography.labelSmall)
                        Text(prof.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    IconButton(onClick = { addElement() }) { Icon(Icons.Outlined.Add, stringResource(R.string.wl_add)) }
                    IconButton(onClick = { removeSelected() }) { Icon(Icons.Outlined.Delete, stringResource(R.string.wl_remove)) }
                    IconButton(onClick = { duplicateSelected() }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.wl_duplicate)) }
                    IconButton(onClick = { undoLast() }, enabled = undo.isNotEmpty() && r >= 0) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.wl_undo))
                    }
                    IconButton(onClick = { panelElement = selected }) { Icon(Icons.Outlined.Settings, stringResource(R.string.wl_settings)) }
                    IconButton(onClick = { showColors = true }) { Icon(Icons.Outlined.Palette, stringResource(R.string.wl_scheme_color)) }
                }
            }
            if (showColors) {
                EditorPanel(stringResource(R.string.wl_scheme_color), { persist(); showColors = false }) {
                    Text(stringResource(R.string.wl_scheme_color_hint), style = MaterialTheme.typography.bodySmall)
                    ColorGrid(prof.themeColor) { color -> prof.themeColor = color; commit() }
                    HexColorField(prof.themeColor) { prof.themeColor = it; commit() }
                }
            }
            panelElement?.let { element ->
                // kanvas tetap bisa disentuh saat panel terbuka
                key(element) {
                    EditorPanel(
                        stringResource(R.string.wl_settings), { persist(); panelElement = null },
                        if (element.x > 0.5f) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        ElementFields(element, r, { view.invalidate() }, { commit() })
                    }
                }
            }
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
    }
}

private val palette = intArrayOf(
    0, 0xffffffff.toInt(), 0xffdddddd.toInt(), 0xffaaaaaa.toInt(), 0xff777777.toInt(), 0xff444444.toInt(), 0xff222222.toInt(), 0xff000000.toInt(),
    0xffff3b30.toInt(), 0xffff6961.toInt(), 0xffff453a.toInt(), 0xffcc0000.toInt(), 0xff800000.toInt(), 0xff4d0000.toInt(), 0xffff8080.toInt(), 0xffffb3b3.toInt(),
    0xffff9500.toInt(), 0xffff6000.toInt(), 0xfffe9f0d.toInt(), 0xffffcc00.toInt(), 0xffffd60a.toInt(), 0xffffea00.toInt(), 0xffffb347.toInt(), 0xffffdca5.toInt(),
    0xff34c759.toInt(), 0xff30d158.toInt(), 0xff4cd964.toInt(), 0xff00b050.toInt(), 0xff2e8b57.toInt(), 0xff006400.toInt(), 0xffa8e6cf.toInt(), 0xffd4edda.toInt(),
    0xff5ac8fa.toInt(), 0xff32ade6.toInt(), 0xff00b4d8.toInt(), 0xff0096c7.toInt(), 0xff00758a.toInt(), 0xff004c5a.toInt(), 0xffb2ebf2.toInt(), 0xffe0f7fa.toInt(),
    0xff007aff.toInt(), 0xff0a84ff.toInt(), 0xff2184ff.toInt(), 0xff1a56db.toInt(), 0xff003f8f.toInt(), 0xff001a66.toInt(), 0xffbed6f8.toInt(), 0xffdce9ff.toInt(),
    0xffaf52de.toInt(), 0xffbf5af2.toInt(), 0xff9b59b6.toInt(), 0xff6e3fa3.toInt(), 0xff4a0e8f.toInt(), 0xff2d0066.toInt(), 0xffd7b4f3.toInt(), 0xffede0f8.toInt(),
    0xffff2d92.toInt(), 0xffff375f.toInt(), 0xffff6ab0.toInt(), 0xffe91e8c.toInt(), 0xffad1457.toInt(), 0xff6a0032.toInt(), 0xffffb3d9.toInt(), 0xffffdcef.toInt()
)

@Composable
private fun BoxScope.EditorPanel(title: String, onDismiss: () -> Unit, alignment: Alignment = Alignment.CenterEnd, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val panelWidth = if (landscape) minOf(330f, configuration.screenWidthDp * 0.44f).dp
        else (configuration.screenWidthDp - 24).dp
    Surface(
        modifier = Modifier.align(alignment).padding(12.dp).width(panelWidth)
            .heightIn(max = (configuration.screenHeightDp - 24).dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.wl_save)) }
            }
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp - 104).coerceAtLeast(160).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun ColorGrid(selected: Int, onSelected: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        modifier = Modifier.width(224.dp).height(224.dp),
        userScrollEnabled = false,
    ) {
        items(palette.size) { index ->
            val color = palette[index]
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .background(if (color == 0) Color.White else Color(color))
                        .then(if (selected == color) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .clickable { onSelected(color) },
                )
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, selected: T, options: List<T>, text: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text(selected), modifier = Modifier.weight(1f))
            Text("\u25BE")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { expanded = false; onSelected(option) })
            }
        }
    }
}

@Composable
private fun Percent(label: String, value: Float, step: Int, onChange: (Float) -> Unit, onFinished: () -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f) {
    var progress by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${(progress * 100).roundToInt()}%")
        Slider(
            value = progress.coerceIn(range),
            valueRange = range,
            onValueChange = {
                val rounded = if (step == 1) (it * 100).roundToInt() else ((it * 100 / step).roundToInt() * step)
                progress = (rounded / 100f).coerceIn(range)
                onChange(progress)
            },
            onValueChangeFinished = onFinished,
        )
    }
}

@Composable
private fun RatioSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    var progress by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${(progress * 100).roundToInt()}%")
        Slider(
            value = progress,
            onValueChange = {
                progress = ((it * 100).roundToInt() / 100f).coerceIn(min, max)
                onChange(progress)
            },
            onValueChangeFinished = onFinished,
            valueRange = min..max,
        )
    }
}

@Composable
private fun HexColorField(color: Int, onChange: (Int) -> Unit) {
    var value by remember(color) { mutableStateOf(if (color == 0) "" else "%06X".format(color and 0xffffff)) }
    OutlinedTextField(
        value, { text ->
            val clean = text.removePrefix("#").take(6)
            value = clean
            if (clean.isEmpty()) onChange(0)
            else if (clean.length == 6 && clean.all { it in "0123456789abcdefABCDEF" }) onChange((0xff000000L or clean.toLong(16)).toInt())
        },
        label = { Text(stringResource(R.string.wl_color_hex)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

/** Isian pengaturan satu elemen, urutan sama dengan `ElementFields` Winlator 4.1. [touch] = gambar ulang, [commit] = catat perubahan. */
@Composable
private fun ElementFields(e: Element, rev: Int, touch: () -> Unit, commit: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val update = rev
    val type = e.type
    Choice(stringResource(R.string.wl_type), type, Element.Type.entries.toList(), { it.name.replace('_', '-') }) {
        // applyType mengatur ulang binding: jangan dipanggil bila jenisnya sama
        if (it != e.type) { e.applyType(it); commit() }
    }
    if (type == Element.Type.BUTTON) {
        Choice(stringResource(R.string.wl_shape), e.shape, Element.Shape.entries.toList(), { it.name.replace('_', ' ') }) {
            if (it != e.shape) { e.shape = it; commit() }
        }
    }
    if (type != Element.Type.DYNAMIC_STICK) {
        Percent(stringResource(R.string.wl_scale), e.scale, 5, { e.scale = it; touch() }, commit, 0.5f..2f)
    }
    if (type == Element.Type.STICK || type == Element.Type.DYNAMIC_STICK) {
        var deadZone by remember(e) { mutableStateOf(e.deadZone) }
        Column {
            Text(stringResource(R.string.wl_dead_zone_fmt, (deadZone * 100).roundToInt()))
            Slider(
                value = deadZone,
                onValueChange = { deadZone = (it * 100).roundToInt() / 100f; e.deadZone = deadZone },
                onValueChangeFinished = commit,
                valueRange = 0f..0.5f,
            )
        }
    }
    if (type == Element.Type.DYNAMIC_STICK) {
        RatioSlider(stringResource(R.string.wl_touch_area_width), e.areaWidthRatio, 0.15f, 1f, { e.areaWidthRatio = it; touch() }, commit)
        RatioSlider(stringResource(R.string.wl_touch_area_height), e.areaHeightRatio, 0.15f, 1f, { e.areaHeightRatio = it; touch() }, commit)
        RatioSlider(stringResource(R.string.wl_stick_radius), e.stickRadiusRatio, 0.05f, 0.3f, { e.stickRadiusRatio = it; touch() }, commit)
    }
    Percent(stringResource(R.string.wl_opacity), e.opacity, 1, { e.opacity = it; touch() }, commit)
    Text(stringResource(R.string.wl_theme_color), style = MaterialTheme.typography.labelMedium)
    ColorGrid(e.customColor) { e.customColor = it; commit() }
    HexColorField(e.customColor) { e.customColor = it; commit() }
    if (type == Element.Type.BUTTON) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wl_toggle_switch), modifier = Modifier.weight(1f))
            Switch(checked = e.toggleSwitch, onCheckedChange = { e.toggleSwitch = it; commit() })
        }
        var customText by remember(e) { mutableStateOf(e.text) }
        OutlinedTextField(
            value = customText,
            onValueChange = { customText = it; e.text = it.trim(); commit() },
            label = { Text(stringResource(R.string.wl_custom_text)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
    if (type == Element.Type.RANGE_BUTTON) {
        Choice(
            stringResource(R.string.wl_range), e.range ?: Element.Range.FROM_A_TO_Z, Element.Range.entries.toList(),
            { it.name.removePrefix("FROM_").replace("_TO_", "-").replace('_', ' ') },
        ) {
            if (it != e.range) { e.range = it; commit() }
        }
        // jumlah kolom yang tampak (3..8); sisanya digulir dengan menggeser jari, seperti Winlator
        Choice(stringResource(R.string.wl_columns), e.bindings.size, (3..8).toList(), { it.toString() }) {
            if (it != e.bindings.size) { e.bindings = Array(it) { Binding.NONE }; commit() }
        }
        val horizontal = stringResource(R.string.wl_horizontal)
        val vertical = stringResource(R.string.wl_vertical)
        Choice(stringResource(R.string.wl_orientation), e.orientation, listOf(0, 1), { if (it == 0) horizontal else vertical }) {
            if (it != e.orientation) { e.orientation = it; commit() }
        }
    }
    val bindingLabels = when (type) {
        Element.Type.BUTTON -> listOf(R.string.wl_binding)
        Element.Type.D_PAD, Element.Type.STICK, Element.Type.DYNAMIC_STICK, Element.Type.TRACKPAD ->
            listOf(R.string.wl_up, R.string.wl_right, R.string.wl_down, R.string.wl_left)
        else -> emptyList()
    }
    bindingLabels.forEachIndexed { index, label ->
        key(type, index) { BindingField(stringResource(label), e, index, commit) }
    }
    if (type == Element.Type.BUTTON) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wl_move_cursor_drag), modifier = Modifier.weight(1f))
            Switch(checked = e.mouseMoveMode, onCheckedChange = { e.mouseMoveMode = it; commit() })
        }
    }
}

@Composable
private fun BindingField(label: String, e: Element, index: Int, commit: () -> Unit) {
    val current = e.bindings.getOrElse(index) { Binding.NONE }
    var family by remember(e, index) { mutableIntStateOf(if (current.isMouse) 1 else if (current.isGamepad) 2 else 0) }
    val options = when (family) {
        1 -> Binding.mouseValues
        2 -> Binding.gamepadValues
        else -> Binding.keyboardValues
    }
    val none = stringResource(R.string.wl_none)
    val families = listOf(stringResource(R.string.wl_tab_keyboard), stringResource(R.string.wl_tab_mouse), stringResource(R.string.wl_tab_gamepad))
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            Choice(stringResource(R.string.wl_input), family, listOf(0, 1, 2), { families[it] }) { family = it }
        }
        Box(Modifier.weight(1f)) {
            Choice(stringResource(R.string.wl_binding), current, options, { if (it == Binding.NONE) none else it.label }) { b ->
                if (b != current) {
                    if (e.type == Element.Type.BUTTON) e.bindings = arrayOf(b)
                    else {
                        if (e.bindings.size < 4) e.bindings = Array(4) { j -> e.bindings.getOrElse(j) { Binding.NONE } }
                        e.bindings[index] = b
                    }
                    commit()
                }
            }
        }
    }
}
