#!/bin/sh
# Membangun lapisan Vulkan VK_LAYER_TINY_displayx (aarch64, glibc) untuk rootfs Debian di dalam Tiny Computer.
#   extra/displayx-layer/build.sh <folder-keluaran>
# Hanya butuh HEADER Vulkan (libvulkan-dev: vulkan/vulkan.h dan vulkan/vk_layer.h); lapisan tidak ditautkan ke
# libvulkan karena loader guest yang memuatnya. Keluaran: libVkLayer_tiny_displayx.so + VkLayer_tiny_displayx.json.
# CI menaruhnya di assets/displayx/, Rootfs.ensureVkLayer menyalinnya ke tiny/extra/displayx.
# Untuk uji di PC: CC=gcc.
set -eu
out=${1:?usage: build.sh <output dir>}
here=$(dirname "$0")
mkdir -p "$out"
CC=${CC:-aarch64-linux-gnu-gcc}
"$CC" -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -std=gnu11 -fvisibility=hidden -idirafter /usr/include \
  -o "$out/libVkLayer_tiny_displayx.so" "$here/displayx_layer.c" -lpthread
${STRIP:-aarch64-linux-gnu-strip} --strip-unneeded "$out/libVkLayer_tiny_displayx.so" 2>/dev/null || true
cp "$here/VkLayer_tiny_displayx.json" "$out/VkLayer_tiny_displayx.json"
echo "OK: $out/libVkLayer_tiny_displayx.so"
