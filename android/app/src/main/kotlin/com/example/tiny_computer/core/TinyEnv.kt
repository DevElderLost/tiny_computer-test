package com.example.tiny_computer.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Jalur data dan nilai bawaan (perintah boot, perintah cepat) yang dulu ada di kelas `G` dan `D`. */
object TinyEnv {
    lateinit var app: Context
        private set

    /** Sama dengan `getApplicationSupportDirectory()` milik path_provider di Android: `filesDir`. */
    val dataPath: String get() = app.filesDir.absolutePath

    @Volatile
    var currentContainer = 0

    /** Perintah tambahan yang dijalankan sekali saat pertama masuk ke kontainer. */
    @Volatile
    var postCommand = ""

    fun init(context: Context) {
        app = context.applicationContext
    }

    val isChineseLocale: Boolean get() = Locale.getDefault().language == "zh"

    private val defaults: JSONObject by lazy {
        JSONObject(app.assets.open("tiny/defaults.json").bufferedReader().use { it.readText() })
    }

    /** Perintah boot proot mentah (versi Mandarin; dipakai apa adanya oleh Dart sebagai `D.boot`). */
    val bootZh: String get() = defaults.getString("boot")

    /** Perintah boot untuk bahasa non-Mandarin: sama seperti `replaceFirst` di `initForFirstTime`. */
    val bootEn: String
        get() = bootZh
            .replaceFirst("LANG=zh_CN.UTF-8", "LANG=en_US.UTF-8")
            .replaceFirst("公共", "Public")
            .replaceFirst("图片", "Pictures")
            .replaceFirst("音乐", "Music")
            .replaceFirst("视频", "Videos")
            .replaceFirst("下载", "Downloads")
            .replaceFirst("文档", "Documents")
            .replaceFirst("照片", "Photos")

    val defaultBoot: String get() = if (isChineseLocale) bootZh else bootEn

    val defaultCommands: JSONArray
        get() = defaults.getJSONArray(if (isChineseLocale) "commands" else "commands4En")

    val wineCommands: JSONArray
        get() = defaults.getJSONArray(if (isChineseLocale) "wineCommands" else "wineCommands4En")

    fun containerDir(index: Int = currentContainer) = File(dataPath, "containers/$index")

    /** Tempat soket Wayland: `/tmp` di dalam kontainer (XDG_RUNTIME_DIR guest = /tmp). */
    fun runtimeDir(index: Int = currentContainer) = File(containerDir(index), "tmp")

    val links = listOf(
        "projectUrl" to "https://github.com/Cateners/tiny_computer",
        "issueUrl" to "https://github.com/Cateners/tiny_computer/issues",
        "faqUrl" to "https://gitee.com/caten/tc-hints/blob/master/pool/faq.md",
        "solutionUrl" to "https://gitee.com/caten/tc-hints/blob/master/pool/solution.md",
        "discussionUrl" to "https://github.com/Cateners/tiny_computer/discussions",
    )
}
