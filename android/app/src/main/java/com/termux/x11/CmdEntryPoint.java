package com.termux.x11;

import android.os.ParcelFileDescriptor;

/**
 * JNI wrapper untuk libXlorie.so — X11 server entry point.
 *
 * MECHANISM ASLI:
 * 1. listenForConnections() — listens on port 7892 (blocking)
 * 2. requestConnection() (di LorieView) — send MAGIC ke port 7892
 * 3. listenForConnections() terima MAGIC → panggil sendBroadcast()
 * 4. sendBroadcast() → app panggil getXConnection() + lorieView.connect(fd)
 * 5. Rendering aktif!
 */
public class CmdEntryPoint {

    public static String loadError = null;

    // Callback dipanggil oleh sendBroadcast() saat koneksi baru masuk via port 7892
    public static Runnable onConnectionCallback = null;
    // Flag untuk cegah multiple connections
    public static volatile boolean connectionEstablished = false;

    static {
        try {
            // Pakai path yang sama dengan LorieView — System.load() idempotent
            // untuk path yang sama, jadi aman dipanggil dua kali.
            if (XLorieConfig.libraryPath != null) {
                System.load(XLorieConfig.libraryPath);
            } else {
                System.loadLibrary("Xlorie");
            }
        } catch (UnsatisfiedLinkError e) {
            // Already loaded by LorieView — ignore
        } catch (Throwable t) {
            loadError = t.toString();
            android.util.Log.e("XLorie", "loadLibrary failed: " + t);
        }
    }

    /** Mulai X server. */
    public static native boolean start(String[] args);

    /** Ambil file descriptor untuk koneksi X protocol. */
    public native ParcelFileDescriptor getXConnection();

    /** Ambil file descriptor untuk logcat output dari native. */
    public native ParcelFileDescriptor getLogcatOutput();

    /** Cek apakah X server sudah terhubung ke LorieView. */
    public static native boolean connected();

    /**
     * BLOCKING — jalankan di background thread!
     * Listens on port 7892 for requestConnection() MAGIC bytes.
     * Saat MAGIC diterima, memanggil sendBroadcast() pada instance ini.
     */
    public native void listenForConnections();

    /**
     * Dipanggil oleh native listenForConnections() saat client mengirim MAGIC.
     * Triggers app untuk setup koneksi renderer ke X server via getXConnection().
     * WAJIB ADA — kalau tidak ada, native akan crash dengan GetMethodID fail.
     */
    public void sendBroadcast() {
        // Hanya proses SEKALI — cegah multiple reconnection loops
        if (connectionEstablished) {
            android.util.Log.i("XLorie", "sendBroadcast: already connected, ignoring");
            return;
        }
        connectionEstablished = true;
        android.util.Log.i("XLorie", "sendBroadcast! Setting up renderer connection...");
        Runnable cb = onConnectionCallback;
        if (cb != null) {
            cb.run();
        }
    }
}
