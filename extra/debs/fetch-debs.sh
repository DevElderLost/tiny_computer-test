#!/bin/sh
# Mengunduh paket .deb arm64 BESERTA seluruh dependensinya (yang belum ada di Debian dasar) ke satu folder.
# Dijalankan di kontainer Debian arm64 (QEMU harus sudah aktif di runner) dengan `apt-get --download-only`,
# jadi dependensi dan verifikasi tanda tangan ditangani apt. Hasilnya dibungkus CI ke assets/patch.tzst
# (tiny/extra/debs) dan dipasang OFFLINE oleh ExtraSetup saat instalasi pertama.
#
# Pemakaian : fetch-debs.sh OUTDIR PAKET...
# Env       : XWAYLAND_SUITE (default trixie; samakan dengan rootfs)
# Keluaran  : OUTDIR/*.deb ; exit 1 bila gagal (CI hanya memberi peringatan).
set -eu
out=$1; shift
suite=${XWAYLAND_SUITE:-trixie}
mkdir -p "$out"
docker run --rm --platform linux/arm64 -v "$out":/out "debian:$suite-slim" sh -ec '
  apt-get update -qq
  apt-get install -y -qq --download-only --no-install-recommends "$@"
  cp /var/cache/apt/archives/*.deb /out/
' sh "$@"
ls "$out"/*.deb >/dev/null
