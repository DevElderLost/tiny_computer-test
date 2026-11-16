#pragma once
#include <string>

namespace xorg {
// Immutable startup options, initialized before the screen worker is created.
struct NativeRendererConfig {
    // Backend Vulkan tidak ikut dibawa ke Tiny Computer (butuh renderer Vulkan + LSFG/WFG milik Winlator);
    // nilai Vulkan tetap ada supaya nomor backend dari JNI sama dengan Winlator dan jatuh ke EGL.
    enum class Backend { Vulkan, EGL, DisplayX };
    Backend backend = Backend::EGL;
    bool trueDisplayX{}, performanceMode{true}, presentAtRefreshRate{true}, precisePresentation{};
    // MIT-SHM tidak diiklankan: SysV shm tidak bisa di-attach dari dalam proot (Mesa/Qt/Wine mencetak "Failed to attach to x11 shm").
    bool disableShm{};
    // DRI3 tidak diiklankan: transport DRI3 server ini memakai AHardwareBuffer khas Winlator, bukan dmabuf, sehingga Mesa biasa
    // di guest tidak bisa memakainya. Tanpa DRI3 Mesa/Zink memakai jalur WSI software (GPU merender, hasilnya dikirim lewat PutImage).
    bool disableDri3{};
    std::string driverPath, libraryName, nativeLibDir;
    bool swapRB = false;
    int presentMode = 2; // VK_PRESENT_MODE_FIFO_KHR
};
}
