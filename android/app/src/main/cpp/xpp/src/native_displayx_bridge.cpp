#include "native_displayx_bridge.h"
#include "native_screen.h"
#include "core/xserver.h"
#include "core/window.h"
#include "base/log.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <set>
#include <dlfcn.h>
#include <android/hardware_buffer.h>
#include "displayx_protocol.hpp"

namespace xorg {
namespace {
// ---- Alokasi buffer oleh host untuk guest glibc (protokol v2, opcode CreateHost) ----
// AHardwareBuffer tidak bisa dibuat proses glibc, jadi host yang membuatnya lalu memberi guest dma-buf-nya.
// Kita pakai usage CPU_*_RARELY supaya gralloc memilih layout LINEAR (tanpa UBWC): modifier yang dilaporkan ke guest
// selalu DRM_FORMAT_MOD_LINEAR, jadi guest tidak perlu menebak layout vendor.
struct NativeHandleView { int version, numFds, numInts; int data[1]; };
using GetNativeHandleFn = const NativeHandleView *(*)(const AHardwareBuffer *);
GetNativeHandleFn NativeHandleApi() {
    // AHardwareBuffer_getNativeHandle baru ada di API 31; dicari lewat dlsym agar minSdk tidak naik.
    static const auto fn = reinterpret_cast<GetNativeHandleFn>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle"));
    return fn;
}
// fd dma-buf milik buffer, DIPINJAM (jangan ditutup) bila API 31+ ada; selain itu -1.
int BorrowedDmaBufFd(AHardwareBuffer *buffer) {
    auto api = NativeHandleApi();
    if (!api) return -1;
    const NativeHandleView *handle = api(buffer);
    return handle && handle->numFds >= 1 ? handle->data[0] : -1;
}
// Cadangan untuk API < 31: serialisasi buffer lewat socketpair dan ambil fd pertamanya dari SCM_RIGHTS. Hasilnya fd
// milik kita (harus ditutup).
int ExtractDmaBufFdViaSocket(AHardwareBuffer *buffer) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair)) return -1;
    int result = -1;
    if (AHardwareBuffer_sendHandleToUnixSocket(buffer, pair[0]) == 0) {
        char data[4096];
        iovec io{data, sizeof(data)};
        alignas(cmsghdr) char control[CMSG_SPACE(32 * sizeof(int))]{};
        msghdr message{};
        message.msg_iov = &io; message.msg_iovlen = 1;
        message.msg_control = control; message.msg_controllen = sizeof(control);
        ssize_t count;
        do { count = recvmsg(pair[1], &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC); } while (count < 0 && errno == EINTR);
        if (count >= 0) {
            for (auto *h = CMSG_FIRSTHDR(&message); h; h = CMSG_NXTHDR(&message, h)) {
                if (h->cmsg_level != SOL_SOCKET || h->cmsg_type != SCM_RIGHTS) continue;
                const size_t n = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                for (size_t i = 0; i < n; ++i) {
                    int value;
                    memcpy(&value, CMSG_DATA(h) + i * sizeof(int), sizeof(value));
                    if (result < 0) result = value; else close(value);   // fd pertama = dma-buf
                }
            }
        }
    }
    close(pair[0]);
    close(pair[1]);
    return result;
}
struct HostBuffer {
    AHardwareBuffer *buffer{};   // dipegang sampai NativeDrawable mengambil acquire sendiri
    int fd{-1};                  // fd yang akan dikirim
    bool ownsFd{};               // true bila fd hasil ExtractDmaBufFdViaSocket
    u32 strideBytes{};
    ~HostBuffer() {
        if (ownsFd && fd >= 0) close(fd);
        if (buffer) AHardwareBuffer_release(buffer);
    }
    HostBuffer() = default;
    HostBuffer(const HostBuffer &) = delete;
    HostBuffer &operator=(const HostBuffer &) = delete;
};
// false bila gralloc menolak atau dma-buf tidak bisa diambil.
bool AllocateHostBuffer(u32 width, u32 height, HostBuffer &out) {
    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    if (AHardwareBuffer_allocate(&desc, &out.buffer) != 0 || !out.buffer) { out.buffer = nullptr; return false; }
    AHardwareBuffer_Desc real{};
    AHardwareBuffer_describe(out.buffer, &real);
    out.strideBytes = real.stride * 4;
    out.fd = BorrowedDmaBufFd(out.buffer);
    if (out.fd < 0) {
        out.fd = ExtractDmaBufFdViaSocket(out.buffer);
        out.ownsFd = out.fd >= 0;
    }
    return out.fd >= 0 && out.strideBytes >= width * 4;
}
struct Peer {
    int fd;
    std::atomic<bool> connected{true};
    std::mutex output;
    std::set<u8> ids;
    explicit Peer(int value) : fd(value) {}
    ~Peer() {
        close(fd);
    }
    void Stop() {
        if (connected.exchange(false))
            shutdown(fd, SHUT_RDWR);
    }
    void Complete(u8 id, u32 index, u64 present, u32 mode) {
        if (present == UINT64_MAX && mode != 2)
            return;
        const auto packet = displayx_wire::completion({{id, present, index}});
        std::lock_guard lock(output);
        if (connected && !displayx_wire::sendPacket(fd, packet)) Stop();
    }
};
} // namespace
struct NativeDisplayXBridge::Impl {
    XServer &server;
    int listener{-1};
    std::atomic<bool> stopped{false};
    std::thread acceptThread;
    std::mutex clientsMutex;
    struct Client {
        sp<Peer> peer;
        std::thread worker;
    };
    std::vector<Client> clients;
    struct Swapchain {
        u32 window{}, mode{}, colorSpace{};
        std::array<float, 12> hdr{};
        bool hasHdr{};
        u64 generation{};
        std::vector<sp<NativeDrawable>> images;
        sp<NativeExternalFrame> latest;
    };
    explicit Impl(XServer &value) : server(value) {
        clients.reserve(16);
        listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        if (listener < 0)
            throw std::runtime_error("Cannot create X++ DisplayX+ socket");
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        memcpy(address.sun_path + 1, "displayx", 8);
        if (bind(listener, reinterpret_cast<sockaddr *>(&address), offsetof(sockaddr_un, sun_path) + 9) ||
            listen(listener, 8)) {
            close(listener);
            listener = -1;
            throw std::runtime_error("X++ DisplayX+ socket is already in use");
        }
        try {
            acceptThread = std::thread([this] { Accept(); });
        } catch (...) {
            close(listener);
            throw;
        }
    }
    ~Impl() {
        stopped = true;
        shutdown(listener, SHUT_RDWR);
        if (acceptThread.joinable())
            acceptThread.join();
        close(listener);
        for (auto &client : clients)
            client.peer->Stop();
        for (auto &client : clients)
            if (client.worker.joinable())
                client.worker.join();
    }
    void Accept() {
        while (!stopped) {
            int fd = accept4(listener, nullptr, nullptr, SOCK_CLOEXEC);
            if (fd < 0) {
                if (errno == EINTR)
                    continue;
                return;
            }
            ucred credentials{};
            socklen_t length = sizeof(credentials);
            if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &length) ||
                credentials.uid != getuid()) {
                close(fd);
                continue;
            }
            timeval timeout{2, 0};
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            auto peer = std::make_shared<Peer>(fd);
            std::lock_guard lock(clientsMutex);
            // Reap disconnected workers; descriptor reuse cannot target a previous peer.
            for (auto i = clients.begin(); i != clients.end();) {
                if (!i->peer->connected) {
                    if (i->worker.joinable())
                        i->worker.join();
                    i = clients.erase(i);
                } else
                    ++i;
            }
            if (clients.size() >= 16) {
                peer->Stop();
                continue;
            }
            try {
                clients.push_back({peer, std::thread([this, peer] { Run(peer); })});
            } catch (const std::system_error &) {
                peer->Stop();
            }
        }
    }
    void Run(const sp<Peer> &peer) {
        std::map<u8, Swapchain> chains;
        auto remove = [&](Swapchain &chain) {
            auto lock = server.Lock();
            static_cast<NativeScreen &>(server.GetScreen())
                .RemoveExternal(chain.window, chain.generation, chain.latest);
            chain.latest.reset();
        };
        try {
            while (!stopped && peer->connected) {
                pollfd input{peer->fd, POLLIN, 0};
                int ready;
                do {
                    ready = poll(&input, 1, 100);
                } while (ready < 0 && errno == EINTR);
                if (ready == 0)
                    continue;
                if (ready < 0 || (input.revents & (POLLERR | POLLHUP | POLLNVAL)))
                    break;
                displayx_wire::Packet packet;
                if (!displayx_wire::receive(peer->fd, packet)) break;
                const u32 opcode = packet.get<u32>(0);
                if (opcode == displayx_wire::Create || opcode == displayx_wire::CreateHost) {
                    // Create (v1): guest bionic mengirim AHardwareBuffer. CreateHost (v2): host yang mengalokasikan dan
                    // mengirim dma-buf ke guest glibc. Sisa alurnya sama.
                    const bool hostAlloc = opcode == displayx_wire::CreateHost;
                    if (packet.size != (hostAlloc ? 29u : 25u) || packet.fence >= 0) break;
                    const u8 id = packet.get<u8>(4);
                    const u32 count = packet.get<u32>(5), window = packet.get<u32>(9);
                    const u32 mode = packet.get<u32>(13);
                    const u32 colorSpace = packet.get<u32>(hostAlloc ? 25 : 21);
                    const u32 requestedWidth = hostAlloc ? packet.get<u32>(17) : 0;
                    const u32 requestedHeight = hostAlloc ? packet.get<u32>(21) : 0;
                    if (!count || count > 16 || mode > 3) break;
                    u32 width, height;
                    u64 generation;
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(window);
                        if (!resource || resource->GetType() != XResourceType::Window) break;
                        auto *w = resource->As<Window>();
                        width = w->GetWidth(); height = w->GetHeight(); generation = w->generation;
                    }
                    if (hostAlloc && requestedWidth && requestedHeight) { width = requestedWidth; height = requestedHeight; }
                    if (!width || !height || width > 16384 || height > 16384) break;
                    Swapchain chain;
                    chain.window = window;
                    chain.mode = mode;
                    chain.colorSpace = colorSpace;
                    chain.generation = generation;
                    if (hostAlloc) {
                        std::vector<HostBuffer> buffers(count);
                        bool ok = true;
                        for (u32 i = 0; i < count && ok; ++i) {
                            ok = AllocateHostBuffer(width, height, buffers[i]);
                            if (ok)
                                chain.images.push_back(
                                    std::make_shared<NativeDrawable>(width, height, 32, buffers[i].buffer));
                        }
                        std::vector<uint8_t> reply;
                        displayx_wire::append(reply, uint32_t(displayx_wire::Buffers));
                        displayx_wire::append(reply, id);
                        displayx_wire::append(reply, uint32_t(ok ? count : 0));
                        displayx_wire::append(reply, uint32_t(width));
                        displayx_wire::append(reply, uint32_t(height));
                        displayx_wire::append(reply, uint32_t(displayx_wire::kFourccAbgr8888));
                        displayx_wire::append(reply, uint32_t(0));
                        std::vector<int> fds;
                        if (ok) {
                            for (const auto &b : buffers) {
                                displayx_wire::append(reply, uint32_t(b.strideBytes));
                                displayx_wire::append(reply, uint32_t(0));
                                displayx_wire::append(reply, uint64_t(b.strideBytes) * height);
                                displayx_wire::append(reply, uint64_t(displayx_wire::kModifierLinear));
                                fds.push_back(b.fd);
                            }
                        }
                        bool sent;
                        {
                            std::lock_guard output(peer->output);
                            sent = displayx_wire::sendWithFds(peer->fd, reply, fds.data(), fds.size());
                        }
                        if (!ok) LOGE("X++ DisplayX+ host buffer allocation failed ({}x{} x{})", width, height, count);
                        if (!ok || !sent) break;
                    } else for (u32 i = 0; i < count; ++i) {
                        AHardwareBuffer *buffer{};
                        if (AHardwareBuffer_recvHandleFromUnixSocket(peer->fd, &buffer) || !buffer)
                            throw std::runtime_error("Invalid DisplayX+ buffer");
                        try {
                            chain.images.push_back(
                                std::make_shared<NativeDrawable>(width, height, 32, buffer));
                        } catch (...) {
                            AHardwareBuffer_release(buffer);
                            throw;
                        }
                        AHardwareBuffer_release(buffer);
                    }
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(window);
                        if (!resource || resource->GetType() != XResourceType::Window ||
                            resource->As<Window>()->generation != generation) break;
                    }
                    if (auto found = chains.find(id); found != chains.end())
                        remove(found->second);
                    chains.insert_or_assign(id, std::move(chain));
                    { std::lock_guard output(peer->output); peer->ids.insert(id); }
                } else if (opcode == displayx_wire::Present) {
                    if (packet.size != 17) break;
                    const u8 id = packet.get<u8>(4);
                    const u32 index = packet.get<u32>(5);
                    const u64 present = packet.get<u64>(9);
                    int fence = packet.takeFence();
                    auto found = chains.find(id);
                    if (found == chains.end() || index >= found->second.images.size()) {
                        if (fence >= 0) close(fence);
                        break;
                    }
                    auto &chain = found->second;
                    auto frame = std::make_shared<NativeExternalFrame>();
                    frame->drawable = chain.images[index];
                    frame->fence = fence;
                    frame->presentMode = chain.mode;
                    frame->colorSpace = chain.colorSpace;
                    frame->hasHdr = chain.hasHdr;
                    frame->hdr = chain.hdr;
                    frame->completion = [peer, id, index, present, mode = chain.mode] {
                        peer->Complete(id, index, present, mode);
                    };
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(chain.window);
                        if (!resource || resource->GetType() != XResourceType::Window ||
                            resource->As<Window>()->generation != chain.generation)
                            break;
                        chain.latest = frame;
                        static_cast<NativeScreen &>(server.GetScreen())
                            .UpdateExternal(chain.window, chain.generation, frame);
                    }
                    // FIFO publishes the next image only after Draw has claimed this one.
                    // Several claimed images can be in flight; completion still waits for presentation.
                    // Mailbox may replace an unsubmitted scene; RAII completion releases the dropped image.
                    if (chain.mode == 2) {
                        std::unique_lock lock(frame->completionMutex);
                        while (!frame->claimed && !frame->acknowledged && !stopped && peer->connected) {
                            frame->completionCv.wait_for(lock, std::chrono::milliseconds(100));
                            pollfd input{peer->fd, POLLIN, 0};
                            if (poll(&input, 1, 0) > 0 && (input.revents & (POLLHUP | POLLERR | POLLNVAL)))
                                peer->Stop();
                        }
                    }
                } else if (opcode == displayx_wire::Hdr) {
                    if (packet.size != 53 || packet.fence >= 0) break;
                    auto found = chains.find(packet.get<u8>(4));
                    if (found == chains.end()) break;
                    for (size_t i = 0; i < 12; ++i) {
                        found->second.hdr[i] = packet.get<float>(5 + i * 4);
                        if (!std::isfinite(found->second.hdr[i])) throw std::runtime_error("Invalid DisplayX HDR metadata");
                    }
                    found->second.hasHdr = true;
                } else if (opcode == displayx_wire::Geometry) {
                    // Ukuran window X untuk surface Vulkan guest (currentExtent); tidak mengubah state swapchain.
                    if (packet.size != 8 || packet.fence >= 0) break;
                    const u32 window = packet.get<u32>(4);
                    u32 gw = 0, gh = 0;
                    {
                        auto lock = server.Lock();
                        auto *resource = server.GetResource(window);
                        if (resource && resource->GetType() == XResourceType::Window) {
                            auto *w = resource->As<Window>();
                            gw = w->GetWidth(); gh = w->GetHeight();
                        }
                    }
                    std::vector<uint8_t> reply;
                    displayx_wire::append(reply, uint32_t(displayx_wire::GeometryReply));
                    displayx_wire::append(reply, window);
                    displayx_wire::append(reply, gw);
                    displayx_wire::append(reply, gh);
                    std::lock_guard output(peer->output);
                    if (!displayx_wire::sendWithFds(peer->fd, reply, nullptr, 0)) break;
                } else if (opcode == displayx_wire::Destroy) {
                    if (packet.size != 5 || packet.fence >= 0) break;
                    const u8 id = packet.get<u8>(4);
                    if (auto found = chains.find(id); found != chains.end()) {
                        remove(found->second);
                        chains.erase(found);
                        std::lock_guard output(peer->output); peer->ids.erase(id);
                    }
                } else
                    break;
            }
        } catch (const std::exception &error) {
            LOGE("X++ DisplayX+ peer rejected: {}", error.what());
        }
        peer->Stop();
        for (auto &[id, chain] : chains)
            remove(chain);
    }
};
NativeDisplayXBridge::NativeDisplayXBridge(XServer &server) : impl(std::make_unique<Impl>(server)) {}
NativeDisplayXBridge::~NativeDisplayXBridge() = default;
void NativeDisplayXBridge::ResetState() {
    std::lock_guard clients(impl->clientsMutex);
    for (const auto& client : impl->clients) {
        std::lock_guard output(client.peer->output);
        for (u8 id : client.peer->ids) {
            std::vector<uint8_t> packet;
            displayx_wire::append(packet, uint32_t(displayx_wire::Reset));
            displayx_wire::append(packet, id);
            if (client.peer->connected && !displayx_wire::sendPacket(client.peer->fd, packet)) client.peer->Stop();
        }
    }
}
} // namespace xorg
