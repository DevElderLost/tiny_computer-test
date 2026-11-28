#include <jni.h>
#include <android/native_window_jni.h>
#include "core/xserver.h"
#include "core/keyboard.h"
#include "core/mouse.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/atom.h"
#include "native_screen.h"
#include "native_icc.h"
#include "native_drawable.h"
#include <cstdarg>
#include <cstdio>
#include <android/hardware_buffer.h>
#include <map>

namespace {
std::mutex sessions_mutex;
std::map<jlong, sp<xorg::XServer>> sessions;
jlong next_session = 1;
sp<xorg::XServer> session(jlong handle) {
    std::lock_guard lock(sessions_mutex);
    auto i = sessions.find(handle); return i == sessions.end() ? nullptr : i->second;
}
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct JavaString {
    JNIEnv* env; jstring value; const char* text;
    JavaString(JNIEnv* e, jstring s) : env(e), value(s), text(e->GetStringUTFChars(s, nullptr)) {}
    ~JavaString() { if (text) env->ReleaseStringUTFChars(value, text); }
};
bool attach(JavaVM* vm, JNIEnv** env) {
#ifdef __ANDROID__
    return vm->AttachCurrentThread(env, nullptr) == JNI_OK;
#else
    return vm->AttachCurrentThread(reinterpret_cast<void**>(env), nullptr) == JNI_OK;
#endif
}
struct WindowListener {
    JavaVM* vm{};
    jobject view{};
    jmethodID mapped{};
    ~WindowListener() {
        JNIEnv* env{};
        bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attached && !attach(vm, &env)) return;
        env->DeleteGlobalRef(view);
        if (attached) vm->DetachCurrentThread();
    }
    void notify(xorg::Window* window) {
        auto property = [&](const char* name) {
            auto atom = xorg::Atom::GetID(name);
            return atom >= 0 ? window->GetProperty(atom) : nullptr;
        };
        u32 pid{};
        if (auto* p = property("_NET_WM_PID"); p && p->GetData().size() >= sizeof(pid))
            memcpy(&pid, p->GetData().data(), sizeof(pid));
        auto* wow = property("_NET_WM_WOW64");
        bool isWow = wow && !wow->GetData().empty() && wow->GetData()[0] == 1;
        std::vector<jchar> name;
        if (auto* p = property("WM_CLASS"))
            for (u8 c : p->GetData()) { if (!c) break; name.push_back(c); }
        if (!pid && name.empty()) return;
        JNIEnv* env{};
        bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
        if (attached && !attach(vm, &env)) return;
        auto text = env->NewString(name.data(), jsize(name.size()));
        if (text) {
            env->CallVoidMethod(view, mapped, jint(pid), text, jboolean(isWow));
            env->DeleteLocalRef(text);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) vm->DetachCurrentThread();
    }
};
}

