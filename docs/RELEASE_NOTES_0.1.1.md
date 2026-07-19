# NoBrain Linux 0.1.1

Patch pre-release based on runtime Build 146.

## Download

`nobrain-linux-0.1.1.apk`

SHA-256:

`9588914249414db1527680150af297c6efcf046d5468bbfd875122c45afbfec4`

The APK uses the same NoBrain release certificate as 0.1.0. Install it as an
update; do not uninstall 0.1.0 first. The update preserves the existing rootfs,
Linux home, SSH configuration, Chromium profile, and application data.

## Fixes

- Canonical runtime assets are staged atomically and reconciled after the
  legacy bootstrap payload, preventing startup from restoring stale scripts.
- Chromium uses a persistent profile and stable XDG runtime directory, removes
  unsafe test flags, and receives a graceful shutdown path.
- Existing WPS installations automatically receive the `tiff5` compatibility
  library needed by the PDF engine.
- A native helper keeps WPS chooser dialogs above their parent windows.
- DWM uses click-to-focus. Update-in-place users receive it through a one-time
  in-guest rebuild from the packaged canonical DWM source.
- SSH key/config exchange now refuses to run unless Android shared storage is
  genuinely mounted. Successful exports appear under
  `Internal storage/Download/NoBrain-SSH`.

## Runtime verification

The installed Build 146 audit confirmed canonical asset hashes, one DWM
process, an `enternotify` implementation containing only `ret`, a running WPS
dialog helper, `libqpdfpaint.so` resolving `libtiff.so.5`, a preserved 492 MB
Chromium profile, unchanged rootfs version, and a working exported SSH key on
localhost port 2223.
