#ifndef VORTEK_RING_BUFFER_POOL_H
#define VORTEK_RING_BUFFER_POOL_H

#include <stdbool.h>

#include "winlator.h"
#include "ring_buffer.h"
#include "time_utils.h"

#define RING_POOL_BUFFER_SIZE 128
#define RING_POOL_ARRAY_LEN 8
#define RING_POOL_ACQUIRE_TIMEOUT_NS 2000000000
#define RING_ID_INVALID 255

typedef unsigned char ringid_t;

// Mode ICD lama (libvulkan_vortek.so 2.1 di aset): host hanya mengirim 2 ring (server 4MB, client 256KB), request tidak
// diawali ringId, dan semua respons dibaca ICD dari clientRing. Mode baru memakai ring pool (2 + RING_POOL_ARRAY_LEN fd).
#ifdef VORTEK_LEGACY_ICD
#define VT_RING_ID_SIZE 0
#define VT_REQUEST_RING_ID(ctx) ((ringid_t)0)
#define VT_RESPONSE_RING(ctx, id) ((ctx)->clientRing)
#else
#define VT_RING_ID_SIZE sizeof(ringid_t)
#define VT_REQUEST_RING_ID(ctx) (*(ringid_t*)((ctx)->inputBuffer + 0))
#define VT_RESPONSE_RING(ctx, id) ((ctx)->ringPool[id].ring)
#endif

typedef struct RingBufferPool {
    bool acquired;
    RingBuffer* ring;
} RingBufferPool;

static inline ringid_t RingBufferPool_acquire(RingBufferPool* ringPool) {
    for (ringid_t i = 0; i < RING_POOL_ARRAY_LEN; i++) {
        if (!ringPool[i].acquired) {
            ringPool[i].acquired = true;
            return i;
        }
    }

    uint32_t iter = 0;
    uint64_t endTime = nanoTime() + RING_POOL_ACQUIRE_TIMEOUT_NS;

    while (1) {
        iter = busyWait(iter);

        for (ringid_t i = 0; i < RING_POOL_ARRAY_LEN; i++) {
            if (!ringPool[i].acquired) {
                ringPool[i].acquired = true;
                return i;
            }
        }

        if (nanoTime() > endTime) {
            println("vortek: can't acquire ring buffer, the timeout has been reached %u", iter);
            break;
        }
    }

    return RING_ID_INVALID;
}

#endif