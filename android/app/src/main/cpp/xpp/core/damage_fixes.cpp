// Ekstensi DAMAGE 1.1, XFIXES 4.0 (region, clip picture, stub kursor) dan SHAPE 1.1 (disimpan saja, bentuk jendela tidak
// dipakai saat menggambar). Dipakai compositor xfwm4 bersama RENDER dan Composite.
#include <algorithm>

#include "core/damage.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/pixmap.h"
#include "core/render_picture.h"
#include "core/window.h"
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "native_screen.h"
#include <X11/extensions/renderproto.h>

namespace xorg {
namespace {

struct DamageObject;
std::vector<DamageObject*>& Registry() {
    static std::vector<DamageObject*> list;
    return list;
}

struct DamageObject final : XResource {
    DamageObject(u32 id, XClient* owner, u32 drawable, u8 level)
        : XResource(XResourceType::Damage, id), owner(owner), drawableId(drawable), level(level) {
        Registry().push_back(this);
    }
    ~DamageObject() override { std::erase(Registry(), this); }
    void OnOwnerDies(XClient*) override { owner = nullptr; }
    XClient* owner;
    u32 drawableId;
    u8 level;  // 0 NonEmpty, 1 BoundingBox, 2 RawRectangles, 3 DeltaRectangles
    RectList region;
};

struct Reply32 {
    u8 type{X_Reply}, data{};
    u16 sequence{};
    u32 length{};
    u8 body[24]{};
};
static_assert(sizeof(Reply32) == 32);

RenderRect WindowRect(const Window* w) { return {0, 0, s32(w->GetWidth()), s32(w->GetHeight())}; }

}  // namespace

void NotifyDamage(XServer& server, Drawable* drawable) {
    auto& registry = Registry();
    if (registry.empty()) return;
    const auto* extension = server.GetExtensionManager().FindExtension("DAMAGE");
    if (!extension) return;
    const std::vector<DamageObject*> copy = registry;
    for (auto* d : copy) {
        if (!d->owner) continue;
        auto* res = server.GetResource(d->drawableId);
        if (!res || !res->IsDrawable() || res->AsDrawable() != drawable) continue;
        const RenderRect full{0, 0, s32(drawable->GetWidth()), s32(drawable->GetHeight())};
        const RectList area{full};
        const bool wasEmpty = d->region.empty();
        const bool grew = !RegionSubtract(area, d->region).empty();
        d->region = RegionUnion(d->region, area);
        if (d->level == 0 ? !wasEmpty : !grew) continue;
        xEvent ev{};
        auto* b = reinterpret_cast<u8*>(&ev);
        b[0] = u8(extension->event_base);
        b[1] = d->level;
        const u32 drawableId = d->drawableId, damageId = d->id, ts = server.GetTimestamp();
        memcpy(b + 4, &drawableId, 4); memcpy(b + 8, &damageId, 4); memcpy(b + 12, &ts, 4);
        const s16 xy[2] = {0, 0}; const u16 wh[2] = {u16(full.x2), u16(full.y2)};
        memcpy(b + 16, xy, 4); memcpy(b + 20, wh, 4); memcpy(b + 24, xy, 4); memcpy(b + 28, wh, 4);
        d->owner->SendEvent(ev);
    }
}

void XClient::ProcDamage() {
    const auto* extension = server.GetExtensionManager().FindExtension("DAMAGE");
    const auto* base = GetRequestData<xReq>();
    const u32* a = reinterpret_cast<const u32*>(base + 1);
    auto damage = [&](u32 id) -> DamageObject* {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::Damage) throw XRequestError(extension->error_base, id);
        return static_cast<DamageObject*>(r);
    };
    auto region = [&](u32 id) -> XFixesRegion* {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::Region)
            throw XRequestError(server.GetExtensionManager().FindExtension("XFIXES")->error_base, id);
        return r->As<XFixesRegion>();
    };
    switch (base->data) {
    case 0: {  // QueryVersion
        if (request_size < 12) throw BadLengthError();
        Reply32 reply; reply.sequence = sequence;
        const u32 ver[2] = {1, 1}; memcpy(reply.body, ver, 8);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 1: {  // Create
        if (request_size < 16) throw BadLengthError();
        CheckResourceID(a[0]);
        if (GetResource(a[0])) throw BadIdChoiceError(a[0]);
        auto* d = GetResource(a[1]);
        if (!d || !d->IsDrawable()) throw BadDrawableError(a[1]);
        const u8 level = reinterpret_cast<const u8*>(a)[8];
        if (level > 3) throw BadValueError(level);
        AddResource(std::make_shared<DamageObject>(a[0], this, a[1], level)); return;
    }
    case 2: {  // Destroy
        if (request_size < 8) throw BadLengthError();
        damage(a[0]); RemoveResource(a[0]); return;
    }
    case 3: {  // Subtract(damage, repair, parts)
        if (request_size < 16) throw BadLengthError();
        auto* d = damage(a[0]);
        const RectList old = d->region;
        if (a[2]) region(a[2])->rects = a[1] ? RegionIntersect(old, region(a[1])->rects) : old;
        d->region = a[1] ? RegionSubtract(old, region(a[1])->rects) : RectList{};
        return;
    }
    case 4: {  // Add(drawable, region)
        if (request_size < 12) throw BadLengthError();
        auto* d = GetResource(a[0]);
        if (!d || !d->IsDrawable()) throw BadDrawableError(a[0]);
        region(a[1]);
        NotifyDamage(server, d->AsDrawable()); return;
    }
    default: throw XRequestError(BadRequest, 0);
    }
}

