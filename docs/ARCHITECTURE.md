# Architecture

NoBrain Linux has three execution layers.

## Android Host

The Android application owns the Activity, foreground service, XLorie surface,
input bridge, audio process, navbar, lifecycle, and PRoot process. The installed
package name is `com.nobrain.panel`.

`MainActivity` maintains the Android surface and converts touch, mouse, and
keyboard events into X11 input. `PocketService` keeps the session alive and
starts the Android-side audio and Linux processes. `ProotManager` extracts the
rootfs and starts commands in the guest.

## XLorie

XLorie is a modified Termux:X11 server. It renders X11 buffers into the Android
surface and handles clipboard, resize, input, and surface reconnection. The
source lives in `native/termux-x11`; exact dependency revisions are in
`dependencies.lock`.

## Void Linux Guest

The guest is an ARM64 Void Linux filesystem executed by PRoot against the
Android kernel. DWM is the window manager. Runtime wrappers under `runtime/`
provide the menu, terminal, package update handling, SSH policy, and Android
command bridge.

The guest communicates with XLorie over display `127.0.0.1:4` and with the
Android-hosted PulseAudio server over TCP port `4713` on localhost.

## Persistence

The extracted guest rootfs and `/home/nobrain` live in Android private app
storage. Updates signed with the same release certificate preserve this data.
NoBrain only re-extracts the rootfs when its internal runtime version marker
changes.
