#include "core/trace.h"

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <unistd.h>

namespace xorg {
namespace {
std::mutex trace_mutex;
std::atomic<bool> trace_enabled{false};
std::chrono::steady_clock::time_point last_check{};
FILE* trace_file{};
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
            trace_enabled = want && trace_file;
        }
    }
    return trace_enabled;
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
    std::fprintf(trace_file, "[%9.3f] ", t);
    va_list args;
    va_start(args, format);
    std::vfprintf(trace_file, format, args);
    va_end(args);
    std::fputc('\n', trace_file);
    std::fflush(trace_file);
}
}  // namespace xorg
