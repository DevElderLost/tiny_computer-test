#define _GNU_SOURCE
#include "tinyaudio_core.h"
#include "tinyaudio_proto.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAX_CLIENTS 8
#define RING_FRAMES 65536u            /* 1,36 dtk @48k; pangkat dua */
#define RING_MASK (RING_FRAMES - 1u)
#define FADE_FRAMES 64
#define IN_CHUNK 16384

enum { SLOT_FREE = 0, SLOT_ACTIVE = 1, SLOT_CLOSING = 2, SLOT_DONE = 3 };

typedef struct {
    atomic_int state;
    /* --- milik thread server --- */
    int fd;
    struct ta_hello hello;
    int hello_n;
    int ready;
    uint8_t in[IN_CHUNK + 16];
    size_t in_n;
    float hist[3][2];               /* 3 frame sebelum posisi sekarang (Hermite) */
    float *x;                       /* kerja resampler */
    size_t x_cap;
    double pos;                     /* posisi sampel keluaran berikutnya relatif ke x[] */
    double step;                    /* rate_in / 48000 */
    uint64_t last_ack;
    /* --- ring SPSC (server menulis, callback membaca) --- */
    int16_t *ring;                  /* RING_FRAMES * 2 */
    atomic_uint_fast64_t wpos, rpos;
    /* --- milik callback --- */
    int playing;
    int fade_in;
    uint32_t prefill;               /* frame keluaran */
    atomic_uint_fast64_t played;    /* frame keluaran yang sudah dikonsumsi (untuk rekaman) */
} client_t;

struct ta_server {
    int lfd;
    char path[108];
    client_t c[MAX_CLIENTS];
    atomic_int prefill_ms;
    atomic_uint underruns;
    atomic_int nclients;
    int64_t last_check;             /* ms; pemeriksaan berkas soket masih ada */
    int32_t *mix;                   /* akumulator callback */
    int32_t mix_cap;
};

static inline int16_t clip16(int32_t v) { return v > 32767 ? 32767 : (v < -32768 ? -32768 : (int16_t)v); }

static int bind_listen(ta_server *s)
{
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s", s->path);
    unlink(s->path);
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0 || listen(fd, 8) < 0) { close(fd); return -1; }
    chmod(s->path, 0666);
    if (s->lfd >= 0) close(s->lfd);      /* klien yang sudah tersambung tidak terpengaruh */
    s->lfd = fd;
    return 0;
}

ta_server *ta_server_create(const char *path)
{
    ta_server *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->lfd = -1;
    if (strlen(path) >= sizeof s->path) { free(s); return NULL; }
    snprintf(s->path, sizeof s->path, "%s", path);
    if (bind_listen(s) < 0) { free(s); return NULL; }
    atomic_init(&s->prefill_ms, 30);
    atomic_init(&s->underruns, 0);
    atomic_init(&s->nclients, 0);
    s->mix_cap = 8192;
    s->mix = malloc((size_t)s->mix_cap * 2 * sizeof(int32_t));
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s->c[i];
        atomic_init(&c->state, SLOT_FREE);
        atomic_init(&c->wpos, 0); atomic_init(&c->rpos, 0); atomic_init(&c->played, 0);
        c->fd = -1;
        c->ring = calloc((size_t)RING_FRAMES * 2, sizeof(int16_t));
        if (!c->ring) { ta_server_destroy(s); return NULL; }
    }
    return s;
}

static void slot_reset(client_t *c)
{
    if (c->fd >= 0) close(c->fd);
    c->fd = -1; c->hello_n = 0; c->ready = 0; c->in_n = 0; c->pos = 1.0; c->last_ack = 0;
    memset(c->hist, 0, sizeof c->hist);
    atomic_store(&c->wpos, 0); atomic_store(&c->rpos, 0); atomic_store(&c->played, 0);
    c->playing = 0; c->fade_in = 0;
}

void ta_server_destroy(ta_server *s)
{
    if (!s) return;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (s->c[i].fd >= 0) close(s->c[i].fd);
        free(s->c[i].ring); free(s->c[i].x);
    }
    if (s->lfd >= 0) { close(s->lfd); unlink(s->path); }
    free(s->mix);
    free(s);
}

