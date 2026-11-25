/*
 * VK_LAYER_TINY_displayx: WSI "DisplayX+" untuk guest glibc (aarch64) di Tiny Computer, saudara layer bionic
 * displayx_layer.so milik Winlator. Menggantikan WSI X11 Mesa (yang di X++ jatuh ke jalur software) dengan swapchain
 * yang menulis LANGSUNG ke buffer milik host Android:
 *
 *   guest  --CreateHost(id, count, window, mode, w, h)-->  host X++ (socket abstrak "displayx")
 *   guest  <--Buffers(+ N fd dma-buf, stride, modifier LINEAR)--  host (AHardwareBuffer_allocate, usage CPU_*_RARELY)
 *   guest: tiap dma-buf diimpor ke Turnip (VK_EXT_external_memory_dma_buf [+ image_drm_format_modifier]) jadi VkImage
 *   guest  --Present(index, presentId, sync_fd)-->  host;  host --Complete(index...)--> guest (buffer bebas lagi)
 *
 * Kenapa host yang mengalokasikan: AHardwareBuffer tidak ada di proses glibc. Syarat Turnip: backend kgsl harus
 * mampu mengimpor dma-buf dan semaphore/fence guest harus bisa dibuat/diekspor sebagai sync_fd. Itu DIPERIKSA saat
 * runtime; bila ada yang kurang lapisan diam (semua panggilan diteruskan) sehingga WSI bawaan tetap jalan.
 *
 * Aktif hanya untuk surface xcb/xlib yang window-nya dikenal server X++ (query Geometry ke host). Selebihnya
 * diteruskan. Env: DISPLAYX_LOG_LEVEL (0..3), DISPLAYX_PRESENT_MODE (0 IMMEDIATE, 1 MAILBOX, 2 FIFO, 3 FIFO_RELAXED),
 * DISPLAYX_DISABLE=1 mematikan lapisan, DISPLAYX_IMAGES=n memaksa jumlah buffer.
 * Dibangun oleh extra/displayx-layer/build.sh (hanya butuh header Vulkan), dipasang oleh Rootfs.ensureDisplayXLayer.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#define DX_EXPORT __attribute__((visibility("default")))
#define MAX_IMAGES 16
#define MAX_CLIENT_IMAGES 8

/* ---- protokol host (lihat displayx_protocol.hpp) ---- */
enum { OP_PRESENT = 3, OP_COMPLETE = 4, OP_RESET = 5, OP_DESTROY = 2, OP_CREATE_HOST = 7, OP_BUFFERS = 8, OP_GEOMETRY = 9, OP_GEOMETRY_REPLY = 10 };
#define DRM_FORMAT_ABGR8888 0x34324241u

/* ---- struktur surface xcb/xlib tanpa memakai header X ---- */
typedef struct { VkStructureType sType; const void *pNext; VkFlags flags; void *connection; uint32_t window; } DxXcbSurfaceCI;
typedef struct { VkStructureType sType; const void *pNext; VkFlags flags; void *dpy; unsigned long window; } DxXlibSurfaceCI;
typedef VkResult (VKAPI_PTR *PFN_dxCreateSurface)(VkInstance, const void *, const VkAllocationCallbacks *, VkSurfaceKHR *);

/* ---- sync_file ---- */
struct dx_sync_merge { char name[32]; int32_t fd2; int32_t fence; uint32_t flags; uint32_t pad; };
#define DX_SYNC_IOC_MERGE _IOWR('>', 3, struct dx_sync_merge)

/* ---- log ---- */
static int g_log = -1;
static void dlog(int level, const char *fmt, ...) {
    if (g_log < 0) { const char *e = getenv("DISPLAYX_LOG_LEVEL"); g_log = e ? atoi(e) : 1; }
    if (level > g_log) return;
    va_list ap; va_start(ap, fmt);
    fputs("[displayx] ", stderr); vfprintf(stderr, fmt, ap); fputc('\n', stderr);
    va_end(ap);
}

