// Pabrik renderer X++ untuk Tiny Computer.
//
// Di Winlator file ini juga berisi LudashiRenderer (backend Vulkan) yang memakai VulkanRendererContext,
// LSFG dan WFG. Itu tidak dibawa ke sini: Tiny Computer hanya memakai backend EGL dan DisplayX,
// yang berdiri sendiri (native_egl_renderer.cpp dan native_displayx_renderer.cpp).
// Nomor backend dari JNI: 0 = Vulkan (tidak tersedia, jatuh ke EGL), 1 = EGL, 2 = DisplayX.
#include "native_screen.h"

namespace xorg {
std::unique_ptr<NativeRenderer> CreateNativeEGLRenderer(u16, u16, const NativeRendererConfig&);
std::unique_ptr<NativeRenderer> CreateNativeDisplayXRenderer(u16, u16, const NativeRendererConfig&);

std::unique_ptr<NativeRenderer> CreateNativeRenderer(u16 w, u16 h, bool /*validation*/, const NativeRendererConfig& config) {
    if (config.backend == NativeRendererConfig::Backend::DisplayX) return CreateNativeDisplayXRenderer(w, h, config);
    return CreateNativeEGLRenderer(w, h, config);
}
}
