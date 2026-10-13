/*
 * libfakedrm.so - a DRM render node that exists only in userspace.
 *
 * Why: Android kernels expose the Adreno GPU through KGSL, so a proot container has no
 * /dev/dri. Xwayland's glamor (GPU-accelerated X) refuses to start without a DRM render node:
 * it asks libdrm for the compositor's device, opens it, wraps it with GBM and hands that to
 * Mesa's EGL. Only the *identity* of the device is needed for that: Mesa/zink allocate their
 * buffers through Vulkan, never through a kernel DRM ioctl.
 *
 * What it does (LD_PRELOAD, enabled by FAKEDRM_ROOT=<dir>):
 *  - keeps a small tree under FAKEDRM_ROOT that looks like /dev/dri/renderD128 plus the
 *    /sys/dev/char/<maj>:<min> entries libdrm reads, and redirects those paths to it;
 *  - makes stat()/fstat()/statx() report that node as a character device <maj>:<min>
 *    (the node is recognised by inode, so dup()ed fds and other processes work too);
 *  - answers the few DRM ioctls libdrm and Mesa's loader issue on it (DRM_IOCTL_VERSION
 *    reports FAKEDRM_DRIVER, everything else is "unsupported").
 *
 * It does NOT emulate a GPU: no buffers, no GEM, no mode setting. Pair it with
 * MESA_LOADER_DRIVER_OVERRIDE=zink so the work goes to Turnip over Vulkan.
 *
 * Env: FAKEDRM_ROOT (required, e.g. /tmp/.fakedrm), FAKEDRM_MAJOR (default 1000; keep it
 * >= 255 so zink does not try to match the device against VK_EXT_physical_device_drm),
 * FAKEDRM_MINOR (default 128), FAKEDRM_DRIVER (default "msm"), FAKEDRM_DEBUG=1.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

static int g_on;
static int g_debug;
static char g_root[PATH_MAX];
static char g_driver[64] = "msm";
static unsigned g_major = 1000, g_minor = 128;
static dev_t g_rdev;
static dev_t g_node_dev;
static ino_t g_node_ino;
static char g_node_path[64];   /* "/dev/dri/renderD<minor>" */
static char g_sys_prefix[64];  /* "/sys/dev/char/<maj>:<min>" */

#define DBG(...) do { if (g_debug) { fprintf(stderr, "fakedrm: " __VA_ARGS__); fputc('\n', stderr); } } while (0)

/* ---------------------------------------------------------------------- */
/* real libc entry points                                                 */
/* ---------------------------------------------------------------------- */

#define REAL(name) ({ static void *p_; if (!p_) p_ = dlsym(RTLD_NEXT, #name); p_; })

static int raw_fstat(int fd, struct stat *st)
{
    return (int) syscall(SYS_newfstatat, fd, "", st, AT_EMPTY_PATH);
}

/* ---------------------------------------------------------------------- */
/* path redirection                                                       */
/* ---------------------------------------------------------------------- */

static int has_prefix(const char *path, const char *prefix)
{
    size_t n = strlen(prefix);
    return !strncmp(path, prefix, n) && (path[n] == '\0' || path[n] == '/');
}

static const char *rw(const char *path, char *buf, size_t n)
{
    if (!g_on || !path || path[0] != '/')
        return path;
    if (has_prefix(path, "/dev/dri") || has_prefix(path, g_sys_prefix)) {
        if ((size_t) snprintf(buf, n, "%s%s", g_root, path) < n)
            return buf;
    }
    return path;
}

static void mkdir_p(const char *path)
{
    char tmp[PATH_MAX];
    size_t i;

    snprintf(tmp, sizeof(tmp), "%s", path);
    for (i = 1; tmp[i]; i++) {
        if (tmp[i] == '/') {
            tmp[i] = '\0';
            mkdir(tmp, 0777);
            tmp[i] = '/';
        }
    }
    mkdir(tmp, 0777);
}

static void write_file(const char *path, const char *text)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0666);

    if (fd < 0)
        return;     /* already there (another process made it) */
    if (text && write(fd, text, strlen(text)) < 0) { /* best effort */ }
    close(fd);
}

