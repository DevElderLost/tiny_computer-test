package com.example.tiny_computer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.tiny_computer.core.AppPermissions
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.Startup
import com.example.tiny_computer.ui.TinyApp
import com.example.tiny_computer.ui.TinyTheme

/** Satu-satunya halaman utama; seluruh UI Jetpack Compose (pengganti FlutterActivity). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pertama kali dibuka: info pemasangan dan izin ada di OnboardingActivity. Pengguna lama yang sudah
        // memasang sistem dan mengaktifkan izin wajib tidak perlu melihatnya.
        if (!Cfg.onboardingDone) {
            if (Startup.installed && AppPermissions.requiredGranted(this)) {
                Cfg.onboardingDone = true
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { TinyTheme { TinyApp() } }
        hideSystemBars()
    }

    /**
     * Layar penuh: jam/baterai (status bar) dan tombol navigasi Android disembunyikan. Geser dari tepi layar
     * memunculkannya sebentar saja (transient), seperti layar desktop.
     */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // bilah sistem muncul lagi setelah dialog/papan ketik/layar lain menutup: sembunyikan ulang saat fokus kembali
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (Cfg.wakelock) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
