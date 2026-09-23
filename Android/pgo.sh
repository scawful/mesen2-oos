#!/usr/bin/env bash
# Android instrumented PGO for Mesen2-OOS on RG353P.
#
# Usage:
#   Android/pgo.sh generate          # instrumented APK
#   Android/pgo.sh train             # install + matched workload + pull profraw
#   Android/pgo.sh optimize          # merge + PGO-optimized APK
#   Android/pgo.sh all               # generate → train → optimize
#   Android/pgo.sh bench             # Smooth / Full / FF matrix on installed APK
#
# Env:
#   ANDROID_SERIAL   device serial (default: first adb device)
#   PGO_SMOOTH_SECS  smooth-play training seconds (default: 40)
#   PGO_FF_SECS      fast-forward training seconds (default: 25)
#   PGO_FULL_SECS    full-render training seconds (default: 20)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="${ROOT}/Android"
PGO_DIR="${ANDROID_DIR}/pgo-data"
APK="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
PROFRAW="${PGO_DIR}/mesen_pgo.profraw"
PROFDATA="${PGO_DIR}/mesen_pgo.profdata"
DEVICE="${ANDROID_SERIAL:-}"
SMOOTH_SECS="${PGO_SMOOTH_SECS:-40}"
FF_SECS="${PGO_FF_SECS:-25}"
FULL_SECS="${PGO_FULL_SECS:-20}"
ROM="/storage/emulated/0/Android/data/ca.mesen.oos/files/oos168x.sfc"
OOS_ROOT="${ROOT}/../oracle-of-secrets"
# Prefer a real overworld training state (hot PPU/CPU paths). Fall back to the
# older matched baseline if the train state is missing on-device.
BASELINE="/data/data/ca.mesen.oos/files/SaveStates/pgo-train-outdoors.mss"
BASELINE_FALLBACK="/storage/emulated/0/Android/data/ca.mesen.oos/files/perf-baseline-20260916.mss"
TRAIN_STATE_HOST="${OOS_ROOT}/Roms/TestBuilds/part00-weather-2026-09-14/checkpoints/outdoors.mss"
# Handheld play/bench ROM: today's main build (includes tree fix). Prefer a fresh
# boot or in-game SRAM over pre-fix Mesen checkpoints (VRAM can show old tiles).
MATCHED_ROM_SHA1="2671E543D83A96AD73F1D7D3CCB55580593DCA14"
MATCHED_ROM_HOST="${OOS_ROOT}/Roms/oos168x.sfc"
# Allowed alternate with identical tree tile16 data (Sep14 rain-fix + 16-byte tree patch).
TREEFIX_ROM_SHA1="B4F48B2133E0C43667370A3E4235B12844C4ED7A"

die() { echo "error: $*" >&2; exit 1; }

ensure_device() {
  if [[ -z "${DEVICE}" ]]; then
    DEVICE="$(adb devices | awk '/\tdevice$/{print $1; exit}')"
  fi
  [[ -n "${DEVICE}" ]] || die "no adb device; set ANDROID_SERIAL"
  export ANDROID_SERIAL="${DEVICE}"
  echo "device: ${DEVICE}"
}

ndk_llvm_bin() {
  local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/opt/homebrew/share/android-ndk}}"
  local bin
  bin="$(ls -d "${ndk}"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1 || true)"
  [[ -n "${bin}" && -x "${bin}/llvm-profdata" ]] || die "llvm-profdata not found under ${ndk}"
  echo "${bin}"
}

build_mode() {
  local mode="$1"
  mkdir -p "${PGO_DIR}"
  case "${mode}" in
    generate)
      echo "building instrumented APK..."
      (cd "${ANDROID_DIR}" && YAZE_ANDROID_PGO=generate ./build.sh)
      ;;
    optimize)
      [[ -f "${PROFDATA}" ]] || die "missing ${PROFDATA}; run train first"
      echo "building PGO-optimized APK with ${PROFDATA}..."
      (cd "${ANDROID_DIR}" && YAZE_ANDROID_PGO=optimize YAZE_PGO_PROFDATA="${PROFDATA}" ./build.sh)
      ;;
    *)
      die "unknown build mode: ${mode}"
      ;;
  esac
  [[ -f "${APK}" ]] || die "APK missing: ${APK}"
  sha256sum "${APK}"
}

install_apk() {
  ensure_device
  [[ -f "${APK}" ]] || die "APK missing: ${APK}"
  echo "install: ${APK}"
  # -d allows replacing a higher shipping versionCode with generate when needed.
  if ! adb -s "${DEVICE}" install -r -d "${APK}"; then
    die "adb install failed"
  fi
  if [[ -x "${OOS_ROOT}/Scripts/Device/oos_rg353p.sh" ]]; then
    (cd "${OOS_ROOT}" && Scripts/Device/oos_rg353p.sh mesen-launch --keep-hud) || true
  else
    adb -s "${DEVICE}" shell am start -n ca.mesen.oos/.MesenActivity \
      --es rom "${ROM}" --ez keep_hud true --ez smooth_play true --ez gpu_blit true
  fi
  sleep 4
  adb -s "${DEVICE}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "${DEVICE}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  adb -s "${DEVICE}" shell dumpsys window | rg -q 'mCurrentFocus=.*NotificationShade' \
    && adb -s "${DEVICE}" shell input swipe 320 50 320 600 100 || true
  adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
}

