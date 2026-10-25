#ifndef VK_MEMBUDGET_H
#define VK_MEMBUDGET_H
/*
 * Memory budget of the compositor's own Vulkan allocations (Settings > Memory limit).
 *
 * Every vkAllocateMemory / vkFreeMemory of the compositor goes through vkmb_allocate / vkmb_free: scene and effect
 * images, the frame-generation ring, ReShade targets, wl_shm upload images and the HDR work images. While a limit is
 * set, an allocation that would take the total above it is refused with VK_ERROR_OUT_OF_DEVICE_MEMORY - the callers
 * already treat that like any failed allocation (the effect or frame stays off, the image is not created), so the
 * compositor keeps running inside its budget instead of growing without bound.
 *
 * Not counted: memory IMPORTED from a client (dma-buf, host pointer). The client owns those pages; importing adds a
 * mapping, not a copy, and refusing an import would break the client's window.
 */
#include "vk_loader.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Limit in MiB; 0 = unlimited. Callable from any thread. */
void vkmb_set_limit_mb(int mb);
int vkmb_limit_mb(void);

VkResult vkmb_allocate(VkDevice dev, const VkMemoryAllocateInfo *info, VkDeviceMemory *out);
void vkmb_free(VkDevice dev, VkDeviceMemory mem);

/* Counted bytes in use, allocations refused because of the limit, and the biggest total seen. */
uint64_t vkmb_used_bytes(void);
unsigned vkmb_refused(void);
uint64_t vkmb_peak_bytes(void);

#ifdef __cplusplus
}
#endif
#endif
