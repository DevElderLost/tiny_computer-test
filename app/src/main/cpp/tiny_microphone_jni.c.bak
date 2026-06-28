/*
 * tiny_microphone_jni.c -- This file is part of tiny_container.
 *
 * Copyright (C) 2026 Caten Hu
 *
 * Tiny Container is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or any later version.
 *
 * Tiny Container is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 */

/*
 * tiny_microphone_jni.c – Native implementation for TinyMicrophone (Android)
 *
 * Architecture:
 *   [AAudio capture callback]  ──ringbuffer──>  [socket writer thread]
 *         ↑                                           ↓
 *   Device microphone                         Unix domain socket
 *                                             → PipeWire virtual source
 *
 * The AAudio capture callback is the sole producer into the ringbuffer.
 * The socket thread is the sole consumer, sending data to the Linux module.
 *
 * On device disconnect the AAudio stream is recreated without losing data
 * (ringbuffer bridges the gap).
 *
 * All Unix-socket operations use NDK <sys/socket.h> / <sys/un.h>.
 * All audio capture uses NDK <aaudio/AAudio.h>.
 */

#include <jni.h>
#include <android/log.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <aaudio/AAudio.h>

#define LOG_TAG "TinyMic-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ================================================================= */
/*  Lock-free SPSC ringbuffer                                        */
/* ================================================================= */
#define RING_SIZE_BYTES  (512u * 1024u)  /* 512 KB */

typedef struct {
    uint8_t     data[RING_SIZE_BYTES];
    atomic_uint write_idx;
    atomic_uint read_idx;
} ring_t;

static void ring_init(ring_t *r)
{
    atomic_init(&r->write_idx, 0);
    atomic_init(&r->read_idx,  0);
}

/* Producer (AAudio callback): returns bytes written. */
static uint32_t ring_write(ring_t *r, const uint8_t *src, uint32_t len)
{
    uint32_t w  = atomic_load_explicit(&r->write_idx, memory_order_relaxed);
    uint32_t rd = atomic_load_explicit(&r->read_idx,  memory_order_acquire);
    uint32_t used  = w - rd;
    uint32_t space = RING_SIZE_BYTES - used;
    if (len > space) len = space;
    if (len == 0) return 0;

    uint32_t off = w % RING_SIZE_BYTES;
    uint32_t first = RING_SIZE_BYTES - off;
    if (first > len) first = len;

    memcpy(r->data + off, src, first);
    if (len > first)
        memcpy(r->data, src + first, len - first);

    atomic_store_explicit(&r->write_idx, w + len, memory_order_release);
    return len;
}

/* Consumer (socket thread): returns bytes read. */
static uint32_t ring_read(ring_t *r, uint8_t *dst, uint32_t len)
{
    uint32_t rd = atomic_load_explicit(&r->read_idx,  memory_order_relaxed);
    uint32_t w  = atomic_load_explicit(&r->write_idx, memory_order_acquire);
    uint32_t avail = w - rd;
    if (len > avail) len = avail;
    if (len == 0) return 0;

    uint32_t off = rd % RING_SIZE_BYTES;
    uint32_t first = RING_SIZE_BYTES - off;
    if (first > len) first = len;

    memcpy(dst, r->data + off, first);
    if (len > first)
        memcpy(dst + first, r->data, len - first);

    atomic_store_explicit(&r->read_idx, rd + len, memory_order_release);
    return len;
}

/* ================================================================= */
/*  Global state                                                     */
/* ================================================================= */
typedef struct {
    ring_t        ring;

    /* ---- control ---- */
    atomic_bool   running;           /* cleared to stop                */
    atomic_bool   stream_setup;      /* AAudio thread must (re)open    */
    atomic_bool   aaudio_restart;    /* device disconnect → restart    */
    atomic_bool   stream_ready;      /* AAudio is streaming            */

    /* ---- format ---- */
    int32_t       sample_rate;
    int32_t       channel_count;
    int32_t       bytes_per_frame;

    /* ---- socket ---- */
    int            sock_fd;
    pthread_t      sock_thread;

    /* ---- AAudio (owned by capture callback context) ---- */
    AAudioStream  *stream;
} state_t;

static state_t g;

/* ================================================================= */
/*  Socket helpers (NDK)                                             */
/* ================================================================= */
static int write_all(int fd, const void *buf, size_t count)
{
    const uint8_t *p = buf;
    size_t remain = count;
    while (remain > 0) {
        ssize_t n = write(fd, p, remain);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p      += (size_t)n;
        remain -= (size_t)n;
    }
    return 0;
}

static void write_le32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v);
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static int connect_unix(const char *path)
{
    struct sockaddr_un addr;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket(): %s", strerror(errno));
        return -1;
    }
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("connect(%s): %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    LOGI("connected to %s (fd=%d)", path, fd);
    return fd;
}

