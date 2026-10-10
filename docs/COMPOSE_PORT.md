# Port Compose + compositor DroidDeck

Tiny Computer tidak lagi memakai Flutter. UI sepenuhnya Jetpack Compose; layar desktop memakai
compositor Wayland dari DroidDeck (`libdroiddeckwayland.so`) dengan Xwayland di dalam rootfs Debian.

| Bagian | Dulu | Sekarang |
|---|---|---|
| UI | Flutter (`lib/*.dart`) | Compose (`ui/`, `display/`, `controller/`) |
| Layar | x11_flutter / AVNC / noVNC | `display/DisplayActivity` + compositor Wayland (C, Vulkan/Turnip) |
| Terminal | xterm + flutter_pty | Termux terminal-view; shell `/system/bin/sh` yang sama |
| Debian | proot + `D.boot` | tidak berubah (`assets/tiny/defaults.json`, `setup_bootstrap.sh`, `install_rootfs.sh` diekstrak dari `workflow.dart`) |
| Controller | tidak ada | `libfakeinput.so` (evdev Xbox 360 / hidraw Steam Deck) + overlay Compose, tanpa Steam |

Alur: `Launcher.launchCurrentContainer` menulis perintah boot proot ke terminal. Saat `DisplayActivity`
menyalakan compositor, `Launcher.launchGuiBackend` menjalankan `Xwayland :4` (rootful, fullscreen) lalu
`/etc/X11/xinit/Xsession`. Soket Wayland ada di `containers/N/tmp` (= `/tmp` di guest).

Controller: `PadSetup.prepare` menyiapkan ring evdev (`FakeInputWriter`) dan env `FAKE_EVDEV_*`;
dengan identitas "deck", `DeckSysfs` menulis sysfs/udev untuk `/dev/hidraw16`.
`extra/fakeinput/build.sh` membangun `libfakeinput.so` (aarch64 glibc) di CI ke `assets/`.

Belum dibuild/diuji di sisi pembuat patch. Lihat catatan di pesan.
