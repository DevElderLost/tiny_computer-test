#include "core/xkb_proto.h"
#include <algorithm>
#include <cstdio>
#include <cstring>

namespace xorg::xkb {
namespace {

// ---- konstanta protokol XKB ----
enum Minor : u8 {
    kUseExtension = 0, kSelectEvents = 1, kBell = 3, kGetState = 4, kLatchLockState = 5, kGetControls = 6,
    kSetControls = 7, kGetMap = 8, kSetMap = 9, kGetCompatMap = 10, kSetCompatMap = 11, kGetIndicatorState = 12,
    kGetIndicatorMap = 13, kSetIndicatorMap = 14, kGetNamedIndicator = 15, kSetNamedIndicator = 16, kGetNames = 17,
    kSetNames = 18, kGetGeometry = 19, kSetGeometry = 20, kPerClientFlags = 21, kListComponents = 22,
    kGetKbdByName = 23, kGetDeviceInfo = 24, kSetDeviceInfo = 25, kSetDebuggingFlags = 101,
};

// MapPart
enum : u16 { kPartTypes = 1, kPartSyms = 2, kPartModMap = 4, kPartExplicit = 8, kPartActions = 16,
             kPartBehaviors = 32, kPartVMods = 64, kPartVModMap = 128 };

constexpr int kNumTypes = 4;  // ONE_LEVEL, TWO_LEVEL, ALPHABETIC, KEYPAD (urutan kanonis XKB)
enum : u8 { kOneLevel = 0, kTwoLevel = 1, kAlphabetic = 2, kKeypad = 3 };
constexpr u8 kLevels[kNumTypes] = {1, 2, 2, 2};

constexpr u8 kModShift = 1, kModLock = 2, kModControl = 4, kModMod1 = 8, kModMod2 = 16, kModMod4 = 64;

constexpr u8 kSaSetMods = 1, kSaLockMods = 3;
constexpr u8 kSaClearLocks = 1, kSaUseModMapMods = 4;

constexpr int kNumLeds = 3;  // Caps Lock, Num Lock, Scroll Lock
constexpr u32 kLedMask = 0x7;

u16 Rd16(const u8* p, size_t off) { u16 v; memcpy(&v, p + off, 2); return v; }
u32 Rd32(const u8* p, size_t off) { u32 v; memcpy(&v, p + off, 4); return v; }
size_t Pad4(size_t n) { return (n + 3) & ~size_t(3); }
int PopCount(u32 v) { return __builtin_popcount(v); }

// Penyusun balasan: header 32 byte lebih dulu, lalu data tambahan; panjang (kata 4 byte) diisi saat Finish().
struct Out {
    std::vector<u8> b;
    Out(u16 seq, u8 data1 = kCoreKbdDeviceId) : b(32, 0) {
        b[0] = 1;  // X_Reply
        b[1] = data1;
        memcpy(&b[2], &seq, 2);
    }
    void Put8(size_t off, u8 v) { b[off] = v; }
    void Put16(size_t off, u16 v) { memcpy(&b[off], &v, 2); }
    void Put32(size_t off, u32 v) { memcpy(&b[off], &v, 4); }
    void Add8(u8 v) { b.push_back(v); }
    void Add16(u16 v) { b.resize(b.size() + 2); memcpy(&b[b.size() - 2], &v, 2); }
    void Add32(u32 v) { b.resize(b.size() + 4); memcpy(&b[b.size() - 4], &v, 4); }
    void AlignTo4() { b.resize(Pad4(b.size()), 0); }
    Result Finish() {
        AlignTo4();
        const u32 words = static_cast<u32>((b.size() - 32) / 4);
        memcpy(&b[4], &words, 4);
        Result r; r.status = Status::Reply; r.bytes = std::move(b);
        return r;
    }
};

Result Fail(Status s, u32 data = 0) { Result r; r.status = s; r.data = data; return r; }
bool DeviceOk(u16 spec) { return spec == kUseCoreKbd || spec == kCoreKbdDeviceId; }

// Nama tombol evdev (keycode XKB = evdev + 8).
const char* EvdevName(unsigned kc) {
    static const char* const t[] = {
        /*  8 */ nullptr, "ESC", "AE01", "AE02", "AE03", "AE04", "AE05", "AE06", "AE07", "AE08", "AE09", "AE10",
        /* 20 */ "AE11", "AE12", "BKSP", "TAB", "AD01", "AD02", "AD03", "AD04", "AD05", "AD06", "AD07", "AD08",
        /* 32 */ "AD09", "AD10", "AD11", "AD12", "RTRN", "LCTL", "AC01", "AC02", "AC03", "AC04", "AC05", "AC06",
        /* 44 */ "AC07", "AC08", "AC09", "AC10", "AC11", "TLDE", "LFSH", "BKSL", "AB01", "AB02", "AB03", "AB04",
        /* 56 */ "AB05", "AB06", "AB07", "AB08", "AB09", "AB10", "RTSH", "KPMU", "LALT", "SPCE", "CAPS", "FK01",
        /* 68 */ "FK02", "FK03", "FK04", "FK05", "FK06", "FK07", "FK08", "FK09", "FK10", "NMLK", "SCLK", "KP7",
        /* 80 */ "KP8", "KP9", "KPSU", "KP4", "KP5", "KP6", "KPAD", "KP1", "KP2", "KP3", "KP0", "KPDL",
        /* 92 */ nullptr, nullptr, "LSGT", "FK11", "FK12", nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
        /*104 */ "KPEN", "RCTL", "KPDV", "PRSC", "RALT", "LNFD", "HOME", "UP", "PGUP", "LEFT", "RGHT", "END",
        /*116 */ "DOWN", "PGDN", "INS", "DELE",
    };
    if (kc >= 8 && kc - 8 < sizeof(t) / sizeof(t[0])) return t[kc - 8];
    if (kc == 133) return "LWIN";
    if (kc == 134) return "RWIN";
    return nullptr;
}

u8 ModmapFor(unsigned kc) {
    switch (kc) {
        case 50: case 62: return kModShift;
        case 66: return kModLock;
        case 37: case 105: return kModControl;
        case 64: case 108: return kModMod1;
        case 77: return kModMod2;
        case 133: case 134: return kModMod4;
        default: return 0;
    }
}

// Simbol yang tidak ada di tabel keysym inti (Keyboard::MapKeycodeToKeysym) tetapi perlu agar modifier dan kunci kunci
// dikenali oleh pustaka XKB. Hanya dipakai di tampilan XKB; request inti GetKeyboardMapping tidak berubah.
u32 OverlaySym(unsigned kc) {
    switch (kc) {
        case 66: return 0xffe5;   // Caps_Lock
        case 77: return 0xff7f;   // Num_Lock
        case 78: return 0xff14;   // Scroll_Lock
        case 104: return 0xff8d;  // KP_Enter
        case 107: return 0xff61;  // Print
        case 133: return 0xffeb;  // Super_L
        case 134: return 0xffec;  // Super_R
        default: return 0;
    }
}

}  // namespace

Keymap::Keymap(u8 minKey, u8 maxKey, u8 perKey, const std::vector<u32>& keysyms, InternFn intern)
    : minKey_(minKey), maxKey_(maxKey), keys_(size_t(maxKey) - minKey + 1) {
    for (unsigned kc = minKey; kc <= maxKey; ++kc) {
        Key& k = keys_[kc - minKey];
        u32 s0 = 0, s1 = 0;
        const size_t base = size_t(kc - minKey) * perKey;
        if (perKey > 0 && base < keysyms.size()) s0 = keysyms[base];
        if (perKey > 1 && base + 1 < keysyms.size()) s1 = keysyms[base + 1];
        if (!s0 && !s1) s0 = OverlaySym(kc);
        k.modmap = ModmapFor(kc);
        if (!s0 && !s1) {
            k.nSyms = 0; k.type = kOneLevel;
        } else if (!s1 || s1 == s0) {
            k.nSyms = 1; k.sym[0] = s0; k.type = kOneLevel;
        } else {
            k.nSyms = 2; k.sym[0] = s0; k.sym[1] = s1;
            if (s0 >= 'a' && s0 <= 'z' && s1 == s0 - 32) k.type = kAlphabetic;
            else if (s0 >= 0xff80 && s0 <= 0xffbd) k.type = kKeypad;
            else k.type = kTwoLevel;
        }
        if (k.modmap && k.nSyms) {
            if (k.modmap == kModLock || k.modmap == kModMod2) { k.action = 2; k.lockMask = k.modmap; }
            else k.action = 1;
        }
        if (const char* n = EvdevName(kc)) memcpy(k.name, n, std::min<size_t>(4, strlen(n)));
        else snprintf(k.name, 5, "I%03u", kc);  // "I008": tidak ada nama evdev
    }
    atoms_.keycodes = intern("evdev+aliases(qwerty)");
    atoms_.geometry = intern("pc(pc105)");
    atoms_.symbols = intern("pc+us+inet(evdev)");
    atoms_.types = intern("complete");
    atoms_.compat = intern("complete");
    atoms_.group = intern("English (US)");
    atoms_.devType = intern("KEYBOARD");
    const char* const typeNames[kNumTypes] = {"ONE_LEVEL", "TWO_LEVEL", "ALPHABETIC", "KEYPAD"};
    const char* const levelNames[kNumTypes][2] = {{"Any", nullptr}, {"Base", "Shift"}, {"Base", "Caps"}, {"Base", "Number"}};
    for (int t = 0; t < kNumTypes; ++t) {
        atoms_.typeNames[t] = intern(typeNames[t]);
        for (int l = 0; l < kLevels[t]; ++l) atoms_.levelNames[t][l] = intern(levelNames[t][l]);
    }
    atoms_.leds[0] = intern("Caps Lock");
    atoms_.leds[1] = intern("Num Lock");
    atoms_.leds[2] = intern("Scroll Lock");
}

Result Keymap::Handle(u8 minor, const u8* r, size_t len, u16 seq, u8 mods) const {
    if (len < 4) return Fail(Status::BadLength);
    switch (minor) {
    case kUseExtension: {
        if (len < 8) return Fail(Status::BadLength);
        Out o(seq, 1);  // byte data = supported
        o.Put8(1, Rd16(r, 4) == 1 ? 1 : 0);
        o.Put16(8, 1); o.Put16(10, 0);  // versi server 1.0
        return o.Finish();
    }
    case kSelectEvents:
        if (len < 12) return Fail(Status::BadLength);
        return {};  // tidak ada event XKB yang dikirim server; topeng diterima begitu saja
    case kBell: case kLatchLockState: case kSetControls: case kSetMap: case kSetCompatMap: case kSetIndicatorMap:
    case kSetNamedIndicator: case kSetNames: case kSetGeometry: case kSetDeviceInfo:
        return {};  // keymap bersifat tetap: perubahan diabaikan
    case kGetState: return GetState(r, len, seq, mods);
    case kGetControls: return GetControls(r, len, seq);
    case kGetMap: return GetMap(r, len, seq);
    case kGetCompatMap: return GetCompatMap(r, len, seq);
    case kGetIndicatorState: {
        if (len < 8) return Fail(Status::BadLength);
        if (!DeviceOk(Rd16(r, 4))) return Fail(Status::BadKeyboard, Rd16(r, 4));
        Out o(seq);
        o.Put32(8, (mods & kModLock ? 1u : 0u) | (mods & kModMod2 ? 2u : 0u));
        return o.Finish();
    }
    case kGetIndicatorMap: return GetIndicatorMap(r, len, seq);
    case kGetNamedIndicator: return GetNamedIndicator(r, len, seq);
    case kGetNames: return GetNames(r, len, seq);
    case kPerClientFlags: {
        if (len < 28) return Fail(Status::BadLength);
        if (!DeviceOk(Rd16(r, 4))) return Fail(Status::BadKeyboard, Rd16(r, 4));
        const u32 change = Rd32(r, 8), value = Rd32(r, 12);
        constexpr u32 kSupported = 0x1f;  // DetectableAutoRepeat, GrabsUseXKBState, AutoResetControls, LookupStateWhenGrabbed, SendEventUsesXKBState
        Out o(seq);
        o.Put32(8, kSupported);
        o.Put32(12, value & change & kSupported);
        return o.Finish();
    }
    case kListComponents: {
        if (len < 8) return Fail(Status::BadLength);
        if (!DeviceOk(Rd16(r, 4))) return Fail(Status::BadKeyboard, Rd16(r, 4));
        Out o(seq);  // semua daftar kosong
        return o.Finish();
    }
    case kGetDeviceInfo: return GetDeviceInfo(r, len, seq);
    case kGetGeometry: case kGetKbdByName:
        return Fail(Status::BadImplementation);
    case kSetDebuggingFlags: {
        Out o(seq);  // balasan kosong (tidak ada flag debug)
        return o.Finish();
    }
    default:
        return Fail(Status::BadRequest);
    }
}

Result Keymap::GetState(const u8* r, size_t len, u16 seq, u8 mods) const {
    if (len < 8) return Fail(Status::BadLength);
    if (!DeviceOk(Rd16(r, 4))) return Fail(Status::BadKeyboard, Rd16(r, 4));
    constexpr u8 kLocks = kModLock | kModMod2;  // status Caps/Num Lock bersifat "terkunci"
    Out o(seq);
    o.Put8(8, mods);                  // mods (efektif)
    o.Put8(9, mods & ~kLocks);        // baseMods
    o.Put8(10, 0);                    // latchedMods
    o.Put8(11, mods & kLocks);        // lockedMods
    o.Put8(12, 0); o.Put8(13, 0);     // group, lockedGroup
    o.Put16(14, 0); o.Put16(16, 0);   // baseGroup, latchedGroup
    o.Put8(18, mods);                 // compatState
    o.Put8(19, mods);                 // grabMods
    o.Put8(20, mods);                 // compatGrabMods
    o.Put8(21, mods);                 // lookupMods
    o.Put8(22, mods);                 // compatLookupMods
    o.Put16(24, 0);                   // ptrBtnState
    return o.Finish();
}

Result Keymap::GetControls(const u8* r, size_t len, u16 seq) const {
    if (len < 8) return Fail(Status::BadLength);
    if (!DeviceOk(Rd16(r, 4))) return Fail(Status::BadKeyboard, Rd16(r, 4));
    Out o(seq);
    o.b.resize(92, 0);
    o.Put8(8, 0);        // mouseKeysDfltBtn
    o.Put8(9, 1);        // numGroups
    o.Put8(10, 0);       // groupsWrap
    o.Put8(11, 0);       // internalModsMask
    o.Put8(12, kModLock | kModMod2);  // ignoreLockModsMask
    o.Put8(13, 0);
    o.Put8(14, kModLock | kModMod2);  // ignoreLockModsRealMods
    o.Put16(20, 660);    // repeatDelay (ms)
    o.Put16(22, 40);     // repeatInterval (ms)
    o.Put16(24, 0); o.Put16(26, 0); o.Put16(28, 0); o.Put16(30, 0);
    o.Put16(32, 0); o.Put16(34, 0); o.Put16(36, 0); o.Put16(38, 0);
    o.Put32(56, 1u | (1u << 9));  // enabledControls: RepeatKeys | AudibleBell
    memset(&o.b[60], 0xff, 32);    // perKeyRepeat: semua tombol boleh berulang
    return o.Finish();
}

Result Keymap::GetMap(const u8* r, size_t len, u16 seq) const {
    if (len < 28) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    const u16 full = Rd16(r, 6), partial = Rd16(r, 8);
    const u8 firstType = r[10], nTypes = r[11], firstSym = r[12], nSyms = r[13], firstAct = r[14], nActs = r[15],
             firstBeh = r[16], nBeh = r[17], firstExp = r[20], nExp = r[21], firstMod = r[22], nMod = r[23],
             firstVMod = r[24], nVMod = r[25];
    (void)firstBeh; (void)nBeh; (void)firstExp; (void)nExp; (void)firstVMod; (void)nVMod;
    const u16 present = (full | partial) & 0xff;

    // Rentang yang dikembalikan untuk satu bagian. false = rentang tidak sah (BadValue).
    auto range = [&](u16 part, unsigned first, unsigned n, unsigned lo, unsigned hi, unsigned& f, unsigned& c) {
        if (full & part) { f = lo; c = hi - lo + 1; return true; }
        if (!(partial & part) || n == 0) { f = lo; c = 0; return true; }
        if (first < lo || first + n - 1 > hi) return false;
        f = first; c = n; return true;
    };
    unsigned fT, cT, fS, cS, fA, cA, fM, cM;
    if (!range(kPartTypes, firstType, nTypes, 0, kNumTypes - 1, fT, cT)) return Fail(Status::BadValue, firstType);
    if (!range(kPartSyms, firstSym, nSyms, minKey_, maxKey_, fS, cS)) return Fail(Status::BadValue, firstSym);
    if (!range(kPartActions, firstAct, nActs, minKey_, maxKey_, fA, cA)) return Fail(Status::BadValue, firstAct);
    if (!range(kPartModMap, firstMod, nMod, minKey_, maxKey_, fM, cM)) return Fail(Status::BadValue, firstMod);

    Out o(seq);
    o.b.resize(40, 0);
    o.Put8(10, minKey_); o.Put8(11, maxKey_);
    o.Put16(12, present);

    // --- tipe tombol ---
    o.Put8(14, static_cast<u8>(fT)); o.Put8(15, static_cast<u8>(cT)); o.Put8(16, kNumTypes);
    for (unsigned t = fT; t < fT + cT; ++t) {
        u8 mask = 0; u8 nEntries = 0;
        u8 entryMods[2] = {0, 0};
        switch (t) {
            case kTwoLevel: mask = kModShift; nEntries = 1; entryMods[0] = kModShift; break;
            case kAlphabetic: mask = kModShift | kModLock; nEntries = 2; entryMods[0] = kModShift; entryMods[1] = kModLock; break;
            case kKeypad: mask = kModShift | kModMod2; nEntries = 2; entryMods[0] = kModShift; entryMods[1] = kModMod2; break;
            default: break;
        }
        o.Add8(mask); o.Add8(mask); o.Add16(0);       // mods_mask, mods_mods, mods_vmods
        o.Add8(kLevels[t]); o.Add8(nEntries); o.Add8(0); o.Add8(0);  // numLevels, nMapEntries, hasPreserve, pad
        for (int e = 0; e < nEntries; ++e) {
            o.Add8(1); o.Add8(entryMods[e]); o.Add8(1); o.Add8(entryMods[e]); o.Add16(0); o.Add16(0);  // aktif, mask, level, mods
        }
    }

    // --- simbol tombol ---
    unsigned totalSyms = 0;
    o.Put8(17, static_cast<u8>(fS)); o.Put8(20, static_cast<u8>(cS));
    for (unsigned kc = fS; kc < fS + cS; ++kc) {
        const Key& k = keys_[kc - minKey_];
        o.Add8(k.type); o.Add8(0); o.Add8(0); o.Add8(0);   // kt_index[4]
        o.Add8(k.nSyms ? 1 : 0);                           // groupInfo: jumlah grup
        o.Add8(k.nSyms ? (k.type == kOneLevel ? 1 : 2) : 0);  // width
        o.Add16(k.nSyms);
        for (int i = 0; i < k.nSyms; ++i) o.Add32(k.sym[i]);
        totalSyms += k.nSyms;
    }
    o.Put16(18, static_cast<u16>(totalSyms));

    // --- aksi tombol: satu aksi untuk tiap tombol modifier ---
    unsigned totalActs = 0;
    if (present & kPartActions) {
        o.Put8(21, static_cast<u8>(fA)); o.Put8(24, static_cast<u8>(cA));
        for (unsigned kc = fA; kc < fA + cA; ++kc) o.Add8(keys_[kc - minKey_].action ? 1 : 0);
        o.AlignTo4();
        for (unsigned kc = fA; kc < fA + cA; ++kc) {
            const Key& k = keys_[kc - minKey_];
            if (!k.action) continue;
            ++totalActs;
            if (k.action == 1) {  // SetMods(modifiers=modMapMods, clearLocks)
                // Server X asli menyelesaikan modmap saat kompilasi keymap: mask dan realMods berisi modifier tombol ini.
                const u8 a[8] = {kSaSetMods, u8(kSaUseModMapMods | kSaClearLocks), k.modmap, k.modmap, 0, 0, 0, 0};
                o.b.insert(o.b.end(), a, a + 8);
            } else {              // LockMods(modifiers=Lock / Mod2)
                const u8 a[8] = {kSaLockMods, 0, k.lockMask, k.lockMask, 0, 0, 0, 0};
                o.b.insert(o.b.end(), a, a + 8);
            }
        }
        o.Put16(22, static_cast<u16>(totalActs));
    }
    // --- behavior, vmods, explicit: tidak ada (0 entri) ---
    // --- modmap tombol ---
    unsigned totalMod = 0;
    if (present & kPartModMap) {
        o.Put8(31, static_cast<u8>(fM));
        for (unsigned kc = fM; kc < fM + cM; ++kc) {
            const u8 m = keys_[kc - minKey_].modmap;
            if (!m) continue;
            o.Add8(static_cast<u8>(kc)); o.Add8(m); ++totalMod;
        }
        o.AlignTo4();
        o.Put8(32, static_cast<u8>(cM)); o.Put8(33, static_cast<u8>(totalMod));
    }
    return o.Finish();
}

Result Keymap::GetNames(const u8* r, size_t len, u16 seq) const {
    if (len < 12) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    const u32 which = Rd32(r, 8) & 0x3fff;
    enum : u32 { kKeycodes = 1, kGeometry = 2, kSymbols = 4, kPhysSymbols = 8, kTypes = 16, kCompat = 32, kTypeNames = 64,
                 kKtLevels = 128, kLedNames = 256, kKeyNames = 512, kAliases = 1024, kVModNames = 2048,
                 kGroupNames = 4096, kRgNames = 8192 };
    unsigned nKtLevels = 0;
    for (int t = 0; t < kNumTypes; ++t) nKtLevels += kLevels[t];
    Out o(seq);
    o.Put32(8, which);
    o.Put8(12, minKey_); o.Put8(13, maxKey_);
    o.Put8(14, kNumTypes);
    o.Put8(15, 1);                 // groupNames: satu grup
    o.Put16(16, 0);                // virtualMods: tidak ada
    o.Put8(18, minKey_); o.Put8(19, static_cast<u8>(keys_.size()));
    o.Put32(20, kLedMask);         // indicators
    o.Put8(24, 0); o.Put8(25, 0);  // nRadioGroups, nKeyAliases
    o.Put16(26, static_cast<u16>(nKtLevels));
    if (which & kKeycodes) o.Add32(atoms_.keycodes);
    if (which & kGeometry) o.Add32(atoms_.geometry);
    if (which & kSymbols) o.Add32(atoms_.symbols);
    if (which & kPhysSymbols) o.Add32(atoms_.symbols);
    if (which & kTypes) o.Add32(atoms_.types);
    if (which & kCompat) o.Add32(atoms_.compat);
    if (which & kTypeNames) for (int t = 0; t < kNumTypes; ++t) o.Add32(atoms_.typeNames[t]);
    if (which & kKtLevels) {
        for (int t = 0; t < kNumTypes; ++t) o.Add8(kLevels[t]);
        o.AlignTo4();
        for (int t = 0; t < kNumTypes; ++t) for (int l = 0; l < kLevels[t]; ++l) o.Add32(atoms_.levelNames[t][l]);
    }
    if (which & kLedNames) for (int i = 0; i < kNumLeds; ++i) o.Add32(atoms_.leds[i]);
    // kVModNames: virtualMods = 0 -> kosong
    if (which & kGroupNames) o.Add32(atoms_.group);
    if (which & kKeyNames) for (const Key& k : keys_) o.b.insert(o.b.end(), k.name, k.name + 4);
    // kAliases, kRgNames: kosong
    (void)kAliases; (void)kVModNames; (void)kRgNames;
    return o.Finish();
}

Result Keymap::GetCompatMap(const u8* r, size_t len, u16 seq) const {
    if (len < 12) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    const u8 groups = r[6] & 0x0f;
    const bool all = r[7] != 0;
    const unsigned firstSI = Rd16(r, 8), nSI = Rd16(r, 10);
    // Aksi tombol sudah dikirim langsung lewat GetMap, jadi tabel interpretasi hanya berisi satu entri "tanpa aksi"
    // (Any+AnyOfOrNone(none)). Satu entri dipertahankan karena Xlib memperlakukan balasan berpanjang 0 sebagai BadAlloc.
    constexpr unsigned kTotalSI = 1;
    unsigned first = 0, n = kTotalSI;
    if (!all) {
        if (nSI > 0 && firstSI + nSI > kTotalSI) return Fail(Status::BadValue, firstSI);
        first = nSI ? firstSI : 0; n = nSI;
    }
    Out o(seq);
    o.Put8(8, groups);
    o.Put16(10, static_cast<u16>(first)); o.Put16(12, static_cast<u16>(n)); o.Put16(14, kTotalSI);
    for (unsigned i = 0; i < n; ++i) {
        o.Add32(0);   // sym = NoSymbol (cocok untuk semua simbol)
        o.Add8(0);    // mods
        o.Add8(1);    // match = AnyOfOrNone
        o.Add8(0xff); // virtualMod = tidak ada
        o.Add8(0);    // flags
        for (int j = 0; j < 8; ++j) o.Add8(0);  // action = NoAction
    }
    for (int g = 0; g < 4; ++g)
        if (groups & (1 << g)) o.Add32(0);  // peta modifier grup: kosong
    return o.Finish();
}

Result Keymap::GetIndicatorMap(const u8* r, size_t len, u16 seq) const {
    if (len < 12) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    const u32 which = Rd32(r, 8);
    Out o(seq);
    o.Put32(8, which);
    o.Put32(12, kLedMask);  // realIndicators
    o.Put8(16, static_cast<u8>(PopCount(which)));
    for (int bit = 0; bit < 32; ++bit) {
        if (!(which & (1u << bit))) continue;
        u8 map[12] = {};
        constexpr u8 kUseLocked = 4;
        if (bit == 0) { map[3] = kUseLocked; map[4] = kModLock; map[5] = kModLock; }    // Caps Lock
        if (bit == 1) { map[3] = kUseLocked; map[4] = kModMod2; map[5] = kModMod2; }    // Num Lock
        o.b.insert(o.b.end(), map, map + 12);
    }
    return o.Finish();
}

Result Keymap::GetNamedIndicator(const u8* r, size_t len, u16 seq) const {
    if (len < 16) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    const u32 atom = Rd32(r, 12);
    Out o(seq);
    o.Put32(8, atom);
    for (int i = 0; i < kNumLeds; ++i) {
        if (atoms_.leds[i] != atom) continue;
        o.Put8(12, 1);          // found
        o.Put8(14, 1);          // realIndicator
        o.Put8(15, static_cast<u8>(i));  // ndx
        if (i < 2) {
            const u8 m = i == 0 ? kModLock : kModMod2;
            o.Put8(19, 4); o.Put8(20, m); o.Put8(21, m);  // whichMods = UseLocked
        }
        o.Put8(28, 1);          // supported
        break;
    }
    return o.Finish();
}

Result Keymap::GetDeviceInfo(const u8* r, size_t len, u16 seq) const {
    if (len < 16) return Fail(Status::BadLength);
    const u16 spec = Rd16(r, 4);
    if (!DeviceOk(spec)) return Fail(Status::BadKeyboard, spec);
    static const char kName[] = "Virtual core keyboard";
    const u16 nameLen = sizeof(kName) - 1;
    Out o(seq, kCoreKbdDeviceId);
    o.Put16(10, 0x1f);             // supported: semua fitur XI yang dikenal protokol
    o.Put32(28, atoms_.devType);   // devType
    o.Add16(nameLen);
    o.b.insert(o.b.end(), kName, kName + nameLen);
    return o.Finish();
}

}  // namespace xorg::xkb
