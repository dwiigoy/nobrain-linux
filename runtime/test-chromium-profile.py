#!/usr/bin/env python3
from pathlib import Path


source = Path(
    "android/app/src/main/java/com/nobrain/linux/PocketService.java"
).read_text(encoding="utf-8")

required = (
    "PROFILE=$HOME/.config/nobrain-chromium",
    "SESSION=/tmp/cr-runtime-$$",
    "--user-data-dir=$PROFILE",
    "export XDG_RUNTIME_DIR=$SESSION",
)
for marker in required:
    if marker not in source:
        raise SystemExit(f"persistent Chromium profile guard missing: {marker}")

if "--user-data-dir=$SESSION" in source:
    raise SystemExit("Chromium profile still points at an ephemeral runtime directory")

print("CHROMIUM_PERSISTENT_PROFILE_OK")
