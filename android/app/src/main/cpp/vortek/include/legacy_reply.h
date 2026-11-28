#pragma once
#ifdef VORTEK_LEGACY_ICD
#include <unistd.h>
#include <errno.h>
#include "socket_utils.h"
#include "vortek_log.h"

// Protokol ICD Vortek lama: balasan operasi asinkron (pembuatan pipeline, vkWaitSemaphores) tidak lewat ring. ICD
// menunggu satu fd (ujung baca pipe) yang dikirim host lewat soket (1 byte data + 1 fd), lalu read() hasilnya dari pipe.
static inline int LegacyReply_begin(int clientFd) {
    int p[2];
    if (pipe(p) < 0) return -1;
    int sent = send_fds(clientFd, &p[0], 1, NULL, 0);
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
#endif
