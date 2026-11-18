#include "core/selection_manager.h"

#include <algorithm>
#include <cstring>

#include <X11/Xproto.h>
#include "extension.h"
#include "window.h"
#include "xclient.h"
#include "xserver.h"

namespace xorg {

SelectionManager::SelectionManager(XServer* server) : server(server) {}

SelectionManager::~SelectionManager() {}

void SelectionManager::SetSelection(u32 atom, Window* owner, XClient* client, u32 timestamp) {
    auto& selection = selections[atom];
    if (selection.client && (!owner || selection.client != client)) {
        xEvent event{};
        event.u.selectionClear.time = client->GetServer()->GetTimestamp();
        event.u.selectionClear.window = selection.owner->id;
        event.u.selectionClear.atom = atom;
        event.u.u.type = SelectionClear;
        selection.client->SendEvent(event);
    }
    selection.owner = owner;
    selection.client = client;
    // GDK menentukan gdk_screen_is_composited dari kepemilikan _NET_WM_CM_Sn dan memperbaruinya lewat event ini.
    NotifyFixes(atom, 0, 1, owner ? owner->id : 0, timestamp ? timestamp : client->GetServer()->GetTimestamp());
}

void SelectionManager::SelectFixesInput(XClient* client, u32 window, u32 selection, u32 mask) {
    std::erase_if(fixes_inputs, [&](const FixesSelectionInput& s) {
        return s.client == client && s.window == window && s.selection == selection;
    });
    if (mask) fixes_inputs.push_back({client, window, selection, mask});
}

void SelectionManager::NotifyFixes(u32 selection, u8 subtype, u32 mask_bit, u32 owner, u32 timestamp) {
    const auto* extension = server->GetExtensionManager().FindExtension("XFIXES");
    if (!extension) return;
    const std::vector<FixesSelectionInput> copy = fixes_inputs;
    for (const auto& input : copy) {
        if (input.selection != selection || !(input.mask & mask_bit)) continue;
        xEvent event{};
        auto* b = reinterpret_cast<u8*>(&event);
        b[0] = u8(extension->event_base);
        b[1] = subtype;
        std::memcpy(b + 4, &input.window, 4);
        std::memcpy(b + 8, &owner, 4);
        std::memcpy(b + 12, &selection, 4);
        std::memcpy(b + 16, &timestamp, 4);
        std::memcpy(b + 20, &timestamp, 4);
        input.client->SendEvent(event);
    }
}

Selection SelectionManager::GetSelection(u32 atom) { return selections[atom]; }
void SelectionManager::RemoveClient(XClient* client) {
    std::erase_if(fixes_inputs, [&](const FixesSelectionInput& s) { return s.client == client; });
    for (auto& [atom, s] : selections) {
        if (s.client != client) continue;
        s = {};
        NotifyFixes(atom, 2, 4, 0, server->GetTimestamp());
    }
}
void SelectionManager::RemoveWindow(Window* window) {
    for (auto& [atom, s] : selections) {
        if (s.owner != window) continue;
        s = {};
        NotifyFixes(atom, 1, 2, 0, server->GetTimestamp());
    }
}

}  // namespace xorg
