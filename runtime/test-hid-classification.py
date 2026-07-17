#!/usr/bin/env python3
from pathlib import Path


source = Path(
    "android/app/src/main/java/com/nobrain/linux/MainActivity.java"
).read_text(encoding="utf-8")

required = {
    "enumerates Android input devices": "InputDevice.getDeviceIds()",
    "ignores virtual devices": "device.isVirtual()",
    "checks keyboard input source": "InputDevice.SOURCE_KEYBOARD",
    "verifies actual keys": "device.hasKeys(requiredKeys)",
    "requires alphabetic Q key": "KeyEvent.KEYCODE_Q",
    "requires alphabetic A key": "KeyEvent.KEYCODE_A",
    "requires alphabetic Z key": "KeyEvent.KEYCODE_Z",
    "refreshes stale IME connections": "imm.restartInput(lorieView)",
}

for description, marker in required.items():
    if marker not in source:
        raise SystemExit(f"missing HID regression guard: {description}")

legacy = "config.keyboard != Configuration.KEYBOARD_NOKEYS"
if legacy in source:
    raise SystemExit("mouse-prone Configuration.keyboard detection is still active")

print("HID keyboard classification checks passed")