void ta_server_set_prefill_ms(ta_server *s, int ms)
{
    if (ms < 5) ms = 5;
    if (ms > 300) ms = 300;
    atomic_store(&s->prefill_ms, ms);
}
int ta_server_clients(const ta_server *s) { return atomic_load(&s->nclients); }
uint32_t ta_server_underruns(const ta_server *s) { return atomic_load(&s->underruns); }

/* ------------------------------------------------------------------ server thread */

static void accept_new(ta_server *s)
{
    for (;;) {
        int fd = accept4(s->lfd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (fd < 0) return;
        client_t *slot = NULL;
        for (int i = 0; i < MAX_CLIENTS; i++)
            if (atomic_load(&s->c[i].state) == SLOT_FREE) { slot = &s->c[i]; break; }
        if (!slot) { close(fd); continue; }
        slot_reset(slot);
        slot->fd = fd;
        /* state tetap FREE sampai hello valid; callback tidak menyentuh slot FREE */
    }
}

static int client_hello(ta_server *s, client_t *c)
{
    while (c->hello_n < (int)sizeof c->hello) {
        ssize_t r = recv(c->fd, (char *)&c->hello + c->hello_n, sizeof c->hello - (size_t)c->hello_n, 0);
        if (r > 0) { c->hello_n += (int)r; continue; }
        if (r == 0) return -1;
        if (errno == EINTR) continue;
        return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
    }
    const struct ta_hello *h = &c->hello;
    if (h->magic != TA_MAGIC || h->rate < 8000 || h->rate > 96000 || h->channels < 1 ||
        h->channels > TA_MAX_CHANNELS || (h->format != TA_FMT_S16LE && h->format != TA_FMT_F32LE))
        return -1;
    c->step = (double)h->rate / TA_OUT_RATE;
    c->pos = 1.0;
    uint32_t period = (uint32_t)((uint64_t)h->period_frames * TA_OUT_RATE / h->rate);
    uint32_t buffer = (uint32_t)((uint64_t)h->buffer_frames * TA_OUT_RATE / h->rate);
    if (period < 64) period = 64;
    uint32_t want = (uint32_t)atomic_load(&s->prefill_ms) * (TA_OUT_RATE / 1000);
    uint32_t max = buffer > period * 2 ? buffer - period : period;
    if (want > max) want = max;
    if (want < period) want = period;
    c->prefill = want;
    c->ready = 1;
    atomic_store(&c->state, SLOT_ACTIVE);
    atomic_fetch_add(&s->nclients, 1);
    return 0;
}

static inline float hermite(float ym1, float y0, float y1, float y2, float t)
{
    float c0 = y0, c1 = 0.5f * (y1 - ym1);
    float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

/* Ubah `n` frame masukan klien ke stereo float lalu resample ke ring. Mengembalikan frame keluaran. */
static size_t ingest(client_t *c, const uint8_t *in, size_t nframes, size_t room)
{
    const struct ta_hello *h = &c->hello;
    size_t need = nframes + 3;
    if (c->x_cap < need) {
        float *nx = realloc(c->x, need * 2 * sizeof(float));
        if (!nx) return 0;
        c->x = nx; c->x_cap = need;
    }
    float *x = c->x;
    for (int k = 0; k < 3; k++) { x[k * 2] = c->hist[k][0]; x[k * 2 + 1] = c->hist[k][1]; }
    float *d = x + 6;
    for (size_t i = 0; i < nframes; i++) {
        float l, r;
        if (h->format == TA_FMT_S16LE) {
            const int16_t *p = (const int16_t *)in + i * h->channels;
            l = p[0] * (1.0f / 32768.0f);
            r = h->channels > 1 ? p[1] * (1.0f / 32768.0f) : l;
        } else {
            const float *p = (const float *)in + i * h->channels;
            l = p[0];
            r = h->channels > 1 ? p[1] : l;
        }
        d[i * 2] = l; d[i * 2 + 1] = r;
    }
    size_t total = nframes + 3;               /* x[0..total) */
    uint64_t w = atomic_load_explicit(&c->wpos, memory_order_relaxed);
    size_t out = 0;
    int16_t *ring = c->ring;
    if (h->rate == TA_OUT_RATE) {
        for (size_t i = 0; i < nframes && out < room; i++, out++) {
            size_t o = (size_t)((w + out) & RING_MASK) * 2;
            ring[o] = clip16((int32_t)(d[i * 2] * 32767.0f));
            ring[o + 1] = clip16((int32_t)(d[i * 2 + 1] * 32767.0f));
        }
        c->pos = 1.0;
        /* kerja resampler tidak dipakai; histori tetap diperbarui di bawah */
        total = nframes + 3;
        for (int k = 0; k < 3; k++) { c->hist[k][0] = x[(total - 3 + k) * 2]; c->hist[k][1] = x[(total - 3 + k) * 2 + 1]; }
        atomic_store_explicit(&c->wpos, w + out, memory_order_release);
        return out;
    }
    /* posisi pos: indeks float di x, sampel y0 = x[floor(pos)]; butuh floor(pos)+2 < total */
    double pos = c->pos;
    while (out < room) {
        size_t i = (size_t)pos;
        if (i + 2 >= total) break;
        float t = (float)(pos - (double)i);
        size_t o = (size_t)((w + out) & RING_MASK) * 2;
        float l = hermite(x[(i - 1) * 2], x[i * 2], x[(i + 1) * 2], x[(i + 2) * 2], t);
        float r = hermite(x[(i - 1) * 2 + 1], x[i * 2 + 1], x[(i + 1) * 2 + 1], x[(i + 2) * 2 + 1], t);
        ring[o] = clip16((int32_t)(l * 32767.0f));
        ring[o + 1] = clip16((int32_t)(r * 32767.0f));
        out++;
        pos += c->step;
    }
    /* sisakan histori mulai (floor(pos)-1) supaya lanjutan mulus; pos dinormalkan ke x yang baru */
    size_t keep_from = (size_t)pos >= 1 ? (size_t)pos - 1 : 0;
    if (keep_from > total - 3) keep_from = total - 3;
    for (int k = 0; k < 3; k++) { c->hist[k][0] = x[(keep_from + k) * 2]; c->hist[k][1] = x[(keep_from + k) * 2 + 1]; }
    c->pos = pos - (double)keep_from;
    atomic_store_explicit(&c->wpos, w + out, memory_order_release);
    return out;
}

static void client_read(ta_server *s, client_t *c)
{
    (void)s;
    const struct ta_hello *h = &c->hello;
    size_t fbytes = (size_t)h->channels * (h->format == TA_FMT_F32LE ? 4 : 2);
    for (int rounds = 0; rounds < 4; rounds++) {
        uint64_t fill = atomic_load(&c->wpos) - atomic_load_explicit(&c->rpos, memory_order_acquire);
        size_t room = RING_FRAMES - 64 - (size_t)fill;
        /* masukan maksimum yang pasti muat di ring setelah resample (+ margin) */
        size_t max_in = (size_t)((double)room * c->step) ;
        if (max_in < 4) return;                     /* ring penuh: biarkan data tetap di kernel (backpressure) */
        size_t cap = IN_CHUNK / fbytes * fbytes;
        size_t want_in = max_in * fbytes;
        if (want_in > cap) want_in = cap;
        if (want_in <= c->in_n) want_in = c->in_n;
        ssize_t r = recv(c->fd, c->in + c->in_n, want_in - c->in_n, 0);
        if (r == 0) { atomic_store(&c->state, SLOT_CLOSING); return; }
        if (r < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return;
            atomic_store(&c->state, SLOT_CLOSING);
            return;
        }
        c->in_n += (size_t)r;
        size_t frames = c->in_n / fbytes;
        if (frames) {
            ingest(c, c->in, frames, room);
            size_t used = frames * fbytes;
            memmove(c->in, c->in + used, c->in_n - used);
            c->in_n -= used;
        }
        if ((size_t)r < want_in) return;
    }
}

static void send_ack(client_t *c)
{
    uint64_t played = atomic_load(&c->played);
    uint64_t ack = played * c->hello.rate / TA_OUT_RATE;
    if (ack == c->last_ack) return;
    ssize_t r = send(c->fd, &ack, sizeof ack, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (r == (ssize_t)sizeof ack) c->last_ack = ack;
}

int ta_server_pump(ta_server *s, int timeout_ms)
{
    struct pollfd pf[MAX_CLIENTS + 1];
    int n = 0;
    pf[n].fd = s->lfd; pf[n].events = POLLIN; n++;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s->c[i];
        if (c->fd < 0) continue;
        int st = atomic_load(&c->state);
        if (st == SLOT_FREE || st == SLOT_ACTIVE) { pf[n].fd = c->fd; pf[n].events = POLLIN; n++; }
    }
    poll(pf, (nfds_t)n, timeout_ms);
    /* Berkas soket di /tmp kontainer bisa terhapus (pembersihan tmp oleh guest). Tanpa soket, plugin di guest
     * tidak bisa menyambung dan suara "hilang"; bind ulang tiap kali berkasnya tidak ada. */
    {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t now = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
        if (now - s->last_check > 1000) {
            struct stat st;
            s->last_check = now;
            if (stat(s->path, &st) < 0 && errno == ENOENT) bind_listen(s);
        }
    }
    accept_new(s);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s->c[i];
        if (c->fd < 0) continue;
        int st = atomic_load(&c->state);
        if (st == SLOT_FREE) {                               /* menunggu hello */
            if (client_hello(s, c) < 0) slot_reset(c);
            else if (c->ready) client_read(s, c);
        } else if (st == SLOT_ACTIVE) {
            client_read(s, c);
            if (atomic_load(&c->state) == SLOT_ACTIVE) send_ack(c);
        } else if (st == SLOT_DONE) {
            atomic_fetch_sub(&s->nclients, 1);
            slot_reset(c);
            atomic_store(&c->state, SLOT_FREE);
        }
    }
    return atomic_load(&s->nclients);
}

/* ------------------------------------------------------------------ audio callback */

void ta_server_render(ta_server *s, int16_t *out, int32_t frames)
{
    if (frames > s->mix_cap) {                 /* callback sangat besar: proses per potong, tanpa malloc */
        while (frames > 0) {
            int32_t n = frames > s->mix_cap ? s->mix_cap : frames;
            ta_server_render(s, out, n);
            out += (size_t)n * 2; frames -= n;
        }
        return;
    }
    int32_t *mix = s->mix;
    memset(mix, 0, (size_t)frames * 2 * sizeof(int32_t));
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s->c[i];
        int st = atomic_load_explicit(&c->state, memory_order_acquire);
        if (st != SLOT_ACTIVE && st != SLOT_CLOSING) continue;
        uint64_t r = atomic_load_explicit(&c->rpos, memory_order_relaxed);
        uint64_t w = atomic_load_explicit(&c->wpos, memory_order_acquire);
        int32_t fill = (int32_t)(w - r);
        int32_t n = 0;
        int fade_out = 0;
        if (st == SLOT_CLOSING) {
            /* sambungan ditutup (drop): sisa dibuang setelah fade-out singkat supaya tidak ada "klik" */
            n = fill < FADE_FRAMES ? fill : FADE_FRAMES;
            if (n > frames) n = frames;
            fade_out = c->playing && n > 0;
            if (!fade_out) n = 0;
            c->playing = 0;
        } else {
            if (!c->playing) {
                if (fill >= (int32_t)c->prefill) { c->playing = 1; c->fade_in = FADE_FRAMES; }
            }
            if (c->playing) {
                n = fill < frames ? fill : frames;
                if (n < frames) {                       /* kurang data: fade-out lalu tunggu prefill lagi */
                    fade_out = n > 0;
                    c->playing = 0;
                    atomic_fetch_add_explicit(&s->underruns, 1, memory_order_relaxed);
                }
            }
        }
        const int16_t *ring = c->ring;
        for (int32_t k = 0; k < n; k++) {
            size_t o = (size_t)((r + (uint64_t)k) & RING_MASK) * 2;
            int32_t l = ring[o], rr = ring[o + 1];
            if (c->fade_in > 0) {
                int32_t g = FADE_FRAMES - c->fade_in;
                l = l * g / FADE_FRAMES; rr = rr * g / FADE_FRAMES;
                c->fade_in--;
            }
            if (fade_out && n - k <= FADE_FRAMES) {
                int32_t g = n - k;
                l = l * g / FADE_FRAMES; rr = rr * g / FADE_FRAMES;
            }
            mix[k * 2] += l; mix[k * 2 + 1] += rr;
        }
        if (st == SLOT_CLOSING) {
            atomic_store_explicit(&c->rpos, w, memory_order_release);
            atomic_store_explicit(&c->state, SLOT_DONE, memory_order_release);
        } else if (n > 0) {
            atomic_store_explicit(&c->rpos, r + (uint64_t)n, memory_order_release);
            atomic_fetch_add_explicit(&c->played, (uint64_t)n, memory_order_relaxed);
        }
    }
    for (int32_t k = 0; k < frames * 2; k++) out[k] = clip16(mix[k]);
}