// ---- Jembatan Vortek: window X -> AHardwareBuffer yang ditulis renderer Vulkan Vortek ----
// Vortek (ICD guest) menggambar ke AHB milik window; X++ menampilkannya lewat jalur yang sama dengan
// Present DRI3 (drawable AHB + PresentTicket), sehingga tidak ada salinan CPU.
namespace {
void vortekBridgeLog(const char* fmt, ...) {
    char msg[300]; va_list ap; va_start(ap, fmt); vsnprintf(msg, sizeof(msg), fmt, ap); va_end(ap);
    if (FILE* f = fopen("/storage/emulated/0/Download/Wayland-logs/vortek.log", "a")) { fprintf(f, "           [xpp] %s\n", msg); fclose(f); }
}
struct VortekWindowBuffer { sp<xorg::NativeDrawable> drawable; u16 w{}, h{}; bool bgra{}; };
std::mutex vortek_mutex;
std::map<std::pair<jlong, u32>, VortekWindowBuffer> vortek_buffers;
xorg::Window* vortekWindow(xorg::XServer& server, u32 id) {
    auto* r = server.GetResource(id);
    return r && r->GetType() == xorg::XResourceType::Window ? r->As<xorg::Window>() : nullptr;
}
void vortekClear(jlong handle) {
    std::lock_guard lock(vortek_mutex);
    std::erase_if(vortek_buffers, [handle](const auto& e) { return e.first.first == handle; });
}
}
#define JNI_NAME(name) Java_com_example_tiny_1computer_xpp_NativeX11_##name
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeWindowListener)(JNIEnv* env, jclass, jlong handle, jobject view) {
    auto server = session(handle); if (!server) return;
    auto listener = std::make_shared<WindowListener>();
    env->GetJavaVM(&listener->vm);
    listener->view = env->NewGlobalRef(view);
    auto cls = env->GetObjectClass(view);
    listener->mapped = env->GetMethodID(cls, "onNativeWindowMapped", "(ILjava/lang/String;Z)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    if (!listener->view || !listener->mapped) return;
    auto lock = server->Lock();
    server->GetWindowManager().onWindowMapped = [listener](xorg::Window* window) { listener->notify(window); };
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeCreate)(JNIEnv* env, jclass, jint width, jint height, jboolean validation,
        jstring driverPath, jstring libraryName, jstring nativeLibDir, jint presentMode, jboolean swapRB, jint backend, jint flags) {
    try {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw std::runtime_error("Invalid native X11 resolution");
        JavaString path(env, driverPath), library(env, libraryName), nativeLib(env, nativeLibDir);
        if (!path.text || !library.text || !nativeLib.text) return 0;
        xorg::NativeRendererConfig config;
        config.driverPath = path.text; config.libraryName = library.text; config.nativeLibDir = nativeLib.text;
        config.swapRB = swapRB == JNI_TRUE; config.presentMode = presentMode >= 0 && presentMode <= 3 ? presentMode : 2;
        config.backend = backend == 2 ? xorg::NativeRendererConfig::Backend::DisplayX
                : backend == 1 ? xorg::NativeRendererConfig::Backend::EGL : xorg::NativeRendererConfig::Backend::Vulkan;
        config.trueDisplayX = flags & 1; config.performanceMode = flags & 2;
        config.presentAtRefreshRate = flags & 4; config.precisePresentation = flags & 8;
        config.disableShm = (flags & 16) != 0;
        config.disableDri3 = (flags & 32) != 0;
        config.disableRender = (flags & 64) != 0;
        config.disableComposite = (flags & 128) != 0;
        config.disableXkb = (flags & 256) != 0;
        // bit 12-15: jumlah gambar swapchain; bit 16-30: batas memori renderer (MiB)
        config.bufferCount = (flags >> 12) & 0xF;
        config.memLimitMb = (flags >> 16) & 0x7FFF;
        auto server = std::make_shared<xorg::XServer>(nullptr, xorg::ScreenInfo(width, height, 4),
                validation == JNI_TRUE, std::move(config));
        std::lock_guard lock(sessions_mutex); jlong handle = next_session++;
        sessions.emplace(handle, std::move(server)); return handle;
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeStart)(JNIEnv* env, jclass, jlong handle, jstring path, jstring shm) {
    auto server = session(handle); if (!server) return;
    JavaString socketPath(env, path), shmPath(env, shm);
    if (!socketPath.text || !shmPath.text) return;
    try { auto lock = server->Lock(); server->Start(socketPath.text, shmPath.text); }
    catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeDestroy)(JNIEnv*, jclass, jlong handle) {
    sp<xorg::XServer> server;
    { std::lock_guard lock(sessions_mutex); auto i = sessions.find(handle);
      if (i != sessions.end()) { server = std::move(i->second); sessions.erase(i); } }
    vortekClear(handle);
    // Release outside registry lock: shutdown joins native worker threads.
    server.reset();
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeSurface)(JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto server = session(handle); if (!server) return;
    auto* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    server->UpdateSurface(window);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeFrameTimeline)(JNIEnv*, jclass, jlong handle, jlong id) {
    auto server = session(handle);
    if (server) static_cast<xorg::NativeScreen&>(server->GetScreen()).SetFrameTimeline(id);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeInput)(JNIEnv* env, jclass, jlong handle, jint kind, jint a, jint b) {
    auto server = session(handle); if (!server) return;
    try {
        auto lock = server->Lock();
        if (kind == 0) server->GetMouse().MoveTo(a, b);
        else if (kind == 3) server->GetMouse().MoveBy(a, b);
        else if (kind == 4) server->GetMouse().AnchorTo(a, b);
        else if (kind == 5) server->GetMouse().SetRawInputEnabled(a != 0);
        else if (kind == 1) server->GetMouse().SetButton(a, b != 0);
        else if (kind == 2) server->GetKeyboard().SetKeyState(static_cast<xorg::XKeycode>(a), b != 0);
    } catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeOptions)(JNIEnv*, jclass, jlong handle, jboolean fullscreen,
                                                          jboolean cursor, jint fps, jfloat refresh) {
    auto server = session(handle); if (!server) return;
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetOptions(fullscreen, cursor, fps, refresh);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeMagnifierZoom)(JNIEnv*, jclass, jlong handle, jfloat zoom) {
    auto server = session(handle); if (!server) return;
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetMagnifierZoom(zoom);
}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(nativeError)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return env->NewStringUTF("");
    auto message = static_cast<xorg::NativeScreen&>(server->GetScreen()).LastError();
    return env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeVisualOptions)(JNIEnv* env, jclass, jlong handle,
        jint filter, jint postFX, jint stretch, jfloat sharpness, jboolean frameGen,
        jstring cache, jint multiplier, jint targetRate, jint flowScale, jfloat refresh, jint engine,
        jfloat brightness, jfloat contrast, jfloat gamma, jfloat saturation) {
    auto server = session(handle); if (!server) return 0;
    xorg::NativeVisualOptions options;
    options.filter = filter; options.postFX = postFX; options.stretch = stretch; options.sharpness = sharpness;
    options.frameGen = frameGen; options.multiplier = multiplier; options.targetRate = targetRate;
    options.flowScale = flowScale; options.refresh = refresh;
    options.frameGenEngine = engine == 1 ? 1 : 0;
    options.brightness = brightness; options.contrast = contrast; options.gamma = gamma; options.saturation = saturation;
    if (cache) {
        JavaString path(env, cache);
        if (!path.text) return 0;
        options.frameGenCache = path.text;
    }
    return static_cast<xorg::NativeScreen&>(server->GetScreen()).SetVisualOptions(std::move(options));
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_NAME(nativeVisualState)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return nullptr;
    const auto state = static_cast<xorg::NativeScreen&>(server->GetScreen()).VisualState();
    const jlong values[] = {jlong(state[0]), jlong(state[1]), jlong(state[2])};
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeDiagnostics)(JNIEnv*, jclass, jlong handle, jboolean enabled, jboolean commandCacheEnabled) {
    auto server = session(handle); if (!server) return;
    auto& screen = static_cast<xorg::NativeScreen&>(server->GetScreen());
    screen.SetCommandCacheEnabled(commandCacheEnabled);
    screen.SetDiagnostics(enabled);
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeUnviewableWMClasses)(JNIEnv* env, jclass, jlong handle, jobjectArray values) {
    auto server = session(handle); if (!server) return;
    std::vector<std::string> classes;
    const jsize count = values ? env->GetArrayLength(values) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        if (env->ExceptionCheck()) return;
        if (!value) continue;
        {
            JavaString text(env, value);
            if (text.text) classes.emplace_back(text.text);
        }
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return;
    }
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetUnviewableWMClasses(std::move(classes));
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeRefreshRates)(JNIEnv* env, jclass, jlong handle, jintArray values) {
    auto server = session(handle); if (!server || !values) return;
    const jsize count = env->GetArrayLength(values);
    if (count < 1 || count > 1024) return;
    std::vector<jint> input(count);
    env->GetIntArrayRegion(values, 0, count, input.data());
    if (env->ExceptionCheck()) return;
    std::vector<u16> rates;
    for (jint hz : input) if (hz > 0 && hz <= 1000) rates.push_back(hz);
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetRandrRates(std::move(rates));
}

