#include "core/render_ops.h"

#include <algorithm>
#include <cmath>

#include "core/drawable.h"

namespace xorg {
namespace {

inline u32 Mul8(u32 a, u32 b) {
    const u32 t = a * b + 128;
    return (t + (t >> 8)) >> 8;
}
inline u32 Scale(u32 p, u32 s) {
    return (Mul8(p >> 24, s) << 24) | (Mul8((p >> 16) & 255, s) << 16) | (Mul8((p >> 8) & 255, s) << 8) | Mul8(p & 255, s);
}
inline u32 AddSat(u32 a, u32 b) {
    u32 r = 0;
    for (int shift = 0; shift < 32; shift += 8) {
        const u32 v = ((a >> shift) & 255) + ((b >> shift) & 255);
        r |= (v > 255 ? 255u : v) << shift;
    }
    return r;
}

// Operator RENDER (PictOp*). Disjoint/Conjoint dan Saturate didekati dengan Over.
u32 Operate(int op, u32 s, u32 d) {
    const u32 sa = s >> 24, da = d >> 24;
    switch (op) {
        case 0: return 0;                                                   // Clear
        case 1: return s;                                                   // Src
        case 2: return d;                                                   // Dst
        case 3: return AddSat(s, Scale(d, 255 - sa));                       // Over
        case 4: return AddSat(d, Scale(s, 255 - da));                       // OverReverse
        case 5: return Scale(s, da);                                        // In
        case 6: return Scale(d, sa);                                        // InReverse
        case 7: return Scale(s, 255 - da);                                  // Out
        case 8: return Scale(d, 255 - sa);                                  // OutReverse
        case 9: return AddSat(Scale(s, da), Scale(d, 255 - sa));            // Atop
        case 10: return AddSat(Scale(d, sa), Scale(s, 255 - da));           // AtopReverse
        case 11: return AddSat(Scale(s, 255 - da), Scale(d, 255 - sa));     // Xor
        case 12: return AddSat(s, d);                                       // Add
        default: return AddSat(s, Scale(d, 255 - sa));
    }
}

bool Wrap(s32& v, s32 n, int repeat) {
    if (v >= 0 && v < n) return true;
    switch (repeat) {
        case 1: v = ((v % n) + n) % n; return true;
        case 2: v = v < 0 ? 0 : n - 1; return true;
        case 3: {
            const s32 period = 2 * n;
            v = ((v % period) + period) % period;
            if (v >= n) v = period - 1 - v;
            return true;
        }
        default: return false;
    }
}

u32 Fetch(const RenderSource& s, s32 x, s32 y) {
    if (s.solid) return s.color;
    if (!Wrap(x, s.surface.w, s.repeat) || !Wrap(y, s.surface.h, s.repeat)) return 0;
    return RenderCanon(s.surface.depth, s.surface.px[size_t(y) * s.surface.stride + x]);
}

u32 Lerp4(u32 a, u32 b, u32 c, u32 d, double fx, double fy) {
    u32 out = 0;
    for (int shift = 0; shift < 32; shift += 8) {
        const double top = ((a >> shift) & 255) * (1 - fx) + ((b >> shift) & 255) * fx;
        const double bottom = ((c >> shift) & 255) * (1 - fx) + ((d >> shift) & 255) * fx;
        const double v = top * (1 - fy) + bottom * fy + 0.5;
        out |= u32(std::clamp(v, 0.0, 255.0)) << shift;
    }
    return out;
}

u32 PackStop(float a, float r, float g, float b) {
    const auto q = [](float v) { return u32(std::clamp(v, 0.f, 1.f) * 255.f + 0.5f); };
    const u32 qa = q(a);
    return (qa << 24) | (std::min(q(r), qa) << 16) | (std::min(q(g), qa) << 8) | std::min(q(b), qa);
}

// Warna gradien pada parameter t. repeat: 0 none (transparan di luar stop), 1 normal, 2 pad, 3 reflect.
u32 GradientColor(const RenderGradient& g, double t, int repeat) {
    const auto& st = g.stops;
    if (st.empty() || !std::isfinite(t)) return 0;
    const double first = st.front().pos, last = st.back().pos;
    switch (repeat) {
        case 1: t -= std::floor(t); break;
        case 3: t = std::fmod(t, 2.0); if (t < 0) t += 2.0; if (t > 1.0) t = 2.0 - t; break;
        case 2: break;  // pad: nilai di luar stop memakai stop terdekat (di bawah)
        default: if (t < first || t > last) return 0; break;
    }
    if (t <= first) return PackStop(st.front().a, st.front().r, st.front().g, st.front().b);
    if (t >= last) return PackStop(st.back().a, st.back().r, st.back().g, st.back().b);
    size_t i = 1;
    while (i < st.size() && st[i].pos < t) ++i;
    const auto& lo = st[i - 1];
    const auto& hi = st[i];
    const double span = hi.pos - lo.pos;
    const float f = span > 0 ? float((t - lo.pos) / span) : 1.f;
    const auto mix = [f](float a, float b) { return a + (b - a) * f; };
    return PackStop(mix(lo.a, hi.a), mix(lo.r, hi.r), mix(lo.g, hi.g), mix(lo.b, hi.b));
}

// Nilai gradien di titik sumber (px, py).
u32 GradientAt(const RenderGradient& g, double px, double py, int repeat) {
    switch (g.kind) {
        case RenderGradient::Kind::Linear: {
            const double dx = g.x2 - g.x1, dy = g.y2 - g.y1, len2 = dx * dx + dy * dy;
            if (len2 <= 0) return 0;
            return GradientColor(g, ((px - g.x1) * dx + (py - g.y1) * dy) / len2, repeat);
        }
        case RenderGradient::Kind::Radial: {
            // Dua lingkaran: cari s terbesar dengan |p - c(s)| = r(s) dan r(s) >= 0.
            const double cdx = g.x2 - g.x1, cdy = g.y2 - g.y1, dr = g.r2 - g.r1;
            const double pdx = px - g.x1, pdy = py - g.y1;
            const double a = cdx * cdx + cdy * cdy - dr * dr;
            const double b = pdx * cdx + pdy * cdy + g.r1 * dr;
            const double c = pdx * pdx + pdy * pdy - g.r1 * g.r1;
            double s = 0;
            if (std::fabs(a) < 1e-12) {
                if (std::fabs(b) < 1e-12) return 0;
                s = c / (2 * b);
                if (g.r1 + s * dr < 0) return 0;
            } else {
                const double disc = b * b - a * c;
                if (disc < 0) return 0;
                const double root = std::sqrt(disc);
                double s1 = (b + root) / a, s2 = (b - root) / a;
                if (s1 < s2) std::swap(s1, s2);
                if (g.r1 + s1 * dr >= 0) s = s1;
                else if (g.r1 + s2 * dr >= 0) s = s2;
                else return 0;
            }
            return GradientColor(g, s, repeat);
        }
        case RenderGradient::Kind::Conical: {
            constexpr double kTwoPi = 6.283185307179586;
            double t = (std::atan2(py - g.y1, px - g.x1) - g.angle * kTwoPi / 360.0) / kTwoPi;
            t -= std::floor(t);
            return GradientColor(g, t, 1);
        }
    }
    return 0;
}

u32 Sample(const RenderSource& s, s32 x, s32 y) {
    if (s.solid) return s.color;
    if (s.gradient) {
        double px = x + s.ox + 0.5, py = y + s.oy + 0.5;
        if (s.hasTransform) {
            const double tx = (s.m[0] * px + s.m[1] * py) / 65536.0 + s.m[2] / 65536.0;
            const double ty = (s.m[3] * px + s.m[4] * py) / 65536.0 + s.m[5] / 65536.0;
            const double tw = (s.m[6] * px + s.m[7] * py) / 65536.0 + s.m[8] / 65536.0;
            if (std::fabs(tw) < 1e-9) return 0;
            px = tx / tw; py = ty / tw;
        }
        return GradientAt(*s.gradient, px, py, s.repeat);
    }
    if (!s.hasTransform) return Fetch(s, x + s.ox, y + s.oy);
    const double px = x + s.ox + 0.5, py = y + s.oy + 0.5;
    const double tx = (s.m[0] * px + s.m[1] * py) / 65536.0 + s.m[2] / 65536.0;
    const double ty = (s.m[3] * px + s.m[4] * py) / 65536.0 + s.m[5] / 65536.0;
    const double tw = (s.m[6] * px + s.m[7] * py) / 65536.0 + s.m[8] / 65536.0;
    if (std::fabs(tw) < 1e-9) return 0;
    const double sx = tx / tw, sy = ty / tw;
    if (!s.bilinear) return Fetch(s, s32(std::floor(sx)), s32(std::floor(sy)));
    const double fx = sx - 0.5, fy = sy - 0.5;
    const s32 ix = s32(std::floor(fx)), iy = s32(std::floor(fy));
    const double wx = fx - ix, wy = fy - iy;
    return Lerp4(Fetch(s, ix, iy), Fetch(s, ix + 1, iy), Fetch(s, ix, iy + 1), Fetch(s, ix + 1, iy + 1), wx, wy);
}

}  // namespace

RenderResult RenderComposite(int op, const RenderSource& src, const RenderSource* mask, const RenderTarget& dst,
                             s32 dx, s32 dy, s32 w, s32 h) {
    RenderResult result;
    Drawable* dd = dst.drawable;
    if (!dd || w <= 0 || h <= 0) return result;
    s32 x0 = std::max(dx, 0), y0 = std::max(dy, 0);
    s32 x1 = std::min<s32>(dx + w, dd->GetWidth()), y1 = std::min<s32>(dy + h, dd->GetHeight());
    if (dst.hasClip && dst.clip) {
        const RenderRect e = RegionExtents(*dst.clip);
        x0 = std::max(x0, e.x1); y0 = std::max(y0, e.y1); x1 = std::min(x1, e.x2); y1 = std::min(y1, e.y2);
    }
    if (x1 <= x0 || y1 <= y0) return result;
    const s32 bw = x1 - x0, bh = y1 - y0;
    result.x = x0; result.y = y0; result.w = bw; result.h = bh;
    result.pixels.resize(size_t(bw) * bh);
    dd->ReadPixels(reinterpret_cast<u8*>(result.pixels.data()), s16(x0), s16(y0), u16(bw), u16(bh), u16(bw));

    std::vector<std::pair<s32, s32>> spans;
    for (s32 y = y0; y < y1; ++y) {
        spans.clear();
        if (dst.hasClip && dst.clip) {
            for (const auto& r : *dst.clip)
                if (r.y1 <= y && y < r.y2 && std::max(r.x1, x0) < std::min(r.x2, x1))
                    spans.emplace_back(std::max(r.x1, x0), std::min(r.x2, x1));
        } else {
            spans.emplace_back(x0, x1);
        }
        u32* row = result.pixels.data() + size_t(y - y0) * bw;
        for (const auto& span : spans) {
            for (s32 x = span.first; x < span.second; ++x) {
                u32& out = row[x - x0];
                const u32 d = RenderCanon(dst.depth, out);
                const u32 s = Sample(src, x, y);
                u32 r;
                if (!mask) {
                    r = Operate(op, s, d);
                } else {
                    const u32 m = Sample(*mask, x, y);
                    if (mask->componentAlpha && (op == 3 || op == 1)) {
                        const u32 sa = s >> 24, da = d >> 24;
                        const u32 mr = (m >> 16) & 255, mg = (m >> 8) & 255, mb = m & 255, ma = m >> 24;
                        const u32 sr = Mul8((s >> 16) & 255, mr), sg = Mul8((s >> 8) & 255, mg), sb = Mul8(s & 255, mb);
                        const u32 sa2 = Mul8(sa, ma);
                        if (op == 1) {
                            r = (sa2 << 24) | (sr << 16) | (sg << 8) | sb;
                        } else {
                            const u32 ar = Mul8(sa, mr), ag = Mul8(sa, mg), ab = Mul8(sa, mb);
                            const u32 rr = std::min(255u, sr + Mul8((d >> 16) & 255, 255 - ar));
                            const u32 rg = std::min(255u, sg + Mul8((d >> 8) & 255, 255 - ag));
                            const u32 rb = std::min(255u, sb + Mul8(d & 255, 255 - ab));
                            const u32 ra = std::min(255u, sa2 + Mul8(da, 255 - sa2));
                            r = (ra << 24) | (rr << 16) | (rg << 8) | rb;
                        }
                    } else {
                        r = Operate(op, Scale(s, m >> 24), d);
                    }
                }
                out = RenderStore(dst.depth, r);
            }
        }
    }
    return result;
}

}  // namespace xorg
