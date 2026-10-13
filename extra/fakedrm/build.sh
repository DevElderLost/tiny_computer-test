#!/bin/sh
# Membangun libfakedrm.so (aarch64, glibc) untuk rootfs Debian di dalam Tiny Computer.
#   extra/fakedrm/build.sh <folder-keluaran>
# CI menyisipkannya ke patch.tzst sebagai tiny/extra/libfakedrm.so. Untuk uji di PC: CC=gcc.
set -eu
out=${1:?usage: build.sh <output dir>}
here=$(dirname "$0")
mkdir -p "$out"
CC=${CC:-aarch64-linux-gnu-gcc}
"$CC" -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -std=gnu11 -fvisibility=hidden \
  -o "$out/libfakedrm.so" "$here/libfakedrm.c" -ldl
${STRIP:-aarch64-linux-gnu-strip} --strip-unneeded "$out/libfakedrm.so" 2>/dev/null || true
echo "OK: $out/libfakedrm.so"
