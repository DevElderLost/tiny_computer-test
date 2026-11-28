#include "async_pipeline_creator.h"
#include "vortek_serializer.h"
#include "vulkan_helper.h"
#include "vortek_log.h"

typedef struct PipelineCreateRequest {
    RingBuffer* responseRing;
    VkDevice device;
    VkPipelineCache pipelineCache;
    uint32_t pipelineCount;
    void* pipelineInfos;
    ShaderInspector* shaderInspector;
    MemoryPool memoryPool;
} PipelineCreateRequest;

static void createGraphicsPipelinesThread(void* param) {
    PipelineCreateRequest* req = param;
    VkPipeline pipelines[req->pipelineCount];
    VkResult result = VK_SUCCESS;

    VkGraphicsPipelineCreateInfo* createInfos = req->pipelineInfos;
    vortekLog("graphics pipeline: mulai (jumlah=%u)", req->pipelineCount);
    for (int i = 0; i < req->pipelineCount; i++) {
        ShaderInspector_inspectShaderStages(req->shaderInspector, req->device, (VkPipelineShaderStageCreateInfo*)createInfos[i].pStages, createInfos[i].stageCount, createInfos[i].pVertexInputState);
    }
    vortekLog("graphics pipeline: inspeksi shader selesai, memanggil driver host");
    result = vulkanWrapper.vkCreateGraphicsPipelines(req->device, req->pipelineCache, req->pipelineCount, createInfos, NULL, pipelines);
    vortekLog("graphics pipeline: driver host mengembalikan %d", (int)result);
    mp_free(&req->memoryPool);

    int bufferSize = sizeof(VkResult) + req->pipelineCount * VK_HANDLE_BYTE_COUNT;
    char outputBuffer[bufferSize];

    *(int*)(outputBuffer + 0) = result;
    for (int i = 0, j = sizeof(VkResult); i < req->pipelineCount; i++, j += VK_HANDLE_BYTE_COUNT) {
        *(uint64_t*)(outputBuffer + j) = (uint64_t)pipelines[i];
    }

    RingBuffer_write(req->responseRing, outputBuffer, bufferSize);
    vortekLog("graphics pipeline: respons ditulis (%d byte)", bufferSize);
    free(req);
}

static void createComputePipelinesThread(void* param) {
    PipelineCreateRequest* req = param;
    VkPipeline pipelines[req->pipelineCount];
    VkResult result = VK_SUCCESS;

    VkComputePipelineCreateInfo* createInfos = req->pipelineInfos;
    for (int i = 0; i < req->pipelineCount; i++) {
        ShaderInspector_inspectShaderStages(req->shaderInspector, req->device, (VkPipelineShaderStageCreateInfo*)&createInfos[i].stage, 1, NULL);
    }
    result = vulkanWrapper.vkCreateComputePipelines(req->device, req->pipelineCache, req->pipelineCount, createInfos, NULL, pipelines);
    mp_free(&req->memoryPool);

    int bufferSize = sizeof(VkResult) + req->pipelineCount * VK_HANDLE_BYTE_COUNT;
    char outputBuffer[bufferSize];

    *(int*)(outputBuffer + 0) = result;
    for (int i = 0, j = sizeof(VkResult); i < req->pipelineCount; i++, j += VK_HANDLE_BYTE_COUNT) {
        *(uint64_t*)(outputBuffer + j) = (uint64_t)pipelines[i];
    }

    RingBuffer_write(req->responseRing, outputBuffer, bufferSize);
    free(req);
}

void AsyncPipelineCreator_createGraphicsPipelines(VkContext* context) {
    ringid_t ringId = VT_REQUEST_RING_ID(context);
    PipelineCreateRequest* pipelineCreateRequest = calloc(1, sizeof(PipelineCreateRequest));
    pipelineCreateRequest->responseRing = VT_RESPONSE_RING(context, ringId);
    pipelineCreateRequest->shaderInspector = context->shaderInspector;

    char* inputBuffer = context->inputBuffer + VT_RING_ID_SIZE;
    vortekLog("graphics pipeline: request diterima");

    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;

    vt_unserialize_vkCreateGraphicsPipelines((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, &pipelineCreateRequest->pipelineCount, NULL, NULL, NULL, inputBuffer, NULL);

    VkGraphicsPipelineCreateInfo* createInfos = mp_alloc(&pipelineCreateRequest->memoryPool, pipelineCreateRequest->pipelineCount * sizeof(VkGraphicsPipelineCreateInfo));
    vt_unserialize_vkCreateGraphicsPipelines(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, createInfos, NULL, NULL, inputBuffer, &pipelineCreateRequest->memoryPool);
    pipelineCreateRequest->pipelineInfos = createInfos;
    pipelineCreateRequest->device = VkObject_fromId(deviceId);
    pipelineCreateRequest->pipelineCache = VkObject_fromId(pipelineCacheId);

    ThreadPool_run(context->threadPool, createGraphicsPipelinesThread, pipelineCreateRequest);
}

void AsyncPipelineCreator_createComputePipelines(VkContext* context) {
    ringid_t ringId = VT_REQUEST_RING_ID(context);
    PipelineCreateRequest* pipelineCreateRequest = calloc(1, sizeof(PipelineCreateRequest));
    pipelineCreateRequest->responseRing = VT_RESPONSE_RING(context, ringId);
    pipelineCreateRequest->shaderInspector = context->shaderInspector;

    char* inputBuffer = context->inputBuffer + VT_RING_ID_SIZE;

    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;

    vt_unserialize_vkCreateComputePipelines((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, &pipelineCreateRequest->pipelineCount, NULL, NULL, NULL, inputBuffer, NULL);
    VkComputePipelineCreateInfo* createInfos = mp_alloc(&pipelineCreateRequest->memoryPool, pipelineCreateRequest->pipelineCount * sizeof(VkComputePipelineCreateInfo));
    vt_unserialize_vkCreateComputePipelines(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, createInfos, NULL, NULL, inputBuffer, &pipelineCreateRequest->memoryPool);
    pipelineCreateRequest->pipelineInfos = createInfos;
    pipelineCreateRequest->device = VkObject_fromId(deviceId);
    pipelineCreateRequest->pipelineCache = VkObject_fromId(pipelineCacheId);

    ThreadPool_run(context->threadPool, createComputePipelinesThread, pipelineCreateRequest);
}