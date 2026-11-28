#include <jni.h>
#include <libgen.h>
#include <sys/stat.h>

#include "vk_context.h"
#include "vortek_serializer.h"
#include "request_handler.h"
#include "vulkan_helper.h"
#include "jni_utils.h"
#include "vortek_log.h"

#include "adrenotools/driver.h"

VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;

static void* openVulkanLibrary(JNIEnv* env, jstring nativeLibraryDir, jstring libvulkanPath) {
    void* libvulkan;
    if (libvulkanPath) {
        const char* nativeLibraryDirC = (*env)->GetStringUTFChars(env, nativeLibraryDir, NULL);
        const char* libvulkanPathC = (*env)->GetStringUTFChars(env, libvulkanPath, NULL);
        const char* libvulkanName = basename(libvulkanPathC);

        char libvulkanDir[PATH_MAX] = {0};
        strcpy(libvulkanDir, dirname(libvulkanPathC));
        strcat(libvulkanDir, "/");

        char* tmpDir;
        asprintf(&tmpDir, "%s%s", libvulkanDir, "tmp");
        mkdir(tmpDir, S_IRWXU | S_IRWXG);

        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibraryDirC, libvulkanDir, libvulkanName, NULL, NULL);

        (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, nativeLibraryDirC);
        (*env)->ReleaseStringUTFChars(env, libvulkanPath, libvulkanPathC);
    }
    else libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);

    if (!libvulkan) vortekLog("gagal membuka libvulkan host: %s", dlerror());
    else vortekLog("libvulkan host terbuka (%s)", libvulkanPath ? "adrenotools" : LIBVULKAN_PATH);
    return libvulkan;
}

JNIEXPORT jlong JNICALL
Java_com_example_tiny_1computer_vortek_VortekRenderer_createVkContext(JNIEnv *env,
                                                                                  jobject obj,
                                                                                  jint clientFd,
                                                                                  jobject options) {
    VkContext* context = createVkContext(env, obj, clientFd, options);
    return context ? (jlong)context : 0;
}

JNIEXPORT void JNICALL
Java_com_example_tiny_1computer_vortek_VortekRenderer_destroyVkContext(JNIEnv *env,
                                                                                   jobject obj,
                                                                                   jlong contextPtr) {
    destroyVkContext(env, (VkContext*)contextPtr);
}

JNIEXPORT void JNICALL
Java_com_example_tiny_1computer_vortek_VortekRenderer_initVulkanWrapper(JNIEnv *env,
                                                                                    jobject obj,
                                                                                    jstring nativeLibraryDir,
                                                                                    jstring libvulkanPath) {
    void* libvulkan = openVulkanLibrary(env, nativeLibraryDir, libvulkanPath);
    initVulkanWrapper(&vulkanWrapper, libvulkan);
    vortekLog("wrapper Vulkan siap: vkCreateInstance=%p", (void*)vulkanWrapper.vkCreateInstance);
}

JNIEXPORT jboolean JNICALL
Java_com_example_tiny_1computer_vortek_VortekRenderer_handleExtraDataRequest(JNIEnv *env,
                                                                                         jobject obj,
                                                                                         jlong contextPtr,
                                                                                         int requestId,
                                                                                         int requestLength) {
    return handleExtraDataRequest((VkContext*)contextPtr, requestId, requestLength);
}