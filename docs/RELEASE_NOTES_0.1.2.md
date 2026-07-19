# NoBrain Linux 0.1.2

Patch pre-release based on runtime Build 150.

## Download

`nobrain-linux-0.1.2.apk`

Size: 594,519,323 bytes (567 MiB)

SHA-256:

`2ec9e60f81412c22619cb63acaf7bb02ae5d790d74bb8fe5adb4891e5909cb60`

The APK uses the same NoBrain release certificate as previous public builds.
Install it as an update to preserve the rootfs, Linux home, installed packages,
SSH configuration, Chromium profile, WPS installation, and application data.
A true fresh install was also tested successfully.

## Fixes

- Fresh installs no longer try to rebuild DWM when the bundled binary already
  contains the click-to-focus fix. This prevents startup from stalling at
  `Preparing application support` when Xft development headers are absent.
- An older rootfs that genuinely needs the DWM migration can now defer a
  failed rebuild and continue starting with its existing binary.
- NoBrain repairs the root ownership and setuid modes of `su` and `sudo`, and
  safe ownership and modes for the sudo configuration, on every start.
- The XBPS update wrapper repeats that repair after successful package
  transactions, so SSH Access keeps working after a full system update.
- Near-fullscreen WPS file choosers are now attached to and raised above the
  export dialog that opened them.

## Runtime verification

Build 148 was tested from a true uninstall/fresh install through `Desktop
ready`, followed by `sudo xbps-install -Su`, WPS installation, SSH key export,
and a key-only SSH audit. Sudo reached UID 0, direct root SSH remained rejected,
the SSH key fingerprints matched, all WPS executables resolved their shared
libraries, package databases passed their relevant checks, and the desktop,
X server, SSH server, and WPS remained operational. The Build 150 dialog fix
was then verified live against WPS: the chooser became a transient child of the
export dialog and appeared above it.
For the final non-fresh regression, WPS alone was removed and reinstalled with
the packaged `install-wps` script. Existing Linux documents and WPS settings
were preserved; Writer launched successfully and its first-run dialog stacked
correctly above the application window.
