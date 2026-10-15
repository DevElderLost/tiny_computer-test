/*
 * libasound_module_pcm_tinyaudio.so - plugin ALSA (ioplug) untuk guest Debian di Tiny Computer.
 *
 * Mengirim PCM ke server audio native aplikasi Android lewat soket unix (lihat tinyaudio_proto.h).
 * Dipakai SDL2/ALSA (PPSSPP), Dolphin (backend ALSA/cubeb-alsa), Wine (winealsa), dll. tanpa PulseAudio.
 *
 * Desain anti "suara hilang" dan anti "keresek":
 *  - hw_ptr = jumlah frame yang BENAR-BENAR sudah diputar Android (rekaman dari server). Aplikasi ditahan
 *    oleh jam audio perangkat; buffer tidak pernah meluap dan tidak pernah dibuang diam-diam.
 *  - Bila server hilang (aplikasi Android dimulai ulang, rute audio berganti, soket putus) plugin TIDAK
 *    gagal: ia beralih ke mode simulasi berjam waktu nyata (aplikasi tidak macet dan tidak berlari
 *    kencang) dan mencoba menyambung lagi tiap 500 ms. Begitu server kembali, suara hidup lagi sendiri.
 *  - Semua I/O non-blocking + MSG_NOSIGNAL, tanpa SIGPIPE yang bisa mematikan emulator.
 *
 * Konfigurasi (asound.conf):  pcm.tinyaudio_raw { type tinyaudio  socket "/tmp/.tinyaudio" }
 * Env: TINYAUDIO_SOCKET menimpa jalur soket.
 */
#define _GNU_SOURCE
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include "tinyaudio_proto.h"

#define RETRY_NS (500LL * 1000 * 1000)

typedef struct {
    snd_pcm_ioplug_t io;
    char path[108];
    int sock;                 /* -1 = tidak tersambung (mode simulasi) */
    int tfd;                  /* timerfd: dasar poll untuk ALSA */
    uint64_t written;         /* frame yang sudah ditulis aplikasi sejak prepare */
    uint64_t consumed;        /* frame yang dianggap sudah diputar (monoton) */
    uint64_t base;            /* consumed = base + ack saat tersambung */
    uint64_t ack;             /* rekaman terakhir dari server */
    uint8_t ackbuf[8];
    int ackn;
    uint64_t sim_c0;          /* consumed saat simulasi dimulai */
    int64_t sim_t0;           /* ns monotonic saat simulasi dimulai */
    int64_t last_try;         /* ns percobaan sambung terakhir */
    int prepared;
    unsigned frame_bytes;
} ta_t;

static int64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void ta_close_sock(ta_t *t)
{
    if (t->sock >= 0) { close(t->sock); t->sock = -1; }
    t->ackn = 0;
}

static void ta_enter_sim(ta_t *t)
{
    ta_close_sock(t);
    t->sim_c0 = t->consumed;
    t->sim_t0 = now_ns();
}

static int send_all(ta_t *t, const void *p, size_t n)
{
    const char *c = p;
    int64_t deadline = now_ns() + 200LL * 1000 * 1000; /* batas 200 ms: server macet dianggap putus */
    while (n) {
        ssize_t r = send(t->sock, c, n, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (r > 0) { c += r; n -= (size_t)r; continue; }
        if (r < 0 && errno == EINTR) continue;
        if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (now_ns() > deadline) return -1;
            struct pollfd pf = { .fd = t->sock, .events = POLLOUT };
            poll(&pf, 1, 20);
            continue;
        }
        return -1;
    }
    return 0;
}

static int ta_try_connect(ta_t *t)
{
    t->last_try = now_ns();
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", t->path);
    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) { close(fd); return -1; }
    int sz = 256 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sz, sizeof sz);
    t->sock = fd;
    struct ta_hello h = {
        .magic = TA_MAGIC, .rate = t->io.rate, .channels = (uint16_t)t->io.channels,
        .format = t->io.format == SND_PCM_FORMAT_FLOAT_LE ? TA_FMT_F32LE : TA_FMT_S16LE,
        .period_frames = (uint32_t)t->io.period_size, .buffer_frames = (uint32_t)t->io.buffer_size,
    };
    if (send_all(t, &h, sizeof h) < 0) { ta_close_sock(t); return -1; }
    return 0;
}

