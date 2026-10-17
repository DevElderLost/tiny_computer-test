#!/usr/bin/python3
# Meminta semua jendela X11 menutup diri lewat WM_DELETE_WINDOW (sama seperti menekan tombol X di bingkai jendela).
# Aplikasi Qt/GTK/SDL lalu menjalankan jalur "keluar" normalnya dan SEMPAT menulis konfigurasi (qt-config.ini,
# ppsspp.ini, dst.). Dipanggil Tiny Computer sebelum sesi Shortcut dimatikan; mencetak "CLOSED=<jumlah jendela>".
import ctypes
import ctypes.util
import signal
import sys

signal.alarm(3)  # jangan pernah menggantung: peluncur menunggu paling lama beberapa detik


def done(n):
    print("CLOSED=%d" % n)
    sys.stdout.flush()
    sys.exit(0)


try:
    x = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
except OSError:
    done(0)

c_ulong, c_int, c_uint, c_long, c_void_p, c_char_p = (
    ctypes.c_ulong, ctypes.c_int, ctypes.c_uint, ctypes.c_long, ctypes.c_void_p, ctypes.c_char_p)
P = ctypes.POINTER

x.XOpenDisplay.restype = c_void_p
x.XOpenDisplay.argtypes = [c_char_p]
x.XDefaultRootWindow.restype = c_ulong
x.XDefaultRootWindow.argtypes = [c_void_p]
x.XInternAtom.restype = c_ulong
x.XInternAtom.argtypes = [c_void_p, c_char_p, c_int]
x.XQueryTree.restype = c_int
x.XQueryTree.argtypes = [c_void_p, c_ulong, P(c_ulong), P(c_ulong), P(P(c_ulong)), P(c_uint)]
x.XGetWMProtocols.restype = c_int
x.XGetWMProtocols.argtypes = [c_void_p, c_ulong, P(P(c_ulong)), P(c_int)]
x.XSendEvent.restype = c_int
x.XSendEvent.argtypes = [c_void_p, c_ulong, c_int, c_long, c_void_p]
x.XFree.argtypes = [c_void_p]
x.XSync.argtypes = [c_void_p, c_int]
x.XCloseDisplay.argtypes = [c_void_p]


class ClientMessage(ctypes.Structure):
    _fields_ = [("type", c_int), ("serial", c_ulong), ("send_event", c_int), ("display", c_void_p),
                ("window", c_ulong), ("message_type", c_ulong), ("format", c_int), ("l", c_long * 5)]


d = x.XOpenDisplay(None)  # memakai $DISPLAY
if not d:
    done(0)
root = x.XDefaultRootWindow(d)
WM_PROTOCOLS = x.XInternAtom(d, b"WM_PROTOCOLS", 0)
WM_DELETE = x.XInternAtom(d, b"WM_DELETE_WINDOW", 0)


def children(w):
    r, p, kids, n = c_ulong(), c_ulong(), P(c_ulong)(), c_uint()
    if not x.XQueryTree(d, w, ctypes.byref(r), ctypes.byref(p), ctypes.byref(kids), ctypes.byref(n)):
        return []
    out = [kids[i] for i in range(n.value)] if kids else []
    if kids:
        x.XFree(ctypes.cast(kids, c_void_p))
    return out


def wants_delete(w):
    protos, n = P(c_ulong)(), c_int()
    if not x.XGetWMProtocols(d, w, ctypes.byref(protos), ctypes.byref(n)):
        return False
    ok = any(protos[i] == WM_DELETE for i in range(n.value))
    x.XFree(ctypes.cast(protos, c_void_p))
    return ok


def close(w):
    ev = ClientMessage()
    ev.type = 33  # ClientMessage
    ev.send_event = 1
    ev.display = d
    ev.window = w
    ev.message_type = WM_PROTOCOLS
    ev.format = 32
    ev.l[0] = WM_DELETE
    ev.l[1] = 0  # CurrentTime
    return x.XSendEvent(d, w, 0, 0, ctypes.byref(ev)) != 0


count = 0


def walk(w, depth):
    global count
    if depth > 0 and wants_delete(w):
        if close(w):
            count += 1
        return
    if depth < 4:
        for k in children(w):
            walk(k, depth + 1)


walk(root, 0)
x.XSync(d, 0)
x.XCloseDisplay(d)
done(count)