ensure_train_state() {
  if adb -s "${DEVICE}" shell run-as ca.mesen.oos ls files/SaveStates/pgo-train-outdoors.mss >/dev/null 2>&1; then
    return 0
  fi
  [[ -f "${TRAIN_STATE_HOST}" ]] || die "missing train state ${TRAIN_STATE_HOST}"
  echo "pushing train state..."
  adb -s "${DEVICE}" push "${TRAIN_STATE_HOST}" /data/local/tmp/pgo-train-outdoors.mss >/dev/null
  adb -s "${DEVICE}" shell run-as ca.mesen.oos cp /data/local/tmp/pgo-train-outdoors.mss files/SaveStates/pgo-train-outdoors.mss
}

load_baseline() {
  if ! python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" load "${BASELINE}" >/dev/null 2>&1; then
    echo "warn: ${BASELINE} failed; trying fallback" >&2
    python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" load "${BASELINE_FALLBACK}" >/dev/null
  fi
}

forward_socket() {
  adb -s "${DEVICE}" forward --remove tcp:27015 >/dev/null 2>&1 || true
  adb -s "${DEVICE}" forward tcp:27015 tcp:27015 >/dev/null
  export MESEN2_SOCKET_PATH=tcp://127.0.0.1:27015
}

wait_socket() {
  local tries="${1:-20}"
  local i
  for ((i = 1; i <= tries; i++)); do
    if python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" run-state --json >/dev/null 2>&1; then
      adb -s "${DEVICE}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
      adb -s "${DEVICE}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
      adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
      return 0
    fi
    sleep 1
  done
  die "Mesen2 socket not ready after ${tries}s"
}

ensure_matched_rom() {
  local sha
  sha="$(python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" rom-info --json | jq -r '.sha1')"
  if [[ "${sha}" == "${MATCHED_ROM_SHA1}" || "${sha}" == "${TREEFIX_ROM_SHA1}" ]]; then
    echo "rom ok: sha1=${sha}"
    return 0
  fi
  [[ -f "${MATCHED_ROM_HOST}" ]] || die "matched main ROM missing: ${MATCHED_ROM_HOST}"
  echo "rom drift (got sha1=${sha}); pushing today's main build..."
  if [[ -x "${OOS_ROOT}/Scripts/Device/oos_rg353p.sh" ]]; then
    (cd "${OOS_ROOT}" && Scripts/Device/oos_rg353p.sh push --rom "${MATCHED_ROM_HOST}" --mesen) || die "main ROM push failed"
  else
    die "matched main ROM required (got sha1=${sha})"
  fi
  wait_socket 30
  sha="$(python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" rom-info --json | jq -r '.sha1')"
  [[ "${sha}" == "${MATCHED_ROM_SHA1}" ]] || die "main ROM push did not stick (got sha1=${sha})"
}

ensure_gpu_presents() {
  local tries="${1:-25}"
  local i presents
  for ((i = 1; i <= tries; i++)); do
    adb -s "${DEVICE}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb -s "${DEVICE}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
    local w
    w="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json 2>/dev/null || true)"
    if [[ -n "${w}" ]]; then
      local accel driver
      accel="$(echo "${w}" | jq -r '.renderAccelerated // false')"
      driver="$(echo "${w}" | jq -r '.renderDriver // "none"')"
      presents="$(echo "${w}" | jq -r '.renderPresents // 0')"
      if [[ "${accel}" == "true" && "${driver}" != "none" && "${presents}" -gt 0 ]]; then
        echo "gpu ready: driver=${driver} presents=${presents}"
        return 0
      fi
    fi
    sleep 1
  done
  die "GPU presents not active after ${tries}s (need unlock + surface bind)"
}

run_train_segment() {
  local label="$1"
  local speed="$2"
  local smooth="$3"
  local seconds="$4"
  echo "train ${label}: speed=${speed} smooth=${smooth} ${seconds}s"
  adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  adb -s "${DEVICE}" shell am start -n ca.mesen.oos/.MesenActivity \
    --es rom "${ROM}" --ez keep_hud true --ez smooth_play "${smooth}" --ez gpu_blit true >/dev/null
  sleep 1
  wait_socket 25
  python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" pause >/dev/null
  load_baseline
  python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" speed "${speed}" >/dev/null
  python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" resume >/dev/null
  sleep "${seconds}"
  python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" speed 1 >/dev/null
}

