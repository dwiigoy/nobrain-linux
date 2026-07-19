#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "android/app/src/main/java/com/nobrain/linux/PocketService.java").read_text(encoding="utf-8")
proot = (ROOT / "android/app/src/main/java/com/nobrain/linux/ProotManager.java").read_text(encoding="utf-8")
gradle = (ROOT / "android/app/build.gradle").read_text(encoding="utf-8")
canonical = (ROOT / "runtime/chromium").read_text(encoding="utf-8")
zip_swap = (ROOT / "tools/build_zip_swap.py").read_text(encoding="utf-8")

required = (
    "PROFILE=$HOME/.config/nobrain-chromium",
    "RUNTIME=/tmp/xdg-runtime",
    "--user-data-dir=$PROFILE",
    "export XDG_RUNTIME_DIR=$RUNTIME",
)
for marker in required:
    if marker not in source:
        raise SystemExit(f"persistent Chromium profile guard missing: {marker}")

if "--user-data-dir=$SESSION" in source or "SESSION=/tmp/cr-runtime-$$" in source:
    raise SystemExit("Chromium profile still points at an ephemeral runtime directory")

for forbidden in ("export GOOGLE_API_KEY=no", "--test-type",
                  "--disable-infobars", "--no-process-singleton-dialog"):
    if forbidden in source:
        raise SystemExit(f"unsafe Chromium launcher marker remains: {forbidden}")
    if forbidden in canonical:
        raise SystemExit(f"unsafe canonical Chromium marker remains: {forbidden}")

for marker in ("syncCanonicalRuntime", "../../runtime", "../../dwm", "include 'chromium'",
               "include 'install-wps'", "include 'nobrain-chromium-shutdown'",
               "include 'nobrain-dialog-raise.c'",
               "include 'nobrain-ssh-access'",
               "include 'nobrain-menu-core'", "into 'dwm-src'", "include 'Makefile'"):
    if marker not in gradle:
        raise SystemExit(f"canonical runtime asset sync marker missing: {marker}")

for marker in ('TARGET_VERSION_CODE = 4', 'TARGET_VERSION_NAME = "0.1.2"',
               '"nobrain-dialog-raise.c"', '"nobrain-ssh-access"',
               'Manifest versionCode attribute not found'):
    if marker not in zip_swap:
        raise SystemExit(f"ZIP-swap release guard missing: {marker}")

for marker in (
    "stageCanonicalRuntime();",
    "RUNTIME_CANON=/usr/local/share/nobrain/runtime",
    "install -m 755",
    "nobrain-chromium-shutdown:nobrain-chromium-shutdown",
    ".dialog-helper-built-v3",
    "nobrain-dialog-raise.c",
    "nobrain-ssh-access:nobrain-ssh-access",
    "-o /usr/local/bin/.nobrain-dialog-raise.tmp -lX11",
    "command -v wps",
    "xbps-query -p pkgver tiff5",
    "/usr/bin/xbps-install -Sy tiff5",
    "WPS_MIGRATE tiff5 deferred",
    ".dwm-click-focus-built-v1",
    "objdump -d --disassemble=enternotify",
    "DWM_MIGRATE already current",
    "make -C",
    "DWM_MIGRATE deferred; keeping existing binary",
    "dwm-src/dwm.c",
    "nobrain-menu-core:nobrain-menu-core",
    "RUNTIME_RECONCILE_OK",
):
    if marker not in source:
        raise SystemExit(f"runtime reconciliation guard missing: {marker}")

if "DWM_MIGRATE build failed' >> $DBG; rm -rf" in source:
    raise SystemExit("DWM migration failure still blocks Linux startup")

for marker in ('NOBRAIN_SDCARD_SHARED', '"/storage/emulated/0".equals(sdcardSrc)'):
    if marker not in proot:
        raise SystemExit(f"Android shared-storage mount marker missing: {marker}")
for marker in ('${NOBRAIN_SDCARD_SHARED:-0}', '/tmp/nobrain-shared-storage-active'):
    if marker not in source:
        raise SystemExit(f"guest shared-storage marker missing: {marker}")
if source.index('${NOBRAIN_SDCARD_SHARED:-0}') > source.index("SESSION_ALIVE pid="):
    raise SystemExit("shared-storage state must be published before reconnect short-circuit")
if '"dwm-src/Makefile"' not in source or '"dwm-src/util.h"' not in source:
    raise SystemExit("DWM canonical source assets are not staged")

print("CHROMIUM_PERSISTENT_PROFILE_OK")
