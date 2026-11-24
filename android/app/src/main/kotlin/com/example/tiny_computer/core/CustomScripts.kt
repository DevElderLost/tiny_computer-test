package com.example.tiny_computer.core

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skrip buatan pengguna (mis. untuk memasang sesuatu) yang tampil di Beranda di bawah grup Open desktop, seperti
 * Quick commands di Tiny Container master: tiap skrip punya nama, deskripsi, dan isi skrip; tombol Run
 * menjalankannya di terminal kontainer (tab Terminal), dan ikon + membuka dialog untuk menambah skrip baru.
 *
 * Daftar disimpan sebagai JSON di [TinyPrefs] (kunci [KEY]). Saat dijalankan, isi skrip ditulis ke
 * `~/.local/share/custom-scripts/<id>.sh` di dalam rootfs kontainer lalu dijalankan dengan `bash`, sama seperti
 * skrip pasang AppImage: terminal hanya menerima satu baris pendek, jadi skrip panjang/banyak baris aman dan
 * stdin tetap terminal (apt, sudo, dan perintah interaktif lain bisa meminta masukan).
 */
object CustomScripts {
    data class Item(val id: String, val name: String, val description: String, val script: String)

    private const val KEY = "customScripts"
    private const val GUEST_DIR = "/home/tiny/.local/share/custom-scripts"

    /** Daftar yang diamati Compose; diisi dari [TinyPrefs] sekali, saat objek ini pertama dipakai. */
    val items = mutableStateListOf<Item>()

    /**
     * Id skrip yang sedang berjalan di terminal. TinyApp menunggu penanda selesai ([isDone]) lalu menutup tampilan
     * terminal, mematikan shell-nya, dan kembali ke Beranda (kartu Open desktop). Bila skrip gagal, penanda tidak
     * ditulis sehingga terminal tetap terbuka dan galatnya bisa dibaca.
     */
    var watching by mutableStateOf<String?>(null)
        private set

    fun stopWatching(id: String) { if (watching == id) watching = null }

    /** Skrip [id] selesai tanpa galat (penanda `<id>.done` ditulis shell setelah `bash <id>.sh` berkode keluar 0). */
    fun isDone(id: String) = File(hostDir(), "$id.done").exists()

    init { load() }

    private fun load() {
        val raw = TinyPrefs.getString(KEY, "")
        if (raw.isBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (!validId(id)) continue
                items.add(Item(id, o.optString("name"), o.optString("description"), o.optString("script")))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("description", it.description).put("script", it.script))
        }
        TinyPrefs.setString(KEY, arr.toString())
    }

    private fun validId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() }

    fun add(name: String, description: String, script: String) {
        var id: String
        do { id = "cs" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (0..35).random().toString(36) } while (items.any { it.id == id })
        items.add(Item(id, name.trim(), description.trim(), script))
        save()
    }

    fun update(id: String, name: String, description: String, script: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = Item(id, name.trim(), description.trim(), script)
        save()
    }

    fun remove(id: String) {
        if (items.removeAll { it.id == id }) save()
        runCatching { File(hostDir(), "$id.sh").delete() }
        runCatching { File(hostDir(), "$id.done").delete() }
    }

    private fun hostDir() = File(TinyEnv.containerDir(), "home/tiny/.local/share/custom-scripts")

    /**
     * Menulis skrip ke rootfs kontainer dan mengembalikan baris yang diketik ke terminal, atau null bila sistem
     * belum terpasang (rootfs belum ada). Dipanggil di thread utama; ukuran skrip kecil.
     */
    fun prepare(item: Item): String? {
        if (!Startup.installed || !File(TinyEnv.containerDir(), "home/tiny").isDirectory) return null
        val dir = hostDir()
        dir.mkdirs()
        val file = File(dir, "${item.id}.sh")
        file.writeText(item.script.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n")
        file.setReadable(true, false)
        file.setExecutable(true, false)
        // penanda selesai dari proses sebelumnya dihapus supaya tidak dianggap selesai lebih awal
        File(dir, "${item.id}.done").delete()
        // penanda ditulis hanya bila skrip berakhir sukses (&&): gagal -> terminal tetap terbuka agar galatnya terbaca
        return "clear; bash $GUEST_DIR/${item.id}.sh && : > $GUEST_DIR/${item.id}.done"
    }

    /**
     * Run: jalankan [item] di terminal. Kontainer yang sudah berjalan langsung menerima perintah dan tab Terminal
     * dibuka lewat [onShowTerminal]; bila belum, kontainer dinyalakan dulu ([Startup.beginForTerminal]) dan
     * perintahnya ditulis begitu siap (TinyApp lalu membuka tab Terminal). Setelah skrip selesai tanpa galat,
     * TinyApp menutup terminal dan kembali ke Beranda ([watching]).
     */
    fun run(ctx: Context, item: Item, onShowTerminal: () -> Unit) {
        val command = runCatching { prepare(item) }.getOrElse {
            Toast.makeText(ctx, ctx.getString(R.string.tc_cs_write_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            return
        }
        if (command == null) {
            Toast.makeText(ctx, R.string.tc_cs_no_system, Toast.LENGTH_LONG).show()
            return
        }
        watching = item.id
        if (Startup.phase == Startup.Phase.DONE && Launcher.isStarted) {
            Launcher.runInTerminal(ctx, command)
            onShowTerminal()
        } else {
            Startup.beginForTerminal(ctx, command)
        }
        Toast.makeText(ctx, ctx.getString(R.string.tc_cs_running, item.name), Toast.LENGTH_SHORT).show()
    }
}
