#pragma once

#include "base/types.h"
#include "core/property.h"
#include <span>
#include <vector>

namespace xorg {

class XServer;

// Batas ukuran profil ICC yang diterima (X property biasa, bukan BIG-REQUESTS).
constexpr size_t kMaxIccProfileSize = 1u << 20;

// Profil sRGB IEC61966-2.1 (ICC v2.1, matrix/TRC) dibangun saat runtime, tanpa berkas eksternal.
std::vector<u8> BuildSrgbIccProfile();

// Pemeriksaan ringan: ukuran header, field size sama dengan panjang data, signature 'acsp'.
bool IsValidIccProfile(std::span<const u8> profile);

// Pasang profil sebagai properti window root sesuai spesifikasi "ICC Profiles in X Specification" 0.4:
// _ICC_PROFILE, _ICC_PROFILE_0 (output/layar 0) dan _ICC_PROFILE_IN_X_VERSION.
// `profile` kosong = pakai sRGB bawaan. Pemanggil harus memegang XServer::Lock() bila server sudah berjalan.
void InstallIccProfile(XServer& server, std::span<const u8> profile);

// Properti _ICC_PROFILE di window root (nullptr bila belum ada). Dipakai RandR untuk properti output.
const Property* GetIccProfileProperty(XServer& server);

}  // namespace xorg
