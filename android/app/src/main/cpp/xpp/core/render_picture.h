#pragma once

#include <memory>
#include <unordered_map>
#include <vector>

#include "core/drawable.h"
#include "core/xresource.h"

namespace xorg {

// Persegi [x1,x2) x [y1,y2) dalam koordinat drawable.
struct RenderRect {
    s32 x1{}, y1{}, x2{}, y2{};
    bool Empty() const { return x2 <= x1 || y2 <= y1; }
};
using RectList = std::vector<RenderRect>;

// Aljabar region sederhana (daftar persegi yang tidak saling tumpang tindih setelah RegionNormalize).
RectList RegionNormalize(RectList rects);
RectList RegionUnion(const RectList& a, const RectList& b);
RectList RegionIntersect(const RectList& a, const RectList& b);
RectList RegionSubtract(const RectList& a, const RectList& b);
RectList RegionTranslate(RectList rects, s32 dx, s32 dy);
RenderRect RegionExtents(const RectList& rects);

// Gradien RENDER (CreateLinearGradient/RadialGradient/ConicalGradient). Koordinat dalam piksel (double),
// warna stop premultiplied (XRenderColor sudah premultiplied) dan diinterpolasi dalam ruang premultiplied.
struct RenderGradient {
    enum class Kind { Linear, Radial, Conical };
    struct Stop { double pos; float a, r, g, b; };
    Kind kind{Kind::Linear};
    double x1{}, y1{}, x2{}, y2{};  // linear: p1 dan p2; radial: pusat lingkaran dalam dan luar; conical: pusat di (x1, y1)
    double r1{}, r2{};              // radial: jari-jari dalam dan luar
    double angle{};                 // conical: derajat
    std::vector<Stop> stops;
};

// Picture RENDER. image kosong untuk solid fill dan gradien.
struct RenderPicture final : XResource {
    RenderPicture(u32 id, sp<Drawable> image, u8 depth)
        : XResource(XResourceType::RenderPicture, id), image(std::move(image)), depth(depth) {}
    // Preserve the backing image even when libXcursor frees its pixmap first.
    // Writes after CreatePicture are observed until CreateCursor snapshots it.
    sp<Drawable> image;
    u8 depth;
    bool solid{};
    u32 solidColor{};         // ARGB premultiplied
    sp<RenderGradient> gradient;  // bila terisi, picture ini gradien (image kosong)
    int repeat{};             // 0 none, 1 normal, 2 pad, 3 reflect
    bool componentAlpha{};
    bool bilinear{};          // filter: false = nearest
    bool hasClip{};
    RectList clip;            // koordinat drawable absolut
    bool hasTransform{};
    s32 transform[9]{};       // 16.16, baris demi baris
};

// Region XFixes.
struct XFixesRegion final : XResource {
    XFixesRegion(u32 id, RectList r) : XResource(XResourceType::Region, id), rects(std::move(r)) {}
    RectList rects;
};

struct RenderGlyph {
    u16 width{}, height{};
    s16 x{}, y{}, xOff{}, yOff{};
    std::vector<u32> pixels;  // kanonik: ARGB premultiplied (A8 => alpha, A1 => 0/255 alpha)
};
using GlyphMap = std::unordered_map<u32, RenderGlyph>;

struct RenderGlyphSet final : XResource {
    RenderGlyphSet(u32 id, u8 depth, sp<GlyphMap> glyphs)
        : XResource(XResourceType::GlyphSet, id), depth(depth), glyphs(std::move(glyphs)) {}
    u8 depth;  // 1, 8 atau 32
    sp<GlyphMap> glyphs;
};

}  // namespace xorg
