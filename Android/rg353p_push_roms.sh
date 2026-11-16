#!/usr/bin/env bash
# Copy Oracle playtest ROMs (+ sidecar .srm) to the RG353P ROM folder.
# Usage: Android/rg353p_push_roms.sh <dir-with-sfc-files> [--serial SERIAL] [--dry-run]
# Never deletes or overwrites anything on the device: an existing file with the
# same name is skipped. The app imports a sidecar .srm once, on first launch of
# that ROM, and never overwrites its own Saves/<name>.srm afterwards.
set -euo pipefail
SRC=""; SERIAL="${ANDROID_SERIAL:-}"; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) SRC="$1"; shift ;;
  esac
done
[ -d "$SRC" ] || { echo "usage: $0 <dir> [--serial S] [--dry-run]" >&2; exit 2; }
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
DEST=/sdcard/Android/data/ca.mesen.oos/files
[ $DRY -eq 1 ] || "${ADB[@]}" shell "mkdir -p '$DEST'"
shopt -s nullglob
for f in "$SRC"/*.sfc "$SRC"/*.srm; do
  name=$(basename "$f")
  if "${ADB[@]}" shell "test -e '$DEST/$name'" 2>/dev/null; then
    echo "skip (exists): $name"; continue
  fi
  if [ $DRY -eq 1 ]; then echo "would push: $name"; continue; fi
  # Android 11 refuses adb push straight into Android/data (fchown); stage, then cp.
  tmp="/data/local/tmp/rg353p-push.bin"
  "${ADB[@]}" push "$f" "$tmp" >/dev/null
  "${ADB[@]}" shell "cp '$tmp' '$DEST/$name' && rm -f '$tmp'"
  local_sum=$(shasum -a 256 "$f" | cut -d' ' -f1)
  remote_sum=$("${ADB[@]}" shell "sha256sum '$DEST/$name'" | cut -d' ' -f1)
  [ "$local_sum" = "$remote_sum" ] || { echo "HASH MISMATCH: $name" >&2; exit 1; }
  echo "pushed: $name ${local_sum:0:8}"
done
