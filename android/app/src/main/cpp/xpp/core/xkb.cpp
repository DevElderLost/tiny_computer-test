// XKEYBOARD: lihat core/xkb_proto.h. Berkas ini hanya menghubungkan modul protokol dengan XClient/ExtensionManager.
#include <cstring>
#include "core/atom.h"
#include "core/exceptions.h"
#include "core/extension.h"
#include "core/keyboard.h"
#include "core/xclient.h"
#include "core/xserver.h"
#include "core/xkb_proto.h"

namespace xorg {

namespace {
const xkb::Keymap& StaticKeymap(const Keyboard& keyboard) {
    // Tabel keysym inti tidak pernah berubah selama proses hidup (ChangeKeyboardMapping diabaikan), jadi keymap XKB cukup
    // dibangun sekali. Atom dibuat lewat tabel atom global.
    static const xkb::Keymap keymap(static_cast<u8>(keyboard.GetKeycodeMin()), static_cast<u8>(keyboard.GetKeycodeMax()),
                                    static_cast<u8>(keyboard.GetKeysymsPerKeycode()), keyboard.GetKeysyms(),
                                    [](const std::string& name) { return Atom::Intern(name); });
    return keymap;
}
}  // namespace

void XClient::ProcXkb() {
    const auto* req = GetRequestData<xReq>();
    const auto& keymap = StaticKeymap(server.GetKeyboard());
    const auto mods = static_cast<u8>(server.GetKeyboard().GetModifiersMask() & 0xff);
    const xkb::Result result = keymap.Handle(req->data, request_buffer.data(), request_size, sequence, mods);
    switch (result.status) {
        case xkb::Status::Reply:
            WriteImmediately(result.bytes.data(), result.bytes.size());
            return;
        case xkb::Status::NoReply:
            return;
        case xkb::Status::BadValue: throw BadValueError(result.data);
        case xkb::Status::BadLength: throw BadLengthError();
        case xkb::Status::BadKeyboard:
            throw XRequestError(server.GetExtensionManager().FindExtension("XKEYBOARD")->error_base, result.data);
        case xkb::Status::BadImplementation: throw XRequestError(17 /* BadImplementation */, 0);
        case xkb::Status::BadRequest: throw BadRequestError();
    }
}

}  // namespace xorg
