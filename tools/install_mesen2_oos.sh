#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install_mesen2_oos.sh [options]

Options:
  --source PATH      Source .app bundle (defaults to publish output)
  --dest PATH        Destination directory or .app path (default: /Applications)
  --name NAME        Destination app name (default: Mesen2 OOS.app)
  --user             Install to ~/Applications
  --prune            Move other Mesen app bundles in DEST to backup
  --prune-all        Move other Mesen app bundles in /Applications and ~/Applications
  --no-backup        Replace without backing up existing bundle(s)
  --force            Alias for --no-backup
  --dry-run          Print actions without copying
  --no-core          Skip deploying MesenCore.dylib into the app bundle
  --core PATH        Override core library path (default: InteropDLL/obj.osx-arm64/MesenCore.dylib)
  --symlink          Create/refresh Mesen.app -> installed app in DEST
  --symlink-force    Replace existing Mesen.app if needed
  -h, --help         Show this help

Notes:
  - This only moves/copies app bundles; it does not touch ROMs, saves, or config.
  - Config stays in ~/Library/Application Support/Mesen2.
  - If /usr/local/sbin/agent-root exists and DEST needs privilege, it is used instead of sudo.
    Grant with: ws root grant --local --ttl 30m --allow "ditto,mkdir,mv,rm"

Env:
  MESEN_APP_SRC      Override source app bundle
  MESEN_APP_DEST     Override destination directory or .app path
  MESEN_APP_NAME     Override destination app name
  MESEN_CORE_SRC     Override MesenCore.dylib path for --core
EOF
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLISH_DIR="${ROOT_DIR}/bin/osx-arm64/Release/osx-arm64/publish"

DEST_DIR="/Applications"
DEST_NAME="Mesen2 OOS.app"
SRC_APP=""
DO_BACKUP=1
DO_PRUNE=0
PRUNE_ALL=0
DRY_RUN=0
DO_SYMLINK=0
SYMLINK_FORCE=0
DEPLOY_CORE=1
CORE_SRC=""
AGENT_ROOT="/usr/local/sbin/agent-root"

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
    --force)
      DO_BACKUP=0
      shift
      ;;
    --prune)
      DO_PRUNE=1
      shift
      ;;
    --prune-all)
      DO_PRUNE=1
      PRUNE_ALL=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --no-core)
      DEPLOY_CORE=0
      shift
      ;;
    --core)
      CORE_SRC="$2"
      shift 2
      ;;
    --symlink)
      DO_SYMLINK=1
      shift
      ;;
    --symlink-force)
      DO_SYMLINK=1
      SYMLINK_FORCE=1
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

if [[ -z "$SRC_APP" && -n "${MESEN_APP_SRC:-}" ]]; then
  SRC_APP="${MESEN_APP_SRC}"
fi

if [[ -n "${MESEN_APP_DEST:-}" ]]; then
  DEST_DIR="${MESEN_APP_DEST}"
fi

if [[ -n "${MESEN_APP_NAME:-}" ]]; then
  DEST_NAME="${MESEN_APP_NAME}"
fi

if [[ -z "$CORE_SRC" && -n "${MESEN_CORE_SRC:-}" ]]; then
  CORE_SRC="${MESEN_CORE_SRC}"
fi

if [[ "$DEST_DIR" == *.app ]]; then
  DEST_NAME="$(basename "$DEST_DIR")"
  DEST_DIR="$(dirname "$DEST_DIR")"
fi

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
CORE_DEST="${DEST_APP}/Contents/MacOS/MesenCore.dylib"
DEFAULT_CORE_SRC="${ROOT_DIR}/InteropDLL/obj.osx-arm64/MesenCore.dylib"
if [[ -z "$CORE_SRC" ]]; then
  CORE_SRC="$DEFAULT_CORE_SRC"
fi

