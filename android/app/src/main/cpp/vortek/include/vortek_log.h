#pragma once
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <android/log.h>

// Log diagnostik Vortek: logcat + Download/Wayland-logs/vortek.log (digabung dengan log Kotlin).
static inline void vortekLog(const char* fmt, ...) {
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "Vortek", "%s", msg);
    FILE* f = fopen("/storage/emulated/0/Download/Wayland-logs/vortek.log", "a");
    if (!f) return;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tmv;
    localtime_r(&ts.tv_sec, &tmv);
    fprintf(f, "%02d:%02d:%02d.%03ld [native] %s\n", tmv.tm_hour, tmv.tm_min, tmv.tm_sec, ts.tv_nsec / 1000000, msg);
    fclose(f);
}