dump_and_pull() {
  echo "dumping profile..."
  # Explicit JNI dump via registered BroadcastReceiver, then HOME onPause.
  adb -s "${DEVICE}" shell am broadcast -a ca.mesen.oos.DUMP_PGO -n ca.mesen.oos/.MesenActivity >/dev/null 2>&1 \
    || adb -s "${DEVICE}" shell am broadcast -a ca.mesen.oos.DUMP_PGO >/dev/null 2>&1 \
    || true
  adb -s "${DEVICE}" shell input keyevent KEYCODE_HOME
  sleep 2
  adb -s "${DEVICE}" shell am start -n ca.mesen.oos/.MesenActivity \
    --es rom "${ROM}" --ez keep_hud true >/dev/null || true
  sleep 1
  adb -s "${DEVICE}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  adb -s "${DEVICE}" shell input keyevent KEYCODE_HOME
  sleep 2

  mkdir -p "${PGO_DIR}"
  rm -f "${PROFRAW}"
  if ! adb -s "${DEVICE}" shell run-as ca.mesen.oos ls files/mesen_pgo.profraw >/dev/null 2>&1; then
    # Fall back to any instrumentation artifact under files/
    local remote
    remote="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos sh -c 'ls files/*.profraw 2>/dev/null | head -1' | tr -d '\r')"
    [[ -n "${remote}" ]] || die "no .profraw under app files/; was this an instrumented APK?"
    adb -s "${DEVICE}" exec-out run-as ca.mesen.oos cat "${remote}" > "${PROFRAW}"
  else
    adb -s "${DEVICE}" exec-out run-as ca.mesen.oos cat files/mesen_pgo.profraw > "${PROFRAW}"
  fi
  local size
  size="$(wc -c < "${PROFRAW}" | tr -d ' ')"
  [[ "${size}" -gt 1000 ]] || die "profraw too small (${size} bytes)"
  echo "pulled ${PROFRAW} (${size} bytes)"

  local llvm_bin
  llvm_bin="$(ndk_llvm_bin)"
  "${llvm_bin}/llvm-profdata" merge -output="${PROFDATA}" "${PROFRAW}"
  echo "merged ${PROFDATA}"
  ls -la "${PROFRAW}" "${PROFDATA}"
}

train() {
  ensure_device
  [[ -d "${OOS_ROOT}/Scripts/Mesen2" ]] || die "oracle-of-secrets scripts missing at ${OOS_ROOT}"
  install_apk
  ensure_train_state
  forward_socket
  wait_socket 30
  ensure_matched_rom
  ensure_gpu_presents 30
  run_train_segment SMOOTH 1 true "${SMOOTH_SECS}"
  run_train_segment FULL 1 false "${FULL_SECS}"
  run_train_segment FFMAX 0 true "${FF_SECS}"
  dump_and_pull
}

bench() {
  ensure_device
  forward_socket
  measure() {
    local label="$1" speed="$2" smooth="$3"
    # Prefer prefs-only relaunch so an outdoor session is not wiped by loadRom().
    adb -s "${DEVICE}" shell am start -n ca.mesen.oos/.MesenActivity \
      --ez keep_hud true --ez smooth_play "${smooth}" --ez gpu_blit true >/dev/null
    sleep 1
    python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" pause >/dev/null
    load_baseline
    python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" speed "${speed}" >/dev/null
    python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" resume >/dev/null
    sleep 2
    local f1 r1 p1 t0 f2 r2 p2 t1
    f1="$(python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" run-state --json | jq -r '.frame')"
    r1="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json | jq -r '.renderNewFrames')"
    p1="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json | jq -r '.renderPresents')"
    t0="$(python3 -c 'import time; print(time.time())')"
    sleep 10
    f2="$(python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" run-state --json | jq -r '.frame')"
    r2="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json | jq -r '.renderNewFrames')"
    p2="$(adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json | jq -r '.renderPresents')"
    t1="$(python3 -c 'import time; print(time.time())')"
    python3 - <<PY
dt=float("${t1}")-float("${t0}"); df=int("${f2}")-int("${f1}"); dr=int("${r2}")-int("${r1}"); dp=int("${p2}")-int("${p1}")
print(f"${label}: core={df/dt:.2f} new={dr/dt:.2f} present={dp/dt:.2f} mult={df/dt/60.1:.2f}x")
PY
    python3 "${OOS_ROOT}/Scripts/Mesen2/mesen2_client.py" speed 1 >/dev/null
  }
  measure SMOOTH 1 true
  measure FULL 1 false
  measure FFMAX 0 true
  adb -s "${DEVICE}" shell run-as ca.mesen.oos cat files/workshop.json \
    | jq '{renderDriver,renderAccelerated,renderErrors,observedFps,performanceMode,sha1}'
}

cmd="${1:-}"
case "${cmd}" in
  generate) build_mode generate ;;
  train) train ;;
  optimize) build_mode optimize ;;
  all)
    build_mode generate
    train
    build_mode optimize
    echo "PGO APK ready: ${APK}"
    ;;
  bench) bench ;;
  install)
    install_apk
    ;;
  *)
    cat <<EOF
usage: $0 {generate|train|optimize|all|bench|install}
EOF
    exit 2
    ;;
esac
