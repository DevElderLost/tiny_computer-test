#include "core/trace.h"

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <string>
#include <unistd.h>

namespace xorg {
namespace {
std::mutex trace_mutex;
std::atomic<bool> trace_enabled{false};
std::chrono::steady_clock::time_point last_check{};
FILE* trace_file{};
// Salinan jejak di folder log aplikasi (Download/Wayland-logs, sama dengan log shortcut/container); null bila tidak tersedia.
FILE* trace_copy{};
std::chrono::steady_clock::time_point start_time = std::chrono::steady_clock::now();
}  // namespace

bool TraceOn() {
    const auto now = std::chrono::steady_clock::now();
    {
        std::lock_guard lock(trace_mutex);
        if (now - last_check >= std::chrono::seconds(1)) {
            last_check = now;
            const char* dir = std::getenv("XPP_TRACE_DIR");
            const bool want = dir && access((std::string(dir) + "/.xpp-trace").c_str(), F_OK) == 0;
            if (want && !trace_file) trace_file = std::fopen((std::string(dir) + "/xpp-trace.log").c_str(), "a");
            if (!want && trace_file) { std::fclose(trace_file); trace_file = nullptr; }
            // Salinan di XPP_TRACE_LOG_DIR: satu berkas baru `xpp-trace-<waktu>.log` tiap penanda dibuat; ditutup saat penanda hilang.
            const char* copy_dir = std::getenv("XPP_TRACE_LOG_DIR");
            if (want && !trace_copy && copy_dir && *copy_dir) {
                char stamp[32];
                const std::time_t wall = std::time(nullptr);
                std::tm tm_local{};
                localtime_r(&wall, &tm_local);
                std::strftime(stamp, sizeof(stamp), "%Y-%m-%d_%H-%M-%S", &tm_local);
                trace_copy = std::fopen((std::string(copy_dir) + "/xpp-trace-" + stamp + ".log").c_str(), "a");
            }
            if (!want && trace_copy) { std::fclose(trace_copy); trace_copy = nullptr; }
            trace_enabled = want && trace_file;
        }
    }
    return trace_enabled;
}

bool TraceOnFast() {
    static std::atomic<long long> nextCheck{0};
    const long long now = std::chrono::steady_clock::now().time_since_epoch().count();
    if (now >= nextCheck.load(std::memory_order_relaxed)) {
        nextCheck.store(now + std::chrono::duration_cast<std::chrono::steady_clock::duration>(std::chrono::seconds(1)).count(),
                        std::memory_order_relaxed);
        return TraceOn();
    }
    return trace_enabled.load(std::memory_order_relaxed);
}

bool CompositeDisplayRequested() {
    static std::atomic<bool> requested{false};
    static std::chrono::steady_clock::time_point last{};
    static std::mutex mutex;
    const auto now = std::chrono::steady_clock::now();
    std::lock_guard lock(mutex);
    if (now - last >= std::chrono::seconds(1)) {
        last = now;
        const char* dir = std::getenv("XPP_TRACE_DIR");
        requested = dir && access((std::string(dir) + "/.xpp-composite").c_str(), F_OK) == 0;
    }
    return requested;
}

void Trace(const char* format, ...) {
    if (!TraceOn()) return;
    std::lock_guard lock(trace_mutex);
    if (!trace_file) return;
    const double t = std::chrono::duration<double>(std::chrono::steady_clock::now() - start_time).count();
    for (FILE* out : {trace_file, trace_copy}) {
        if (!out) continue;
        std::fprintf(out, "[%9.3f] ", t);
        va_list args;
        va_start(args, format);
        std::vfprintf(out, format, args);
        va_end(args);
        std::fputc('\n', out);
        std::fflush(out);
    }
}
}  // namespace xorg
