#!/bin/sh
# Membangun Xwayland (aarch64, glibc) dengan patch DRI3-tanpa-DRM untuk rootfs Debian Tiny Computer.
# Dibangun di dalam kontainer Debian arm64 (QEMU di CI) supaya ABI/library-nya sama dengan paket
# xwayland milik rootfs: sumber & dependensi diambil dari Debian, patch ditumpuk di atasnya.
#   extra/xwayland-dri3-nodrm/build.sh <folder-keluaran>
# Hasil: <folder-keluaran>/Xwayland (+ Xwayland.version). CI memasukkannya ke patch.tzst sebagai
# tiny/extra/Xwayland. Env: XWAYLAND_SUITE (default trixie; samakan dengan rootfs).
set -eu
out=${1:?usage: build.sh <output dir>}
here=$(cd "$(dirname "$0")" && pwd)
suite=${XWAYLAND_SUITE:-trixie}
mkdir -p "$out"
out=$(cd "$out" && pwd)
docker run --rm --platform linux/arm64 \
  -e XWAYLAND_SUITE="$suite" \
  -v "$here":/src:ro -v "$out":/out \
  "debian:$suite" sh /src/in-container.sh
test -s "$out/Xwayland"
echo "OK: $out/Xwayland ($(cat "$out/Xwayland.version"))"
