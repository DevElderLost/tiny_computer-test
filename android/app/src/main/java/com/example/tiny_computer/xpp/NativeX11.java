package com.example.tiny_computer.xpp;

import android.view.Surface;

/**
 * Jembatan JNI ke libxpp.so: X++ native X server (dari Winlator Ludashi, folder ludashix11).
 * Seluruh klien X11, resource, DRI3 dan Present ada di C++; sisi Java hanya memberi Surface dan masukan.
 * Nama method harus sama dengan fungsi Java_com_example_tiny_1computer_xpp_NativeX11_* di xpp/src/native_jni.cpp.
 */
public final class NativeX11 {
    private NativeX11() {}

    /**
     * Nomor backend renderer (parameter {@code backend} di {@link #nativeCreate}), sama dengan Winlator Ludashi.
     * Vulkan memakai VulkanRendererContext (swapchain Vulkan) tanpa mesin frame generation.
     */
    public static final int BACKEND_VULKAN = 0;
    public static final int BACKEND_EGL = 1;
    public static final int BACKEND_DISPLAYX = 2;

    /** Bit {@code flags} di {@link #nativeCreate}. */
    public static final int FLAG_TRUE_DISPLAYX = 1;
    public static final int FLAG_PERFORMANCE = 2;
    public static final int FLAG_PRESENT_AT_REFRESH = 4;
    public static final int FLAG_PRECISE_PRESENTATION = 8;
    /** MIT-SHM tidak diiklankan (SysV shm tidak bisa di-attach lewat proot). */
    public static final int FLAG_NO_MIT_SHM = 16;
    /** DRI3 tidak diiklankan (Mesa biasa tidak bisa memakai transport AHardwareBuffer); Zink/Turnip memakai WSI software. */
    public static final int FLAG_NO_DRI3 = 32;
    /** Tidak lagi dipakai oleh XppHost: RENDER sekarang diimplementasikan. Flag tetap ada untuk debugging. */
    public static final int FLAG_NO_RENDER = 64;
    /** Tidak lagi dipakai oleh XppHost: Composite, Damage dan XFixes sekarang diimplementasikan. Flag tetap ada untuk debugging. */
    public static final int FLAG_NO_COMPOSITE = 128;

    /** Jenis masukan untuk {@link #nativeInput}. */
    public static final int INPUT_MOVE_TO = 0;
    public static final int INPUT_BUTTON = 1;
    public static final int INPUT_KEY = 2;
    public static final int INPUT_MOVE_BY = 3;

    static {
        System.loadLibrary("xpp");
    }

    public static native long nativeCreate(int width, int height, boolean validation, String driverPath, String libraryName,
                                           String nativeLibDir, int presentMode, boolean swapRB, int backend, int flags);

    public static native void nativeStart(long handle, String socket, String shm);

    public static native void nativeDestroy(long handle);

    public static native void nativeSurface(long handle, Surface surface);

    public static native void nativeInput(long handle, int kind, int a, int b);

    public static native void nativeOptions(long handle, boolean fullscreen, boolean cursor, int fps, float refresh);

    public static native void nativeRefreshRates(long handle, int[] rates);

    /** Profil ICC layar (isi berkas .icc); null/kosong = sRGB bawaan. false bila data bukan profil ICC yang valid. */
    public static native boolean nativeIccProfile(long handle, byte[] profile);

    /**
     * Opsi visual renderer. [postFX] 3 = efek HDR; brightness/contrast/gamma/saturation = colour grade dalam satuan
     * shader (netral 0, 1, 1, 1). Backend DisplayX tidak punya pass shader sehingga mengabaikan efek ini.
     */
    public static native long nativeVisualOptions(long handle, int filter, int postFX, int stretch, float sharpness,
                                           boolean frameGen, String cachePath, int multiplier, int targetRate,
                                           int flowScale, float refreshRate, int engine,
                                           float brightness, float contrast, float gamma, float saturation);

    public static native String nativeError(long handle);

    public static native boolean nativeStartupContent(long handle);

    // Jembatan Vortek: window X <-> AHardwareBuffer (lihat native_jni.cpp).
    public static native int nativeVortekWindowWidth(long handle, int windowId);
    public static native int nativeVortekWindowHeight(long handle, int windowId);
    public static native long nativeVortekWindowBuffer(long handle, int windowId, boolean bgra);
    public static native void nativeVortekWindowUpdate(long handle, int windowId);
}
