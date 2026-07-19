#!/usr/bin/env python3
import base64
import json
import re
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/nobrain/linux/PocketService.java"
MAIN_ACTIVITY = ROOT / "android/app/src/main/java/com/nobrain/linux/MainActivity.java"
CANONICAL_XBPS = ROOT / "runtime/nobrain-xbps-install"
CANONICAL_MENU_CORE = ROOT / "runtime/nobrain-menu-core"
CANONICAL_MENU = ROOT / "runtime/nobrain-menu"
CANONICAL_TERMINAL = ROOT / "runtime/nobrain-terminal"
CANONICAL_SSH_ACCESS = ROOT / "runtime/nobrain-ssh-access"
CANONICAL_DAEMON = ROOT / "runtime/nobrain-cmd-daemon"


def java_strings(source):
    values = []
    i = 0
    while i < len(source):
        if source.startswith("//", i):
            i = source.find("\n", i)
            if i < 0:
                break
        elif source.startswith("/*", i):
            i = source.find("*/", i + 2)
            if i < 0:
                raise SystemExit("unterminated Java comment")
            i += 2
        elif source[i] == '"':
            start = i
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == '"':
                    i += 1
                    values.append(json.loads(source[start:i]))
                    break
                else:
                    i += 1
            else:
                raise SystemExit("unterminated Java string")
        else:
            i += 1
    return values


text = SOURCE.read_text()
start = text.index("serverProcess = ProotManager.startProot(this,")
end = text.index("\n            );", start)
command = "".join(java_strings(text[start:end]))

build_markers = re.findall(r"BUILD=([0-9]+)", command)
if len(build_markers) != 1:
    raise SystemExit("startup command must contain exactly one numeric build marker")
if build_markers[0] != "146":
    raise SystemExit("startup command must identify Build 146")
for forbidden in (
    "tiny@localhost",
    "AAAAC3NzaC1lZDI1NTE5AAAAIFt47",
    "AAAAC3NzaC1lZDI1NTE5AAAAIFkg1dVS",
    "UsePrivilegeSeparation",
):
    if forbidden in command:
        raise SystemExit(f"startup command contains forbidden legacy SSH material: {forbidden}")
ssh_bootstrap = "ssh-keygen -q -t ed25519 -N '' -C 'nobrain-per-install'"
if "if [ ! -s \"$SSH_AUTH\" ]; then " not in command or ssh_bootstrap not in command:
    raise SystemExit("startup must create a unique SSH bootstrap only when authorization is absent")
if "PasswordAuthentication no" not in command \
        or "PermitRootLogin no" not in command \
        or "AllowUsers nobrain" not in command:
    raise SystemExit("fresh SSH configuration must be key-only for user nobrain")
if "DEFAULT_PASSWORD_HASH=" not in command \
        or "USER_PASSWORD_STATE=/etc/nobrain-user-password-state;" not in command \
        or "nobrain:$DEFAULT_PASSWORD_HASH:" not in command \
        or "root:$DEFAULT_PASSWORD_HASH:" not in command:
    raise SystemExit("managed local passwords must default to nobrain without overwriting owners")
if "passwd -l root" in command:
    raise SystemExit("startup must not lock the documented local default password")
if "NoBrain security notice:" not in command \
        or "nobrain/security-notice" not in command:
    raise SystemExit("first shell must show the password-change recommendation once")
if "sed -i 's/^root:[!*]" in command:
    raise SystemExit("startup must not rewrite the root password hash on every boot")
if "SSH_CONFIG=/root/.config/nobrain/ssh/sshd_config;" not in command:
    raise SystemExit("startup must use the persistent owner-controlled SSH config")
if "if [ ! -s \"$SSH_CONFIG\" ]; then " not in command \
        or "install -m 600 \"$SSH_CONFIG\" \"$SSH_LEGACY\";" not in command:
    raise SystemExit("startup must initialize once and only sync the compatibility config")
