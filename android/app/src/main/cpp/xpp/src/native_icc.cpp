#include "native_icc.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>

#include "core/atom.h"
#include "core/property.h"
#include "core/window.h"
#include "core/window_manager.h"
#include "core/xserver.h"

namespace xorg {
namespace {

constexpr u32 kXaCardinal = 6;      // XA_CARDINAL
constexpr u8 kPropModeReplace = 0;  // PropModeReplace
constexpr u8 kIccInXVersion = 4;    // spesifikasi 0.4 -> major * 100 + minor

void PutU16(std::vector<u8>& out, u16 v) { out.push_back(v >> 8); out.push_back(v & 0xff); }
void PutU32(std::vector<u8>& out, u32 v) {
    out.push_back(v >> 24); out.push_back((v >> 16) & 0xff); out.push_back((v >> 8) & 0xff); out.push_back(v & 0xff);
}
void PutTag(std::vector<u8>& out, const char* tag) { out.insert(out.end(), tag, tag + 4); }
void PutS15Fixed16(std::vector<u8>& out, double v) { PutU32(out, static_cast<u32>(static_cast<s32>(std::lround(v * 65536.0)))); }
void PadTo4(std::vector<u8>& out) { while (out.size() & 3) out.push_back(0); }
u32 ReadU32(std::span<const u8> d, size_t at) { return (u32(d[at]) << 24) | (u32(d[at + 1]) << 16) | (u32(d[at + 2]) << 8) | d[at + 3]; }

struct TagEntry { const char* sig; u32 offset; u32 size; };

std::vector<u8> XyzTag(double x, double y, double z) {
    std::vector<u8> t; PutTag(t, "XYZ "); PutU32(t, 0);
    PutS15Fixed16(t, x); PutS15Fixed16(t, y); PutS15Fixed16(t, z);
    return t;
}

// textDescriptionType (ICC v2): ASCII + blok Unicode/ScriptCode kosong.
std::vector<u8> DescTag(const std::string& text) {
    std::vector<u8> t; PutTag(t, "desc"); PutU32(t, 0);
    PutU32(t, static_cast<u32>(text.size() + 1));
    t.insert(t.end(), text.begin(), text.end()); t.push_back(0);
    PutU32(t, 0); PutU32(t, 0);        // Unicode language code + count
    PutU16(t, 0); t.push_back(0);      // ScriptCode code + count
    t.insert(t.end(), 67, 0);          // ScriptCode string
    return t;
}

std::vector<u8> TextTag(const std::string& text) {
    std::vector<u8> t; PutTag(t, "text"); PutU32(t, 0);
    t.insert(t.end(), text.begin(), text.end()); t.push_back(0);
    return t;
}

// Kurva sRGB (parametrik IEC 61966-2-1) disampel 1024 titik sebagai 'curv' 16-bit.
std::vector<u8> SrgbCurveTag() {
    constexpr u32 kSamples = 1024;
    std::vector<u8> t; PutTag(t, "curv"); PutU32(t, 0); PutU32(t, kSamples);
    for (u32 i = 0; i < kSamples; ++i) {
        const double x = double(i) / (kSamples - 1);
        const double y = x <= 0.04045 ? x / 12.92 : std::pow((x + 0.055) / 1.055, 2.4);
        PutU16(t, static_cast<u16>(std::lround(std::clamp(y, 0.0, 1.0) * 65535.0)));
    }
    return t;
}

}  // namespace

std::vector<u8> BuildSrgbIccProfile() {
    // Primer sRGB yang sudah diadaptasi Bradford ke D50 (PCS XYZ).
    struct Blob { const char* sig; std::vector<u8> data; };
    const auto curve = SrgbCurveTag();
    std::vector<Blob> blobs;
    blobs.push_back({"desc", DescTag("sRGB IEC61966-2.1 (X++ virtual display)")});
    blobs.push_back({"wtpt", XyzTag(0.9642, 1.0, 0.8249)});
    blobs.push_back({"cprt", TextTag("Public Domain")});
    blobs.push_back({"rXYZ", XyzTag(0.4360747, 0.2225045, 0.0139322)});
    blobs.push_back({"gXYZ", XyzTag(0.3850649, 0.7168786, 0.0971045)});
    blobs.push_back({"bXYZ", XyzTag(0.1430804, 0.0606169, 0.7141733)});
    blobs.push_back({"rTRC", curve});   // tiga tag menunjuk blok kurva yang sama

    constexpr u32 kTagCount = 9;
    const u32 tableEnd = 128 + 4 + kTagCount * 12;
    std::vector<u8> body;
    std::vector<TagEntry> entries;
    auto place = [&](const char* sig, const std::vector<u8>& data) {
        entries.push_back({sig, u32(tableEnd + body.size()), u32(data.size())});
        body.insert(body.end(), data.begin(), data.end()); PadTo4(body);
    };
    for (size_t i = 0; i + 1 < blobs.size(); ++i) place(blobs[i].sig, blobs[i].data);
    const u32 curveOffset = u32(tableEnd + body.size());
    body.insert(body.end(), curve.begin(), curve.end()); PadTo4(body);
    entries.push_back({"rTRC", curveOffset, u32(curve.size())});
    entries.push_back({"gTRC", curveOffset, u32(curve.size())});
    entries.push_back({"bTRC", curveOffset, u32(curve.size())});

    std::vector<u8> out;
    PutU32(out, u32(tableEnd + body.size()));         // ukuran profil
    PutU32(out, 0);                                   // CMM
    PutU32(out, 0x02100000);                          // versi 2.1.0
    PutTag(out, "mntr"); PutTag(out, "RGB "); PutTag(out, "XYZ ");
    PutU16(out, 2024); PutU16(out, 1); PutU16(out, 1); PutU16(out, 0); PutU16(out, 0); PutU16(out, 0);  // tanggal tetap
    PutTag(out, "acsp");
    PutU32(out, 0); PutU32(out, 0); PutU32(out, 0); PutU32(out, 0);   // platform, flags, manufacturer, model
    PutU32(out, 0); PutU32(out, 0);                                   // attributes
    PutU32(out, 0);                                                   // rendering intent: perceptual
    PutS15Fixed16(out, 0.9642); PutS15Fixed16(out, 1.0); PutS15Fixed16(out, 0.8249);  // illuminant D50
    PutU32(out, 0);                                                   // creator
    out.resize(128, 0);                                               // profile ID (v2: boleh nol) + reserved
    PutU32(out, kTagCount);
    for (const auto& e : entries) { PutTag(out, e.sig); PutU32(out, e.offset); PutU32(out, e.size); }
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

bool IsValidIccProfile(std::span<const u8> profile) {
    if (profile.size() < 132 || profile.size() > kMaxIccProfileSize) return false;
    if (ReadU32(profile, 0) != profile.size()) return false;
    return std::memcmp(profile.data() + 36, "acsp", 4) == 0;
}

void InstallIccProfile(XServer& server, std::span<const u8> profile) {
    std::vector<u8> data(profile.begin(), profile.end());
    if (data.empty()) data = BuildSrgbIccProfile();
    Window* root = server.GetWindowManager().GetRootWindow();
    if (!root) return;
    root->ModifyProperty(Atom::Intern("_ICC_PROFILE"), kXaCardinal, 8, kPropModeReplace, data);
    root->ModifyProperty(Atom::Intern("_ICC_PROFILE_0"), kXaCardinal, 8, kPropModeReplace, data);
    root->ModifyProperty(Atom::Intern("_ICC_PROFILE_IN_X_VERSION"), kXaCardinal, 8, kPropModeReplace,
                         std::vector<u8>{kIccInXVersion});
}

const Property* GetIccProfileProperty(XServer& server) {
    Window* root = server.GetWindowManager().GetRootWindow();
    if (!root) return nullptr;
    const s32 atom = Atom::GetID("_ICC_PROFILE");
    return atom < 0 ? nullptr : root->GetProperty(static_cast<u32>(atom));
}

}  // namespace xorg
