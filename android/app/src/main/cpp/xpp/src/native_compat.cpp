#include <cstring>
#include <vector>
#include "core/exceptions.h"
#include "core/trace.h"
#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/window_manager.h"
#include "core/window.h"
#include "core/keyboard.h"
#include "core/atom.h"
#include "core/selection_manager.h"
#include "core/graphics_context.h"
#include "core/drawable.h"
#include "core/extension.h"
#include "core/xscreen.h"
#include "core/cursor.h"
#include "native_drawable.h"
#include "native_screen.h"

namespace xorg {
void XClient::ProcCompat() {
    const auto* req = GetRequestData<xReq>();
    auto send = [this](auto& r) { r.type = X_Reply; r.sequenceNumber = sequence; WriteImmediately(&r, sizeof(r)); };
    // Balasan mentah: 32 byte header (b[8..31] diisi pemanggil) + data tambahan; [extraWords] = panjang tambahan dalam kata 4 byte.
    auto rawReply = [this](std::vector<u8>& b, u32 extraWords) {
        b[0] = X_Reply;
        const u16 seq = static_cast<u16>(sequence);
        memcpy(&b[2], &seq, 2);
        memcpy(&b[4], &extraWords, 4);
        WriteImmediately(b.data(), b.size());
    };
    switch (req->reqType) {
    case X_ChangeSaveSet: case X_CloseFont:
    case X_GrabKey: case X_UngrabKey:
    case X_GrabServer: case X_UngrabServer:
        // Grab tombol dan grab server tidak diimplementasikan (keyboard: tidak ada grab pasif).
        if (TraceOn()) {
            static int traced_grabs = 0;
            if (traced_grabs < 200) {
                ++traced_grabs;
                Trace("Unsupported grab request op=%u data=%u", unsigned(req->reqType), unsigned(req->data));
            }
        }
        return;
    case X_GrabButton: {
        CHECK_REQUEST_SIZE_STRICT(xGrabButtonReq);
        const auto* r = GetRequestData<xGrabButtonReq>();
        auto* window = GetResourceAs<Window>(r->grabWindow);
        if (!window) throw BadWindowError(r->grabWindow);
        auto* confine = r->confineTo ? GetResourceAs<Window>(r->confineTo) : nullptr;
        auto* cursor = r->cursor ? GetResourceAs<Cursor>(r->cursor) : nullptr;
        Trace("GrabButton win=0x%x button=%u mods=0x%x owner=%d psync=%d mask=0x%x", unsigned(r->grabWindow), unsigned(r->button),
              unsigned(r->modifiers), int(r->ownerEvents), int(r->pointerMode == GrabModeSync), unsigned(r->eventMask));
        server.GetWindowManager().GrabButton(this, window, r->button, r->modifiers, r->ownerEvents, r->eventMask,
                                             r->pointerMode == GrabModeSync, confine, cursor);
        return;
    }
    case X_UngrabButton: {
        CHECK_REQUEST_SIZE_STRICT(xUngrabButtonReq);
        const auto* r = GetRequestData<xUngrabButtonReq>();
        auto* window = GetResourceAs<Window>(r->grabWindow);
        if (!window) throw BadWindowError(r->grabWindow);
        server.GetWindowManager().UngrabButton(this, window, r->button, r->modifiers);
        return;
    }
    case X_AllowEvents: {
        CHECK_REQUEST_SIZE_STRICT(xAllowEventsReq);
        const auto* r = GetRequestData<xAllowEventsReq>();
        Trace("AllowEvents mode=%u", unsigned(r->mode));
        server.GetWindowManager().AllowEvents(this, r->mode);
        return;
    }
    case X_ChangeKeyboardControl: case X_ChangePointerControl:
    case X_SetCloseDownMode: case X_SetScreenSaver: case X_InstallColormap:
    case X_UninstallColormap: case X_FreeColors:
        return;
    case X_FreeCursor: {
        CHECK_REQUEST_SIZE_STRICT(xResourceReq);
        const auto id = GetRequestData<xResourceReq>()->id;
        if (!GetResourceAs<Cursor>(id)) throw BadCursorError(id);
        // Window attributes and active grabs retain their own references.
        RemoveResource(id); return;
    }
    case X_RecolorCursor: {
        CHECK_REQUEST_SIZE_STRICT(xRecolorCursorReq); const auto* q = GetRequestData<xRecolorCursorReq>();
        auto* cursor = GetResourceAs<Cursor>(q->cursor); if (!cursor) throw BadCursorError(q->cursor);
        auto rgb = [](u16 r, u16 g, u16 b) { return u32(r >> 8) << 16 | u32(g >> 8) << 8 | (b >> 8); };
        cursor->Recolor(rgb(q->foreRed, q->foreGreen, q->foreBlue), rgb(q->backRed, q->backGreen, q->backBlue));
        server.GetWindowManager().UpdatePointer(NotifyNormal); return;
    }
    case X_GrabKeyboard: {
        CHECK_REQUEST_SIZE_STRICT(xGrabKeyboardReq);
        Trace("GrabKeyboard window=0x%x (tidak dicatat server)", unsigned(GetRequestData<xGrabKeyboardReq>()->grabWindow));
        xGrabKeyboardReply r{}; r.status = GrabSuccess; send(r); return;
    }
    case X_UngrabKeyboard: return;
    case X_CopyArea: {
        CHECK_REQUEST_SIZE_STRICT(xCopyAreaReq); const auto* r = GetRequestData<xCopyAreaReq>();
        auto* source = GetDrawable(r->srcDrawable); auto* target = GetDrawable(r->dstDrawable);
        auto* gc = GetResourceAs<GraphicsContext>(r->gc);
        if (!source || !target) throw BadDrawableError(!source ? r->srcDrawable : r->dstDrawable);
        // Public 4.1 uses redirected-window CopyArea as a composition/stacking
        // request. Keep ordinary pixmaps on the real copy path.
        auto* srcWindow = GetResourceAs<Window>(r->srcDrawable);
        if (srcWindow && srcWindow->IsCompositeRedirected()) {
            if (auto* dstWindow = GetResourceAs<Window>(r->dstDrawable))
                server.GetWindowManager().CompositeCopy(srcWindow, dstWindow, r->dstX, r->dstY);
            return;
        }
        if (!gc) throw BadGCError(r->gc);
        static int traced = 0;
        if (traced < 120 && TraceOn()) {
            ++traced;
            Trace("CopyArea src=0x%x dst=0x%x from=%d,%d to=%d,%d size=%ux%u same=%d srcWin=%d dstWin=%d", unsigned(r->srcDrawable),
                  unsigned(r->dstDrawable), int(r->srcX), int(r->srcY), int(r->dstX), int(r->dstY), unsigned(r->width),
                  unsigned(r->height), int(r->srcDrawable == r->dstDrawable), int(srcWindow != nullptr),
                  int(GetResourceAs<Window>(r->dstDrawable) != nullptr));
        }
        static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(target, [&] {
            // Klip GC (cairo memasang XSetClipRectangles lalu satu CopyArea untuk seluruh area pembatas): hanya bagian di
            // dalam klip yang disalin; bagian lain di window tidak boleh tertimpa isi pixmap sumber yang belum digambar.
            const Rect area{r->dstX, r->dstY, r->dstX + int(r->width), r->dstY + int(r->height)};
            for (const Rect& c : gc->ApplyClip(area)) {
                static_cast<NativeDrawable*>(target)->CopyAreaFrom(*static_cast<NativeDrawable*>(source),
                        r->srcX + (c.left - r->dstX), r->srcY + (c.top - r->dstY), c.left, c.top,
                        c.right - c.left, c.bottom - c.top);
            }
        });
        if (gc->GetAttribute(GCGraphicsExposures)) {
            xEvent event{}; event.u.u.type = NoExpose; event.u.noExposure.drawable = r->dstDrawable;
            event.u.noExposure.majorEvent = X_CopyArea; SendEvent(event);
        }
        return;
    }
    case X_ClearArea: {
        CHECK_REQUEST_SIZE_STRICT(xClearAreaReq); const auto* r = GetRequestData<xClearAreaReq>();
        auto* w = GetResourceAs<Window>(r->window); if (!w) throw BadWindowError(r->window);
        auto* d = w->GetDrawable(); if (!d) return;
        static_cast<NativeScreen&>(server.GetScreen()).MutateDrawable(d, [&] {
            // Latar bisa berupa warna, tile pixmap (CWBackPixmap), atau ParentRelative.
            w->PaintBackground(r->x, r->y, r->width ? r->width : w->GetWidth() - r->x,
                               r->height ? r->height : w->GetHeight() - r->y);
        });
        if (r->exposures) w->MakeExposure(); return;
    }
    case X_QueryKeymap: {
        xQueryKeymapReply r{}; r.length = 2; send(r); return;
    }
    case X_GetKeyboardControl: {
        xGetKeyboardControlReply r{}; r.length = 5; r.globalAutoRepeat = 1;
        r.keyClickPercent = 0; r.bellPercent = 0; send(r); return;
    }
    case X_GetPointerControl: {
        xGetPointerControlReply r{}; r.accelNumerator = r.accelDenominator = 1; r.threshold = 0; send(r); return;
    }
    case X_GetPointerMapping: {
        xGetPointerMappingReply r{}; r.type = X_Reply; r.sequenceNumber = sequence; r.nElts = 7; r.length = 2;
        const u8 buttons[] = {1,2,3,4,5,6,7,0}; Write(&r, sizeof(r)); Write(buttons, 8); Flush(); return;
    }
    case X_SetPointerMapping: case X_SetModifierMapping: {
        xSetPointerMappingReply r{}; r.success = MappingSuccess; send(r); return;
    }
    case X_QueryBestSize: {
        CHECK_REQUEST_SIZE_STRICT(xQueryBestSizeReq); const auto* q = GetRequestData<xQueryBestSizeReq>();
        xQueryBestSizeReply r{}; r.width = q->width; r.height = q->height; send(r); return;
    }
    case X_AllocColor: {
        CHECK_REQUEST_SIZE_STRICT(xAllocColorReq); const auto* q = GetRequestData<xAllocColorReq>();
        xAllocColorReply r{}; r.red = q->red; r.green = q->green; r.blue = q->blue;
        r.pixel = (q->red >> 8) << 16 | (q->green >> 8) << 8 | (q->blue >> 8); send(r); return;
    }
    case X_TranslateCoords: {
        CHECK_REQUEST_SIZE_STRICT(xTranslateCoordsReq); const auto* q = GetRequestData<xTranslateCoordsReq>();
        auto* source = GetResourceAs<Window>(q->srcWid); auto* target = GetResourceAs<Window>(q->dstWid);
        if (!source || !target) throw BadWindowError(!source ? q->srcWid : q->dstWid);
        xTranslateCoordsReply r{}; r.sameScreen = true;
        r.dstX = source->GetInRect().left + q->srcX - target->GetInRect().left;
        r.dstY = source->GetInRect().top + q->srcY - target->GetInRect().top;
        send(r); return;
    }
    case X_GetAtomName: {
        CHECK_REQUEST_SIZE_STRICT(xResourceReq); auto id = GetRequestData<xResourceReq>()->id;
        if (!Atom::IsValid(id)) throw BadAtomError(id);
        auto name = Atom::GetName(id); xGetAtomNameReply r{};
        r.type = X_Reply; r.sequenceNumber = sequence; r.nameLength = name.size(); r.length = (name.size() + 3) / 4;
        Write(&r, sizeof(r)); Write(name.data(), name.size()); WritePad((-name.size()) & 3); Flush(); return;
    }
    case X_ListExtensions: {
        std::vector<std::string> names = {"BIG-REQUESTS", "MIT-SHM", "DRI3", "Present", "SYNC", "RANDR", "GLX", "Composite", "RENDER", "DAMAGE", "XFIXES", "SHAPE", "XInputExtension"};
        if (server.RendererConfig().disableShm) names.erase(std::find(names.begin(), names.end(), "MIT-SHM"));
        if (server.RendererConfig().disableDri3) names.erase(std::find(names.begin(), names.end(), "DRI3"));
        if (server.RendererConfig().disableRender) names.erase(std::find(names.begin(), names.end(), "RENDER"));
        if (server.RendererConfig().disableComposite) names.erase(std::find(names.begin(), names.end(), "Composite"));
        std::vector<u8> bytes; for (auto& n : names) { bytes.push_back(n.size()); bytes.insert(bytes.end(), n.begin(), n.end()); }
        xListExtensionsReply r{}; r.type = X_Reply; r.sequenceNumber = sequence;
        r.nExtensions = names.size(); r.length = (bytes.size()+3)/4;
        Write(&r, sizeof(r)); Write(bytes.data(), bytes.size()); WritePad((-bytes.size()) & 3); Flush(); return;
    }
    case X_SetSelectionOwner: {
        CHECK_REQUEST_SIZE_STRICT(xSetSelectionOwnerReq); const auto* q = GetRequestData<xSetSelectionOwnerReq>();
        auto* w = q->window ? GetResourceAs<Window>(q->window) : nullptr;
        if (q->window && !w) throw BadWindowError(q->window);
        server.GetSelectionManager().SetSelection(q->selection, w, this, q->time); return;
    }
    case X_ConvertSelection: {
        CHECK_REQUEST_SIZE_STRICT(xConvertSelectionReq); const auto* q = GetRequestData<xConvertSelectionReq>();
        auto* requestor = GetResourceAs<Window>(q->requestor); if (!requestor) throw BadWindowError(q->requestor);
        auto selection = server.GetSelectionManager().GetSelection(q->selection);
        xEvent event{};
        if (selection.owner && selection.client) {
            event.u.u.type = SelectionRequest; event.u.selectionRequest.time = q->time;
            event.u.selectionRequest.owner = selection.owner->id; event.u.selectionRequest.requestor = q->requestor;
            event.u.selectionRequest.selection = q->selection; event.u.selectionRequest.target = q->target;
            event.u.selectionRequest.property = q->property; selection.client->SendEvent(event);
        } else {
            event.u.u.type = SelectionNotify; event.u.selectionNotify.time = q->time;
            event.u.selectionNotify.requestor = q->requestor; event.u.selectionNotify.selection = q->selection;
            event.u.selectionNotify.target = q->target; event.u.selectionNotify.property = None; SendEvent(event);
        }
        return;
    }
    // ---- Request yang dulu jatuh ke BadRequest. Klien Xlib (SDL, GTK2, Tk, banyak AppImage) memakai handler galat bawaan yang
    // MEMATIKAN proses pada galat apa pun, sehingga aplikasi langsung hilang dan layar tinggal hitam; klien xcb yang menunggu
    // balasan bisa tergantung. Di sini: request yang butuh balasan dijawab dengan balasan sah (kosong), galat yang memang
    // benar menurut protokol dikirim dengan kode yang benar, dan request tanpa balasan diabaikan dengan jejak.
    case X_ListProperties: {
        CHECK_REQUEST_SIZE_STRICT(xResourceReq);
        const auto id = GetRequestData<xResourceReq>()->id;
        auto* window = GetResourceAs<Window>(id);
        if (!window) throw BadWindowError(id);
        const auto atoms = window->GetPropertyAtoms();
        std::vector<u8> b(32 + atoms.size() * 4);
        const u16 count = static_cast<u16>(atoms.size());
        memcpy(&b[8], &count, 2);
        for (size_t i = 0; i < atoms.size(); ++i) memcpy(&b[32 + i * 4], &atoms[i], 4);
        rawReply(b, static_cast<u32>(atoms.size()));
        return;
    }
    case X_GetMotionEvents: {
        std::vector<u8> b(32); rawReply(b, 0); return;           // nEvents = 0
    }
    case X_ListHosts: {
        std::vector<u8> b(32); rawReply(b, 0); return;           // kontrol akses mati, tanpa host
    }
    case X_GetFontPath: {
        std::vector<u8> b(32); rawReply(b, 0); return;           // nPaths = 0
    }
    case X_ListInstalledColormaps: {
        std::vector<u8> b(32); rawReply(b, 0); return;           // nColormaps = 0
    }
    case X_ListFontsWithInfo: {
        std::vector<u8> b(60); rawReply(b, 7); return;           // balasan penutup (nameLength = 0): tidak ada font
    }
    case X_QueryColors: {
        if (request_size < 8) throw BadLengthError();
        const size_t n = (request_size - 8) / 4;
        std::vector<u8> b(32 + n * 8);
        const u16 count = static_cast<u16>(n);
        memcpy(&b[8], &count, 2);
        for (size_t i = 0; i < n; ++i) {
            u32 pixel; memcpy(&pixel, request_buffer.data() + 8 + i * 4, 4);
            const u16 rgb[3] = {u16(((pixel >> 16) & 0xff) * 257), u16(((pixel >> 8) & 0xff) * 257), u16((pixel & 0xff) * 257)};
            memcpy(&b[32 + i * 8], rgb, 6);
        }
        rawReply(b, static_cast<u32>(n * 2));
        return;
    }
    case X_QueryFont: case X_QueryTextExtents: {
        // OpenFont tidak membuat sumber daya font, jadi tidak ada font yang bisa ditanyai. Galat yang benar adalah BadFont
        // (XLoadQueryFont lalu mengembalikan NULL); BadRequest membuat aplikasi mati.
        const auto id = GetRequestData<xResourceReq>()->id;
        throw BadFontError(id);
    }
    case X_AllocNamedColor: case X_LookupColor: throw BadNameError(0);
    case X_AllocColorCells: case X_AllocColorPlanes: throw BadAllocError();
    case X_PolyPoint: case X_PolyRectangle: case X_PolyArc: case X_FillPoly: case X_PolyFillArc: case X_CopyPlane:
    case X_PolyText8: case X_PolyText16: case X_ImageText8: case X_ImageText16: case X_CopyGC: case X_SetDashes:
    case X_CirculateWindow: case X_RotateProperties: case X_ChangeActivePointerGrab: case X_ChangeKeyboardMapping:
    case X_ChangeHosts: case X_SetAccessControl: case X_SetFontPath: case X_KillClient: case X_StoreColors:
    case X_StoreNamedColor:
        if (TraceOn()) {
            static int traced_ignored = 0;
            if (traced_ignored < 100) {
                ++traced_ignored;
                Trace("Request tanpa balasan belum didukung, diabaikan op=%u data=%u", unsigned(req->reqType), unsigned(req->data));
            }
        }
        return;
    default: throw BadRequestError();
    }
}
}


