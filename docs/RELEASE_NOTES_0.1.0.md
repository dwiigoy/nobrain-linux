# NoBrain Linux 0.1.0

First public pre-release of NoBrain Linux, based on runtime Build 144.

NoBrain Linux runs a Void Linux ARM64 desktop on Android through PRoot and an
Android-hosted X11 server. Root access is not required.

## Included

- Customized DWM desktop with nine workspaces and keyboard-controlled layouts.
- Android virtual keyboard, external mouse, clipboard, and key input bridge.
- Persistent Chromium profile and application launchers.
- Key-only SSH by default, plus owner-controlled Password and Custom modes.
- NoBrain menu, terminal scrollback, scaling, package update, and shutdown
  controls.

## Installation

1. Download `nobrain-linux-0.1.0.apk` below.
2. Verify its SHA-256 checksum.
3. Allow installation from your browser or file manager when Android asks.
4. Open NoBrain Linux and wait for `Desktop ready` on first startup.

```text
SHA-256: 9ca835b82eb2368fd26aa63950e57b45dbfb1d9118a8134375b3367843424819
```

## Security Notice

The fresh-install password for both `nobrain` and `root` is `nobrain`. Change
them with `passwd` and `sudo passwd root`. SSH remains key-only until another
mode is explicitly selected.

## Known Limitations

- ARM64 and Android 9+ only.
- Primarily tested on Nubia Neo 5G with Android 13.
- First startup requires internet and may take time on a slow connection.
- Linux GUI applications run through PRoot/X11; GPU and hardware video decode
  support are limited.
- PulseAudio startup diagnostics still print one obsolete package path, but
  audio itself works.

This is a pre-release. Back up important Linux home data before testing updates.
