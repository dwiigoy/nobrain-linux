package com.nobrain.linux;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.termux.x11.CmdEntryPoint;
import com.termux.x11.LorieView;
import com.termux.x11.XLorieConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends com.termux.x11.MainActivity {

    private static final int NAVBAR_HEIGHT_DP = 48;
    private static final int EXTRAKEYS_ROW_HEIGHT_DP = 36;
    private static final String DISPLAY     = ":4";
    private static final String DISPLAY_TCP = "127.0.0.1:4";

    /**
     * Static flag — survives Activity recreation selama process masih hidup.
     * true  = X server sudah jalan, reconnect saja jangan start() lagi.
     * false = belum pernah start, atau process di-kill dan fresh start.
     */
    private static volatile boolean xServerGloballyStarted = false;
    private static CmdEntryPoint savedCmdEntry = null;

    /**
     * LorieView instance di-reuse lintas Activity recreate (swipe-kill lalu
     * buka lagi sementara proses masih hidup). nativeInit() HANYA aman
     * dipanggil sekali per proses: ia register Choreographer frame-callback
     * native yang terus berjalan di main thread selama proses hidup. Kalau
     * Activity baru bikin LorieView baru lalu nativeInit() lagi, callback
     * lama (masih nempel ke state native lama yang baru di-reinit/dibebaskan)
     * akan crash SIGSEGV di Choreographer::dispatchVsync saat vsync berikutnya.
     * Solusi: pakai objek LorieView yang SAMA (globalThiz tetap valid),
     * cuma re-parent ke root Activity baru + surfaceChanged() untuk rebind
     * ke Surface/window baru.
     */
    private static LorieView sLorieView = null;

    private static void setupCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String log = "CRASH on thread " + t.getName() + ":\n" + sw.toString();
                android.util.Log.e("NoBrainCrash", log);
                File f = new File("/sdcard/Download/nobrain-crash.log");
                if (!f.exists()) f.getParentFile().mkdirs();
                try (FileWriter fw = new FileWriter(f, false)) { fw.write(log); }
            } catch (Exception ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private LorieView lorieView;
    private FrameLayout rootView;
    private FrameLayout viewportContainer;
    private FrameLayout bootOverlay;
    private android.widget.TextView bootStatusText;
    private android.widget.TextView[] dotViews = new android.widget.TextView[3];
    private int dotState = 0;
    private LinearLayout navBar;
    private LinearLayout extraKeysBar;
    private android.widget.TextView wsLabel;
    private int _currentWs = 1;
    private final java.util.Map<Integer, Button> modifierButtons = new java.util.HashMap<>();
    private int _navbarPx;
    private int _lastImeBottomPx = -1;
    private boolean _kbForDwm = false;
    private boolean _lastKbForDwm = false;
    private boolean _hardwareKeyboardPresent = false;
    private boolean _inPip = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable _pendingResizeLayout;
    private boolean xServerStarted = false;   // per-Activity instance flag
    private volatile boolean _dmenuLaunchPending = false;
    private volatile boolean _dmenuShowing = false;
    private boolean _keyboardVisible = false;
    private volatile boolean _viewportWatcherStop = false;
    private volatile boolean _bootStatusWatcherStop = false;
    private volatile long _bootSessionStartedAt = 0L;
    private int _activeViewportW = 0;
    private int _activeViewportH = 0;

    private final Runnable dotAnimator = new Runnable() {
        @Override public void run() {
            if (bootOverlay == null || bootOverlay.getVisibility() != View.VISIBLE) return;
            dotState = (dotState + 1) % 4;
            for (int i = 0; i < 3; i++)
                dotViews[i].setAlpha(i < dotState ? 1.0f : 0.12f);
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= 31) {
            getSplashScreen().setOnExitAnimationListener(v -> v.remove());
        }
        super.onCreate(savedInstanceState);
        setupCrashLogger();
        clearShutdownMarkers();
        // WAJIB sebelum referensi LorieView/CmdEntryPoint apapun (fresh start saja):
        // patch libXlorie.so dengan resolusi yang sesuai layar device ini.
        //
        // JANGAN panggil ini lagi saat reconnect! patchXLorieAndSetConfig() menulis ulang
        // (truncate+rewrite) file libXlorie-patched.so YANG SEDANG di-mmap() DAN AKTIF
        // DIEKSEKUSI oleh X server thread (thread terpisah dari main/Activity thread,
        // tetap hidup sejak X server start, lintas Activity recreate). Halaman .got.plt
        // yang belum pernah di-fault-in akan terbaca ULANG dari file yang baru ditulis
        // (berisi nilai link-time PLT0 yang belum di-relocate dynamic linker), bukan
        // alamat resolved yang sudah dipakai sejak awal -> pemanggilan PLT (mis.
        // __errno@plt di WaitForSomething()) loncat ke .plt[0] mentah -> SIGSEGV
        // SEGV_MAPERR fault addr == vaddr .plt section, di X server thread, dalam
        // hitungan puluhan ms setelah reopen. Fresh start aman karena penulisan file
        // terjadi SEBELUM System.load() pertama kali me-mmap library ini.
        if (!xServerGloballyStarted) {
            patchXLorieAndSetConfig();
        }
        _hardwareKeyboardPresent = hasVisibleHardwareKeyboard(getResources().getConfiguration());
        clearShutdownMarkers();
        startRestartWatcher();
        setInstance(this);
        // Reconnect (X server sudah jalan dari proses sebelumnya): JANGAN minta
        // izin lagi. requestPermissions() men-trigger GrantPermissionsActivity
        // yang langsung menutupi window kita -> surfaceDestroyed() pada
        // sLorieView yang sedang aktif dipakai render thread (Choreographer
        // callback masih jalan) -> race di renderer native -> SIGSEGV.
        // Permintaan izin sudah cukup dilakukan sekali saat fresh start.
        if (!xServerGloballyStarted) {
            requestStoragePermissions();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        rootView = root;

        // Track keyboard visibility dari actual UI (bukan flag manual — tidak bisa desync)
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            int screenH = getWindow().getDecorView().getRootView().getHeight();
            _keyboardVisible = (screenH - r.bottom) > screenH * 0.15;
            // Fallback utk API<30: WindowInsetsAnimation.Callback (smooth) belum
            // tersedia, snap layout extra-keys/lorieView dari sini saja.
            if (Build.VERSION.SDK_INT < 30) {
                applyKeyboardLayout(Math.max(0, screenH - r.bottom));
            }
        });

        // LorieView — X server render target. Reuse instance lama kalau ada
        // (reconnect setelah Activity di-recreate, proses masih hidup):
        // jangan panggil nativeInit() lagi, cukup re-parent + surfaceChanged()
        // untuk rebind ke Surface/window Activity yang baru. Lihat javadoc
        // sLorieView di atas untuk alasan kenapa nativeInit() ke-2 = SIGSEGV.
        if (sLorieView == null) {
            sLorieView = new LorieView(this);
            // Saat surface di-destroy (app ke background), set flag supaya
            // surfaceCreated berikutnya (di Activity baru, instance LorieView
            // yang sama) tidak panggil nativeInit() lagi — cukup
            // surfaceChanged(newSurface) untuk hot-swap ke window baru.
            sLorieView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder h) {}
                @Override public void surfaceChanged(android.view.SurfaceHolder h,
                                                      int f, int w, int ht) {}
                @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
                    if (xServerGloballyStarted) {
                        LorieView.skipNextNativeInit = true;
                    }
                }
            });
        } else {
            ViewGroup oldParent = (ViewGroup) sLorieView.getParent();
            if (oldParent != null) oldParent.removeView(sLorieView);
            // Safety net kalau surfaceDestroyed di atas belum sempat fire
            // sebelum re-parent ini (mis. window lama belum sempat teardown).
            if (xServerGloballyStarted) {
                LorieView.skipNextNativeInit = true;
            }
        }
        lorieView = sLorieView;
        int navbarPx = getEffectiveNavbarPx();
        _navbarPx = navbarPx;

        // viewportContainer: ukurannya menyusut (bottomMargin) saat
        // keyboard+extraKeysBar tampil; clipChildren default true memotong
        // lorieView. lorieView SENDIRI fixed sebesar viewport fisik Android.
        // Screen scaling sekarang tajam: X11 tetap native, UI scale diterapkan
        // lewat DPI/font/app scale di sesi Linux.
        viewportContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        containerLp.bottomMargin = navbarPx;
        root.addView(viewportContainer, containerLp);

        viewportContainer.addView(lorieView,
            new FrameLayout.LayoutParams(XLorieConfig.viewW, XLorieConfig.viewH));

        addNavBar(root, navbarPx);
        addExtraKeysBar(root, navbarPx);
        updateNavBarVisibility();

        // Smooth per-frame extra-keys/lorieView resize synced dgn animasi
        // slide IME (API 30+). API<30 pakai fallback addOnGlobalLayoutListener
        // di atas (snap, tanpa animasi).
        if (Build.VERSION.SDK_INT >= 30) {
            root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(
                    android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
                @Override
                public android.view.WindowInsets onProgress(android.view.WindowInsets insets,
                        java.util.List<android.view.WindowInsetsAnimation> runningAnimations) {
                    applyKeyboardLayout(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                    return insets;
                }
            });
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                applyKeyboardLayout(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                return insets;
            });
        }

        // Boot overlay — hanya fresh start, skip kalau reconnect
        if (!xServerGloballyStarted) {
            bootOverlay = buildBootOverlay();
            root.addView(bootOverlay);
            showStatus("Initializing...");
            handler.postDelayed(dotAnimator, 400);
        }

        // Exclude gesture zones (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            lorieView.post(() -> lorieView.setSystemGestureExclusionRects(
                java.util.Collections.singletonList(
                    new android.graphics.Rect(0, 0,
                        lorieView.getWidth(), lorieView.getHeight()))));
        }

        // Show load error if XLorie failed to load
        if (CmdEntryPoint.loadError != null) {
            android.widget.Toast.makeText(this,
                "XLorie load FAILED: " + CmdEntryPoint.loadError,
                android.widget.Toast.LENGTH_LONG).show();
            android.util.Log.e("XLorie", "load error: " + CmdEntryPoint.loadError);
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                    new java.io.File("/sdcard/Download/nobrain-crash.log"), false);
                fw.write("XLorie loadLibrary FAILED:\n" + CmdEntryPoint.loadError);
                fw.close();
            } catch (Exception ignored) {}
        }

        if (ProotManager.isRootfsExtracted(this)) {
            showStatus("Starting services...");
            startServices();
        } else {
            showStatus("Extracting rootfs (~2 min first run)...");
            new Thread(() -> {
                try {
                    ProotManager.setupBinaries(this);
                    ProotManager.extractRootfs(this);
                    showStatus("Starting services...");
                    handler.post(this::startServices);
                } catch (Exception e) {
                    showStatus("ERROR: " + e.getMessage());
                    android.util.Log.e("MainActivity", "setup failed: " + e.getMessage());
                }
            }).start();
        }
        startViewportWatcher();
    }

    private void showStatus(String msg) {
        handler.post(() -> {
            if (bootStatusText != null) bootStatusText.setText(msg);
            if (bootOverlay != null) {
                bootOverlay.setVisibility(View.VISIBLE);
                bootOverlay.setAlpha(1.0f);
            }
        });
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                new java.io.File("/sdcard/Download/nobrain-status.log"), true);
            fw.write(new java.util.Date() + ": " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    private void hideStatus() {
        handler.post(() -> {
            if (bootOverlay == null) return;
            handler.removeCallbacks(dotAnimator);
            bootOverlay.animate()
                .alpha(0f)
                .setDuration(350)
                .withEndAction(() -> {
                    bootOverlay.setVisibility(View.GONE);
                    focusLorieInput();
                })
                .start();
        });
    }

    private void focusLorieInput() {
        if (lorieView == null) return;
        lorieView.setFocusable(true);
        lorieView.setFocusableInTouchMode(true);
        lorieView.requestFocus();
    }

    private void waitForDesktopAndHide() {
        new Thread(() -> {
            File dataDir = ProotManager.getDataDir(this);
            File ready = new File(dataDir, "tmp/nobrain-session-ready");
            File status = new File(dataDir, "tmp/nobrain-boot-status");
            String lastStatus = "";
            long started = android.os.SystemClock.uptimeMillis();
            boolean slowMessageShown = false;

            while (!_bootStatusWatcherStop) {
                try {
                    if (status.exists()
                            && status.lastModified() >= _bootSessionStartedAt - 1000L) {
                        String current = new String(
                            java.nio.file.Files.readAllBytes(status.toPath())).trim();
                        if (!current.isEmpty() && !current.equals(lastStatus)) {
                            lastStatus = current;
                            showStatus(current);
                        }
                    }
                    if (ready.exists()
                            && ready.lastModified() >= _bootSessionStartedAt - 1000L) {
                        showStatus("Desktop ready");
                        Thread.sleep(450);
                        handler.post(this::hideStatus);
                        return;
                    }
                    if (!slowMessageShown
                            && android.os.SystemClock.uptimeMillis() - started > 600000) {
                        slowMessageShown = true;
                        showStatus("Still preparing the Linux session...");
                    }
                    Thread.sleep(250);
                } catch (Exception ignored) {
                    try { Thread.sleep(250); } catch (Exception ignoredAgain) {}
                }
            }
        }, "desktop-watcher").start();
    }

    private boolean isDwmRunning() {
        java.io.File proc = new java.io.File("/proc");
        java.io.File[] pids = proc.listFiles();
        if (pids == null) return false;
        for (java.io.File pidDir : pids) {
            try {
                java.io.File cmdline = new java.io.File(pidDir, "cmdline");
                byte[] bytes = java.nio.file.Files.readAllBytes(cmdline.toPath());
                String cmd = new String(bytes).split("\0")[0].trim();
                if (cmd.equals("dwm")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private FrameLayout buildBootOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF000000);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // Center block: title + dots
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.CENTER_VERTICAL;
        center.setLayoutParams(clp);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("NoBrain Linux");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 36);
        title.setTypeface(android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
        title.setGravity(android.view.Gravity.CENTER);
        title.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            title.setLetterSpacing(0f);
        title.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        center.addView(title);

        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dpToPx(22);
        dotsRow.setLayoutParams(dlp);

        for (int i = 0; i < 3; i++) {
            android.widget.TextView dot = new android.widget.TextView(this);
            dot.setText("●");
            dot.setTextColor(0xFFFFFFFF);
            dot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            dot.setAlpha(0.12f);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            dotLp.leftMargin  = dpToPx(9);
            dotLp.rightMargin = dpToPx(9);
            dot.setLayoutParams(dotLp);
            dotViews[i] = dot;
            dotsRow.addView(dot);
        }
        center.addView(dotsRow);

        // Keep the active bootstrap step readable while the desktop is hidden.
        bootStatusText = new android.widget.TextView(this);
        bootStatusText.setTextColor(0xFFB8C0CC);
        bootStatusText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        bootStatusText.setGravity(android.view.Gravity.CENTER);
        bootStatusText.setMaxLines(2);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dpToPx(18);
        slp.leftMargin = dpToPx(24);
        slp.rightMargin = dpToPx(24);
        bootStatusText.setLayoutParams(slp);
        center.addView(bootStatusText);
        overlay.addView(center);

        return overlay;
    }

    private void startServices() {
        if (!xServerGloballyStarted) {
            _bootSessionStartedAt = System.currentTimeMillis();
            try {
                File forceRestart = new File(
                    ProotManager.getDataDir(this), "tmp/nobrain-force-restart");
                forceRestart.getParentFile().mkdirs();
                forceRestart.createNewFile();
            } catch (Exception ignored) {}
        }
        Intent svc = new Intent(this, PocketService.class);
        svc.putExtra("width", getDisplayPx()[0]);
        svc.putExtra("height", getDisplayPx()[1] - getEffectiveNavbarPx());
        startForegroundService(svc);

        showStatus("proot starting...");
        // Delay lebih pendek saat reconnect (proot sudah jalan),
        // lebih panjang saat fresh start (tunggu proot init).
        long delay = xServerGloballyStarted ? 500L : 3000L;
        handler.postDelayed(this::startXServer, delay);
    }

    // ── X Server startup (fresh) ──────────────────────────────────

    private void startXServer() {
        if (xServerStarted) return;
        xServerStarted = true;

        if (xServerGloballyStarted) {
            // X server masih jalan (proot service hidup), tapi Activity baru dibuat (swipe-kill).
            // nativeInit() sudah jalan (globalThiz diupdate ke LorieView baru).
            // Perlu requestConnection() ulang untuk sambungkan renderer ke X server.
            showStatus("Reconnecting display...");
            final CmdEntryPoint reconnEntry = savedCmdEntry;
            if (reconnEntry != null) {
                CmdEntryPoint.onConnectionCallback = () -> {
                    new Thread(() -> {
                        try {
                            android.os.ParcelFileDescriptor pfd = reconnEntry.getXConnection();
                            android.util.Log.i("XLorie", "reconnect getXConnection: " + pfd);
                            if (pfd == null) return;
                            final int fd = pfd.detachFd();
                            for (int i = 0; i < 100; i++) {
                                if (CmdEntryPoint.connected()) break;
                                Thread.sleep(10);
                            }
                            handler.post(() -> {
                                lorieView.connect(fd);
                                lorieView.setupClipboardSync();
                                android.util.Log.i("XLorie", "reconnected fd=" + fd);
                            });
                        } catch (Exception e) {
                            android.util.Log.e("XLorie", "reconnect error: " + e);
                            handler.post(() -> showStatus("Reconnect failed, restart app"));
                        }
                    }).start();
                };
                new Thread(() -> {
                    for (int i = 0; i < 60; i++) {
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                        boolean req = lorieView.requestConnection();
                        android.util.Log.i("XLorie", "reconnect requestConnection " + (i+1) + ": " + req);
                        if (req) break;
                    }
                }, "reconnect").start();
            } else {
                // Tidak ada savedCmdEntry — fallback, tidak ada overlay di reconnect
            }
            return;
        }

        // Fresh start: X server belum pernah jalan di proses ini.
        new Thread(() -> {
            try {
                File dataDir = ProotManager.getDataDir(this);
                File tmpDir  = new File(dataDir, "tmp");
                new File(tmpDir, ".X11-unix").mkdirs();

                String xkbReal = dataDir.getAbsolutePath() + "/usr/share/xkeyboard-config-2";
                String xkbSym  = dataDir.getAbsolutePath() + "/usr/share/X11/xkb";
                final String xkbPath = new File(xkbReal).exists() ? xkbReal : xkbSym;

                try { android.system.Os.setenv("TMPDIR", tmpDir.getAbsolutePath(), true); }
                catch (Exception ignored) {}
                try { android.system.Os.setenv("XKB_CONFIG_ROOT", xkbPath, true); }
                catch (Exception ignored) {}

                try {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(
                        "/sdcard/Download/xlorie-stderr.log", false);
                    android.system.Os.dup2(fos.getFD(), 1);
                    android.system.Os.dup2(fos.getFD(), 2);
                    fos.close();
                } catch (Exception ignored) {}

                // Hapus stale socket agar XLorie bisa bind baru
                new File(new File(tmpDir, ".X11-unix"), "X4").delete();
                new File(new File(tmpDir, ".X11-unix"), "X4-lock").delete();

                xServerGloballyStarted = true;
                final CmdEntryPoint cmdEntry = new CmdEntryPoint();
                savedCmdEntry = cmdEntry;
                CmdEntryPoint.connectionEstablished = false;

                CmdEntryPoint.onConnectionCallback = () -> {
                    new Thread(() -> {
                        try {
                            android.os.ParcelFileDescriptor pfd = cmdEntry.getXConnection();
                            android.util.Log.i("XLorie", "getXConnection: " + pfd);
                            if (pfd == null) return;
                            final int fd = pfd.detachFd();
                            for (int i = 0; i < 100; i++) {
                                if (CmdEntryPoint.connected()) break;
                                Thread.sleep(10);
                            }
                            handler.post(() -> {
                                lorieView.connect(fd);
                                lorieView.setupClipboardSync();
                                waitForDesktopAndHide();
                                android.util.Log.i("XLorie", "renderer connected fd=" + fd);
                            });
                        } catch (Exception e) {
                            android.util.Log.e("XLorie", "getXConnection error: " + e);
                        }
                    }).start();
                };

                new Thread(() -> cmdEntry.listenForConnections(), "xlorie-listen").start();

                showStatus("Starting X server...");
                handler.post(() -> {
                    try { android.system.Os.setenv("TERMUX_X11_DEBUG", "1", true); }
                    catch (Exception ignored) {}
                    try { android.system.Os.setenv("TERMUX_X11_FORCE_FLIP", "1", true); }
                    catch (Exception ignored) {}

                    android.util.Log.i("XLorie", "Starting X server...");
                    boolean ok = CmdEntryPoint.start(new String[]{
                        DISPLAY, "-ac", "-listen", "tcp", "-xkbdir", xkbPath, "-legacy-drawing"
                    });
                    android.util.Log.i("XLorie", "X server start returned: " + ok);

                    if (!ok) {
                        showStatus("X server failed");
                        xServerGloballyStarted = false;
                        xServerStarted = false;
                        return;
                    }

                    new Thread(() -> {
                        for (int i = 0; i < 60; i++) {
                            try { Thread.sleep(500); } catch (Exception ignored) {}
                            boolean req = lorieView.requestConnection();
                            android.util.Log.i("XLorie", "requestConnection " + (i+1) + ": " + req);
                            if (req) break;
                        }
                    }, "req-conn").start();
                });

            } catch (Exception e) {
                android.util.Log.e("XLorie", "startXServer failed: " + e.getMessage());
                xServerStarted = false;
                xServerGloballyStarted = false;
            }
        }).start();
    }

    @Override
    public void toggleKeyboardVisibility() {
        if (_keyboardVisible) {
            hideKeyboard();
        } else {
            _kbForDwm = true;
            showKeyboard();
            applyKeyboardLayout(_lastImeBottomPx);
        }
    }

    // ── Navbar ────────────────────────────────────────────────────

    private void initCurrentWs() {
        try {
            File wsFile = new File(ProotManager.getDataDir(this), "tmp/ws");
            if (wsFile.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(wsFile.toPath())).trim();
                int v = Integer.parseInt(s);
                if (v >= 1 && v <= 9) _currentWs = v;
            }
        } catch (Exception ignored) {}
    }

    private void updateWsLabel() {
        if (wsLabel != null) {
            handler.post(() -> wsLabel.setText(String.valueOf(_currentWs)));
        }
    }

    private void addNavBar(FrameLayout root, int heightPx) {
        initCurrentWs();

        LinearLayout bar = new LinearLayout(this);
        navBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xEE121212);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        lp.gravity = android.view.Gravity.BOTTOM;
        bar.setLayoutParams(lp);

        addNavBtn(bar, "◄", () -> {
            int ws = Math.max(1, _currentWs - 1);
            if (ws != _currentWs) {
                _currentWs = ws;
                updateWsLabel();
                sendX11Cmd("printf '" + ws + "\\n' >/tmp/ws;nobrain-view-tag " + ws);
            }
        });

        addNavBtn(bar, "▲", () -> toggleKeyboardVisibility());
        addNavBtn(bar, "✕", () -> sendX11Cmd("xdotool key alt+shift+c"));
        addNavBtn(bar, "▼", () -> spawnMenu());

        addNavBtn(bar, "►", () -> {
            int ws = Math.min(9, _currentWs + 1);
            if (ws != _currentWs) {
                _currentWs = ws;
                updateWsLabel();
                sendX11Cmd("printf '" + ws + "\\n' >/tmp/ws;nobrain-view-tag " + ws);
            }
        });

        root.addView(bar);
    }

    private void addNavBtn(LinearLayout parent, String label, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(0xFFDDDDDD);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        btn.setFocusable(false);
        btn.setFocusableInTouchMode(false);
        btn.setBackground(null);
        btn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn);
    }

    private boolean hasVisibleHardwareKeyboard(Configuration config) {
        if (config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES)
            return false;

        int[] requiredKeys = {
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER
        };
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            if ((device.getSources() & InputDevice.SOURCE_KEYBOARD)
                    != InputDevice.SOURCE_KEYBOARD) continue;

            boolean[] present = device.hasKeys(requiredKeys);
            boolean alphabetic = present != null && present.length == requiredKeys.length;
            if (alphabetic) {
                for (boolean hasKey : present) alphabetic &= hasKey;
            }
            if (alphabetic) return true;
        }
        return false;
    }

    private int getEffectiveNavbarPx() {
        return hasVisibleHardwareKeyboard(getResources().getConfiguration())
            ? 0 : dpToPx(NAVBAR_HEIGHT_DP);
    }

    private void updateNavBarVisibility() {
        if (navBar == null) return;
        navBar.setVisibility((_inPip || _hardwareKeyboardPresent) ? View.GONE : View.VISIBLE);
    }

    private void applyHardwareKeyboardMode(boolean resizeX) {
        boolean hasHardwareKeyboard = hasVisibleHardwareKeyboard(getResources().getConfiguration());
        int navbarPx = hasHardwareKeyboard ? 0 : dpToPx(NAVBAR_HEIGHT_DP);
        boolean changed = hasHardwareKeyboard != _hardwareKeyboardPresent || navbarPx != _navbarPx;
        _hardwareKeyboardPresent = hasHardwareKeyboard;
        _navbarPx = navbarPx;
        updateNavBarVisibility();

        boolean softKeyboardOpen = _lastImeBottomPx > 0;
        if (extraKeysBar != null) {
            FrameLayout.LayoutParams ekLp = (FrameLayout.LayoutParams) extraKeysBar.getLayoutParams();
            if (ekLp.bottomMargin != navbarPx && !softKeyboardOpen) {
                ekLp.bottomMargin = navbarPx;
                extraKeysBar.setLayoutParams(ekLp);
            }
        }

        if (viewportContainer != null && !softKeyboardOpen) {
            FrameLayout.LayoutParams cLp = (FrameLayout.LayoutParams) viewportContainer.getLayoutParams();
            if (cLp.bottomMargin != navbarPx) {
                cLp.bottomMargin = navbarPx;
                viewportContainer.setLayoutParams(cLp);
            }
        }

        if (changed && resizeX && rootView != null) {
            rootView.post(this::resizeXForCurrentDisplay);
        }
    }

    // ── Extra-keys toolbar (Esc/Ctrl/Alt/Shift/Super/Tab/Home/End/PgUp/PgDn/arrows) ──

    private void addExtraKeysBar(FrameLayout root, int navbarPx) {
        LinearLayout bar = new LinearLayout(this);
        extraKeysBar = bar;
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xEE121212);
        bar.setVisibility(View.GONE);

        int rowPx = dpToPx(EXTRAKEYS_ROW_HEIGHT_DP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 2);
        lp.gravity = android.view.Gravity.BOTTOM;
        lp.bottomMargin = navbarPx;
        bar.setLayoutParams(lp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addExtraKeyBtn(row1, "Esc",  () -> lorieView.tapKey(KeyEvent.KEYCODE_ESCAPE));
        addModifierBtn(row1, "Shift", KeyEvent.KEYCODE_SHIFT_LEFT);
        addExtraKeyBtn(row1, "PgUp", () -> lorieView.tapKey(KeyEvent.KEYCODE_PAGE_UP));
        addExtraKeyBtn(row1, "PgDn", () -> lorieView.tapKey(KeyEvent.KEYCODE_PAGE_DOWN));
        addExtraKeyBtn(row1, "Home", () -> lorieView.tapKey(KeyEvent.KEYCODE_MOVE_HOME));
        addExtraKeyBtn(row1, "↑",    () -> lorieView.tapKey(KeyEvent.KEYCODE_DPAD_UP));
        addExtraKeyBtn(row1, "End",  () -> lorieView.tapKey(KeyEvent.KEYCODE_MOVE_END));

        addExtraKeyBtn(row2, "Tab",   () -> lorieView.tapTerminalTab());
        addModifierBtn(row2, "Ctrl",  KeyEvent.KEYCODE_CTRL_LEFT);
        addModifierBtn(row2, "Super", KeyEvent.KEYCODE_META_LEFT);
        addModifierBtn(row2, "Alt",   KeyEvent.KEYCODE_ALT_LEFT);
        addExtraKeyBtn(row2, "←",    () -> lorieView.tapKey(KeyEvent.KEYCODE_DPAD_LEFT));
        addExtraKeyBtn(row2, "↓",    () -> lorieView.tapKey(KeyEvent.KEYCODE_DPAD_DOWN));
        addExtraKeyBtn(row2, "→",    () -> lorieView.tapKey(KeyEvent.KEYCODE_DPAD_RIGHT));

        bar.addView(row1);
        bar.addView(row2);
        root.addView(bar);

        lorieView.setModifierListener((keyCode, armed) -> handler.post(() -> {
            Button b = modifierButtons.get(keyCode);
            if (b != null) b.setBackgroundColor(armed ? 0xFF3578E5 : 0);
        }));
    }

    private Button addExtraKeyBtn(LinearLayout parent, String label, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(0xFFDDDDDD);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setFocusable(false);
        btn.setFocusableInTouchMode(false);
        btn.setBackground(null);
        btn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> onClick.run());
        parent.addView(btn);
        return btn;
    }

    private void addModifierBtn(LinearLayout parent, String label, int keyCode) {
        Button btn = addExtraKeyBtn(parent, label, () -> lorieView.armOrToggleModifier(keyCode));
        modifierButtons.put(keyCode, btn);
    }

    /**
     * Resize viewportContainer dan tampilkan/sembunyikan extraKeysBar sesuai
     * tinggi IME (imeBottomPx=0 kalau keyboard tertutup), lalu update pan
     * lorieView. viewportContainer adalah plain FrameLayout (bukan
     * SurfaceView) -- setLayoutParams di sini TIDAK PERNAH memicu native
     * surfaceChanged()/setViewport(), jadi aman dipanggil kapan saja
     * (termasuk cold-start sebelum XLorie/proot siap -- ANR Sesi 61 hilang
     * total krn lorieView sendiri tidak pernah di-resize lagi).
     *
     * navBar (◄▲✕▼►) tertutup keyboard saat kbVisible (imeBottomPx >
     * navbarPx), jadi extraKeysBar & viewportContainer TIDAK pakai
     * _navbarPx sebagai offset tambahan saat keyboard terbuka -- itu yang
     * bikin gap kegedean sebelumnya.
     *
     * Juga menulis /tmp/.nobrain_kb_reserve (dibaca dwm, lihat
     * checkkbreserve/applykbreserve di dwm.c) -- hanya non-zero kalau
     * _kbForDwm true (keyboard dibuka via ▲, bukan ▼/nobrain-menu), supaya
     * dwm reflow window st/chromium agar kursor tetap visible.
     */
    private void applyKeyboardLayout(int imeBottomPx) {
        if (imeBottomPx == _lastImeBottomPx && _kbForDwm == _lastKbForDwm) return;
        _lastImeBottomPx = imeBottomPx;
        _lastKbForDwm = _kbForDwm;
        boolean kbVisible = imeBottomPx > 0;
        int extraKeysPx = dpToPx(EXTRAKEYS_ROW_HEIGHT_DP) * 2;

        FrameLayout.LayoutParams ekLp = (FrameLayout.LayoutParams) extraKeysBar.getLayoutParams();
        if (ekLp.bottomMargin != imeBottomPx) {
            ekLp.bottomMargin = imeBottomPx;
            extraKeysBar.setLayoutParams(ekLp);
        }
        extraKeysBar.setVisibility(kbVisible ? View.VISIBLE : View.GONE);

        int newContainerMargin = kbVisible ? (imeBottomPx + extraKeysPx) : _navbarPx;
        FrameLayout.LayoutParams cLp = (FrameLayout.LayoutParams) viewportContainer.getLayoutParams();
        if (cLp.bottomMargin != newContainerMargin) {
            cLp.bottomMargin = newContainerMargin;
            viewportContainer.setLayoutParams(cLp);
        }

        // xResH == screenH - navbarPx (lihat patchXLorieAndSetConfig), jadi
        // konten yg ke-clip di bawah = newContainerMargin - navbarPx.
        // _kbForDwm=true -> dwm sudah reflow window via kbreserve (window
        // ga pernah masuk area keyboard+toolbar), jadi pan jadi redundant
        // (malah motong bagian atas window). Pan hanya dipakai sbg fallback
        // saat _kbForDwm=false (keyboard via nobrain-menu, dwm tidak reflow).
        int hiddenPx = (kbVisible && !_kbForDwm) ? Math.max(0, newContainerMargin - _navbarPx) : 0;
        lorieView.setViewportPan(hiddenPx);

        // kbreserve = total area kanvas X11 (tinggi xResH) yg ketutup
        // keyboard+toolbar. root.height() = xResH + navbarPx (lihat
        // getDisplayPx/patchXLorieAndSetConfig), jadi viewportContainer
        // (tinggi = root.height()-newContainerMargin) selalu navbarPx px
        // LEBIH TINGGI dari sisa area xResH di luar kbreserve
        // (xResH-kbReservePx) -- tanpa koreksi ini, viewport nampilin
        // navbarPx px tambahan dari bg kosong dwm di atas extraKeysBar
        // (gap hitam). Samakan dgn hiddenPx di atas: kurangi navbarPx.
        int kbReservePx = kbVisible ? Math.max(0, newContainerMargin - _navbarPx) : 0;
        int kbReserveLogicalPx = kbReservePx;
        if (kbReservePx > 0 && XLorieConfig.viewH > 0) {
            kbReserveLogicalPx = Math.round(kbReservePx * (float) LorieView.xResH / (float) XLorieConfig.viewH);
        }
        writeKbReserve(_kbForDwm ? kbReserveLogicalPx : 0);
    }

    private void writeKbReserve(int px) {
        new Thread(() -> {
            try (FileWriter fw = new FileWriter(
                    new File(ProotManager.getDataDir(this), "tmp/.nobrain_kb_reserve"), false)) {
                fw.write(Integer.toString(px));
            } catch (Exception e) {
                android.util.Log.e("KbReserve", "write failed: " + e.getMessage());
            }
        }).start();
    }

    private void sendX11Cmd(String cmd) {
        new Thread(() -> {
            try {
                if (writeGuestCommandFast(cmd)) return;
                String fullCmd = "DISPLAY=" + DISPLAY_TCP + " " + cmd;
                Process p = ProotManager.runCommand(this, fullCmd);
                p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
                p.destroy();
            } catch (Exception e) {
                android.util.Log.e("NavBar", "cmd failed: " + e.getMessage());
            }
        }).start();
    }

    private boolean writeGuestCommandFast(String cmd) {
        java.io.File fifo = new java.io.File(
            ProotManager.getDataDir(this), "tmp/nobrain-cmd");
        if (!fifo.exists()) return false;
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(fifo.getAbsolutePath(),
                android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NONBLOCK, 0);
            byte[] bytes = (cmd + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            android.system.Os.write(fd, bytes, 0, bytes.length);
            android.system.Os.close(fd);
            return true;
        } catch (Exception e) {
            try { if (fd != null) android.system.Os.close(fd); } catch (Exception ignored) {}
            android.util.Log.d("NavBar", "fast command unavailable: " + e.getMessage());
            return false;
        }
    }

    private void spawnMenu() {
        if (_dmenuLaunchPending) return;
        if (_dmenuShowing) {
            _dmenuShowing = false;
            sendX11Cmd("pkill -x dmenu");
            hideKeyboard();
            return;
        }
        _dmenuLaunchPending = true;
        _kbForDwm = false;
        new Thread(() -> {
            java.io.File dataDir = ProotManager.getDataDir(this);
            java.io.File ready = new java.io.File(dataDir, "tmp/nobrain-session-ready");
            java.io.File done = new java.io.File(
                dataDir, "tmp/nobrain-menu-done");
            java.io.File visible = new java.io.File(
                dataDir, "tmp/nobrain-menu-visible");
            try {
                long readyDeadline = android.os.SystemClock.uptimeMillis() + 20000;
                while (_dmenuLaunchPending && !ready.exists()
                        && android.os.SystemClock.uptimeMillis() < readyDeadline) {
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                }
                if (!_dmenuLaunchPending) return;
                if (!ready.exists()) {
                    throw new IllegalStateException("session command channel not ready");
                }

                done.delete();
                visible.delete();

                long dispatchDeadline = android.os.SystemClock.uptimeMillis() + 3000;
                boolean dispatched = false;
                while (_dmenuLaunchPending && !dispatched
                        && android.os.SystemClock.uptimeMillis() < dispatchDeadline) {
                    dispatched = writeGuestCommandFast("/usr/local/bin/nobrain-menu");
                    if (!dispatched) {
                        try { Thread.sleep(50); } catch (Exception ignored) {}
                    }
                }
                if (!dispatched) throw new IllegalStateException("menu dispatch failed");

                while ((_dmenuLaunchPending || _dmenuShowing) && !done.exists()) {
                    if (_dmenuLaunchPending && visible.exists()) {
                        _dmenuLaunchPending = false;
                        _dmenuShowing = true;
                        handler.postDelayed(() -> {
                            if (_dmenuShowing) {
                                showKeyboard();
                                applyKeyboardLayout(_lastImeBottomPx);
                            }
                        }, 120L);
                    }
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                android.util.Log.e("NavBar", "spawn menu failed: " + e.getMessage());
            } finally {
                _dmenuLaunchPending = false;
                _dmenuShowing = false;
                visible.delete();
                done.delete();
                handler.post(this::hideKeyboard);
            }
        }).start();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        focusLorieInput();
        imm.restartInput(lorieView);
        lorieView.post(() -> imm.showSoftInput(lorieView, InputMethodManager.SHOW_FORCED));
    }

    private void hideKeyboard() {
        _kbForDwm = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        imm.hideSoftInputFromWindow(lorieView.getWindowToken(), 0);
    }

    // ── XLorie dynamic resolution patch ──────────────────────────

    /**
     * Menghitung resolusi X server yang sesuai layar device ini, lalu
     * membuat salinan libXlorie.so di filesDir dengan bytes resolusi yang
     * sudah di-patch. Hasilnya disimpan di XLorieConfig agar LorieView dan
     * CmdEntryPoint meload versi yang benar.
     *
     * HARUS dipanggil sebagai hal PERTAMA di onCreate(), sebelum referensi
     * apapun ke LorieView atau CmdEntryPoint (yang akan trigger static init
     * dan System.loadLibrary).
     */
    // Offsets of the two int32 LE resolution fields (lorieScreen.root.width/height,
    // InitOutput.c) in libXlorie.so .data section.
    // Found via binary analysis: search stripped .so for u32(1280) + u32(1024) + "screen\0"
    // (compile-time defaults of lorieScreen.root.{width,height,name}).
    // Recalibrated for Build 35 (RESOLUTION_X/Y getenv override added to
    // OsVendorInit() in InitOutput.c, shifted .data by +128 vs Build 33/34).
    // NOTE: as of Build 35 this binary patch is belt-and-suspenders only --
    // the actual resolution is now applied via the RESOLUTION_X/RESOLUTION_Y
    // env vars set below, which OsVendorInit() reads directly (works even if
    // the patched .so gets deduped against an already-loaded same-soname copy).
    private static final int XLORIE_W_OFFSET = 3362108;
    private static final int XLORIE_H_OFFSET = 3362112;

    private int readDisplayZoom() {
        // Config file: proot ~/.config/nobrain-scale — integer 50-300.
        // Legacy float format (0.25-1.0) is ignored (returns 100).
        try {
            java.io.File cfg = new java.io.File(
                getFilesDir(), "nobrain/home/nobrain/.config/nobrain-scale");
            if (!cfg.exists()) return 100;
            String s = new String(java.nio.file.Files.readAllBytes(cfg.toPath())).trim();
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                float v = Float.parseFloat(line.split("\\s+")[0]);
                if (v > 0 && v <= 4) return 100; // legacy float format
                return Math.max(50, Math.min(300, (int) v));
            }
        } catch (Exception ignored) {}
        return 100;
    }

    private float readScreenScaleRatio() {
        // Config file: proot ~/.config/nobrain-screen-scale.
        // Accepts Tiny-like float (0.65) or percent (65). Default 1.0.
        try {
            java.io.File cfg = new java.io.File(
                getFilesDir(), "nobrain/home/nobrain/.config/nobrain-screen-scale");
            if (!cfg.exists()) return 1.0f;
            String s = new String(java.nio.file.Files.readAllBytes(cfg.toPath())).trim();
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                float v = Float.parseFloat(line.split("\\s+")[0]);
                if (v > 1.0f) v = v / 100.0f;
                if (v > 0.0f) return Math.max(0.50f, Math.min(1.0f, v));
            }
        } catch (Exception ignored) {}
        return 1.0f;
    }

    private int logicalFromPhysical(int physicalPx, float scaleRatio) {
        // Sharp scaling keeps the X11 framebuffer at native physical size.
        // The user-facing ratio is applied inside the Linux session as DPI/app scale.
        return Math.max(1, physicalPx);
    }

    private void patchXLorieAndSetConfig() {
        StringBuilder diag = new StringBuilder();
        java.io.File logFile = new java.io.File(getFilesDir(), "xlorie-patch.log");
        try {
            int[] px = getDisplayPx();
            int screenW  = px[0];
            int screenH  = px[1];
            int navbarPx = getEffectiveNavbarPx();

            int viewW = Math.max(320, screenW);
            int viewH = Math.max(480, screenH - navbarPx);
            float screenScale = readScreenScaleRatio();
            int xW = Math.max(320, logicalFromPhysical(viewW, screenScale));
            int xH = Math.max(480, logicalFromPhysical(viewH, screenScale));
            int zoom = readDisplayZoom();
            int scalePercent = Math.round(screenScale * 100.0f);
            XLorieConfig.xZoomPercent = zoom;
            XLorieConfig.viewW = viewW;
            XLorieConfig.viewH = viewH;
            XLorieConfig.screenScalePercent = scalePercent;
            diag.append("screen=").append(screenW).append("x").append(screenH)
                .append(" nav=").append(navbarPx)
                .append(" view=").append(viewW).append("x").append(viewH)
                .append(" screenScale=").append(scalePercent).append("%")
                .append(" target=").append(xW).append("x").append(xH)
                .append(" zoom=").append(zoom).append("%\n");

            java.io.File dstSo = new java.io.File(getFilesDir(), "libXlorie-patched.so");

            // Read libXlorie.so from inside the APK — always readable, no extractNativeLibs needed
            String apkPath = getPackageCodePath();
            diag.append("apk=").append(apkPath).append("\n");

            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(apkPath)) {
                java.util.zip.ZipEntry entry = apk.getEntry("lib/arm64-v8a/libXlorie.so");
                diag.append("entry=").append(entry != null ? entry.getSize() : "null").append("\n");
                if (entry == null) throw new Exception("libXlorie.so not found in APK");

                // Only re-extract+patch if sizes differ (APK updated)
                diag.append("extracting+patching...\n");
                byte[] soBytes;
                try (java.io.InputStream is = apk.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    soBytes = baos.toByteArray();
                }
                diag.append("read=").append(soBytes.length).append("\n");
                soBytes[XLORIE_W_OFFSET]   = (byte)(xW & 0xFF);
                soBytes[XLORIE_W_OFFSET+1] = (byte)((xW >>  8) & 0xFF);
                soBytes[XLORIE_W_OFFSET+2] = 0;
                soBytes[XLORIE_W_OFFSET+3] = 0;
                soBytes[XLORIE_H_OFFSET]   = (byte)(xH & 0xFF);
                soBytes[XLORIE_H_OFFSET+1] = (byte)((xH >>  8) & 0xFF);
                soBytes[XLORIE_H_OFFSET+2] = 0;
                soBytes[XLORIE_H_OFFSET+3] = 0;
                java.nio.file.Files.write(dstSo.toPath(), soBytes);
                diag.append("written=").append(dstSo.length()).append("\n");
            }

            try { android.system.Os.setenv("RESOLUTION_X", String.valueOf(xW), true); }
            catch (Exception ignored) {}
            try { android.system.Os.setenv("RESOLUTION_Y", String.valueOf(xH), true); }
            catch (Exception ignored) {}

            XLorieConfig.xResW = xW;
            XLorieConfig.xResH = xH;
            XLorieConfig.libraryPath = dstSo.getAbsolutePath();
            diag.append("OK libraryPath=").append(XLorieConfig.libraryPath).append("\n");

        } catch (Exception e) {
            diag.append("EXCEPTION: ").append(e).append("\n");
            android.util.Log.e("XLorie", "patchXLorie failed: " + e);
            XLorieConfig.libraryPath = null;
        } finally {
            try { java.nio.file.Files.write(logFile.toPath(),
                    diag.toString().getBytes()); } catch (Exception ignored) {}
            android.util.Log.i("XLorie", "patch: " + diag);
        }
    }

    /**
     * Live-resize X11 canvas to (newW, newH) -- "auto-rotate Option 1".
     * Invariant: sendWindowChange()'s (width,height) and the setViewport()
     * expW/expH that surfaceChanged() will pass after the LayoutParams
     * resize below MUST target the SAME (newW, newH) -- LorieView.xResW/H
     * is the single source of truth for both, so the renderer self-heals
     * once the X server's desc->width/height catches up (see Build 27 plan).
     *
     * Build 29: rotation itself triggers a "free" surfaceChanged(oldDims)
     * from the OS (window relayout) within a few ms of onConfigurationChanged,
     * BEFORE any code here runs. If sendWindowChange() already ran by then,
     * rootWindowTextureID points at the brand-new buffer while that free
     * surfaceChanged's rendererSetWindow()/redraw races ahead of the
     * renderer's EVENT_ADD_BUFFER processing -> "Buffer X not found" ->
     * permanent black screen (seen in Build 27 AND Build 28, despite Build
     * 28 delaying setLayoutParams). Fix: delay sendWindowChange() itself by
     * RESIZE_X_DELAY_MS so the free surfaceChanged(oldDims) settles first
     * (rootWindowTextureID still the old, already-registered buffer -- no
     * race). Then, as in Build 28, delay setLayoutParams() (which triggers
     * the SECOND surfaceChanged, for newDims) by another
     * RESIZE_LAYOUT_DELAY_MS so the ALooper has time to drain conn_fd for
     * the new buffer first.
     */
    private static final int RESIZE_X_DELAY_MS = 300;
    private static final int RESIZE_LAYOUT_DELAY_MS = 200;

    private Runnable _pendingResizeStart;

    private void requestXResize(int newW, int newH, int viewW, int viewH) {
        if (sLorieView == null) return;
        if (newW == LorieView.xResW && newH == LorieView.xResH
                && viewW == XLorieConfig.viewW && viewH == XLorieConfig.viewH) return;

        if (_pendingResizeStart  != null) handler.removeCallbacks(_pendingResizeStart);
        if (_pendingResizeLayout != null) handler.removeCallbacks(_pendingResizeLayout);

        _pendingResizeStart = () -> {
            _pendingResizeStart = null;
            LorieView.xResW = newW;
            LorieView.xResH = newH;
            XLorieConfig.viewW = viewW;
            XLorieConfig.viewH = viewH;

            sLorieView.sendWindowChange(newW, newH, 60, "screen");

            _pendingResizeLayout = () -> {
                _pendingResizeLayout = null;
                ViewGroup.LayoutParams lp = sLorieView.getLayoutParams();
                if (lp != null) {
                    lp.width = viewW;
                    lp.height = viewH;
                    sLorieView.setLayoutParams(lp);
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    sLorieView.post(() -> sLorieView.setSystemGestureExclusionRects(
                        java.util.Collections.singletonList(
                            new android.graphics.Rect(0, 0, viewW, viewH))));
                }
            };
            handler.postDelayed(_pendingResizeLayout, RESIZE_LAYOUT_DELAY_MS);
        };
        handler.postDelayed(_pendingResizeStart, RESIZE_X_DELAY_MS);
    }

    private void resizeXForCurrentDisplay() {
        int[] px = getDisplayPx();
        int viewW = Math.max(320, px[0]);
        int viewH = Math.max(480, px[1] - getEffectiveNavbarPx());
        float screenScale = readScreenScaleRatio();
        int newW = Math.max(320, logicalFromPhysical(viewW, screenScale));
        int newH = Math.max(480, logicalFromPhysical(viewH, screenScale));
        requestXResize(newW, newH, viewW, viewH);
    }

    private void applyRequestedViewport(int requestedW, int requestedH) {
        int[] px = getDisplayPx();
        int maxW = Math.max(320, px[0]);
        int maxH = Math.max(480, px[1] - getEffectiveNavbarPx());
        int width = Math.max(320, Math.min(maxW, requestedW));
        int height = Math.max(480, Math.min(maxH, requestedH));
        requestXResize(width, height, width, height);
    }

    /**
     * A Linux process can request a temporary X11 canvas without resizing the
     * Android Activity. The marker contains "WIDTH HEIGHT" and is removed when
     * the requesting process exits. This keeps the navbar/IME full-size while
     * allowing fullscreen X11 clients to see a different display aspect ratio.
     */
    private void startViewportWatcher() {
        final File marker = new File(
            getFilesDir(), "nobrain/tmp/.nobrain_viewport");
        _viewportWatcherStop = false;
        new Thread(() -> {
            while (!_viewportWatcherStop && !isFinishing()) {
                int width = 0;
                int height = 0;
                long ownerPid = 0;
                if (marker.isFile()) {
                    try {
                        String value = new String(
                            java.nio.file.Files.readAllBytes(marker.toPath())).trim();
                        String[] fields = value.split("\\s+");
                        if (fields.length >= 2) {
                            width = Integer.parseInt(fields[0]);
                            height = Integer.parseInt(fields[1]);
                            if (fields.length >= 3)
                                ownerPid = Long.parseLong(fields[2]);
                        }
                    } catch (Exception e) {
                        android.util.Log.w("Viewport", "Ignoring invalid marker: " + e);
                    }
                }

                if (ownerPid > 0 && !new File("/proc/" + ownerPid).exists()) {
                    android.util.Log.i("Viewport", "Removing stale marker for pid " + ownerPid);
                    marker.delete();
                    width = 0;
                    height = 0;
                }

                if (width > 0 && height > 0) {
                    if (width != _activeViewportW || height != _activeViewportH) {
                        _activeViewportW = width;
                        _activeViewportH = height;
                        final int requestedW = width;
                        final int requestedH = height;
                        handler.post(() -> applyRequestedViewport(requestedW, requestedH));
                    }
                } else if (_activeViewportW != 0 || _activeViewportH != 0) {
                    _activeViewportW = 0;
                    _activeViewportH = 0;
                    handler.post(this::resizeXForCurrentDisplay);
                }

                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "viewport-watcher").start();
    }

    private void startRestartWatcher() {
        java.io.File shutdownFlag = new java.io.File(
            getFilesDir(), "nobrain/tmp/nobrain-shutdown");
        new Thread(() -> {
            while (!isFinishing()) {
                if (shutdownFlag.exists()) {
                    new android.os.Handler(getMainLooper()).post(() -> {
                        Intent svc = new Intent(this, PocketService.class);
                        svc.setAction(PocketService.ACTION_SHUTDOWN);
                        startForegroundService(svc);
                        finishAndRemoveTask();
                    });
                    return;
                }
                try { Thread.sleep(1000); } catch (Exception ignored) {}
            }
        }, "shutdown-watcher").start();
    }

    private void clearShutdownMarkers() {
        try {
            java.io.File tmp = new java.io.File(getFilesDir(), "nobrain/tmp");
            java.io.File androidMarker = new java.io.File(tmp, "android-shutdown-requested");
            java.io.File linuxMarker = new java.io.File(tmp, "nobrain-shutdown");
            if (androidMarker.exists()) androidMarker.delete();
            if (linuxMarker.exists()) linuxMarker.delete();
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Ukuran area window kita SEBENARNYA (= yang akan dipakai root/
     * viewportContainer saat layout). getRealMetrics() mengembalikan
     * resolusi RAW panel (mis. 1080x2408 di tablet ini), tapi window
     * frame WM tetap menyisakan inset status-bar ~90px di atas
     * (confirmed via dumpsys: frame=[0,90][1080,2408]) WALAUPUN flag
     * FULLSCREEN/HIDE_NAVIGATION sudah dipasang -- getCurrentWindowMetrics()
     * .getBounds() saja TIDAK cukup (masih full 1080x2408, sama spt
     * getRealMetrics). Kurangi dgn systemBars+displayCutout insets supaya
     * hasilnya = window frame asli (1080x2318), align dgn
     * viewportContainer sehingga lorieView (xResW x xResH) tidak
     * overflow ke area navBar custom kita.
     */
    private int[] getDisplayPx() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics wm = getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect b = wm.getBounds();
            android.graphics.Insets in = wm.getWindowInsets().getInsets(
                android.view.WindowInsets.Type.systemBars()
                | android.view.WindowInsets.Type.displayCutout());
            return new int[]{
                b.width() - in.left - in.right,
                b.height() - in.top - in.bottom
            };
        }
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        if (lorieView != null) lorieView.post(this::focusLorieInput);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            if (lorieView != null
                    && (bootOverlay == null || bootOverlay.getVisibility() != View.VISIBLE)) {
                lorieView.post(this::focusLorieInput);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
        try (FileWriter fw = new FileWriter("/sdcard/Download/nobrain-configchange.log", true)) {
            fw.write(new java.util.Date() + ": onConfigurationChanged " + newConfig.toString() + "\n");
        } catch (Exception ignored) {}

        // post(): tunggu window selesai layout ke bounds baru sebelum
        // baca getDisplayPx() (insets pas transisi rotasi belum stabil).
        if (rootView != null) {
            rootView.post(() -> {
                applyHardwareKeyboardMode(false);
                if (!_hardwareKeyboardPresent && lorieView != null) {
                    InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.restartInput(lorieView);
                }
                resizeXForCurrentDisplay();
            });
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!xServerGloballyStarted) return;
        try {
            enterPictureInPictureMode(
                new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(
                        Math.max(1, lorieView.getWidth()),
                        Math.max(1, lorieView.getHeight())))
                    .build());
        } catch (Exception ignored) {}
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip,
            android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        _inPip = isInPip;
        updateNavBarVisibility();
        if (isInPip) {
            hideKeyboard();
            new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                    if (lorieView.requestConnection()) break;
                }
            }, "pip-conn").start();
        }
    }

    @Override
    public void onBackPressed() {}

    private void requestStoragePermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        String[] needed = {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        };
        for (String p : needed) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                perms.add(p);
        }
        if (!perms.isEmpty())
            requestPermissions(perms.toArray(new String[0]), 1001);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }

        // Battery optimization exemption — delay 2s agar tidak bertabrakan dengan permission dialog
        handler.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    try {
                        startActivity(new Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        // Intent tidak tersedia di device ini — tampilkan panduan manual
                        android.widget.Toast.makeText(this,
                            "Buka Settings > Aplikasi > NoBrain Linux > Baterai > Tanpa Pembatasan",
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            }
        }, 2000);
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
            android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int dir = (kc == KeyEvent.KEYCODE_VOLUME_UP)
                ? android.media.AudioManager.ADJUST_RAISE
                : android.media.AudioManager.ADJUST_LOWER;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir,
                android.media.AudioManager.FLAG_SHOW_UI);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        // Service tetap jalan di background saat activity close (wakelock aktif)
        _dmenuLaunchPending = false;
        _dmenuShowing = false;
        _viewportWatcherStop = true;
        _bootStatusWatcherStop = true;
        super.onDestroy();
    }
}
