#include <jni.h>
#include <string>
#include <cstring>
#include <csignal>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include <cerrno>

#define LOG_TAG "NativeAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Dipakai dari dua thread (thread perekam + thread UI saat berhenti), jadi semuanya volatile.
static volatile int server_fd = -1;
static volatile int client_fd = -1;

static void closeFd(volatile int &fd) {
    int f = fd;
    fd = -1;
    if (f != -1) {
        shutdown(f, SHUT_RDWR);
        close(f);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_tiny_1computer_AudioStream_nativeInit(JNIEnv *env, jobject thiz, jstring path) {
    // Menulis ke soket yang sudah ditutup guest memicu SIGPIPE yang mematikan seluruh aplikasi.
    signal(SIGPIPE, SIG_IGN);
    closeFd(client_fd);
    closeFd(server_fd);

    const char *socket_path = env->GetStringUTFChars(path, nullptr);
    if (socket_path == nullptr) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(socket_path) >= sizeof(addr.sun_path)) {
        LOGE("Socket path too long");
        env->ReleaseStringUTFChars(path, socket_path);
        return -1;
    }
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);
    env->ReleaseStringUTFChars(path, socket_path);

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd == -1) {
        LOGE("Socket creation failed: %s", strerror(errno));
        return -1;
    }
    unlink(addr.sun_path);
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) == -1) {
        LOGE("Bind failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    chmod(addr.sun_path, 0666);
    if (listen(fd, 1) == -1) {
        LOGE("Listen failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    server_fd = fd;
    return 0;
}

// 1 = klien tersambung, 0 = belum (habis waktu, coba lagi), -1 = gagal / sudah ditutup.
// accept() yang memblokir tanpa batas tidak bisa dibangunkan close() dari thread lain, itu yang
// membuat tombol "matikan mikrofon" menggantung dan Android menampilkan "tidak menanggapi".
extern "C" JNIEXPORT jint JNICALL
Java_com_example_tiny_1computer_AudioStream_nativeAccept(JNIEnv *env, jobject thiz) {
    int sfd = server_fd;
    if (sfd == -1) return -1;
    struct pollfd pfd;
    pfd.fd = sfd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int r = poll(&pfd, 1, 300);
    if (r == 0) return 0;
    if (r < 0) return errno == EINTR ? 0 : -1;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    int c = accept4(sfd, nullptr, nullptr, SOCK_CLOEXEC);
    if (c == -1) {
        if (errno == EINTR || errno == EAGAIN) return 0;
        LOGE("Accept failed: %s", strerror(errno));
        return -1;
    }
    client_fd = c;
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_tiny_1computer_AudioStream_nativeSend(JNIEnv *env, jobject thiz, jbyteArray data, jint size) {
    int cfd = client_fd;
    if (cfd == -1 || size <= 0) return -1;

    jbyte *buffer = env->GetByteArrayElements(data, nullptr);
    if (buffer == nullptr) return -1;
    jint total = 0;
    while (total < size) {
        ssize_t sent = send(cfd, buffer + total, size - total, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;
            LOGE("Write failed: %s", strerror(errno));
            total = -1;
            break;
        }
        total += (jint) sent;
    }
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
    return total;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_tiny_1computer_AudioStream_nativeClose(JNIEnv *env, jobject thiz) {
    closeFd(client_fd);
    closeFd(server_fd);
}
