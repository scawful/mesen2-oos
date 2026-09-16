#!/usr/bin/env bash
# Build the Mesen2-OOS Android SDL host APK (arm64-v8a).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="${ROOT}/Android"
SDL_JAVA_SRC="${ROOT}/../yaze/ext/SDL/android-project/app/src/main/java/org/libsdl/app"
SDL_JAVA_DST="${ANDROID_DIR}/app/src/main/java/org/libsdl/app"
WRAPPER_SRC="${ROOT}/../yaze/ext/SDL/android-project"

die() { echo "error: $*" >&2; exit 1; }

if [[ ! -d "${SDL_JAVA_SRC}" ]]; then
  die "SDL Java sources missing at ${SDL_JAVA_SRC}"
fi

mkdir -p "${SDL_JAVA_DST}"
rsync -a --delete "${SDL_JAVA_SRC}/" "${SDL_JAVA_DST}/"
python3 "${ANDROID_DIR}/patches/patch_sdl_java.py"

if [[ ! -x "${ANDROID_DIR}/gradlew" ]]; then
  cp "${WRAPPER_SRC}/gradlew" "${ANDROID_DIR}/gradlew"
  chmod +x "${ANDROID_DIR}/gradlew"
  mkdir -p "${ANDROID_DIR}/gradle/wrapper"
  cp "${WRAPPER_SRC}/gradle/wrapper/gradle-wrapper.jar" "${ANDROID_DIR}/gradle/wrapper/"
  cp "${WRAPPER_SRC}/gradle/wrapper/gradle-wrapper.properties" "${ANDROID_DIR}/gradle/wrapper/"
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${sdk_dir}" ]]; then
  for candidate in \
    "${HOME}/Library/Android/sdk" \
    /opt/homebrew/share/android-commandlinetools \
    /usr/local/share/android-commandlinetools
  do
    if [[ -d "${candidate}/platforms" || -d "${candidate}/cmdline-tools" ]]; then
      sdk_dir="${candidate}"
      break
    fi
  done
fi
[[ -n "${sdk_dir}" ]] || die "Android SDK not found. Set ANDROID_HOME or install: brew install --cask android-commandlinetools"

ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${ndk_dir}" ]]; then
  if [[ -d /opt/homebrew/share/android-ndk ]]; then
    ndk_dir=/opt/homebrew/share/android-ndk
  elif [[ -d /usr/local/share/android-ndk ]]; then
    ndk_dir=/usr/local/share/android-ndk
  elif [[ -d "${sdk_dir}/ndk" ]]; then
    ndk_dir="$(ls -d "${sdk_dir}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
[[ -n "${ndk_dir}" && -d "${ndk_dir}" ]] || die "Android NDK not found. Set ANDROID_NDK_HOME or install: brew install --cask android-ndk"

if [[ -f "${ndk_dir}/source.properties" ]]; then
  ANDROID_NDK_VERSION="$(awk -F' = ' '/Pkg.Revision/ {print $2; exit}' "${ndk_dir}/source.properties" | tr -d '\r')"
  export ANDROID_NDK_VERSION
fi

cat > "${ANDROID_DIR}/local.properties" <<EOF
sdk.dir=${sdk_dir}
ndk.dir=${ndk_dir}
EOF

echo "sdk.dir=${sdk_dir}"
echo "ndk.dir=${ndk_dir}"
echo "ndk.version=${ANDROID_NDK_VERSION:-unknown}"
cd "${ANDROID_DIR}"
./gradlew :app:assembleDebug --quiet
apk="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "${apk}" ]] || die "APK missing after build: ${apk}"
echo "apk: ${apk}"
