#pragma once

#include <cstddef>
#include <vector>

#include "core/render_picture.h"

namespace xorg {

// Operasi piksel RENDER pada drawable 32-bit (semua kedalaman disimpan 32 bit per piksel di server ini).
// Kanonik: ARGB premultiplied. Depth 24 dibaca sebagai alpha 255; depth 8 menyimpan alpha di byte rendah; depth 1 menyimpan 0/1.
inline u32 RenderCanon(u8 depth, u32 p) {
    switch (depth) {
        case 24: return p | 0xff000000u;
        case 8: return (p & 0xffu) << 24;
        case 1: return (p & 1u) ? 0xff000000u : 0u;
        default: return p;
    }
}
inline u32 RenderStore(u8 depth, u32 c) {
    switch (depth) {
        case 24: return c | 0xff000000u;
        case 8: return c >> 24;
        case 1: return (c >> 24) >= 128 ? 1u : 0u;
        default: return c;
    }
}

struct RenderSurface {
    const u32* px{};
    s32 stride{};  // dalam piksel
    s32 w{}, h{};
    u8 depth{32};
};

// Sumber atau mask: gambar (dengan repeat/transform/filter) atau warna solid.
struct RenderSource {
    bool solid{};
    u32 color{};
    RenderSurface surface;
    int repeat{};
    bool hasTransform{};
    s32 m[9]{};
    bool bilinear{};
    bool componentAlpha{};
    s32 ox{}, oy{};  // koordinat sumber = koordinat tujuan + (ox, oy)
};

// Menggabungkan sumber (dengan mask opsional) ke area (dx, dy, w, h) di drawable tujuan, dengan clip picture tujuan.
// Mengembalikan false bila tidak ada piksel yang berubah. Penulisan dilakukan lewat [write] supaya NativeScreen
// mencatat perubahan drawable (damage, invalidasi layar).
struct RenderTarget {
    Drawable* drawable{};
    u8 depth{32};
    bool hasClip{};
    const RectList* clip{};
};

// Buffer hasil gabungan: wilayah tujuan yang sudah dihitung, siap ditulis ke drawable.
struct RenderResult {
    s32 x{}, y{}, w{}, h{};
    std::vector<u32> pixels;
    bool valid() const { return w > 0 && h > 0; }
};

RenderResult RenderComposite(int op, const RenderSource& src, const RenderSource* mask, const RenderTarget& dst,
                             s32 dx, s32 dy, s32 w, s32 h);

}  // namespace xorg