if "> /etc/ssh/sshd_config.nobrain" in command:
    raise SystemExit("startup must not overwrite the SSH policy unconditionally")
if "/usr/bin/sshd -f \"$SSH_CONFIG\"" not in command:
    raise SystemExit("listener must start from the persistent SSH config")
if "Starting SSH on port(s) ${SSH_PORTS:-custom}" not in command:
    raise SystemExit("startup status must report effective custom SSH ports")
if "SSH configuration invalid; use SSH Access locally" not in command:
    raise SystemExit("invalid custom SSH policy must be preserved for local repair")
if "SSH_AUTH=/etc/ssh/authorized_keys/nobrain;" not in command \
        or "migrated SSH keys from root to nobrain" not in command:
    raise SystemExit("startup must preserve Build 140 keys while moving managed login to nobrain")
if "SSH_BUILD140_KEY=" not in command or "SSH_BUILD140_PASSWORD=" not in command:
    raise SystemExit("managed Build 140 SSH modes require an explicit migration path")
if "SSH :2223" in MAIN_ACTIVITY.read_text():
    raise SystemExit("Android startup UI must not hardcode the default SSH port")
if "rm -f /var/db/xbps/lock" in command:
    raise SystemExit("startup command must not unlink the XBPS lock")
pkgdb_fix = "python3 /usr/local/lib/nobrain-pkgdb-fix.py >> $DBG 2>&1;"
xterm_install = "xbps-install -fy xterm"
if pkgdb_fix not in command or command.index(pkgdb_fix) > command.index(xterm_install):
    raise SystemExit("package metadata repair must run before first xterm install")
dmenu_install = "xbps-install -fy dmenu"
dmenu_chmod = "chmod 755 /usr/bin/dmenu /usr/bin/dmenu_path /usr/bin/dmenu_run /usr/bin/stest"
dmenu_cache = "su nobrain -c \"HOME=/home/nobrain USER=nobrain /usr/local/bin/nobrain-menu-core --refresh-cache\""
if dmenu_install not in command:
    raise SystemExit("startup must install dmenu when its binaries are absent")
if dmenu_chmod not in command or command.index(dmenu_chmod) < command.index(dmenu_install):
    raise SystemExit("startup must repair all dmenu executable permissions after install check")
daemon_chmod = "chmod +x /usr/local/bin/nobrain-cmd-daemon;"
daemon_launch = "su nobrain -c \"DISPLAY=127.0.0.1:4 PULSE_SERVER=tcp:127.0.0.1:4713 " \
    "HOME=/home/nobrain USER=nobrain /usr/local/bin/nobrain-cmd-daemon\""
if dmenu_cache not in command or command.index(dmenu_cache) < command.index(daemon_chmod):
    raise SystemExit("startup must build the app cache after writing all command binaries")
if daemon_launch not in command or command.index(dmenu_cache) > command.index(daemon_launch):
    raise SystemExit("startup must build the app cache before publishing session readiness")
if "xbps-install -fy xdotool" not in command or "chmod 755 /usr/bin/xdotool" not in command:
    raise SystemExit("visible-window readiness requires executable xdotool")
if "Install WPS (~367MB)|nobrain-terminal -e install-wps" not in command:
    raise SystemExit("WPS installer pin must launch in the managed terminal")
if "Install WPS (~367MB)|st -e install-wps" in command:
    raise SystemExit("WPS installer pin must not use the legacy st terminal")
if "mkdir -p /home/nobrain/.cache;" not in command:
    raise SystemExit("startup must create the dmenu cache directory")
if "> /var/db/shlibs;" not in command \
        or command.index("> /var/db/shlibs;") > command.index(pkgdb_fix):
    raise SystemExit("shlib index must exist before package metadata repair")

shlibs_match = re.search(
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /var/db/shlibs;",
    command,
)
if not shlibs_match:
    raise SystemExit("embedded shlib index payload missing")
