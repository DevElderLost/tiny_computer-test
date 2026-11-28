#pragma once
#ifdef VORTEK_LEGACY_ICD
#include <unistd.h>
#include <errno.h>
#include "socket_utils.h"
#include "vortek_log.h"
#include "ring_buffer.h"

// Protokol ICD Vortek lama: balasan operasi asinkron (pembuatan pipeline, vkWaitSemaphores) tidak lewat ring. ICD
// menunggu satu fd (ujung baca pipe) yang dikirim host lewat soket (1 byte data + 1 fd), lalu read() hasilnya dari pipe.
static inline int LegacyReply_begin(int clientFd) {
    int p[2];
    if (pipe(p) < 0) return -1;
    // Byte data harus NON-NOL: ICD lama menganggap byte 0 sebagai "gagal" (cmp w0,#0; ccmp w24,#0,#0,ne; b.ne error)
    // dan baru mau membaca pipe bila byte != 0 dan tepat satu fd diterima.
    char ok = 1;
    int sent = send_fds(clientFd, &p[0], 1, &ok, 1);
    int err = errno;
    vortekLog("balasan lama: pipe r=%d w=%d, send_fds -> %d (errno=%d)", p[0], p[1], sent, sent < 0 ? err : 0);
    close(p[0]);
    return p[1];
}

static inline void LegacyReply_finish(int writeFd, const void* data, int size) {
    if (writeFd < 0) return;
    const char* c = (const char*)data;
    int total = size;
    while (size > 0) {
        ssize_t n = write(writeFd, c, size);
        if (n <= 0) { vortekLog("balasan lama: write gagal errno=%d (sisa %d dari %d)", errno, size, total); break; }
        c += n;
        size -= n;
    }
    if (size == 0) vortekLog("balasan lama: %d byte tertulis ke pipe", total);
    close(writeFd);
}
// vkWaitForFences (timeout != 0) di ICD lama: host mengirim 1 fd lewat soket dengan data int 0 (4 byte); ICD lalu poll()
// fd itu sampai terbaca. Data 0 + tepat satu fd = "tunggu"; selain itu ICD mengembalikan VK_ERROR_DEVICE_LOST.
static inline int LegacyReply_beginFence(int clientFd) {
    int p[2];
    if (pipe(p) < 0) return -1;
    int zero = 0;
    int sent = send_fds(clientFd, &p[0], 1, &zero, sizeof(zero));
    int err = errno;
    vortekLog("fence lama: pipe r=%d w=%d, send_fds -> %d (errno=%d)", p[0], p[1], sent, sent < 0 ? err : 0);
    close(p[0]);
    return p[1];
}

// Sukses: tulis 1 byte supaya poll() ICD kembali. Timeout/error: jangan sinyalkan; tutup setelah jeda singkat karena
// poll() ICD memakai timeout yang sama dan sudah selesai sendiri (VK_TIMEOUT).
static inline void LegacyReply_finishFence(int writeFd, int result) {
    if (writeFd < 0) return;
    if (result == 0) {
        char one = 1;
        ssize_t n = write(writeFd, &one, 1);
        if (n != 1) vortekLog("fence lama: write gagal errno=%d", errno);
    } else {
        usleep(20000);
    }
    close(writeFd);
}

// Balasan lewat ring dalam format ICD lama: header {result, ukuran} lalu payload (sama dengan vt_send).
static inline void LegacyReply_ringResult(RingBuffer* ring, int result, const void* payload, int size) {
    int header[2] = {result, size};
    RingBuffer_write(ring, header, sizeof(header));
    if (size > 0) RingBuffer_write(ring, (void*)payload, size);
}
#endif
