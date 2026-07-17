package com.termux.x11;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.nobrain.linux.ProotManager;
import java.io.File;

/**
 * SurfaceView yang menjadi render target XLorie.
 *
 * LorieView native methods di-register via RegisterNatives (JNI_OnLoad),
 * bukan via standard JNI naming — karenanya nama method HARUS cocok persis
 * dengan yang didaftarkan di native code (lihat strings libXlorie.so).
 *
 * Urutan init yang benar (jangan terbalik):
 *   1. new LorieView(context) — di onCreate
 *   2. nativeInit()           — di surfaceCreated callback
 *   3. surfaceChanged(surf)   — di surfaceChanged callback
 *   4. setViewport(...)       — setelah surfaceChanged
 *   5. CmdEntryPoint.start()  — terakhir
 */
public class LorieView extends SurfaceView implements SurfaceHolder.Callback {

    // HAL_PIXEL_FORMAT_BGRA_8888 = 5 (required by XLorie renderer)
    private static final int BGRA_8888 = 5;

    static {
        try {
            // XLorieConfig.libraryPath di-set oleh MainActivity.patchXLorieAndSetConfig()
            // SEBELUM class ini pertama kali di-init — sehingga kita load versi yang
            // sudah di-patch resolusinya sesuai layar device.
            if (XLorieConfig.libraryPath != null) {
                System.load(XLorieConfig.libraryPath);
            } else {
                System.loadLibrary("Xlorie");
            }
        } catch (Throwable t) {
            android.util.Log.e("LorieView", "loadLibrary failed: " + t);
        }
    }

    public LorieView(Context context) {
        super(context);
        // setFormat di constructor — sebelum surface dibuat
        // agar tidak trigger surface recreation saat surfaceCreated
        getHolder().setFormat(BGRA_8888);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
    }

    /**
     * Set true sebelum surface recreate saat X server sudah running.
     * Mencegah nativeInit() reset framebuffer XLorie saat reconnect.
     */
    public static volatile boolean skipNextNativeInit = false;