void XClient::ProcXFixes() {
    const auto* extension = server.GetExtensionManager().FindExtension("XFIXES");
    const auto* base = GetRequestData<xReq>();
    const u32* a = reinterpret_cast<const u32*>(base + 1);
    const size_t words = request_size / 4 - 1;  // jumlah u32 setelah header
    auto region = [&](u32 id) -> XFixesRegion* {
        auto* r = GetResource(id);
        if (!r || r->GetType() != XResourceType::Region) throw XRequestError(extension->error_base, id);
        return r->As<XFixesRegion>();
    };
    auto newRegion = [&](u32 id, RectList rects) {
        CheckResourceID(id);
        if (GetResource(id)) throw BadIdChoiceError(id);
        AddResource(std::make_shared<XFixesRegion>(id, RegionNormalize(std::move(rects))));
    };
    auto rectsFrom = [&](size_t firstWord) {
        RectList list;
        const auto* r = reinterpret_cast<const xRectangle*>(a + firstWord);
        const size_t n = (words > firstWord ? words - firstWord : 0) / 2;
        for (size_t i = 0; i < n; ++i) list.push_back({r[i].x, r[i].y, r[i].x + r[i].width, r[i].y + r[i].height});
        return list;
    };
    auto setRegion = [&](u32 id, RectList rects) { region(id)->rects = RegionNormalize(std::move(rects)); };
    switch (base->data) {
    case 0: {  // QueryVersion
        Reply32 reply; reply.sequence = sequence;
        const u32 ver[2] = {4, 0}; memcpy(reply.body, ver, 8);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 1: case 2: case 3: return;  // ChangeSaveSet, SelectSelectionInput, SelectCursorInput
    case 4: {  // GetCursorImage: kursor transparan 1x1 (kursor digambar oleh server)
        struct { u8 type{X_Reply}, pad{}; u16 sequence{}; u32 length{1}; s16 x{}, y{}; u16 w{1}, h{1}, xhot{}, yhot{};
                 u32 serial{}; u8 pad2[8]{}; u32 pixel{}; } reply;
        reply.sequence = sequence;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 5: newRegion(a[0], rectsFrom(1)); return;
    case 6: {  // FromBitmap
        auto* pm = GetResourceAs<Pixmap>(a[1]);
        if (!pm) throw BadPixmapError(a[1]);
        std::vector<u32> bits(size_t(pm->width) * pm->height);
        pm->GetDrawable()->ReadPixels(reinterpret_cast<u8*>(bits.data()), 0, 0, pm->width, pm->height, pm->width);
        RectList list;
        for (s32 y = 0; y < pm->height; ++y) for (s32 x = 0; x < pm->width;) {
            if (!(bits[size_t(y) * pm->width + x] & 1)) { ++x; continue; }
            const s32 start = x;
            while (x < pm->width && (bits[size_t(y) * pm->width + x] & 1)) ++x;
            list.push_back({start, y, x, y + 1});
        }
        newRegion(a[0], std::move(list)); return;
    }
    case 7: {  // FromWindow
        auto* w = GetResourceAs<Window>(a[1]);
        if (!w) throw BadWindowError(a[1]);
        newRegion(a[0], {WindowRect(w)}); return;
    }
    case 8: newRegion(a[0], {}); return;  // FromGC
    case 9: {  // FromPicture
        auto* r = GetResource(a[1]);
        if (!r || r->GetType() != XResourceType::RenderPicture) throw XRequestError(server.GetExtensionManager().FindExtension("RENDER")->error_base + BadPicture, a[1]);
        auto* pic = r->As<RenderPicture>();
        newRegion(a[0], pic->hasClip ? pic->clip : RectList{}); return;
    }
    case 10: region(a[0]); RemoveResource(a[0]); return;
    case 11: setRegion(a[0], rectsFrom(1)); return;
    case 12: region(a[1])->rects = region(a[0])->rects; return;
    case 13: { auto r = RegionUnion(region(a[0])->rects, region(a[1])->rects); region(a[2])->rects = std::move(r); return; }
    case 14: { auto r = RegionIntersect(region(a[0])->rects, region(a[1])->rects); region(a[2])->rects = std::move(r); return; }
    case 15: { auto r = RegionSubtract(region(a[0])->rects, region(a[1])->rects); region(a[2])->rects = std::move(r); return; }
    case 16: {  // Invert(source, bounds, destination)
        const auto* b = reinterpret_cast<const xRectangle*>(a + 1);
        const RectList bounds{{b->x, b->y, b->x + b->width, b->y + b->height}};
        auto r = RegionSubtract(bounds, region(a[0])->rects); region(a[3])->rects = std::move(r); return;
    }
    case 17: {  // Translate
        const s16 dx = s16(a[1] & 0xffff), dy = s16(a[1] >> 16);
        auto* r = region(a[0]); r->rects = RegionTranslate(r->rects, dx, dy); return;
    }
    case 18: {  // Extents
        const RenderRect e = RegionExtents(region(a[0])->rects);
        region(a[1])->rects = e.Empty() ? RectList{} : RectList{e}; return;
    }
    case 19: {  // Fetch
        auto* r = region(a[0]);
        const RenderRect e = RegionExtents(r->rects);
        struct { u8 type{X_Reply}, pad{}; u16 sequence{}; u32 length{}; s16 x{}, y{}; u16 w{}, h{}; u8 pad2[16]{}; } head;
        head.sequence = sequence; head.length = u32(r->rects.size() * 2);
        head.x = s16(e.x1); head.y = s16(e.y1); head.w = u16(e.x2 - e.x1); head.h = u16(e.y2 - e.y1);
        std::vector<xRectangle> out;
        for (const auto& q : r->rects) out.push_back({s16(q.x1), s16(q.y1), u16(q.x2 - q.x1), u16(q.y2 - q.y1)});
        WriteImmediately(&head, sizeof(head));
        if (!out.empty()) WriteImmediately(out.data(), out.size() * sizeof(xRectangle));
        return;
    }
    case 20: return;  // SetGCClipRegion
    case 21: region(a[4]); return;  // SetWindowShapeRegion: bentuk tidak dipakai
    case 22: {  // SetPictureClipRegion(picture, region, xorigin, yorigin)
        auto* res = GetResource(a[0]);
        if (!res || res->GetType() != XResourceType::RenderPicture) throw XRequestError(server.GetExtensionManager().FindExtension("RENDER")->error_base + BadPicture, a[0]);
        auto* pic = res->As<RenderPicture>();
        if (a[1] == 0) { pic->hasClip = false; pic->clip.clear(); return; }
        const s16 ox = s16(a[2] & 0xffff), oy = s16(a[2] >> 16);
        pic->clip = RegionTranslate(region(a[1])->rects, ox, oy); pic->hasClip = true; return;
    }
    case 23: return;  // SetCursorName
    case 24: {        // GetCursorName: tanpa nama
        Reply32 reply; reply.sequence = sequence;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 26: case 27: return;  // ChangeCursor, ChangeCursorByName
    case 28: {  // ExpandRegion(source, destination, left, right, top, bottom)
        const u16 l = u16(a[2] & 0xffff), r = u16(a[2] >> 16), t = u16(a[3] & 0xffff), b = u16(a[3] >> 16);
        RectList out = region(a[0])->rects;
        for (auto& q : out) { q.x1 -= l; q.x2 += r; q.y1 -= t; q.y2 += b; }
        region(a[1])->rects = RegionNormalize(std::move(out)); return;
    }
    case 29: case 30: return;  // HideCursor, ShowCursor
    default: throw XRequestError(BadRequest, 0);
    }
}

void XClient::ProcShape() {
    const auto* base = GetRequestData<xReq>();
    const u32* a = reinterpret_cast<const u32*>(base + 1);
    auto window = [&](u32 id) {
        auto* w = GetResourceAs<Window>(id);
        if (!w) throw BadWindowError(id);
        return w;
    };
    switch (base->data) {
    case 0: {  // QueryVersion
        Reply32 reply; reply.sequence = sequence;
        const u16 ver[2] = {1, 1}; memcpy(reply.body, ver, 4);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 1: case 2: case 3: case 4: case 6: return;  // Rectangles, Mask, Combine, Offset, SelectInput
    case 5: {  // QueryExtents: jendela dianggap persegi penuh
        auto* w = window(a[0]);
        struct { u8 type{X_Reply}, pad{}; u16 sequence{}; u32 length{}; u8 bounding{}, clip{}; u16 pad2{};
                 s16 bx{}, by{}; u16 bw{}, bh{}; s16 cx{}, cy{}; u16 cw{}, ch{}; u32 pad3{}; } reply;
        reply.sequence = sequence; reply.bw = reply.cw = u16(w->GetWidth()); reply.bh = reply.ch = u16(w->GetHeight());
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 7: {  // InputSelected
        window(a[0]);
        Reply32 reply; reply.sequence = sequence; reply.data = 0;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case 8: {  // GetRectangles: satu persegi penuh
        auto* w = window(a[0]);
        struct { u8 type{X_Reply}, ordering{}; u16 sequence{}; u32 length{2}; u32 count{1}; u8 pad[20]{}; xRectangle rect{}; } reply;
        reply.sequence = sequence; reply.rect = {0, 0, u16(w->GetWidth()), u16(w->GetHeight())};
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    default: throw XRequestError(BadRequest, 0);
    }
}

}  // namespace xorg
