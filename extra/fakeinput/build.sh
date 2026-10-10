#!/bin/sh
# Membangun libfakeinput.so (aarch64, glibc) untuk rootfs Debian di dalam Tiny Computer.
# Sumber: fakeinput_steam.cpp dari DroidDeck (GPL-3.0; berasal dari WinNative). Pemakaian:
#   extra/fakeinput/build.sh <folder-keluaran>
# CI menaruh hasilnya di android/app/src/main/assets/libfakeinput.so.
set -eu
out=${1:?usage: build.sh <output dir>}
here=$(dirname "$0")
mkdir -p "$out"
CXX=${CXX:-aarch64-linux-gnu-g++}
"$CXX" -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare -pthread -std=c++17 \
  -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL \
  -o "$out/libfakeinput.so" "$here/fakeinput_steam.cpp" -ldl
${STRIP:-aarch64-linux-gnu-strip} --strip-unneeded "$out/libfakeinput.so"
if aarch64-linux-gnu-objdump -p "$out/libfakeinput.so" | grep -q 'NEEDED.*lib\(stdc++\|gcc_s\)'; then
  echo "libfakeinput.so menautkan runtime C++ secara dinamis" >&2
  exit 1
fi
echo "OK: $out/libfakeinput.so"
