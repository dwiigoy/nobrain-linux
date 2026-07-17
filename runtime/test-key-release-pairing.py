#!/usr/bin/env python3
from pathlib import Path


source = Path(
    "android/app/src/main/java/com/termux/x11/LorieView.java"
).read_text(encoding="utf-8")

dispatch_start = source.index("public boolean dispatchKeyEvent(android.view.KeyEvent event)")
dispatch_end = source.index("private boolean hasArmedModifier", dispatch_start)
dispatch = source[dispatch_start:dispatch_end]

required = (
    "_forwardedKeyDowns.add(kc)",
    "action == android.view.KeyEvent.ACTION_UP",
    "_forwardedKeyDowns.remove(kc)",
    "sendKeyEvent(event.getScanCode(), kc, false, 0)",
)
for marker in required:
    if marker not in dispatch:
        raise SystemExit(f"native key pairing guard missing: {marker}")

paired_up = dispatch.index("_forwardedKeyDowns.remove(kc)")
printable = dispatch.index("handlePrintableKeyText(event, down")
if paired_up > printable:
    raise SystemExit("paired native key-up must run before printable-key conversion")

print("NATIVE_KEY_RELEASE_PAIRING_OK")