shlibs = base64.b64decode(shlibs_match.group(1))
if b"libutempter.so.0 libutempter>=" not in shlibs:
    raise SystemExit("shlib index must identify xterm's libutempter provider")

with tempfile.NamedTemporaryFile("w", suffix=".sh") as script:
    script.write("#!/bin/bash\n")
    script.write(command)
    script.flush()
    subprocess.run(["bash", "-n", script.name], check=True)

match = re.search(
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /usr/local/bin/xbps-install;",
    command,
)
if not match:
    raise SystemExit("embedded XBPS wrapper payload missing")
embedded = base64.b64decode(match.group(1))
canonical = CANONICAL_XBPS.read_bytes()
if embedded != canonical:
    raise SystemExit("embedded XBPS wrapper differs from canonical script")

with tempfile.NamedTemporaryFile("wb", suffix=".sh") as wrapper:
    wrapper.write(embedded)
    wrapper.flush()
    subprocess.run(["bash", "-n", wrapper.name], check=True)

core_match = re.search(
    r"mv -f /usr/local/bin/nobrain-menu /usr/local/bin/nobrain-menu-core;"
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /usr/local/bin/nobrain-menu-core;",
    command,
)
if not core_match:
    raise SystemExit("embedded menu core payload missing")
embedded_core = base64.b64decode(core_match.group(1))
reconcile = "RUNTIME_CANON=/usr/local/share/nobrain/runtime;"
reconcile_done = "RUNTIME_RECONCILE_OK version=1"
if reconcile not in command or reconcile_done not in command:
    raise SystemExit("canonical runtime reconciliation is missing")
if command.index(reconcile) <= core_match.end():
    raise SystemExit("canonical runtime must replace the generated legacy menu core")
if command.index(reconcile_done) > command.index(dmenu_cache):
    raise SystemExit("runtime reconciliation must finish before menu cache generation")
for runtime_pair in (
        "chromium:chromium",
        "install-wps:install-wps",
        "nobrain-chromium-shutdown:nobrain-chromium-shutdown",
        "nobrain-menu-core:nobrain-menu-core",
):
    if runtime_pair not in command:
        raise SystemExit(f"canonical runtime mapping missing: {runtime_pair}")
if b"--refresh-cache" not in CANONICAL_MENU_CORE.read_bytes():
    raise SystemExit("canonical menu core must support refresh mode")


def heredoc_payload(path):
    marker = f"cat > {path} <<'EOF'\n"
    start = command.index(marker) + len(marker)
    end = command.index("EOF\n", start)
    return command[start:end].encode()


menu_match = re.search(
    r"base64 -d > /usr/local/bin/nobrain-menu-core;"
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /usr/local/bin/nobrain-menu;",
    command,
)
if not menu_match:
    raise SystemExit("embedded menu wrapper payload missing")
embedded_menu = base64.b64decode(menu_match.group(1))
if embedded_menu != CANONICAL_MENU.read_bytes():
    raise SystemExit("embedded menu wrapper differs from canonical script")
if b"xdotool search --onlyvisible --class dmenu" not in embedded_menu:
    raise SystemExit("menu wrapper must publish visibility from the mapped X11 window")

terminal_match = re.search(
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /usr/local/bin/nobrain-terminal;",
    command,
)
if not terminal_match:
    raise SystemExit("embedded terminal payload missing")
embedded_terminal = base64.b64decode(terminal_match.group(1))
if embedded_terminal != CANONICAL_TERMINAL.read_bytes():
    raise SystemExit("embedded terminal differs from canonical script")
if b"-si -sk" not in embedded_terminal:
    raise SystemExit("terminal must inhibit tty-output scrolling until keypress")

ssh_access_match = re.search(
    r"echo '([A-Za-z0-9+/=]+)' \| base64 -d > /usr/local/bin/nobrain-ssh-access;",
    command,
)
if not ssh_access_match:
    raise SystemExit("embedded SSH access manager payload missing")
