#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/termux/x11/LorieView.java"
text = SOURCE.read_text()

required = (
    "InputDevice.SOURCE_MOUSE",
    "InputDevice.SOURCE_MOUSE_RELATIVE",
    "MotionEvent.TOOL_TYPE_MOUSE",
    "onGenericMotionEvent(MotionEvent event)",
    "handleExternalMouseEvent(event)",
    "MotionEvent.ACTION_HOVER_MOVE",
    "MotionEvent.ACTION_SCROLL",
    "MotionEvent.AXIS_VSCROLL",
    "MotionEvent.AXIS_HSCROLL",
    "MotionEvent.BUTTON_PRIMARY",
    "MotionEvent.BUTTON_SECONDARY",
    "MotionEvent.BUTTON_TERTIARY",
    "MotionEvent.BUTTON_BACK",
    "MotionEvent.BUTTON_FORWARD",
    "releaseExternalMouseButtons()",
    "PointerIcon.TYPE_NULL",
)
for item in required:
    if item not in text:
        raise SystemExit(f"external mouse path is missing: {item}")

touch_start = text.index("public boolean onTouchEvent(MotionEvent event)")
touch_body = text[touch_start:text.index("int action", touch_start)]
if "isExternalMouseEvent(event)" not in touch_body:
    raise SystemExit("mouse events must bypass Android touch gesture emulation")

generic_start = text.index("public boolean onGenericMotionEvent(MotionEvent event)")
generic_body = text[generic_start:generic_start + 300]
if "isExternalMouseEvent(event)" not in generic_body:
    raise SystemExit("generic mouse motion must be forwarded to XLorie")

print("EXTERNAL_MOUSE_REGRESSION_OK")