    // ── Surface lifecycle ────────────────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (skipNextNativeInit) {
            skipNextNativeInit = false;
            android.util.Log.i("LorieView", "surfaceCreated: skip nativeInit (X server hot-reconnect)");
            return;
        }
        android.util.Log.i("LorieView", "surfaceCreated, calling nativeInit");
        nativeInit();
        android.util.Log.i("LorieView", "nativeInit done");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        android.util.Log.i("LorieView", "surfaceChanged: " + width + "x" + height + " fmt=" + format);
        surfaceChanged(holder.getSurface());
        setViewport(0, 0, width, height, xResW, xResH);
        android.util.Log.i("LorieView", "setViewport dst=" + width + "x" + height + " src=" + xResW + "x" + xResH);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceChanged((Surface) null);
    }

    // ── Touch input → X11 ───────────────────────────────────────
    // Resolusi X server untuk scale koordinat touch.
    // Di-init dari XLorieConfig yang sudah di-set MainActivity sebelum class ini init.
    public static int xResW = XLorieConfig.xResW, xResH = XLorieConfig.xResH;

    // ── Gesture state ────────────────────────────────────────────
    private float _downRawX, _downRawY;
    private boolean _isDrag = false;
    private boolean _isLongPress = false;
    private boolean _isDragSelect = false;  // double-tap drag = tahan button 1
    private boolean _isScrolling = false;
    private boolean _isTouchScroll = false;
    private float _scrollRefY, _scrollRefX;
    private float _pinchRefDist;
    private int _externalMouseButtons = 0;

    // ── Viewport pan (keyboard-open "crop" mode) ─────────────────────────
    // lorieView punya ukuran TETAP xResW x xResH (1:1, no rescale). Saat
    // viewportContainer (parent) menyusut karena keyboard+extraKeysBar,
    // bagian bawah lorieView ke-clip. _maxPanY = berapa px konten yang
    // ke-clip; translationY (_panY, range [-_maxPanY, 0]) memilih bagian
    // mana yang terlihat. Di-drive oleh MainActivity.applyKeyboardLayout.
    private int _maxPanY = 0;
    private float _panY  = 0f;
    private int _lastMouseX = -1, _lastMouseY = -1;
    private long _lastTapTime = 0;
    private float _lastTapRawX, _lastTapRawY;

    private static final float TAP_SLOP           = 18f;
    private static final float SCROLL_PX_PER_TICK  = 1f;
    // sendMouseEvent(x, y, 4, ...) = scroll event: x=horizontal, y=vertical
    // positif y = scroll bawah, negatif y = scroll atas; dibagi 120 oleh native handler
    private static final float SCROLL_UNIT         = 3.0f;  // 50x faster than the 0.06 test; still 2x slower than Build 114
    private static final float ZOOM_PX_PER_STEP    = 40f;   // pinch px per zoom step
    private static final float ZOOM_UNIT           = 120f;  // 1 Ctrl+scroll click per zoom step
    private static final long  LONG_PRESS_MS       = 550;
    private static final long  DOUBLE_TAP_MS       = 300;

    private final Handler  _gestureHandler   = new Handler(Looper.getMainLooper());
    private       Runnable _longPressRunnable;

    /** Konversi koordinat Android surface ke koordinat X server */
    private int toXCoord(float androidX, int surfaceW) {
        return (int)(androidX * xResW / Math.max(surfaceW, 1));
    }
    private int toYCoord(float androidY, int surfaceH) {
        return (int)(androidY * xResH / Math.max(surfaceH, 1));
    }

    private boolean isExternalMouseEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    private int toX11MouseButton(int androidButton) {
        switch (androidButton) {
            case MotionEvent.BUTTON_PRIMARY: return 1;
            case MotionEvent.BUTTON_TERTIARY: return 2;
            case MotionEvent.BUTTON_SECONDARY: return 3;
            case MotionEvent.BUTTON_BACK: return 8;
            case MotionEvent.BUTTON_FORWARD: return 9;
            default: return 0;
        }
    }

    private void releaseExternalMouseButtons() {
        int[] buttons = {
            MotionEvent.BUTTON_PRIMARY,
            MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_SECONDARY,
            MotionEvent.BUTTON_BACK,
            MotionEvent.BUTTON_FORWARD
        };
        for (int button : buttons) {
            if ((_externalMouseButtons & button) != 0)
                sendMouseEvent(0, 0, toX11MouseButton(button), false, true);
        }
        _externalMouseButtons = 0;
    }

    private boolean handleExternalMouseEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int xc = toXCoord(event.getX(event.getActionIndex()), getWidth());
        int yc = toYCoord(event.getY(event.getActionIndex()), getHeight());

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseExternalMouseButtons();
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            float horizontal = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120f;
            float vertical = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f;
            if (horizontal != 0f || vertical != 0f)
                sendMouseEvent(horizontal, vertical, 4, false, false);
            return true;
        }

        if (action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE)
            sendMouseEvent(xc, yc, 0, false, false);
        if (action == MotionEvent.ACTION_DOWN) requestFocus();

        int newButtons = event.getButtonState();
        int changed = _externalMouseButtons ^ newButtons;
        int actionButton = event.getActionButton();
        if (actionButton != 0) changed |= actionButton;

        int[] buttons = {
            MotionEvent.BUTTON_PRIMARY,
            MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_SECONDARY,
            MotionEvent.BUTTON_BACK,
            MotionEvent.BUTTON_FORWARD
        };
        for (int button : buttons) {
            if ((changed & button) == 0) continue;
            boolean down = (newButtons & button) != 0;
            if (actionButton == button) {
                if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_DOWN)
                    down = true;
                else if (action == MotionEvent.ACTION_BUTTON_RELEASE || action == MotionEvent.ACTION_UP)
                    down = false;
            }
            sendMouseEvent(xc, yc, toX11MouseButton(button), down, false);
            if (down) newButtons |= button;
            else newButtons &= ~button;
        }
        _externalMouseButtons = newButtons;
        return true;
    }

    /**
     * Dipanggil MainActivity.applyKeyboardLayout saat viewportContainer
     * menyusut/membesar (keyboard+extraKeysBar show/hide). hiddenPx = berapa
     * px bagian bawah lorieView (1:1, xResW x xResH) sekarang ke-clip oleh
     * container. Clamp _panY ke range baru lalu apply -- kalau keyboard
     * ditutup (hiddenPx=0) konten snap balik ke atas (translationY=0).
     */
    public void setViewportPan(int hiddenPx) {
        _maxPanY = Math.max(0, hiddenPx);
        _panY = Math.max(-_maxPanY, Math.min(0f, _panY));
        setTranslationY(_panY);
    }

    // ── Extra-keys toolbar: sticky modifiers (Ctrl/Alt/Shift/Super) ──────
    // Tap arms a modifier (key-DOWN + highlight); it stays held until the
    // NEXT key input from any source (toolbar tap, hardware key, or Gboard
    // via IME commit/backspace) — that input fires while the modifier is
    // still down (real combo), then releaseArmedModifiers() lifts it.
    public interface ModifierListener { void onModifierChanged(int keyCode, boolean armed); }
    private ModifierListener _modListener;
    public void setModifierListener(ModifierListener l) { _modListener = l; }

    private final java.util.Set<Integer> _armedMods = new java.util.LinkedHashSet<>();
    private final java.util.Set<Integer> _forwardedKeyDowns = new java.util.HashSet<>();
    private boolean _hwCtrlDown = false;
    private boolean _hwShiftDown = false;

    /** Toggle a sticky modifier. Returns the new armed state. */
    public boolean armOrToggleModifier(int keyCode) {
        boolean nowArmed;
        if (_armedMods.contains(keyCode)) {
            sendKeyEvent(0, keyCode, false, 0);
            _armedMods.remove(keyCode);
            nowArmed = false;
        } else {
            sendKeyEvent(0, keyCode, true, 0);
            _armedMods.add(keyCode);
            nowArmed = true;
        }
        if (_modListener != null) _modListener.onModifierChanged(keyCode, nowArmed);
        return nowArmed;
    }

    /** One-shot key tap (down+up), then auto-release any armed modifiers. */
    public void tapKey(int keyCode) {
        sendKeyEvent(0, keyCode, true, 0);
        sendKeyEvent(0, keyCode, false, 0);
        releaseArmedModifiers();
    }

    /** Bash/readline completion is bound to Ctrl-I, the terminal form of Tab. */
    public void tapTerminalTab() {
        sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, true, 0);
        sendKeyEvent(0, KeyEvent.KEYCODE_I, true, 0);
        sendKeyEvent(0, KeyEvent.KEYCODE_I, false, 0);
        sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, false, 0);
        releaseArmedModifiers();
    }

    private void releaseArmedModifiers() {
        if (_armedMods.isEmpty()) return;
        for (int kc : new java.util.ArrayList<>(_armedMods)) {
            sendKeyEvent(0, kc, false, 0);
            if (_modListener != null) _modListener.onModifierChanged(kc, false);
        }
        _armedMods.clear();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isExternalMouseEvent(event)) return handleExternalMouseEvent(event);

        int action       = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId    = event.getPointerId(pointerIndex);
        int surfW        = getWidth(), surfH = getHeight();
        int xc = toXCoord(event.getX(pointerIndex), surfW);
        int yc = toYCoord(event.getY(pointerIndex), surfH);

        switch (action) {

            case MotionEvent.ACTION_DOWN: {
                sendTouchEvent(0, pointerId, xc, yc);
                sendMouseEvent(xc, yc, 0, false, false);
                _downRawX    = event.getRawX();
                _downRawY    = event.getRawY();
                _isDrag      = false;
                _isLongPress = false;
                _isScrolling = false;
                _isTouchScroll = false;
                _scrollRefY = event.getY(pointerIndex);
                _scrollRefX = event.getX(pointerIndex);

                // Double-tap drag: tap kedua < 300ms + < 36px → tahan button 1 (drag select)
                long now = System.currentTimeMillis();
                if (now - _lastTapTime < DOUBLE_TAP_MS
                        && Math.abs(event.getRawX() - _lastTapRawX) < TAP_SLOP * 2
                        && Math.abs(event.getRawY() - _lastTapRawY) < TAP_SLOP * 2) {
                    _isDragSelect = true;
                    sendMouseEvent(xc, yc, 1, true, false);
                } else {
                    _isDragSelect = false;
                }

                // Long press → klik kanan + haptic
                final int fxc = xc, fyc = yc;
                _longPressRunnable = () -> {
                    if (!_isDrag) {
                        _isLongPress = true;
                        sendMouseEvent(fxc, fyc, 3, true,  false);
                        sendMouseEvent(fxc, fyc, 3, false, false);
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                };
                _gestureHandler.postDelayed(_longPressRunnable, LONG_PRESS_MS);
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                sendTouchEvent(0, pointerId, xc, yc);
                _gestureHandler.removeCallbacks(_longPressRunnable);
                _isDrag = true;
                if (event.getPointerCount() == 2) {
                    _isScrolling  = true;
                    _isTouchScroll = false;
                    _scrollRefY   = (event.getY(0) + event.getY(1)) / 2f;
                    _scrollRefX   = (event.getX(0) + event.getX(1)) / 2f;
                    float pdx     = event.getX(1) - event.getX(0);
                    float pdy     = event.getY(1) - event.getY(0);
                    _pinchRefDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
                }
                break;

            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() >= 2 && _isScrolling) {
                    // 2-jari: pinch zoom saja.
                    //
                    // Build 82: 2-jari scroll dimatikan karena sering tabrakan dengan
                    // pinch. Scrolling dipindahkan ke 1-jari drag agar terasa lebih
                    // seperti touchscreen Android.
                    // Touch events tidak dikirim ke X11 (mencegah X11 gesture interpretation)
                    float midX   = (event.getX(0) + event.getX(1)) / 2f;
                    float midY   = (event.getY(0) + event.getY(1)) / 2f;
                    int   mxc    = toXCoord(midX, surfW);
                    int   myc    = toYCoord(midY, surfH);
                    sendMouseEvent(mxc, myc, 0, false, false);

                    // Pinch zoom: Ctrl + scroll ke aplikasi (zoom dalam WPS, browser, dll)
                    float pdx     = event.getX(1) - event.getX(0);
                    float pdy     = event.getY(1) - event.getY(0);
                    float curDist = (float) Math.sqrt(pdx * pdx + pdy * pdy);
                    int   zSteps  = (int)((curDist - _pinchRefDist) / ZOOM_PX_PER_STEP);
                    if (Math.abs(zSteps) >= 1) {
                        // spread (zSteps > 0) = zoom in = Ctrl+scroll atas = y negatif
                        // pinch  (zSteps < 0) = zoom out = Ctrl+scroll bawah = y positif
                        sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, true, 0);
                        sendMouseEvent(0f, (float)(-zSteps) * ZOOM_UNIT, 4, false, false);
                        sendKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, false, 0);
                        _pinchRefDist += zSteps * ZOOM_PX_PER_STEP;
                    }
                } else {
                    // 1-jari: Android-like touch scroll.
                    //
                    // Tap masih klik kiri. Double-tap-drag tetap drag/select dengan
                    // button 1. Gerakan 1-jari biasa dikonversi ke wheel scroll
                    // supaya xterm/browser/WPS terasa seperti layar sentuh.
                    sendTouchEvent(2, pointerId, xc, yc);
                    float dx = Math.abs(event.getRawX() - _downRawX);
                    float dy = Math.abs(event.getRawY() - _downRawY);
                    if (dx > TAP_SLOP || dy > TAP_SLOP) {
                        _isDrag = true;
                        _gestureHandler.removeCallbacks(_longPressRunnable);
                    }
                    if (_isDragSelect) {
                        int mdx = Math.abs(xc - _lastMouseX), mdy = Math.abs(yc - _lastMouseY);
                        if (mdx > 6 || mdy > 6) {
                            sendMouseEvent(xc, yc, 0, false, false);
                            _lastMouseX = xc; _lastMouseY = yc;
                        }
                    } else if (_isDrag) {
                        if (!_isTouchScroll) {
                            _isTouchScroll = true;
                            _scrollRefY = event.getY(pointerIndex);
                            _scrollRefX = event.getX(pointerIndex);
                            sendMouseEvent(xc, yc, 0, false, false);
                            _lastMouseX = xc; _lastMouseY = yc;
                        }

                        float curX = event.getX(pointerIndex);
                        float curY = event.getY(pointerIndex);
                        float deltaX = curX - _scrollRefX;
                        float deltaY = curY - _scrollRefY;
                        if (Math.abs(deltaX) > Math.abs(deltaY)) {
                            // Horizontal: swipe kiri -> scroll kanan, seperti konten touchscreen.
                            int ticksX = (int)(deltaX / SCROLL_PX_PER_TICK);
                            if (Math.abs(ticksX) >= 1) {
                                sendMouseEvent((float)(-ticksX) * SCROLL_UNIT, 0f, 4, false, false);
                                _scrollRefX += ticksX * SCROLL_PX_PER_TICK;
                            }
                            _scrollRefY = curY;
                        } else if (_maxPanY > 0) {
                            // Keyboard terbuka: 1-jari geser viewport agar area bawah terlihat.
                            _panY = Math.max(-_maxPanY, Math.min(0f, _panY + deltaY));
                            setTranslationY(_panY);
                            _scrollRefY = curY;
                            _scrollRefX = curX;
                        } else {
                            // Vertical: swipe atas -> konten turun/scroll bawah.
                            int ticksY = (int)(deltaY / SCROLL_PX_PER_TICK);
                            if (Math.abs(ticksY) >= 1) {
                                sendMouseEvent(0f, (float)(-ticksY) * SCROLL_UNIT, 4, false, false);
                                _scrollRefY += ticksY * SCROLL_PX_PER_TICK;
                            }
                            _scrollRefX = curX;
                        }
                    } else {
                        int mdx = Math.abs(xc - _lastMouseX), mdy = Math.abs(yc - _lastMouseY);
                        if (mdx > 6 || mdy > 6) {
                            sendMouseEvent(xc, yc, 0, false, false);
                            _lastMouseX = xc; _lastMouseY = yc;
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP: {
                _gestureHandler.removeCallbacks(_longPressRunnable);
                sendTouchEvent(1, pointerId, xc, yc);

                if (_isDragSelect) {
                    sendMouseEvent(xc, yc, 1, false, false); // lepas drag select
                    _isDragSelect = false;
                    _lastTapTime  = 0;
                } else if (!_isDrag && !_isLongPress) {
                    // Single tap → klik kiri
                    sendMouseEvent(xc, yc, 1, true,  false);
                    sendMouseEvent(xc, yc, 1, false, false);
                    _lastTapTime  = System.currentTimeMillis();
                    _lastTapRawX  = event.getRawX();
                    _lastTapRawY  = event.getRawY();
                }
                _isDrag      = false;
                _isLongPress = false;
                _isScrolling = false;
                _isTouchScroll = false;
                break;
            }

            case MotionEvent.ACTION_POINTER_UP:
                sendTouchEvent(1, pointerId, xc, yc);
                if (event.getPointerCount() <= 2) _isScrolling = false;
                _isTouchScroll = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                _gestureHandler.removeCallbacks(_longPressRunnable);
                if (_isDragSelect) {
                    sendMouseEvent(xc, yc, 1, false, false);
                    _isDragSelect = false;
                }
                sendTouchEvent(1, pointerId, xc, yc);
                _isDrag = false; _isLongPress = false; _isScrolling = false; _isTouchScroll = false;
                break;
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isExternalMouseEvent(event)) return handleExternalMouseEvent(event);
        return super.onGenericMotionEvent(event);
    }

    // ── IME debug log ke sdcard ──────────────────────────────────────────────
    private static void _imeLog(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                new java.io.File("/sdcard/Download/ime-debug.log"), true);
            fw.write(new java.util.Date() + ": " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
        android.util.Log.i("LorieIME", msg);
    }

    // ── Hardware keyboard fallback via dispatchKeyEvent ───────────────────
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int action = event.getAction();
        boolean down = (action == android.view.KeyEvent.ACTION_DOWN);
        if (action == android.view.KeyEvent.ACTION_MULTIPLE
                && handleArmedClipboardText(event.getCharacters())) return true;
        int kc = event.getKeyCode();
        if (kc == android.view.KeyEvent.KEYCODE_CTRL_LEFT
                || kc == android.view.KeyEvent.KEYCODE_CTRL_RIGHT) {
            _hwCtrlDown = down;
        } else if (kc == android.view.KeyEvent.KEYCODE_SHIFT_LEFT
                || kc == android.view.KeyEvent.KEYCODE_SHIFT_RIGHT) {
            _hwShiftDown = down;
        }
        _imeLog("dispatchKeyEvent: kc=" + kc + " down=" + down
            + " ctrl=" + (event.isCtrlPressed() || _hwCtrlDown)
            + " shift=" + (event.isShiftPressed() || _hwShiftDown)
            + " alt=" + event.isAltPressed());
        if (handleClipboardKeyEvent(event, down)) return true;
        // Modifier state can change before Android delivers the letter key-up.
        // Preserve the path chosen for key-down so its native X11 key-up cannot
        // be misclassified later as an unmatched printable IME event.
        if (action == android.view.KeyEvent.ACTION_UP
                && _forwardedKeyDowns.remove(kc)) {
            sendKeyEvent(event.getScanCode(), kc, false, 0);
            return true;
        }
        if (handlePrintableKeyText(event, down, "dispatchKeyEvent")) {
            return true;
        }
        sendKeyEvent(event.getScanCode(), kc, down, 0);
        if (down) {
            _forwardedKeyDowns.add(kc);
            releaseArmedModifiers();
        }
        return true;
    }

    private boolean hasArmedModifier(int left, int right) {
        return _armedMods.contains(left) || _armedMods.contains(right);
    }

    private boolean hasArmedClipboardChord() {
        boolean ctrl = hasArmedModifier(android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_RIGHT);
        boolean shift = hasArmedModifier(android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_SHIFT_RIGHT);
        boolean alt = hasArmedModifier(android.view.KeyEvent.KEYCODE_ALT_LEFT,
            android.view.KeyEvent.KEYCODE_ALT_RIGHT);
        return (ctrl && shift) || (ctrl && alt) || alt;
    }

    private boolean handleClipboardKeyEvent(android.view.KeyEvent event, boolean down) {
        if (!down) return false;
        int kc = event.getKeyCode();
        if (kc != android.view.KeyEvent.KEYCODE_C
                && kc != android.view.KeyEvent.KEYCODE_V) return false;

        boolean ctrl = event.isCtrlPressed() || _hwCtrlDown;
        boolean shift = event.isShiftPressed() || _hwShiftDown;
        boolean alt = event.isAltPressed();
        boolean eventChord = (ctrl && shift) || (ctrl && alt) || alt;
        boolean armedChord = hasArmedClipboardChord();
        if (!eventChord && !armedChord) return false;

        // Release only modifiers that NoBrain actually pressed. Navbar actions
        // must never be responsible for repairing keyboard state.
        if (armedChord) releaseArmedModifiers();
        if (eventChord) releaseHardwareModifiersForShortcut();
        if (kc == android.view.KeyEvent.KEYCODE_C)
            copyXSelectionToAndroidClipboard();
        else
            pasteAndroidClipboardAsText();
        return true;
    }

    private boolean handleArmedClipboardText(CharSequence text) {
        if (text == null || text.length() != 1 || !hasArmedClipboardChord()) return false;
        char key = Character.toLowerCase(text.charAt(0));
        if (key != 'c' && key != 'v') return false;

        releaseArmedModifiers();
        if (key == 'c') copyXSelectionToAndroidClipboard();
        else pasteAndroidClipboardAsText();
        return true;
    }

    private boolean handlePrintableKeyText(android.view.KeyEvent event, boolean down, String source) {
        if (!_armedMods.isEmpty()) return false;
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;
        if (_hwCtrlDown) return false;
        int unicode = event.getUnicodeChar();
        if (unicode < 32 || unicode == 127) return false;
        if (!down) return true;
        String text = new String(Character.toChars(unicode));
        _imeLog(source + ": text=" + text + " unicode=" + unicode);
        sendTextEvent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        releaseArmedModifiers();
        return true;
    }

    private void releaseHardwareModifiersForShortcut() {
        if (_hwCtrlDown) sendKeyEvent(0, android.view.KeyEvent.KEYCODE_CTRL_LEFT, false, 0);
        if (_hwShiftDown) sendKeyEvent(0, android.view.KeyEvent.KEYCODE_SHIFT_LEFT, false, 0);
        _hwCtrlDown = false;
        _hwShiftDown = false;
    }

    private void copyXSelectionToAndroidClipboard() {
        final Context appCtx = getContext().getApplicationContext();
        _clipBg.post(() -> {
            try {
                File out = new File(ProotManager.getDataDir(appCtx), "tmp/nobrain-copy.txt");
                out.getParentFile().mkdirs();
                if (out.exists()) out.delete();
                String cmd = "rm -f /tmp/nobrain-copy.txt; " +
                    "(timeout 1 xclip -o -selection clipboard 2>/dev/null || " +
                    " timeout 1 xclip -o -selection primary 2>/dev/null) > /tmp/nobrain-copy.txt";
                Process p = ProotManager.runCommand(appCtx, cmd);
                p.waitFor();
                if (!out.exists() || out.length() == 0) return;
                String text = new String(java.nio.file.Files.readAllBytes(out.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
                if (text.isEmpty()) return;
                ClipboardManager cm = (ClipboardManager)
                    appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("X11", text));
                    _lastXClipboardSet = System.currentTimeMillis();
                    _imeLog("copyXSelectionToAndroidClipboard: copied chars=" + text.length());
                }
            } catch (Exception e) {
                android.util.Log.e("LorieIME", "copyXSelection failed: " + e);
                _imeLog("copyXSelectionToAndroidClipboard failed: " + e);
            }
        });
    }

    private void pasteAndroidClipboardAsText() {
        final Context appCtx = getContext().getApplicationContext();
        _clipBg.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                    appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence text = clip.getItemAt(0).coerceToText(appCtx);
                if (text == null || text.length() == 0) return;
                final byte[] bytes = text.toString().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
                _imeLog("pasteAndroidClipboardAsText: chars=" + text.length());
                _mainHandler.postDelayed(() -> sendTextEvent(bytes), 50);
            } catch (Exception e) {
                android.util.Log.e("LorieIME", "pasteAndroidClipboard failed: " + e);
                _imeLog("pasteAndroidClipboardAsText failed: " + e);
            }
        });
    }

    // ── InputConnection — forward Android IME input ke X11 ─────────────────
    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(
            android.view.inputmethod.EditorInfo outAttrs) {
        outAttrs.inputType = android.text.InputType.TYPE_NULL;
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new android.view.inputmethod.BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                _imeLog("commitText: " + text);
                if (handleArmedClipboardText(text)) return true;
                if (text != null && text.length() > 0) {
                    sendTextEvent(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                releaseArmedModifiers();
                return true;
            }
            @Override
            public boolean deleteSurroundingText(int before, int after) {
                int kc = android.view.KeyEvent.KEYCODE_DEL;
                for (int i = 0; i < before; i++) {
                    LorieView.this.sendKeyEvent(0, kc, true, 0);
                    LorieView.this.sendKeyEvent(0, kc, false, 0);
                }
                releaseArmedModifiers();
                return true;
            }
            @Override
            public boolean sendKeyEvent(android.view.KeyEvent event) {
                _imeLog("sendKeyEvent: kc=" + event.getKeyCode() + " action=" + event.getAction());
                return LorieView.this.dispatchKeyEvent(event);
            }
        };
    }

    // ── Native methods — EXACT 19 entries dari RegisterNatives di activity.c ──
    // Urutan dan signature HARUS cocok persis dengan JNINativeMethod methods[] di source

    public native void nativeInit();                                          // ()V
    public native void surfaceChanged(Surface surface);                       // (Landroid/view/Surface;)V
    public native void setViewport(int x, int y, int w, int h,
                                    int expW, int expH);                      // (IIIIII)V
    public native void setRendererZoom(int percent);                          // (I)V
    public native void setFiltering(int filtering);                           // (I)V
    public native void connect(int fd);                                       // (I)V
    public native boolean connected();                                        // ()Z
    public native void startLogcat(int fd);                                   // (I)V
    public native void setClipboardSyncEnabled(boolean local,
                                                boolean remote);              // (ZZ)V
    public native void sendClipboardAnnounce();                               // ()V
    public native void sendClipboardEvent(byte[] data);                      // ([B)V — byte array!
    public native void sendWindowChange(int width, int height,
                                         int framerate,
                                         String name);                        // (IIILjava/lang/String;)V
    public native void sendMouseEvent(float x, float y, int button,
                                       boolean down, boolean relative);       // (FFIZZ)V
    public native void sendTouchEvent(int action, int id, int x, int y);    // (IIII)V
    public native void sendStylusEvent(float x, float y, int pressure,
                                        int tiltX, int tiltY, int orientation,
                                        int buttons, boolean eraser,
                                        boolean mouseMode);                   // (FFIIIIIZZ)V
    public native void requestStylusEnabled(boolean enabled);                 // (Z)V
    public native boolean sendKeyEvent(int scanCode, int keyCode,
                                        boolean down, int a);                 // (IIZI)Z
    public native void sendTextEvent(byte[] text);                           // ([B)V
    public native boolean requestConnection();                                // ()Z — returns boolean!

    // ── Java callbacks dipanggil dari native code (bukan native methods) ──

    /** Dipanggil renderer.c via CallStaticVoidMethod untuk update viewport di Java side. */
    public static void setRendererViewport(int dstX, int dstY, int dstW, int dstH,
                                            float left, float top,
                                            float width, float height) {
        // Log pertama kali saja untuk confirm renderer aktif
        if (_rendererViewportCallCount++ < 3) {
            android.util.Log.i("XLorie", "setRendererViewport called #" + _rendererViewportCallCount
                + " dst=" + dstW + "x" + dstH + " src=" + width + "x" + height);
        }
    }
    private static int _rendererViewportCallCount = 0;

    /** Native memanggil ini untuk reset IME state. */
    public void resetIme() {}

    // Background thread khusus clipboard — agar cm.getPrimaryClip/setPrimaryClip tidak
    // pernah blocking main thread (HarmonyOS bisa block saat clipboard access).
    private static final android.os.Handler _clipBg;
    static {
        android.os.HandlerThread ht = new android.os.HandlerThread("xlorie-clip");
        ht.setDaemon(true);
        ht.start();
        _clipBg = new android.os.Handler(ht.getLooper());
    }

    // Timestamp kapan X11 terakhir set Android clipboard (debounce loop X11→Android→X11).
    private static volatile long _lastXClipboardSet = 0L;

    // sendClipboardEvent/sendClipboardAnnounce harus main thread (write ke conn_fd).
    private static final android.os.Handler _mainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    /** Native memanggil ini untuk request clipboard dari X (dipanggil di main thread xcallback). */
    public void requestClipboard() {
        // getPrimaryClip() bisa blocking di HarmonyOS — pindah ke background
        final android.content.Context appCtx = getContext().getApplicationContext();
        _clipBg.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                android.content.ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence text = clip.getItemAt(0).coerceToText(appCtx);
                if (text == null || text.length() == 0) return;
                final byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // sendClipboardEvent menulis ke conn_fd — harus main thread
                _mainHandler.post(() -> sendClipboardEvent(bytes));
            } catch (Exception ignored) {}
        });
    }

    /** Native memanggil ini untuk set clipboard text dari X (dipanggil di main thread xcallback). */
    public void setClipboardText(String text) {
        if (text == null || text.isEmpty()) return;
        // Set timestamp dulu di main thread, baru setPrimaryClip di background
        _lastXClipboardSet = System.currentTimeMillis();
        final android.content.Context appCtx = getContext().getApplicationContext();
        _clipBg.post(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("X11", text));
            } catch (Exception ignored) {}
        });
    }

    /** Aktifkan clipboard sync Android → X11 (paste ke Linux). Panggil setelah connect().
     *
     *  Root bug di activity.c xcallback (tidak bisa fix tanpa NDK recompile):
     *    EVENT_CLIPBOARD_SEND: read(conn_fd, buf, count+1) tapi X server hanya tulis count bytes
     *    → blocking read di main thread → freeze.
     *
     *  EVENT_CLIPBOARD_SEND dikirim X server ke Android hanya via lorieSendClipboardData,
     *  yang dipanggil dari lorieSelectionCallback (butuh clipboardEnabled=TRUE).
     *  Jadi: jangan pernah set clipboardEnabled=true → tidak ada lorieSendClipboardData
     *  → tidak ada EVENT_CLIPBOARD_SEND → tidak ada freeze.
     *
     *  Paste tetap bekerja: sendClipboardAnnounce() men-claim CLIPBOARD di X server.
     *  Saat X11 app minta paste, X server kirim EVENT_CLIPBOARD_REQUEST (non-blocking) →
     *  requestClipboard() → sendClipboardEvent() → data dikirim ke X11 app. No freeze.
     *
     *  Trade-off: copy X11→Android tidak otomatis (butuh clipboardEnabled=true yang menyebabkan freeze).
     */
    public void setupClipboardSync() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
            getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        // Enable both directions:
        // - clipboardEnabled=true: X11 selection changes → lorieSendClipboardData → Android clipboard
        // - announce: Android clipboard changes → X server claims CLIPBOARD → X11 apps can paste
        setClipboardSyncEnabled(true, false);
        cm.addPrimaryClipChangedListener(() -> {
            if (System.currentTimeMillis() - _lastXClipboardSet > 500) {
                sendClipboardAnnounce();
            }
        });
    }
}