/* ---- dispatch per instance / device ---- */
struct pdinfo { VkPhysicalDevice pd; int ok; int drm_mod; VkImageUsageFlags usage; struct pdinfo *next; };
struct inst {
    void *key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    int api11;
    struct pdinfo *pds;
    struct inst *next;
};
struct dev {
    void *key;
    VkDevice handle;
    VkPhysicalDevice pd;
    struct inst *in;
    PFN_vkGetDeviceProcAddr gdpa;
    int active, drm_mod;
    VkImageUsageFlags usage;
    VkPhysicalDeviceMemoryProperties mem;
    PFN_vkDestroyDevice destroy;
    PFN_vkCreateSwapchainKHR n_create_sc;
    PFN_vkDestroySwapchainKHR n_destroy_sc;
    PFN_vkGetSwapchainImagesKHR n_get_images;
    PFN_vkAcquireNextImageKHR n_acquire;
    PFN_vkAcquireNextImage2KHR n_acquire2;
    PFN_vkQueuePresentKHR n_present;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkGetImageMemoryRequirements img_reqs;
    PFN_vkGetImageSubresourceLayout sub_layout;
    PFN_vkAllocateMemory alloc;
    PFN_vkFreeMemory free_;
    PFN_vkBindImageMemory bind;
    PFN_vkGetMemoryFdPropertiesKHR fd_props;
    PFN_vkGetSemaphoreFdKHR get_sem_fd;
    PFN_vkImportSemaphoreFdKHR imp_sem_fd;
    PFN_vkImportFenceFdKHR imp_fence_fd;
    struct dev *next;
};
struct surf { uint32_t window; struct surf *next; };
struct sc {
    struct dev *d;
    int sock;
    uint8_t id;
    uint32_t count, width, height;
    VkFormat format;
    VkImage image[MAX_IMAGES];
    VkDeviceMemory mem[MAX_IMAGES];
    int state[MAX_IMAGES];          /* 0 bebas, 1 dipegang aplikasi, 2 sedang di host */
    uint64_t present_id;
    int out_of_date, lost;
    pthread_mutex_t lock;
    pthread_cond_t cv;
    pthread_t reader;
    int reader_started;
    struct sc *next;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst *g_insts;
static struct dev *g_devs;
static struct surf *g_surfs;
static struct sc *g_scs;

static void *key_of(const void *h) { return *(void *const *)h; }
static struct inst *inst_find(void *key) {
    pthread_mutex_lock(&g_lock);
    struct inst *i = g_insts;
    while (i && i->key != key) i = i->next;
    pthread_mutex_unlock(&g_lock);
    return i;
}
static struct dev *dev_find(void *key) {
    pthread_mutex_lock(&g_lock);
    struct dev *d = g_devs;
    while (d && d->key != key) d = d->next;
    pthread_mutex_unlock(&g_lock);
    return d;
}
static struct surf *surf_find(VkSurfaceKHR h) {
    pthread_mutex_lock(&g_lock);
    struct surf *s = g_surfs;
    while (s && (VkSurfaceKHR)(uintptr_t)s != h) s = s->next;
    pthread_mutex_unlock(&g_lock);
    return s;
}
static struct sc *sc_find(VkSwapchainKHR h) {
    pthread_mutex_lock(&g_lock);
    struct sc *s = g_scs;
    while (s && (VkSwapchainKHR)(uintptr_t)s != h) s = s->next;
    pthread_mutex_unlock(&g_lock);
    return s;
}
static PFN_vkVoidFunction inst_fn(struct inst *i, const char *name) { return i->gipa(i->handle, name); }

/* ---- koneksi ke host ---- */
static int host_connect(void) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    memcpy(a.sun_path + 1, "displayx", 8);                     /* namespace abstrak: sun_path[0] = 0 */
    if (connect(fd, (struct sockaddr *)&a, offsetof(struct sockaddr_un, sun_path) + 9) < 0) { close(fd); return -1; }
    return fd;
}
static int send_pkt(int fd, const void *data, size_t len, const int *fds, size_t nfds) {
    struct iovec io = { (void *)data, len };
    union { struct cmsghdr align; char buf[CMSG_SPACE(sizeof(int) * 2)]; } ctl;
    struct msghdr m;
    memset(&m, 0, sizeof(m));
    m.msg_iov = &io; m.msg_iovlen = 1;
    if (nfds) {
        memset(&ctl, 0, sizeof(ctl));
        m.msg_control = ctl.buf; m.msg_controllen = CMSG_SPACE(nfds * sizeof(int));
        struct cmsghdr *h = CMSG_FIRSTHDR(&m);
        h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(nfds * sizeof(int));
        memcpy(CMSG_DATA(h), fds, nfds * sizeof(int));
    }
    ssize_t n;
    do { n = sendmsg(fd, &m, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == (ssize_t)len ? 0 : -1;
}
/* Terima satu paket; fd lampiran disimpan ke fds (maks max_fds, sisanya ditutup). Mengembalikan panjang atau -1. */
static ssize_t recv_pkt(int fd, void *buf, size_t cap, int *fds, int max_fds, int *nfds, int timeout_ms) {
    if (timeout_ms >= 0) {
        struct pollfd p = { fd, POLLIN, 0 };
        int r;
        do { r = poll(&p, 1, timeout_ms); } while (r < 0 && errno == EINTR);
        if (r <= 0) return -1;
    }
    struct iovec io = { buf, cap };
    union { struct cmsghdr align; char buf[CMSG_SPACE(sizeof(int) * MAX_IMAGES)]; } ctl;
    struct msghdr m;
    memset(&m, 0, sizeof(m));
    memset(&ctl, 0, sizeof(ctl));
    m.msg_iov = &io; m.msg_iovlen = 1;
    m.msg_control = ctl.buf; m.msg_controllen = sizeof(ctl.buf);
    ssize_t n;
    do { n = recvmsg(fd, &m, MSG_CMSG_CLOEXEC); } while (n < 0 && errno == EINTR);
    int got = 0;
    for (struct cmsghdr *h = CMSG_FIRSTHDR(&m); h; h = CMSG_NXTHDR(&m, h)) {
        if (h->cmsg_level != SOL_SOCKET || h->cmsg_type != SCM_RIGHTS) continue;
        size_t c = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t k = 0; k < c; k++) {
            int v;
            memcpy(&v, CMSG_DATA(h) + k * sizeof(int), sizeof(v));
            if (fds && got < max_fds) fds[got++] = v; else close(v);
        }
    }
    if (nfds) *nfds = got;
    if (n <= 0 || (m.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) {
        for (int k = 0; k < got; k++) close(fds[k]);
        if (nfds) *nfds = 0;
        return -1;
    }
    return n;
}
static uint32_t rd32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t rd64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static void wr32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void wr64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }

/* Ukuran window X dari server X++; -1 bila host tidak ada atau window tidak dikenal. */
static int host_geometry(uint32_t window, uint32_t *w, uint32_t *h) {
    int fd = host_connect();
    if (fd < 0) return -1;
    uint8_t req[8], rep[32];
    wr32(req, OP_GEOMETRY); wr32(req + 4, window);
    int ok = -1;
    if (send_pkt(fd, req, sizeof(req), NULL, 0) == 0) {
        ssize_t n = recv_pkt(fd, rep, sizeof(rep), NULL, 0, NULL, 1000);
        if (n == 16 && rd32(rep) == OP_GEOMETRY_REPLY && rd32(rep + 4) == window && rd32(rep + 8) && rd32(rep + 12)) {
            *w = rd32(rep + 8); *h = rd32(rep + 12); ok = 0;
        }
    }
    close(fd);
    return ok;
}

/* ---- kemampuan physical device ---- */
static int has_ext(const VkExtensionProperties *p, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++) if (!strcmp(p[i].extensionName, name)) return 1;
    return 0;
}
static VkExtensionProperties *list_dev_exts(struct inst *in, VkPhysicalDevice pd, uint32_t *n) {
    PFN_vkEnumerateDeviceExtensionProperties f = (PFN_vkEnumerateDeviceExtensionProperties)inst_fn(in, "vkEnumerateDeviceExtensionProperties");
    *n = 0;
    if (!f || f(pd, NULL, n, NULL) != VK_SUCCESS || !*n) return NULL;
    VkExtensionProperties *p = calloc(*n, sizeof(*p));
    if (!p) { *n = 0; return NULL; }
    if (f(pd, NULL, n, p) < 0) { free(p); *n = 0; return NULL; }
    return p;
}
static VkImageUsageFlags usage_of(VkFormatFeatureFlags f) {
    VkImageUsageFlags u = 0;
    if (f & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) u |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    if (f & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) u |= VK_IMAGE_USAGE_SAMPLED_BIT;
    if (f & VK_FORMAT_FEATURE_TRANSFER_SRC_BIT) u |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (f & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) u |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (f & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) u |= VK_IMAGE_USAGE_STORAGE_BIT;
    return u;
}
/* Periksa sekali per physical device apakah semua yang dibutuhkan ada; hasil di-cache di struct inst. */
static struct pdinfo *pd_probe(struct inst *in, VkPhysicalDevice pd) {
    pthread_mutex_lock(&g_lock);
    for (struct pdinfo *p = in->pds; p; p = p->next) if (p->pd == pd) { pthread_mutex_unlock(&g_lock); return p; }
    pthread_mutex_unlock(&g_lock);

