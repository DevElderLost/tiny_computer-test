package com.example.tiny_computer.ui

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.FlatpakManager
import com.example.tiny_computer.core.FlathubApi
import com.example.tiny_computer.core.FlathubStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val CardShape = RoundedCornerShape(14.dp)
private val IconShape = RoundedCornerShape(14.dp)
private val iconCache = LruCache<String, ImageBitmap>(160)

/**
 * Tab Flathub Store, mengikuti Store milik DroidDeck: jelajah koleksi (Popular, Trending, New, Updated), pencarian
 * dengan kategori, halaman detail, daftar terpasang, dan pembaruan. Aplikasi dipasang dengan Flatpak ke instalasi
 * per pengguna di kontainer ([FlatpakManager]); hasilnya muncul di grid Shortcut dan menu desktop. [onLaunchApp]
 * menjalankan aplikasi terpasang lewat alur Shortcut yang sama.
 */
@Composable
fun FlathubScreen(onLaunchApp: (String) -> Unit) {
    val ctx = LocalContext.current
    val ready = FlathubStore.ready
    var openId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { FlathubStore.refresh() }
    LaunchedEffect(ready) { if (ready) FlathubStore.loadSections() }
    BackHandler(enabled = openId != null) { openId = null }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tc_fh_title), stringResource(R.string.tc_fh_subtitle))
        BusyBar()
        val id = openId
        when {
            !ready -> SetupCard()
            id != null -> AppDetail(id, onLaunchApp) { openId = null }
            else -> Storefront(onOpen = { openId = it }, onLaunchApp = onLaunchApp)
        }
    }
    // alasan gagal dari skrip pasang di terminal ditampilkan sekali saat pemantauan berhenti
    val watching = FlatpakManager.setupWatching
    LaunchedEffect(watching) {
        val failure = FlatpakManager.setupFailure()
        if (!watching && !ready && failure != null) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.tc_fh_setup_failed, failure), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

// ---------------------------------------------------------------------------------------------- pemasangan

@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(CardShape)
                .background(MaterialTheme.colorScheme.surface, CardShape).border(1.dp, pal.line, CardShape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.tc_fh_setup_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground)
            Text(stringResource(R.string.tc_fh_setup_text), fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.onSurfaceVariant)
            DeckButton(stringResource(R.string.tc_fh_setup_button), Modifier.fillMaxWidth(), enabled = !FlatpakManager.setupWatching) {
                FlatpakManager.beginSetup(ctx)?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
            }
            if (FlatpakManager.setupWatching) Text(stringResource(R.string.tc_fh_setup_started), fontSize = 12.5.sp, color = pal.onSurfaceVariant)
        }
    }
}

/** Bilah progres pemasangan/penghapusan/pembaruan yang sedang berjalan. */
@Composable
private fun BusyBar() {
    if (FlathubStore.busy == null) return
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(FlathubStore.stage ?: stringResource(R.string.tc_fh_starting), fontSize = 12.5.sp, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val p = FlathubStore.percent
        if (p in 0..100) LinearProgressIndicator(progress = p / 100f, modifier = Modifier.fillMaxWidth(), color = pal.signal, trackColor = pal.line)
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = pal.signal, trackColor = pal.line)
    }
}

// ---------------------------------------------------------------------------------------------- etalase

@Composable
private fun Storefront(onOpen: (String) -> Unit, onLaunchApp: (String) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("discover") }
    val busy = FlathubStore.busy != null

    fun runSearch() {
        if (query.isBlank() && category == null) FlathubStore.clearSearch() else FlathubStore.search(query.trim(), category)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; mode = "discover" },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true, shape = RoundedCornerShape(12.dp),
            placeholder = { Text(stringResource(R.string.tc_fh_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = pal.signal, unfocusedBorderColor = pal.line2),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.tc_fh_mode_discover), mode == "discover") { mode = "discover" }
            Pill("${stringResource(R.string.tc_fh_mode_installed)} (${FlathubStore.installed.size})", mode == "installed") { mode = "installed" }
            Pill(
                stringResource(R.string.tc_fh_mode_updates) + if (FlathubStore.updates.isNotEmpty()) " (${FlathubStore.updates.size})" else "",
                mode == "updates",
            ) { mode = "updates" }
        }
        if (mode == "discover") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlathubApi.categories.forEach { c ->
                    Pill(stringResource(c.label), category == c.id) {
                        category = if (category == c.id) null else c.id
                        runSearch()
                    }
                }
            }
        }
        when (mode) {
            "installed" -> InstalledList(busy, onOpen, onLaunchApp)
            "updates" -> UpdatesList(busy, onOpen)
            else -> Discover(onOpen, ctx.getString(R.string.tc_fh_no_results))
        }
    }
}

