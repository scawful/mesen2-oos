# Oracle Android host

The RG353P Android app runs Mesen with the Workshop overlay. The APK and the game ROM are separate updates; installing an APK does not select a new Oracle ROM or migrate save RAM.

## Installed — 0.1.10-workshop

Version code 11 / APK SHA256 `967e1184063bc9c37075e19d7bf4379ff987bd8ffd140a9f6b9f8d3fb9d503f0` closes direct LAN access to the privileged emulator-control protocol. Android now binds only `127.0.0.1:27015`; Mac tools connect through an explicit USB `adb forward`. Workshop publishes `bind=127.0.0.1` and `transport=adb-forward`, no longer advertises an NSD service or LAN endpoint, and no longer holds Wi-Fi or multicast locks for remote control. The manifest drops the four now-unused network/wake permissions while retaining `INTERNET` for the loopback socket.

The APK also includes the 0.1.9 batched runtime-status snapshot described below. `Android/build.sh` passed; package inspection verified version code 11, arm64-v8a, and the reduced permission set. Seventy-three Oracle handheld host tests pass, including 31 shell-workflow checks for loopback environment generation and explicit `--lan` refusal. On RG353P `26489f9a104f2273`, the installed APK hash matched, `ss` reported only `127.0.0.1:27015`, USB-forwarded health reached the retained Weather ROM (CRC `9109C3A5`), and direct `192.168.1.227:27015` access timed out. The app was left paused at frame 302 with `debugging=false`.

Exact package: `Roms/HandheldApps/oos-android-0.1.10-workshop-20260916-967e1184/`. The preinstall Workshop artifacts were copied to `Roms/device-workshop/workshop-jg9bgwwq/`; device files were not deleted.

## Superseded host candidate — 0.1.9-workshop

Version code 10 / APK SHA256 `5d1db75cffd2a959ec2311a903bad6d40401f036ea6ef5be50198ea76abf819e` consolidates the ROM SHA1, pause/frame state, and five ALTTP work-RAM status bytes into one `HandheldReadRuntimeSnapshot` call. The status JSON schema is unchanged. This replaces eight emulator lifetime-lock acquisitions per 180 ms poll with one and reduces cross-frame skew between the reported fields.

`Android/build.sh` passes, and package inspection confirms arm64-v8a plus the consolidated native export. Host regressions pass with 83 Workshop checks using two real MSS fixtures, 7 frame-rate checks, 8 ROM-discovery checks, and 15 framebuffer tests under AddressSanitizer/UndefinedBehaviorSanitizer.

This intermediate candidate was not installed or device-verified because the RG353P disconnected before deployment. Its code is included in 0.1.10; the performance change still needs a matched 0.1.8/0.1.10 device comparison.

## Workshop diagnostics — installed 0.1.8-workshop

Version code 9 / APK SHA256 `7a29934fc91c2d4914a79eb232900741e5e1f10c45aeb59160d4f3e199f7ecf0` makes `HEALTH` observational. Without an existing debugger it now returns `debugging:false`, `pc:null`, and `disasmOk:false` without calling `GetDebugger(true)`; when a debugger was already active it still includes PC/disassembly details. The installed regression check proves `STATE → HEALTH → STATE` remains debugger-free.

Version code 8 / `0.1.7-workshop` introduced loaded-ROM SHA1 caching but was superseded after a brief install when the `HEALTH` side effect was found. The cache is invalidated after every successful ROM load; dynamic `Sha1Cheat` hashing remains uncached. This prevents the 180 ms Workshop UI poll and one-second socket status poll from repeatedly hashing the full ROM.

In a short paused-Workshop comparison on the same RG353P and ROM, process CPU dropped from an average 41.8% to 33.4% of one core, while the Android UI thread dropped from 20.8% to 11.8%. The final 0.1.8 sample remained in the same reduced range. These `top` samples are directional and noisy, not a full-speed claim. An eight-second 0.1.8 run advanced at 39.93 FPS and Workshop reported 40.51 FPS. Sleep/wake preserved paused frame 636 and the exact framebuffer with zero render errors and `debugging:false`.

Version code 7 / `0.1.6-workshop` added `sprites` beside best-effort `game_state` in checkpoint and bug manifests and in host collections. Its complete on-device checkpoint contains both objects with no warnings. The exact installed 0.1.6 APK is preserved at `Roms/HandheldApps/oos-android-0.1.6-workshop-20260915-bdae1e2d/`, so its exact-APK checkpoint remains restorable after later upgrades.