static void build_tree(void)
{
    char p[PATH_MAX], text[512];

    snprintf(p, sizeof(p), "%s/dev/dri", g_root);
    mkdir_p(p);
    snprintf(p, sizeof(p), "%s%s", g_root, g_node_path);
    write_file(p, NULL);

    /* libdrm: drmNodeIsDRM() looks for <sysdir>/device/drm */
    snprintf(p, sizeof(p), "%s%s/device/drm", g_root, g_sys_prefix);
    mkdir_p(p);

    /* libdrm: bus type comes from the basename of the device/subsystem link */
    snprintf(p, sizeof(p), "%s%s/device/subsystem", g_root, g_sys_prefix);
    if (symlink("/sys/bus/platform", p) < 0 && errno != EEXIST) { /* best effort */ }

    snprintf(text, sizeof(text),
             "DRIVER=%s\nOF_NAME=gpu\nOF_FULLNAME=/soc/gpu@5000000\n"
             "OF_COMPATIBLE_0=qcom,adreno\nOF_COMPATIBLE_N=1\n"
             "MODALIAS=of:NgpuT(null)Cqcom,adreno\n", g_driver);
    snprintf(p, sizeof(p), "%s%s/device/uevent", g_root, g_sys_prefix);
    write_file(p, text);
}

__attribute__((constructor))
static void fakedrm_init(void)
{
    const char *r = getenv("FAKEDRM_ROOT"), *e;
    char p[PATH_MAX];
    struct stat st;
    size_t n;

    if (!r || !*r)
        return;
    snprintf(g_root, sizeof(g_root), "%s", r);
    n = strlen(g_root);
    while (n > 1 && g_root[n - 1] == '/')
        g_root[--n] = '\0';

    if ((e = getenv("FAKEDRM_MAJOR")) && *e) g_major = (unsigned) strtoul(e, NULL, 0);
    if ((e = getenv("FAKEDRM_MINOR")) && *e) g_minor = (unsigned) strtoul(e, NULL, 0);
    if ((e = getenv("FAKEDRM_DRIVER")) && *e) snprintf(g_driver, sizeof(g_driver), "%s", e);
    g_debug = (e = getenv("FAKEDRM_DEBUG")) && *e && strcmp(e, "0") != 0;

    g_rdev = makedev(g_major, g_minor);
    snprintf(g_node_path, sizeof(g_node_path), "/dev/dri/renderD%u", g_minor);
    snprintf(g_sys_prefix, sizeof(g_sys_prefix), "/sys/dev/char/%u:%u", g_major, g_minor);

    build_tree();

    snprintf(p, sizeof(p), "%s%s", g_root, g_node_path);
    if (stat(p, &st) < 0)
        return;     /* tree could not be made: stay out of the way */
    g_node_dev = st.st_dev;
    g_node_ino = st.st_ino;
    g_on = 1;
    DBG("active: node %s as %u:%u, driver '%s', root %s", g_node_path, g_major, g_minor,
        g_driver, g_root);
}

/* ---------------------------------------------------------------------- */
/* identity of the fake node                                              */
/* ---------------------------------------------------------------------- */

static int is_node_st(const struct stat *st)
{
    return g_on && st->st_ino == g_node_ino && st->st_dev == g_node_dev && S_ISREG(st->st_mode);
}

static void make_chr(struct stat *st)
{
    st->st_mode = (st->st_mode & ~S_IFMT) | S_IFCHR;
    st->st_rdev = g_rdev;
}

static int fd_is_node(int fd)
{
    struct stat st;

    return g_on && raw_fstat(fd, &st) == 0 && is_node_st(&st);
}

/* ---------------------------------------------------------------------- */
/* open / fopen / opendir / readlink / access                             */
/* ---------------------------------------------------------------------- */

#define PATHBUF char b_[PATH_MAX]

EXPORT int open(const char *path, int flags, ...)
{
    PATHBUF;
    mode_t mode = 0;
    int (*f)(const char *, int, ...) = REAL(open);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return f(rw(path, b_, sizeof(b_)), flags, mode);
}

