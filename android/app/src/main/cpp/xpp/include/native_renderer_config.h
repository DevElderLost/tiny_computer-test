#pragma once
#include <string>

namespace xorg {
// Immutable startup options, initialized before the screen worker is created.
struct NativeRendererConfig {
    // Vulkan = VulkanRendererContext dari Winlator (xpp/vulkan/), tanpa mesin frame generation (stub).
    // Nomor backend dari JNI sama dengan Winlator: 0 Vulkan, 1 EGL, 2 DisplayX.
    enum class Backend { Vulkan, EGL, DisplayX };
    Backend backend = Backend::EGL;
    bool trueDisplayX{}, performanceMode{true}, presentAtRefreshRate{true}, precisePresentation{};
    // MIT-SHM tidak diiklankan: SysV shm tidak bisa di-attach dari dalam proot (Mesa/Qt/Wine mencetak "Failed to attach to x11 shm").
    bool disableShm{};
    // DRI3 tidak diiklankan: transport DRI3 server ini memakai AHardwareBuffer khas Winlator, bukan dmabuf, sehingga Mesa biasa
    // di guest tidak bisa memakainya. Tanpa DRI3 Mesa/Zink memakai jalur WSI software (GPU merender, hasilnya dikirim lewat PutImage).
    bool disableDri3{};
    // RENDER tidak diiklankan: server ini hanya mengimplementasikan subset kursor (CreatePicture/CreateCursor/CreateAnimCursor);
    // Composite, FillRectangles, SetPictureFilter, glyph, trapezoid, dan A8 tidak ada. cairo-xlib (GTK3: xfwm4, xfce4-panel,
    // xfce4-terminal, xfdesktop) memilih jalur RENDER begitu ekstensi ada, lalu menerima BadImplementation yang membuat GDK
    // memanggil exit(1). Tanpa window manager, panel, dan desktop layar tinggal hitam. Tanpa RENDER cairo jatuh ke compositor
    // core (XFillRectangle/XPutImage/XCopyArea) yang didukung penuh; kursor ARGB libXcursor menjadi kursor core 1-bit.
    bool disableRender{};
    // Composite tidak diiklankan: hanya sebagian kecil requestnya yang ada (RedirectSubwindows dan lainnya tidak), sedangkan
    // compositor xfwm4 memanggilnya lalu mati dengan BadImplementation. Tanpa Composite xfwm4 berjalan tanpa compositing.
    bool disableComposite{};
    // XKEYBOARD tidak diiklankan (saklar darurat): klien kembali ke keyboard inti seperti sebelum ekstensi ini ada.
    bool disableXkb{};
    std::string driverPath, libraryName, nativeLibDir;
    bool swapRB = false;
    int presentMode = 2; // VK_PRESENT_MODE_FIFO_KHR
    // Jumlah gambar swapchain Vulkan (2 double, 3 triple, 4 quad; 0 = otomatis: minImageCount + 1) dan batas memori Vulkan
    // renderer (MiB; 0 = tanpa batas). Hanya backend Vulkan; dikirim lewat bit flags JNI (lihat nativeCreate).
    int bufferCount = 0, memLimitMb = 0;
};
}