static void ta_drain_acks(ta_t *t)
{
    while (t->sock >= 0) {
        ssize_t r = recv(t->sock, t->ackbuf + t->ackn, 8 - t->ackn, MSG_DONTWAIT);
        if (r > 0) {
            t->ackn += (int)r;
            if (t->ackn == 8) {
                uint64_t v;
                memcpy(&v, t->ackbuf, 8);
                t->ackn = 0;
                if (v > t->ack) t->ack = v;
            }
            continue;
        }
        if (r == 0) { ta_enter_sim(t); return; }          /* server menutup */
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) ta_enter_sim(t);
        return;
    }
}

static void ta_update(ta_t *t)
{
    uint64_t c;
    if (t->sock >= 0) {
        ta_drain_acks(t);
    }
    if (t->sock >= 0) {
        c = t->base + t->ack;
    } else {
        int64_t now = now_ns();
        if (t->prepared && now - t->last_try > RETRY_NS && ta_try_connect(t) == 0) {
            /* server kembali: buang sisa yang tertahan, lanjut dari titik tulis sekarang */
            t->base = t->written;
            t->ack = 0;
            c = t->written;
        } else {
            c = t->sim_c0 + (uint64_t)((now - t->sim_t0) * (int64_t)t->io.rate / 1000000000LL);
        }
    }
    if (c > t->written) c = t->written;
    if (c > t->consumed) t->consumed = c;
}

static void ta_clear_timer(ta_t *t)
{
    uint64_t e;
    while (read(t->tfd, &e, sizeof e) > 0) {}
}

static snd_pcm_sframes_t ta_pointer(snd_pcm_ioplug_t *io)
{
    ta_t *t = io->private_data;
    ta_clear_timer(t);
    ta_update(t);
    return (snd_pcm_sframes_t)(t->consumed % io->buffer_size);
}

static snd_pcm_sframes_t ta_transfer(snd_pcm_ioplug_t *io, const snd_pcm_channel_area_t *areas,
                                     snd_pcm_uframes_t offset, snd_pcm_uframes_t size)
{
    ta_t *t = io->private_data;
    const char *src = (const char *)areas->addr + (areas->first + areas->step * offset) / 8;
    if (t->sock < 0) ta_update(t);          /* kesempatan menyambung lagi */
    if (t->sock >= 0 && send_all(t, src, size * t->frame_bytes) < 0) ta_enter_sim(t);
    t->written += size;
    return (snd_pcm_sframes_t)size;
}

static int ta_prepare(snd_pcm_ioplug_t *io)
{
    ta_t *t = io->private_data;
    ta_close_sock(t);
    t->written = t->consumed = t->base = t->ack = 0;
    t->frame_bytes = io->channels * (io->format == SND_PCM_FORMAT_FLOAT_LE ? 4 : 2);
    t->prepared = 1;
    t->sim_c0 = 0;
    t->sim_t0 = now_ns();
    if (ta_try_connect(t) < 0) t->sim_t0 = now_ns();
    ta_clear_timer(t);
    /* interval timer ~ seperempat period supaya snd_pcm_wait bangun tepat waktu */
    int64_t ns = (int64_t)io->period_size * 1000000000LL / (io->rate ? io->rate : 48000) / 4;
    if (ns < 1000000) ns = 1000000;
    if (ns > 10000000) ns = 10000000;
    struct itimerspec it = { .it_interval = { 0, (long)ns }, .it_value = { 0, (long)ns } };
    timerfd_settime(t->tfd, 0, &it, NULL);
    return 0;
}

static int ta_start(snd_pcm_ioplug_t *io) { (void)io; return 0; }

static int ta_stop(snd_pcm_ioplug_t *io)
{
    ta_t *t = io->private_data;
    ta_close_sock(t);           /* menutup sambungan = membuang sisa buffer di server */
    t->prepared = 0;
    return 0;
}

