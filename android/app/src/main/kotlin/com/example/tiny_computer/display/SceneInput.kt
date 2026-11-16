package com.example.tiny_computer.display

import com.droiddeck.launcher.wayland.WaylandCompositor
import com.example.tiny_computer.xpp.XppHost

/**
 * Satu pintu untuk masukan sentuh/mouse/keyboard ke layar desktop. Selama layar menampilkan X++ native X server
 * ([XppHost.displayAttached]) masukan dikirim ke server itu; selain itu ke compositor Wayland seperti biasa.
 * Jenis masukan dan satuannya sama dengan `WaylandCompositor.nativeSendSceneInput` supaya pemanggil lama
 * (TouchpadInput, ControllerOverlay, DisplayActivity) cukup berganti objek.
 */
object SceneInput {
    const val SCENE_MOTION = 2
    const val SCENE_BUTTON = 3
    const val SCENE_WHEEL = 4
    /** a/b = gerak relatif dalam 1/256 piksel scene. */
    const val SCENE_RELATIVE = 6
    /** a = sumbu (0 vertikal, 1 horizontal), b = jarak dalam 1/120 takik. */
    const val SCENE_SMOOTH = 9

    fun send(kind: Int, a: Int, b: Int) {
        if (!XppHost.displayAttached) {
            WaylandCompositor.nativeSendSceneInput(kind, a, b)
            return
        }
        when (kind) {
            SCENE_MOTION -> XppHost.moveTo(a, b)
            SCENE_BUTTON -> XppHost.evdevButton(a, b != 0)
            SCENE_WHEEL -> XppHost.wheel(a)
            SCENE_RELATIVE -> XppHost.moveByFixed(a, b)
            SCENE_SMOOTH -> XppHost.smoothScroll(a, b)
        }
    }

    /** [evdev] kode tombol Linux (EvdevKeys); keycode X11 dihitung di [XppHost.key]. */
    fun key(evdev: Int, down: Boolean) {
        if (XppHost.displayAttached) XppHost.key(evdev, down)
        else WaylandCompositor.nativeSendKey(evdev, if (down) 1 else 0)
    }

    /** Posisi kursor (piksel scene) ke [out]; mengembalikan jumlah nilai yang diisi (>= 2 bila tersedia). */
    fun cursorPosition(out: IntArray): Int =
        if (XppHost.displayAttached) XppHost.cursor(out) else WaylandCompositor.nativeCursorPosition(out)
}
