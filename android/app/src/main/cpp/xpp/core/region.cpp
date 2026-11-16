#include "core/render_picture.h"

#include <algorithm>

namespace xorg {

RectList RegionNormalize(RectList in) {
    std::erase_if(in, [](const RenderRect& r) { return r.Empty(); });
    if (in.size() <= 1) return in;
    std::vector<s32> ys;
    ys.reserve(in.size() * 2);
    for (const auto& r : in) { ys.push_back(r.y1); ys.push_back(r.y2); }
    std::sort(ys.begin(), ys.end());
    ys.erase(std::unique(ys.begin(), ys.end()), ys.end());
    RectList out;
    std::vector<std::pair<s32, s32>> spans;
    for (size_t i = 0; i + 1 < ys.size(); ++i) {
        const s32 y0 = ys[i], y1 = ys[i + 1];
        spans.clear();
        for (const auto& r : in) if (r.y1 <= y0 && r.y2 >= y1) spans.emplace_back(r.x1, r.x2);
        if (spans.empty()) continue;
        std::sort(spans.begin(), spans.end());
        auto cur = spans[0];
        for (size_t k = 1; k < spans.size(); ++k) {
            if (spans[k].first <= cur.second) cur.second = std::max(cur.second, spans[k].second);
            else { out.push_back({cur.first, y0, cur.second, y1}); cur = spans[k]; }
        }
        out.push_back({cur.first, y0, cur.second, y1});
    }
    // Gabungkan band bertetangga dengan rentang x yang sama.
    RectList merged;
    for (const auto& r : out) {
        bool joined = false;
        for (auto& m : merged) {
            if (m.x1 == r.x1 && m.x2 == r.x2 && m.y2 == r.y1) { m.y2 = r.y2; joined = true; break; }
        }
        if (!joined) merged.push_back(r);
    }
    return merged;
}

RectList RegionUnion(const RectList& a, const RectList& b) {
    RectList all = a;
    all.insert(all.end(), b.begin(), b.end());
    return RegionNormalize(std::move(all));
}

RectList RegionIntersect(const RectList& a, const RectList& b) {
    RectList out;
    for (const auto& r : a) for (const auto& s : b) {
        RenderRect i{std::max(r.x1, s.x1), std::max(r.y1, s.y1), std::min(r.x2, s.x2), std::min(r.y2, s.y2)};
        if (!i.Empty()) out.push_back(i);
    }
    return RegionNormalize(std::move(out));
}

RectList RegionSubtract(const RectList& a, const RectList& b) {
    RectList cur = a;
    for (const auto& s : b) {
        RectList next;
        for (const auto& r : cur) {
            if (s.x2 <= r.x1 || s.x1 >= r.x2 || s.y2 <= r.y1 || s.y1 >= r.y2) { next.push_back(r); continue; }
            if (s.y1 > r.y1) next.push_back({r.x1, r.y1, r.x2, s.y1});
            if (s.y2 < r.y2) next.push_back({r.x1, s.y2, r.x2, r.y2});
            const s32 y1 = std::max(r.y1, s.y1), y2 = std::min(r.y2, s.y2);
            if (s.x1 > r.x1) next.push_back({r.x1, y1, s.x1, y2});
            if (s.x2 < r.x2) next.push_back({s.x2, y1, r.x2, y2});
        }
        cur = std::move(next);
    }
    return RegionNormalize(std::move(cur));
}

RectList RegionTranslate(RectList rects, s32 dx, s32 dy) {
    for (auto& r : rects) { r.x1 += dx; r.x2 += dx; r.y1 += dy; r.y2 += dy; }
    return rects;
}

RenderRect RegionExtents(const RectList& rects) {
    if (rects.empty()) return {};
    RenderRect e = rects[0];
    for (const auto& r : rects) {
        e.x1 = std::min(e.x1, r.x1); e.y1 = std::min(e.y1, r.y1);
        e.x2 = std::max(e.x2, r.x2); e.y2 = std::max(e.y2, r.y2);
    }
    return e;
}

}  // namespace xorg
