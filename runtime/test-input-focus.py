#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/nobrain/linux/MainActivity.java"
text = SOURCE.read_text()

hide_start = text.index("private void hideStatus()")
hide_end = text.index("private void waitForDesktopAndHide()", hide_start)
if "focusLorieInput();" not in text[hide_start:hide_end]:
    raise SystemExit("boot overlay must restore LorieView focus after it is hidden")

resume_start = text.index("protected void onResume()")
resume_end = text.index("public void onWindowFocusChanged", resume_start)
if "focusLorieInput" not in text[resume_start:resume_end]:
    raise SystemExit("onResume must restore LorieView input focus")

window_focus_start = resume_end
window_focus_end = text.index("public void onConfigurationChanged", window_focus_start)
if "focusLorieInput" not in text[window_focus_start:window_focus_end]:
    raise SystemExit("window focus recovery must restore LorieView input focus")

if text.count("btn.setFocusable(false);") < 2:
    raise SystemExit("navbar and extra-key buttons must not take input focus")
if text.count("btn.setFocusableInTouchMode(false);") < 2:
    raise SystemExit("navbar and extra-key buttons must not take touch-mode focus")

print("INPUT_FOCUS_REGRESSION_OK")
