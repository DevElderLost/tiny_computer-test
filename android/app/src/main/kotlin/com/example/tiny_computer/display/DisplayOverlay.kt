package com.example.tiny_computer.display

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.PadBridge
import com.example.tiny_computer.controller.ControllerOverlay
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Launcher
import com.example.tiny_computer.ui.AppProgressOverlay
import com.example.tiny_computer.ui.PcKeyboard
import kotlin.math.abs

/**
 * Lapisan Compose di atas SurfaceView: controller layar, keyboard PC, menu sesi ([SessionDrawer], terbuka
 * dengan menggeser dari tepi kiri atau tombol Back) dan layar progres. Tidak ada lagi tombol mengambang.
 */
@Composable
fun DisplayOverlay(
    state: DisplayUiState, pad: PadBridge, sendKey: (Int, Boolean) -> Unit, onClose: () -> Unit,
    onShutdown: () -> Unit = {},
    onPadOverlayChange: (Boolean) -> Unit = { Cfg.padOverlay = it },
) {
    Box(Modifier.fillMaxSize()) {
        if (state.padOverlay) {
            ControllerOverlay(
                pad = pad, deck = state.padIdentity == "deck", onKeyboard = { state.pcKeyboard = !state.pcKeyboard },
                reloadKey = state.padLayoutRev, sceneSize = state.sceneW to state.sceneH,
            )
        }
        // petunjuk tipis di tepi kiri: dari sinilah menu digeser keluar
        if (!state.menuOpen) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 2.dp)
                    .width(4.dp).height(56.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
        }
        // controller layar (OnScreenControls) memakan semua sentuhan di atas SurfaceView, jadi handler geser tepi di
        // DisplayActivity tidak pernah menerimanya. Selama controller aktif, pita tepi kiri ini mengambil alih:
        // geser ke kanan dari tepi = buka menu, sama seperti saat controller mati.
        if (state.padOverlay && !state.menuOpen) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE_ZONE_DP.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = EDGE_SWIPE_DP.dp.toPx()
                            var opened = false
                            do {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = c.position.x - down.position.x
                                val dy = abs(c.position.y - down.position.y)
                                if (!opened && dx > slop && dx > dy * 1.2f) {
                                    opened = true
                                    state.menuOpen = true
                                }
                                c.consume()
                            } while (ev.changes.any { it.pressed })
                        }
                    },
            )
        }
        if (state.pcKeyboard) {
            PcKeyboard(sendKey = sendKey, onClose = { state.pcKeyboard = false })
        }
        SessionDrawer(
            state = state,
            onBackToTerminal = onClose,
            onShutdown = onShutdown,
            onPadOverlayChange = onPadOverlayChange,
        )
        // paling atas: progres menunggu proot menyala / menunggu semua proses mati (menutup layar dan menelan sentuhan)
        val phase = Launcher.appPhase
        if ((state.appMode && phase == Launcher.AppPhase.STARTING) || phase == Launcher.AppPhase.STOPPING) {
            AppProgressOverlay(stopping = phase == Launcher.AppPhase.STOPPING, stage = Launcher.appStage)
        }
    }
}

/** Samakan dengan EDGE_ZONE_DP / EDGE_SWIPE_DP di DisplayActivity (tepi kiri tempat geseran menu dimulai). */
private const val EDGE_ZONE_DP = 28f
private const val EDGE_SWIPE_DP = 36f
