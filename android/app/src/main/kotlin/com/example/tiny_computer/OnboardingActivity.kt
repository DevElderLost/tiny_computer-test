package com.example.tiny_computer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.core.AppPermissions
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.ui.OnboardingScreen
import com.example.tiny_computer.ui.TinyTheme

/**
 * Layar pertama kali dibuka: info pemasangan dan izin (penyimpanan + mikrofon wajib, baterai opsional).
 * Tombol Next baru muncul setelah izin wajib aktif; sesudahnya MainActivity dibuka dan layar ini
 * tidak tampil lagi.
 */
class OnboardingActivity : ComponentActivity() {
    // naik setiap kembali dari dialog izin / layar pengaturan sistem, supaya status dihitung ulang
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { TinyTheme { OnboardingScreen(resumeTick) { finishOnboarding() } } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun finishOnboarding() {
        if (!AppPermissions.requiredGranted(this)) return
        Cfg.onboardingDone = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
