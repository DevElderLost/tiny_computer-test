package com.example.tiny_computer.ui

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Menjaga bilah sistem (status bar + tombol navigasi Android) tetap tersembunyi di jendela dialog / dropdown.
 *
 * MainActivity menyembunyikan bilah sistem hanya untuk jendela Activity-nya. Dialog dan popup Compose (AlertDialog,
 * Dialog, DropdownMenu) adalah jendela Android terpisah yang mengambil fokus, dan sistem memakai pengaturan bilah
 * milik jendela yang sedang fokus, jadi tombol navigasi muncul lagi dan tidak mau hilang. Panggil fungsi ini di dalam
 * isi dialog/dropdown: jendela itu ikut diminta menyembunyikan bilah dengan perilaku transient (muncul sebentar hanya
 * saat digeser dari tepi layar, seperti perilaku bawaan perangkat), sebelum jendela mendapat fokus.
 */
@Composable
fun ImmersiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        hideSystemBarsOfWindow(view)
        onDispose { }
    }
}

/** Dialog: Window milik DialogWindowProvider (induk view). Popup tidak punya Window, jadi memakai controller view-nya. */
private fun hideSystemBarsOfWindow(view: View) {
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    if (dialogWindow != null) {
        WindowInsetsControllerCompat(dialogWindow, dialogWindow.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        return
    }
    if (Build.VERSION.SDK_INT >= 30) {
        view.windowInsetsController?.let {
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(android.view.WindowInsets.Type.systemBars())
        }
    } else {
        @Suppress("DEPRECATION")
        view.rootView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
