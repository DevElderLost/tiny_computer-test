#pragma once

namespace xorg {
class XServer;
class Drawable;

// Dipanggil NativeScreen setiap kali piksel sebuah drawable berubah: mengirim DamageNotify ke klien yang memantau
// drawable itu (compositor seperti xfwm4).
void NotifyDamage(XServer& server, Drawable* drawable);
}  // namespace xorg
