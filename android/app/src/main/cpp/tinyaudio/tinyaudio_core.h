#ifndef TINYAUDIO_CORE_H
#define TINYAUDIO_CORE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Inti server audio native: tanpa API Android, supaya bisa diuji di PC.
 *  - ta_server_pump()   : dipanggil dari SATU thread server (accept, baca PCM, resample, kirim rekaman).
 *  - ta_server_render() : dipanggil dari callback audio (real-time: tanpa lock, tanpa malloc, tanpa syscall). */
typedef struct ta_server ta_server;

ta_server *ta_server_create(const char *socket_path);   /* NULL bila bind gagal */
void ta_server_destroy(ta_server *s);
/* Tunggu I/O paling lama timeout_ms lalu layani semua klien. Mengembalikan jumlah klien aktif. */
int ta_server_pump(ta_server *s, int timeout_ms);
/* Isi out dengan `frames` frame stereo int16 pada TA_OUT_RATE (campuran semua klien; diam bila tak ada). */
void ta_server_render(ta_server *s, int16_t *out, int32_t frames);
int ta_server_clients(const ta_server *s);
uint32_t ta_server_underruns(const ta_server *s);
/* Prefill (ms) sebelum sebuah stream mulai diputar; dibatasi oleh ukuran buffer klien. */
void ta_server_set_prefill_ms(ta_server *s, int ms);

#ifdef __cplusplus
}
#endif
#endif
