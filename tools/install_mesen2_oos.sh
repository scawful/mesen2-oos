#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install_mesen2_oos.sh [options]

Options:
  --source PATH      Source .app bundle (defaults to publish output)
  --dest DIR         Destination directory (default: /Applications)
  --name NAME        Destination app name (default: Mesen2 OOS.app)
  --user             Install to ~/Applications
  --prune            Move other Mesen app bundles in DEST to backup
  --no-backup        Replace without backing up existing bundle(s)
  -h, --help         Show this help

Notes:
  - This only moves/copies app bundles; it does not touch ROMs, saves, or config.
  - Config stays in ~/Library/Application Support/Mesen2.
EOF
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISH_DIR="${ROOT_DIR}/bin/osx-arm64/Release/osx-arm64/publish"

DEST_DIR="/Applications"
DEST_NAME="Mesen2 OOS.app"
SRC_APP=""
DO_BACKUP=1
DO_PRUNE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      SRC_APP="$2"
      shift 2
      ;;
    --dest)
      DEST_DIR="$2"
      shift 2
      ;;
    --name)
      DEST_NAME="$2"
      shift 2
      ;;
    --user)
      DEST_DIR="$HOME/Applications"
      shift
      ;;
    --no-backup)
      DO_BACKUP=0
      shift
      ;;
    --prune)
      DO_PRUNE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$SRC_APP" ]]; then
  if [[ -d "${PUBLISH_DIR}/Mesen2 OOS.app" ]]; then
    SRC_APP="${PUBLISH_DIR}/Mesen2 OOS.app"
  elif [[ -d "${PUBLISH_DIR}/Mesen.app" ]]; then
    SRC_APP="${PUBLISH_DIR}/Mesen.app"
  else
    echo "Build output not found in: ${PUBLISH_DIR}" >&2
    echo "Run: make -j8 ui" >&2
    exit 1
  fi
fi

if [[ ! -d "$SRC_APP" ]]; then
  echo "Source app not found: $SRC_APP" >&2
  exit 1
fi

DEST_APP="${DEST_DIR}/${DEST_NAME}"

SUDO=""
if [[ -d "$DEST_DIR" ]]; then
  if [[ ! -w "$DEST_DIR" ]]; then
    SUDO="sudo"
  fi
else
  PARENT_DIR="$(dirname "$DEST_DIR")"
  if [[ ! -w "$PARENT_DIR" ]]; then
    SUDO="sudo"
  fi
fi

if [[ ! -d "$DEST_DIR" ]]; then
  $SUDO mkdir -p "$DEST_DIR"
fi

timestamp="$(date +"%Y%m%d-%H%M%S")"
backup_dir="${DEST_DIR}/Mesen2-Backups/${timestamp}"

backup_or_remove() {
  local path="$1"
  if [[ ! -d "$path" ]]; then
    return 0
  fi
  if [[ "$DO_BACKUP" -eq 1 ]]; then
    $SUDO mkdir -p "$backup_dir"
    $SUDO mv "$path" "$backup_dir/"
  else
    $SUDO rm -rf "$path"
  fi
}

if [[ "$DO_PRUNE" -eq 1 ]]; then
  for name in "Mesen.app" "Mesen2.app" "Mesen2 OOS.app"; do
    prune_path="${DEST_DIR}/${name}"
    if [[ "$prune_path" != "$DEST_APP" ]]; then
      backup_or_remove "$prune_path"
    fi
  done
fi

backup_or_remove "$DEST_APP"

$SUDO /usr/bin/ditto "$SRC_APP" "$DEST_APP"

echo "Installed: $DEST_APP"
