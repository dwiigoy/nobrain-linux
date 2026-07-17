#!/bin/bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
sed \
    -e "s#/usr/local/bin/nobrain-menu-core#$TMP/bin/nobrain-menu-core#g" \
    -e "s#/tmp/nobrain-menu#$TMP/nobrain-menu#g" \
    -e "s#/tmp/nobrain-xbindkeys#$TMP/nobrain-xbindkeys#g" \
    "$SCRIPT_DIR/nobrain-menu" > "$TMP/menu"
chmod 755 "$TMP/menu"

cat > "$TMP/bin/pgrep" <<'EOF'
#!/bin/sh
[ -f "$MOCK_XBINDKEYS_STATE" ]
EOF

cat > "$TMP/bin/pkill" <<'EOF'
#!/bin/sh
rm -f "$MOCK_XBINDKEYS_STATE"
printf 'stopped\n' >> "$MOCK_XBINDKEYS_EVENTS"
EOF

cat > "$TMP/bin/xbindkeys" <<'EOF'
#!/bin/sh
touch "$MOCK_XBINDKEYS_STATE"
printf 'started\n' >> "$MOCK_XBINDKEYS_EVENTS"
EOF

cat > "$TMP/bin/xdotool" <<'EOF'
#!/bin/sh
exit 0
EOF

cat > "$TMP/bin/nobrain-menu-core" <<'EOF'
#!/bin/sh
test ! -f "$MOCK_XBINDKEYS_STATE"
exit 0
EOF
chmod 755 "$TMP/bin/"*

touch "$TMP/xbindkeys-running"
PATH="$TMP/bin:$PATH" \
MOCK_XBINDKEYS_STATE="$TMP/xbindkeys-running" \
MOCK_XBINDKEYS_EVENTS="$TMP/xbindkeys-events" \
    "$TMP/menu"

test -f "$TMP/xbindkeys-running"
test "$(sed -n '1p' "$TMP/xbindkeys-events")" = stopped
test "$(sed -n '2p' "$TMP/xbindkeys-events")" = started
grep -q 'xbindkeys paused' "$TMP/nobrain-menu.log"
grep -q 'xbindkeys restarted' "$TMP/nobrain-menu.log"

echo "menu wrapper regression tests passed"
