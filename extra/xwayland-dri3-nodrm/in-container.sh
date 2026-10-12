#!/bin/sh
# Dijalankan DI DALAM kontainer debian:<suite> arm64 oleh build.sh.
set -eu
export DEBIAN_FRONTEND=noninteractive
# deb-src untuk apt-get source / build-dep (Debian 13 memakai format deb822, versi lama sources.list)
if [ -f /etc/apt/sources.list.d/debian.sources ]; then
  sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
else
  sed -i 's/^deb /deb-src /' /etc/apt/sources.list 2>/dev/null || true
  grep '^deb ' /etc/apt/sources.list | sed 's/^deb /deb-src /' >> /etc/apt/sources.list || true
fi
apt-get update -qq
apt-get install -y -qq --no-install-recommends build-essential devscripts dpkg-dev fakeroot patch ca-certificates
apt-get build-dep -y -qq xwayland
mkdir -p /build && cd /build
apt-get source -qq xwayland
cd xwayland-*/
# patch ditumpuk di atas sumber Debian; --fuzz=0 supaya ketahuan bila versi xwayland berubah jauh
patch -p1 --fuzz=0 < /src/xwayland-dri3-nodrm.patch
ver=$(dpkg-parsechangelog -S Version)
DEB_BUILD_OPTIONS="nocheck noautodbgsym parallel=$(nproc)" dpkg-buildpackage -b -uc -us -d
cd ..
dpkg-deb -x xwayland_*_arm64.deb /build/x
strip --strip-unneeded /build/x/usr/bin/Xwayland
cp /build/x/usr/bin/Xwayland /out/Xwayland
chmod 755 /out/Xwayland
echo "xwayland $ver + dri3-nodrm (debian $XWAYLAND_SUITE)" > /out/Xwayland.version
