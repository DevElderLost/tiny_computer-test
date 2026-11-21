#pragma once
// Ekstensi X KeyBoard (XKEYBOARD, "XKB") versi 1.0 untuk X++.
//
// Latar belakang: toolkit modern (winit/Rust, GLFW, SDL2, GTK3/4, Qt5/6, xkbcommon-x11) memakai XKB untuk membangun keymap.
// winit 0.30 bahkan panik ("X server missing XKB extension") bila XKEYBOARD tidak ada, sehingga aplikasi seperti Craft
// Anything gagal membuka jendela. Modul ini hanya membangun bytes balasan protokol dari keymap statis (QWERTY US, evdev+8,
// sama dengan tabel keysym inti di Keyboard), sehingga tidak bergantung pada kelas server lain dan bisa diuji sendiri.
#include <functional>
#include <string>
#include <vector>
#include "base/types.h"

namespace xorg::xkb {

constexpr u16 kUseCoreKbd = 0x100;   // XkbUseCoreKbd
constexpr u16 kUseCorePtr = 0x200;   // XkbUseCorePtr
constexpr u8 kCoreKbdDeviceId = 3;   // sama dengan "Virtual core keyboard" milik XInput2 di X++

enum class Status {
    Reply,              // `bytes` berisi balasan lengkap
    NoReply,            // request tanpa balasan, sukses
    BadValue,           // galat inti BadValue, `data` = nilai
    BadLength,          // galat inti BadLength
    BadKeyboard,        // galat XKB BadKeyboard (kode = error_base ekstensi), `data` = deviceSpec
    BadImplementation,  // galat inti BadImplementation (17): request XKB sah tapi tidak didukung
    BadRequest,         // nomor request XKB tidak dikenal
};

struct Result {
    Status status{Status::NoReply};
    std::vector<u8> bytes;
    u32 data{};
};

class Keymap {
public:
    using InternFn = std::function<u32(const std::string&)>;

    // keysyms: (maxKey-minKey+1)*perKey entri, urutan keycode naik (sama dengan Keyboard::GetKeysyms()).
    Keymap(u8 minKey, u8 maxKey, u8 perKey, const std::vector<u32>& keysyms, InternFn intern);

    // `req` = seluruh request (header 4 byte: major, minor, panjang), `seq` = nomor urut request, `mods` = status
    // modifier efektif (bit Shift=1, Lock=2, Control=4, Mod1=8, Mod2=16, ...) untuk GetState.
    Result Handle(u8 minor, const u8* req, size_t len, u16 seq, u8 mods) const;

private:
    struct Key {
        u32 sym[2]{};
        u8 nSyms{};      // 0, 1 atau 2 simbol pada grup 1
        u8 type{};       // indeks tipe kunci
        u8 modmap{};     // topeng modifier nyata
        u8 action{};     // 0 = tanpa aksi, 1 = SetMods(modmap), 2 = LockMods(lockMask)
        u8 lockMask{};
        char name[4]{};
    };
    Result GetMap(const u8* r, size_t len, u16 seq) const;
    Result GetNames(const u8* r, size_t len, u16 seq) const;
    Result GetCompatMap(const u8* r, size_t len, u16 seq) const;
    Result GetIndicatorMap(const u8* r, size_t len, u16 seq) const;
    Result GetNamedIndicator(const u8* r, size_t len, u16 seq) const;
    Result GetDeviceInfo(const u8* r, size_t len, u16 seq) const;
    Result GetControls(const u8* r, size_t len, u16 seq) const;
    Result GetState(const u8* r, size_t len, u16 seq, u8 mods) const;

    u8 minKey_, maxKey_;
    std::vector<Key> keys_;  // indeks = keycode - minKey_
    struct Atoms {
        u32 keycodes{}, geometry{}, symbols{}, types{}, compat{}, group{}, devType{};
        u32 typeNames[4]{};
        u32 levelNames[4][2]{};
        u32 leds[3]{};
    } atoms_;
};

}  // namespace xorg::xkb
