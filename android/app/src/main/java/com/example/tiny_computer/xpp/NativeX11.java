package com.example.tiny_computer.xpp;

import android.view.Surface;

/**
 * Jembatan JNI ke libxpp.so: X++ native X server (dari Winlator Ludashi, folder ludashix11).
 * Seluruh klien X11, resource, DRI3 dan Present ada di C++; sisi Java hanya memberi Surface dan masukan.
 * Nama method harus sama dengan fungsi Java_com_example_tiny_1computer_xpp_NativeX11_* di xpp/src/native_jni.cpp.
 */
public final class NativeX11 {
    private NativeX11() {}

    /** Nomor backend renderer (parameter {@code backend} di {@link #nativeCreate}). 0 (Vulkan) tidak ada di build ini. */
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

    public static native long nativeVisualOptions(long handle, int filter, int postFX, int stretch, float sharpness,
                                           boolean frameGen, String cachePath, int multiplier, int targetRate,
                                           int flowScale, float refreshRate, int engine);

    public static native String nativeError(long handle);

    public static native boolean nativeStartupContent(long handle);
}
