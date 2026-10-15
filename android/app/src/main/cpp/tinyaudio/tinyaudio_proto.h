/*
 * Protokol soket "tinyaudio": jalur suara langsung dari guest (proot) ke Android, tanpa PulseAudio.
 *
 *  guest (plugin ALSA)  ──unix stream /tmp/.tinyaudio──►  server di aplikasi Android (AAudio)
 *
 *  1. Klien menyambung lalu mengirim satu ta_hello (20 byte, little-endian).
 *  2. Setelahnya klien hanya mengirim PCM mentah interleaved sesuai ta_hello.
 *  3. Server mengirim balik rekaman u64 LE (8 byte): jumlah frame KLIEN yang SUDAH diputar sejak
 *     sambungan dibuka. Plugin memakainya sebagai hw_ptr, sehingga emulator ditahan oleh jam audio
 *     perangkat (bukan oleh timer) dan tidak ada buffer yang menumpuk atau kosong tiba-tiba.
 *  4. Menutup sambungan = membuang sisa buffer stream itu (setara snd_pcm_drop).
 */
#ifndef TINYAUDIO_PROTO_H
#define TINYAUDIO_PROTO_H
#include <stdint.h>

#define TA_MAGIC 0x31415554u /* "TUA1" */
#define TA_FMT_S16LE 1
#define TA_FMT_F32LE 2
#define TA_OUT_RATE 48000     /* laju tetap stream AAudio; resample ke sini dilakukan server */
#define TA_MAX_CHANNELS 2

struct ta_hello {
    uint32_t magic;
    uint32_t rate;           /* Hz klien */
    uint16_t channels;       /* 1..2 */
    uint16_t format;         /* TA_FMT_* */
    uint32_t period_frames;  /* ukuran period ALSA klien, dipakai server menentukan prefill */
    uint32_t buffer_frames;  /* ukuran buffer ALSA klien */
} __attribute__((packed));

#endif
