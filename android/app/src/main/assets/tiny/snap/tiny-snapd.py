#!/usr/bin/env python3
# tiny-snapd: pengganti snapd untuk kontainer proot Tiny Computer (tanpa systemd, tanpa mount squashfs, tanpa
# namespace). Satu berkas, tiga peran:
#   * perintah `snap` (CLI): install, remove, list, info, find, refresh, run, ...
#   * daemon REST di soket unix (subset /v2 yang dibaca Ubuntu App Center / snap-store)
#   * pelari snap (`snap run`): menyiapkan env SNAP_* lalu exec aplikasi snap tanpa snap-confine
# "Squashfs palsu": berkas .snap diekstrak dengan unsquashfs ke /snap/<nama>/<rev> (bukan di-mount), jadi snap
# jalan sebagai proses biasa tanpa confinement. Content snap (gnome-42-2204 dst.) disambungkan dengan symlink.
import hashlib, json, os, re, shlex, shutil, signal, socket, socketserver, subprocess, sys, threading, time
import urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler

try:
    import fcntl
except ImportError:  # pragma: no cover
    fcntl = None
try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

VERSION = "2.66-tiny"
HOME = os.path.expanduser("~")
BASE = os.environ.get("TINY_SNAP_DIR", f"{HOME}/.local/share/tiny/snap")
SNAP_ROOT = os.environ.get("TINY_SNAP_ROOT", "/snap")
VAR_SNAP = os.environ.get("TINY_VAR_SNAP", "/var/snap")
SNAPD_LIB = os.environ.get("TINY_SNAPD_LIB", "/var/lib/snapd")
STORE = os.environ.get("TINY_SNAP_STORE", "https://api.snapcraft.io").rstrip("/")
SOCKET = os.environ.get("TINY_SNAPD_SOCKET", f"{BASE}/snapd.socket")
SYS_SOCKET = os.environ.get("TINY_SNAPD_SYS_SOCKET", "/run/snapd.socket")
CLI = os.environ.get("TINY_SNAP_CLI", "/usr/local/bin/snap")
DESKTOP_DIR = os.environ.get("TINY_SNAP_DESKTOP", f"{HOME}/.local/share/applications")
STATE_FILE = f"{BASE}/state.json"
ARCH = "arm64"
UA = {"User-Agent": f"tiny-snapd/{VERSION}", "Snap-Device-Series": "16", "Accept": "application/json"}
INFO_FIELDS = ("base,categories,confinement,contact,description,download,license,media,publisher,"
               "revision,store-url,summary,title,type,version,website,epoch")
FIND_FIELDS = ("base,categories,confinement,contact,description,download,license,media,publisher,"
               "revision,store-url,summary,title,type,version,website")
DAEMON_PLUGS = {"snapd-control", "snap-refresh-control", "snap-themes-control", "snap-refresh-observe"}
CATEGORIES = ["art-and-design", "books-and-reference", "development", "devices-and-iot", "education",
              "entertainment", "featured", "finance", "games", "health-and-fitness", "music-and-audio",
              "news-and-weather", "personalisation", "photo-and-video", "productivity", "science",
              "security", "server-and-cloud", "social", "utilities"]
_TLOCK = threading.RLock()


class SnapError(Exception):
    def __init__(self, message, kind="", status=400):
        super().__init__(message)
        self.kind, self.status = kind, status


# ---------------------------------------------------------------------------------------------- state
class oplock:
    """Kunci antarproses (flock) + antarthread supaya CLI dan daemon tidak saling menimpa state.json. Re-entrant:
    install() memanggil dirinya sendiri untuk content snap, dan flock kedua dari proses yang sama akan buntu."""
    depth = 0

    def __enter__(self):
        _TLOCK.acquire()
        if oplock.depth == 0:
            os.makedirs(BASE, exist_ok=True)
            self.f = open(f"{BASE}/state.lock", "a")
            if fcntl:
                fcntl.flock(self.f, fcntl.LOCK_EX)
        oplock.depth += 1
        return self

    def __exit__(self, *a):
        try:
            oplock.depth -= 1
            if oplock.depth == 0:
                if fcntl:
                    fcntl.flock(self.f, fcntl.LOCK_UN)
                self.f.close()
        finally:
            _TLOCK.release()


def load_state():
    try:
        with open(STATE_FILE) as f:
            d = json.load(f)
        d.setdefault("snaps", {})
        return d
    except (OSError, ValueError):
        return {"snaps": {}}


