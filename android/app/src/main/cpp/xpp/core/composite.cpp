#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window_manager.h"
#include "core/pixmap.h"
#include "core/render_picture.h"

namespace xorg {
void XClient::ProcComposite() {
    const auto minor = GetRequestData<xReq>()->data;
    if (minor == 0) {
        struct Request { xReq header; u32 major, minor; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        struct Reply { u8 type{}, pad{}; u16 sequence{}; u32 length{}, major{}, minor{}, pad2[4]{}; } reply;
        static_assert(sizeof(reply) == 32);
        reply.type = X_Reply; reply.sequence = sequence;
        const auto* r = GetRequestData<Request>();
        reply.minor = r->major ? 4 : std::min(4u, r->minor);
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    if (minor == 1 || minor == 3) {
        struct Request { xReq header; u32 window; u8 update; u8 pad[3]; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        auto* window = GetResourceAs<Window>(r->window);
        if (!window) throw BadWindowError(r->window);
        server.GetWindowManager().RedirectComposite(this, window, r->update, minor == 1);
        return;
    }
    // RedirectSubwindows/UnredirectSubwindows: setiap jendela sudah punya drawable sendiri dan server menggambar jendela itu
    // ke layar, jadi pengalihan manual hanya dicatat sebagai diterima. Compositor menggambar ke overlay di atas hasil server.
    if (minor == 2 || minor == 4) {
        struct Request { xReq header; u32 window; u8 update; u8 pad[3]; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        return;
    }
    // CreateRegionFromBorderClip(region, window)
    if (minor == 5) {
        struct Request { xReq header; u32 region, window; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        auto* window = GetResourceAs<Window>(r->window);
        if (!window) throw BadWindowError(r->window);
        CheckResourceID(r->region);
        if (GetResource(r->region)) throw BadIdChoiceError(r->region);
        AddResource(std::make_shared<XFixesRegion>(r->region, RectList{{0, 0, s32(window->GetWidth()), s32(window->GetHeight())}}));
        return;
    }
    // NameWindowPixmap(window, pixmap): pixmap yang memakai drawable jendela (tanpa salinan).
    if (minor == 6) {
        struct Request { xReq header; u32 window, pixmap; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        auto* window = GetResourceAs<Window>(r->window);
        if (!window) throw BadWindowError(r->window);
        if (window->IsInputOnly() || !window->SharedDrawable()) throw BadMatchError();
        CheckResourceID(r->pixmap);
        if (GetResource(r->pixmap)) throw BadIdChoiceError(r->pixmap);
        AddResource(std::make_shared<Pixmap>(r->pixmap, u16(window->GetWidth()), u16(window->GetHeight()), window->GetDepth(),
                                             window->SharedDrawable()));
        return;
    }
    // GetOverlayWindow: overlay adalah root. Server sendiri menggambar jendela di atas root, jadi hasil compositor yang menggambar
    // ke root tidak menutupi jendela dan tidak menangkap masukan; jendela tetap tampil normal.
    if (minor == 7) {
        struct Request { xReq header; u32 window; };
        CHECK_REQUEST_SIZE_STRICT(Request);
        auto* r = GetRequestData<Request>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        struct Reply { u8 type{}, pad{}; u16 sequence{}; u32 length{}, overlay{}, pad2[5]{}; } reply;
        static_assert(sizeof(reply) == 32);
        reply.type = X_Reply; reply.sequence = sequence; reply.overlay = server.GetWindowManager().GetRootWindow()->id;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    if (minor == 8) return;  // ReleaseOverlayWindow
    throw XRequestError(BadImplementation, 0);
}
}
