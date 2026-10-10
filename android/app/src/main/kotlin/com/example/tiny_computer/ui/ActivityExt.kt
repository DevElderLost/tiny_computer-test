package com.example.tiny_computer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Membuka bungkus ContextWrapper (Compose) sampai ketemu Activity-nya. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
