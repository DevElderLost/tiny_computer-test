// Stub mesin frame generation untuk backend Vulkan X++ di Tiny Computer.
//
// Di Winlator VulkanRendererContext memanggil mesin LSFG (lsfg/) dan Win-FG (wfg/) yang berukuran besar dan butuh
// penerjemah DXBC. Keduanya tidak dibawa ke Tiny Computer. Stub ini membuat renderer tetap terhubung (link) dan
// melaporkan setiap mesin tidak tersedia: vkr_*_create/bind_dispatch gagal sehingga ensureFrameGenerationResources()
// mengembalikan false dan tidak ada frame yang dihasilkan. XppHost juga tidak pernah mengaktifkan frame generation.
#include "lsfg/vkr_lsfg.h"
#include "wfg/vkr_wfg.h"

extern "C" {
VkrLsfg* vkr_lsfg_create(VkDevice, VkPhysicalDevice, const char*, bool) { return nullptr; }
void vkr_lsfg_destroy(VkrLsfg*) {}
void vkr_lsfg_configure(VkrLsfg*, uint32_t, uint32_t, float, float) {}
void vkr_lsfg_set_refresh_rate(VkrLsfg*, float) {}
void vkr_lsfg_set_guest_extent(VkrLsfg*, uint32_t, uint32_t) {}
bool vkr_lsfg_needs_rebuild(const VkrLsfg*, uint32_t, uint32_t, VkFormat) { return false; }
bool vkr_lsfg_prepare(VkrLsfg*, uint32_t, uint32_t, VkFormat) { return false; }
uint32_t vkr_lsfg_plan(VkrLsfg*, uint32_t, uint64_t) { return 0; }
void vkr_lsfg_process(VkrLsfg*, VkCommandBuffer, VkImage, uint32_t, uint32_t, uint32_t) {}
void vkr_lsfg_generate_into(VkrLsfg*, VkCommandBuffer, uint32_t, uint32_t, VkImage, VkImageView, uint32_t, uint32_t) {}
void vkr_lsfg_forget_targets(VkrLsfg*) {}
void vkr_lsfg_reset(VkrLsfg*) {}
}

bool vkr_wfg_bind_dispatch(PFN_vkGetInstanceProcAddr, VkInstance) { return false; }
VkrWfg* vkr_wfg_create(VkDevice, VkPhysicalDevice) { return nullptr; }
void vkr_wfg_destroy(VkrWfg*) {}
void vkr_wfg_configure(VkrWfg*, uint32_t, uint32_t, uint32_t, float) {}
bool vkr_wfg_prepare(VkrWfg*, uint32_t, uint32_t, VkFormat) { return false; }
uint32_t vkr_wfg_plan(VkrWfg*, uint32_t, uint64_t, float) { return 0; }
bool vkr_wfg_needs_history(const VkrWfg*) { return false; }
void vkr_wfg_process(VkrWfg*, VkCommandBuffer, VkImage, uint32_t, uint32_t, uint32_t) {}
void vkr_wfg_generate_into(VkrWfg*, VkCommandBuffer, uint32_t, VkImage, VkImageView, uint32_t, uint32_t) {}
void vkr_wfg_forget_targets(VkrWfg*) {}
