package com.example.tiny_computer.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager

/**
 * Status izin yang diminta layar awal (OnboardingActivity). Penyimpanan dan mikrofon wajib,
 * pengecualian baterai opsional. Logika penyimpanan sama dengan yang dulu ada di MainActivity:
 * di bawah Android 13 cukup izin READ/WRITE_EXTERNAL_STORAGE, mulai Android 13 harus "akses semua file".
 */
object AppPermissions {
    fun storageGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return Build.VERSION.SDK_INT < 33 &&
            ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun micGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun batteryIgnored(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun requiredGranted(ctx: Context): Boolean = storageGranted(ctx) && micGranted(ctx)
}