def save_state(d):
    os.makedirs(BASE, exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(d, f, indent=1)
    os.replace(tmp, STATE_FILE)


def read_yaml(path):
    if yaml is None:
        raise SnapError("python3-yaml belum terpasang: sudo apt install python3-yaml", "no-yaml", 500)
    try:
        with open(path) as f:
            return yaml.safe_load(f) or {}
    except OSError:
        raise SnapError(f"berkas {path} tidak ada", "snap-corrupt", 500)


def snap_dir(name, rev):
    return f"{SNAP_ROOT}/{name}/{rev}"


def expand(s, env):
    """$VAR dan ${VAR} (tanpa operator shell), seperti os.Expand milik snapd; variabel tak dikenal menjadi kosong."""
    if not isinstance(s, str):
        return s
    return re.sub(r"\$(?:\{(\w+)\}|(\w+))", lambda m: env.get(m.group(1) or m.group(2), ""), s)


# ---------------------------------------------------------------------------------------------- store
def store_get(path, params=None):
    url = STORE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise SnapError("snap tidak ditemukan di store", "snap-not-found", 404)
        raise SnapError(f"store menjawab HTTP {e.code}", "store-error", 502)
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise SnapError(f"store tidak terjangkau: {e}", "network-error", 502)


def split_channel(ch):
    ch = ch or "stable"
    parts = ch.split("/")
    if len(parts) == 1:
        return "latest", parts[0]
    return parts[0], parts[1]


def pick_release(info, channel):
    track, risk = split_channel(channel)
    cm = info.get("channel-map") or []
    for e in cm:
        c = e.get("channel") or {}
        if c.get("risk") == risk and c.get("track", "latest") == track and c.get("architecture", ARCH) == ARCH:
            return e
    for e in cm:  # store sudah menyaring arsitektur lewat parameter; terima kecocokan track/risk apa adanya
        c = e.get("channel") or {}
        if c.get("risk") == risk and c.get("track", "latest") == track:
            return e
    raise SnapError(f"tidak ada revisi di channel {track}/{risk} untuk {ARCH}", "snap-channel-not-available", 404)


def store_info(name):
    return store_get(f"/v2/snaps/info/{urllib.parse.quote(name)}", {"fields": INFO_FIELDS, "architecture": ARCH})


def to_snapd(info, entry, status="available"):
    """Satu hasil store (info/find) -> objek snap gaya snapd /v2/find."""
    s = info.get("snap") or {}
    entry = entry or {}
    pub = s.get("publisher") or {}
    dl = entry.get("download") or {}
    ch = entry.get("channel") or {}
    return {
        "id": info.get("snap-id") or s.get("snap-id") or "", "name": info.get("name") or s.get("name", ""),
        "title": s.get("title") or info.get("name", ""), "summary": s.get("summary", ""),
        "description": s.get("description", ""), "download-size": dl.get("size", 0),
        "publisher": {"id": pub.get("id", ""), "username": pub.get("username", ""),
                      "display-name": pub.get("display-name", ""), "validation": pub.get("validation", "unproven")},
        "developer": pub.get("username", ""), "store-url": s.get("store-url", ""), "status": status,
        "type": entry.get("type") or s.get("type", "app"), "version": entry.get("version") or s.get("version", ""),
        "channel": ch.get("name", "stable") if isinstance(ch, dict) else "stable",
        "revision": str(entry.get("revision", "")), "confinement": entry.get("confinement") or s.get("confinement", "strict"),
        "private": False, "contact": s.get("contact", ""), "license": s.get("license", ""),
        "media": s.get("media") or [], "base": entry.get("base") or s.get("base"),
        "categories": [{"featured": False, "name": c.get("name", "")} for c in (s.get("categories") or [])],
    }


def store_find(params):
    q = {"fields": FIND_FIELDS, "architecture": ARCH}
    q.update({k: v for k, v in params.items() if v})
    out = []
    for it in store_get("/v2/snaps/find", q).get("results", []):
        entry = it.get("revision") if isinstance(it.get("revision"), dict) else it
        out.append(to_snapd(it, entry))
    return out


# ---------------------------------------------------------------------------------------------- unduh + ekstrak
def download(url, dest, size=0, sha3=None, progress=None):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    h = hashlib.sha3_384()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r, open(tmp, "wb") as f:
            total = int(r.headers.get("Content-Length") or size or 0)
            done = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                h.update(chunk)
                done += len(chunk)
                if progress:
                    progress("Mengunduh", done, total or done)
    except (urllib.error.URLError, OSError) as e:
        raise SnapError(f"unduhan gagal: {e}", "download-error", 502)
    if sha3 and h.hexdigest() != sha3:
        os.remove(tmp)
        raise SnapError("sha3-384 berkas unduhan tidak cocok", "download-error", 502)
    os.replace(tmp, dest)


def extract_squashfs(src, dst):
    """Pengganti `mount -t squashfs`: ekstrak penuh. dst dibuat ulang agar tidak ada sisa revisi lama."""
    if not shutil.which("unsquashfs"):
        raise SnapError("unsquashfs tidak ada: sudo apt install squashfs-tools", "no-unsquashfs", 500)
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(os.path.dirname(dst), exist_ok=True)  # unsquashfs membuat dst sendiri tetapi tidak induknya
    r = subprocess.run(["unsquashfs", "-no-xattrs", "-f", "-d", dst, src], capture_output=True, text=True)
    if r.returncode != 0:
        shutil.rmtree(dst, ignore_errors=True)
        raise SnapError("unsquashfs gagal: " + (r.stderr or r.stdout).strip()[-300:], "extract-error", 500)
    subprocess.run(["chmod", "-R", "u+rwX", dst], check=False)


# ---------------------------------------------------------------------------------------------- content snap
def providers_of(y):
    out = []
    for pname, plug in (y.get("plugs") or {}).items():
        if isinstance(plug, dict) and plug.get("interface") == "content" and plug.get("default-provider"):
            p = str(plug["default-provider"]).split(":")[0]
            if p and p not in out:
                out.append(p)
    return out


def provider_source(prov, content_id):
    rec = load_state()["snaps"].get(prov)
    if not rec:
        return None
    pdir = snap_dir(prov, rec["revision"])
    py = read_yaml(f"{pdir}/meta/snap.yaml")
    for sname, slot in (py.get("slots") or {}).items():
        if isinstance(slot, dict) and slot.get("interface") == "content" and (slot.get("content") or sname) == content_id:
            reads = (slot.get("source") or {}).get("read") or ["."]
            p = expand(str(reads[0]), {"SNAP": pdir})
            if not p.startswith("/"):
                p = os.path.normpath(os.path.join(pdir, p))
            return p.replace(pdir, f"{SNAP_ROOT}/{prov}/current", 1)
    return None


def link_content(name, rev, y):
    d = snap_dir(name, rev)
    for pname, plug in (y.get("plugs") or {}).items():
        if not (isinstance(plug, dict) and plug.get("interface") == "content" and plug.get("target")
                and plug.get("default-provider")):
            continue
        prov = str(plug["default-provider"]).split(":")[0]
        src = provider_source(prov, plug.get("content") or pname)
        target = expand(str(plug["target"]), {"SNAP": d})
        if not src or not target.startswith(d + "/"):
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.islink(target):
            os.remove(target)
        elif os.path.isdir(target):
            try:
                os.rmdir(target)
            except OSError:
                continue
        os.symlink(src, target)


# ---------------------------------------------------------------------------------------------- wrapper + desktop
def wrapper_name(name, app):
    return name if app == name else f"{name}.{app}"


def write_integration(name, rev, y):
    d = snap_dir(name, rev)
    os.makedirs(f"{SNAP_ROOT}/bin", exist_ok=True)
    for app in (y.get("apps") or {}):
        w = f"{SNAP_ROOT}/bin/{wrapper_name(name, app)}"
        with open(w, "w") as f:
            f.write(f'#!/bin/sh\nexec "{CLI}" run {wrapper_name(name, app)} "$@"\n')
        os.chmod(w, 0o755)
    os.makedirs(DESKTOP_DIR, exist_ok=True)
    gui = f"{d}/meta/gui"
    if os.path.isdir(gui):
        for fn in os.listdir(gui):
            if not fn.endswith(".desktop"):
                continue
            lines = []
            for line in open(f"{gui}/{fn}", encoding="utf-8", errors="replace").read().splitlines():
                if line.startswith("Exec="):
                    parts = line[5:].split(None, 1)
                    tok = parts[0] if parts else ""
                    if tok == name or tok.startswith(name + "."):
                        line = f"Exec={SNAP_ROOT}/bin/{tok}" + (f" {parts[1]}" if len(parts) > 1 else "")
                line = line.replace("${SNAP}", f"{SNAP_ROOT}/{name}/current").replace("$SNAP", f"{SNAP_ROOT}/{name}/current")
                lines.append(line)
            lines.append(f"X-SnapInstanceName={name}")
            with open(f"{DESKTOP_DIR}/{name}_{fn}", "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")


def remove_integration(name, y):
    for app in ((y or {}).get("apps") or {}):
        try:
            os.remove(f"{SNAP_ROOT}/bin/{wrapper_name(name, app)}")
        except OSError:
            pass
    if os.path.isdir(DESKTOP_DIR):
        for fn in os.listdir(DESKTOP_DIR):
            if fn.startswith(name + "_") and fn.endswith(".desktop"):
                try:
                    os.remove(f"{DESKTOP_DIR}/{fn}")
                except OSError:
                    pass


# ---------------------------------------------------------------------------------------------- install / remove
def install(name, channel="stable", progress=None, _stack=()):
    """Pasang (atau perbarui) satu snap beserta default-provider content snap-nya. Mengembalikan pesan hasil."""
    if name in _stack:
        return "lewati"
    with oplock():
        info = store_info(name)
        entry = pick_release(info, channel)
        rev = int(entry["revision"])
        cur = load_state()["snaps"].get(name)
        if cur and cur["revision"] == rev:
            return f"snap \"{name}\" sudah terpasang (rev {rev})"
        dl = entry.get("download") or {}
        if not dl.get("url"):
            raise SnapError("store tidak memberi URL unduhan", "download-error", 502)
        snapfile = f"{SNAPD_LIB}/snaps/{name}_{rev}.snap"
        download(dl["url"], snapfile, dl.get("size", 0), dl.get("sha3-384"), progress)
        if progress:
            progress("Mengekstrak", 0, 1)
        d = snap_dir(name, rev)
        extract_squashfs(snapfile, d)
        y = read_yaml(f"{d}/meta/snap.yaml")
        for prov in providers_of(y):
            if prov not in load_state()["snaps"]:
                install(prov, "stable", progress, _stack + (name,))
        base = y.get("base")
        if base and base not in ("core", "bare") and os.environ.get("TINY_SNAP_INSTALL_BASE") == "1" \
                and base not in load_state()["snaps"]:
            install(base, "stable", progress, _stack + (name,))
        link_content(name, rev, y)
        old = cur["revision"] if cur else None
        link = f"{SNAP_ROOT}/{name}/current"
        if os.path.lexists(link):
            os.remove(link)
        os.symlink(str(rev), link)
        for sub in (f"{VAR_SNAP}/{name}/{rev}", f"{VAR_SNAP}/{name}/common", f"{HOME}/snap/{name}/{rev}", f"{HOME}/snap/{name}/common"):
            os.makedirs(sub, exist_ok=True)
        cur_link = f"{HOME}/snap/{name}/current"
        if os.path.lexists(cur_link):
            os.remove(cur_link)
        os.symlink(str(rev), cur_link)
        write_integration(name, rev, y)
        st = load_state()
        st["snaps"][name] = {
            "revision": rev, "version": entry.get("version") or y.get("version", ""), "channel": channel or "stable",
            "snap-id": info.get("snap-id", ""), "type": entry.get("type") or y.get("type", "app"),
            "base": y.get("base"), "confinement": y.get("confinement", "strict"), "summary": y.get("summary", ""),
            "title": (info.get("snap") or {}).get("title") or y.get("title") or name,
            "description": y.get("description", ""), "installed": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "size": dl.get("size", 0), "requires": providers_of(y), "publisher": ((info.get("snap") or {}).get("publisher") or {}),
        }
        save_state(st)
        if old and old != rev:
            shutil.rmtree(snap_dir(name, old), ignore_errors=True)
            try:
                os.remove(f"{SNAPD_LIB}/snaps/{name}_{old}.snap")
            except OSError:
                pass
        if progress:
            progress("Selesai", 1, 1)
        return f"{name} {rev} terpasang"


def remove(name, purge=False, force=False):
    with oplock():
        st = load_state()
        rec = st["snaps"].get(name)
        if not rec:
            raise SnapError(f"snap \"{name}\" tidak terpasang", "snap-not-installed", 404)
        users = [n for n, r in st["snaps"].items() if n != name and name in (r.get("requires") or [])]
        if users and not force:
            raise SnapError(f"snap \"{name}\" masih dipakai oleh: {', '.join(users)}", "snap-in-use", 400)
        try:
            y = read_yaml(f"{snap_dir(name, rec['revision'])}/meta/snap.yaml")
        except SnapError:
            y = {}
        remove_integration(name, y)
        shutil.rmtree(f"{SNAP_ROOT}/{name}", ignore_errors=True)
        try:
            os.remove(f"{HOME}/snap/{name}/current")
        except OSError:
            pass
        if os.path.isdir(f"{SNAPD_LIB}/snaps"):
            for fn in os.listdir(f"{SNAPD_LIB}/snaps"):
                if fn.startswith(name + "_") and fn.endswith(".snap"):
                    os.remove(f"{SNAPD_LIB}/snaps/{fn}")
        if purge:
            shutil.rmtree(f"{HOME}/snap/{name}", ignore_errors=True)
            shutil.rmtree(f"{VAR_SNAP}/{name}", ignore_errors=True)
        del st["snaps"][name]
        save_state(st)
        return f"{name} dihapus"


def refresh(names=None, progress=None):
    done = []
    for name, rec in list(load_state()["snaps"].items()):
        if names and name not in names:
            continue
        try:
            entry = pick_release(store_info(name), rec.get("channel", "stable"))
        except SnapError:
            continue
        if int(entry["revision"]) != rec["revision"]:
            install(name, rec.get("channel", "stable"), progress)
            done.append(name)
    return done


def updates_available():
    out = []
    for name, rec in load_state()["snaps"].items():
        try:
            info = store_info(name)
            entry = pick_release(info, rec.get("channel", "stable"))
        except SnapError:
            continue
        if int(entry["revision"]) != rec["revision"]:
            out.append(to_snapd(info, entry))
    return out


def local_snap(name, rec):
    d = snap_dir(name, rec["revision"])
    try:
        y = read_yaml(f"{d}/meta/snap.yaml")
    except SnapError:
        y = {}
    pub = rec.get("publisher") or {}
    return {
        "id": rec.get("snap-id", ""), "title": rec.get("title", name), "summary": rec.get("summary", ""),
        "description": rec.get("description", ""), "installed-size": rec.get("size", 0), "name": name,
        "publisher": {"id": pub.get("id", ""), "username": pub.get("username", ""),
                      "display-name": pub.get("display-name", ""), "validation": pub.get("validation", "unproven")},
        "developer": pub.get("username", ""), "status": "active", "type": rec.get("type", "app"),
        "version": rec.get("version", ""), "channel": rec.get("channel", "stable"),
        "tracking-channel": rec.get("channel", "stable"), "revision": str(rec["revision"]),
        "confinement": rec.get("confinement", "strict"), "base": rec.get("base"), "install-date": rec.get("installed", ""),
        "mounted-from": f"{SNAPD_LIB}/snaps/{name}_{rec['revision']}.snap", "private": False, "devmode": False,
        "jailmode": False, "contact": "", "license": y.get("license", ""), "common-ids": [],
        "apps": [{"snap": name, "name": a} for a in (y.get("apps") or {})],
    }


# ---------------------------------------------------------------------------------------------- snap run
def snap_env(name, rec, y, app_cfg):
    rev = rec["revision"]
    d = snap_dir(name, rev)
    user_data = f"{HOME}/snap/{name}/{rev}"
    e = dict(os.environ)
    for sub in (user_data, f"{HOME}/snap/{name}/common"):
        os.makedirs(sub, exist_ok=True)
    e.update({
        "SNAP": d, "SNAP_NAME": name, "SNAP_INSTANCE_NAME": name, "SNAP_REVISION": str(rev),
        "SNAP_VERSION": str(rec.get("version", "")), "SNAP_ARCH": ARCH, "SNAP_DATA": f"{VAR_SNAP}/{name}/{rev}",
        "SNAP_COMMON": f"{VAR_SNAP}/{name}/common", "SNAP_USER_DATA": user_data,
        "SNAP_USER_COMMON": f"{HOME}/snap/{name}/common", "SNAP_REAL_HOME": HOME, "SNAP_UID": str(os.getuid()),
        "SNAP_EUID": str(os.geteuid()), "SNAP_LIBRARY_PATH": "/var/lib/snapd/lib/gl:/var/lib/snapd/lib/gl32:/var/lib/snapd/void",
        "SNAP_CONTEXT": "", "SNAP_COOKIE": "", "HOME": user_data,
    })
    e["PATH"] = ":".join([f"{d}/usr/sbin", f"{d}/usr/bin", f"{d}/sbin", f"{d}/bin", e.get("PATH", "/usr/local/bin:/usr/bin:/bin"), f"{SNAP_ROOT}/bin", ensure_snapctl_wrapper()])
    for src in (y.get("environment"), app_cfg.get("environment")):
        for k, v in (src or {}).items():
            e[k] = expand(str(v), e)
    return e


def ensure_daemon():
    if socket_alive():
        return
    os.makedirs(BASE, exist_ok=True)
    try:
        subprocess.Popen([sys.executable, os.path.abspath(__file__), "daemon"], start_new_session=True,
                         stdin=subprocess.DEVNULL, stdout=open(f"{BASE}/snapd.log", "a"), stderr=subprocess.STDOUT)
    except OSError:
        return
    for _ in range(50):
        if socket_alive():
            return
        time.sleep(0.1)


def run_snap(spec, args):
    name, _, app = spec.partition(".")
    st = load_state()
    rec = st["snaps"].get(name)
    if not rec:
        raise SnapError(f"snap \"{name}\" tidak terpasang", "snap-not-installed", 404)
    d = snap_dir(name, rec["revision"])
    y = read_yaml(f"{d}/meta/snap.yaml")
    apps = y.get("apps") or {}
    if not app:
        app = name if name in apps else (next(iter(apps)) if len(apps) == 1 else "")
    if app not in apps:
        raise SnapError(f"snap \"{name}\" tidak punya aplikasi \"{app or '?'}\" (ada: {', '.join(apps) or '-'})", "app-not-found", 404)
    cfg = apps[app] or {}
    plugs = set((cfg.get("plugs") or [])) | set((y.get("plugs") or {}).keys())
    if name == "snap-store" or plugs & DAEMON_PLUGS:
        ensure_daemon()
    env = snap_env(name, rec, y, cfg)
    cmd = [expand(a, env) for a in shlex.split(cfg.get("command", ""))]
    if not cmd:
        raise SnapError("aplikasi tidak punya command", "app-not-found", 400)

    def abspath(p):
        return p if p.startswith("/") else os.path.join(d, p)
    first = cmd[0]
    if "/" not in first:
        first = shutil.which(first, path=env["PATH"]) or first
    argv = [abspath(c) for c in (cfg.get("command-chain") or [])] + [abspath(first)] + cmd[1:] + list(args)
    os.chdir(env["SNAP_USER_DATA"])
    try:
        os.execve(argv[0], argv, env)
    except OSError as e:
        raise SnapError(f"tidak bisa menjalankan {argv[0]}: {e}", "exec-error", 500)


# ---------------------------------------------------------------------------------------------- snapctl
def declared_plugs(y):
    out = dict(y.get("plugs") or {})
    for cfg in (y.get("apps") or {}).values():
        for p in ((cfg or {}).get("plugs") or []):
            if isinstance(p, str):
                out.setdefault(p, None)
    return out


def snapctl(args):
    """snapctl palsu: skrip desktop-launch milik extension gnome memanggil `snapctl is-connected <plug>` dan berhenti
    bila gagal. Plug content dianggap tersambung bila symlink-nya ke content snap ada; plug lain dianggap tersambung."""
    pos = [a for a in args if not a.startswith("-")]
    if not pos:
        return 0
    if pos[0] != "is-connected":
        return 0  # get/set/services/start/stop/...: tidak ada yang perlu dilakukan tanpa snapd sungguhan
    plug = pos[-1] if len(pos) > 1 else ""
    name = os.environ.get("SNAP_NAME", "")
    rec = load_state()["snaps"].get(name)
    if not rec:
        return 1
    try:
        y = read_yaml(f"{snap_dir(name, rec['revision'])}/meta/snap.yaml")
    except SnapError:
        return 1
    plugs = declared_plugs(y)
    if plug not in plugs:
        return 1
    cfg = plugs[plug]
    if isinstance(cfg, dict) and cfg.get("interface") == "content" and cfg.get("target"):
        target = expand(str(cfg["target"]), {"SNAP": snap_dir(name, rec["revision"])})
        return 0 if os.path.exists(target) else 1
    return 0


def ensure_snapctl_wrapper():
    d = f"{BASE}/bin"
    os.makedirs(d, exist_ok=True)
    w = f"{d}/snapctl"
    body = f'#!/bin/sh\nexec "{sys.executable}" "{os.path.abspath(__file__)}" snapctl "$@"\n'
    try:
        if not os.path.exists(w) or open(w).read() != body:
            with open(w, "w") as f:
                f.write(body)
            os.chmod(w, 0o755)
    except OSError:
        pass
    return d


# ---------------------------------------------------------------------------------------------- daemon REST
CHANGES = {}
_CHANGE_SEQ = [0]
_OPS = threading.Lock()  # satu operasi pemasangan pada satu waktu


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S.000000000Z", time.gmtime())


def new_change(kind, summary, fn):
    with _TLOCK:
        _CHANGE_SEQ[0] += 1
        cid = str(_CHANGE_SEQ[0])
    task = {"id": "t" + cid, "kind": "download-snap", "summary": summary, "status": "Do", "change-id": cid,
            "progress": {"label": "", "done": 0, "total": 1}, "spawn-time": now_iso()}
    ch = {"id": cid, "kind": kind, "summary": summary, "status": "Do", "ready": False, "tasks": [task], "spawn-time": now_iso()}
    CHANGES[cid] = ch

    def progress(label, done, total):
        task["status"] = "Doing"
        task["summary"] = f"{label}: {summary}"
        task["progress"] = {"label": label, "done": int(done), "total": max(int(total), 1)}

    def runner():
        with _OPS:
            ch["status"] = "Doing"
            try:
                fn(progress)
                ch["status"], task["status"] = "Done", "Done"
                task["progress"] = {"label": "", "done": 1, "total": 1}
            except SnapError as e:
                ch["status"], task["status"], ch["err"] = "Error", "Error", str(e)
            except Exception as e:  # noqa: BLE001 - daemon tidak boleh mati karena satu operasi
                ch["status"], task["status"], ch["err"] = "Error", "Error", f"{type(e).__name__}: {e}"
            ch["ready"] = True
            ch["ready-time"] = now_iso()
    threading.Thread(target=runner, daemon=True).start()
    return cid


def icon_path(name):
    rec = load_state()["snaps"].get(name)
    if not rec:
        return None
    gui = f"{snap_dir(name, rec['revision'])}/meta/gui"
    if os.path.isdir(gui):
        for fn in sorted(os.listdir(gui)):
            if fn.startswith("icon."):
                return f"{gui}/{fn}"
    return None


def sync(result, extra=None):
    d = {"type": "sync", "status-code": 200, "status": "OK", "result": result}
    d.update(extra or {})
    return 200, d


def route(method, path, query, body):
    q = {k: v[-1] for k, v in urllib.parse.parse_qs(query).items()}
    if path == "/v2/system-info" and method == "GET":
        osr = {}
        try:
            for line in open("/etc/os-release"):
                k, _, v = line.strip().partition("=")
                osr[k] = v.strip('"')
        except OSError:
            pass
        return sync({"series": "16", "version": VERSION, "os-release": {"id": osr.get("ID", "ubuntu"), "version-id": osr.get("VERSION_ID", "")},
                     "on-classic": True, "managed": False, "confinement": "partial", "architecture": ARCH,
                     "sandbox-features": {}, "refresh": {"schedule": "", "last": "", "next": ""}, "build-id": "tiny"})
    if path == "/v2/snaps" and method == "GET":
        want = set(filter(None, q.get("snaps", "").split(",")))
        return sync([local_snap(n, r) for n, r in load_state()["snaps"].items() if not want or n in want])
    m = re.fullmatch(r"/v2/snaps/([^/]+)", path)
    if m and method == "GET":
        rec = load_state()["snaps"].get(m.group(1))
        if not rec:
            raise SnapError("snap tidak terpasang", "snap-not-installed", 404)
        return sync(local_snap(m.group(1), rec))
    if m and method == "POST":
        name, action = m.group(1), (body or {}).get("action", "")
        ch = (body or {}).get("channel") or "stable"
        if action in ("install", "refresh"):
            cid = new_change(f"{action}-snap", f"{'Install' if action == 'install' else 'Refresh'} \"{name}\" snap", lambda p: install(name, ch, p))
        elif action == "remove":
            cid = new_change("remove-snap", f"Remove \"{name}\" snap", lambda p: remove(name, bool((body or {}).get("purge"))))
        elif action in ("enable", "disable", "switch"):
            cid = new_change(f"{action}-snap", f"{action} \"{name}\"", lambda p: None)
        else:
            raise SnapError(f"aksi tidak didukung: {action}", "bad-request", 400)
        return 202, {"type": "async", "status-code": 202, "status": "Accepted", "change": cid}
    if path == "/v2/snaps" and method == "POST":
        if (body or {}).get("action") == "refresh":
            names = (body or {}).get("snaps") or None
            cid = new_change("refresh-snap", "Refresh snaps", lambda p: refresh(names, p))
            return 202, {"type": "async", "status-code": 202, "status": "Accepted", "change": cid}
        raise SnapError("aksi tidak didukung", "bad-request", 400)
    m = re.fullmatch(r"/v2/changes/([^/]+)", path)
    if m and method == "GET":
        ch = CHANGES.get(m.group(1))
        if not ch:
            raise SnapError("change tidak ada", "not-found", 404)
        return sync(ch)
    if path == "/v2/changes" and method == "GET":
        sel = q.get("select", "in-progress")
        rows = [c for c in CHANGES.values() if sel == "all" or (sel == "ready") == bool(c["ready"])]
        return sync(rows)
    if path == "/v2/find" and method == "GET":
        if q.get("select") == "refresh":
            return sync(updates_available())
        if q.get("name"):
            info = store_info(q["name"])
            return sync([to_snapd(info, pick_release(info, "stable"))], {"sources": ["store"]})
        params = {"q": q.get("q"), "category": q.get("category") or q.get("section")}
        if not any(params.values()):
            return sync([], {"sources": ["store"]})
        return sync(store_find(params), {"sources": ["store"]})
    if path == "/v2/categories" and method == "GET":
        return sync([{"name": c} for c in CATEGORIES])
    if path in ("/v2/accessories/themes", "/v2/themes") and method == "GET":
        return sync({"gtk-themes": {}, "icon-themes": {}, "sound-themes": {}})
    m = re.fullmatch(r"/v2/icons/([^/]+)/icon", path)
    if m and method == "GET":
        return 200, ("file", icon_path(m.group(1)))
    if path in ("/v2/connections", "/v2/interfaces", "/v2/apps", "/v2/aliases", "/v2/assertions", "/v2/logs",
                "/v2/notices", "/v2/users", "/v2/snapshots", "/v2/sections") and method == "GET":
        return sync({"established": [], "plugs": [], "slots": [], "undesired": []} if path == "/v2/connections" else [])
    if path == "/v2/login":
        raise SnapError("login tidak didukung di tiny-snapd", "login-required", 401)
    raise SnapError(f"endpoint tidak ada: {method} {path}", "not-found", 404)


STATUS = {200: "OK", 202: "Accepted", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 500: "Internal Server Error", 502: "Bad Gateway"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def address_string(self):
        return "unix"

    def log_message(self, fmt, *a):
        if os.environ.get("TINY_SNAPD_DEBUG"):
            sys.stderr.write("[snapd] " + (fmt % a) + "\n")

    def _write(self, code, data, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _handle(self, method):
        u = urllib.parse.urlsplit(self.path)
        body = None
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            raw = self.rfile.read(n)
            try:
                body = json.loads(raw)
            except ValueError:
                body = None
        try:
            code, obj = route(method, u.path, u.query, body)
        except SnapError as e:
            code = e.status
            obj = {"type": "error", "status-code": code, "status": STATUS.get(code, "Error"),
                   "result": {"message": str(e), "kind": e.kind}}
        except Exception as e:  # noqa: BLE001
            code = 500
            obj = {"type": "error", "status-code": 500, "status": "Internal Server Error",
                   "result": {"message": f"{type(e).__name__}: {e}"}}
        if isinstance(obj, tuple) and obj[0] == "file":
            p = obj[1]
            if p and os.path.isfile(p):
                ctype = "image/svg+xml" if p.endswith(".svg") else "image/png"
                return self._write(200, open(p, "rb").read(), ctype)
            return self._write(404, b"{}")
        self._write(code, json.dumps(obj).encode())

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")


class UnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True


def socket_alive(path=None):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(1)
    try:
        s.connect(path or SOCKET)
        return True
    except OSError:
        return False
    finally:
        s.close()


def link_system_socket():
    """App Center mencari /run/snapd.socket (tanpa opsi lain): buat symlink ke soket milik pengguna."""
    try:
        if os.path.lexists(SYS_SOCKET):
            if os.path.islink(SYS_SOCKET) and os.readlink(SYS_SOCKET) == SOCKET:
                return
            os.remove(SYS_SOCKET)
        os.makedirs(os.path.dirname(SYS_SOCKET), exist_ok=True)
        os.symlink(SOCKET, SYS_SOCKET)
    except OSError:
        subprocess.run(["sudo", "-n", "ln", "-sf", SOCKET, SYS_SOCKET], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def set_proc_name(name):
    """comm proses diganti supaya Launcher menganggapnya proses pendukung (bukan aplikasi yang masih berjalan)."""
    try:
        import ctypes
        ctypes.CDLL(None).prctl(15, name.encode(), 0, 0, 0)
    except Exception:  # noqa: BLE001
        pass


def serve():
    os.makedirs(BASE, exist_ok=True)
    set_proc_name("tiny-snapd")
    if socket_alive():
        print("tiny-snapd sudah berjalan")
        return
    try:
        os.remove(SOCKET)
    except OSError:
        pass
    srv = UnixServer(SOCKET, Handler)
    os.chmod(SOCKET, 0o666)
    with open(f"{BASE}/snapd.pid", "w") as f:
        f.write(str(os.getpid()))
    link_system_socket()
    signal.signal(signal.SIGTERM, lambda *a: threading.Thread(target=srv.shutdown, daemon=True).start())
    print(f"tiny-snapd {VERSION} melayani {SOCKET}", flush=True)
    try:
        srv.serve_forever()
    finally:
        for p in (SOCKET, f"{BASE}/snapd.pid"):
            try:
                os.remove(p)
            except OSError:
                pass


# ---------------------------------------------------------------------------------------------- CLI
def cli_progress():
    last = {"k": None}

    def p(label, done, total):
        pct = int(done * 100 / total) if total else 0
        key = (label, pct // 10)
        if key != last["k"]:
            last["k"] = key
            print(f"  {label} {pct}%", file=sys.stderr, flush=True)
    return p


def fmt_size(n):
    for u in ("B", "kB", "MB", "GB"):
        if n < 1000 or u == "GB":
            return f"{n:.0f}{u}" if u == "B" else f"{n:.1f}{u}"
        n /= 1000.0


def main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print("tiny-snapd (pengganti snapd untuk proot): install, remove, list, info, find, refresh, run, version, daemon, daemon-start, daemon-stop")
        return 0
    cmd, rest = argv[0], argv[1:]
    opts = [a for a in rest if a.startswith("-")]
    names = [a for a in rest if not a.startswith("-")]
    channel = "stable"
    for o in opts:
        if o.startswith("--channel="):
            channel = o.split("=", 1)[1]
        elif o in ("--stable", "--candidate", "--beta", "--edge"):
            channel = o[2:]
    try:
        if cmd == "run":
            # `snap run [--shell|--command=...] nama[.app] args...` : sisanya diteruskan apa adanya
            args = [a for a in rest if not (a.startswith("--") and a.split("=")[0] in ("--shell", "--command", "--hook", "--trace-exec", "--strace", "--gdb", "--experimental-gdbserver", "--timer"))]
            if not args:
                raise SnapError("snap run: nama snap diperlukan", "bad-request", 400)
            run_snap(args[0], args[1:])
        elif cmd == "snapctl":
            return snapctl(rest)
        elif cmd == "install":
            for n in names:
                print(install(n, channel, cli_progress()))
        elif cmd == "remove":
            for n in names:
                print(remove(n, "--purge" in opts, "--force" in opts))
        elif cmd == "refresh":
            done = refresh(names or None, cli_progress())
            print("diperbarui: " + ", ".join(done) if done else "semua snap sudah terbaru")
        elif cmd == "list":
            rows = load_state()["snaps"]
            if not rows:
                print("Tidak ada snap yang terpasang.")
            else:
                print(f"{'Name':<24}{'Version':<18}{'Rev':<8}{'Tracking':<14}Notes")
                for n, r in sorted(rows.items()):
                    print(f"{n:<24}{str(r.get('version', ''))[:17]:<18}{r['revision']:<8}{r.get('channel', 'stable'):<14}-")
        elif cmd == "info":
            for n in names:
                info = store_info(n)
                e = pick_release(info, channel)
                s = to_snapd(info, e)
                cur = load_state()["snaps"].get(n)
                print(f"name:      {s['name']}\nsummary:   {s['summary']}\npublisher: {s['publisher']['display-name']}\n"
                      f"license:   {s['license'] or 'unset'}\nversion:   {s['version']}  (rev {s['revision']}, {fmt_size(s['download-size'])})\n"
                      f"installed: {('rev ' + str(cur['revision'])) if cur else '-'}")
        elif cmd == "find":
            for s in store_find({"q": " ".join(names)}):
                print(f"{s['name']:<26}{s['version'][:14]:<16}{s['publisher']['display-name'][:20]:<22}{s['summary'][:60]}")
        elif cmd == "version":
            print(f"snap    {VERSION}\nsnapd   {VERSION}\nseries  16\nos-release  tiny-proot\narchitecture  {ARCH}")
        elif cmd in ("connect", "disconnect", "connections", "interface", "interfaces", "set", "get", "unset", "services", "start", "stop", "restart", "enable", "disable", "wait", "changes"):
            print(f"snap {cmd}: tidak berlaku di tiny-snapd (tanpa confinement/layanan); dilewati.", file=sys.stderr)
        elif cmd == "daemon":
            serve()
        elif cmd == "daemon-start":
            ensure_daemon()
            print("tiny-snapd berjalan" if socket_alive() else "tiny-snapd gagal start (lihat " + BASE + "/snapd.log)")
            return 0 if socket_alive() else 1
        elif cmd == "daemon-stop":
            try:
                os.kill(int(open(f"{BASE}/snapd.pid").read()), signal.SIGTERM)
                print("tiny-snapd dihentikan")
            except (OSError, ValueError):
                print("tiny-snapd tidak berjalan")
        else:
            print(f'snap: perintah "{cmd}" tidak dikenal', file=sys.stderr)
            return 2
    except SnapError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
