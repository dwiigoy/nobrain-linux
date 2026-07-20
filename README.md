<p align="center">
  <img src="assets/nobrain-icon.png" width="180" alt="NoBrain Linux logo">
</p>

# NoBrain Linux

**A pocket Linux desktop environment for Android. No brain required.**

NoBrain Linux packages a Void Linux ARM64 userspace, a customized DWM desktop,
and an Android-hosted X server into one application. It runs through PRoot, so
the Android device does not need root access and ADB is not required after
installation.

> [!IMPORTANT]
> **AI disclosure:** NoBrain Linux was created 100% with AI. Its creator is not
> a programmer, a software developer, or an IT professional—just someone who
> loves Linux. This is a personal project and it is far from perfect. Criticism,
> bug reports, ideas, and all kinds of feedback are warmly welcomed. It will
> keep improving—with AI, of course. That is the philosophy: **No Brain
> Required.** Please send feedback through
> [GitHub Issues](https://github.com/dwiigoy/nobrain-linux/issues).
>
> **Creator:** [@dwi.dot.yogi](https://www.instagram.com/dwi.dot.yogi/) on
> Instagram. DMs are always welcome—come talk about Linux! There is only one
> rule: **no brains allowed.**

> **Release status:** `v0.1.2` is the current public pre-release, based on
> runtime Build 150. The project has been tested primarily on a Nubia Neo 5G
> running Android 13 and a Huawei MatePad 11.5 (2024). Other ARM64 devices need
> broader testing.

## Highlights

- Void Linux ARM64 with XBPS, GCC, Python, Git, OpenSSH, and desktop utilities.
- Customized DWM layout, workspace, resize, keyboard-reserve, and navigation.
- Android virtual keyboard, external mouse, clipboard, and hardware-key input.
- Persistent Chromium profile, bookmarks, cookies, and login sessions.
- Owner-controlled SSH modes: Key, Password, or Custom.
- NoBrain menu for application launchers, pins, scaling, storage, and shutdown.
- Optional application installers, including Chromium and WPS Office.

## Download

Download the APK from [GitHub Releases](../../releases). Do not download the
automatically generated source ZIP when you want to install the application.

Expected release asset:

```text
nobrain-linux-0.1.2.apk
SHA-256: 2ec9e60f81412c22619cb63acaf7bb02ae5d790d74bb8fe5adb4891e5909cb60
```

Requirements:

- Android 9 or newer.
- ARM64 (`arm64-v8a`) device.
- About 2 GB free for installation and first startup; more is recommended for
  Linux applications and package updates.
- Internet access during the initial package setup.

## Installation

1. Download `nobrain-linux-0.1.2.apk` from Releases.
2. Allow APK installation from the browser or file manager when Android asks.
3. Install and open NoBrain Linux.
4. Grant `All files access` when Android opens the app-specific settings page;
   this is required for the visible `Download/NoBrain-SSH` exchange folder.
5. Keep the app open while the startup screen prepares the first session.
6. Wait until the status changes to `Desktop ready` before using shortcuts.

On Android 12 and newer, power users who run many Linux applications should
enable **Developer options -> Disable child process restrictions** when that
switch is available. Android otherwise monitors PRoot's Linux child processes
as phantom processes and may terminate them under its process-count or CPU
rules. This setting is recommended for heavier Chromium, WPS, development, and
multi-session workloads; it is not required for a basic desktop session. See
the [installation Wiki](https://github.com/dwiigoy/nobrain-linux/wiki/Installation-and-Updates#android-child-process-restrictions)
for the ADB fallback, trade-offs, and recovery instructions.

An in-place update signed by the same NoBrain release certificate preserves the
Linux home directory and application data. Keep a backup before updating from
unofficial or differently signed builds.

## Security Defaults

Fresh-install passwords for both `nobrain` and `root` are `nobrain`. Change
them after installation:

```bash
passwd
sudo passwd root
```

SSH is key-only by default, listens on port `2223`, allows the `nobrain` user,
and rejects direct root login. Export the per-install key from:

```text
nobrain-menu -> SSH Access -> Key
```

Then connect from another device:

```bash
ssh -i ~/.ssh/nobrain_ed25519 -p 2223 nobrain@DEVICE_IP
```

`Export key` writes to `Internal storage/Download/NoBrain-SSH` only when
Android shared storage is genuinely mounted. Otherwise the operation fails
with an instruction to grant `All files access` and restart NoBrain; it does
not silently export a private key into an app-specific fallback directory.

The `nobrain` account has passwordless sudo because NoBrain currently uses a
single-owner device model. It is not a multi-user security boundary.

## Source Tree

```text
android/                 Android application source
dwm/                     NoBrain DWM 6.8 modifications
native/termux-x11/       XLorie/Termux:X11 fork source
runtime/                 Menu, terminal, SSH, and package wrappers
rootfs/                  Build 144 package manifest and rootfs notes
tools/                   Dependency fetch, assembly, alignment, and signing
docs/                    Architecture and build documentation
```

The repository intentionally excludes APK files, signing keys, Microsoft
fonts, the compressed rootfs, generated reports, account data, and private
development notes. See [Building](docs/BUILDING.md) for the exact status of the
public build path.

## Known Limitations

- ARM64 only; x86 and 32-bit Android are not supported.
- Desktop Linux applications run through PRoot and X11, not as native Android
  applications.
- GPU acceleration and hardware video decoding are limited by the Android/X11
  integration and device drivers.
- The first public source snapshot does not yet produce the full release APK
  with one command because the rootfs and third-party Android binaries are
  deliberately excluded from Git.
- The runtime has a harmless diagnostic path that looks for the PulseAudio log
  under the old package name; audio itself runs normally.

## License

NoBrain's Android and runtime modifications are released under GPL-3.0. The
Termux:X11-derived code retains GPL-3.0, while DWM retains the MIT/X Consortium
license. Bundled distributions contain additional components under their own
licenses. See [Third-party components](THIRD_PARTY.md).
