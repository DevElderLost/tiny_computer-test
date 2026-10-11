package com.example.tiny_computer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tiny_computer.R
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Startup
import com.example.tiny_computer.core.TinyEnv
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

// Jangkauan seekbar; sama dengan batas yang dipakai ControllerOverlay (opasitas 0.2..1.0, ukuran 0.6..1.6).
private val OPACITY_RANGE = 20..100
private val SIZE_RANGE = 60..160

/**
 * Pengaturan controller bergaya DroidDeck: kelompok berjudul dengan baris label + petunjuk di kiri dan
 * kontrol di kanan. Identitas berupa kotak pilihan, saklar sekali ketuk, dan opasitas / ukuran overlay
 * berupa seekbar (bukan lagi deretan pil persen). Di lanskap dua kolom: identitas di kiri, overlay di kanan.
 */
@Composable
fun ControllerScreen() {
    var identity by remember { mutableStateOf(Cfg.padIdentity) }
    var overlay by remember { mutableStateOf(Cfg.padOverlay) }
    var haptics by remember { mutableStateOf(Cfg.padHaptics) }
    var opacity by remember { mutableIntStateOf((Cfg.padOverlayOpacity * 100).roundToInt().coerceIn(OPACITY_RANGE.first, OPACITY_RANGE.last)) }
    var size by remember { mutableIntStateOf((Cfg.padOverlayScale * 100).roundToInt().coerceIn(SIZE_RANGE.first, SIZE_RANGE.last)) }
    val hasLib = File(TinyEnv.dataPath, "tiny/extra/libfakeinput.so").isFile
    val land = isLandscape()

    // Menggeser seekbar memanggil onChange berkali-kali; tulis ke penyimpanan setelah berhenti sejenak.
    LaunchedEffect(opacity) {
        delay(200)
        if ((Cfg.padOverlayOpacity * 100).roundToInt() != opacity) Cfg.padOverlayOpacity = opacity / 100.0
    }
    LaunchedEffect(size) {
        delay(200)
        if ((Cfg.padOverlayScale * 100).roundToInt() != size) Cfg.padOverlayScale = size / 100.0
    }

    val identityGroup: @Composable () -> Unit = {
        // libfakeinput.so baru disalin saat pemasangan; sebelum itu bukan berarti hilang dari APK.
        if (Startup.installed && !hasLib) {
            Text(
                stringResource(R.string.tc_ctrl_missing), fontSize = 13.sp, color = LocalPalette.current.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        DeckGroup(stringResource(R.string.tc_ctrl_identity)) {
            DeckChoiceRow(
                stringResource(R.string.tc_ctrl_identity), null,
                listOf("xbox360" to stringResource(R.string.tc_ctrl_xbox), "deck" to stringResource(R.string.tc_ctrl_deck)),
                identity,
            ) { identity = it; Cfg.padIdentity = it }
        }
        DeckGroup(stringResource(R.string.tc_ctrl_defaults)) {
            DeckRow(stringResource(R.string.tc_ctrl_reset), stringResource(R.string.tc_ctrl_reset_hint)) {
                DeckButton(stringResource(R.string.tc_ctrl_reset_button), primary = false) {
                    identity = "xbox360"; Cfg.padIdentity = identity
                    overlay = false; Cfg.padOverlay = overlay
                    haptics = true; Cfg.padHaptics = haptics
                    opacity = 65; size = 100
                    Cfg.padOverlayOpacity = 0.65; Cfg.padOverlayScale = 1.0
                }
            }
        }
    }
    val overlayGroup: @Composable () -> Unit = {
        DeckGroup(stringResource(R.string.tc_pad_overlay)) {
            DeckToggleRow(stringResource(R.string.tc_pad_overlay), null, overlay) { overlay = it; Cfg.padOverlay = it }
            DeckSliderRow(
                stringResource(R.string.tc_ctrl_opacity), null, opacity, OPACITY_RANGE, step = 5,
                format = { "$it%" },
            ) { opacity = it }
            DeckSliderRow(
                stringResource(R.string.tc_ctrl_scale), null, size, SIZE_RANGE, step = 5,
                format = { "$it%" },
            ) { size = it }
            DeckToggleRow(stringResource(R.string.tc_ctrl_haptics), null, haptics) { haptics = it; Cfg.padHaptics = it }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle(stringResource(R.string.tc_ctrl_title), stringResource(R.string.tc_ctrl_hint))
        if (land) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { identityGroup() }
                Column(Modifier.weight(1f)) { overlayGroup() }
            }
        } else {
            identityGroup()
            overlayGroup()
        }
    }
}