    struct pdinfo *info = calloc(1, sizeof(*info));
    if (!info) return NULL;
    info->pd = pd;
    PFN_vkGetPhysicalDeviceProperties gpdp = (PFN_vkGetPhysicalDeviceProperties)inst_fn(in, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFormatProperties gpdfp = (PFN_vkGetPhysicalDeviceFormatProperties)inst_fn(in, "vkGetPhysicalDeviceFormatProperties");
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties gesp = (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)inst_fn(in, "vkGetPhysicalDeviceExternalSemaphoreProperties");
    PFN_vkGetPhysicalDeviceExternalFenceProperties gefp = (PFN_vkGetPhysicalDeviceExternalFenceProperties)inst_fn(in, "vkGetPhysicalDeviceExternalFenceProperties");
    uint32_t n;
    VkExtensionProperties *ext = list_dev_exts(in, pd, &n);
    const char *why = NULL;
    VkPhysicalDeviceProperties props;
    VkFormatProperties fp;
    if (!in->api11) why = "instance di bawah Vulkan 1.1";
    else if (!gpdp || !gpdfp || !gesp || !gefp) why = "fungsi properti eksternal tidak ada";
    else {
        gpdp(pd, &props);
        gpdfp(pd, VK_FORMAT_R8G8B8A8_UNORM, &fp);
        if (props.apiVersion < VK_API_VERSION_1_1) why = "driver di bawah Vulkan 1.1";
        else if (!ext) why = "tidak bisa membaca ekstensi device";
        else if (!has_ext(ext, n, "VK_KHR_external_memory_fd") || !has_ext(ext, n, "VK_EXT_external_memory_dma_buf"))
            why = "driver tanpa VK_EXT_external_memory_dma_buf (impor dma-buf)";
        else if (!has_ext(ext, n, "VK_KHR_external_semaphore_fd") || !has_ext(ext, n, "VK_KHR_external_fence_fd"))
            why = "driver tanpa semaphore/fence fd";
        else {
            VkPhysicalDeviceExternalSemaphoreInfo si = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO, NULL, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT };
            VkExternalSemaphoreProperties sp = { VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES };
            gesp(pd, &si, &sp);
            VkPhysicalDeviceExternalFenceInfo fi = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_FENCE_INFO, NULL, VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT };
            VkExternalFenceProperties fpr = { VK_STRUCTURE_TYPE_EXTERNAL_FENCE_PROPERTIES };
            gefp(pd, &fi, &fpr);
            const VkExternalSemaphoreFeatureFlags want_s = VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT;
            if ((sp.externalSemaphoreFeatures & want_s) != want_s) why = "semaphore sync_fd tidak bisa diekspor/diimpor";
            else if (!(fpr.externalFenceFeatures & VK_EXTERNAL_FENCE_FEATURE_IMPORTABLE_BIT)) why = "fence sync_fd tidak bisa diimpor";
            else {
                info->usage = usage_of(fp.linearTilingFeatures);
                if (!(info->usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) why = "RGBA8 linear tidak bisa jadi color attachment";
                info->drm_mod = has_ext(ext, n, "VK_EXT_image_drm_format_modifier");
            }
        }
    }
    free(ext);
    info->ok = why == NULL;
    if (why) dlog(1, "tidak aktif untuk device ini: %s (WSI bawaan dipakai)", why);
    else dlog(2, "device didukung (drm_modifier=%d usage=0x%x)", info->drm_mod, (unsigned)info->usage);
    pthread_mutex_lock(&g_lock);
    info->next = in->pds; in->pds = info;
    pthread_mutex_unlock(&g_lock);
    return info;
}

/* ===================== instance ===================== */
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL dx_GetDeviceProcAddr(VkDevice device, const char *name);
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL dx_GetInstanceProcAddr(VkInstance instance, const char *name);

static VKAPI_ATTR VkResult VKAPI_CALL dx_CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *cb, VkInstance *out) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    PFN_vkCreateInstance next_create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = next_create(ci, cb, out);
    if (r != VK_SUCCESS) return r;
    struct inst *i = calloc(1, sizeof(*i));
    if (!i) return r;
    i->key = key_of(*out);
    i->handle = *out;
    i->gipa = next_gipa;
    i->destroy = (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance");
    uint32_t api = ci->pApplicationInfo && ci->pApplicationInfo->apiVersion ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
    const char *off = getenv("DISPLAYX_DISABLE");
    i->api11 = api >= VK_API_VERSION_1_1 && !(off && atoi(off));
    pthread_mutex_lock(&g_lock);
    i->next = g_insts; g_insts = i;
    pthread_mutex_unlock(&g_lock);
    return r;
}
static VKAPI_ATTR void VKAPI_CALL dx_DestroyInstance(VkInstance instance, const VkAllocationCallbacks *cb) {
    void *key = key_of(instance);
    struct inst *i = inst_find(key);
    PFN_vkDestroyInstance next = i ? i->destroy : NULL;
    pthread_mutex_lock(&g_lock);
    for (struct inst **pp = &g_insts; *pp; pp = &(*pp)->next)
        if ((*pp)->key == key) {
            struct inst *dead = *pp; *pp = dead->next;
            while (dead->pds) { struct pdinfo *p = dead->pds; dead->pds = p->next; free(p); }
            free(dead);
            break;
        }
    pthread_mutex_unlock(&g_lock);
    if (next) next(instance, cb);
}

