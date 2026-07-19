# Changelog

## 0.1.2 - 2026-07-19

- Fixed fresh-install startup stalling at `Preparing application support` by
  detecting an already-patched DWM binary before attempting an in-guest build.
- Made the DWM migration non-blocking on older rootfs installations that do
  not have the Xft development headers required to rebuild DWM.
- Repaired the owner and setuid modes of `su` and `sudo`, plus safe ownership
  and modes for the sudo configuration, on every application start.
- Extended the XBPS update wrapper to repeat that privilege repair after a
  successful package transaction, keeping SSH Access usable after a full
  `sudo xbps-install -Su`.
- Fixed WPS native file choosers opening below their parent export dialog when
  the chooser occupies almost the full X11 screen.
- Verified Build 148 on a true fresh install followed by a full system update,
  WPS installation, SSH key export, and key-only remote audit. The final Build
  150 candidate also passed an update-in-place and canonical WPS reinstall
  while preserving Linux documents and WPS configuration.

## 0.1.1 - 2026-07-19

- Added a canonical first-run/runtime reconciliation step so packaged Chromium,
  WPS installer, shutdown helper, and menu scripts replace stale embedded
  payloads atomically on every start.
- Fixed Chromium profile persistence and lifecycle handling with a stable XDG
  runtime directory, graceful shutdown, and removal of unsafe test flags.
- Fixed WPS PDF export by installing the `tiff5` compatibility library and
  automatically raising native WPS chooser dialogs above the desktop.
- Changed DWM pointer behavior to click-to-focus so entering a window no longer
  steals keyboard focus.
- Added a one-time in-guest DWM rebuild from the packaged canonical source so
  update-in-place users receive click-to-focus without rootfs replacement.
- Preserved existing Linux data during update-in-place; this patch does not
  force rootfs re-extraction.
- Made SSH key/config exchange fail closed unless Android shared storage is
  actually mounted, with an explicit All-files-access/restart instruction
  instead of silently writing to an app-specific fallback directory.

## 0.1.0 - 2026-07-17

First public pre-release, based on runtime Build 144.

- Added the Android-hosted XLorie display and customized DWM desktop.
- Added Android keyboard, external mouse, clipboard, workspace navigation,
  custom tiling, and interactive resize support.
- Added persistent owner-controlled SSH access with Key, Password, and Custom
  modes.
- Added startup readiness reporting, NoBrain menu, package update wrapper, and
  persistent Chromium profile handling.
- Fixed paired native key releases so modified virtual-keyboard input cannot
  leave repeating X11 keys behind.
- Fixed external mouse detection so a mouse does not suppress the Android
  virtual keyboard.
- Fixed WPS installation launcher and terminal scrollback behavior.
