# Third-Party Components

NoBrain Linux combines original integration code with existing free and open
source projects. Each component keeps its upstream license.

| Component | Use | Source | License |
| --- | --- | --- | --- |
| Termux:X11 / XLorie | Android-hosted X server | https://github.com/termux/termux-x11 | GPL-3.0 |
| DWM 6.8 | Window manager | https://git.suckless.org/dwm/ | MIT/X Consortium |
| PRoot | Rootless Linux process environment | https://github.com/proot-me/PRoot | GPL-2.0 or later |
| Void Linux packages | ARM64 guest userspace | https://github.com/void-linux/void-packages | Per package |
| PulseAudio | Android/Linux audio bridge | https://gitlab.freedesktop.org/pulseaudio/pulseaudio | LGPL-2.1 or later and per-file terms |
| BusyBox | Android-side utility binary | https://busybox.net/ | GPL-2.0 |
| VirGLRenderer | Optional graphics path | https://gitlab.freedesktop.org/virgl/virglrenderer | MIT |

The complete package set present in the Build 144 rootfs is recorded in
`rootfs/packages-build144.txt`. Source recipes for Void packages are available
from the Void `void-packages` repository. NoBrain does not relicense those
packages.

The Microsoft-compatible fonts installed on the owner's development device are
not part of this repository or the public APK.