/* ================================================================= */
/*  AAudio error callback                                            */
/* ================================================================= */
static void error_callback(AAudioStream *s, void *user, aaudio_result_t err)
{
    (void)s; (void)user;
    if (err == AAUDIO_ERROR_DISCONNECTED) {
        LOGI("AAudio capture device disconnected – queueing restart");
        atomic_store_explicit(&g.aaudio_restart, true, memory_order_release);
    } else {
        LOGE("AAudio capture error: %d", (int)err);
    }
}

/*
 * AAudio data callback – called when captured audio is available.
 *
 * Runs on a high-priority AAudio-internal thread.
 * Must NOT block or allocate.
 */
static aaudio_data_callback_result_t data_callback(
        AAudioStream *stream,
        void *user_data,
        void *audio_data,
        int32_t num_frames)
{
    (void)stream;
    (void)user_data;

    if (!atomic_load_explicit(&g.running, memory_order_acquire))
        return AAUDIO_CALLBACK_RESULT_STOP;

    uint32_t bytes = (uint32_t)num_frames * (uint32_t)g.bytes_per_frame;
    ring_write(&g.ring, (const uint8_t *)audio_data, bytes);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ================================================================= */
/*  AAudio capture stream management                                 */
/* ================================================================= */
static void stream_close(void)
{
    if (g.stream) {
        AAudioStream_close(g.stream);
        g.stream = NULL;
    }
    atomic_store_explicit(&g.stream_ready, false, memory_order_release);
}

/* Returns 0 on success. Called from the socket thread. */
static int stream_open(int32_t rate, int32_t channels)
{
    AAudioStreamBuilder *b = NULL;
    AAudioStream *s = NULL;
    aaudio_result_t res;

    stream_close();

    /* ── try exclusive low-latency ── */
    res = AAudio_createStreamBuilder(&b);
    if (res != AAUDIO_OK) { LOGE("createBuilder: %d", (int)res); return -1; }

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setErrorCallback(b, error_callback, NULL);
    AAudioStreamBuilder_setDataCallback(b, data_callback, NULL);

    res = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);

    if (res != AAUDIO_OK) {
        /* ── fallback: shared mode ── */
        LOGI("exclusive failed (%d), trying shared", (int)res);
        res = AAudio_createStreamBuilder(&b);
        if (res != AAUDIO_OK) return -1;

        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setSampleRate(b, rate);
        AAudioStreamBuilder_setChannelCount(b, channels);
        AAudioStreamBuilder_setErrorCallback(b, error_callback, NULL);
        AAudioStreamBuilder_setDataCallback(b, data_callback, NULL);

        res = AAudioStreamBuilder_openStream(b, &s);
        AAudioStreamBuilder_delete(b);
    }

    if (res != AAUDIO_OK) {
        LOGE("openStream: %d", (int)res);
        return -1;
    }

    if (AAudioStream_getFormat(s) != AAUDIO_FORMAT_PCM_FLOAT) {
        LOGE("PCM_FLOAT not supported (got %d)", (int)AAudioStream_getFormat(s));
        AAudioStream_close(s);
        return -1;
    }

    res = AAudioStream_requestStart(s);
    if (res != AAUDIO_OK) {
        LOGE("requestStart: %d", (int)res);
        AAudioStream_close(s);
        return -1;
    }

    g.stream          = s;
    g.sample_rate     = AAudioStream_getSampleRate(s);
    g.channel_count   = AAudioStream_getChannelCount(s);
    g.bytes_per_frame = g.channel_count * (int32_t)sizeof(float);

    atomic_store_explicit(&g.stream_ready, true, memory_order_release);

    LOGI("AAudio capture opened: rate=%d ch=%d burst=%d",
         (int)g.sample_rate, (int)g.channel_count,
         (int)AAudioStream_getFramesPerBurst(s));
    return 0;
}

