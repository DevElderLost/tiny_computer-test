/*
 * VK_LAYER_TINY_memlimit: lapisan Vulkan untuk guest (aarch64 glibc) yang membatasi memori GPU yang boleh dipakai
 * sebuah proses. Dipasang hanya bila Pengaturan > Batas memori > "Aplikasi Vulkan guest" diisi.
 *
 *  - vkGetPhysicalDeviceMemoryProperties(2): ukuran tiap heap dipotong ke batas (dan heapBudget bila ada
 *    VK_EXT_memory_budget), sehingga game/aplikasi yang membaca ukuran heap menyesuaikan tekstur dan cache-nya.
 *  - vkAllocateMemory: total alokasi hidup di proses ini tidak boleh melewati batas; yang melewatinya dijawab
 *    VK_ERROR_OUT_OF_DEVICE_MEMORY (aplikasi yang benar menanganinya seperti kehabisan VRAM). Memori IMPOR (dma-buf,
 *    host pointer, AHB) tidak dihitung.
 *
 * Batas dibaca dari env TINY_VK_MEM_LIMIT_MB (MiB). Kosong/0 = lapisan meneruskan semuanya apa adanya.
 * Dibangun oleh extra/vkmemlimit/build.sh; Launcher mengaktifkannya lewat VK_INSTANCE_LAYERS + VK_LAYER_PATH.
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#define TINY_EXPORT __attribute__((visibility("default")))

/* ---- dispatch per instance / device, dicari lewat kunci dispatch (kata pertama handle) ---- */
struct inst {
    void *key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance destroy;
    PFN_vkGetPhysicalDeviceMemoryProperties gpdmp;
    PFN_vkGetPhysicalDeviceMemoryProperties2 gpdmp2;
    struct inst *next;
};
struct dev {
    void *key;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice destroy;
    PFN_vkAllocateMemory alloc;
    PFN_vkFreeMemory free_;
    struct dev *next;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct inst *g_insts;
static struct dev *g_devs;
static uint64_t g_limit;          /* byte; 0 = tanpa batas */
static uint64_t g_used;           /* byte yang dihitung, seluruh proses */

static void *key_of(const void *handle) { return *(void *const *)handle; }

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

/* ---- tabel ukuran alokasi (handle -> byte) supaya vkFreeMemory tahu berapa yang dilepas ---- */
struct node { uint64_t h; uint64_t size; struct node *next; };
#define NB 4096
static struct node *g_tab[NB];
static unsigned bucket(uint64_t h) { h ^= h >> 33; h *= 0xff51afd7ed558ccdULL; h ^= h >> 33; return (unsigned)(h % NB); }

static void tab_put(uint64_t h, uint64_t size) {
    struct node *n = malloc(sizeof(*n));
    if (!n) return;
    n->h = h; n->size = size;
    unsigned b = bucket(h);
    n->next = g_tab[b]; g_tab[b] = n;
}
static uint64_t tab_take(uint64_t h) {
    unsigned b = bucket(h);
    for (struct node **pp = &g_tab[b]; *pp; pp = &(*pp)->next) {
        if ((*pp)->h != h) continue;
        struct node *n = *pp; uint64_t size = n->size;
        *pp = n->next; free(n);
        return size;
    }
    return 0;
}

static void read_limit(void) {
    const char *e = getenv("TINY_VK_MEM_LIMIT_MB");
    long long mb = e ? atoll(e) : 0;
    g_limit = mb > 0 ? (uint64_t)mb * 1048576ULL : 0;
}

/* ---- heap dipotong ---- */
static void clamp_props(VkPhysicalDeviceMemoryProperties *p) {
    if (!g_limit) return;
    for (uint32_t i = 0; i < p->memoryHeapCount; i++)
        if (p->memoryHeaps[i].size > g_limit) p->memoryHeaps[i].size = g_limit;
}

static void clamp_budget(VkPhysicalDeviceMemoryProperties2 *p2) {
    if (!g_limit) return;
    clamp_props(&p2->memoryProperties);
    for (VkBaseOutStructure *s = (VkBaseOutStructure *)p2->pNext; s; s = s->pNext) {
        if (s->sType != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT) continue;
        VkPhysicalDeviceMemoryBudgetPropertiesEXT *b = (VkPhysicalDeviceMemoryBudgetPropertiesEXT *)s;
        pthread_mutex_lock(&g_lock);
        uint64_t left = g_limit > g_used ? g_limit - g_used : 0;
        pthread_mutex_unlock(&g_lock);
        for (uint32_t i = 0; i < p2->memoryProperties.memoryHeapCount; i++) {
            if (b->heapBudget[i] > left) b->heapBudget[i] = left;
            if (b->heapUsage[i] > g_used) b->heapUsage[i] = g_used;
        }
    }
}

static VKAPI_ATTR void VKAPI_CALL tiny_GetPhysicalDeviceMemoryProperties(VkPhysicalDevice pd,
                                                                         VkPhysicalDeviceMemoryProperties *p) {
    struct inst *i = inst_find(key_of(pd));
    if (!i || !i->gpdmp) return;
    i->gpdmp(pd, p);
    clamp_props(p);
}

static VKAPI_ATTR void VKAPI_CALL tiny_GetPhysicalDeviceMemoryProperties2(VkPhysicalDevice pd,
                                                                          VkPhysicalDeviceMemoryProperties2 *p) {
    struct inst *i = inst_find(key_of(pd));
    if (!i || !i->gpdmp2) return;
    i->gpdmp2(pd, p);
    clamp_budget(p);
}

/* ---- alokasi dibatasi ---- */
static int is_import(const VkMemoryAllocateInfo *info) {
    for (const VkBaseInStructure *p = (const VkBaseInStructure *)info->pNext; p; p = p->pNext) {
        switch ((int)p->sType) {
        case VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR:
        case VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT:
            return 1;
        default: break;
        }
    }
    return 0;
}

static VKAPI_ATTR VkResult VKAPI_CALL tiny_AllocateMemory(VkDevice device, const VkMemoryAllocateInfo *info,
                                                          const VkAllocationCallbacks *cb, VkDeviceMemory *mem) {
    struct dev *d = dev_find(key_of(device));
    if (!d || !d->alloc) return VK_ERROR_INITIALIZATION_FAILED;
    if (!g_limit || is_import(info)) return d->alloc(device, info, cb, mem);

    const uint64_t size = info->allocationSize;
    pthread_mutex_lock(&g_lock);
    if (g_used + size > g_limit) {
        pthread_mutex_unlock(&g_lock);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    g_used += size;                      /* dipesan sebelum panggilan, supaya dua thread tidak sama-sama lolos */
    pthread_mutex_unlock(&g_lock);

    VkResult r = d->alloc(device, info, cb, mem);

    pthread_mutex_lock(&g_lock);
    if (r == VK_SUCCESS) tab_put((uint64_t)(uintptr_t)*mem, size);
    else g_used -= size;
    pthread_mutex_unlock(&g_lock);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL tiny_FreeMemory(VkDevice device, VkDeviceMemory mem, const VkAllocationCallbacks *cb) {
    struct dev *d = dev_find(key_of(device));
    if (mem != VK_NULL_HANDLE) {
        pthread_mutex_lock(&g_lock);
        uint64_t size = tab_take((uint64_t)(uintptr_t)mem);
        g_used = size <= g_used ? g_used - size : 0;
        pthread_mutex_unlock(&g_lock);
    }
    if (d && d->free_) d->free_(device, mem, cb);
}

/* ---- siklus hidup instance/device ---- */
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL tiny_GetDeviceProcAddr(VkDevice device, const char *name);
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL tiny_GetInstanceProcAddr(VkInstance instance, const char *name);

static VKAPI_ATTR VkResult VKAPI_CALL tiny_CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *cb,
                                                          VkInstance *out) {
    VkLayerInstanceCreateInfo *chain = (VkLayerInstanceCreateInfo *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (VkLayerInstanceCreateInfo *)chain->pNext;
    if (!chain) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next_gipa = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;       /* lapisan berikutnya melihat rantai tanpa kita */

    PFN_vkCreateInstance next_create = (PFN_vkCreateInstance)next_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!next_create) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult r = next_create(ci, cb, out);
    if (r != VK_SUCCESS) return r;

    struct inst *i = calloc(1, sizeof(*i));
    if (!i) return r;                                       /* tanpa dispatch: lapisan diam, instance tetap jalan */
    i->key = key_of(*out);
    i->handle = *out;
    i->gipa = next_gipa;
    i->destroy = (PFN_vkDestroyInstance)next_gipa(*out, "vkDestroyInstance");
    i->gpdmp = (PFN_vkGetPhysicalDeviceMemoryProperties)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties");
    i->gpdmp2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties2");
    if (!i->gpdmp2) i->gpdmp2 = (PFN_vkGetPhysicalDeviceMemoryProperties2)next_gipa(*out, "vkGetPhysicalDeviceMemoryProperties2KHR");
    pthread_mutex_lock(&g_lock);
    read_limit();
    i->next = g_insts; g_insts = i;
    pthread_mutex_unlock(&g_lock);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL tiny_DestroyInstance(VkInstance instance, const VkAllocationCallbacks *cb) {
    void *key = key_of(instance);
    struct inst *i = inst_find(key);
    PFN_vkDestroyInstance next = i ? i->destroy : NULL;
    pthread_mutex_lock(&g_lock);
    for (struct inst **pp = &g_insts; *pp; pp = &(*pp)->next)
        if ((*pp)->key == key) { struct inst *dead = *pp; *pp = dead->next; free(dead); break; }
    pthread_mutex_unlock(&g_lock);
    if (next) next(instance, cb);
}

static VKAPI_ATTR VkResult VKAPI_CALL tiny_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci,
                                                        const VkAllocationCallbacks *cb, VkDevice *out) {
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
    VkResult r = next_create(pd, ci, cb, out);
    if (r != VK_SUCCESS) return r;

    struct dev *d = calloc(1, sizeof(*d));
    if (!d) return r;
    d->key = key_of(*out);
    d->gdpa = next_gdpa;
    d->destroy = (PFN_vkDestroyDevice)next_gdpa(*out, "vkDestroyDevice");
    d->alloc = (PFN_vkAllocateMemory)next_gdpa(*out, "vkAllocateMemory");
    d->free_ = (PFN_vkFreeMemory)next_gdpa(*out, "vkFreeMemory");
    pthread_mutex_lock(&g_lock);
    d->next = g_devs; g_devs = d;
    pthread_mutex_unlock(&g_lock);
    return r;
}

static VKAPI_ATTR void VKAPI_CALL tiny_DestroyDevice(VkDevice device, const VkAllocationCallbacks *cb) {
    void *key = key_of(device);
    struct dev *d = dev_find(key);
    PFN_vkDestroyDevice next = d ? d->destroy : NULL;
    pthread_mutex_lock(&g_lock);
    for (struct dev **pp = &g_devs; *pp; pp = &(*pp)->next)
        if ((*pp)->key == key) { struct dev *dead = *pp; *pp = dead->next; free(dead); break; }
    pthread_mutex_unlock(&g_lock);
    if (next) next(device, cb);
}

/* ---- penyelesaian nama fungsi ---- */
static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL tiny_GetDeviceProcAddr(VkDevice device, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)tiny_GetDeviceProcAddr;
    if (!strcmp(name, "vkDestroyDevice")) return (PFN_vkVoidFunction)tiny_DestroyDevice;
    if (!strcmp(name, "vkAllocateMemory")) return (PFN_vkVoidFunction)tiny_AllocateMemory;
    if (!strcmp(name, "vkFreeMemory")) return (PFN_vkVoidFunction)tiny_FreeMemory;
    struct dev *d = dev_find(key_of(device));
    return d && d->gdpa ? d->gdpa(device, name) : NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL tiny_GetInstanceProcAddr(VkInstance instance, const char *name) {
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)tiny_GetInstanceProcAddr;
    if (!strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)tiny_CreateInstance;
    if (!strcmp(name, "vkDestroyInstance")) return (PFN_vkVoidFunction)tiny_DestroyInstance;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)tiny_CreateDevice;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)tiny_GetDeviceProcAddr;
    if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties")) return (PFN_vkVoidFunction)tiny_GetPhysicalDeviceMemoryProperties;
    if (!strcmp(name, "vkGetPhysicalDeviceMemoryProperties2") || !strcmp(name, "vkGetPhysicalDeviceMemoryProperties2KHR"))
        return (PFN_vkVoidFunction)tiny_GetPhysicalDeviceMemoryProperties2;
    if (!instance) return NULL;
    struct inst *i = inst_find(key_of(instance));
    return i && i->gipa ? i->gipa(instance, name) : NULL;
}

TINY_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (!v || v->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT) return VK_ERROR_INITIALIZATION_FAILED;
    if (v->loaderLayerInterfaceVersion > 2) v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = tiny_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = tiny_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
