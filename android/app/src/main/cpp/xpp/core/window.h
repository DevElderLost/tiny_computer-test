#pragma once

#include <list>
#include <atomic>
#include <optional>
#include <X11/X.h>
#include <X11/Xproto.h>
#include "base/2d.h"
#include "core/property.h"
#include "core/render_picture.h"
#include "core/xresource.h"

#define VisibilityNotViewable 3

namespace xorg {

class XClient;
class Drawable;
class Cursor;
class Window;
class WindowManager;

struct WindowAttributes {
    u32 win_gravity{NorthWestGravity};
    u32 backing_store{NotUseful};
    bool mapped{};
    bool save_under{};
    bool override_redirect{};
    u32 back_pixel{};
    // CWBackPixmap: pixmap latar yang di-tile dari pojok kiri atas window, atau ParentRelative (latar milik leluhur terdekat).
    // Server ini memegang isi tiap window di drawable-nya sendiri, jadi latar dicat langsung (lihat Window::PaintBackground).
    sp<Drawable> back_tile;
    bool back_parent_relative{};
    u32 backing_pixel{};
    u32 backing_planes{1};
    u32 bit_gravity{CenterGravity};
    u32 event_mask{};
    u16 dont_propagate_mask{};
    sp<Cursor> cursor;

    bool Update(Window* window, u32 mask, const u32* values, XClient* client);
};

class Window final : public XResource {
public:
    inline static std::atomic<u64> nextGeneration{1};
    const u64 generation{nextGeneration.fetch_add(1, std::memory_order_relaxed)};
    Window(WindowManager& window_manager,
           u32 id,
           Window* parent,
           bool is_input_only,
           s32 x,
           s32 y,
           u16 width,
           u16 height,
           u8 depth,
           s32 border_width,
           XClient* client);

    ~Window() override;

    Cursor* GetCursor() const {
        if (attributes.cursor) {
            return attributes.cursor.get();
        }
        return parent ? parent->GetCursor() : nullptr;
    }

    XClient* GetClient() const { return owner_client; }

    bool IsRoot() const { return parent == nullptr; }

    bool IsInputOnly() const { return is_input_only; }

    bool IsMapped() const { return attributes.mapped; }
    bool IsCompositeRedirected() const { return composite_redirected; }

    Drawable* GetDrawable() const { return drawable.get(); }
    const sp<Drawable>& SharedDrawable() const { return drawable; }

    // Bentuk SHAPE (koordinat window). nullopt = bentuk bawaan (persegi penuh).
    const std::optional<RectList>& ShapeBounding() const { return shape_bounding; }
    const std::optional<RectList>& ShapeClip() const { return shape_clip; }
    const std::optional<RectList>& ShapeInput() const { return shape_input; }
    u32 ShapeGeneration() const { return shape_generation; }
    // kind: 0 Bounding, 1 Clip, 2 Input (konstanta ShapeBounding/ShapeClip/ShapeInput).
    void SetShape(u8 kind, std::optional<RectList> rects);

    // Mengecat latar window (warna, tile pixmap, atau ParentRelative) pada persegi (x, y, w, h) koordinat window.
    // Dipakai oleh CWBackPixel/CWBackPixmap, ClearArea, dan setelah drawable dibuat ulang karena resize.
    void PaintBackground(s32 x, s32 y, s32 w, s32 h);
    // Mengecat ulang seluruh anak (rekursif) yang latarnya ParentRelative setelah latar window ini berubah.
    void RepaintRelativeChildren();

    const std::list<Window*>& GetChildren() const { return children; }

    u16 GetBorderWidth() const { return border_width; }

    const Rect& GetOutRect() const { return out_rect; }

    const Rect& GetInRect() const { return in_rect; }

    Window* GetParent() const { return parent; }

    u32 GetWidth() const { return width; }

    u32 GetHeight() const { return height; }

    u8 GetDepth() const { return depth; }

    WindowAttributes& GetAttributes() { return attributes; }

    bool IsAllAncestorsMapped() const;

    bool IsAncestorOf(const Window* w) const;

    bool IsInferiorOf(const Window* w) const;

    Property* GetProperty(u32 atom) const;

    // Daftar atom properti window ini (untuk ListProperties).
    std::vector<u32> GetPropertyAtoms() const;

    Property* AddProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data);

    bool ModifyProperty(u32 atom, u32 type, u8 format, u8 mode, const std::vector<u8>& data);

    void DeleteProperty(u32 atom);

    template <typename Callback> void ForeachEventListeners(u32 event_id, Callback&& callback) {
        constexpr bool BOOL_BREAK = std::is_same_v<bool, std::invoke_result_t<Callback, XClient*>>;
        for (const auto& it : client_masks) {
            if (it.second & event_id) {
                if constexpr (BOOL_BREAK) {
                    if (callback(it.first)) {
                        break;
                    }
                } else {
                    callback(it.first);
                }
            }
        }
    }