The running FPS value is derived from frame-count/time deltas; paused Workshop shows the last running value rather than Mesen's nominal `60.1`. Sampling remains active when **Keep HUD** is hidden. `GAMESTATE` and `SPRITES` read SNES work RAM directly, `workshop.json` publication is atomic, and the Oracle collector/backup/launch scripts use Android `run-as` for app-private files. See the [current diagnostics report](../../oracle-of-secrets/Docs/Planning/Status/rg353p_workshop_diagnostics_2026-09-15.md).

## Workshop captures — 0.1.4-workshop history

Version code 5 / APK SHA256 `83df208be901fb7bc6bfb12b232a33514c0756a85951bdab71b91f2919c7bd20` adds optional Name and Note fields on Save checkpoint / Capture bug, and holds a Wi-Fi high-performance lock so the debug socket can stay reachable after Home and sleep/wake. Native libraries remain byte-for-byte equal to `0.1.3-workshop`. D-pad navigation skips the text fields; tap to type. Host checks: 75 artifact tests (two real MSS fixtures) and 11 collect tests.

Prior `0.1.3-workshop` (version code 4 / APK SHA256 `2d2e127cc19c9687af4b736b5460009036e5dddfb7ec629346e923d5582f1a12`) added Save checkpoint, Capture bug categories, compatible checkpoint listing and explicit restore review.

`WorkshopArtifacts.java` runs on a single UI-owned worker and talks only to the existing loopback socket. It requires an already-paused stable game and uses STATE, ROMINFO, SAVESTATE and SCREENSHOT. Explicit restore additionally uses LOADSTATE after verifying exact ROM SHA1/CRC, APK SHA256 and artifact bytes. Unique app-private `files/Workshop/checkpoints/` and `issues/` folders contain `state.mss`, `screenshot.png` and an atomic `manifest.json` using `oos-workshop-artifact-v1`. Partial failures are preserved and reported; legacy states and normal SRAM are not imported.

`WorkshopArtifactFiles.java` validates state framing/compression and PNG structure/checksums. The current `WorkshopArtifactsTest` passes 83 focused checks with two actual MSS fixtures; frame-rate and ROM-discovery suites add 7 and 8 checks. Device checks for 0.1.3 covered distinct saves, duplicate input suppression, categorized capture, explicit paused restore with matching image, and USB collection. The 0.1.6 device check added a complete collected checkpoint containing both `game_state` and `sprites`. The host command is `Scripts/Device/oos_rg353p.sh collect` from Oracle. See the [0.1.4 follow-up](../../oracle-of-secrets/Docs/Planning/Status/rg353p_workshop_labels_2026-09-15.md) and the [0.1.3 feature report](../../oracle-of-secrets/Docs/Planning/Status/rg353p_workshop_features_2026-09-15.md).

## Prior September 15 display/performance update (0.1.2-workshop)

The deployment session installed final candidate F: package `ca.mesen.oos`, version `0.1.2-workshop` / version code `3`. Its APK SHA256 is:

```text
f42a131221fce36a68f54df99bee3ea69ce149b46f49baaf64e3cfa05e1f21f4
```

The verified native host source SHA256 is `a6488b784738646c49961d3342dfc61ffab9f823dd882fc415fe917958e24a1a` for `app/src/main/cpp/mesen_android_main.cpp`. Record APK identity after each rebuild; version text alone does not identify identical bytes.

Final F passed three Home returns and three rapid sleep/wake returns. All six preserved paused emulator frame `4739` and identical game pixels. USB health checks passed while backgrounded and while the screen was off; the captured log contained zero `EGL_BAD_SURFACE` errors. Fifteen native host tests passed with AddressSanitizer/UndefinedBehaviorSanitizer, and eight Java ROM-discovery checks passed independently. These are focused recovery results, not a full playthrough or physical controller/audio acceptance.

An earlier diagnostic build showed that SDL renderer creation could succeed while the Android native window and stored EGL surface were absent, followed by `EGL_BAD_SURFACE` during presentation. A healthy emulator core or unchanged frame count did not prove visible game output. Final F validates the surface binding before drawing and checks it again around resource creation and presentation.