/* ================================================================= */
/*  Socket writer thread                                             */
/*  ─────────────────────                                             */
/*  Sends format header, then reads ringbuffer → write to socket.    */
/*  Also manages AAudio capture stream lifecycle (open / restart).   */
/* ================================================================= */
static void *socket_thread_func(void *arg)
{
    (void)arg;
    int fd = g.sock_fd;

    LOGI("socket thread started (fd=%d)", fd);

    /* ── 1. Open AAudio capture ── */
    int32_t rate = g.sample_rate > 0 ? g.sample_rate : 48000;
    int32_t ch   = g.channel_count > 0 ? g.channel_count : 1;

    if (stream_open(rate, ch) != 0) {
        LOGE("failed to open AAudio capture");
        goto exit_thread;
    }

    /* ── 2. Send format header (3 × uint32_t LE) ── */
    {
        uint8_t hdr[12];
        write_le32(hdr + 0, (uint32_t)g.sample_rate);
        write_le32(hdr + 4, (uint32_t)g.channel_count);
        write_le32(hdr + 8, 0);  /* format: implied F32LE, unused by module */
        if (write_all(fd, hdr, sizeof(hdr)) < 0) {
            LOGE("failed to send format header: %s", strerror(errno));
            stream_close();
            goto exit_thread;
        }
        LOGI("sent format header: rate=%d ch=%d",
             (int)g.sample_rate, (int)g.channel_count);
    }

    /* ── 3. Stream loop ── */
    uint8_t buf[16384];
    while (atomic_load_explicit(&g.running, memory_order_acquire)) {

        /* Handle device disconnect → restart AAudio.
         * Drain ringbuffer before restarting: old capture data is stale. */
        if (atomic_exchange_explicit(&g.aaudio_restart, false,
                                     memory_order_acq_rel)) {
            int32_t saved_rate = g.sample_rate;
            int32_t saved_ch   = g.channel_count;
            if (saved_rate <= 0) saved_rate = 48000;
            if (saved_ch   <= 0) saved_ch   = 1;

            /* Close old stream (stops its data callback). */
            stream_close();

            /* Drain ringbuffer: old device data is useless. */
            {
                uint32_t w = atomic_load_explicit(&g.ring.write_idx,
                                                  memory_order_acquire);
                uint32_t rd = atomic_load_explicit(&g.ring.read_idx,
                                                   memory_order_relaxed);
                LOGI("mic restart: draining %u ringbuffer bytes", w - rd);
                atomic_store_explicit(&g.ring.read_idx, w,
                                      memory_order_release);
            }

            LOGI("restarting AAudio capture: rate=%d ch=%d",
                 (int)saved_rate, (int)saved_ch);

            if (stream_open(saved_rate, saved_ch) != 0) {
                LOGE("AAudio capture restart failed – retrying");
                atomic_store_explicit(&g.aaudio_restart, true,
                                      memory_order_release);
                usleep(50000);
            }
            continue;
        }

        /* Read from ringbuffer (poll, no blocking to stay responsive) */
        uint32_t got = ring_read(&g.ring, buf, sizeof(buf));
        if (got > 0) {
            if (write_all(fd, buf, got) < 0) {
                LOGI("socket write failed: %s", strerror(errno));
                break;
            }
        } else {
            /* No data yet – brief yield. The AAudio callback runs on
             * its own high-priority thread and will fill the ringbuffer. */
            usleep(2000);  /* 2 ms */
        }
    }

    stream_close();

    exit_thread:
    atomic_store_explicit(&g.running, false, memory_order_release);
    LOGI("socket thread stopped");
    return NULL;
}

/* ================================================================= */
/*  JNI entry points                                                 */
/* ================================================================= */

JNIEXPORT jboolean JNICALL
Java_com_fct_tc4_TinyMicrophone_nativeStart(JNIEnv *env, jclass cls,
                                            jstring socketPath)
{
    (void)cls;

    const char *path = (*env)->GetStringUTFChars(env, socketPath, NULL);
    if (!path) return JNI_FALSE;

    LOGI("nativeStart → %s", path);

    /* Reset global state */
    memset(&g, 0, sizeof(g));
    g.sock_fd = -1;
    ring_init(&g.ring);
    atomic_init(&g.running, true);
    atomic_init(&g.stream_setup, false);
    atomic_init(&g.aaudio_restart, false);
    atomic_init(&g.stream_ready, false);

    /* Hard-coded capture format: 48000 Hz mono F32 */
    g.sample_rate   = 48000;
    g.channel_count = 1;

    /* Connect to PipeWire module socket */
    {
        int fd = connect_unix(path);
        g.sock_fd = fd;
    }
    (*env)->ReleaseStringUTFChars(env, socketPath, path);

    if (g.sock_fd < 0)
        return JNI_FALSE;

    /* Launch socket writer (which also manages AAudio capture) */
    if (pthread_create(&g.sock_thread, NULL,
                       socket_thread_func, NULL) != 0) {
        LOGE("pthread_create(sock_thread) failed");
        close(g.sock_fd);
        g.sock_fd = -1;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_fct_tc4_TinyMicrophone_nativeStop(JNIEnv *env, jclass cls)
{
(void)env; (void)cls;
LOGI("nativeStop");

atomic_store_explicit(&g.running, false, memory_order_release);

/* Close socket (the socket thread polls running and will exit) */
if (g.sock_fd >= 0) {
shutdown(g.sock_fd, SHUT_RDWR);
close(g.sock_fd);
g.sock_fd = -1;
}

pthread_join(g.sock_thread, NULL);

LOGI("nativeStop complete");
}