static int ta_drain(snd_pcm_ioplug_t *io)
{
    ta_t *t = io->private_data;
    int64_t deadline = now_ns() + 2000LL * 1000 * 1000;
    while (now_ns() < deadline) {
        ta_update(t);
        /* toleransi 4 frame: sisa di dalam filter resampler server tidak dihitung diputar */
        if (t->consumed + 4 >= t->written) break;
        usleep(2000);
    }
    return 0;
}

static int ta_close(snd_pcm_ioplug_t *io)
{
    ta_t *t = io->private_data;
    ta_close_sock(t);
    if (t->tfd >= 0) close(t->tfd);
    free(t);
    return 0;
}

static int ta_hw_params(snd_pcm_ioplug_t *io, snd_pcm_hw_params_t *p)
{
    (void)io; (void)p;
    return 0;
}

static const snd_pcm_ioplug_callback_t ta_callback = {
    .start = ta_start, .stop = ta_stop, .drain = ta_drain, .pointer = ta_pointer,
    .transfer = ta_transfer, .close = ta_close, .hw_params = ta_hw_params, .prepare = ta_prepare,
};

SND_PCM_PLUGIN_DEFINE_FUNC(tinyaudio)
{
    snd_config_iterator_t i, next;
    const char *sock = getenv("TINYAUDIO_SOCKET");
    if (!sock) sock = "/tmp/.tinyaudio";
    snd_config_for_each(i, next, conf) {
        snd_config_t *n = snd_config_iterator_entry(i);
        const char *id;
        if (snd_config_get_id(n, &id) < 0) continue;
        if (!strcmp(id, "comment") || !strcmp(id, "type") || !strcmp(id, "hint")) continue;
        if (!strcmp(id, "socket") && !getenv("TINYAUDIO_SOCKET")) {
            if (snd_config_get_string(n, &sock) < 0) { SNDERR("socket harus string"); return -EINVAL; }
            continue;
        }
        SNDERR("Parameter tidak dikenal: %s", id);
        return -EINVAL;
    }
    if (stream != SND_PCM_STREAM_PLAYBACK) return -EINVAL; /* hanya output; mikrofon tetap lewat logika mic */

    ta_t *t = calloc(1, sizeof *t);
    if (!t) return -ENOMEM;
    t->sock = -1;
    snprintf(t->path, sizeof t->path, "%s", sock);
    t->tfd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (t->tfd < 0) { free(t); return -errno; }

    t->io.version = SND_PCM_IOPLUG_VERSION;
    t->io.name = "Tiny Computer native audio";
    t->io.callback = &ta_callback;
    t->io.private_data = t;
    t->io.poll_fd = t->tfd;
    t->io.poll_events = POLLIN;
    t->io.mmap_rw = 0;

    int err = snd_pcm_ioplug_create(&t->io, name, stream, mode);
    if (err < 0) { close(t->tfd); free(t); return err; }

    static const unsigned access_list[] = { SND_PCM_ACCESS_RW_INTERLEAVED };
    static const unsigned format_list[] = { SND_PCM_FORMAT_S16_LE, SND_PCM_FORMAT_FLOAT_LE };
    if ((err = snd_pcm_ioplug_set_param_list(&t->io, SND_PCM_IOPLUG_HW_ACCESS, 1, access_list)) < 0 ||
        (err = snd_pcm_ioplug_set_param_list(&t->io, SND_PCM_IOPLUG_HW_FORMAT, 2, format_list)) < 0 ||
        (err = snd_pcm_ioplug_set_param_minmax(&t->io, SND_PCM_IOPLUG_HW_CHANNELS, 1, TA_MAX_CHANNELS)) < 0 ||
        (err = snd_pcm_ioplug_set_param_minmax(&t->io, SND_PCM_IOPLUG_HW_RATE, 8000, 96000)) < 0 ||
        (err = snd_pcm_ioplug_set_param_minmax(&t->io, SND_PCM_IOPLUG_HW_PERIOD_BYTES, 256, 128 * 1024)) < 0 ||
        (err = snd_pcm_ioplug_set_param_minmax(&t->io, SND_PCM_IOPLUG_HW_PERIODS, 2, 16)) < 0) {
        snd_pcm_ioplug_delete(&t->io);
        return err;
    }
    *pcmp = t->io.pcm;
    return 0;
}
SND_PCM_PLUGIN_SYMBOL(tinyaudio);
