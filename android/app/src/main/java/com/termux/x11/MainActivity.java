package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;

/**
 * Base class yang WAJIB ada karena libXlorie.so memanggil:
 *   FindClass("com/termux/x11/MainActivity")
 *   GetStaticMethodID("getInstance", "()Lcom/termux/x11/MainActivity;")
 *
 * Tanpa class ini → SIGSEGV saat start().
 *
 * com.nobrain.linux.MainActivity extends class ini, sehingga instanceof check
 * tetap valid dan getInstance() bisa return instance yang benar.
 */
public class MainActivity extends Activity {

    private static MainActivity sInstance;

    /**
     * Dipanggil oleh native code untuk mendapat Activity reference.
     * com.nobrain.linux.MainActivity memanggil setInstance(this) di onCreate().
     */
    public static MainActivity getInstance() {
        return sInstance;
    }

    protected static void setInstance(MainActivity activity) {
        sInstance = activity;
    }

    /** Dipanggil native code untuk toggle soft keyboard. */
    public void toggleKeyboardVisibility() {
        InputMethodManager imm = (InputMethodManager)
            getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        android.view.View view = getCurrentFocus();
        if (view == null) return;
        if (imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } else {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** Dipanggil native code untuk cek status koneksi. */
    public boolean isConnected() {
        return CmdEntryPoint.connected();
    }

    /** Dipanggil native code saat state koneksi X client berubah. */
    public void clientConnectedStateChanged() {}
}
