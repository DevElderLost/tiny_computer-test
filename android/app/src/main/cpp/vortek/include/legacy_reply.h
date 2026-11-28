#pragma once
#ifdef VORTEK_LEGACY_ICD
#include <unistd.h>
#include "socket_utils.h"

// Protokol ICD Vortek lama: balasan operasi asinkron (pembuatan pipeline, vkWaitSemaphores) tidak lewat ring. ICD
// menunggu satu fd (ujung baca pipe) yang dikirim host lewat soket (1 byte data + 1 fd), lalu read() hasilnya dari pipe.
static inline int LegacyReply_begin(int clientFd) {
    int p[2];
    if (pipe(p) < 0) return -1;
    send_fds(clientFd, &p[0], 1, NULL, 0);
    close(p[0]);
    return p[1];
}

static inline void LegacyReply_finish(int writeFd, const void* data, int size) {
    if (writeFd < 0) return;
    const char* c = (const char*)data;
    while (size > 0) {
        ssize_t n = write(writeFd, c, size);
        if (n <= 0) break;
        c += n;
        size -= n;
    }
    close(writeFd);
}
#endif
