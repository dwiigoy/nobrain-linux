# Changelog

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