embedded_ssh_access = base64.b64decode(ssh_access_match.group(1))
canonical_ssh_access = CANONICAL_SSH_ACCESS.read_bytes()
for expected in (
        b"1. Key (secure, recommended)",
        b"2. Password",
        b"3. Custom",
        b"CONFIG_DIR=/root/.config/nobrain/ssh",
        b"CONFIG=$CONFIG_DIR/sshd_config",
        b"AuthenticationMethods password",
        b"AUTH=$AUTH_DIR/nobrain",
        b"AllowUsers nobrain",
        b"PermitRootLogin no",
        b"passwd nobrain",
        b"PubkeyAuthentication no",
        b"PasswordAuthentication yes",
        b"Use existing password",
        b"Validate and apply working configuration",
        b"sshd_config.backup",
):
    if expected not in canonical_ssh_access:
        raise SystemExit(f"SSH manager is missing required workflow: {expected!r}")
for expected in (
        b"SHARED_STORAGE_MARKER=/tmp/nobrain-shared-storage-active",
        b"require_shared_storage()",
        b"Android shared storage is unavailable.",
        b"Nothing was exported or imported.",
        b"Internal storage/Download/NoBrain-SSH",
):
    if expected not in canonical_ssh_access:
        raise SystemExit(f"SSH storage guard is missing: {expected!r}")
for expected in (
        b"export_bootstrap() {\n    require_shared_storage || return 1",
        b"import_authorized_keys() {\n    require_shared_storage || return 1",
        b"if require_shared_storage; then\n                    rm -f \"$EXPORT_DIR/nobrain_ed25519\"",
        b"if ! require_shared_storage; then\n                    :\n                elif [ -s \"$IMPORT_CONFIG\" ]",
):
    if expected not in canonical_ssh_access:
        raise SystemExit(f"SSH exchange action is not storage-gated: {expected!r}")
if b"UsePrivilegeSeparation" in canonical_ssh_access:
    raise SystemExit("SSH manager contains a deprecated server option")
if b"SSH Access|nobrain-terminal -e sudo -n /usr/local/bin/nobrain-ssh-access" \
        not in embedded_core:
    raise SystemExit("default menu pins must expose the SSH access manager")
if "grep -q '^SSH Access|' /root/.nobrain-pins" not in command:
    raise SystemExit("startup must add SSH access management to existing pin sets")

embedded_daemon = heredoc_payload("/usr/local/bin/nobrain-cmd-daemon")
if embedded_daemon != CANONICAL_DAEMON.read_bytes():
    raise SystemExit("embedded command daemon differs from canonical script")
if b"su nobrain" in embedded_daemon:
    raise SystemExit("command daemon must not spawn su for each command")
if "su nobrain -c \"DISPLAY=127.0.0.1:4 PULSE_SERVER=tcp:127.0.0.1:4713 " \
        "HOME=/home/nobrain USER=nobrain /usr/local/bin/nobrain-cmd-daemon\"" not in command:
    raise SystemExit("command daemon must be launched as user nobrain")

for payload in (
        embedded_core, embedded_menu, embedded_terminal, embedded_ssh_access, embedded_daemon):
    with tempfile.NamedTemporaryFile("wb", suffix=".sh") as script:
        script.write(payload)
        script.flush()
        subprocess.run(["bash", "-n", script.name], check=True)

print("STARTUP_COMMAND_SYNTAX_OK")
print("BUILD_MARKER=" + build_markers[0])
print("PKGDB_BEFORE_XTERM_OK")
print("XTERM_SHLIB_PROVIDER_OK")
print("DMENU_RUNTIME_READY_OK")
print("XBPS_PAYLOAD_SYNC_OK")
print("MENU_PAYLOADS_SYNC_OK")
print("TERMINAL_PAYLOAD_SYNC_OK")
print("PER_INSTALL_SSH_ACCESS_OK")