@Composable
private fun Discover(onOpen: (String) -> Unit, noResults: String) {
    val pal = LocalPalette.current
    val results = FlathubStore.searchResults
    when {
        FlathubStore.searching -> Text(stringResource(R.string.tc_fh_checking), Modifier.padding(20.dp), color = pal.onSurfaceVariant)
        results != null -> {
            if (results.isEmpty()) Text(noResults, Modifier.padding(20.dp), color = pal.onSurfaceVariant)
            results.forEach { a -> AppRow(a.id, a.name, a.summary, a.icon, FlathubStore.installed.any { it.id == a.id }, onClick = { onOpen(a.id) }) }
        }
        else -> {
            if (FlathubStore.sectionsFailed && FlathubStore.sections.values.all { it.isEmpty() }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.tc_fh_load_failed), color = pal.onSurfaceVariant)
                    DeckButton(stringResource(R.string.tc_fh_retry), primary = false) { FlathubStore.loadSections(force = true) }
                }
            }
            FlathubStore.SECTIONS.forEach { (key, label) ->
                val apps = FlathubStore.sections[key] ?: return@forEach
                if (apps.isEmpty()) return@forEach
                Text(stringResource(label), Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    apps.forEach { a -> AppCard(a.name, a.icon ?: FlathubApi.iconUrl(a.id)) { onOpen(a.id) } }
                }
            }
        }
    }
}

@Composable
private fun InstalledList(busy: Boolean, onOpen: (String) -> Unit, onLaunchApp: (String) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val apps = FlathubStore.installed
    if (apps.isEmpty()) Text(stringResource(R.string.tc_fh_empty_installed), Modifier.padding(20.dp), color = pal.onSurfaceVariant)
    apps.forEach { a ->
        AppRow(a.id, a.name, a.summary ?: a.id, a.icon ?: FlathubApi.iconUrl(a.id), false, onClick = { onOpen(a.id) }) {
            DeckButton(stringResource(R.string.tc_fh_open), Modifier.widthIn(min = 72.dp), primary = false, enabled = !busy) { onLaunchApp(a.id) }
            Spacer(Modifier.width(6.dp))
            DeckButton(stringResource(R.string.tc_btn_remove), Modifier.widthIn(min = 72.dp), primary = false, enabled = !busy) {
                FlathubStore.uninstall(ctx, a.id, a.name)
            }
        }
    }
}

@Composable
private fun UpdatesList(busy: Boolean, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckButton(
            stringResource(if (FlathubStore.checkingUpdates) R.string.tc_fh_checking else R.string.tc_fh_check_updates),
            Modifier.fillMaxWidth(), primary = false, enabled = !busy && !FlathubStore.checkingUpdates,
        ) { FlathubStore.checkUpdates(ctx) }
        if (FlathubStore.updates.isNotEmpty()) {
            DeckButton(stringResource(R.string.tc_fh_update_all), Modifier.fillMaxWidth(), enabled = !busy) {
                FlathubStore.update(ctx, null, ctx.getString(R.string.tc_fh_update_all))
            }
        } else if (FlathubStore.updatesChecked) {
            Text(stringResource(R.string.tc_fh_up_to_date), color = pal.onSurfaceVariant)
        }
    }
    FlathubStore.installed.filter { it.id in FlathubStore.updates }.forEach { a ->
        AppRow(a.id, a.name, a.summary ?: a.id, a.icon ?: FlathubApi.iconUrl(a.id), false, onClick = { onOpen(a.id) }) {
            DeckButton(stringResource(R.string.tc_fh_update), Modifier.widthIn(min = 72.dp), enabled = !busy) { FlathubStore.update(ctx, a.id, a.name) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- detail

@Composable
private fun AppDetail(id: String, onLaunchApp: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    LaunchedEffect(id) { FlathubStore.loadDetails(id) }
    val d = FlathubStore.details[id]
    val installedApp = FlathubStore.installed.firstOrNull { it.id == id }
    val installed = installedApp != null
    val updatable = id in FlathubStore.updates
    val busy = FlathubStore.busy != null
    val name = d?.name ?: installedApp?.name ?: id.substringAfterLast('.')

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.tc_fh_back), tint = pal.onBackground) }
            AppIcon(installedApp?.icon ?: d?.icon ?: FlathubApi.iconUrl(id), name, 64.dp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                d?.developer?.let { Text(it, fontSize = 13.sp, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        d?.summary?.takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 14.sp, color = pal.onBackground) }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (installed) {
                DeckButton(stringResource(R.string.tc_fh_open), Modifier.weight(1f), enabled = !busy) { onLaunchApp(id) }
                if (updatable) DeckButton(stringResource(R.string.tc_fh_update), Modifier.weight(1f), primary = false, enabled = !busy) { FlathubStore.update(ctx, id, name) }
                DeckButton(stringResource(R.string.tc_btn_remove), Modifier.weight(1f), primary = false, enabled = !busy) { FlathubStore.uninstall(ctx, id, name) }
            } else {
                DeckButton(stringResource(R.string.tc_btn_install), Modifier.fillMaxWidth(), enabled = !busy) { FlathubStore.install(ctx, id, name) }
            }
        }

        if (d == null) {
            if (FlathubStore.detailsFailed[id] == true) Text(stringResource(R.string.tc_fh_details_failed), Modifier.padding(20.dp), color = pal.onSurfaceVariant)
            return@Column
        }
        if (d.screenshots.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.screenshots.forEach { s ->
                    RemoteImage(s.thumb, Modifier.height(170.dp).clip(RoundedCornerShape(10.dp)), ContentScale.FillHeight)
                }
            }
        }
        if (d.description.isNotEmpty()) {
            Text(d.description, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.onBackground)
        }
        DeckGroup(stringResource(R.string.tc_fh_info)) {
            d.version?.let { DeckInfoRow(stringResource(R.string.tc_fh_version), it) }
            d.license?.let { DeckInfoRow(stringResource(R.string.tc_fh_license), it) }
            if (d.downloadSize > 0) DeckInfoRow(stringResource(R.string.tc_fh_download_size), Formatter.formatShortFileSize(ctx, d.downloadSize))
            if (d.installedSize > 0) DeckInfoRow(stringResource(R.string.tc_fh_installed_size), Formatter.formatShortFileSize(ctx, d.installedSize))
            d.runtime?.let { DeckInfoRow(stringResource(R.string.tc_fh_runtime), it.substringBefore('/')) }
            d.homepage?.let { DeckInfoRow(stringResource(R.string.tc_fh_website), it.removePrefix("https://").removePrefix("http://").trimEnd('/')) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- komponen

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.18f) else pal.surfaceVariant, shape)
            .border(1.dp, if (selected) pal.signal else pal.line2, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) pal.signal else pal.onBackground, maxLines = 1) }
}

