#include "core/graphics_context.h"

#include <bit>
#include <X11/X.h>

#include "base/log.h"

namespace xorg {

GraphicsContext::GraphicsContext(u32 id) : XResource(XResourceType::GC, id) {
    SetAttribute(GCFunction, GXcopy);
    SetAttribute(GCPlaneMask, 0xffffffff);
    SetAttribute(GCForeground, 0);
    SetAttribute(GCBackground, 1);
    SetAttribute(GCLineWidth, 0);
    SetAttribute(GCLineStyle, LineSolid);
    SetAttribute(GCCapStyle, CapButt);
    SetAttribute(GCJoinStyle, JoinMiter);
    SetAttribute(GCFillStyle, FillSolid);
    SetAttribute(GCFillRule, EvenOddRule);
    SetAttribute(GCSubwindowMode, ClipByChildren);
    SetAttribute(GCGraphicsExposures, 1);
    SetAttribute(GCDashList, 4);
    SetAttribute(GCArcMode, ArcPieSlice);
}

GraphicsContext::~GraphicsContext() {}

void GraphicsContext::SetAttribute(u32 mask, u32 value) {
    const u32 index = std::countr_zero(mask);
    ASSERT(index < attributes.size());
    attributes[index] = value;
}

u32 GraphicsContext::GetAttribute(u32 mask) const {
    const u32 index = std::countr_zero(mask);
    ASSERT(index < attributes.size());
    return attributes[index];
}

void GraphicsContext::UpdateValues(u32 mask, const u32* values) {
    for (; mask; mask &= mask - 1) {
        const u32 index = std::countr_zero(mask);
        attributes[index] = *values++;
        // GCClipMask = None (XSetClipMask(None)) menghapus klip persegi; mask pixmap tidak didukung dan diperlakukan tanpa klip.
        if ((1u << index) == GCClipMask) {
            clip_rectangles.clear();
            has_clip = false;
        }
    }
}

std::vector<Rect> GraphicsContext::ApplyClip(const Rect& r) const {
    if (!has_clip) return {r};
    std::vector<Rect> out;
    for (const Rect& c : clip_rectangles) {
        const Rect i{std::max(r.left, c.left), std::max(r.top, c.top), std::min(r.right, c.right), std::min(r.bottom, c.bottom)};
        if (i.left < i.right && i.top < i.bottom) out.push_back(i);
    }
    return out;
}

void GraphicsContext::SetClipRectangles(s16 x, s16 y, const xRectangle* rectangle, size_t count) {
    clip_rectangles.clear();
    has_clip = true;
    for (size_t i = 0; i < count; i++) {
        clip_rectangles.push_back(Rect{
                .left = rectangle[i].x + x,
                .top = rectangle[i].y + y,
                .right = rectangle[i].x + x + rectangle[i].width,
                .bottom = rectangle[i].y + y + rectangle[i].height,
        });
    }
}

}  // namespace xorg
