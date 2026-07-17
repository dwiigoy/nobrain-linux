package com.termux.x11;

/**
 * Holder untuk konfigurasi XLorie yang harus di-set SEBELUM
 * LorieView atau CmdEntryPoint pertama kali direferensikan.
 *
 * Tidak boleh ada static library loading di sini — class ini
 * harus aman direferensikan kapan saja tanpa side effect.
 */
public class XLorieConfig {
    /** Path absolut ke libXlorie.so yang sudah di-patch resolusinya.
     *  Null = fallback ke System.loadLibrary("Xlorie"). */
    public static String libraryPath = null;

    /** Resolusi X server — harus cocok dengan bytes yang di-patch ke .so file. */
    public static int xResW = 540;
    public static int xResH = 1144;

    /** Ukuran SurfaceView fisik yang dipakai Android. Bisa lebih kecil dari X11
     *  resolution kalau Tiny-style screen scaling aktif. */
    public static int viewW = 540;
    public static int viewH = 1144;

    /** Tiny-style display scaling ratio percent. 100 = 1:1, 65 = 0.65x. */
    public static int screenScalePercent = 100;

    /** Renderer zoom percent (50-300). 100 = native 1:1. Applied via setRendererZoom(). */
    public static int xZoomPercent = 100;
}
