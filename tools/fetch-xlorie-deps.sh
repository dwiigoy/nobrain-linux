#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_root="$repo_root/native/termux-x11"
lock_file="$source_root/dependencies.lock"

while IFS='|' read -r path url commit; do
    case "$path" in
        ''|'#'*) continue ;;
    esac

    target="$source_root/$path"
    if [ ! -d "$target/.git" ]; then
        rm -rf "$target"
        git clone --no-checkout "$url" "$target"
    fi

    git -C "$target" fetch --depth 1 origin "$commit"
    git -C "$target" checkout --detach "$commit"
    printf 'ready %s @ %s\n' "$path" "$commit"
done < "$lock_file"
