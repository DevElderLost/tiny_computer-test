package com.example.tiny_computer

import android.app.Application
import android.content.Context
import com.example.tiny_computer.core.TinyEnv
import com.example.tiny_computer.core.TinyPrefs
import me.weishu.reflection.Reflection

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        TinyEnv.init(this)
        TinyPrefs.init(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Reflection.unseal(base)
    }
}