// Profil ICC layar: byte[] berisi berkas .icc/.icm; null atau array kosong = kembali ke sRGB bawaan.
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeIccProfile)(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    auto server = session(handle); if (!server) return JNI_FALSE;
    std::vector<u8> profile;
    const jsize count = data ? env->GetArrayLength(data) : 0;
    if (count > 0) {
        if (static_cast<size_t>(count) > xorg::kMaxIccProfileSize) return JNI_FALSE;
        profile.resize(count);
        env->GetByteArrayRegion(data, 0, count, reinterpret_cast<jbyte*>(profile.data()));
        if (env->ExceptionCheck() || !xorg::IsValidIccProfile(profile)) return JNI_FALSE;
    }
    auto lock = server->Lock();
    xorg::InstallIccProfile(*server, profile);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeHasContent)(JNIEnv*, jclass, jlong handle) {
    auto server = session(handle); if (!server) return false;
    return static_cast<xorg::NativeScreen&>(server->GetScreen()).presentedCount.load() != 0;
}

namespace {
uint64_t windowHandle(xorg::Window* w) {
    for (; w; w = w->GetParent()) {
        auto atom = xorg::Atom::GetID("_NET_WM_HWND");
        auto* property = atom >= 0 ? w->GetProperty(atom) : nullptr;
        if (!property) continue;
        auto data = property->GetData(); uint64_t handle{};
        if (data.size() >= 4) memcpy(&handle, data.data(), std::min(data.size(), sizeof(handle)));
        if (handle) return handle;
    }
    return 0;
}
bool findHandle(xorg::Window* w, uint64_t handle) {
    if (w->IsMapped() && windowHandle(w) == handle) return true;
    for (auto* child : w->GetChildren()) if (findHandle(child, handle)) return true;
    return false;
}
}
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeFocusHandle)(JNIEnv*, jclass, jlong handle) {
    auto server = session(handle); if (!server) return 0;
    auto lock = server->Lock(); return windowHandle(server->GetWindowManager().GetFocuseWindow());
}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeHasWindow)(JNIEnv*, jclass, jlong sessionHandle, jlong hwnd) {
    auto server = session(sessionHandle); if (!server || !hwnd) return false;
    auto lock = server->Lock(); return findHandle(server->GetWindowManager().GetRootWindow(), hwnd);
}

extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(nativeStartupContent)(JNIEnv*, jclass, jlong handle) {
    auto server = session(handle); if (!server) return JNI_FALSE;
    return static_cast<xorg::NativeScreen&>(server->GetScreen()).HasStartupContent() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeHudEnabled)(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    auto server = session(handle); if (!server) return;
    auto lock = server->Lock();
    static_cast<xorg::NativeScreen&>(server->GetScreen()).SetHudEnabled(enabled);
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_NAME(nativeHudSnapshot)(JNIEnv* env, jclass, jlong handle) {
    auto server = session(handle); if (!server) return nullptr;
    std::string values[4];
    {
        auto lock = server->Lock();
        auto& screen = static_cast<xorg::NativeScreen&>(server->GetScreen());
        auto* r = server->GetResource(screen.HudWindow());
        auto* window = r && r->GetType() == xorg::XResourceType::Window ? r->As<xorg::Window>() : nullptr;
        for (auto* w = window; w; w = w->GetParent()) if (!w->IsMapped()) { window = nullptr; break; }
        values[0] = std::to_string(window ? window->id : 0);
        values[1] = std::to_string(screen.HudFrames());
        const char* names[] = {"_MESA_DRV_ENGINE_NAME", "_MESA_DRV_GPU_NAME"};
        for (int i = 0; window && i < 2; ++i) {
            auto atom = xorg::Atom::GetID(names[i]);
            auto* property = atom >= 0 ? window->GetProperty(atom) : nullptr;
            if (!property) continue;
            for (u8 c : property->GetData()) {
                if (!c || values[i+2].size() == 256) break;
                values[i+2] += c >= 32 && c < 127 ? char(c) : '?';
            }
        }
    }
    auto stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    auto result = env->NewObjectArray(4, stringClass, nullptr); env->DeleteLocalRef(stringClass);
    if (!result) return nullptr;
    for (int i = 0; i < 4; ++i) {
        auto value = env->NewStringUTF(values[i].c_str()); if (!value) return nullptr;
        env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}


extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeVortekWindowWidth)(JNIEnv*, jclass, jlong handle, jint id) {
    auto server = session(handle); if (!server) return 0;
    auto lock = server->Lock(); auto* w = vortekWindow(*server, u32(id)); return w ? jint(w->GetWidth()) : 0;
}
extern "C" JNIEXPORT jint JNICALL JNI_NAME(nativeVortekWindowHeight)(JNIEnv*, jclass, jlong handle, jint id) {
    auto server = session(handle); if (!server) return 0;
    auto lock = server->Lock(); auto* w = vortekWindow(*server, u32(id)); return w ? jint(w->GetHeight()) : 0;
}
// Mengembalikan AHardwareBuffer* (0 bila gagal). Dipakai ulang selama ukuran dan format window tetap.
extern "C" JNIEXPORT jlong JNICALL JNI_NAME(nativeVortekWindowBuffer)(JNIEnv*, jclass, jlong handle, jint id, jboolean bgra) {
    auto server = session(handle); if (!server) return 0;
    auto lock = server->Lock();
    auto* window = vortekWindow(*server, u32(id)); if (!window) { vortekBridgeLog("buffer: window 0x%x tidak ada", u32(id)); return 0; }
    const u16 w = u16(window->GetWidth()), h = u16(window->GetHeight());
    if (!w || !h) { vortekBridgeLog("buffer window=0x%x ukuran 0 (%ux%u)", u32(id), w, h); return 0; }
    std::lock_guard cache(vortek_mutex);
    auto& slot = vortek_buffers[{handle, u32(id)}];
    if (slot.drawable && slot.w == w && slot.h == h && slot.bgra == (bgra == JNI_TRUE) && slot.drawable->HardwareBuffer())
        return reinterpret_cast<jlong>(slot.drawable->HardwareBuffer());
    AHardwareBuffer_Desc desc{};
    desc.width = w; desc.height = h; desc.layers = 1;
    // 5 = HAL_PIXEL_FORMAT_BGRA_8888 (sama dengan GPUImage Winlator)
    desc.format = bgra == JNI_TRUE ? 5u : uint32_t(AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    AHardwareBuffer* ahb = nullptr;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || !ahb) { slot = {}; return 0; }
    try {
        // NativeDrawable memegang referensinya sendiri (AHardwareBuffer_acquire); lepas referensi alokasi.
        slot.drawable = std::make_shared<xorg::NativeDrawable>(w, h, window->GetDepth(), ahb);
    } catch (...) { AHardwareBuffer_release(ahb); slot = {}; return 0; }
    AHardwareBuffer_release(ahb);
    slot.w = w; slot.h = h; slot.bgra = bgra == JNI_TRUE;
    vortekBridgeLog("buffer window=0x%x %ux%u bgra=%d ahb=%p", u32(id), w, h, int(slot.bgra), (void*)slot.drawable->HardwareBuffer());
    return reinterpret_cast<jlong>(slot.drawable->HardwareBuffer());
}
// Isi AHB window baru selesai ditulis: tampilkan lewat jalur Present.
extern "C" JNIEXPORT void JNICALL JNI_NAME(nativeVortekWindowUpdate)(JNIEnv*, jclass, jlong handle, jint id) {
    auto server = session(handle); if (!server) return;
    auto lock = server->Lock();
    if (!vortekWindow(*server, u32(id))) return;
    sp<xorg::NativeDrawable> drawable;
    { std::lock_guard cache(vortek_mutex);
      auto i = vortek_buffers.find({handle, u32(id)}); if (i == vortek_buffers.end()) return; drawable = i->second.drawable; }
    if (!drawable) return;
    { static int updates = 0; updates++; if (updates <= 5 || updates % 300 == 0) vortekBridgeLog("update #%d window=0x%x", updates, u32(id)); }
    auto ticket = std::make_shared<xorg::PresentTicket>();
    ticket->window = u32(id); ticket->drawable = std::move(drawable);
    static_cast<xorg::NativeScreen&>(server->GetScreen()).Present(std::move(ticket));
}
