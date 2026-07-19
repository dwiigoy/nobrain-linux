#!/usr/bin/env python3
from pathlib import Path


source = Path("runtime/nobrain-dialog-raise.c").read_text(encoding="utf-8")

required = (
    "attributes.width >= 320",
    "attributes.height >= 180",
    "attributes.width <= DisplayWidth",
    "attributes.height <= DisplayHeight",
    "XSetTransientForHint(display, window, parent)",
    "XMapRaised(display, window)",
    "XSetErrorHandler(handle_x_error)",
    "XPending(display) == 0",
)
for marker in required:
    if marker not in source:
        raise SystemExit(f"dialog helper is missing chooser repair marker: {marker}")

if "screen_width * 95 / 100" in source or "screen_height * 95 / 100" in source:
    raise SystemExit("near-fullscreen WPS choosers must not be rejected")

if "raise_and_focus" in source:
    raise SystemExit("normal dialog MapNotify events must not override chooser stacking")

print("WPS_DIALOG_STACKING_OK")
