package com.example.tiny_computer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Rootfs
import com.example.tiny_computer.core.Startup
import kotlinx.coroutines.delay

/**
 * Layar progres saat pengguna menekan Open desktop, mengikuti LoadingOverlay DroidDeck: judul, satu baris
 * keterangan tahap, dan satu segmen progres per tahap (yang selesai terisi, yang berjalan terisi sesuai
 * persen atau bergerak bila tidak terukur). Animasi logo DroidDeck sengaja tidak dipakai.
 */
@Composable
fun StartupOverlay(onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val phase = Startup.phase
    val first = Startup.firstInstall
    val keys = Startup.stageKeys()
    var reached by remember { mutableIntStateOf(0) }
    var percent by remember { mutableIntStateOf(-1) }

    LaunchedEffect(phase) {
        if (phase == Startup.Phase.RUNNING) {
            reached = 0
            while (true) {
                val at = Startup.stageIndex(Rootfs.statusText)
                if (at > reached) reached = at // tidak pernah mundur
                percent = if (at >= reached) Rootfs.percent else -1
                delay(200)
            }
        }
    }

    val failed = phase == Startup.Phase.FAILED
    val active = reached.coerceAtMost(keys.lastIndex)
    val title = when {
        failed -> stringResource(R.string.tc_start_failed)
        Startup.importing -> stringResource(R.string.tc_import_title)
        first -> stringResource(R.string.tc_start_title_first)
        else -> stringResource(R.string.tc_start_title)
    }
    val detail = if (failed) Startup.error.orEmpty() else stageDetail(keys[active], percent)

    Box(
        Modifier.fillMaxSize().background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (failed) 8 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp))
            if (!failed) LoadSegments(keys.size, active, percent, Modifier.padding(top = 18.dp))
            if (failed) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
                DeckButton(stringResource(R.string.tc_start_retry)) { Startup.retry(ctx) }
                DeckButton(stringResource(R.string.tc_start_back), primary = false, onClick = onClose)
            }
        }
    }
}

@Composable
private fun stageDetail(key: String, percent: Int): String {
    val base = stringResource(
        when (key) {
            "installingBootPackage" -> R.string.tc_ui_installing_boot_package
            "copyingContainerSystem" -> R.string.tc_ui_copying_container_system
            "installingContainerSystem" -> R.string.tc_ui_installing_container_system
            "startingContainer" -> R.string.tc_start_container
            "installingExtras" -> R.string.tc_extras_installing
            "importingSystem" -> R.string.tc_import_stage
            else -> R.string.tc_start_prepare
        },
    )
    return if (percent >= 0) stringResource(R.string.tc_start_detail_percent, base, percent) else base
}

/**
 * Layar progres yang sama (judul, keterangan tahap, segmen) untuk aplikasi dari menu Shortcut:
 * [stopping] false = menunggu proot/Xwayland/aplikasi menyala (3 tahap), true = menunggu semua proses
 * benar-benar mati setelah Shutdown (2 tahap). [stage] berasal dari `Launcher.appStage`.
 */
@Composable
fun AppProgressOverlay(stopping: Boolean, stage: Int) {
    val colors = MaterialTheme.colorScheme
    val keys = if (stopping) {
        listOf(R.string.tc_app_stop_apps, R.string.tc_app_stop_wait)
    } else {
        listOf(R.string.tc_app_stage_prepare, R.string.tc_app_stage_display, R.string.tc_app_stage_app)
    }
    val active = stage.coerceIn(0, keys.lastIndex)
    Box(
        Modifier.fillMaxSize().background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(
                stringResource(if (stopping) R.string.tc_app_stop_title else R.string.tc_app_start_title),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(keys[active]), fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp),
            )
            LoadSegments(keys.size, active, -1, Modifier.padding(top = 18.dp))
        }
    }
}

/** Satu segmen per tahap: yang selesai terisi, yang berjalan terisi sesuai persen (atau bergerak bila -1). */
@Composable
private fun LoadSegments(count: Int, active: Int, percent: Int, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    val run by rememberInfiniteTransition(label = "segment")
        .animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "run")
    val stepDescription = stringResource(R.string.tc_start_step, active + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.semantics { contentDescription = stepDescription },
    ) {
        repeat(count) { i ->
            Box(
                Modifier.width(44.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < active) pal.signal else track)
                    .drawBehind {
                        if (i != active) return@drawBehind
                        if (percent >= 0) drawRect(pal.signal, size = Size(size.width * percent.coerceIn(0, 100) / 100f, size.height))
                        else drawRect(pal.signal, topLeft = Offset(size.width * run, 0f), size = Size(size.width * 0.4f, size.height))
                    },
            )
        }
    }
}
