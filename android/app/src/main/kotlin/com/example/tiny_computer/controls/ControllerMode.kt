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
     * Controller yang aktif untuk [app] (id Shortcut; null = desktop). Pilihan eksplisit sebuah Shortcut (DroidDeck, Winlator,
     * atau Tidak ada) menang untuk aplikasi itu saja; tanpa pilihan Shortcut berlaku pengaturan global: Winlator menang atas
     * DroidDeck. Hasilnya selalu tepat satu mode.
     */
    fun resolve(ctx: Context, app: String?): Active {
        when (ShortcutPrefs.controllerMode(app)) {
            ShortcutPrefs.CONTROLLER_DECK -> return Active.DECK
            ShortcutPrefs.CONTROLLER_WINLATOR -> return Active.WINLATOR
            ShortcutPrefs.CONTROLLER_NONE -> return Active.NONE
        }
        val perApp = ShortcutPrefs.padOverlay(app)   // pilihan lama (pad ON/OFF) yang belum memakai mode controller
        if (perApp == true) return Active.DECK
        if (ProfileStore.enabled(ctx)) return Active.WINLATOR
        return if (perApp ?: Cfg.padOverlay) Active.DECK else Active.NONE
    }

    /**
     * Memilih SATU controller: global ([app] = null) menulis kedua saklar sekaligus (yang lain pasti mati; [Cfg.padOverlay] ditulis
     * lebih dulu karena penulisan [ProfileStore] memicu pemuatan ulang di DisplayActivity), atau milik satu Shortcut
     * (mode controller aplikasi itu; pengaturan global tidak berubah).
     */
    fun select(ctx: Context, app: String?, mode: Active) {
        if (app == null) {
            Cfg.padOverlay = mode == Active.DECK
            ProfileStore.setEnabled(ctx, mode == Active.WINLATOR)
        } else {
            ShortcutPrefs.setController(
                app,
                when (mode) {
                    Active.DECK -> ShortcutPrefs.CONTROLLER_DECK
                    Active.WINLATOR -> ShortcutPrefs.CONTROLLER_WINLATOR
                    Active.NONE -> ShortcutPrefs.CONTROLLER_NONE
                },
            )
        }
        version++
    }

    /** Saklar controller gaya Winlator: menyalakannya memilih Winlator (DroidDeck mati); mematikannya hanya bila Winlator yang aktif. */
    fun enableWinlator(ctx: Context, on: Boolean, app: String? = null) {
        if (on) select(ctx, app, Active.WINLATOR)
        else if (resolve(ctx, app) == Active.WINLATOR) select(ctx, app, Active.NONE)
        else version++
    }

    /** Saklar controller layar DroidDeck: menyalakannya memilih DroidDeck (Winlator mati); mematikannya hanya bila DroidDeck yang aktif. */
    fun enableDeck(ctx: Context, app: String?, on: Boolean) {
        if (on) select(ctx, app, Active.DECK)
        else if (resolve(ctx, app) == Active.DECK) select(ctx, app, Active.NONE)
        else version++
    }

    /** Memberi tahu pendengar (menu sesi, DisplayActivity) bahwa pilihan profil kontrol berubah. */
    fun notifyChanged() { version++ }
}