Final F passed a 71.1-second foreground idle check with global stay-on=0 and timeout 60000 ms, preserving paused frame 4739 and identical game pixels. Its final sample measured 35.6% of one core paused and 114.6% running, versus 124.9% and 196.6% originally. Actual frame advance was 37.64 FPS, comparable to 37.63 FPS originally; the CPU reduction does not establish a speed increase. Physical controller feel, audible sound, and sustained full-speed emulation remain unproven. See the [Oracle verification report](../../oracle-of-secrets/Docs/Planning/Status/rg353p_android_update_2026-09-15.md) for exact captures and measurement conditions.

The following CPU comparison belongs to **candidate D**, after visible Home-return recovery; it is not a final F benchmark. CPU percentages use one core as 100%:

| Measurement | Original baseline | Candidate D |
|---|---:|---:|
| Paused process CPU | 124.9% | 33.2% |
| Running process CPU | 196.6% | 113.3% |
| Running emulator frames/second | 37.63 | 37.52 |

The CPU work decreased in that sample, but emulation remained near 37.5 frames/second. The renderer changes do not establish full-speed emulation.

## Workshop and Home

Open Workshop with Start + Select or the Android Back/Mode control. Opening Workshop pauses the emulated game; Continue closes it and resumes. Java now forwards the real focus state to SDL and preserves the inherited pause/stop calls; it no longer forces native startup or resume while losing focus or going into the background. The native event loop/socket remains available with `SDL_HINT_ANDROID_BLOCK_ON_PAUSE=0`, while native drawing is gated separately. Going to Home is not a substitute for pausing the emulated game in Workshop.

With the ROM list closed, D-pad Down follows Continue → Reset console → Choose ROM → Keep HUD → Fill screen → Add to Home. Left/Right moves between Keep HUD and Fill screen. Touch activates the same controls. HUD and scaling preferences persist. The displayed A/B labels follow the handheld mapping; physical button behavior remains an acceptance check.

**Add to Home** uses Android's supported shortcut API when the current launcher supports it. The shortcut targets an explicit `MAIN`/`LAUNCHER` intent without a `rom` extra, so opening it sends an existing Activity no ROM-load request. A cold launch retains the existing last-ROM behavior. “Request sent” is not a successful pin: “Added to Home” appears only after the one-use authenticated callback. Cancellation does not report success.

The RG353P's vendor Quickstep reported in-app shortcut pinning unsupported during this deployment. Use its app drawer: long-press **Oracle of Secrets**, then drag the app icon onto Home. That fallback was completed in the deployment session. No direct `launcher.db` edit or replacement Home launcher is required. A supported launcher's confirmation/callback path remains unverified on hardware.

The chooser now uses [RomFiles.java](app/src/main/java/ca/mesen/oos/RomFiles.java). It keeps distinct ROM paths even when filename and size match, and collapses canonical path aliases without reading ROM contents on the UI thread. Empty-list text and disabled/nonfocusable views are excluded from D-pad navigation. Existing source labels identify app, drop, and SD entries.

## Renderer behavior and diagnostics

[mesen_android_main.cpp](app/src/main/cpp/mesen_android_main.cpp) keeps SDL rendering on its SDL thread and prefers the software renderer. [HandheldFramePresenter.h](app/src/main/cpp/HandheldFramePresenter.h) owns callback pixels and publishes a new generation only when dimensions or pixels change. Repeated identical callbacks, including paused callbacks, do not trigger another staging copy, texture upload, or ordinary presentation.

Window exposure/resize/restore and scaling changes can request a redraw without a new game frame. Texture resets invalidate the upload state. New pixel generations wake the SDL event loop; its event wait also times out after 8 ms so input, mode changes, and surface retries continue to be serviced. This removes the previous continuous redraw of an unchanged framebuffer. It does not prove that the whole emulator now runs at full speed. Rewind defaults were not changed by this update.

Surface recovery requires a foreground, visible window. Drawing requires both a current Android native-window pointer and stored EGL surface, plus a completed rebuild. The first non-GL window may bootstrap the software renderer from a valid native window before its hidden EGL surface exists; creation is accepted only after the resulting binding is complete. Once a surface has been ready, recovery waits for a complete binding instead of treating a non-null renderer as sufficient. A changed window/surface pair invalidates prior resources and requests redraw. Rebuilds destroy the renderer and borrowed SDL window surface before recreating them. Failed creation/presentation or a binding change across those operations retains pending work and retries after 100 ms; a new lifecycle/binding event can trigger an earlier retry. The latest pixels are redrawn after recovery even when the game remains paused.

