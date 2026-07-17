# NoBrain Linux 0.1.0

First public pre-release of NoBrain Linux, based on runtime Build 144.

NoBrain Linux runs a Void Linux ARM64 desktop on Android through PRoot and an
Android-hosted X11 server. Root access is not required.

## A Note from the Creator

NoBrain Linux was created 100% with AI. I am not a programmer, a software
developer, or an IT professional. I am simply someone who loves Linux.

This is a personal project. It is far from perfect, and that is okay. I warmly
welcome criticism, bug reports, ideas, and all kinds of feedback. I will be
very happy to keep learning and improving it—with AI, of course.

Please send feedback through
[GitHub Issues](https://github.com/dwiigoy/nobrain-linux/issues).

You can also find me on Instagram:
[@dwi.dot.yogi](https://www.instagram.com/dwi.dot.yogi/). DMs are always
welcome—come talk about Linux! There is only one rule: **no brains allowed.**

That is the philosophy behind this project: **No Brain Required.**

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
- Primarily tested on a Nubia Neo 5G with Android 13 and a Huawei MatePad 11.5
  (2024).
- First startup requires internet and may take time on a slow connection.
- Linux GUI applications run through PRoot/X11; GPU and hardware video decode
  support are limited.
- PulseAudio startup diagnostics still print one obsolete package path, but
  audio itself works.

This is a pre-release. Back up important Linux home data before testing updates.
