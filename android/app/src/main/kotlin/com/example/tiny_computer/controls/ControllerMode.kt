package com.example.tiny_computer.controls

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.example.tiny_computer.core.Cfg
import com.example.tiny_computer.core.ShortcutPrefs

/**
 * Satu-satunya tempat yang memutuskan controller layar mana yang aktif: controller layar DroidDeck ([Cfg.padOverlay],
 * per Shortcut lewat [ShortcutPrefs.padOverlay]) ATAU controller gaya Winlator ([ProfileStore.enabled]). Keduanya tidak boleh
 * aktif bersamaan: dua View controller yang menumpuk saling menimpa sentuhan dan menggambar di atas satu sama lain.
 *
 * Aturannya dua lapis:
 *  1. Penyimpanan: menyalakan salah satu lewat [enableDeck]/[enableWinlator] otomatis mematikan yang lain.
 *  2. Saat berjalan: [resolve] selalu memberi tepat satu mode, jadi walau penyimpanan lama sempat menyimpan keduanya
 *     aktif (atau satu Shortcut sudah diset DroidDeck ON sementara Winlator global menyala) hanya satu yang tampil.
 */
object ControllerMode {
    enum class Active { NONE, DECK, WINLATOR }

    /** Naik setiap salah satu saklar berubah lewat objek ini, supaya layar pengaturan yang menampilkan keduanya ikut menyegar. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * Controller yang aktif untuk [app] (id Shortcut; null = desktop). Pilihan eksplisit sebuah Shortcut "DroidDeck ON"
     * menang atas Winlator global untuk aplikasi itu saja; selain itu Winlator menang atas DroidDeck.
     */
    fun resolve(ctx: Context, app: String?): Active {
        val perApp = ShortcutPrefs.padOverlay(app)
        if (perApp == true) return Active.DECK
        if (ProfileStore.enabled(ctx)) return Active.WINLATOR
        return if (perApp ?: Cfg.padOverlay) Active.DECK else Active.NONE
    }

    /**
     * Saklar controller gaya Winlator. Dinyalakan: controller DroidDeck global dimatikan, dan bila dipanggil dari sebuah
     * aplikasi Shortcut yang memaksa DroidDeck ON, pilihan itu diganti OFF agar Winlator benar-benar tampil di aplikasi itu.
     * [Cfg.padOverlay] ditulis lebih dulu karena penulisan [ProfileStore] memicu pemuatan ulang di DisplayActivity.
     */
    fun enableWinlator(ctx: Context, on: Boolean, app: String? = null) {
        if (on) {
            Cfg.padOverlay = false
            if (app != null && ShortcutPrefs.padOverlay(app) == true) ShortcutPrefs.setPadOverlay(app, false)
        }
        ProfileStore.setEnabled(ctx, on)
        version++
    }

    /**
     * Saklar controller layar DroidDeck: global ([app] = null, Pengaturan atau menu desktop) atau milik satu Shortcut.
     * Dinyalakan: controller gaya Winlator dimatikan, supaya saklar keduanya tidak pernah tampil aktif bersamaan.
     */
    fun enableDeck(ctx: Context, app: String?, on: Boolean) {
        if (app != null) ShortcutPrefs.setPadOverlay(app, on) else Cfg.padOverlay = on
        if (on) ProfileStore.setEnabled(ctx, false)
        version++
    }
}
