package com.droiddeck.launcher.session

import java.io.File

/**
 * Versi ringkas SessionState milik DroidDeck: hanya bagian yang dibaca CompositorHost dan
 * PadBridge (kode DroidDeck yang disalin apa adanya). Satu proses, tanpa Service terpisah.
 */
object SessionState {
    /** True selama sesi desktop (Xwayland + desktop Debian) berjalan. */
    @Volatile
    @JvmField
    var running = false

    @Volatile
    private var deckPadValue = false

    /**
     * True bila pad dipresentasikan sebagai controller Steam Deck (hidraw). PadBridge membacanya
     * lewat `SessionState.getDeckPad()` dari Java, jadi harus @JvmStatic.
     */
    @JvmStatic
    var deckPad: Boolean
        get() = deckPadValue
        set(value) {
            deckPadValue = value
        }

    /** Direktori evdev palsu (bind ke /dev/input di guest) begitu ring siap. */
    @Volatile
    var fakeInputDir: File? = null
}
