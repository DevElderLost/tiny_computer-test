package com.example.tiny_computer.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Permintaan pindah tab dari activity lain ke [com.example.tiny_computer.ui.TinyApp]. Layar desktop
 * ([com.example.tiny_computer.display.DisplayActivity]) menyalakan [terminal] lalu menutup dirinya; TinyApp
 * membacanya begitu tampil lagi dan langsung membuka tab Terminal (bukan tab terakhir yang dibuka).
 */
object NavRequest {
    var terminal by mutableStateOf(false)
}
