#pragma once

#include "base/types.h"
#include <unordered_map>
#include <vector>

namespace xorg {

class Window;
class XClient;
class XServer;

struct Selection {
    Window* owner;
    XClient* client;
};

// Langganan XFIXES SelectSelectionInput: mask bit 1 = SetSelectionOwnerNotify, 2 = SelectionWindowDestroyNotify,
// 4 = SelectionClientCloseNotify.
struct FixesSelectionInput {
    XClient* client;
    u32 window;
    u32 selection;
    u32 mask;
};

class SelectionManager {
public:
    explicit SelectionManager(XServer* server);
    ~SelectionManager();

    // mask 0 menghapus langganan (client, window, selection).
    void SelectFixesInput(XClient* client, u32 window, u32 selection, u32 mask);

    void SetSelection(u32 atom, Window* owner, XClient* client, u32 timestamp);

    Selection GetSelection(u32 atom);
    void RemoveClient(XClient*);
    void RemoveWindow(Window*);

private:
    void NotifyFixes(u32 selection, u8 subtype, u32 mask_bit, u32 owner, u32 timestamp);

    XServer* server;
    std::pmr::unordered_map<u32, Selection> selections;
    std::vector<FixesSelectionInput> fixes_inputs;
};

}  // namespace xorg