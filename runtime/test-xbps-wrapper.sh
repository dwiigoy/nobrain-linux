#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

sed \
    -e "s#/usr/bin/xbps-install#$TMP/mock-xbps-install#g" \
    -e 's#python3 /usr/local/lib/nobrain-pkgdb-fix.py#true #' \
    "$SCRIPT_DIR/nobrain-xbps-install" > "$TMP/wrapper"
chmod 755 "$TMP/wrapper"

cat > "$TMP/mock-xbps-install" <<'EOF'
#!/bin/sh
count=0
[ -f "$MOCK_COUNT" ] && count=$(cat "$MOCK_COUNT")
count=$((count + 1))
printf '%s\n' "$count" > "$MOCK_COUNT"

if [ "$MOCK_MODE" = transient ] && [ "$count" -eq 1 ]; then
    echo "ERROR: [trans] failed to download package: Input/output error"
    exit 1
fi
if [ "$MOCK_MODE" = permanent ]; then
    echo "ERROR: Package 'missing' not found in repository pool."
    exit 2
fi
echo success
exit 0
EOF
chmod 755 "$TMP/mock-xbps-install"

MOCK_MODE=transient MOCK_COUNT="$TMP/count" "$TMP/wrapper" -Suy \
    > "$TMP/transient.log" 2>&1
test "$(cat "$TMP/count")" = 2
test "$(grep -c 'xbps failed; retry' "$TMP/transient.log")" = 1

rm -f "$TMP/count"
set +e
MOCK_MODE=permanent MOCK_COUNT="$TMP/count" "$TMP/wrapper" missing \
    > "$TMP/permanent.log" 2>&1
ret=$?
set -e
test "$ret" = 2
test "$(cat "$TMP/count")" = 1
test "$(grep -c 'xbps failed; retry' "$TMP/permanent.log" || true)" = 0

grep -q 'system_update=1' "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'chmod 4755 /usr/bin/su /bin/su /usr/bin/sudo' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'chown 0:0 /usr/bin/su /bin/su /usr/bin/sudo' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'chmod 0644 /etc/sudo.conf' "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'chmod 0440 /etc/sudoers' "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'sshd_config=/root/.config/nobrain/ssh/sshd_config' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'sshd -t -f "$sshd_config"' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'sshd_pidfile=.*sshd -T -f "$sshd_config"' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'sshd_pid=$(cat "$sshd_pidfile"' \
    "$SCRIPT_DIR/nobrain-xbps-install"
grep -q 'kill -HUP "$sshd_pid"' "$SCRIPT_DIR/nobrain-xbps-install"

echo "xbps wrapper regression tests passed"
