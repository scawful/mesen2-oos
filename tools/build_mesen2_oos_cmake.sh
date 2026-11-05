#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build_mesen2_oos_cmake.sh [options]

Builds MesenCore via CMake and publishes the macOS UI bundle.

Options:
  -j, --jobs N      Parallel build jobs (default: 8)
  --install         Install app bundle after publish
  --install-args .. Pass-through args to install_mesen2_oos.sh
  -h, --help        Show this help
EOF
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build-cmake"
JOBS=8
DO_INSTALL=0
INSTALL_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -j|--jobs)
      JOBS="$2"
      shift 2
      ;;
    --install)
      DO_INSTALL=1
      shift
      ;;
    --install-args)
      shift
      while [[ $# -gt 0 && "$1" != --* ]]; do
        INSTALL_ARGS+=("$1")
        shift
      done
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

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script is intended for macOS (Darwin)." >&2
  exit 1
fi

ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
  RID="osx-arm64"
elif [[ "$ARCH" == "x86_64" ]]; then
  RID="osx-x64"
else
  echo "Unsupported architecture: ${ARCH}" >&2
  exit 1
fi

SDKROOT="$(xcrun --show-sdk-path)"
export SDKROOT

cmake -S "${ROOT_DIR}" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release
cmake --build "${BUILD_DIR}" --target MesenCore -j "${JOBS}"

CORE_LIB="${BUILD_DIR}/lib/${RID}/libMesenCore.dylib"
OUT_DIR="${ROOT_DIR}/bin/${RID}/Release"
mkdir -p "${OUT_DIR}"
# Sign once before embedding/copying; deep app signing otherwise replaces the
# linker's temporary signature and makes the two packaged cores differ.
codesign --force --sign - --identifier ca.mesen.oos.core "$CORE_LIB"
cp "${CORE_LIB}" "${OUT_DIR}/MesenCore.dylib"

pushd "${ROOT_DIR}/UI" >/dev/null
DOTNET_CLI_DISABLE_BUILD_SERVER=1 dotnet publish -c Release \
  -p:OptimizeUi="true" -p:UseSharedCompilation=false \
  -t:BundleApp -p:UseAppHost=true -p:RuntimeIdentifier="${RID}" \
  -p:SelfContained=true -p:PublishSingleFile=false -p:PublishReadyToRun=false
DOTNET_CLI_DISABLE_BUILD_SERVER=1 dotnet publish -c Release \
  -p:OptimizeUi="true" -p:UseSharedCompilation=false \
  -t:BundleApp -p:UseAppHost=true -p:RuntimeIdentifier="${RID}" \
  -p:SelfContained=true -p:PublishSingleFile=false -p:PublishReadyToRun=false
popd >/dev/null

APP="$OUT_DIR/$RID/publish/Mesen2 OOS.app"
# BundleApp does not copy this separately built native library into the app.
# Leaving a previous copy here was enough to run a stale shutdown implementation.
cp "$CORE_LIB" "$OUT_DIR/$RID/publish/MesenCore.dylib"
cp "$CORE_LIB" "$APP/Contents/MacOS/MesenCore.dylib"

# Publishing the UI alone can retain an older core. Bind this app and its
# embedded dependencies to the native library built by this invocation.
python3 - "$ROOT_DIR" "$CORE_LIB" "$OUT_DIR" "$RID" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

root, core, output = map(Path, sys.argv[1:4])
app = output / sys.argv[4] / "publish/Mesen2 OOS.app"
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
expected = sha(core)
for path in (output / "MesenCore.dylib", app / "Contents/MacOS/MesenCore.dylib"):
    if sha(path) != expected:
        raise SystemExit(f"Published core differs from fresh build: {path}")
with zipfile.ZipFile(root / "UI/Dependencies.zip") as dependencies:
    embedded = hashlib.sha256(dependencies.read("MesenCore.dylib")).hexdigest()
if embedded != expected:
    raise SystemExit("Embedded dependency core differs from fresh build")
manifest = {
    "source_commit": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
    "source_dirty": bool(subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True)),
    "core_sha256": expected,
    "managed_sha256": sha(app / "Contents/MacOS/Mesen.dll"),
}
path = app / "Contents/Resources/oos-build.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Verified fresh native core and embedded dependencies: {expected}")
PY
codesign --force --deep --sign - --preserve-metadata=identifier,flags,runtime "$APP"
# Signing must not replace the core that the dependency archive contains.
cmp "$CORE_LIB" "$APP/Contents/MacOS/MesenCore.dylib"

if [[ "${DO_INSTALL}" -eq 1 ]]; then
  "${ROOT_DIR}/tools/install_mesen2_oos.sh" "${INSTALL_ARGS[@]}"
fi