EXPORT int open64(const char *path, int flags, ...)
{
    PATHBUF;
    mode_t mode = 0;
    int (*f)(const char *, int, ...) = REAL(open64);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return f(rw(path, b_, sizeof(b_)), flags, mode);
}

EXPORT int openat(int dirfd, const char *path, int flags, ...)
{
    PATHBUF;
    mode_t mode = 0;
    int (*f)(int, const char *, int, ...) = REAL(openat);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return f(dirfd, rw(path, b_, sizeof(b_)), flags, mode);
}

EXPORT int openat64(int dirfd, const char *path, int flags, ...)
{
    PATHBUF;
    mode_t mode = 0;
    int (*f)(int, const char *, int, ...) = REAL(openat64);

    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return f(dirfd, rw(path, b_, sizeof(b_)), flags, mode);
}

/* _FORTIFY_SOURCE builds (Debian's default) call these instead of open()/openat() */
EXPORT int __open_2(const char *path, int flags)
{
    PATHBUF;
    int (*f)(const char *, int, ...) = REAL(open);

    return f(rw(path, b_, sizeof(b_)), flags);
}

EXPORT int __open64_2(const char *path, int flags)
{
    PATHBUF;
    int (*f)(const char *, int, ...) = REAL(open64);

    return f(rw(path, b_, sizeof(b_)), flags);
}

EXPORT int __openat_2(int dirfd, const char *path, int flags)
{
    PATHBUF;
    int (*f)(int, const char *, int, ...) = REAL(openat);

    return f(dirfd, rw(path, b_, sizeof(b_)), flags);
}

EXPORT int __openat64_2(int dirfd, const char *path, int flags)
{
    PATHBUF;
    int (*f)(int, const char *, int, ...) = REAL(openat64);

    return f(dirfd, rw(path, b_, sizeof(b_)), flags);
}

EXPORT FILE *fopen(const char *path, const char *mode)
{
    PATHBUF;
    FILE *(*f)(const char *, const char *) = REAL(fopen);

    return f(rw(path, b_, sizeof(b_)), mode);
}

EXPORT FILE *fopen64(const char *path, const char *mode)
{
    PATHBUF;
    FILE *(*f)(const char *, const char *) = REAL(fopen64);

    return f(rw(path, b_, sizeof(b_)), mode);
}

EXPORT DIR *opendir(const char *path)
{
    PATHBUF;
    DIR *(*f)(const char *) = REAL(opendir);

    return f(rw(path, b_, sizeof(b_)));
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t len)
{
    PATHBUF;
    ssize_t (*f)(const char *, char *, size_t) = REAL(readlink);

    return f(rw(path, b_, sizeof(b_)), buf, len);
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t len)
{
    PATHBUF;
    ssize_t (*f)(int, const char *, char *, size_t) = REAL(readlinkat);

    return f(dirfd, rw(path, b_, sizeof(b_)), buf, len);
}

EXPORT int access(const char *path, int mode)
{
    PATHBUF;
    int (*f)(const char *, int) = REAL(access);

    return f(rw(path, b_, sizeof(b_)), mode);
}

EXPORT int faccessat(int dirfd, const char *path, int mode, int flags)
{
    PATHBUF;
    int (*f)(int, const char *, int, int) = REAL(faccessat);

    return f(dirfd, rw(path, b_, sizeof(b_)), mode, flags);
}

/* ---------------------------------------------------------------------- */
/* stat family                                                            */
/* ---------------------------------------------------------------------- */

#define STAT_WRAP(name, sig, call)                                         \
    EXPORT int name sig                                                    \
    {                                                                      \
        PATHBUF;                                                           \
        int (*f) sig = REAL(name);                                         \
        int r = call;                                                      \
        if (r == 0 && is_node_st(st))                                      \
            make_chr(st);                                                  \
        return r;                                                          \
    }

STAT_WRAP(stat,    (const char *path, struct stat *st), f(rw(path, b_, sizeof(b_)), st))
STAT_WRAP(lstat,   (const char *path, struct stat *st), f(rw(path, b_, sizeof(b_)), st))
/* On LP64 struct stat and struct stat64 are the same layout; the casts only satisfy the headers */
EXPORT int stat64(const char *path, struct stat64 *st)
{
    return stat(path, (struct stat *) st);
}

