#pragma once
#include <array>
#include <vector>
#include <map>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <sys/socket.h>

namespace displayx_wire {
enum : uint32_t { Create = 1, Destroy = 2, Present = 3, Complete = 4, Reset = 5, Hdr = 6,
    // Protokol v2 untuk guest glibc (tanpa AHardwareBuffer): buffer dialokasikan HOST lalu dikirim sebagai dma-buf.
    //  CreateHost (guest -> host), 29 byte: u32 op, u8 id, u32 count, u32 window, u32 mode, u32 width, u32 height, u32 colorSpace.
    //    width/height 0 = ukuran window X.
    //  Buffers (host -> guest), SCM_RIGHTS `count` fd dma-buf: u32 op, u8 id, u32 count, u32 width, u32 height,
    //    u32 drmFourcc, u32 flags, lalu per buffer {u32 strideBytes, u32 offset, u64 sizeHint, u64 drmModifier}.
    //    count 0 = alokasi gagal. Present/Complete/Reset/Destroy sama persis dengan v1 (indeks = urutan fd).
    //  Geometry (guest -> host), 8 byte: u32 op, u32 window. GeometryReply (host -> guest): u32 op, u32 window,
    //    u32 width, u32 height (0, 0 bila window tidak ada).
    CreateHost = 7, Buffers = 8, Geometry = 9, GeometryReply = 10 };
constexpr uint32_t kFourccAbgr8888 = 0x34324241;   // 'A','B','2','4': byte R,G,B,A (sama dengan VK_FORMAT_R8G8B8A8_UNORM)
constexpr uint64_t kModifierLinear = 0;
struct Packet {
    std::array<uint8_t, 64> bytes{};
    size_t size{};
    int fence{-1};
    Packet() = default;
    Packet(const Packet&) = delete;
    ~Packet() { if (fence >= 0) close(fence); }
    template<class T> T get(size_t offset) const {
        T value{};
        if (offset <= size && sizeof(T) <= size - offset) memcpy(&value, bytes.data() + offset, sizeof(T));
        return value;
    }
    int takeFence() { int value = fence; fence = -1; return value; }
};
inline bool receive(int fd, Packet& packet) {
    iovec data{packet.bytes.data(), packet.bytes.size()};
    alignas(cmsghdr) char control[CMSG_SPACE(8 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &data; message.msg_iovlen = 1;
    message.msg_control = control; message.msg_controllen = sizeof(control);
    ssize_t count;
    do { count = recvmsg(fd, &message, MSG_CMSG_CLOEXEC); } while (count < 0 && errno == EINTR);
    bool valid = count >= 4 && !(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC));
    unsigned descriptors = 0;
    for (auto *h = CMSG_FIRSTHDR(&message); h; h = CMSG_NXTHDR(&message, h)) {
        if (h->cmsg_level != SOL_SOCKET || h->cmsg_type != SCM_RIGHTS || h->cmsg_len < CMSG_LEN(0)) { valid = false; continue; }
        size_t n = (h->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t i = 0; i < n; ++i) {
            int value; memcpy(&value, CMSG_DATA(h) + i * sizeof(int), sizeof(value));
            if (descriptors++ == 0) packet.fence = value; else close(value);
        }
    }
    packet.size = count > 0 ? size_t(count) : 0;
    if (descriptors > 1) valid = false;
    if (!valid && packet.fence >= 0) { close(packet.fence); packet.fence = -1; }
    return valid;
}
template<class T> inline void append(std::vector<uint8_t>& packet, const T& value) {
    const auto *p = reinterpret_cast<const uint8_t*>(&value);
    packet.insert(packet.end(), p, p + sizeof(T));
}
struct Completion { uint8_t id; uint64_t present; uint32_t index; };
// One SEQPACKET message can carry several independent swapchain groups.
inline std::vector<uint8_t> completion(const std::vector<Completion>& values) {
    std::map<uint8_t, std::vector<Completion>> groups;
    for (const auto& v : values) groups[v.id].push_back(v);
    std::vector<uint8_t> packet;
    append(packet, uint32_t(Complete)); append(packet, uint32_t(groups.size()));
    for (const auto& [id, group] : groups) {
        uint64_t present = UINT64_MAX;
        for (const auto& v : group) if (v.present != UINT64_MAX)
            present = present == UINT64_MAX ? v.present : std::max(present, v.present);
        append(packet, id); append(packet, present);
        append(packet, uint32_t(group.size()));
        for (const auto& v : group) append(packet, v.index);
    }
    return packet;
}
// Kirim satu paket SEQPACKET beserta `count` descriptor (SCM_RIGHTS). Memblokir sampai terkirim.
inline bool sendWithFds(int fd, const std::vector<uint8_t>& packet, const int* fds, size_t count) {
    iovec data{const_cast<uint8_t*>(packet.data()), packet.size()};
    alignas(cmsghdr) char control[CMSG_SPACE(16 * sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &data; message.msg_iovlen = 1;
    if (count) {
        if (count > 16) return false;
        message.msg_control = control; message.msg_controllen = CMSG_SPACE(count * sizeof(int));
        auto *h = CMSG_FIRSTHDR(&message);
        h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(count * sizeof(int));
        memcpy(CMSG_DATA(h), fds, count * sizeof(int));
    }
    ssize_t sent;
    do { sent = sendmsg(fd, &message, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
    return sent == ssize_t(packet.size());
}
inline bool sendPacket(int fd, const std::vector<uint8_t>& packet) {
    ssize_t sent;
    do { sent = send(fd, packet.data(), packet.size(), MSG_NOSIGNAL | MSG_DONTWAIT); }
    while (sent < 0 && errno == EINTR);
    return sent == ssize_t(packet.size());
}
}
