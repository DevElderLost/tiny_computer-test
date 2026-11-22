package com.example.tiny_computer.controls

/**
 * Port `inputcontrols/Binding.java` Winlator. Nama konstanta SAMA dengan Winlator supaya berkas profil `.icp`
 * (JSON) dari Winlator bisa diimpor/ekspor tanpa konversi. Beda dengan Winlator: tidak ada XKeycode; keyboard
 * langsung dalam kode evdev Linux ([evdev]) yang dikirim ke compositor lewat SceneInput.key.
 */
enum class Binding(val evdev: Int = 0) {
    NONE,
    MOUSE_LEFT_BUTTON(272), MOUSE_MIDDLE_BUTTON(274), MOUSE_RIGHT_BUTTON(273),
    MOUSE_MOVE_LEFT, MOUSE_MOVE_RIGHT, MOUSE_MOVE_UP, MOUSE_MOVE_DOWN, MOUSE_SCROLL_UP, MOUSE_SCROLL_DOWN,
    KEY_UP(103), KEY_RIGHT(106), KEY_DOWN(108), KEY_LEFT(105), KEY_ENTER(28), KEY_ESC(1), KEY_BKSP(14), KEY_DEL(111),
    KEY_TAB(15), KEY_SPACE(57), KEY_CTRL_L(29), KEY_CTRL_R(97), KEY_INSERT(110), KEY_SHIFT_L(42), KEY_SHIFT_R(54),
    KEY_ALT_L(56), KEY_ALT_R(100), KEY_HOME(102), KEY_END(107), KEY_PRTSCN(99), KEY_PG_UP(104), KEY_PG_DOWN(109),
    KEY_CAPS_LOCK(58), KEY_NUM_LOCK(69),
    KEY_0(11), KEY_1(2), KEY_2(3), KEY_3(4), KEY_4(5), KEY_5(6), KEY_6(7), KEY_7(8), KEY_8(9), KEY_9(10),
    KEY_A(30), KEY_B(48), KEY_C(46), KEY_D(32), KEY_E(18), KEY_F(33), KEY_G(34), KEY_H(35), KEY_I(23), KEY_J(36),
    KEY_K(37), KEY_L(38), KEY_M(50), KEY_N(49), KEY_O(24), KEY_P(25), KEY_Q(16), KEY_R(19), KEY_S(31), KEY_T(20),
    KEY_U(22), KEY_V(47), KEY_W(17), KEY_X(45), KEY_Y(21), KEY_Z(44),
    KEY_BRACKET_LEFT(26), KEY_BRACKET_RIGHT(27), KEY_BACKSLASH(43), KEY_SLASH(53), KEY_SEMICOLON(39), KEY_COMMA(51),
    KEY_PERIOD(52), KEY_APOSTROPHE(40), KEY_KP_ADD(78), KEY_MINUS(12), KEY_GRAVE(41),
    KEY_F1(59), KEY_F2(60), KEY_F3(61), KEY_F4(62), KEY_F5(63), KEY_F6(64), KEY_F7(65), KEY_F8(66), KEY_F9(67),
    KEY_F10(68), KEY_F11(87), KEY_F12(88),
    KEY_KP_0(82), KEY_KP_1(79), KEY_KP_2(80), KEY_KP_3(81), KEY_KP_4(75), KEY_KP_5(76), KEY_KP_6(77), KEY_KP_7(71),
    KEY_KP_8(72), KEY_KP_9(73),
    GAMEPAD_BUTTON_A, GAMEPAD_BUTTON_B, GAMEPAD_BUTTON_X, GAMEPAD_BUTTON_Y, GAMEPAD_BUTTON_L1, GAMEPAD_BUTTON_R1,
    GAMEPAD_BUTTON_SELECT, GAMEPAD_BUTTON_START, GAMEPAD_BUTTON_L3, GAMEPAD_BUTTON_R3, GAMEPAD_BUTTON_L2,
    GAMEPAD_BUTTON_R2, GAMEPAD_LEFT_THUMB_UP, GAMEPAD_LEFT_THUMB_RIGHT, GAMEPAD_LEFT_THUMB_DOWN,
    GAMEPAD_LEFT_THUMB_LEFT, GAMEPAD_RIGHT_THUMB_UP, GAMEPAD_RIGHT_THUMB_RIGHT, GAMEPAD_RIGHT_THUMB_DOWN,
    GAMEPAD_RIGHT_THUMB_LEFT, GAMEPAD_DPAD_UP, GAMEPAD_DPAD_RIGHT, GAMEPAD_DPAD_DOWN, GAMEPAD_DPAD_LEFT;

    val isMouse get() = name.startsWith("MOUSE_")
    val isKeyboard get() = name.startsWith("KEY_") || this == NONE
    val isGamepad get() = name.startsWith("GAMEPAD_")
    val isMouseMove get() = this == MOUSE_MOVE_UP || this == MOUSE_MOVE_RIGHT || this == MOUSE_MOVE_DOWN || this == MOUSE_MOVE_LEFT

    /** Tombol mouse evdev (BTN_*) atau 0 bila bukan tombol. */
    val mouseButton get() = if (this == MOUSE_LEFT_BUTTON || this == MOUSE_MIDDLE_BUTTON || this == MOUSE_RIGHT_BUTTON) evdev else 0

    /** Label pendek untuk tombol di layar dan daftar pilihan (sama dengan Binding.toString Winlator). */
    val label: String
        get() = when (this) {
            KEY_INSERT -> "INSERT"
            KEY_SHIFT_L -> "L SHIFT"; KEY_SHIFT_R -> "R SHIFT"
            KEY_CTRL_L -> "L CTRL"; KEY_CTRL_R -> "R CTRL"
            KEY_ALT_L -> "L ALT"; KEY_ALT_R -> "R ALT"
            KEY_BRACKET_LEFT -> "["; KEY_BRACKET_RIGHT -> "]"
            KEY_BACKSLASH -> "\\"; KEY_SLASH -> "/"; KEY_SEMICOLON -> ";"; KEY_COMMA -> ","
            KEY_PERIOD -> "."; KEY_APOSTROPHE -> "'"; KEY_MINUS -> "-"; KEY_KP_ADD -> "+"; KEY_GRAVE -> "`"
            else -> name.replace(Regex("^(MOUSE_|KEY_|GAMEPAD_)"), "").replace("KP_", "NUMPAD_").replace("_", " ")
        }

    companion object {
        /** Nama lama Winlator (KEY_CTRL dst.) dan nama tak dikenal tidak membuat profil gagal dibaca. */
        fun fromString(name: String): Binding = when (name) {
            "KEY_CTRL" -> KEY_CTRL_L
            "KEY_SHIFT" -> KEY_SHIFT_L
            "KEY_ALT" -> KEY_ALT_L
            else -> runCatching { valueOf(name) }.getOrDefault(NONE)
        }

        val keyboardValues: List<Binding> = entries.filter { it.isKeyboard }
        val mouseValues: List<Binding> = entries.filter { it.isMouse }
        val gamepadValues: List<Binding> = entries.filter { it.isGamepad }
    }
}
