// Implementasi RENDER (0.8) di atas drawable 32-bit server ini: operator Porter-Duff, FillRectangles, glyph,
// trapezoid/segitiga, transform, repeat, filter, clip, format A1/A8/RGB24/ARGB32. Perubahan drawable lewat
// NativeScreen::MutateDrawable sehingga layar dan Damage ikut diperbarui.
#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window.h"
#include "core/pixmap.h"
#include "core/cursor.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/id_generator.h"
#include "core/render_ops.h"
#include "core/render_picture.h"
#include "core/visual.h"
#include "native_screen.h"
#include <X11/extensions/renderproto.h>

namespace xorg {
namespace {

// Urutan: A1, RGB24, ARGB32, A8.
const std::array<u32, 4>& PictFormats() {
    static const std::array<u32, 4> formats{u32(GenerateID()), u32(GenerateID()), u32(GenerateID()), u32(GenerateID())};
    return formats;
}
constexpr std::array<u8, 4> kFormatDepth{1, 24, 32, 8};

RenderSource Describe(RenderPicture& p, s32 ox, s32 oy) {
    RenderSource d;
    d.ox = ox; d.oy = oy; d.repeat = p.repeat; d.componentAlpha = p.componentAlpha;
    if (p.solid) { d.solid = true; d.color = p.solidColor; return d; }
    if (p.gradient) {
        d.gradient = p.gradient.get();
        d.hasTransform = p.hasTransform;
        std::copy(std::begin(p.transform), std::end(p.transform), d.m);
        return d;
    }
    Drawable* dr = p.image.get();
    d.surface.px = static_cast<const u32*>(dr->Map());
    d.surface.stride = s32(dr->GetStride() / 4);
    d.surface.w = dr->GetWidth(); d.surface.h = dr->GetHeight(); d.surface.depth = p.depth;
    d.hasTransform = p.hasTransform;
    std::copy(std::begin(p.transform), std::end(p.transform), d.m);
    d.bilinear = p.bilinear;
    return d;
}

u32 PremultColor(const xRenderColor& c) {
    const u32 a = c.alpha >> 8;
    return (a << 24) | (std::min<u32>(c.red >> 8, a) << 16) | (std::min<u32>(c.green >> 8, a) << 8) | std::min<u32>(c.blue >> 8, a);
}

// Lintasan nilai antar sub-scanline dalam piksel: cakupan dari rentang [xl, xr).
void AddSpan(std::vector<float>& row, float xl, float xr, float weight, int x0) {
    if (xr <= xl) return;
    const int first = std::max(int(std::floor(xl)), x0), last = std::min(int(std::ceil(xr)), x0 + int(row.size()));
    for (int x = first; x < last; ++x) {
        const float overlap = std::min(xr, float(x + 1)) - std::max(xl, float(x));
        if (overlap > 0) row[x - x0] += overlap * weight;
    }
}

struct RasterMask {
    s32 x{}, y{}, w{}, h{};
    std::vector<u32> px;  // alpha kanonik (a << 24)
};

float EdgeX(const xLineFixed& l, float y) {
    const float x1 = l.p1.x / 65536.f, y1 = l.p1.y / 65536.f, x2 = l.p2.x / 65536.f, y2 = l.p2.y / 65536.f;
    return y2 == y1 ? x1 : x1 + (y - y1) * (x2 - x1) / (y2 - y1);
}

template <typename ScanFn>
RasterMask Rasterize(s32 bx0, s32 by0, s32 bx1, s32 by1, ScanFn&& scan) {
    RasterMask m;
    m.x = bx0; m.y = by0; m.w = bx1 - bx0; m.h = by1 - by0;
    if (m.w <= 0 || m.h <= 0) { m.w = m.h = 0; return m; }
    m.px.assign(size_t(m.w) * m.h, 0);
    std::vector<float> row(m.w);
    constexpr int kSub = 4;
    for (s32 y = by0; y < by1; ++y) {
        std::fill(row.begin(), row.end(), 0.f);
        for (int k = 0; k < kSub; ++k) scan(y + (k + 0.5f) / kSub, 1.f / kSub, row, bx0);
        for (s32 x = 0; x < m.w; ++x) {
            const u32 a = u32(std::clamp(row[x], 0.f, 1.f) * 255.f + 0.5f);
            m.px[size_t(y - by0) * m.w + x] = a << 24;
        }
    }
    return m;
}

struct PointF { float x, y; };
void TriangleScan(const PointF* p, float ys, float weight, std::vector<float>& row, int x0) {
    float lo = 1e30f, hi = -1e30f;
    for (int i = 0; i < 3; ++i) {
        const PointF a = p[i], b = p[(i + 1) % 3];
        if ((a.y <= ys && b.y > ys) || (b.y <= ys && a.y > ys)) {
            const float x = a.x + (ys - a.y) * (b.x - a.x) / (b.y - a.y);
            lo = std::min(lo, x); hi = std::max(hi, x);
        }
    }
    if (hi > lo) AddSpan(row, lo, hi, weight, x0);
}

}  // namespace

void XClient::ProcRender() {
    const auto& formats = PictFormats();
    const auto* extension = server.GetExtensionManager().FindExtension("RENDER");
    auto& screen = static_cast<NativeScreen&>(server.GetScreen());
    const auto renderError = [&](u8 offset, u32 id) { return XRequestError(extension->error_base + offset, id); };
    auto newID = [&](u32 id) { CheckResourceID(id); if (GetResource(id)) throw BadIdChoiceError(id); };
    auto picture = [&](u32 id) -> RenderPicture* {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::RenderPicture) throw renderError(BadPicture, id);
        return r->As<RenderPicture>();
    };
    auto picture_lookup = [&](u32 id) { return picture(id); };
    auto glyphset = [&](u32 id) -> RenderGlyphSet* {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::GlyphSet) throw renderError(BadGlyphSet, id);
        return r->As<RenderGlyphSet>();
    };
    auto depthOf = [&](u32 format) -> int {
        auto f = std::ranges::find(formats, format);
        return f == formats.end() ? -1 : kFormatDepth[size_t(f - formats.begin())];
    };

    // Atribut picture (CreatePicture / ChangePicture): nilai berurutan menurut bit mask.
    auto applyAttributes = [&](RenderPicture& pic, u32 mask, const u32* values) {
        if (mask >= (1u << 13)) throw BadValueError(mask);
        s32 clipX = 0, clipY = 0; u32 clipMask = 0; bool clipMaskSet = false;
        for (u32 bit = 0; bit < 13; ++bit) {
            if (!(mask & (1u << bit))) continue;
            const u32 v = *values++;
            switch (bit) {
                case 0: if (v > 3) throw BadValueError(v); pic.repeat = int(v); break;
                case 4: clipX = s32(s16(v & 0xffff)); break;
                case 5: clipY = s32(s16(v & 0xffff)); break;
                case 6: clipMask = v; clipMaskSet = true; break;
                case 12: pic.componentAlpha = v != 0; break;
                default: break;  // alpha map, graphics exposure, subwindow mode, poly edge/mode, dither: tidak berpengaruh
            }
        }
        if (clipMaskSet) {
            if (clipMask == 0) { pic.hasClip = false; pic.clip.clear(); return; }
            auto* pm = GetResourceAs<Pixmap>(clipMask);
            if (!pm) throw BadPixmapError(clipMask);
            std::vector<u32> bits(size_t(pm->width) * pm->height);
            pm->GetDrawable()->ReadPixels(reinterpret_cast<u8*>(bits.data()), 0, 0, pm->width, pm->height, pm->width);
            RectList rects;
            for (s32 y = 0; y < pm->height; ++y) {
                for (s32 x = 0; x < pm->width;) {
                    if (!(bits[size_t(y) * pm->width + x] & 1)) { ++x; continue; }
                    const s32 start = x;
                    while (x < pm->width && (bits[size_t(y) * pm->width + x] & 1)) ++x;
                    rects.push_back({start, y, x, y + 1});
                }
            }
            pic.clip = RegionTranslate(RegionNormalize(std::move(rects)), clipX, clipY);
            pic.hasClip = true;
        } else if ((mask & ((1u << 4) | (1u << 5))) && pic.hasClip) {
            // hanya origin clip berubah: geser terhadap origin sebelumnya tidak dilacak; dianggap tidak berubah
        }
    };

    // Menulis hasil komposit ke drawable tujuan lewat NativeScreen.
    auto commit = [&](RenderPicture& dst, const RenderResult& r) {
        if (!r.valid()) return;
        Drawable* dd = dst.image.get();
        screen.MutateDrawable(dd, [&] {
            dd->WritePixels(reinterpret_cast<const u8*>(r.pixels.data()), 0, 0, s16(r.x), s16(r.y), u16(r.w), u16(r.h), u16(r.w));
        });
    };
    auto target = [&](RenderPicture& dst) {
        if (dst.solid || !dst.image) throw BadMatchError();
        RenderTarget t; t.drawable = dst.image.get(); t.depth = dst.depth; t.hasClip = dst.hasClip; t.clip = &dst.clip;
        return t;
    };
    // Gradien: daftar posisi (Fixed, nStops buah) lalu warna (xRenderColor premultiplied, nStops buah) setelah header.
    auto readStops = [&](RenderGradient& g, size_t headerSize, u32 n) {
        if (n < 1 || n > 4096 || request_size != headerSize + size_t(n) * (sizeof(s32) + sizeof(xRenderColor)))
            throw BadLengthError();
        const u8* base = reinterpret_cast<const u8*>(GetRequestData<xReq>());
        g.stops.reserve(n);
        for (u32 i = 0; i < n; ++i) {
            s32 pos; xRenderColor c;
            std::memcpy(&pos, base + headerSize + size_t(i) * sizeof(s32), sizeof(pos));
            std::memcpy(&c, base + headerSize + size_t(n) * sizeof(s32) + size_t(i) * sizeof(c), sizeof(c));
            const float a = c.alpha / 65535.f;
            g.stops.push_back({pos / 65536.0, a, std::min(c.red / 65535.f, a), std::min(c.green / 65535.f, a),
                               std::min(c.blue / 65535.f, a)});
        }
    };
    auto addGradient = [&](u32 pid, sp<RenderGradient> g) {
        auto pic = std::make_shared<RenderPicture>(pid, nullptr, 32);
        pic->gradient = std::move(g);
        AddResource(pic);
    };
    auto checkOp = [&](u8 op) { if (op > 13 && !(op >= 0x10 && op <= 0x2f)) throw renderError(BadPictOp, op); };

    // Mask A8 hasil rasterisasi trapezoid/segitiga di-gabung ke tujuan melalui sumber + mask.
    auto compositeMask = [&](u8 op, RenderPicture& src, RenderPicture& dst, const RasterMask& m, s32 srcOx, s32 srcOy) {
        if (m.w <= 0) return;
        RenderSource s = Describe(src, srcOx, srcOy);
        RenderSource mk;
        mk.surface.px = m.px.data(); mk.surface.stride = m.w; mk.surface.w = m.w; mk.surface.h = m.h; mk.surface.depth = 32;
        mk.ox = -m.x; mk.oy = -m.y;
        commit(dst, RenderComposite(op, s, &mk, target(dst), m.x, m.y, m.w, m.h));
    };

    switch (GetRequestData<xReq>()->data) {
    case X_RenderQueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xRenderQueryVersionReq);
        const auto* r = GetRequestData<xRenderQueryVersionReq>();
        xRenderQueryVersionReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.majorVersion = 0; reply.minorVersion = r->majorVersion ? 8 : std::min(8u, r->minorVersion);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_RenderQueryPictFormats: {
        CHECK_REQUEST_SIZE_STRICT(xRenderQueryPictFormatsReq);
        struct FormatsReply {
            xRenderQueryPictFormatsReply header{};
            std::array<xPictFormInfo, 4> formats{};
            xPictScreen screen{};
            xPictDepth a1{}, a8{}, rgb{};
            xPictVisual visual{};
            xPictDepth argb{};
            u32 subpixel{};
        } packet;
        static_assert(sizeof(packet) == 32 + 4 * 28 + 8 + 4 * 8 + 8 + 4);
        auto& h = packet.header;
        h.type = X_Reply; h.sequenceNumber = sequence; h.length = (sizeof(packet) - 32) / 4;
        h.numFormats = 4; h.numScreens = 1; h.numDepths = 4; h.numVisuals = 1; h.numSubpixel = 1;
        for (size_t i = 0; i < 4; ++i) {
            auto& f = packet.formats[i]; f.id = formats[i]; f.type = PictTypeDirect; f.depth = kFormatDepth[i];
            if (kFormatDepth[i] == 1) { f.direct.alpha = 0; f.direct.alphaMask = 1; }
            else if (kFormatDepth[i] == 8) { f.direct.alpha = 0; f.direct.alphaMask = 255; }
            else {
                f.direct.red = 16; f.direct.green = 8; f.direct.blue = 0;
                f.direct.redMask = f.direct.greenMask = f.direct.blueMask = 255;
                if (kFormatDepth[i] == 32) { f.direct.alpha = 24; f.direct.alphaMask = 255; }
            }
        }
        packet.screen.nDepth = 4; packet.screen.fallback = formats[1];
        packet.a1.depth = 1; packet.a8.depth = 8; packet.rgb.depth = 24; packet.rgb.nPictVisuals = 1;
        packet.visual.visual = server.GetRootVisual()->id; packet.visual.format = formats[1];
        packet.argb.depth = 32;
        WriteImmediately(&packet, sizeof(packet)); return;
    }
    case X_RenderCreatePicture: {
        CHECK_REQUEST_SIZE(xRenderCreatePictureReq);
        auto* r = GetRequestData<xRenderCreatePictureReq>(); newID(r->pid);
        if (request_size != sizeof(*r) + std::popcount(r->mask) * 4u) throw BadLengthError();
        const int depth = depthOf(r->format);
        if (depth < 0) throw renderError(BadPictFormat, r->format);
        auto* resource = GetResource(r->drawable);
        if (!resource || !resource->IsDrawable()) throw BadDrawableError(r->drawable);
        sp<Drawable> image = resource->GetType() == XResourceType::Pixmap
            ? resource->As<Pixmap>()->SharedDrawable() : resource->As<Window>()->SharedDrawable();
        if (!image || image->GetDepth() != depth) throw BadMatchError();
        auto pic = std::make_shared<RenderPicture>(r->pid, std::move(image), u8(depth));
        applyAttributes(*pic, r->mask, reinterpret_cast<const u32*>(r + 1));
        AddResource(pic); return;
    }
    case X_RenderChangePicture: {
        CHECK_REQUEST_SIZE(xRenderChangePictureReq);
        auto* r = GetRequestData<xRenderChangePictureReq>();
        if (request_size != sizeof(*r) + std::popcount(r->mask) * 4u) throw BadLengthError();
        applyAttributes(*picture(r->picture), r->mask, reinterpret_cast<const u32*>(r + 1)); return;
    }
    case X_RenderSetPictureClipRectangles: {
        CHECK_REQUEST_SIZE(xRenderSetPictureClipRectanglesReq);
        auto* r = GetRequestData<xRenderSetPictureClipRectanglesReq>();
        auto* pic = picture(r->picture);
        const size_t bytes = request_size - sizeof(*r);
        if (bytes % sizeof(xRectangle)) throw BadLengthError();
        const auto* rects = reinterpret_cast<const xRectangle*>(r + 1);
        RectList list;
        for (size_t i = 0; i < bytes / sizeof(xRectangle); ++i)
            list.push_back({rects[i].x, rects[i].y, rects[i].x + rects[i].width, rects[i].y + rects[i].height});
        pic->clip = RegionTranslate(RegionNormalize(std::move(list)), r->xOrigin, r->yOrigin);
        pic->hasClip = true; return;
    }
    case X_RenderFreePicture: {
        CHECK_REQUEST_SIZE_STRICT(xRenderFreePictureReq);
        const u32 id = GetRequestData<xRenderFreePictureReq>()->picture;
        picture(id); RemoveResource(id); return;
    }
    case X_RenderComposite: {
        CHECK_REQUEST_SIZE_STRICT(xRenderCompositeReq);
        auto* r = GetRequestData<xRenderCompositeReq>();
        checkOp(r->op);
        auto* src = picture(r->src); auto* dst = picture(r->dst);
        RenderPicture* mask = r->mask ? picture(r->mask) : nullptr;
        RenderSource s = Describe(*src, r->xSrc - r->xDst, r->ySrc - r->yDst);
        RenderSource m; if (mask) m = Describe(*mask, r->xMask - r->xDst, r->yMask - r->yDst);
        commit(*dst, RenderComposite(r->op, s, mask ? &m : nullptr, target(*dst), r->xDst, r->yDst, r->width, r->height));
        return;
    }
    case X_RenderFillRectangles: {
        CHECK_REQUEST_SIZE(xRenderFillRectanglesReq);
        auto* r = GetRequestData<xRenderFillRectanglesReq>();
        checkOp(r->op);
        auto* dst = picture(r->dst);
        const size_t bytes = request_size - sizeof(*r);
        if (bytes % sizeof(xRectangle)) throw BadLengthError();
        const auto* rects = reinterpret_cast<const xRectangle*>(r + 1);
        RenderSource s; s.solid = true; s.color = PremultColor(r->color);
        for (size_t i = 0; i < bytes / sizeof(xRectangle); ++i)
            commit(*dst, RenderComposite(r->op, s, nullptr, target(*dst), rects[i].x, rects[i].y, rects[i].width, rects[i].height));
        return;
    }
    case X_RenderCreateSolidFill: {
        CHECK_REQUEST_SIZE_STRICT(xRenderCreateSolidFillReq);
        auto* r = GetRequestData<xRenderCreateSolidFillReq>(); newID(r->pid);
        auto pic = std::make_shared<RenderPicture>(r->pid, nullptr, 32);
        pic->solid = true; pic->solidColor = PremultColor(r->color); pic->repeat = 1;
        AddResource(pic); return;
    }
    case X_RenderCreateLinearGradient: {
        CHECK_REQUEST_SIZE(xRenderCreateLinearGradientReq);
        auto* r = GetRequestData<xRenderCreateLinearGradientReq>(); newID(r->pid);
        auto g = std::make_shared<RenderGradient>();
        g->kind = RenderGradient::Kind::Linear;
        g->x1 = r->p1.x / 65536.0; g->y1 = r->p1.y / 65536.0; g->x2 = r->p2.x / 65536.0; g->y2 = r->p2.y / 65536.0;
        readStops(*g, sizeof(*r), r->nStops);
        addGradient(r->pid, std::move(g)); return;
    }
    case X_RenderCreateRadialGradient: {
        CHECK_REQUEST_SIZE(xRenderCreateRadialGradientReq);
        auto* r = GetRequestData<xRenderCreateRadialGradientReq>(); newID(r->pid);
        auto g = std::make_shared<RenderGradient>();
        g->kind = RenderGradient::Kind::Radial;
        g->x1 = r->inner.x / 65536.0; g->y1 = r->inner.y / 65536.0; g->x2 = r->outer.x / 65536.0; g->y2 = r->outer.y / 65536.0;
        g->r1 = r->inner_radius / 65536.0; g->r2 = r->outer_radius / 65536.0;
        readStops(*g, sizeof(*r), r->nStops);
        addGradient(r->pid, std::move(g)); return;
    }
    case X_RenderCreateConicalGradient: {
        CHECK_REQUEST_SIZE(xRenderCreateConicalGradientReq);
        auto* r = GetRequestData<xRenderCreateConicalGradientReq>(); newID(r->pid);
        auto g = std::make_shared<RenderGradient>();
        g->kind = RenderGradient::Kind::Conical;
        g->x1 = r->center.x / 65536.0; g->y1 = r->center.y / 65536.0; g->angle = r->angle / 65536.0;
        readStops(*g, sizeof(*r), r->nStops);
        addGradient(r->pid, std::move(g)); return;
    }
    case X_RenderSetPictureTransform: {
        CHECK_REQUEST_SIZE_STRICT(xRenderSetPictureTransformReq);
        auto* r = GetRequestData<xRenderSetPictureTransformReq>();
        auto* pic = picture(r->picture);
        const s32 m[9] = {r->transform.matrix11, r->transform.matrix12, r->transform.matrix13,
                          r->transform.matrix21, r->transform.matrix22, r->transform.matrix23,
                          r->transform.matrix31, r->transform.matrix32, r->transform.matrix33};
        constexpr s32 identity[9] = {65536, 0, 0, 0, 65536, 0, 0, 0, 65536};
        pic->hasTransform = !std::equal(std::begin(m), std::end(m), std::begin(identity));
        std::copy(std::begin(m), std::end(m), pic->transform); return;
    }
    case X_RenderQueryFilters: {
        CHECK_REQUEST_SIZE_STRICT(xRenderQueryFiltersReq);
        static const char* const names[] = {"nearest", "bilinear", "fast", "good", "best"};
        static const u16 aliases[] = {0xffff, 0xffff, 0, 1, 1};
        std::vector<u8> body;
        for (u16 a : aliases) { body.push_back(u8(a & 0xff)); body.push_back(u8(a >> 8)); }
        while (body.size() % 4) body.push_back(0);
        for (const char* n : names) { body.push_back(u8(strlen(n))); body.insert(body.end(), n, n + strlen(n)); }
        while (body.size() % 4) body.push_back(0);
        xRenderQueryFiltersReply reply{}; reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.length = u32(body.size() / 4); reply.numAliases = 5; reply.numFilters = 5;
        WriteImmediately(&reply, sizeof(reply)); WriteImmediately(body.data(), body.size()); return;
    }
    case X_RenderSetPictureFilter: {
        CHECK_REQUEST_SIZE(xRenderSetPictureFilterReq);
        auto* r = GetRequestData<xRenderSetPictureFilterReq>();
        auto* pic = picture(r->picture);
        if (sizeof(*r) + r->nbytes > request_size) throw BadLengthError();
        const std::string name(reinterpret_cast<const char*>(r + 1), r->nbytes);
        pic->bilinear = !(name == "nearest" || name == "fast"); return;
    }
    case X_RenderCreateGlyphSet: {
        CHECK_REQUEST_SIZE_STRICT(xRenderCreateGlyphSetReq);
        auto* r = GetRequestData<xRenderCreateGlyphSetReq>(); newID(r->gsid);
        const int depth = depthOf(r->format);
        if (depth < 0) throw renderError(BadPictFormat, r->format);
        AddResource(std::make_shared<RenderGlyphSet>(r->gsid, u8(depth), std::make_shared<GlyphMap>())); return;
    }
    case X_RenderReferenceGlyphSet: {
        CHECK_REQUEST_SIZE_STRICT(xRenderReferenceGlyphSetReq);
        auto* r = GetRequestData<xRenderReferenceGlyphSetReq>(); newID(r->gsid);
        auto* existing = glyphset(r->existing);
        AddResource(std::make_shared<RenderGlyphSet>(r->gsid, existing->depth, existing->glyphs)); return;
    }
    case X_RenderFreeGlyphSet: {
        CHECK_REQUEST_SIZE_STRICT(xRenderFreeGlyphSetReq);
        const u32 id = GetRequestData<xRenderFreeGlyphSetReq>()->glyphset;
        glyphset(id); RemoveResource(id); return;
    }
    case X_RenderAddGlyphs: {
        CHECK_REQUEST_SIZE(xRenderAddGlyphsReq);
        auto* r = GetRequestData<xRenderAddGlyphsReq>();
        auto* gs = glyphset(r->glyphset);
        const size_t n = r->nglyphs;
        const u8* p = reinterpret_cast<const u8*>(r + 1);
        const u8* end = reinterpret_cast<const u8*>(r) + request_size;
        if (n > 65536 || size_t(end - p) < n * (4 + sizeof(xGlyphInfo))) throw BadLengthError();
        const u32* ids = reinterpret_cast<const u32*>(p);
        const xGlyphInfo* infos = reinterpret_cast<const xGlyphInfo*>(p + n * 4);
        const u8* data = p + n * (4 + sizeof(xGlyphInfo));
        for (size_t i = 0; i < n; ++i) {
            const xGlyphInfo& gi = infos[i];
            size_t rowBytes;
            if (gs->depth == 1) rowBytes = ((size_t(gi.width) + 31) / 32) * 4;
            else if (gs->depth == 8) rowBytes = (size_t(gi.width) + 3) & ~size_t(3);
            else rowBytes = size_t(gi.width) * 4;
            const size_t bytes = rowBytes * gi.height;
            if (size_t(end - data) < bytes) throw BadLengthError();
            RenderGlyph glyph; glyph.width = gi.width; glyph.height = gi.height; glyph.x = gi.x; glyph.y = gi.y;
            glyph.xOff = gi.xOff; glyph.yOff = gi.yOff;
            glyph.pixels.resize(size_t(gi.width) * gi.height);
            for (u32 y = 0; y < gi.height; ++y) for (u32 x = 0; x < gi.width; ++x) {
                u32 v;
                if (gs->depth == 1) v = (data[y * rowBytes + x / 8] >> (x % 8) & 1) ? 0xff000000u : 0u;
                else if (gs->depth == 8) v = u32(data[y * rowBytes + x]) << 24;
                else memcpy(&v, data + y * rowBytes + x * 4, 4);
                glyph.pixels[size_t(y) * gi.width + x] = v;
            }
            data += bytes;
            (*gs->glyphs)[ids[i]] = std::move(glyph);
        }
        return;
    }
    case X_RenderFreeGlyphs: {
        CHECK_REQUEST_SIZE(xRenderFreeGlyphsReq);
        auto* r = GetRequestData<xRenderFreeGlyphsReq>();
        auto* gs = glyphset(r->glyphset);
        const size_t n = (request_size - sizeof(*r)) / 4;
        const u32* ids = reinterpret_cast<const u32*>(r + 1);
        for (size_t i = 0; i < n; ++i) gs->glyphs->erase(ids[i]);
        return;
    }
    case X_RenderCompositeGlyphs8:
    case X_RenderCompositeGlyphs16:
    case X_RenderCompositeGlyphs32: {
        CHECK_REQUEST_SIZE(xRenderCompositeGlyphsReq);
        auto* r = GetRequestData<xRenderCompositeGlyphsReq>();
        checkOp(r->op);
        auto* src = picture(r->src); auto* dst = picture(r->dst);
        auto* gs = glyphset(r->glyphset);
        const size_t idSize = GetRequestData<xReq>()->data == X_RenderCompositeGlyphs8 ? 1
            : GetRequestData<xReq>()->data == X_RenderCompositeGlyphs16 ? 2 : 4;
        const u8* p = reinterpret_cast<const u8*>(r + 1);
        const u8* end = reinterpret_cast<const u8*>(r) + request_size;
        s32 penX = 0, penY = 0;
        while (end - p >= ptrdiff_t(sizeof(xGlyphElt))) {
            xGlyphElt elt; memcpy(&elt, p, sizeof(elt)); p += sizeof(elt);
            if (elt.len == 0xff) {  // ganti glyphset
                if (end - p < 4) throw BadLengthError();
                u32 id; memcpy(&id, p, 4); p += 4;
                gs = glyphset(id);
                continue;
            }
            penX += elt.deltax; penY += elt.deltay;
            const size_t bytes = size_t(elt.len) * idSize;
            if (size_t(end - p) < bytes) throw BadLengthError();
            for (u32 i = 0; i < elt.len; ++i) {
                u32 id = 0; memcpy(&id, p + i * idSize, idSize);
                auto it = gs->glyphs->find(id);
                if (it == gs->glyphs->end()) throw renderError(BadGlyph, id);
                const RenderGlyph& g = it->second;
                if (g.width && g.height) {
                    const s32 gx = penX - g.x, gy = penY - g.y;
                    RenderSource s = Describe(*src, r->xSrc, r->ySrc);
                    RenderSource mk;
                    mk.surface.px = g.pixels.data(); mk.surface.stride = g.width; mk.surface.w = g.width;
                    mk.surface.h = g.height; mk.surface.depth = 32; mk.ox = -gx; mk.oy = -gy;
                    mk.componentAlpha = gs->depth == 32;
                    commit(*dst, RenderComposite(r->op, s, &mk, target(*dst), gx, gy, g.width, g.height));
                }
                penX += g.xOff; penY += g.yOff;
            }
            p += (bytes + 3) & ~size_t(3);
        }
        return;
    }
    case X_RenderTrapezoids: {
        CHECK_REQUEST_SIZE(xRenderTrapezoidsReq);
        auto* r = GetRequestData<xRenderTrapezoidsReq>();
        checkOp(r->op);
        auto* src = picture(r->src); auto* dst = picture(r->dst);
        const size_t bytes = request_size - sizeof(*r);
        if (bytes % sizeof(xTrapezoid)) throw BadLengthError();
        const size_t n = bytes / sizeof(xTrapezoid);
        if (!n) return;
        const auto* traps = reinterpret_cast<const xTrapezoid*>(r + 1);
        float minY = 1e30f, maxY = -1e30f, minX = 1e30f, maxX = -1e30f;
        for (size_t i = 0; i < n; ++i) {
            minY = std::min(minY, traps[i].top / 65536.f); maxY = std::max(maxY, traps[i].bottom / 65536.f);
            for (const auto* l : {&traps[i].left, &traps[i].right}) {
                minX = std::min({minX, l->p1.x / 65536.f, l->p2.x / 65536.f});
                maxX = std::max({maxX, l->p1.x / 65536.f, l->p2.x / 65536.f});
            }
        }
        const s32 bx0 = std::max<s32>(s32(std::floor(minX)), 0), bx1 = std::min<s32>(s32(std::ceil(maxX)), dst->image->GetWidth());
        const s32 by0 = std::max<s32>(s32(std::floor(minY)), 0), by1 = std::min<s32>(s32(std::ceil(maxY)), dst->image->GetHeight());
        auto mask = Rasterize(bx0, by0, bx1, by1, [&](float ys, float weight, std::vector<float>& row, int x0) {
            for (size_t i = 0; i < n; ++i) {
                if (ys < traps[i].top / 65536.f || ys >= traps[i].bottom / 65536.f) continue;
                AddSpan(row, EdgeX(traps[i].left, ys), EdgeX(traps[i].right, ys), weight, x0);
            }
        });
        compositeMask(r->op, *src, *dst, mask, r->xSrc - (traps[0].left.p1.x >> 16), r->ySrc - (traps[0].left.p1.y >> 16));
        return;
    }
    case X_RenderTriangles:
    case X_RenderTriStrip:
    case X_RenderTriFan: {
        CHECK_REQUEST_SIZE(xRenderTrianglesReq);
        auto* r = GetRequestData<xRenderTrianglesReq>();
        checkOp(r->op);
        auto* src = picture(r->src); auto* dst = picture(r->dst);
        const size_t bytes = request_size - sizeof(*r);
        const u8 kind = GetRequestData<xReq>()->data;
        std::vector<std::array<PointF, 3>> tris;
        auto pt = [](const xPointFixed& p) { return PointF{p.x / 65536.f, p.y / 65536.f}; };
        s32 firstX = 0, firstY = 0;
        if (kind == X_RenderTriangles) {
            if (bytes % sizeof(xTriangle)) throw BadLengthError();
            const auto* t = reinterpret_cast<const xTriangle*>(r + 1);
            for (size_t i = 0; i < bytes / sizeof(xTriangle); ++i) tris.push_back({pt(t[i].p1), pt(t[i].p2), pt(t[i].p3)});
            if (bytes) { firstX = t[0].p1.x >> 16; firstY = t[0].p1.y >> 16; }
        } else {
            if (bytes % sizeof(xPointFixed)) throw BadLengthError();
            const auto* pts = reinterpret_cast<const xPointFixed*>(r + 1);
            const size_t count = bytes / sizeof(xPointFixed);
            for (size_t i = 2; i < count; ++i) {
                if (kind == X_RenderTriStrip) tris.push_back({pt(pts[i - 2]), pt(pts[i - 1]), pt(pts[i])});
                else tris.push_back({pt(pts[0]), pt(pts[i - 1]), pt(pts[i])});
            }
            if (count) { firstX = pts[0].x >> 16; firstY = pts[0].y >> 16; }
        }
        if (tris.empty()) return;
        float minY = 1e30f, maxY = -1e30f, minX = 1e30f, maxX = -1e30f;
        for (const auto& t : tris) for (const auto& p : t) {
            minX = std::min(minX, p.x); maxX = std::max(maxX, p.x); minY = std::min(minY, p.y); maxY = std::max(maxY, p.y);
        }
        const s32 bx0 = std::max<s32>(s32(std::floor(minX)), 0), bx1 = std::min<s32>(s32(std::ceil(maxX)), dst->image->GetWidth());
        const s32 by0 = std::max<s32>(s32(std::floor(minY)), 0), by1 = std::min<s32>(s32(std::ceil(maxY)), dst->image->GetHeight());
        auto mask = Rasterize(bx0, by0, bx1, by1, [&](float ys, float weight, std::vector<float>& row, int x0) {
            for (const auto& t : tris) TriangleScan(t.data(), ys, weight, row, x0);
        });
        compositeMask(r->op, *src, *dst, mask, r->xSrc - firstX, r->ySrc - firstY);
        return;
    }
    case X_RenderAddTraps: {
        CHECK_REQUEST_SIZE(xRenderAddTrapsReq);
        auto* r = GetRequestData<xRenderAddTrapsReq>();
        auto* dst = picture(r->picture);
        const size_t bytes = request_size - sizeof(*r);
        if (bytes % sizeof(xTrap)) throw BadLengthError();
        const size_t n = bytes / sizeof(xTrap);
        if (!n) return;
        const auto* traps = reinterpret_cast<const xTrap*>(r + 1);
        auto f = [](s32 v) { return v / 65536.f; };
        float minY = 1e30f, maxY = -1e30f, minX = 1e30f, maxX = -1e30f;
        for (size_t i = 0; i < n; ++i) {
            minY = std::min(minY, f(traps[i].top.y) + r->yOff); maxY = std::max(maxY, f(traps[i].bot.y) + r->yOff);
            minX = std::min({minX, f(traps[i].top.l) + r->xOff, f(traps[i].bot.l) + r->xOff});
            maxX = std::max({maxX, f(traps[i].top.r) + r->xOff, f(traps[i].bot.r) + r->xOff});
        }
        const s32 bx0 = std::max<s32>(s32(std::floor(minX)), 0), bx1 = std::min<s32>(s32(std::ceil(maxX)), dst->image->GetWidth());
        const s32 by0 = std::max<s32>(s32(std::floor(minY)), 0), by1 = std::min<s32>(s32(std::ceil(maxY)), dst->image->GetHeight());
        auto mask = Rasterize(bx0, by0, bx1, by1, [&](float ys, float weight, std::vector<float>& row, int x0) {
            for (size_t i = 0; i < n; ++i) {
                const float t = f(traps[i].top.y) + r->yOff, b = f(traps[i].bot.y) + r->yOff;
                if (ys < t || ys >= b || b <= t) continue;
                const float k = (ys - t) / (b - t);
                const float l = f(traps[i].top.l) + (f(traps[i].bot.l) - f(traps[i].top.l)) * k + r->xOff;
                const float rr = f(traps[i].top.r) + (f(traps[i].bot.r) - f(traps[i].top.r)) * k + r->xOff;
                AddSpan(row, l, rr, weight, x0);
            }
        });
        RenderSource white; white.solid = true; white.color = 0xffffffffu;
        RenderSource mk;
        mk.surface.px = mask.px.data(); mk.surface.stride = mask.w; mk.surface.w = mask.w; mk.surface.h = mask.h;
        mk.surface.depth = 32; mk.ox = -mask.x; mk.oy = -mask.y;
        commit(*dst, RenderComposite(12, white, &mk, target(*dst), mask.x, mask.y, mask.w, mask.h));
        return;
    }
    case X_RenderCreateCursor: {
        CHECK_REQUEST_SIZE_STRICT(xRenderCreateCursorReq);
        auto* r = GetRequestData<xRenderCreateCursorReq>(); newID(r->cid);
        auto* picture = picture_lookup(r->src);
        auto& image = *picture->image;
        if (picture->depth != 32 || r->x >= image.GetWidth() || r->y >= image.GetHeight()) throw BadMatchError();
        if (size_t(image.GetWidth()) * image.GetHeight() > 512u * 512u) throw BadAllocError();
        AddResource(std::make_shared<Cursor>(server.GetScreen(), r->cid, r->x, r->y, image)); return;
    }
    case X_RenderCreateAnimCursor: {
        CHECK_REQUEST_SIZE(xRenderCreateAnimCursorReq);
        auto* r = GetRequestData<xRenderCreateAnimCursorReq>(); newID(r->cid);
        if (request_size <= sizeof(*r) || (request_size - sizeof(*r)) % sizeof(xAnimCursorElt)) throw BadLengthError();
        const auto* frames = reinterpret_cast<const xAnimCursorElt*>(r + 1);
        const size_t count = (request_size - sizeof(*r)) / sizeof(*frames);
        for (size_t i = 0; i < count; ++i) if (!GetResourceAs<Cursor>(frames[i].cursor)) throw BadCursorError(frames[i].cursor);
        auto* first = GetResourceAs<Cursor>(frames[0].cursor);
        AddResource(std::make_shared<Cursor>(server.GetScreen(), r->cid, first->GetHotspotX(),
            first->GetHotspotY(), *first->GetDrawable(), false)); return; // Static first frame.
    }
    default: throw XRequestError(BadImplementation, 0);
    }
}
}
