package com.example.tiny_computer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dapat diklik tanpa riak (kartu pilihan sudah berubah warna saat dipilih). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** Kotak kecil berukuran [d] untuk titik status. */
fun Modifier.dot(d: Int = 10): Modifier = this.then(Modifier.size(d.dp))
