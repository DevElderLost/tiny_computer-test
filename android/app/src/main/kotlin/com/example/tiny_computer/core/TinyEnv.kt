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

    /**
     * Perintah boot proot untuk rootfs Linux bebas (Debian, Ubuntu, dst.) hasil import: masuk sebagai root,
     * tanpa mount khusus Tiny (tmoe-linux, /home/tiny) dan tanpa EXTRA_MOUNT/EXTRA_OPT yang merujuk ke /home/tiny.
     * Memakai `$DATA_DIR` dan `$CONTAINER_DIR` dari skrip lingkungan, seperti [defaultBoot].
     */
    val bootGeneric: String
        get() = "\$DATA_DIR/bin/proot -0 -H --pwd=/root --rootfs=\$CONTAINER_DIR --mount=/system --mount=/apex --mount=/sys " +
            "--mount=/data --kill-on-exit --mount=/storage --sysvipc -L --link2symlink --mount=/proc --mount=/dev " +
            "--mount=\$CONTAINER_DIR/tmp:/dev/shm --mount=/dev/urandom:/dev/random --mount=/proc/self/fd:/dev/fd " +
            "--mount=/proc/self/fd/0:/dev/stdin --mount=/proc/self/fd/1:/dev/stdout --mount=/proc/self/fd/2:/dev/stderr " +
            "--mount=/dev/null:/dev/tty0 --mount=/dev/null:/proc/sys/kernel/cap_last_cap --mount=/storage/self/primary:/media/sd " +
            "/usr/bin/env -i HOSTNAME=TINY HOME=/root USER=root LOGNAME=root TERM=xterm-256color TMPDIR=/tmp LANG=C.UTF-8 " +
            "SHELL=/bin/sh PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "/bin/sh -c \"if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi\""

    /**
     * Rootfs hasil ekspor Tiny Container master (tc4) tidak membawa `usr/local/etc/tmoe-linux`. Mount tiruan /proc
     * (`--mount=$CONTAINER_DIR/usr/local/etc/tmoe-linux/proot_proc/...`) pada [defaultBoot] lalu menunjuk ke berkas
     * yang tidak ada dan membuat proot gagal/menempelkan berkas kosong. Fungsi ini membuang mount itu bila folder
     * tmoe-linux tidak ada di [root]; bila ada, perintah dikembalikan apa adanya.
     */
    fun bootForRootfs(root: File, boot: String): String {
        if (File(root, "usr/local/etc/tmoe-linux/proot_proc").isDirectory) return boot
        return boot.replace(Regex("""\s--mount=\S*/usr/local/etc/tmoe-linux/proot_proc/\S+"""), "")
    }

    /** Perintah cepat untuk sistem hasil import: hanya yang pasti ada di rootfs Debian/Ubuntu tanpa sudo. */
    val genericCommands: JSONArray
        get() = JSONArray()
            .put(JSONObject().put("name", "Update Packages").put("command", "apt update && apt full-upgrade -y && apt autoremove -y"))
            .put(JSONObject().put("name", "System Info").put("command", "cat /etc/os-release"))
            .put(JSONObject().put("name", "Clear").put("command", "clear"))
            .put(JSONObject().put("name", "Interrupt").put("command", "\u0003"))

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
