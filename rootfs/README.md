# Rootfs

The compressed Build 144 rootfs is approximately 583 MB and is distributed
inside the release APK, not in Git.

`packages-build144.txt` is generated from the XBPS package database in the
staging rootfs and records the exact installed package versions. The rootfs is
Void Linux ARM64 and is executed through PRoot against the Android host kernel.

Do not add user home data, browser profiles, SSH private keys, Microsoft fonts,
generated reports, package caches, or development credentials to a public
rootfs archive.
