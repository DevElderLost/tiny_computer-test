package com.example.tiny_computer

import android.system.Os.setenv

import android.content.Intent
import androidx.annotation.NonNull
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors


class MainActivity: FlutterActivity() {

    // Thread pool khusus untuk operasi AudioStream (start/stop) yang bisa
    // memblokir (mis. join() dengan timeout di stopStreaming()).
    // JANGAN pernah panggil AudioStream.startStreaming/stopStreaming langsung
    // di MethodChannel handler karena handler itu jalan di main/UI thread
    // Flutter — kalau native accept()/send() macet, UI thread ikut macet,
    // berujung ANR lalu proses di-kill signal 9 oleh Android, yang memutus
    // koneksi socket X11 punya termux-x11 di dalam container (tampilan
    // xserver disconnect / "crash").
    private val audioExecutor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "android").setMethodCallHandler {
            // 注册通道并设置方法调用处理器
            call, result ->
            // 判断方法名
            when (call.method) {
                "launchSignal9Page" -> {
                    startActivity(Intent(this, Signal9Activity::class.java))
                    result.success(0)
                }
                "getNativeLibraryPath" -> {
                    result.success(getApplicationInfo().nativeLibraryDir)
                }
                "startStreaming" -> {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("ARG_ERROR", "path is required", null)
                    } else {
                        audioExecutor.execute {
                            AudioStream.startStreaming(path)
                        }
                        // startStreaming sendiri sudah async (spawn thread sendiri),
                        // jadi cukup ack langsung supaya Dart side tidak menunggu.
                        runOnUiThread { result.success(0) }
                    }
                }
                "stopStreaming" -> {
                    audioExecutor.execute {
                        // Ini yang tadinya blocking main thread (join() tanpa
                        // timeout). Sekarang dijalankan di background thread
                        // terpisah supaya UI/rendering (termasuk surface yang
                        // menampilkan Termux:X11) tidak pernah ikut freeze.
                        AudioStream.stopStreaming()
                        runOnUiThread { result.success(0) }
                    }
                }
                else -> {
                    // 不支持的方法名
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        audioExecutor.shutdownNow()
        super.onDestroy()
    }

}
