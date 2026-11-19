// Pabrik renderer X++ untuk Tiny Computer.
//
// Nomor backend dari JNI: 0 = Vulkan (native_vulkan_renderer.cpp + vulkan/), 1 = EGL, 2 = DisplayX.
// "Old Display" di Pengaturan memakai backend EGL (sama seperti Winlator Ludashi: Old Display pada X++ = EGL native).
#include "native_screen.h"

namespace xorg {
std::unique_ptr<NativeRenderer> CreateNativeEGLRenderer(u16, u16, const NativeRendererConfig&);
std::unique_ptr<NativeRenderer> CreateNativeDisplayXRenderer(u16, u16, const NativeRendererConfig&);
std::unique_ptr<NativeRenderer> CreateNativeVulkanRenderer(u16, u16, bool, const NativeRendererConfig&);

std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 w, u16 h, bool validation, const NativeRendererConfig& config) {
    if (config.backend == NativeRendererConfig::Backend::DisplayX) return CreateNativeDisplayXRenderer(w, h, config);
    if (config.backend == NativeRendererConfig::Backend::Vulkan) return CreateNativeVulkanRenderer(w, h, validation, config);
    return CreateNativeEGLRenderer(w, h, config);
}
}