@Composable
private fun AppCard(name: String, icon: Any?, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier.width(104.dp).clip(CardShape).background(MaterialTheme.colorScheme.surface, CardShape)
            .border(1.dp, pal.line, CardShape).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(icon, name, 60.dp)
        Text(name, fontSize = 12.5.sp, color = pal.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
    }
}

/** Baris aplikasi: ikon, nama + ringkasan, dan aksi di kanan ([trailing]); bila tanpa aksi, lencana Terpasang. */
@Composable
private fun AppRow(
    id: String, name: String, summary: String, icon: Any?, installed: Boolean,
    onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null,
) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon ?: FlathubApi.iconUrl(id), name, 48.dp)
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(summary, fontSize = 12.5.sp, color = pal.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else if (installed) Text(stringResource(R.string.tc_fh_installed_badge), fontSize = 12.sp, color = pal.good)
    }
}

/** Ikon aplikasi dari berkas lokal ([File]) atau URL; bila belum ada, kotak dengan huruf awal nama. */
@Composable
private fun AppIcon(model: Any?, name: String, size: Dp) {
    val pal = LocalPalette.current
    val bitmap = rememberBitmap(model)
    Box(Modifier.size(size).clip(IconShape).background(pal.surfaceVariant, IconShape), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) Image(bmp, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(name.take(1).uppercase(), fontSize = (size.value / 2.4f).sp, fontWeight = FontWeight.SemiBold, color = pal.onSurfaceVariant)
    }
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier, scale: ContentScale) {
    val pal = LocalPalette.current
    val bmp = rememberBitmap(url)
    if (bmp != null) Image(bmp, null, modifier, contentScale = scale)
    else Box(modifier.width(260.dp).background(pal.surfaceVariant))
}

/** Memuat gambar dari [File] atau URL http(s) di thread IO dan menyimpannya di cache memori. */
@Composable
private fun rememberBitmap(model: Any?): ImageBitmap? {
    val key = when (model) {
        is File -> model.path
        is String -> model
        else -> null
    } ?: return null
    return produceState<ImageBitmap?>(initialValue = iconCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decode(model!!)?.also { iconCache.put(key, it) } }
        }
    }.value
}

private fun decode(model: Any): ImageBitmap? = runCatching {
    when (model) {
        is File -> BitmapFactory.decodeFile(model.path)
        is String -> {
            val c = URL(model).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                if (c.responseCode / 100 != 2) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                c.disconnect()
            }
        }
        else -> null
    }?.asImageBitmap()
}.getOrNull()