/* Sembunyikan ekstensi yang tidak bisa dipenuhi swapchain DisplayX (present id/wait, swapchain_maintenance1). */
static int hidden_ext(const char *n) {
    return !strcmp(n, "VK_KHR_present_id") || !strcmp(n, "VK_KHR_present_wait") || !strcmp(n, "VK_EXT_swapchain_maintenance1");
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_EnumerateDeviceExtensionProperties(VkPhysicalDevice pd, const char *layer, uint32_t *pCount, VkExtensionProperties *pProps) {
    struct inst *in = inst_find(key_of(pd));
    if (!in) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkEnumerateDeviceExtensionProperties next = (PFN_vkEnumerateDeviceExtensionProperties)inst_fn(in, "vkEnumerateDeviceExtensionProperties");
    if (!next) return VK_ERROR_INITIALIZATION_FAILED;
    if (layer) return next(pd, layer, pCount, pProps);
    struct pdinfo *info = pd_probe(in, pd);
    if (!info || !info->ok) return next(pd, layer, pCount, pProps);
    uint32_t n = 0;
    VkResult r = next(pd, NULL, &n, NULL);
    if (r != VK_SUCCESS) return r;
    VkExtensionProperties *all = calloc(n ? n : 1, sizeof(*all));
    if (!all) return VK_ERROR_OUT_OF_HOST_MEMORY;
    r = next(pd, NULL, &n, all);
    if (r < 0) { free(all); return r; }
    uint32_t kept = 0;
    for (uint32_t k = 0; k < n; k++) if (!hidden_ext(all[k].extensionName)) all[kept++] = all[k];
    if (!pProps) { *pCount = kept; free(all); return VK_SUCCESS; }
    uint32_t c = *pCount < kept ? *pCount : kept;
    memcpy(pProps, all, c * sizeof(*all));
    *pCount = c;
    free(all);
    return c < kept ? VK_INCOMPLETE : VK_SUCCESS;
}

/* ===================== device ===================== */
static VKAPI_ATTR VkResult VKAPI_CALL dx_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci, const VkAllocationCallbacks *cb, VkDevice *out) {
    VkLayerDeviceCreateInfo *chain = (VkLayerDeviceCreateInfo *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerDeviceCreateInfo *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr next_gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    struct inst *in = inst_find(key_of(pd));
    PFN_vkCreateDevice next_create = in ? (PFN_vkCreateDevice)next_gipa(in->handle, "vkCreateDevice") : NULL;
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;

    struct pdinfo *info = pd_probe(in, pd);
    int active = info && info->ok;
    VkDeviceCreateInfo mod = *ci;
    const char **names = NULL;
    if (active) {
        /* ekstensi tambahan untuk impor dma-buf + sync_fd, dan buang yang tidak bisa dipenuhi swapchain kita */
        static const char *const need[] = { "VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf",
            "VK_KHR_external_semaphore_fd", "VK_KHR_external_fence_fd", "VK_EXT_image_drm_format_modifier",
            "VK_KHR_image_format_list", "VK_KHR_external_memory", "VK_KHR_external_semaphore", "VK_KHR_external_fence" };
        uint32_t n = 0;
        VkExtensionProperties *have = list_dev_exts(in, pd, &n);
        names = calloc(ci->enabledExtensionCount + sizeof(need) / sizeof(*need), sizeof(*names));
        if (names && have) {
            uint32_t c = 0;
            for (uint32_t k = 0; k < ci->enabledExtensionCount; k++)
                if (!hidden_ext(ci->ppEnabledExtensionNames[k])) names[c++] = ci->ppEnabledExtensionNames[k];
            for (size_t k = 0; k < sizeof(need) / sizeof(*need); k++) {
                if (!has_ext(have, n, need[k])) continue;
                if (!strcmp(need[k], "VK_EXT_image_drm_format_modifier") && !info->drm_mod) continue;
                int dup = 0;
                for (uint32_t q = 0; q < c; q++) if (!strcmp(names[q], need[k])) dup = 1;
                if (!dup) names[c++] = need[k];
            }
            mod.enabledExtensionCount = c;
            mod.ppEnabledExtensionNames = names;
        } else active = 0;
        free(have);
    }
    VkResult r = next_create(pd, active ? &mod : ci, cb, out);
    free(names);
    if (r != VK_SUCCESS) return r;

    struct dev *d = calloc(1, sizeof(*d));
    if (!d) return r;
    d->key = key_of(*out);
    d->handle = *out;
    d->pd = pd;
    d->in = in;
    d->gdpa = next_gdpa;
    d->active = active;
    d->drm_mod = info ? info->drm_mod : 0;
    d->usage = info ? info->usage : 0;
#define DX_FN(field, name) d->field = (PFN_vk##name)next_gdpa(*out, "vk" #name)
    DX_FN(destroy, DestroyDevice);
    DX_FN(n_create_sc, CreateSwapchainKHR);
    DX_FN(n_destroy_sc, DestroySwapchainKHR);
    DX_FN(n_get_images, GetSwapchainImagesKHR);
    DX_FN(n_acquire, AcquireNextImageKHR);
    DX_FN(n_acquire2, AcquireNextImage2KHR);
    DX_FN(n_present, QueuePresentKHR);
    DX_FN(create_image, CreateImage);
    DX_FN(destroy_image, DestroyImage);
    DX_FN(img_reqs, GetImageMemoryRequirements);
    DX_FN(sub_layout, GetImageSubresourceLayout);
    DX_FN(alloc, AllocateMemory);
    DX_FN(free_, FreeMemory);
    DX_FN(bind, BindImageMemory);
    DX_FN(fd_props, GetMemoryFdPropertiesKHR);
    DX_FN(get_sem_fd, GetSemaphoreFdKHR);
    DX_FN(imp_sem_fd, ImportSemaphoreFdKHR);
    DX_FN(imp_fence_fd, ImportFenceFdKHR);
#undef DX_FN
    if (active && (!d->create_image || !d->alloc || !d->bind || !d->fd_props || !d->get_sem_fd || !d->imp_sem_fd || !d->imp_fence_fd)) {
        dlog(1, "fungsi device yang dibutuhkan tidak ada; lapisan tidak aktif");
        d->active = 0;
    }
    PFN_vkGetPhysicalDeviceMemoryProperties gm = (PFN_vkGetPhysicalDeviceMemoryProperties)next_gipa(in->handle, "vkGetPhysicalDeviceMemoryProperties");
    if (gm) gm(pd, &d->mem); else d->active = 0;
    pthread_mutex_lock(&g_lock);
    d->next = g_devs; g_devs = d;
    pthread_mutex_unlock(&g_lock);
    return r;
}
static VKAPI_ATTR void VKAPI_CALL dx_DestroyDevice(VkDevice device, const VkAllocationCallbacks *cb) {
    void *key = key_of(device);
    struct dev *d = dev_find(key);
    PFN_vkDestroyDevice next = d ? d->destroy : NULL;
    pthread_mutex_lock(&g_lock);
    for (struct dev **pp = &g_devs; *pp; pp = &(*pp)->next)
        if ((*pp)->key == key) { struct dev *dead = *pp; *pp = dead->next; free(dead); break; }
    pthread_mutex_unlock(&g_lock);
    if (next) next(device, cb);
}

/* ===================== surface ===================== */
static VkResult create_surface_common(VkInstance instance, const char *fn, uint32_t window, const void *ci,
                                      const VkAllocationCallbacks *cb, VkSurfaceKHR *out) {
    struct inst *in = inst_find(key_of(instance));
    if (!in) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_dxCreateSurface next = (PFN_dxCreateSurface)inst_fn(in, fn);
    uint32_t w, h;
    if (in->api11 && window && host_geometry(window, &w, &h) == 0) {
        struct surf *s = calloc(1, sizeof(*s));
        if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
        s->window = window;
        pthread_mutex_lock(&g_lock);
        s->next = g_surfs; g_surfs = s;
        pthread_mutex_unlock(&g_lock);
        *out = (VkSurfaceKHR)(uintptr_t)s;
        dlog(2, "surface DisplayX untuk window 0x%x (%ux%u)", window, w, h);
        return VK_SUCCESS;
    }
    if (!next) return VK_ERROR_EXTENSION_NOT_PRESENT;
    return next(instance, ci, cb, out);
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_CreateXcbSurfaceKHR(VkInstance instance, const void *ci, const VkAllocationCallbacks *cb, VkSurfaceKHR *out) {
    return create_surface_common(instance, "vkCreateXcbSurfaceKHR", ((const DxXcbSurfaceCI *)ci)->window, ci, cb, out);
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_CreateXlibSurfaceKHR(VkInstance instance, const void *ci, const VkAllocationCallbacks *cb, VkSurfaceKHR *out) {
    return create_surface_common(instance, "vkCreateXlibSurfaceKHR", (uint32_t)((const DxXlibSurfaceCI *)ci)->window, ci, cb, out);
}
static VKAPI_ATTR void VKAPI_CALL dx_DestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface, const VkAllocationCallbacks *cb) {
    struct surf *s = surf_find(surface);
    if (s) {
        pthread_mutex_lock(&g_lock);
        for (struct surf **pp = &g_surfs; *pp; pp = &(*pp)->next) if (*pp == s) { *pp = s->next; break; }
        pthread_mutex_unlock(&g_lock);
        free(s);
        return;
    }
    struct inst *in = inst_find(key_of(instance));
    PFN_vkDestroySurfaceKHR next = in ? (PFN_vkDestroySurfaceKHR)inst_fn(in, "vkDestroySurfaceKHR") : NULL;
    if (next) next(instance, surface, cb);
}

static int surface_formats(struct inst *in, VkPhysicalDevice pd, VkSurfaceFormatKHR *out) {
    PFN_vkGetPhysicalDeviceFormatProperties gfp = (PFN_vkGetPhysicalDeviceFormatProperties)inst_fn(in, "vkGetPhysicalDeviceFormatProperties");
    static const VkFormat fmts[2] = { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB };
    int n = 0;
    for (int k = 0; k < 2; k++) {
        VkFormatProperties fp;
        if (!gfp) break;
        gfp(pd, fmts[k], &fp);
        if (!(fp.linearTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)) continue;
        out[n].format = fmts[k];
        out[n].colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        n++;
    }
    return n;
}
/* Pola umum: surface milik kita dan device didukung -> jawab sendiri; selain itu teruskan. */
#define DX_SURFACE_PREAMBLE(PD, SURFACE, NEXTNAME, NEXTTYPE, ...)                                              \
    struct inst *in = inst_find(key_of(PD));                                                                    \
    if (!in) return VK_ERROR_INITIALIZATION_FAILED;                                                             \
    struct surf *ours = surf_find(SURFACE);                                                                     \
    if (!ours) {                                                                                                \
        NEXTTYPE next = (NEXTTYPE)inst_fn(in, NEXTNAME);                                                        \
        return next ? next(__VA_ARGS__) : VK_ERROR_EXTENSION_NOT_PRESENT;                                       \
    }                                                                                                           \
    struct pdinfo *info = pd_probe(in, PD);                                                                     \
    (void)info;

static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfaceSupportKHR(VkPhysicalDevice pd, uint32_t qf, VkSurfaceKHR surface, VkBool32 *supported) {
    DX_SURFACE_PREAMBLE(pd, surface, "vkGetPhysicalDeviceSurfaceSupportKHR", PFN_vkGetPhysicalDeviceSurfaceSupportKHR, pd, qf, surface, supported)
    *supported = info && info->ok ? VK_TRUE : VK_FALSE;
    return VK_SUCCESS;
}
static VkResult fill_caps(struct surf *s, struct pdinfo *info, VkSurfaceCapabilitiesKHR *c) {
    uint32_t w, h;
    if (host_geometry(s->window, &w, &h) != 0) return VK_ERROR_SURFACE_LOST_KHR;
    memset(c, 0, sizeof(*c));
    c->minImageCount = 2;
    c->maxImageCount = MAX_CLIENT_IMAGES;
    c->currentExtent.width = w; c->currentExtent.height = h;
    c->minImageExtent.width = 1; c->minImageExtent.height = 1;
    c->maxImageExtent.width = 16384; c->maxImageExtent.height = 16384;
    c->maxImageArrayLayers = 1;
    c->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    c->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    c->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    c->supportedUsageFlags = info ? info->usage : 0;
    return VK_SUCCESS;
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice pd, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR *caps) {
    DX_SURFACE_PREAMBLE(pd, surface, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR", PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR, pd, surface, caps)
    return fill_caps(ours, info, caps);
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfaceCapabilities2KHR(VkPhysicalDevice pd, const VkPhysicalDeviceSurfaceInfo2KHR *si, VkSurfaceCapabilities2KHR *caps) {
    DX_SURFACE_PREAMBLE(pd, si->surface, "vkGetPhysicalDeviceSurfaceCapabilities2KHR", PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR, pd, si, caps)
    return fill_caps(ours, info, &caps->surfaceCapabilities);
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice pd, VkSurfaceKHR surface, uint32_t *pCount, VkSurfaceFormatKHR *pFormats) {
    DX_SURFACE_PREAMBLE(pd, surface, "vkGetPhysicalDeviceSurfaceFormatsKHR", PFN_vkGetPhysicalDeviceSurfaceFormatsKHR, pd, surface, pCount, pFormats)
    VkSurfaceFormatKHR all[2];
    int n = surface_formats(in, pd, all);
    if (!pFormats) { *pCount = (uint32_t)n; return VK_SUCCESS; }
    uint32_t c = *pCount < (uint32_t)n ? *pCount : (uint32_t)n;
    memcpy(pFormats, all, c * sizeof(*all));
    *pCount = c;
    return c < (uint32_t)n ? VK_INCOMPLETE : VK_SUCCESS;
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfaceFormats2KHR(VkPhysicalDevice pd, const VkPhysicalDeviceSurfaceInfo2KHR *si, uint32_t *pCount, VkSurfaceFormat2KHR *pFormats) {
    DX_SURFACE_PREAMBLE(pd, si->surface, "vkGetPhysicalDeviceSurfaceFormats2KHR", PFN_vkGetPhysicalDeviceSurfaceFormats2KHR, pd, si, pCount, pFormats)
    VkSurfaceFormatKHR all[2];
    int n = surface_formats(in, pd, all);
    if (!pFormats) { *pCount = (uint32_t)n; return VK_SUCCESS; }
    uint32_t c = *pCount < (uint32_t)n ? *pCount : (uint32_t)n;
    for (uint32_t k = 0; k < c; k++) pFormats[k].surfaceFormat = all[k];
    *pCount = c;
    return c < (uint32_t)n ? VK_INCOMPLETE : VK_SUCCESS;
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_GetPhysicalDeviceSurfacePresentModesKHR(VkPhysicalDevice pd, VkSurfaceKHR surface, uint32_t *pCount, VkPresentModeKHR *pModes) {
    DX_SURFACE_PREAMBLE(pd, surface, "vkGetPhysicalDeviceSurfacePresentModesKHR", PFN_vkGetPhysicalDeviceSurfacePresentModesKHR, pd, surface, pCount, pModes)
    static const VkPresentModeKHR all[4] = { VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_FIFO_RELAXED_KHR };
    if (!pModes) { *pCount = 4; return VK_SUCCESS; }
    uint32_t c = *pCount < 4 ? *pCount : 4;
    memcpy(pModes, all, c * sizeof(*all));
    *pCount = c;
    return c < 4 ? VK_INCOMPLETE : VK_SUCCESS;
}

/* ===================== swapchain ===================== */
static void *reader_main(void *arg) {
    struct sc *s = arg;
    uint8_t buf[512];
    for (;;) {
        ssize_t n = recv_pkt(s->sock, buf, sizeof(buf), NULL, 0, NULL, -1);
        if (n < 4) {
            pthread_mutex_lock(&s->lock);
            s->lost = 1;
            pthread_cond_broadcast(&s->cv);
            pthread_mutex_unlock(&s->lock);
            return NULL;
        }
        uint32_t op = rd32(buf);
        pthread_mutex_lock(&s->lock);
        if (op == OP_COMPLETE && n >= 8) {
            uint32_t groups = rd32(buf + 4);
            size_t off = 8;
            for (uint32_t g = 0; g < groups && off + 13 <= (size_t)n; g++) {
                uint8_t id = buf[off];
                uint32_t cnt = rd32(buf + off + 9);
                off += 13;
                for (uint32_t k = 0; k < cnt && off + 4 <= (size_t)n; k++, off += 4) {
                    uint32_t idx = rd32(buf + off);
                    if (id == s->id && idx < s->count && s->state[idx] == 2) s->state[idx] = 0;
                }
            }
        } else if (op == OP_RESET) {
            s->out_of_date = 1;
        }
        pthread_cond_broadcast(&s->cv);
        pthread_mutex_unlock(&s->lock);
    }
}

static int pick_mem_type(struct dev *d, uint32_t bits) {
    for (uint32_t i = 0; i < d->mem.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (d->mem.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) return (int)i;
    for (uint32_t i = 0; i < d->mem.memoryTypeCount; i++) if (bits & (1u << i)) return (int)i;
    return -1;
}
static void sc_free_images(struct sc *s) {
    struct dev *d = s->d;
    for (uint32_t i = 0; i < s->count; i++) {
        if (s->image[i] && d->destroy_image) d->destroy_image(d->handle, s->image[i], NULL);
        if (s->mem[i] && d->free_) d->free_(d->handle, s->mem[i], NULL);
        s->image[i] = VK_NULL_HANDLE; s->mem[i] = VK_NULL_HANDLE;
    }
}
/* Impor satu dma-buf jadi VkImage. fd DIAMBIL alih bila sukses; bila gagal tetap milik pemanggil. */
static VkResult import_image(struct dev *d, const VkSwapchainCreateInfoKHR *ci, uint32_t w, uint32_t h, int fd, uint32_t stride,
                             uint32_t offset, VkImage *img, VkDeviceMemory *mem) {
    VkExternalMemoryImageCreateInfo ext = { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, NULL, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT };
    VkSubresourceLayout plane = { offset, 0, stride, 0, 0 };
    VkImageDrmFormatModifierExplicitCreateInfoEXT drm = { VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT, NULL, 0 /* LINEAR */, 1, &plane };
    VkImageFormatListCreateInfo list;
    const void *tail = NULL;
    VkImageCreateFlags flags = 0;
    if (ci->flags & VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR) {
        flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
        for (const VkBaseInStructure *p = ci->pNext; p; p = p->pNext)
            if (p->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
                list = *(const VkImageFormatListCreateInfo *)p;
                list.pNext = NULL;
                tail = &list;
                break;
            }
    }
    if (d->drm_mod) { drm.pNext = tail; ext.pNext = &drm; } else ext.pNext = tail;
    VkImageCreateInfo ic;
    memset(&ic, 0, sizeof(ic));
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.pNext = &ext;
    ic.flags = flags;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = ci->imageFormat;
    ic.extent.width = w; ic.extent.height = h; ic.extent.depth = 1;
    ic.mipLevels = 1; ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = d->drm_mod ? VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT : VK_IMAGE_TILING_LINEAR;
    ic.usage = ci->imageUsage;
    ic.sharingMode = ci->imageSharingMode;
    ic.queueFamilyIndexCount = ci->queueFamilyIndexCount;
    ic.pQueueFamilyIndices = ci->pQueueFamilyIndices;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult r = d->create_image(d->handle, &ic, NULL, img);
    if (r != VK_SUCCESS) { dlog(1, "vkCreateImage gagal (%d)", r); return r; }
    if (!d->drm_mod && d->sub_layout) {
        VkImageSubresource sr = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0 };
        VkSubresourceLayout got;
        d->sub_layout(d->handle, *img, &sr, &got);
        if (got.rowPitch != stride || got.offset != offset) {
            dlog(1, "pitch image linear driver (%llu) beda dengan buffer host (%u): tidak bisa berbagi",
                 (unsigned long long)got.rowPitch, stride);
            d->destroy_image(d->handle, *img, NULL); *img = VK_NULL_HANDLE;
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
        }
    }
    VkMemoryRequirements req;
    d->img_reqs(d->handle, *img, &req);
    VkMemoryFdPropertiesKHR fp = { VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR };
    r = d->fd_props(d->handle, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fp);
    if (r != VK_SUCCESS) { dlog(1, "vkGetMemoryFdPropertiesKHR(dma-buf) gagal (%d)", r); goto fail; }
    int type = pick_mem_type(d, req.memoryTypeBits & fp.memoryTypeBits);
    if (type < 0) { dlog(1, "tidak ada tipe memori yang cocok untuk dma-buf"); r = VK_ERROR_FORMAT_NOT_SUPPORTED; goto fail; }
    off_t fdsize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (fdsize > 0 && (uint64_t)fdsize < req.size)
        dlog(1, "dma-buf (%lld B) lebih kecil dari kebutuhan image (%llu B)", (long long)fdsize, (unsigned long long)req.size);
    VkMemoryDedicatedAllocateInfo ded = { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL, *img, VK_NULL_HANDLE };
    VkImportMemoryFdInfoKHR imp = { VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, &ded, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd };
    VkMemoryAllocateInfo ai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &imp, req.size, (uint32_t)type };
    r = d->alloc(d->handle, &ai, NULL, mem);
    if (r != VK_SUCCESS) { dlog(1, "impor dma-buf ke driver ditolak (%d)", r); goto fail; }
    r = d->bind(d->handle, *img, *mem, 0);
    if (r != VK_SUCCESS) { d->free_(d->handle, *mem, NULL); *mem = VK_NULL_HANDLE; goto fail; }
    return VK_SUCCESS;
fail:
    d->destroy_image(d->handle, *img, NULL);
    *img = VK_NULL_HANDLE;
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL dx_CreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *ci, const VkAllocationCallbacks *cb, VkSwapchainKHR *out) {
    struct dev *d = dev_find(key_of(device));
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    struct surf *surf = surf_find(ci->surface);
    if (!surf) return d->n_create_sc ? d->n_create_sc(device, ci, cb, out) : VK_ERROR_EXTENSION_NOT_PRESENT;
    if (!d->active) return VK_ERROR_INITIALIZATION_FAILED;
    if ((ci->imageFormat != VK_FORMAT_R8G8B8A8_UNORM && ci->imageFormat != VK_FORMAT_R8G8B8A8_SRGB) || ci->imageArrayLayers != 1 ||
        (ci->imageUsage & ~d->usage)) {
        dlog(1, "parameter swapchain tidak didukung (format %d usage 0x%x)", ci->imageFormat, (unsigned)ci->imageUsage);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    uint32_t count = ci->minImageCount < 2 ? 2 : ci->minImageCount;
    const char *forced = getenv("DISPLAYX_IMAGES");
    if (forced && atoi(forced) >= 2) count = (uint32_t)atoi(forced);
    if (count > MAX_CLIENT_IMAGES) count = MAX_CLIENT_IMAGES;
    uint32_t mode = ci->presentMode <= VK_PRESENT_MODE_FIFO_RELAXED_KHR ? (uint32_t)ci->presentMode : 2u;
    const char *pm = getenv("DISPLAYX_PRESENT_MODE");
    if (pm && atoi(pm) >= 0 && atoi(pm) <= 3) mode = (uint32_t)atoi(pm);

    struct sc *s = calloc(1, sizeof(*s));
    if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    s->d = d; s->sock = -1; s->id = 0; s->count = count; s->format = ci->imageFormat;
    pthread_mutex_init(&s->lock, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&s->cv, &ca);
    pthread_condattr_destroy(&ca);

    VkResult r = VK_ERROR_SURFACE_LOST_KHR;
    int fds[MAX_IMAGES], nfds = 0;
    s->sock = host_connect();
    if (s->sock < 0) { dlog(1, "tidak bisa terhubung ke host DisplayX+"); goto fail; }
    uint8_t req[29];
    wr32(req, OP_CREATE_HOST); req[4] = s->id; wr32(req + 5, count); wr32(req + 9, surf->window);
    wr32(req + 13, mode); wr32(req + 17, ci->imageExtent.width); wr32(req + 21, ci->imageExtent.height);
    wr32(req + 25, (uint32_t)ci->imageColorSpace);
    if (send_pkt(s->sock, req, sizeof(req), NULL, 0) != 0) goto fail;
    uint8_t rep[512];
    ssize_t n = recv_pkt(s->sock, rep, sizeof(rep), fds, MAX_IMAGES, &nfds, 4000);
    if (n < 25 || rd32(rep) != OP_BUFFERS) { dlog(1, "host tidak membalas CreateHost"); goto fail; }
    uint32_t got = rd32(rep + 5);
    s->width = rd32(rep + 9); s->height = rd32(rep + 13);
    if (got != count || (uint32_t)nfds != count || !s->width || !s->height || rd32(rep + 17) != DRM_FORMAT_ABGR8888 ||
        (size_t)n < 25 + (size_t)count * 24) {
        dlog(1, "host gagal mengalokasikan buffer (count=%u fd=%d)", got, nfds);
        goto fail;
    }
    if (s->width != ci->imageExtent.width || s->height != ci->imageExtent.height)
        dlog(1, "ukuran swapchain %ux%u beda dengan permintaan %ux%u; pakai ukuran host",
             s->width, s->height, ci->imageExtent.width, ci->imageExtent.height);
    r = VK_ERROR_INITIALIZATION_FAILED;
    for (uint32_t i = 0; i < count; i++) {
        const uint8_t *e = rep + 25 + (size_t)i * 24;
        if (rd64(e + 16) != 0) { dlog(1, "host melaporkan modifier non-linear; tidak didukung"); goto fail; }
        r = import_image(d, ci, s->width, s->height, fds[i], rd32(e), rd32(e + 4), &s->image[i], &s->mem[i]);
        if (r != VK_SUCCESS) goto fail;
        close(fds[i]);         /* driver sudah menduplikasi/mengambil referensi; fd lokal tidak dibutuhkan lagi */
        fds[i] = -1;
    }
    if (pthread_create(&s->reader, NULL, reader_main, s) != 0) { r = VK_ERROR_INITIALIZATION_FAILED; goto fail; }
    s->reader_started = 1;
    pthread_mutex_lock(&g_lock);
    s->next = g_scs; g_scs = s;
    pthread_mutex_unlock(&g_lock);
    *out = (VkSwapchainKHR)(uintptr_t)s;
    dlog(2, "swapchain DisplayX %p: %u buffer %ux%u mode %u", (void *)s, count, s->width, s->height, mode);
    return VK_SUCCESS;
fail:
    for (int k = 0; k < nfds; k++) if (fds[k] >= 0) close(fds[k]);
    sc_free_images(s);
    if (s->sock >= 0) close(s->sock);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->cv);
    free(s);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL dx_DestroySwapchainKHR(VkDevice device, VkSwapchainKHR sw, const VkAllocationCallbacks *cb) {
    struct dev *d = dev_find(key_of(device));
    struct sc *s = sc_find(sw);
    if (!s) { if (d && d->n_destroy_sc) d->n_destroy_sc(device, sw, cb); return; }
    pthread_mutex_lock(&g_lock);
    for (struct sc **pp = &g_scs; *pp; pp = &(*pp)->next) if (*pp == s) { *pp = s->next; break; }
    pthread_mutex_unlock(&g_lock);
    uint8_t pkt[5];
    wr32(pkt, OP_DESTROY); pkt[4] = s->id;
    send_pkt(s->sock, pkt, sizeof(pkt), NULL, 0);
    shutdown(s->sock, SHUT_RDWR);
    if (s->reader_started) pthread_join(s->reader, NULL);
    close(s->sock);
    sc_free_images(s);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->cv);
    free(s);
}

static VKAPI_ATTR VkResult VKAPI_CALL dx_GetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR sw, uint32_t *pCount, VkImage *pImages) {
    struct sc *s = sc_find(sw);
    if (!s) {
        struct dev *d = dev_find(key_of(device));
        return d && d->n_get_images ? d->n_get_images(device, sw, pCount, pImages) : VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (!pImages) { *pCount = s->count; return VK_SUCCESS; }
    uint32_t c = *pCount < s->count ? *pCount : s->count;
    for (uint32_t i = 0; i < c; i++) pImages[i] = s->image[i];
    *pCount = c;
    return c < s->count ? VK_INCOMPLETE : VK_SUCCESS;
}

static VkResult signal_acquire(struct dev *d, VkSemaphore sem, VkFence fence) {
    /* fd -1 pada impor SYNC_FD = payload sudah tersinyal: buffer memang sudah dilepas host */
    if (sem != VK_NULL_HANDLE) {
        VkImportSemaphoreFdInfoKHR ii = { VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR, NULL, sem, VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
                                          VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT, -1 };
        VkResult r = d->imp_sem_fd(d->handle, &ii);
        if (r != VK_SUCCESS) return r;
    }
    if (fence != VK_NULL_HANDLE) {
        VkImportFenceFdInfoKHR fi = { VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR, NULL, fence, VK_FENCE_IMPORT_TEMPORARY_BIT,
                                      VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT, -1 };
        VkResult r = d->imp_fence_fd(d->handle, &fi);
        if (r != VK_SUCCESS) return r;
    }
    return VK_SUCCESS;
}
static VkResult acquire_ours(struct dev *d, struct sc *s, uint64_t timeout, VkSemaphore sem, VkFence fence, uint32_t *index) {
    struct timespec until;
    if (timeout != 0 && timeout != UINT64_MAX) {
        clock_gettime(CLOCK_MONOTONIC, &until);
        until.tv_sec += (time_t)(timeout / 1000000000ull);
        until.tv_nsec += (long)(timeout % 1000000000ull);
        if (until.tv_nsec >= 1000000000L) { until.tv_sec++; until.tv_nsec -= 1000000000L; }
    }
    pthread_mutex_lock(&s->lock);
    for (;;) {
        if (s->lost) { pthread_mutex_unlock(&s->lock); return VK_ERROR_SURFACE_LOST_KHR; }
        if (s->out_of_date) { pthread_mutex_unlock(&s->lock); return VK_ERROR_OUT_OF_DATE_KHR; }
        for (uint32_t i = 0; i < s->count; i++)
            if (s->state[i] == 0) {
                s->state[i] = 1;
                pthread_mutex_unlock(&s->lock);
                VkResult r = signal_acquire(d, sem, fence);
                if (r != VK_SUCCESS) {
                    pthread_mutex_lock(&s->lock); s->state[i] = 0; pthread_mutex_unlock(&s->lock);
                    return r;
                }
                *index = i;
                return VK_SUCCESS;
            }
        if (timeout == 0) { pthread_mutex_unlock(&s->lock); return VK_NOT_READY; }
        if (timeout == UINT64_MAX) pthread_cond_wait(&s->cv, &s->lock);
        else if (pthread_cond_timedwait(&s->cv, &s->lock, &until) == ETIMEDOUT) { pthread_mutex_unlock(&s->lock); return VK_TIMEOUT; }
    }
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_AcquireNextImageKHR(VkDevice device, VkSwapchainKHR sw, uint64_t timeout, VkSemaphore sem, VkFence fence, uint32_t *index) {
    struct dev *d = dev_find(key_of(device));
    struct sc *s = sc_find(sw);
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    if (!s) return d->n_acquire ? d->n_acquire(device, sw, timeout, sem, fence, index) : VK_ERROR_EXTENSION_NOT_PRESENT;
    return acquire_ours(d, s, timeout, sem, fence, index);
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_AcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR *info, uint32_t *index) {
    struct dev *d = dev_find(key_of(device));
    struct sc *s = sc_find(info->swapchain);
    if (!d) return VK_ERROR_INITIALIZATION_FAILED;
    if (!s) return d->n_acquire2 ? d->n_acquire2(device, info, index) : VK_ERROR_EXTENSION_NOT_PRESENT;
    return acquire_ours(d, s, info->timeout, info->semaphore, info->fence, index);
}

static int sync_merge(int a, int b) {
    struct dx_sync_merge m;
    memset(&m, 0, sizeof(m));
    strncpy(m.name, "displayx", sizeof(m.name) - 1);
    m.fd2 = b;
    return ioctl(a, DX_SYNC_IOC_MERGE, &m) < 0 ? -1 : m.fence;
}
/* Ekspor semua semaphore tunggu jadi SATU sync_fd (-1 = sudah tersinyal semua). */
static VkResult export_waits(struct dev *d, const VkSemaphore *sems, uint32_t n, int *fence) {
    *fence = -1;
    for (uint32_t i = 0; i < n; i++) {
        VkSemaphoreGetFdInfoKHR gi = { VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR, NULL, sems[i], VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT };
        int fd = -1;
        VkResult r = d->get_sem_fd(d->handle, &gi, &fd);
        if (r != VK_SUCCESS) { if (*fence >= 0) close(*fence); *fence = -1; return r; }
        if (fd < 0) continue;
        if (*fence < 0) { *fence = fd; continue; }
        int merged = sync_merge(*fence, fd);
        close(*fence); close(fd);
        if (merged < 0) { *fence = -1; return VK_ERROR_DEVICE_LOST; }
        *fence = merged;
    }
    return VK_SUCCESS;
}
static VkResult present_one(struct sc *s, uint32_t index, int fence) {
    pthread_mutex_lock(&s->lock);
    if (s->lost) { pthread_mutex_unlock(&s->lock); if (fence >= 0) close(fence); return VK_ERROR_SURFACE_LOST_KHR; }
    if (index >= s->count || s->state[index] != 1) { pthread_mutex_unlock(&s->lock); if (fence >= 0) close(fence); return VK_ERROR_OUT_OF_DATE_KHR; }
    s->state[index] = 2;
    uint64_t pid = ++s->present_id;
    int ood = s->out_of_date;
    pthread_mutex_unlock(&s->lock);
    uint8_t pkt[17];
    wr32(pkt, OP_PRESENT); pkt[4] = s->id; wr32(pkt + 5, index); wr64(pkt + 9, pid);
    int ok = send_pkt(s->sock, pkt, sizeof(pkt), fence >= 0 ? &fence : NULL, fence >= 0 ? 1 : 0);
    if (fence >= 0) close(fence);
    if (ok != 0) {
        pthread_mutex_lock(&s->lock); s->lost = 1; s->state[index] = 0; pthread_cond_broadcast(&s->cv); pthread_mutex_unlock(&s->lock);
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    return ood ? VK_SUBOPTIMAL_KHR : VK_SUCCESS;
}
static VKAPI_ATTR VkResult VKAPI_CALL dx_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pi) {
    struct dev *d = dev_find(key_of(queue));
    if (!d || !d->n_present) return VK_ERROR_INITIALIZATION_FAILED;
    uint32_t ours = 0;
    for (uint32_t i = 0; i < pi->swapchainCount; i++) if (sc_find(pi->pSwapchains[i])) ours++;
    if (!ours) return d->n_present(queue, pi);

    VkResult overall = VK_SUCCESS;
    int fence = -1;
    VkResult wr = export_waits(d, pi->pWaitSemaphores, pi->waitSemaphoreCount, &fence);
    for (uint32_t i = 0; i < pi->swapchainCount; i++) {
        struct sc *s = sc_find(pi->pSwapchains[i]);
        if (!s) continue;
        VkResult r;
        if (wr != VK_SUCCESS) r = wr;
        else {
            int own = fence >= 0 ? dup(fence) : -1;             /* tiap swapchain mendapat salinan sendiri */
            r = present_one(s, pi->pImageIndices[i], own);
        }
        if (pi->pResults) pi->pResults[i] = r;
        if (r < 0 && overall >= 0) overall = r;
        else if (r == VK_SUBOPTIMAL_KHR && overall == VK_SUCCESS) overall = r;
    }
    if (fence >= 0) close(fence);
    if (ours < pi->swapchainCount) {
        /* Campuran swapchain DisplayX dan WSI bawaan: semaphore tunggu sudah dikonsumsi di atas, jadi swapchain lain
         * dipresentasikan tanpa menunggu. Kasus ini praktis tidak pernah terjadi (satu aplikasi, satu jendela). */
        VkSwapchainKHR *sw = malloc(pi->swapchainCount * sizeof(*sw));
        uint32_t *idx = malloc(pi->swapchainCount * sizeof(*idx));
        VkResult *res = malloc(pi->swapchainCount * sizeof(*res));
        uint32_t n = 0;
        if (sw && idx && res)
            for (uint32_t i = 0; i < pi->swapchainCount; i++)
                if (!sc_find(pi->pSwapchains[i])) { sw[n] = pi->pSwapchains[i]; idx[n] = pi->pImageIndices[i]; n++; }
        if (sw && idx && res && n) {
            VkPresentInfoKHR rest = *pi;
            rest.waitSemaphoreCount = 0; rest.pWaitSemaphores = NULL;
            rest.swapchainCount = n; rest.pSwapchains = sw; rest.pImageIndices = idx; rest.pResults = res;
            rest.pNext = NULL;
            VkResult r = d->n_present(queue, &rest);
            if (r < 0 && overall >= 0) overall = r;
            if (pi->pResults) {
                uint32_t k = 0;
                for (uint32_t i = 0; i < pi->swapchainCount; i++) if (!sc_find(pi->pSwapchains[i])) pi->pResults[i] = res[k++];
            }
        }
        free(sw); free(idx); free(res);
    }
    return overall;
}

/* ===================== penyelesaian nama ===================== */
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL dx_GetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)dx_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)dx_DestroyDevice;
    if (!strcmp(name, "vkCreateSwapchainKHR")) return (PFN_vkVoidFunction)dx_CreateSwapchainKHR;
    if (!strcmp(name, "vkDestroySwapchainKHR")) return (PFN_vkVoidFunction)dx_DestroySwapchainKHR;
    if (!strcmp(name, "vkGetSwapchainImagesKHR")) return (PFN_vkVoidFunction)dx_GetSwapchainImagesKHR;
    if (!strcmp(name, "vkAcquireNextImageKHR")) return (PFN_vkVoidFunction)dx_AcquireNextImageKHR;
    if (!strcmp(name, "vkAcquireNextImage2KHR")) return (PFN_vkVoidFunction)dx_AcquireNextImage2KHR;
    if (!strcmp(name, "vkQueuePresentKHR")) return (PFN_vkVoidFunction)dx_QueuePresentKHR;
    struct dev *d = dev_find(key_of(device));
    return d && d->gdpa ? d->gdpa(device, name) : NULL;
}
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL dx_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)dx_GetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)dx_CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)dx_DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)dx_CreateDevice;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)dx_GetDeviceProcAddr;
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) return (PFN_vkVoidFunction)dx_EnumerateDeviceExtensionProperties;
    if (!strcmp(name, "vkCreateXcbSurfaceKHR")) return (PFN_vkVoidFunction)dx_CreateXcbSurfaceKHR;
    if (!strcmp(name, "vkCreateXlibSurfaceKHR")) return (PFN_vkVoidFunction)dx_CreateXlibSurfaceKHR;
    if (!strcmp(name, "vkDestroySurfaceKHR")) return (PFN_vkVoidFunction)dx_DestroySurfaceKHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceSupportKHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfaceSupportKHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfaceCapabilitiesKHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilities2KHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfaceCapabilities2KHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceFormatsKHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfaceFormatsKHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceFormats2KHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfaceFormats2KHR;
    if (!strcmp(name, "vkGetPhysicalDeviceSurfacePresentModesKHR")) return (PFN_vkVoidFunction)dx_GetPhysicalDeviceSurfacePresentModesKHR;
    if (!instance) return NULL;
    struct inst *i = inst_find(key_of(instance));
    return i && i->gipa ? i->gipa(instance, name) : NULL;
}

DX_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (!v || v->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = dx_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = dx_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
