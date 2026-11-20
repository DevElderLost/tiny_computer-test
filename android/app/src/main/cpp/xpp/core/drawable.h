#pragma once

#include <android/hardware_buffer.h>

#include "base/2d.h"
#include "core/xresource.h"

namespace xorg {

class Visual;
class GraphicsContext;

// Operasi gambar core X11 (FillRectangle, PutImage, CopyArea dari depth 24) tidak mengenal alpha: kliennya (mis. xfwm4)
// menulis piksel 0x00RRGGBB ke window ARGB depth 32. X sungguhan menampilkannya opaque bila tidak ada compositor; di sini
// piksel itu akan menjadi tembus pandang saat window dicampur alpha. Piksel premultiplied yang sah tidak pernah berupa
// alpha 0 dengan RGB bukan nol, jadi bentuk itu dianggap opaque. Alpha asli (RENDER/cairo) dan 0x00000000 tetap utuh,
// sehingga transparansi sungguhan dipertahankan.
inline u32 CoreOpaqueIfNoAlpha(u32 p) { return ((p >> 24) == 0 && (p & 0x00ffffffu)) ? (p | 0xff000000u) : p; }

class Drawable {
public:
    Drawable(u16 width, u16 height, u8 depth, AHardwareBuffer* buffer);

    virtual ~Drawable();

    u16 GetWidth() const { return width; }

    u16 GetHeight() const { return height; }

    u8 GetDepth() const { return depth; }

    virtual sp<Drawable> Clone() = 0;

    virtual void ReadPixels(u8* dst_data, s16 x, s16 y, u16 width, u16 height, u16 dst_stride) = 0;

    virtual void WritePixels(const u8* src_data,
                             s16 src_x,
                             s16 src_y,
                             s16 dst_x,
                             s16 dst_y,
                             u16 width,
                             u16 height,
                             u16 stride) = 0;

    virtual void WriteBinaryPixels(
            const u8* pixels, u16 width, u16 height, u32 fg_color, u32 bg_color) = 0;

    virtual void FillRect(s16 x, s16 y, u16 width, u16 height, u32 color) = 0;

    virtual void DrawLines(const Point* points, u32 count, u32 color, u32 line_width) = 0;

    virtual void DrawPolySegments(const PolySegment* segments,
                                  u32 count,
                                  u32 color,
                                  u32 line_width) = 0;

    virtual void* Map() = 0;

    virtual u32 GetStride() = 0;

protected:
    u16 width{};
    u16 height{};
    u8 depth{};
    AHardwareBuffer* buffer{};
};

}  // namespace xorg