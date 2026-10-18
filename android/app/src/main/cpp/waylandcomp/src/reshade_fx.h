#ifndef RESHADE_FX_H
#define RESHADE_FX_H
/*
 * ReShade FX (.fx) / vkBasalt-style effect for the embedded compositor's screen-effect chain.
 *
 * The shader is compiled by the vendored reshadefx front end (reshadefx/, the same copy vkBasalt
 * ships: preprocessor + parser + SPIR-V codegen) on a worker thread, then turned into Vulkan objects
 * on the compositor thread and run as one more stage of effects_chain.c (after CRT, before the
 * terminal debanding pass). Switching shaders while the session runs is therefore cheap: the old
 * shader keeps drawing until the new one is compiled and built, then it is swapped in one frame.
 *
 * Scope (same as vkBasalt's ReshadeEffect): techniques with `enabled` not false are chained, passes
 * write the back buffer or render-target textures, textures may come from files (`source`), uniforms
 * with a `source` annotation (frametime, framecount, timer, pingpong, random, date) are fed per frame,
 * the other uniforms run on their declared defaults (they become specialization constants).
 * Not supported: stencil, depth (DEPTH reads the colour image), sRGB sampling of the back buffer.
 */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Any thread. fx_path "" / NULL = off. include_dir = ReShade's `Shaders` folder (for #include),
 * texture_dir = ReShade's `Textures` folder (for `source = "lut.png"`). Takes effect on the next frame. */
void vkp_reshade_set_shader(const char *fx_path, const char *include_dir, const char *texture_dir);
/* Any thread: the state of the last request ("off", "compiling ...", "ok: N pass(es)", "error: ..."). */
void vkp_reshade_status(char *out, int cap);
/* Any thread: 1 when a shader is requested (the chain must run so the build can start). */
int vkp_reshade_wanted(void);

/* Compositor thread. */
void vkp_reshade_bind_device(VkDevice dev, VkPhysicalDevice pd, const VkPhysicalDeviceMemoryProperties *memprops);
/* Starts a compile / adopts a finished one for the chain's size and format. Returns 1 when a built
 * shader matches (w, h, fmt) and vkp_reshade_record() will draw it. */
int vkp_reshade_prepare(int w, int h, VkFormat fmt);
/* Records the passes: reads `src_view` (SHADER_READ_ONLY_OPTIMAL, w x h) and writes `dst_view` (a
 * colour target of the same size and format, any layout; left SHADER_READ_ONLY_OPTIMAL). */
void vkp_reshade_record(VkCommandBuffer cmd, VkImageView src_view, VkImageView dst_view, int w, int h);
void vkp_reshade_destroy(void);

#ifdef __cplusplus
}
#endif
#endif