EXPORT int lstat64(const char *path, struct stat64 *st)
{
    return lstat(path, (struct stat *) st);
}

EXPORT int fstat(int fd, struct stat *st)
{
    int r = raw_fstat(fd, st);

    if (r == 0 && is_node_st(st))
        make_chr(st);
    return r;
}

EXPORT int fstat64(int fd, struct stat64 *st)
{
    return fstat(fd, (struct stat *) st);
}

EXPORT int fstatat(int dirfd, const char *path, struct stat *st, int flags)
{
    PATHBUF;
    int (*f)(int, const char *, struct stat *, int) = REAL(fstatat);
    int r = f(dirfd, rw(path, b_, sizeof(b_)), st, flags);

    if (r == 0 && is_node_st(st))
        make_chr(st);
    return r;
}

EXPORT int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags)
{
    return fstatat(dirfd, path, (struct stat *) st, flags);
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx)
{
    PATHBUF;
    int (*f)(int, const char *, int, unsigned int, struct statx *) = REAL(statx);
    int r = f(dirfd, rw(path, b_, sizeof(b_)), flags, mask, stx);

    if (r == 0 && g_on && S_ISREG(stx->stx_mode) && stx->stx_ino == g_node_ino &&
        makedev(stx->stx_dev_major, stx->stx_dev_minor) == g_node_dev) {
        stx->stx_mode = (stx->stx_mode & ~S_IFMT) | S_IFCHR;
        stx->stx_rdev_major = g_major;
        stx->stx_rdev_minor = g_minor;
    }
    return r;
}

/* ---------------------------------------------------------------------- */
/* ioctl                                                                  */
/* ---------------------------------------------------------------------- */

struct fd_drm_version {
    int version_major, version_minor, version_patchlevel;
    size_t name_len;
    char *name;
    size_t date_len;
    char *date;
    size_t desc_len;
    char *desc;
};

static void copy_str(char *dst, size_t *len, const char *src)
{
    size_t n = strlen(src);

    if (dst && *len)
        memcpy(dst, src, n < *len ? n : *len);
    *len = n;
}

#define FD_DRM_TYPE         'd'
#define FD_NR_VERSION       0x00
#define FD_NR_GET_MAGIC     0x02
#define FD_NR_GET_CAP       0x0c
#define FD_NR_AUTH_MAGIC    0x11

struct fd_drm_get_cap { unsigned long long capability, value; };

EXPORT int ioctl(int fd, unsigned long req, ...)
{
    int (*f)(int, unsigned long, ...) = REAL(ioctl);
    va_list ap;
    void *arg;

    va_start(ap, req);
    arg = va_arg(ap, void *);
    va_end(ap);

    if (!fd_is_node(fd))
        return f(fd, req, arg);

    if (_IOC_TYPE(req) != FD_DRM_TYPE) {
        errno = ENOTTY;
        return -1;
    }

    switch (_IOC_NR(req)) {
    case FD_NR_VERSION: {
        struct fd_drm_version *v = arg;

        v->version_major = 1;
        v->version_minor = 0;
        v->version_patchlevel = 0;
        copy_str(v->name, &v->name_len, g_driver);
        copy_str(v->date, &v->date_len, "20260101");
        copy_str(v->desc, &v->desc_len, "userspace render node (libfakedrm)");
        DBG("DRM_IOCTL_VERSION -> %s", g_driver);
        return 0;
    }
    case FD_NR_GET_CAP: {
        struct fd_drm_get_cap *c = arg;

        c->value = 0;       /* every optional capability: not supported */
        DBG("DRM_IOCTL_GET_CAP 0x%llx -> 0", c->capability);
        return 0;
    }
    case FD_NR_GET_MAGIC:
    case FD_NR_AUTH_MAGIC:
        errno = EACCES;     /* what the kernel says on a render node */
        return -1;
    default:
        DBG("ioctl nr 0x%02lx unsupported", (unsigned long) _IOC_NR(req));
        errno = EINVAL;
        return -1;
    }
}