NEED_PRIV=0
if [[ -d "$DEST_DIR" ]]; then
  if [[ ! -w "$DEST_DIR" ]]; then
    NEED_PRIV=1
  fi
else
  PARENT_DIR="$(dirname "$DEST_DIR")"
  if [[ ! -w "$PARENT_DIR" ]]; then
    NEED_PRIV=1
  fi
fi

if [[ -d "$DEST_APP" && ! -w "$DEST_APP" ]]; then
  NEED_PRIV=1
fi

SUDO=""
if [[ "$NEED_PRIV" -eq 1 ]]; then
  if [[ -x "$AGENT_ROOT" ]]; then
    SUDO="sudo $AGENT_ROOT"
    if [[ "$DRY_RUN" -eq 0 ]]; then
      echo "Using agent-root for privileged operations."
      echo "If this fails, run: ws root grant --local --ttl 30m --allow \"ditto,mkdir,mv,rm\""
    fi
  else
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
  local backup_root="$2"
  if [[ ! -d "$path" ]]; then
    return 0
  fi
  if [[ "$DO_BACKUP" -eq 1 ]]; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
      echo "Would backup: $path -> $backup_root/"
    else
      $SUDO mkdir -p "$backup_root"
      $SUDO mv "$path" "$backup_root/"
    fi
  else
    if [[ "$DRY_RUN" -eq 1 ]]; then
      echo "Would remove: $path"
    else
      $SUDO rm -rf "$path"
    fi
  fi
}

prune_dir() {
  local dir="$1"
  local backup_root="$2"
  for name in "Mesen.app" "Mesen2.app" "Mesen2 OOS.app"; do
    prune_path="${dir}/${name}"
    if [[ "$prune_path" != "$DEST_APP" ]]; then
      backup_or_remove "$prune_path" "$backup_root"
    fi
  done
}

if [[ "$DO_PRUNE" -eq 1 ]]; then
  if [[ "$PRUNE_ALL" -eq 1 ]]; then
    for dir in "/Applications" "$HOME/Applications"; do
      if [[ -d "$dir" ]]; then
        prune_backup="${dir}/Mesen2-Backups/${timestamp}"
        prune_dir "$dir" "$prune_backup"
      fi
    done
  else
    prune_backup="${DEST_DIR}/Mesen2-Backups/${timestamp}"
    prune_dir "$DEST_DIR" "$prune_backup"
  fi
fi

backup_or_remove "$DEST_APP" "$backup_dir"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "Would install: $SRC_APP -> $DEST_APP"
else
  $SUDO /usr/bin/ditto "$SRC_APP" "$DEST_APP"
  echo "Installed: $DEST_APP"
fi

if [[ "$DEPLOY_CORE" -eq 1 ]]; then
  if [[ -f "$CORE_SRC" ]]; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
      echo "Would deploy core: $CORE_SRC -> $CORE_DEST"
    else
      $SUDO cp "$CORE_SRC" "$CORE_DEST"
      echo "Deployed core: $CORE_DEST"
    fi
  else
    echo "Warning: core library not found: $CORE_SRC" >&2
    echo "         Run 'make' to build MesenCore.dylib or pass --no-core." >&2
  fi
fi

if [[ "$DO_SYMLINK" -eq 1 ]]; then
  link_path="${DEST_DIR}/Mesen.app"
  if [[ -e "$link_path" && ! -L "$link_path" && "$SYMLINK_FORCE" -eq 0 ]]; then
    echo "Symlink target exists and is not a symlink: $link_path" >&2
    echo "Re-run with --symlink-force to replace it." >&2
    exit 1
  fi
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "Would symlink: $link_path -> $DEST_APP"
  else
    if [[ -e "$link_path" && "$SYMLINK_FORCE" -eq 1 ]]; then
      $SUDO rm -rf "$link_path"
    fi
    $SUDO ln -sfn "$DEST_APP" "$link_path"
    echo "Symlinked: $link_path -> $DEST_APP"
  fi
fi
