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

if [[ "${DO_INSTALL}" -eq 1 ]]; then
  "${ROOT_DIR}/tools/install_mesen2_oos.sh" "${INSTALL_ARGS[@]}"
fi
