#include "core/xclient.h"
#include "core/xclient_dispatch.h"
#include "core/xserver.h"
#include "core/extension.h"
#include "core/window_manager.h"
#include "core/window.h"
#include "core/atom.h"
#include "native_screen.h"
#include <X11/extensions/randrproto.h>
#include <algorithm>
#include <cstring>

namespace xorg {
void XClient::ProcRandR() {
    auto* req = GetRequestData<xReq>();
    const auto& screen = server.GetScreenInfo();
    constexpr u32 crtc = 0x100001, output = 0x100002, modeBase = 0x100003;
    const u32 timestamp = 1;
    const u32 root = server.GetWindowManager().GetRootWindow()->id;
    auto& native = static_cast<NativeScreen&>(server.GetScreen());
    const auto& rates = native.RandrRates();
    const u16 rate = native.RandrRate();
    const u32 mode = modeBase + std::distance(rates.begin(), std::find(rates.begin(), rates.end(), rate));
    auto send = [this](auto& reply) { reply.type = X_Reply; reply.sequenceNumber = sequence; WriteImmediately(&reply, sizeof(reply)); };
    // Galat khusus RandR (BadRROutput, BadRRCrtc, BadRRMode, BadRRProvider, BadRRLease): kode = error_base ekstensi + BadRR*.
    // Dulu ekstensi terdaftar tanpa galat sehingga crtc/output salah dijawab BadValue; klien RandR 1.2+ menunggu kode ini.
    const auto* ext = server.GetExtensionManager().FindExtension("RANDR");
    auto bad = [ext](u32 code, u32 id) { return XRequestError(static_cast<u8>(ext->error_base + code), id); };
    // Mode yang berlaku sekarang (dihitung ulang karena SetScreenConfig/SetCrtcConfig bisa mengubah laju segarnya).
    auto currentMode = [&]() -> u32 {
        const auto& rs = native.RandrRates();
        return modeBase + std::distance(rs.begin(), std::find(rs.begin(), rs.end(), native.RandrRate()));
    };
    // RandR 1.2+: kirim RRScreenChangeNotify, RRCrtcChangeNotify, dan RROutputChangeNotify ke klien yang memilihnya lewat
    // RRSelectInput. Dipanggil hanya dari thread server (handler request), setelah mode/laju segar benar-benar berubah.
    auto notifyModeChange = [&]() {
        const u32 newMode = currentMode();
        server.ForEachClient([&](XClient& c) {
            auto& selections = c.RandrSelections();
            for (auto it = selections.begin(); it != selections.end();) {
                if (!c.GetResource(it->first)) { it = selections.erase(it); continue; }   // window sudah dihancurkan
                const u32 window = it->first;
                const u16 mask = it->second;
                ++it;
                xEvent ev{};
                if (mask & RRScreenChangeNotifyMask) {
                    xRRScreenChangeNotifyEvent e{};
                    e.type = static_cast<u8>(ext->event_base + RRScreenChangeNotify); e.rotation = RR_Rotate_0;
                    e.timestamp = e.configTimestamp = timestamp; e.root = root; e.window = window; e.sizeID = 0;
                    e.subpixelOrder = 0; e.widthInPixels = screen.width; e.heightInPixels = screen.height;
                    e.widthInMillimeters = screen.GetWidthInMillimeters(); e.heightInMillimeters = screen.GetHeightInMillimeters();
                    memcpy(&ev, &e, sizeof(e)); c.SendEvent(ev);
                }
                if (mask & RRCrtcChangeNotifyMask) {
                    xRRCrtcChangeNotifyEvent e{};
                    e.type = static_cast<u8>(ext->event_base + RRNotify); e.subCode = RRNotify_CrtcChange;
                    e.timestamp = timestamp; e.window = window; e.crtc = crtc; e.mode = newMode; e.rotation = RR_Rotate_0;
                    e.x = 0; e.y = 0; e.width = screen.width; e.height = screen.height;
                    memcpy(&ev, &e, sizeof(e)); c.SendEvent(ev);
                }
                if (mask & RROutputChangeNotifyMask) {
                    xRROutputChangeNotifyEvent e{};
                    e.type = static_cast<u8>(ext->event_base + RRNotify); e.subCode = RRNotify_OutputChange;
                    e.timestamp = e.configTimestamp = timestamp; e.window = window; e.output = output; e.crtc = crtc;
                    e.mode = newMode; e.rotation = RR_Rotate_0; e.connection = RR_Connected; e.subpixelOrder = 0;
                    memcpy(&ev, &e, sizeof(e)); c.SendEvent(ev);
                }
            }
        });
    };
    switch (req->data) {
    case X_RRQueryVersion: {
        CHECK_REQUEST_SIZE_STRICT(xRRQueryVersionReq);
        // RandR 1.6 (menambah RRCreateLease/RRFreeLease/RRNotify_Lease di atas 1.5: monitor; 1.4: provider; 1.3: panning/primary/transform).
        // Versi dinegosiasikan seperti server asli: min(versi klien, versi server). SDL3 mensyaratkan RandR >= 1.3.
        const auto* v = GetRequestData<xRRQueryVersionReq>();
        u32 major = RANDR_MAJOR, minor = RANDR_MINOR;
        if (v->majorVersion < u32(RANDR_MAJOR)) { major = RANDR_MAJOR; minor = 0; }
        else if (v->majorVersion == u32(RANDR_MAJOR) && v->minorVersion < u32(RANDR_MINOR)) minor = v->minorVersion;
        xRRQueryVersionReply reply{}; reply.majorVersion = major; reply.minorVersion = minor;
        send(reply); return;
    }
    case X_RRSelectInput: {
        CHECK_REQUEST_SIZE_STRICT(xRRSelectInputReq);
        auto* r = GetRequestData<xRRSelectInputReq>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        constexpr u16 kKnownMasks = RRScreenChangeNotifyMask | RRCrtcChangeNotifyMask | RROutputChangeNotifyMask |
                                    RROutputPropertyNotifyMask | RRProviderChangeNotifyMask | RRProviderPropertyNotifyMask |
                                    RRResourceChangeNotifyMask | RRLeaseNotifyMask;
        if (r->enable & ~kKnownMasks) throw BadValueError(r->enable);
        if (r->enable) randrSelections[r->window] = r->enable; else randrSelections.erase(r->window);
        return;
    }
    case X_RRGetScreenSizeRange: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenSizeRangeReq);
        xRRGetScreenSizeRangeReply reply{};
        reply.minWidth = reply.maxWidth = screen.width; reply.minHeight = reply.maxHeight = screen.height;
        send(reply); return;
    }
    case X_RRGetScreenInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenInfoReq);
        xRRGetScreenInfoReply reply{};
        const size_t body = 8 + (1 + rates.size()) * 2;
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.length = (body + 3) / 4;
        reply.root = root; reply.timestamp = reply.configTimestamp = timestamp;
        reply.nSizes = 1; reply.rotation = reply.setOfRotations = RR_Rotate_0;
        reply.rate = rate; reply.nrateEnts = 1 + rates.size();
        xScreenSizes size{screen.width, screen.height, screen.GetWidthInMillimeters(), screen.GetHeightInMillimeters()};
        const u16 count = rates.size();
        Write(&reply, sizeof(reply)); Write(&size, sizeof(size)); Write(&count, 2);
        Write(rates.data(), rates.size() * 2); WritePad((-body) & 3); Flush(); return;
    }
    case X_RRSetScreenConfig: {
        CHECK_REQUEST_SIZE(xRR1_0SetScreenConfigReq);
        auto* r = GetRequestData<xRR1_0SetScreenConfigReq>();
        xRRSetScreenConfigReply reply{}; reply.status = r->sizeID == 0 ? RRSetConfigSuccess : RRSetConfigFailed;
        const u16 rateBefore = native.RandrRate();
        if (reply.status == RRSetConfigSuccess && request_size >= sizeof(xRRSetScreenConfigReq)) {
            u16 requested = GetRequestData<xRRSetScreenConfigReq>()->rate;
            if (requested && !native.SelectRandrRate(requested)) reply.status = RRSetConfigFailed;
        }
        if (native.RandrRate() != rateBefore) notifyModeChange();
        reply.root = root; reply.newTimestamp = reply.newConfigTimestamp = timestamp;
        send(reply); return;
    }
    case X_RRSetScreenSize: {
        CHECK_REQUEST_SIZE_STRICT(xRRSetScreenSizeReq);
        auto* r = GetRequestData<xRRSetScreenSizeReq>();
        if (r->width != screen.width || r->height != screen.height) throw BadMatchError();
        return;
    }
    case X_RRGetScreenResources:
    case X_RRGetScreenResourcesCurrent: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetScreenResourcesReq);
        std::string name = std::to_string(screen.width) + "x" + std::to_string(screen.height);
        xRRGetScreenResourcesReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        const size_t namesSize = name.size() * rates.size();
        reply.length = (8 + rates.size() * sizeof(xRRModeInfo) + ((namesSize + 3) & ~3)) / 4;
        reply.timestamp = reply.configTimestamp = timestamp;
        reply.nCrtcs = reply.nOutputs = 1; reply.nModes = rates.size(); reply.nbytesNames = namesSize;
        Write(&reply, sizeof(reply)); Write(&crtc, 4); Write(&output, 4);
        for (size_t i = 0; i < rates.size(); ++i) {
            xRRModeInfo info{}; info.id = modeBase + i; info.width = screen.width; info.height = screen.height;
            info.hTotal = screen.width; info.vTotal = screen.height;
            info.dotClock = std::min<u64>(u64(screen.width) * screen.height * rates[i], UINT32_MAX);
            info.nameLength = name.size(); Write(&info, sizeof(info));
        }
        for (size_t i = 0; i < rates.size(); ++i) Write(name.data(), name.size());
        WritePad((-namesSize) & 3); Flush(); return;
    }
    case X_RRGetOutputInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetOutputInfoReq);
        if (GetRequestData<xRRGetOutputInfoReq>()->output != output) throw bad(BadRROutput, GetRequestData<xRRGetOutputInfoReq>()->output);
        const std::string name = "Ludashi";
        xRRGetOutputInfoReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.status = RRSetConfigSuccess;
        reply.length = (sizeof(reply) - 32 + 4 + rates.size() * 4 + ((name.size() + 3) & ~3)) / 4;
        reply.timestamp = timestamp; reply.crtc = crtc; reply.connection = RR_Connected;
        reply.mmWidth = screen.GetWidthInMillimeters(); reply.mmHeight = screen.GetHeightInMillimeters();
        reply.nCrtcs = reply.nPreferred = 1; reply.nModes = rates.size(); reply.nameLength = name.size();
        Write(&reply, sizeof(reply)); Write(&crtc, 4);
        for (size_t i = 0; i < rates.size(); ++i) { const u32 id = modeBase + i; Write(&id, 4); }
        Write(name.data(), name.size()); WritePad((4 - name.size() % 4) % 4); Flush(); return;
    }
    case X_RRGetCrtcInfo: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcInfoReq);
        if (GetRequestData<xRRGetCrtcInfoReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRGetCrtcInfoReq>()->crtc);
        xRRGetCrtcInfoReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.length = 2;
        reply.timestamp = timestamp; reply.width = screen.width; reply.height = screen.height;
        reply.mode = mode; reply.rotation = reply.rotations = RR_Rotate_0;
        reply.nOutput = reply.nPossibleOutput = 1;
        Write(&reply, sizeof(reply)); Write(&output, 4); Write(&output, 4); Flush(); return;
    }
    case X_RRSetCrtcConfig: {
        CHECK_REQUEST_SIZE(xRRSetCrtcConfigReq);
        auto* r = GetRequestData<xRRSetCrtcConfigReq>();
        xRRSetCrtcConfigReply reply{};
        if (r->crtc != crtc) throw bad(BadRRCrtc, r->crtc);
        const u16 rateBefore = native.RandrRate();
        reply.status = (r->mode >= modeBase && r->mode - modeBase < rates.size() &&
                        r->x == 0 && r->y == 0 && native.SelectRandrRate(rates[r->mode - modeBase]))
                       ? RRSetConfigSuccess : RRSetConfigFailed;
        if (native.RandrRate() != rateBefore) notifyModeChange();
        reply.newTimestamp = timestamp; send(reply); return;
    }
    case X_RRListOutputProperties: {
        CHECK_REQUEST_SIZE_STRICT(xRRListOutputPropertiesReq);
        // Satu-satunya properti output: _ICC_PROFILE (profil warna layar, sama dengan properti di window root).
        if (GetRequestData<xRRListOutputPropertiesReq>()->output != output) throw bad(BadRROutput, GetRequestData<xRRListOutputPropertiesReq>()->output);
        const Property* icc = GetIccProfileProperty(server);
        xRRListOutputPropertiesReply reply{};
        if (!icc) { send(reply); return; }
        const u32 atom = icc->name;
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.length = 1; reply.nAtoms = 1;
        Write(&reply, sizeof(reply)); Write(&atom, 4); Flush(); return;
    }
    case X_RRGetOutputProperty: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetOutputPropertyReq);
        auto* r = GetRequestData<xRRGetOutputPropertyReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        const Property* icc = GetIccProfileProperty(server);
        xRRGetOutputPropertyReply reply{};
        if (!icc || r->property != icc->name) { send(reply); return; }   // properti tidak ada: format 0, type None
        const auto data = icc->GetData();
        reply.type = X_Reply; reply.sequenceNumber = sequence; reply.format = icc->format; reply.propertyType = icc->type;
        constexpr u32 kAnyPropertyType = 0;
        if (r->type != kAnyPropertyType && r->type != icc->type) {   // tipe tidak cocok: hanya laporkan ukuran
            reply.bytesAfter = data.size(); Write(&reply, sizeof(reply)); Flush(); return;
        }
        const u64 offset = u64(r->longOffset) * 4;
        if (offset > data.size()) throw BadValueError(r->longOffset);
        const u64 length = std::min<u64>(data.size() - offset, u64(r->longLength) * 4);
        reply.bytesAfter = data.size() - offset - length; reply.nItems = length; reply.length = (length + 3) / 4;
        Write(&reply, sizeof(reply)); Write(data.data() + offset, length); WritePad((-length) & 3); Flush(); return;
    }
    case X_RRGetCrtcGammaSize: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcGammaSizeReq);
        if (GetRequestData<xRRGetCrtcGammaSizeReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRGetCrtcGammaSizeReq>()->crtc);
        xRRGetCrtcGammaSizeReply reply{}; reply.size = 256; send(reply); return;
    }
    case X_RRGetCrtcGamma: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcGammaReq);
        if (GetRequestData<xRRGetCrtcGammaReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRGetCrtcGammaReq>()->crtc);
        xRRGetCrtcGammaReply reply{}; reply.size = 256; reply.length = 384;
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        u16 values[256]; for (u32 i = 0; i < 256; ++i) values[i] = i * 257;
        Write(&reply, sizeof(reply)); for (int i = 0; i < 3; ++i) Write(values, sizeof(values)); Flush(); return;
    }
    case X_RRSetCrtcGamma: CHECK_REQUEST_SIZE(xRRSetCrtcGammaReq); return;
    // RandR 1.3: output utama dan panning (tidak ada panning).
    case X_RRSetOutputPrimary: {
        CHECK_REQUEST_SIZE_STRICT(xRRSetOutputPrimaryReq);
        auto* r = GetRequestData<xRRSetOutputPrimaryReq>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        if (r->output && r->output != output) throw bad(BadRROutput, r->output);
        return;   // satu-satunya output sudah primary
    }
    case X_RRGetOutputPrimary: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetOutputPrimaryReq);
        xRRGetOutputPrimaryReply reply{}; reply.output = output; send(reply); return;
    }
    case X_RRGetPanning: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetPanningReq);
        if (GetRequestData<xRRGetPanningReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRGetPanningReq>()->crtc);
        xRRGetPanningReply reply{}; reply.status = RRSetConfigSuccess; reply.length = (sizeof(reply) - 32) / 4;
        reply.timestamp = timestamp; send(reply); return;
    }
    case X_RRSetPanning: {
        CHECK_REQUEST_SIZE_STRICT(xRRSetPanningReq);
        auto* r = GetRequestData<xRRSetPanningReq>();
        if (r->crtc != crtc) throw bad(BadRRCrtc, r->crtc);
        // Panning tidak didukung: area nol (matikan panning) berhasil, area apa pun lainnya gagal.
        const bool any = r->left || r->top || r->width || r->height || r->track_left || r->track_top ||
                         r->track_width || r->track_height || r->border_left || r->border_top || r->border_right || r->border_bottom;
        xRRSetPanningReply reply{}; reply.status = any ? RRSetConfigFailed : RRSetConfigSuccess; reply.newTimestamp = timestamp;
        send(reply); return;
    }
    // RandR 1.3: transform CRTC. SDL3 (PPSSPP, dll) memanggil RRGetCrtcTransform (minor 27) saat init
    // video; kalau dijawab BadRequest, handler error Xlib bawaan langsung exit() dan layar jadi hitam.
    // Server ini tidak punya transform, jadi jawab matriks identitas (16.16 fixed point) tanpa filter.
    case X_RRGetCrtcTransform: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetCrtcTransformReq);
        if (GetRequestData<xRRGetCrtcTransformReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRGetCrtcTransformReq>()->crtc);
        xRRGetCrtcTransformReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.length = (sizeof(reply) - 32) / 4;   // tanpa nama filter dan parameter
        reply.pendingTransform.matrix11 = reply.pendingTransform.matrix22 = reply.pendingTransform.matrix33 = 0x10000;
        reply.currentTransform.matrix11 = reply.currentTransform.matrix22 = reply.currentTransform.matrix33 = 0x10000;
        reply.hasTransforms = 0;
        WriteImmediately(&reply, sizeof(reply)); return;
    }
    case X_RRSetCrtcTransform: {
        CHECK_REQUEST_SIZE(xRRSetCrtcTransformReq);
        if (GetRequestData<xRRSetCrtcTransformReq>()->crtc != crtc) throw bad(BadRRCrtc, GetRequestData<xRRSetCrtcTransformReq>()->crtc);
        return;   // transform tidak didukung: abaikan (tetap identitas)
    }
    // RandR 1.4: provider. Server ini hanya punya satu GPU virtual, jadi daftar provider kosong.
    // Klien 1.4+ (xrandr, mutter, Qt) memanggil RRGetProviders saat init; BadRequest bisa membuat mereka exit().
    case X_RRGetProviders: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetProvidersReq);
        xRRGetProvidersReply reply{}; reply.timestamp = timestamp; reply.nProviders = 0;
        send(reply); return;
    }
    // RandR 1.5: monitor. Satu monitor otomatis (primary) yang menutupi seluruh layar virtual.
    case X_RRGetMonitors: {
        CHECK_REQUEST_SIZE_STRICT(xRRGetMonitorsReq);
        xRRMonitorInfo info{};
        info.name = Atom::Intern("Ludashi"); info.primary = 1; info.automatic = 1; info.noutput = 1;
        info.x = 0; info.y = 0; info.width = screen.width; info.height = screen.height;
        info.widthInMillimeters = screen.GetWidthInMillimeters(); info.heightInMillimeters = screen.GetHeightInMillimeters();
        xRRGetMonitorsReply reply{};
        reply.type = X_Reply; reply.sequenceNumber = sequence;
        reply.length = (sizeof(info) + sizeof(output)) / 4;
        reply.timestamp = timestamp; reply.nmonitors = 1; reply.noutputs = 1;
        Write(&reply, sizeof(reply)); Write(&info, sizeof(info)); Write(&output, 4); Flush(); return;
    }
    // Layout monitor tetap (otomatis); monitor buatan klien tidak disimpan. Request divalidasi lalu diabaikan.
    case X_RRSetMonitor: {
        CHECK_REQUEST_SIZE(xRRSetMonitorReq);
        auto* r = GetRequestData<xRRSetMonitorReq>();
        if (!Atom::IsValid(r->monitor.name)) throw BadAtomError(r->monitor.name);
        return;
    }
    case X_RRDeleteMonitor: {
        CHECK_REQUEST_SIZE_STRICT(xRRDeleteMonitorReq);
        auto* r = GetRequestData<xRRDeleteMonitorReq>();
        if (!Atom::IsValid(r->name)) throw BadAtomError(r->name);
        // Monitor otomatis tidak bisa dihapus dan tidak ada monitor buatan klien: BadValue sesuai spesifikasi.
        throw BadValueError(r->name);
    }
    // RandR 1.2: properti output. Satu-satunya properti yang ada adalah _ICC_PROFILE (hanya baca); properti lain tidak
    // disimpan, jadi Configure/Change/Delete divalidasi lalu diabaikan (xrandr --set tidak lagi memicu BadRequest).
    case X_RRQueryOutputProperty: {
        CHECK_REQUEST_SIZE_STRICT(xRRQueryOutputPropertyReq);
        auto* r = GetRequestData<xRRQueryOutputPropertyReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        const Property* icc = GetIccProfileProperty(server);
        if (!icc || r->property != icc->name) throw BadNameError(r->property);
        xRRQueryOutputPropertyReply reply{}; reply.immutable = 1; send(reply); return;
    }
    case X_RRConfigureOutputProperty: {
        CHECK_REQUEST_SIZE(xRRConfigureOutputPropertyReq);
        auto* r = GetRequestData<xRRConfigureOutputPropertyReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        return;
    }
    case X_RRChangeOutputProperty: {
        CHECK_REQUEST_SIZE(xRRChangeOutputPropertyReq);
        auto* r = GetRequestData<xRRChangeOutputPropertyReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        return;
    }
    case X_RRDeleteOutputProperty: {
        CHECK_REQUEST_SIZE_STRICT(xRRDeleteOutputPropertyReq);
        auto* r = GetRequestData<xRRDeleteOutputPropertyReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        return;
    }
    // RandR 1.2: mode. Mode server ini tetap: satu per laju segar yang dilaporkan layar Android, tidak bisa dibuat atau dihapus.
    case X_RRCreateMode: {
        CHECK_REQUEST_SIZE(xRRCreateModeReq);
        auto* r = GetRequestData<xRRCreateModeReq>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        throw BadAccessError();
    }
    case X_RRDestroyMode: {
        CHECK_REQUEST_SIZE_STRICT(xRRDestroyModeReq);
        const u32 m = GetRequestData<xRRDestroyModeReq>()->mode;
        if (m < modeBase || m - modeBase >= rates.size()) throw bad(BadRRMode, m);
        throw BadAccessError();
    }
    case X_RRAddOutputMode: {
        CHECK_REQUEST_SIZE_STRICT(xRRAddOutputModeReq);
        auto* r = GetRequestData<xRRAddOutputModeReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        if (r->mode < modeBase || r->mode - modeBase >= rates.size()) throw bad(BadRRMode, r->mode);
        return;   // semua mode sudah terpasang di output
    }
    case X_RRDeleteOutputMode: {
        CHECK_REQUEST_SIZE_STRICT(xRRDeleteOutputModeReq);
        auto* r = GetRequestData<xRRDeleteOutputModeReq>();
        if (r->output != output) throw bad(BadRROutput, r->output);
        if (r->mode < modeBase || r->mode - modeBase >= rates.size()) throw bad(BadRRMode, r->mode);
        throw BadAccessError();   // mode tetap, tidak bisa dilepas dari output
    }
    // RandR 1.4: daftar provider kosong, jadi setiap id provider yang diberikan klien tidak valid.
    case X_RRGetProviderInfo:
    case X_RRSetProviderOffloadSink:
    case X_RRSetProviderOutputSource:
    case X_RRListProviderProperties:
    case X_RRQueryProviderProperty:
    case X_RRConfigureProviderProperty:
    case X_RRChangeProviderProperty:
    case X_RRDeleteProviderProperty:
    case X_RRGetProviderProperty: {
        if (request_size < sizeof(xReq) + 4) throw BadLengthError();
        throw bad(BadRRProvider, *reinterpret_cast<const u32*>(req + 1));
    }
    // RandR 1.6: lease. Lease menyerahkan CRTC/output ke klien lewat fd DRM; server ini tidak punya perangkat DRM dan hanya
    // punya satu CRTC + output yang selalu dipakai tampilan utama, jadi lease tidak pernah bisa dibuat. Request tetap
    // divalidasi penuh (panjang, window, crtc, output) supaya klien VR/Wayland mendapat galat protokol yang benar.
    case X_RRCreateLease: {
        CHECK_REQUEST_SIZE(xRRCreateLeaseReq);
        auto* r = GetRequestData<xRRCreateLeaseReq>();
        if (!GetResourceAs<Window>(r->window)) throw BadWindowError(r->window);
        if (request_size != sizeof(xRRCreateLeaseReq) + (size_t(r->nCrtcs) + r->nOutputs) * 4) throw BadLengthError();
        const u32* ids = reinterpret_cast<const u32*>(r + 1);
        for (u32 i = 0; i < r->nCrtcs; ++i) if (ids[i] != crtc) throw bad(BadRRCrtc, ids[i]);
        for (u32 i = 0; i < r->nOutputs; ++i) if (ids[r->nCrtcs + i] != output) throw bad(BadRROutput, ids[r->nCrtcs + i]);
        throw BadMatchError();
    }
    case X_RRFreeLease: {
        CHECK_REQUEST_SIZE_STRICT(xRRFreeLeaseReq);
        throw bad(BadRRLease, GetRequestData<xRRFreeLeaseReq>()->lid);   // tidak ada lease yang pernah dibuat
    }
    default: throw BadRequestError();
    }
}
}
