// Server audio native Tiny Computer: soket unix (dari plugin ALSA di guest) -> AAudio, tanpa PulseAudio/OpenSL ES.
//
// Perbaikan dibanding jalur lama (pulseaudio + module-opensles lewat TCP):
//  * satu stream AAudio 48 kHz stereo I16 tetap dibuka; campuran semua klien dibuat di callback tanpa lock;
//  * buffer stream menyesuaikan diri: tiap xrun menaikkan buffer satu burst (tidak ada keresek berulang);
//  * rute berubah (headphone dicabut/dipasang, Bluetooth) -> stream dibuka ulang otomatis, suara tidak hilang;
//  * stream ditutup 3 detik setelah klien terakhir pergi dan dibuka lagi saat ada klien (tanpa "suspend on idle").
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <sys/resource.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <ctime>
#include <string>

extern "C" {
#include "tinyaudio_core.h"
}
#include "tinyaudio_proto.h"

#define TAG "TinyAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

ta_server *g_server = nullptr;
pthread_t g_thread;
std::atomic<bool> g_run{false};
std::atomic<bool> g_alive{false};
std::atomic<bool> g_need_reopen{false};
std::atomic<int> g_buffer_ms{40};
std::atomic<int> g_stream_state{0};      // 0 tutup, 1 terbuka
std::atomic<int> g_xruns{0};
std::atomic<int> g_buffer_frames{0};
AAudioStream *g_stream = nullptr;
std::string g_path;

int64_t nowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

