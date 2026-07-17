# Building NoBrain Linux

This source snapshot corresponds to the functionality shipped in Build 144.
The Java, runtime scripts, DWM modifications, XLorie source, dependency commits,
and rootfs package manifest are public.

## Current Reproducibility Status

The repository does not yet produce the complete 597 MB release APK from an
empty clone with one command. These large/generated inputs are intentionally
excluded from Git:

- `rootfs.tar.gz`
- Android-native PRoot, PulseAudio, BusyBox, and VirGL binaries
- compiled `libXlorie.so`
- the historical base APK used by the current ZIP-swap release pipeline
- release signing keys

The APK release remains installable and the source for NoBrain's modifications
is available here. Rootfs and third-party binary automation is the next build
infrastructure milestone. Do not claim byte-for-byte reproducibility yet.

## Host Requirements

- Linux host
- JDK 17 or newer
- Android SDK 34 and Build Tools
- Android NDK r27c or a compatible ARM64 toolchain
- Python 3.10 or newer
- CMake 3.22+, Ninja, Bison, Clang, pkg-config
- X11, Xft, Fontconfig, and Xinerama development packages for DWM
- XBPS tooling when creating the Void rootfs

## Android Source Check

Set `ANDROID_HOME` or create an untracked `android/local.properties` containing
the Android SDK path, then run:

```bash
cd android
./gradlew :app:compileDebugJavaWithJavac
```

Google's Linux AAPT2 artifact is x86_64. On an ARM64 development host, use an
ARM64-compatible Android build-tools package or validate the Java layer
directly with `javac`, as used by the Build 144 pipeline. The source snapshot
was checked successfully with Java 8 target compatibility; full Gradle resource
linking was not executed on the ARM64 release host.

A runnable APK additionally requires the files documented in
`android/app/src/main/assets/README.md` and
`android/app/src/main/jniLibs/README.md`.

## DWM

```bash
make -C dwm clean all
```

Copy the resulting `dwm/dwm` into the rootfs as `/usr/local/bin/dwm`.

## XLorie

Fetch the exact native dependency commits used by Build 144:

```bash
./tools/fetch-xlorie-deps.sh
```

Using the custom Clang toolchain:

```bash
export NOBRAIN_NDK_SYSROOT=/absolute/path/to/ndk-sysroot
cmake -S native/termux-x11/app/src/main/cpp \
  -B native/termux-x11/app/src/main/cpp/build-aarch64 \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/native/termux-x11/aarch64-android-clang19.cmake"
cmake --build native/termux-x11/app/src/main/cpp/build-aarch64
```

The release pipeline strips the resulting shared library before packaging it.

## Rootfs

`rootfs/packages-build144.txt` records all 426 package versions in the Build
144 staging filesystem. A compatible rootfs starts from Void Linux ARM64 and
installs those packages through XBPS. Runtime-specific configuration is applied
by `PocketService` on startup and by the scripts in `runtime/`.

The archive expected by the Android application is:

```text
android/app/src/main/assets/rootfs.tar.gz
```

It must contain a normal `/home/nobrain`, must not contain private account data,
and must not include the removed Codex, Steam, SXMO, or game experiment tools.

## Historical Release Assembly

The current release was assembled with `tools/build_zip_swap.py`. The script
accepts paths through environment variables:

```bash
export NOBRAIN_BASE_APK=/path/to/base.apk
export NOBRAIN_ROOTFS=/path/to/rootfs.tar.gz
export NOBRAIN_STAGING_ROOTFS=/path/to/staging-rootfs
export NOBRAIN_XLORIE_SO=/path/to/libXlorie.so
export NOBRAIN_ICON=/path/to/nobrain-icon.png
python3 tools/build_zip_swap.py
```

This is retained to document the actual Build 144 pipeline. It is transitional,
not the intended long-term public build system.

## Signing

Never commit a keystore or password. Align and sign using environment variables:

```bash
python3 tools/zipalign.py unsigned.apk aligned.apk

export NOBRAIN_APKSIGNER_JAR=/path/to/apksigner.jar
export NOBRAIN_KEYSTORE=/secure/path/release.keystore
export NOBRAIN_KEY_ALIAS=nobrain-os
export NOBRAIN_KEYSTORE_PASS='your-secret'
export NOBRAIN_EXPECTED_CERT_SHA256='expected-certificate-digest'
python3 tools/sign_apk.py aligned.apk signed.apk
```