    bool HasEventListenerFor(u32 event_id) const {
        for (const auto& it : client_masks) {
            if (it.second & event_id) {
                return true;
            }
        }
        return false;
    }

    u32 GetAllEventMasks() const {
        u32 mask = 0;
        for (const auto& it : client_masks) {
            mask |= it.second;
        }
        return mask;
    }
    u32 GetEventMask(XClient* client) const {
        auto i = client_masks.find(client);
        return i == client_masks.end() ? 0 : i->second;
    }

    void PutClientMask(XClient* client, u32 mask) { client_masks[client] = mask; }

    void RemoveEventListener(XClient* client) { client_masks.erase(client); }

    // XInput2: topeng event perangkat XI2 per klien pada window ini (bit = 1 << evtype: 2 KeyPress ... 10 FocusOut).
    // SDL3 (PPSSPP, dll.) memilih input mouse, keyboard, enter/leave dan fokus HANYA lewat XI2 dan tidak memasang
    // topeng inti, jadi tanpa ini jendelanya tidak pernah menerima klik, tombol, atau fokus.
    void PutXIMask(XClient* client, u32 mask) {
        if (mask) xi_masks[client] = mask;
        else xi_masks.erase(client);
    }
    void RemoveXIListener(XClient* client) { xi_masks.erase(client); }
    u32 GetXIMask(XClient* client) const {
        auto i = xi_masks.find(client);
        return i == xi_masks.end() ? 0 : i->second;
    }
    bool HasXIListenerFor(u32 bits) const {
        for (const auto& it : xi_masks) {
            if (it.second & bits) return true;
        }
        return false;
    }
    template <typename Callback> void ForeachXIListeners(u32 bits, Callback&& callback) {
        for (const auto& it : xi_masks) {
            if (it.second & bits) callback(it.first);
        }
    }
    // Kirim event perangkat XI2 (Key/Button/Motion) ke klien yang memilihnya pada window ini atau leluhur terdekat.
    // Mengembalikan klien penerima supaya event inti yang sama tidak dikirim dobel (Xorg: XI2 menggantikan event inti).
    std::vector<XClient*> XIDeviceNotify(u16 evtype, s32 x, s32 y, u32 detail, u32 state);

    void Move(s32 dx, s32 dy, s32 dw, s32 dh);

    void Reparent(XClient* caller_client, Window* new_parent, s32 x, s32 y);

    void MakeExposure();

    bool Occuldes(Window* w1, Window* w2) const;

    void UpdateVisibility();

    void UpdateAffectedVisibility();

    void FocusOutNotify(u32 detail, u32 mode);

    void FocusInNotify(u32 detail, u32 mode);

    // only != nullptr: event crossing hanya dikirim ke klien itu (dipakai saat pointer di-grab dengan owner_events).
    void LeaveEnterNotify(s32 x, s32 y, Window* enter_window, u32 mode, XClient* only = nullptr);

    void PointerNotify(u32 type, s32 x, s32 y, u32 detail, Window* window, u32 mode, XClient* only = nullptr);

    void PointerMotionNotify(s32 x, s32 y, u32 button_mask, XClient* grab_client);

    Window* ButtonNotify(bool down, s32 x, s32 y, u32 button, XClient* grab_client, XClient** recipient = nullptr);

    bool KeyNotify(bool down, s32 x, s32 y, u32 keycode, XClient* grab_client);

    void GrabButtonNotify(bool down,
                          s32 x,
                          s32 y,
                          u32 button,
                          XClient* grab_client,
                          u32 event_mask,
                          bool owner_events);

    void GrabKeyNotify(bool down, s32 x, s32 y, u32 keycode, XClient* grab_client, bool owner_events);

    void GrabPointerMoveNotify(
            s32 x, s32 y, u32 button_mask, XClient* grab_client, u32 event_mask, bool owner_events);

    Window* WindowAtPoint(s16 x, s16 y);

    u32 CalculateVisibility() const;

    void RecreateDrawable();

    void OnOwnerDies(XClient* owner) override;

private:
    friend class WindowManager;
    bool RouteGrabEvent(xEvent& event, u32 mask, XClient* client);

    WindowManager& window_manager;
    sp<Drawable> drawable{};

    bool is_input_only{};
    bool composite_redirected{};
    std::optional<RectList> shape_bounding, shape_clip, shape_input;
    u32 shape_generation{};
    Rect out_rect{};
    Rect in_rect{};
    u16 border_width{};
    u32 visibility{VisibilityNotViewable};
    bool exposed{};
    Window* parent{};
    XClient* owner_client{};
    u16 width{};
    u16 height{};
    u8 depth{};
    WindowAttributes attributes{};
    std::unordered_map<XClient*, u32> client_masks;
    std::unordered_map<XClient*, u32> xi_masks;
    std::list<Window*> children;
    std::unordered_map<u32, sp<Property>> properties;
};

}  // namespace xorg
