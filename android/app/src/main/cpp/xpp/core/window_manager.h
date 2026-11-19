#pragma once

#include <X11/X.h>
#include <chrono>
#include <functional>
#include <vector>
#include "base/types.h"
#include "core/mouse.h"
#include "core/screen_info.h"
#include "core/window.h"
#include "core/xserver.h"

namespace xorg {

class DrawableManager;
class Window;
class Visual;
class XClient;
class XScreen;

class WindowManager final {
public:
    WindowManager(XServer& server, Mouse& mouse, ScreenInfo screen_info);

    XServer& GetServer() const { return server; }

    XScreen& GetScreen() const;

    Mouse& GetMouse() const { return mouse; }

    Window* GetRootWindow() const { return root_window.get(); }

    Window* GetPointerWindow(s32 x, s32 y) const;

    u8 GetFocusRevertTo() const { return focus_revert_to; }

    Window* GetFocuseWindow() const { return focus_window; }

    sp<Window> CreateWindow(XClient* client,
                            u32 id,
                            Window* parent,
                            s16 x,
                            s16 y,
                            u16 width,
                            u16 height,
                            u16 border_width,
                            u16 win_class,
                            u8 depth,
                            u32 mask,
                            const u32* values);

    bool ConfigureWindow(XClient* caller_client, Window* window, u32 mask, const u32* values);
    void RedirectComposite(XClient*, Window*, u8 update, bool redirect);
    void RemoveCompositeClient(XClient*);
    // CompositeRedirectSubwindows(root, Manual) oleh compositor; dipakai untuk memilih mode tampilan compositor.
    void SetSubwindowsRedirect(XClient* client, bool redirected) {
        if (redirected) subwindows_redirector = client;
        else if (subwindows_redirector == client) subwindows_redirector = nullptr;
    }
    bool SubwindowsRedirected() const { return subwindows_redirector != nullptr; }
    void RemoveCompositeWindow(Window*);
    void CompositeCopy(Window* source, Window* destination, s16 x, s16 y);

    void MapWindow(const XClient* caller_client, Window* window);
    std::function<void(Window*)> onWindowMapped;

    void UnmapWindow(Window* window, bool invalidate = true);

    void FocusInOutNotify(Window* wlose, Window* wgain, Window* wp, Window* wroot, u32 mode) const;

    void RevertFocus(Window* window);

    void DestroyWindow(Window* window, bool release_resource);

    void RemoveAllSubWindows(Window* window, std::vector<Window*>& windows_to_remove);

    void SetInputFocus(Window* window, u8 revert_to, u32 time);

    void ChangeWindowAttributes(XClient* client, Window* window, u32 mask, const u32* values);

    u32 GrabPointer(XClient* client,
                    Window* grab_window,
                    Window* confine_window,
                    Cursor* cursor,
                    u32 time,
                    u32 event_mask,
                    bool owner_events,
                    bool psync,
                    bool ksync);

    void UngrabPointer(const XClient* client, u32 time);

    // Grab tombol pasif (XGrabButton). WM seperti xfwm4 memakainya untuk click-to-focus dan raise: klik pada window klien
    // diterima WM lebih dulu (mode sinkron), lalu WM memanggil AllowEvents(ReplayPointer) agar klik diteruskan ke klien.
    void GrabButton(XClient* client, Window* window, u8 button, u16 modifiers, bool owner_events, u16 event_mask,
                    bool pointer_sync, Window* confine_window, Cursor* cursor);
    void UngrabButton(XClient* client, Window* window, u8 button, u16 modifiers);
    void AllowEvents(XClient* client, u8 mode);

    void UpdatePointer(u32 mode);

    Cursor* UpdatePointerPosition(s32 x, s32 y, u32 mode, bool notify);

    void UpdatePointerButton(u16 button, bool down);

    void OnPointerMove(s32 x, s32 y);

    void OnPointerButtonChange(u16 button, bool down);

    void OnKeyboardKeyChange(u32 keycode, bool down);

    u32 GetButtonMasks() const;

private:
    XServer& server;
    Mouse& mouse;
    bool has_window_mapped{};
    sp<Window> root_window;
    Window* grab_keyboard_window{};
    u8 focus_revert_to{RevertToNone};
    Window* focus_window{};
    Window* motion_window{};
    XClient* grab_pointer_client{};
    XClient* grab_keyboard_client{};
    Window* grab_confine_window{};
    Window* grab_pointer_window{};
    sp<Cursor> grab_cursor;
    bool grab_pointer_passive{};
    bool grab_pointer_synchronous{};
    bool grab_pointer_owner_events{};
    u32 grab_pointer_under{};  // id window di bawah pointer saat grab owner_events (untuk event crossing normal)

    struct PassiveButtonGrab {
        XClient* client;
        u32 window;
        u8 button;  // 0 = AnyButton
        u16 modifiers;  // 0x8000 = AnyModifier
        bool owner_events;
        u16 event_mask;
        bool pointer_sync;
        u32 confine_window;
        u32 cursor;
    };
    std::vector<PassiveButtonGrab> passive_grabs;
    // Setelah grab pasif sinkron aktif, antrean pointer "beku" sampai AllowEvents. Aman: dilepas otomatis bila WM terlambat.
    bool passive_frozen{};
    bool passive_skip{};  // sedang memutar ulang klik: jangan cocokkan grab pasif lagi
    u16 frozen_button{};
    bool frozen_release{};
    std::chrono::steady_clock::time_point frozen_since{};
    const PassiveButtonGrab* FindPassiveButtonGrab(Window* pointer_window, u16 button, u32 state) const;
    void ReplayFrozenPointer();
    void CheckFrozenTimeout();
    bool grab_keyboard_synchronous{};
    u32 grab_event_mask{};
    struct CompositeRedirect { XClient* client; Window* window; u8 update; };
    std::vector<CompositeRedirect> composite_redirects;
    XClient* subwindows_redirector{};  // klien yang me-redirect subwindow root secara manual (compositor)
};

}  // namespace xorg