`MesenNative.statusJson()` exposes six process-lifetime counters, also copied into Workshop's `workshop.json` snapshot:

| Field | Meaning |
|---|---|
| `renderCallbacks` | Software-renderer refresh callbacks received, including repeats. |
| `renderNewFrames` | New pixel/dimension generations published. |
| `renderUploads` | Successful texture uploads. |
| `renderPresents` | Calls made to `SDL_RenderPresent`, including forced redraws and failed attempts. |
| `renderErrors` | Counted renderer creation/binding, texture, draw, and freshly reported presentation failures. |
| `renderSurfaceRebuilds` | Renderer rebuilds accepted with a complete, usable Android surface binding, including initial setup. |

`renderSurfaceDiagnostic` is the latest structured surface diagnostic, initially `null`, not a seventh counter. The same event is written to Android logcat under `MesenSurface`. It includes event/ticks, window visibility/focus flags, renderer name, Android native-window and stored EGL-surface pointers, SDL cached window/context, actual EGL context/draw surface when probing is available, `surfaceReady`, `eglBindingReady`, context/draw-surface match flags, fresh `presentError`, and rebuild/upload/present counts at that event. Pointer values are serialized as strings. Matching null pointers do not establish readiness: `surfaceReady` requires both Android pointers; `eglBindingReady` requires an actual context and draw surface, or is `null` when the probe is unavailable. Repeated error traces are throttled, so the diagnostic is an event snapshot rather than a current health guarantee.

These counters are not emulation FPS or proof of visible output. The separate `frames` field is the emulator frame count. Compare deltas over a measured interval with the same APK/ROM, pause state, and foreground conditions. Workshop manifests and the latest diagnostic are snapshots and may be stale; they do not prove live connectivity. `SDL_RenderPresent` has no success return, so the host captures its fresh SDL error separately and still requires a visible-frame check. An increasing present/rebuild counter alone must not be reported as display recovery.

## Foreground screen policy

[MesenActivity.java](app/src/main/java/ca/mesen/oos/MesenActivity.java) requests `FLAG_KEEP_SCREEN_ON` and sets the visible decor view's `keepScreenOn` property after setup and after the inherited `onResume()`. The view request retains the effective window flag if SDL changes its client window flags asynchronously. This is an app-window policy, not an Android global stay-awake setting; it stops applying when the app window is no longer visible.

The keep-awake helper only changes window/view flags. The separate lifecycle repair now forwards real focus, preserves inherited pause/stop behavior, and limits active-window setup/startup fallback to focus gain. It does not load a ROM or issue an emulated-game resume/reset. The precise caller responsible for the earlier 60-second timeout was not established. Validate this policy with Android's global plugged-in stay-on setting restored to its prior value; a USB connection alone is not proof that the device reports USB power.

## Build and focused checks

Run from the repository root:

```bash
Android/build.sh
```

The script discovers the SDK/NDK (or uses `ANDROID_HOME` / `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`), refreshes SDL Java from sibling `yaze/ext/SDL`, applies `patches/patch_sdl_java.py`, and runs `:app:assembleDebug`. Maintain local SDL Java changes in that patch script, not the refreshed copies.

The output is `Android/app/build/outputs/apk/debug/app-debug.apk`. [app/build.gradle](app/build.gradle) currently targets `arm64-v8a`, compile SDK 34, target SDK 30, and minimum SDK 21. Both C and C++ debug compilation use `-O2 -g`; this includes SDL's C software renderer. C++ uses C++17, and the debug variant remains JNI-debuggable. Record the APK hash after each build; unchanged version text does not imply identical bytes.

The ROM-discovery test can run without an Android device or APK build:

```bash
workshop_test_classes=$(mktemp -d)
javac -d "$workshop_test_classes" \
  Android/app/src/main/java/ca/mesen/oos/RomFiles.java \
  Android/tests/RomFilesTest.java
java -cp "$workshop_test_classes" ca.mesen.oos.RomFilesTest
```

Its eight checks cover distinct same-name/same-size builds, path aliases, ordering, and filtering. The renderer's host tests are in `Android/tests/handheld_frame_presenter_test.cpp`; they exercise frame ownership, unchanged callbacks, staging reuse, and presentation decisions. Host tests do not replace launcher, physical-control, audio, foreground/background, or sustained gameplay acceptance.
