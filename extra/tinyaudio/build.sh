#!/bin/sh
# Membangun libasound_module_pcm_tinyaudio.so (aarch64, glibc) untuk rootfs Debian di dalam Tiny Computer.
#   extra/tinyaudio/build.sh <folder-keluaran>
# -DPIC wajib: tanpa itu makro SND_PCM_PLUGIN_SYMBOL memakai daftar simbol statis (snd_dlsym_start) yang tidak ada di libasound.
# Hanya butuh HEADER alsa (paket libasound2-dev di runner CI); plugin tidak ditautkan ke libasound karena
# simbolnya diselesaikan saat dimuat oleh libasound milik guest. CI menyisipkannya ke patch.tzst sebagai
# tiny/extra/libasound_module_pcm_tinyaudio.so. Untuk uji di PC: CC=gcc.
set -eu
out=${1:?usage: build.sh <output dir>}
here=$(dirname "$0")
mkdir -p "$out"
CC=${CC:-aarch64-linux-gnu-gcc}
# -idirafter: header ALSA milik host dicari TERAKHIR, header libc tetap dari cross-compiler.
"$CC" -shared -fPIC -DPIC -O2 -Wall -Wextra -Wno-unused-parameter -std=gnu11 -idirafter /usr/include \
  -o "$out/libasound_module_pcm_tinyaudio.so" "$here/libasound_module_pcm_tinyaudio.c"
${STRIP:-aarch64-linux-gnu-strip} --strip-unneeded "$out/libasound_module_pcm_tinyaudio.so" 2>/dev/null || true
echo "OK: $out/libasound_module_pcm_tinyaudio.so"
