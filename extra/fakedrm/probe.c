/*
 * probe.c - does libfakedrm look like a render node to libdrm, GBM and Mesa's EGL?
 * Repeats what Xwayland's glamor does at start-up, one step at a time, and says where it stops.
 *   gcc probe.c -o probe -ldrm -lgbm -lEGL $(pkg-config --cflags libdrm)
 *   FAKEDRM_ROOT=/tmp/.fakedrm LD_PRELOAD=/path/libfakedrm.so MESA_LOADER_DRIVER_OVERRIDE=zink ./probe
 * Optional argument: dev_t major:minor to look up (default: the first render node found).
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <xf86drm.h>
#include <gbm.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

static int fails;
#define STEP(ok, ...) do { printf("%s ", (ok) ? "ok:  " : "FAIL:"); printf(__VA_ARGS__); printf("\n"); if (!(ok)) { fails++; goto done; } } while (0)

int main(int argc, char **argv)
{
    drmDevicePtr devs[8], dev = NULL, dev2 = NULL;
    int n, fd = -1;
    struct stat st;
    struct gbm_device *gbm = NULL;
    EGLDisplay dpy = EGL_NO_DISPLAY;
    drmVersionPtr ver;

    n = drmGetDevices2(0, devs, 8);
    STEP(n >= 1, "drmGetDevices2 finds %d device(s)", n);
    STEP(devs[0]->available_nodes & (1 << DRM_NODE_RENDER), "device has a render node: %s",
         devs[0]->nodes[DRM_NODE_RENDER]);

    if (stat(devs[0]->nodes[DRM_NODE_RENDER], &st) == 0)
        printf("info: stat -> %s, rdev %u:%u\n", S_ISCHR(st.st_mode) ? "char device" : "NOT a char device",
               major(st.st_rdev), minor(st.st_rdev));
    STEP(S_ISCHR(st.st_mode), "stat() reports a character device");

    /* what Xwayland does with the main device named by the compositor's dmabuf feedback */
    STEP(drmGetDeviceFromDevId(st.st_rdev, 0, &dev) == 0, "drmGetDeviceFromDevId(%u:%u)",
         major(st.st_rdev), minor(st.st_rdev));

    fd = open(dev->nodes[DRM_NODE_RENDER], O_RDWR | O_CLOEXEC);
    STEP(fd >= 0, "open(%s)", dev->nodes[DRM_NODE_RENDER]);
    STEP(drmGetNodeTypeFromFd(fd) == DRM_NODE_RENDER, "drmGetNodeTypeFromFd = render");
    STEP(drmGetDevice2(fd, 0, &dev2) == 0, "drmGetDevice2(fd)");
    {
        int fd2 = fcntl(fd, F_DUPFD_CLOEXEC, 3);   /* zink dups the fd */
        drmDevicePtr d3 = NULL;
        STEP(fd2 >= 0 && drmGetDevice2(fd2, 0, &d3) == 0, "a dup()ed fd is still recognised");
        drmFreeDevice(&d3);
        close(fd2);
    }
    ver = drmGetVersion(fd);
    STEP(ver != NULL, "drmGetVersion(fd) -> %s", ver ? ver->name : "?");
    drmFreeVersion(ver);

    gbm = gbm_create_device(fd);
    STEP(gbm != NULL, "gbm_create_device, backend '%s'", gbm ? gbm_device_get_backend_name(gbm) : "?");

    {
        PFNEGLGETPLATFORMDISPLAYEXTPROC getpd =
            (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
        EGLint maj, min;
        dpy = getpd ? getpd(EGL_PLATFORM_GBM_KHR, gbm, NULL) : EGL_NO_DISPLAY;
        STEP(dpy != EGL_NO_DISPLAY, "eglGetPlatformDisplayEXT(GBM)");
        STEP(eglInitialize(dpy, &maj, &min), "eglInitialize %d.%d (driver '%s')", maj, min,
             getenv("MESA_LOADER_DRIVER_OVERRIDE") ? getenv("MESA_LOADER_DRIVER_OVERRIDE") : "auto");
        printf("info: EGL vendor %s\n", eglQueryString(dpy, EGL_VENDOR));
        STEP(eglBindAPI(EGL_OPENGL_API), "eglBindAPI(OpenGL)");
        {
            EGLConfig cfg; EGLint ncfg = 0;
            EGLint attr[] = { EGL_SURFACE_TYPE, 0, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE };
            EGLContext ctx;
            STEP(eglChooseConfig(dpy, attr, &cfg, 1, &ncfg) && ncfg > 0, "eglChooseConfig");
            ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, NULL);
            STEP(ctx != EGL_NO_CONTEXT, "eglCreateContext");
            STEP(eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx), "eglMakeCurrent (surfaceless)");
            {
                const char *(*getstr)(unsigned) = (const char *(*)(unsigned)) eglGetProcAddress("glGetString");
                printf("info: GL_RENDERER = %s\n", getstr ? getstr(0x1F01) : "?");
            }
        }
    }
done:
    printf("%s (%d failure%s)\n", fails ? "RESULT: STOPPED" : "RESULT: PASSED", fails, fails == 1 ? "" : "s");
    return fails ? 1 : 0;
}
