#include "synchronization_object_manager.h"
#include "vortek_serializer.h"
#include "string_utils.h"
#include "vulkan_helper.h"
#include "legacy_reply.h"

typedef struct WaitForFencesRequest {
    RingBuffer* responseRing;
    VkDevice device;
    uint32_t fenceCount;
    VkBool32 waitAll;
    uint64_t timeout;
    VkFence* fences;
    MemoryPool memoryPool;
    int replyFd; // ICD lama: ujung tulis pipe (timeout != 0) atau -1 (balas lewat ring)
} WaitForFencesRequest;

typedef struct WaitSemaphoresRequest {
    RingBuffer* responseRing;
    VkDevice device;
    VkSemaphoreWaitInfo waitInfo;
    uint64_t timeout;
    MemoryPool memoryPool;
    int replyFd; // ICD lama: ujung tulis pipe balasan
} WaitSemaphoresRequest;

static void waitForFencesThread(void* param) {
    WaitForFencesRequest* req = param;
    VkResult result = vulkanWrapper.vkWaitForFences(req->device, req->fenceCount, req->fences, req->waitAll, req->timeout);
#ifdef VORTEK_LEGACY_ICD
    if (req->replyFd >= 0) LegacyReply_finishFence(req->replyFd, (int)result);
    else LegacyReply_ringResult(req->responseRing, (int)result, NULL, 0);
#else
    RingBuffer_write(req->responseRing, &result, sizeof(VkResult));
#endif

    mp_free(&req->memoryPool);
    free(req);
}

static void waitSemaphoresThread(void* param) {
    WaitSemaphoresRequest* req = param;
    VkResult result = vulkanWrapper.vkWaitSemaphores(req->device, &req->waitInfo, req->timeout);
#ifdef VORTEK_LEGACY_ICD
    LegacyReply_finish(req->replyFd, &result, sizeof(VkResult));
#else
    RingBuffer_write(req->responseRing, &result, sizeof(VkResult));
#endif

    mp_free(&req->memoryPool);
    free(req);
}

void SynchronizationObjectManager_waitForFences(VkContext* context) {
    ringid_t ringId = VT_REQUEST_RING_ID(context);
    WaitForFencesRequest* waitForFencesRequest = calloc(1, sizeof(WaitForFencesRequest));
    waitForFencesRequest->responseRing = VT_RESPONSE_RING(context, ringId);

    char* inputBuffer = context->inputBuffer + VT_RING_ID_SIZE;
    uint64_t deviceId;
    vt_unserialize_vkWaitForFences((VkDevice)&deviceId, &waitForFencesRequest->fenceCount, NULL, &waitForFencesRequest->waitAll, &waitForFencesRequest->timeout, inputBuffer, &waitForFencesRequest->memoryPool);
    waitForFencesRequest->device  = VkObject_fromId(deviceId);
#ifdef VORTEK_LEGACY_ICD
    waitForFencesRequest->replyFd = waitForFencesRequest->timeout != 0 ? LegacyReply_beginFence(context->clientFd) : -1;
#endif

    waitForFencesRequest->fences = mp_alloc(&waitForFencesRequest->memoryPool, waitForFencesRequest->fenceCount * sizeof(VkFence));
    vt_unserialize_vkWaitForFences(VK_NULL_HANDLE, NULL, waitForFencesRequest->fences, NULL, NULL, inputBuffer, &waitForFencesRequest->memoryPool);

    ThreadPool_run(context->threadPool, waitForFencesThread, waitForFencesRequest);
}

void SynchronizationObjectManager_waitSemaphores(VkContext* context) {
    ringid_t ringId = VT_REQUEST_RING_ID(context);
    WaitSemaphoresRequest* waitSemaphoresRequest = calloc(1, sizeof(WaitSemaphoresRequest));
    waitSemaphoresRequest->responseRing = VT_RESPONSE_RING(context, ringId);
#ifdef VORTEK_LEGACY_ICD
    waitSemaphoresRequest->replyFd = LegacyReply_begin(context->clientFd);
#endif

    char* inputBuffer = context->inputBuffer + VT_RING_ID_SIZE;
    uint64_t deviceId;
    vt_unserialize_vkWaitSemaphores((VkDevice)&deviceId, &waitSemaphoresRequest->waitInfo, &waitSemaphoresRequest->timeout, inputBuffer, &waitSemaphoresRequest->memoryPool);
    waitSemaphoresRequest->device = VkObject_fromId(deviceId);

    ThreadPool_run(context->threadPool, waitSemaphoresThread, waitSemaphoresRequest);
}