aaudio_data_callback_result_t onData(AAudioStream *, void *, void *audio, int32_t frames) {
    ta_server_render(g_server, static_cast<int16_t *>(audio), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// Dipanggil di thread AAudio: tidak boleh menutup stream di sini, cukup beri tahu thread server.
void onError(AAudioStream *, void *, aaudio_result_t error) {
    LOGW("AAudio error %d (%s) -> stream dibuka ulang", error, AAudio_convertResultToText(error));
    g_need_reopen = true;
}

bool openStream() {
    for (int attempt = 0; attempt < 2; attempt++) {
        AAudioStreamBuilder *b = nullptr;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(b, attempt == 0 ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
                                                               : AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(b, 2);
        AAudioStreamBuilder_setSampleRate(b, TA_OUT_RATE);
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(b, onData, nullptr);
        AAudioStreamBuilder_setErrorCallback(b, onError, nullptr);
        AAudioStream *s = nullptr;
        aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK) {
            LOGW("openStream(mode %d) gagal: %s", attempt, AAudio_convertResultToText(r));
            continue;
        }
        int32_t burst = AAudioStream_getFramesPerBurst(s);
        int32_t cap = AAudioStream_getBufferCapacityInFrames(s);
        int32_t want = g_buffer_ms.load() * (TA_OUT_RATE / 1000);
        if (want < burst * 2) want = burst * 2;
        if (cap > 0 && want > cap) want = cap;
        int32_t got = AAudioStream_setBufferSizeInFrames(s, want);
        r = AAudioStream_requestStart(s);
        if (r != AAUDIO_OK) {
            LOGW("requestStart gagal: %s", AAudio_convertResultToText(r));
            AAudioStream_close(s);
            continue;
        }
        g_stream = s;
        g_xruns = 0;
        g_buffer_frames = got > 0 ? got : want;
        g_stream_state = 1;
        LOGI("AAudio terbuka: rate=%d burst=%d buffer=%d/%d frame perfMode=%d", AAudioStream_getSampleRate(s), burst,
             g_buffer_frames.load(), cap, AAudioStream_getPerformanceMode(s));
        return true;
    }
    return false;
}

void closeStream() {
    if (!g_stream) return;
    AAudioStream_requestStop(g_stream);
    AAudioStream_close(g_stream);   // menunggu callback terakhir selesai
    g_stream = nullptr;
    g_stream_state = 0;
}

// Naikkan buffer satu burst tiap ada xrun baru (batas 150 ms); inilah yang menghentikan keresek berulang
// saat CPU penuh oleh emulator.
void tuneBuffer() {
    if (!g_stream) return;
    int32_t x = AAudioStream_getXRunCount(g_stream);
    if (x <= g_xruns.load()) return;
    g_xruns = x;
    int32_t burst = AAudioStream_getFramesPerBurst(g_stream);
    int32_t cap = AAudioStream_getBufferCapacityInFrames(g_stream);
    int32_t cur = AAudioStream_getBufferSizeInFrames(g_stream);
    int32_t next = cur + burst;
    int32_t limit = 150 * (TA_OUT_RATE / 1000);
    if (cap > 0 && limit > cap) limit = cap;
    if (next > limit) return;
    int32_t got = AAudioStream_setBufferSizeInFrames(g_stream, next);
    if (got > 0) g_buffer_frames = got;
    LOGI("xrun #%d -> buffer %d frame", x, got);
}

void *serverThread(void *) {
    setpriority(PRIO_PROCESS, gettid(), -16);   // boleh gagal; thread ini mengisi ring untuk callback
    int64_t idleSince = 0, lastTry = 0;
    while (g_run.load()) {
        int clients = ta_server_pump(g_server, 2);
        int64_t now = nowMs();
        if (g_need_reopen.exchange(false)) { closeStream(); lastTry = 0; }
        if (clients > 0) {
            idleSince = 0;
            if (!g_stream && now - lastTry > 300) { lastTry = now; openStream(); }
            tuneBuffer();
        } else {
            if (idleSince == 0) idleSince = now;
            if (g_stream && now - idleSince > 3000) closeStream();
            if (!g_stream) usleep(20000);       // tanpa klien: pump sudah menunggu di poll, cukup santai
        }
    }
    closeStream();
    g_alive = false;
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tiny_1computer_core_TinyAudio_nativeStart(JNIEnv *env, jobject, jstring path, jint prefillMs, jint bufferMs) {
    if (g_run.load()) return JNI_TRUE;
    signal(SIGPIPE, SIG_IGN);
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    g_path = p;
    env->ReleaseStringUTFChars(path, p);
    g_server = ta_server_create(g_path.c_str());
    if (!g_server) { LOGE("bind %s gagal", g_path.c_str()); return JNI_FALSE; }
    ta_server_set_prefill_ms(g_server, prefillMs);
    g_buffer_ms = bufferMs < 10 ? 10 : (bufferMs > 200 ? 200 : bufferMs);
    g_need_reopen = false;
    g_run = true;
    g_alive = true;
    if (pthread_create(&g_thread, nullptr, serverThread, nullptr) != 0) {
        g_run = false; g_alive = false;
        ta_server_destroy(g_server); g_server = nullptr;
        return JNI_FALSE;
    }
    LOGI("server audio native aktif di %s (prefill %d ms, buffer %d ms)", g_path.c_str(), prefillMs, g_buffer_ms.load());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_tiny_1computer_core_TinyAudio_nativeStop(JNIEnv *, jobject) {
    if (!g_run.exchange(false)) return;
    pthread_join(g_thread, nullptr);
    ta_server_destroy(g_server);
    g_server = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_tiny_1computer_core_TinyAudio_nativeRunning(JNIEnv *, jobject) {
    return g_run.load() ? JNI_TRUE : JNI_FALSE;
}

// [klien, underrun server, xrun AAudio, buffer frame, stream terbuka]
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_tiny_1computer_core_TinyAudio_nativeStats(JNIEnv *env, jobject) {
    jlong v[5] = {0, 0, 0, 0, 0};
    if (g_run.load() && g_server) {
        v[0] = ta_server_clients(g_server);
        v[1] = ta_server_underruns(g_server);
        v[2] = g_xruns.load();
        v[3] = g_buffer_frames.load();
        v[4] = g_stream_state.load();
    }
    jlongArray a = env->NewLongArray(5);
    env->SetLongArrayRegion(a, 0, 5, v);
    return a;
}
