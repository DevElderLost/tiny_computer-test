#include "vk_membudget.h"
#include "vk_present.h" /* droiddeck_log */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

/* Open-addressing table: memory handle -> counted size. Imported memory is never entered. */
struct mb_entry { uint64_t handle; uint64_t size; };
static struct mb_entry *g_tab;
static size_t g_cap, g_n;       /* g_cap is a power of two; g_n counts live entries */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int64_t g_limit_bytes;   /* 0 = unlimited */
static uint64_t g_used, g_peak;
static unsigned g_refused;
static int64_t g_last_note_ns;

#define TOMB ((uint64_t)1)               /* handle value of a deleted slot (a real handle is never 1) */

static size_t slot_of(uint64_t h, size_t cap) {
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL; h ^= h >> 33;
    return (size_t)h & (cap - 1);
}

static int tab_grow(void) {
    size_t ncap = g_cap ? g_cap * 2 : 256;
    struct mb_entry *nt = calloc(ncap, sizeof(*nt));
    if (!nt) return -1;
    for (size_t i = 0; i < g_cap; i++) {
        if (g_tab[i].handle == 0 || g_tab[i].handle == TOMB) continue;
        size_t s = slot_of(g_tab[i].handle, ncap);
        while (nt[s].handle) s = (s + 1) & (ncap - 1);
        nt[s] = g_tab[i];
    }
    free(g_tab);
    g_tab = nt; g_cap = ncap;
    return 0;
}

/* Caller holds g_lock. */
static void tab_put(uint64_t h, uint64_t size) {
    if ((g_n + 1) * 2 > g_cap && tab_grow() != 0) return; /* out of memory: the allocation just stays uncounted */
    size_t s = slot_of(h, g_cap);
    while (g_tab[s].handle && g_tab[s].handle != TOMB) s = (s + 1) & (g_cap - 1);
    g_tab[s].handle = h; g_tab[s].size = size;
    g_n++;
}

/* Caller holds g_lock. Returns the counted size and forgets the entry, 0 when it was never counted. */
static uint64_t tab_take(uint64_t h) {
    if (!g_cap) return 0;
    size_t s = slot_of(h, g_cap);
    for (size_t probes = 0; probes < g_cap && g_tab[s].handle; probes++, s = (s + 1) & (g_cap - 1)) {
        if (g_tab[s].handle != h) continue;
        uint64_t size = g_tab[s].size;
        g_tab[s].handle = TOMB; g_tab[s].size = 0;
        g_n--;
        return size;
    }
    return 0;
}

static int is_import(const VkMemoryAllocateInfo *info) {
    for (const VkBaseInStructure *p = (const VkBaseInStructure *)info->pNext; p; p = p->pNext) {
        switch ((int)p->sType) {
        case VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR:
        case VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT:
        case VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID:
            return 1;
        default: break;
        }
    }
    return 0;
}

static int64_t mono_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void vkmb_set_limit_mb(int mb) {
    pthread_mutex_lock(&g_lock);
    g_limit_bytes = mb > 0 ? (int64_t)mb * 1048576LL : 0;
    uint64_t used = g_used;
    pthread_mutex_unlock(&g_lock);
    /* JNI thread: the Android log only (droiddeck_log belongs to the compositor thread) */
    if (mb > 0)
        __android_log_print(ANDROID_LOG_INFO, "vkmembudget", "limit %d MB (in use now %.1f MB): allocations above it are refused",
                            mb, (double)used / 1048576.0);
    else
        __android_log_print(ANDROID_LOG_INFO, "vkmembudget", "limit off (in use now %.1f MB)", (double)used / 1048576.0);
}

int vkmb_limit_mb(void) { return (int)(g_limit_bytes / 1048576LL); }

VkResult vkmb_allocate(VkDevice dev, const VkMemoryAllocateInfo *info, VkDeviceMemory *out) {
    if (is_import(info)) return g_vk.AllocateMemory(dev, info, NULL, out);
    const uint64_t size = info->allocationSize;
    pthread_mutex_lock(&g_lock);
    const int64_t limit = g_limit_bytes;
    if (limit && g_used + size > (uint64_t)limit) {
        g_refused++;
        const int64_t now = mono_ns();
        const int note = now - g_last_note_ns > 2000000000LL; /* a burst of refusals is one log line */
        if (note) g_last_note_ns = now;
        const double used = (double)g_used / 1048576.0;
        pthread_mutex_unlock(&g_lock);
        if (note)
            droiddeck_log("memory", "allocation of %.1f MB refused: %.1f MB in use, limit %lld MB (%u refused so far)",
                          (double)size / 1048576.0, used, (long long)(limit / 1048576LL), g_refused);
        return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    }
    g_used += size;                       /* reserved before the call so two threads cannot both pass the check */
    if (g_used > g_peak) g_peak = g_used;
    pthread_mutex_unlock(&g_lock);

    VkResult r = g_vk.AllocateMemory(dev, info, NULL, out);

    pthread_mutex_lock(&g_lock);
    if (r == VK_SUCCESS) tab_put((uint64_t)(uintptr_t)*out, size);
    else g_used -= size;
    pthread_mutex_unlock(&g_lock);
    return r;
}

void vkmb_free(VkDevice dev, VkDeviceMemory mem) {
    if (mem == VK_NULL_HANDLE) return;
    pthread_mutex_lock(&g_lock);
    const uint64_t size = tab_take((uint64_t)(uintptr_t)mem);
    if (size <= g_used) g_used -= size; else g_used = 0;
    pthread_mutex_unlock(&g_lock);
    g_vk.FreeMemory(dev, mem, NULL);
}

uint64_t vkmb_used_bytes(void) { return g_used; }
unsigned vkmb_refused(void) { return g_refused; }
uint64_t vkmb_peak_bytes(void) { return g_peak; }
