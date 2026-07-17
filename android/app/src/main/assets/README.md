# Runtime Assets

The public repository does not store the large or generated runtime assets.
Before assembling a runnable APK, provide:

```text
rootfs.tar.gz
native-libs/              Android-side versioned runtime libraries
pulseaudio/               Android PulseAudio libraries and modules
volume-tools/pamixer      ARM64 pamixer binary
websockify.tar.gz         Compatibility websockify bundle
```

Use upstream source and package recipes listed in `THIRD_PARTY.md`. Do not copy
private device data into this directory.
