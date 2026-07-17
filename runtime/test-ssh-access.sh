#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$SCRIPT_DIR/nobrain-ssh-access

sh -n "$SCRIPT"

grep -q '^CONFIG_DIR=/root/.config/nobrain/ssh$' "$SCRIPT"
grep -q '^CONFIG=\$CONFIG_DIR/sshd_config$' "$SCRIPT"
grep -q '^MODE=\$CONFIG_DIR/mode$' "$SCRIPT"
grep -q '^AUTH=\$AUTH_DIR/nobrain$' "$SCRIPT"
grep -q "printf '1. Key (secure, recommended)" "$SCRIPT"
grep -q "printf '2. Password" "$SCRIPT"
grep -q "printf '3. Custom" "$SCRIPT"

grep -q '^PubkeyAuthentication yes$' "$SCRIPT"
grep -q '^PasswordAuthentication no$' "$SCRIPT"
grep -q '^PermitRootLogin no$' "$SCRIPT"
grep -q '^AllowUsers nobrain$' "$SCRIPT"
grep -q '^PubkeyAuthentication no$' "$SCRIPT"
grep -q '^PasswordAuthentication yes$' "$SCRIPT"
grep -q '^AuthenticationMethods password$' "$SCRIPT"

grep -q 'passwd -S nobrain' "$SCRIPT"
grep -q 'Use existing password' "$SCRIPT"
grep -q 'passwd nobrain' "$SCRIPT"
grep -q 'sshd -t -f "\$_candidate"' "$SCRIPT"
grep -q 'config_ports "\$CONFIG"' "$SCRIPT"
grep -q 'awk .*pidfile' "$SCRIPT"
grep -q 'migrate_managed_config' "$SCRIPT"
grep -q 'write_build140_key_config' "$SCRIPT"
grep -q 'write_build140_password_config' "$SCRIPT"
grep -q 'sync_legacy_config' "$SCRIPT"

if grep -q 'UsePrivilegeSeparation' "$SCRIPT"; then
    echo 'deprecated SSH option found' >&2
    exit 1
fi

echo 'SSH access manager regression tests passed'
