# Gameplay scenes and native capture foundation

Status: initial source scaffold, 2026-10-30. Native runtime acceptance pending.
Owner: CODEX, coordination task `task_20261030T064617Z_28753`.
Consumer: yaze scene authoring and Mesen2-OOS client; adoption is deferred until
the exact candidate emulator passes the owned native episode below.

## Repository ownership

| Project | Owns |
| --- | --- |
| yaze | Scene/map identity, ROM-derived geometry, fixture catalogs, authoring UI and test scenario requests. Reuse `zelda3/cutscene/cutscene_shot.h` geometry and camera panel. |
| mesen2-oos | Native video/audio recording, explicit socket control, bounded execution, scene observation and fixture execution in the existing `tools/oos_client` package. |
| oracle-of-secrets | Only game-specific opt-in ASM loader/camera hooks and minimal configuration. No new Python drivers. |

`tools/oos_client` is currently untracked in the shared Mesen checkout. The
isolated candidate copies that existing package unchanged before adding the new
module/tests and CLI changes. Adoption must reconcile it with its current owner;
do not replace the shared package wholesale. Set `OOS_ROOT` explicitly when
running from a worktree, since the default sibling path differs there.

## Implemented first slice

`mesen2_client.py scene-plan catalog.json --map-id 0xA1 --preset basic`
resolves one exact, hash-verified fixture. This command is offline. IDs are never
masked with `& 0x3f` or `& 0x7f`; a missing expanded/tail-map fixture fails.
`mesen.scene-catalog.v1` lives in yaze's `docs/internal/testing/schemas/`.

Example catalog entry (replace hashes and fixture with real private artifacts):

```json
{
  "schema": "mesen.scene-catalog.v1",
  "scenes": [{
    "kind": "overworld", "map_id": 161, "preset": "basic",
    "fixture": "fixtures/a1-basic.mss",
    "fixture_sha256": "REPLACE_WITH_FIXTURE_SHA256",
    "rom_sha256": "REPLACE_WITH_CURRENT_ROM_SHA256",
    "expected": {
      "module": 9, "submodule": 0, "indoors": false, "area": 161,
      "link_x": 100, "link_y": 100, "scroll_x": 0, "scroll_y": 0
    },
    "checks": [{"name": "health", "address": 8319853, "value": 24}]
  }]
}
```

The coordinates above illustrate the shape, not a known-good spawn on map A1.
Capture fixtures through normal gameplay/loader transitions on the exact ROM.
List all meaningful gear, maximum/current health, progression and scene flags in
`checks`; the preset label alone supplies nothing. The executor reads them back
and refuses mismatches. It does not write arbitrary gear/flags into a live scene.
Preserve fixture SHA-256 and ROM SHA-256 beside each scene.

The `prepare_scene` library entry point accepts a plan, exact ROM path, explicit
endpoint, a live `authorize(endpoint)` lease callback, and a new private rollback
path. It requires a paused instance and no active recording. It checks loaded
ROM SHA-1 against the supplied ROM bytes and catalog SHA-256, saves rollback,
loads the fixture, then verifies camera/player/map/preset postconditions.
Failures return the first phase/error and attempt rollback only while the lease
is still owned. The caller must retain the returned receipt and rollback file.
The callback integrates the existing heavy-lock/registry owner; it is not a new
lease implementation. It must verify token, PID/start time and endpoint binding.

```python
plan = plan_scene(catalog, map_id=0xA1, preset="basic")
result = prepare_scene(client, plan, exact_rom, endpoint=owned_socket,
                       authorize=check_live_native_lease,
                       rollback_path=private_episode / "rollback.mss")
```

The returned Link viewport check is geometric, using BG2 `$E2/$E8`. It cannot
prove Link is visible through roofs, doorway occlusion or blinking. A rendered
image review remains required. Dungeon fixtures, room/quadrant/collision-layer
semantics, dynamic map loading and preset application are not implemented here.

`advance_exact(bridge, 1..600)` sends native `FRAME` and checks the observed
paused frame delta. It rejects noop acknowledgements, overshoot and timeouts.
It does not use `run_frames`, which currently approximates frames with sleeps.
Its native semantics remain unverified; a caller must stop/pause its owned
episode on any exception and retain the failure rather than falling back.
Future controller actions must set input overrides for a bounded frame count,
use this verified stepping path and clear only their own overrides in cleanup.

## Native audiovisual socket protocol

Capability: `native_video_record_v1`.

```sh
python3 tools/oos_client/mesen2_client.py --socket /tmp/OWNED.sock \
  video-record start --token EPISODE_TOKEN --path /absolute/private/take.avi
python3 tools/oos_client/mesen2_client.py --socket /tmp/OWNED.sock \
  video-record status --token EPISODE_TOKEN
python3 tools/oos_client/mesen2_client.py --socket /tmp/OWNED.sock \
  video-record stop --token EPISODE_TOKEN
```

Acquire the existing native lease first. The recording token protects a take
from another recording client; it does not authorize a native session. Never
target `oos-rc-play`. Start and stop require a paused owned instance. Advance or
resume only inside the accepted episode bounds, then pause before stopping.
The server rejects existing output paths, duplicate starts and a wrong stop
token. Keep the token after connection loss; query status before retrying an
uncertain start. An internally failed recording still requires owner cleanup.
The command does not launch, stop or select emulator instances.

The recorder uses ZMBV AVI, emulator FPS and mixer audio through existing
`VideoRenderer::ProcessAviRecording` / `AddRecordingSound`. It does not poll
screenshots, synthesize repeated frames or require a visible renderer. The
mixer bypasses background-mute attenuation while recording. Master mute/volume
and source silence still matter. This source path does not establish actual
encoded cadence, correct audio, duration alignment or installed-binary parity.
Every status result deliberately reports `media_verified: false`.

Native acceptance: build the exact candidate with existing approved audio fixes
preserved, hash core/UI/ROM and fixture, acquire an owned headless lease and run
one bounded 120-frame animated scene with a known audible sound. Retain start,
stop, capabilities and exact-frame receipts. Audit the actual AVI with:

```sh
ffprobe -v error -count_frames -show_streams -show_format -of json take.avi
```

Require the native NTSC frame rate (approximately 60.10 Hz, not forced 60.00),
the expected frame count, monotonic frame timestamps, an audio stream with
nonzero decoded signal, and aligned durations within the measured boundary
budget. Review animation and audible output. Metadata alone cannot establish
that frames were not duplicated. Check recorder shutdown queue flushing and
the first-frame audio boundary; existing native recorder behavior is reused,
not newly proven. Preserve the first failure; no automatic native retries.

## Next bounded implementation stages

1. Complete the native recording episode above, fix observed cadence/audio or
   queue-drain failures, and publish binary parity only after verification.
2. Add a small catalog of real current-ROM fixtures and wire yaze's scene picker
   to `scene-plan` and the existing owned execution service. Do not put process
   launch/discovery in a new editor widget. Attest runtime binary/state ABI in
   the existing health/registry protocol before accepting old fixtures.
3. Extend the existing acknowledged Oracle debug warp dispatcher for requested
   loader semantics. `Util/item_cheat.asm` has `!DEBUG_WARP = 0` by default and
   rejects dungeon/cross-world requests. Require normal loader completion plus
   stable postconditions. Map geometry comes from ROM metadata; special and
   expanded maps must not borrow vanilla parent-grid assumptions.
4. Implement cinematic ASM in Oracle behind a default-off capture flag. Reuse
   yaze shot geometry, but introduce a versioned runtime mailbox only after RAM
   and bank allocation audit. No addresses or hooks are reserved by this doc.

## Cinematic ASM contract to implement

Request: sequence, enable/disable, scene kind and ID, camera start/target,
duration in frames, easing, and map/room bounds. Response: sequence ack,
supported ABI/version, active/completed/error, actual camera and reason.
Arm last; refuse stale sequences, transitions, unsupported scene kinds and
overlapping requests. Clamp to the loaded map/quadrant's verified bounds.

Separate Link-follow suppression from world execution. Hook the camera producer,
not arbitrary PPU scroll mirrors. Keep BG1/BG2 relationships, scroll caches,
tilemap streaming, parallax and sprite/OAM projection coherent. A dungeon pan
must account for room quadrants, layer and camera locks; an overworld pan must
respect parent/large/wide/tall/tail-map layout. Do not pan into unloaded scenery.
Animation, sprites, palettes and audio must keep running during a shot.

Restore prior camera-follow state on disable, scene transition, timeout and
debugger disconnect watchdog. Preserve processor flags, register widths, DBR,
DP and displaced instructions at audited hooks. Keep routine size assertions
and no overlap with existing debug warp allocation at `$3CB400`. Verify the
private build, compiled byte differences, overlap checks and normal gameplay
control before enabling any hook. Verify an animated overworld and dungeon
shot separately. No cinematic ASM has been added or verified in this slice.

## Verification scope

17 stdlib unit tests exercise fixture selection, ROM/fixture mismatch refusal,
camera/preset/viewport rollback, lease refusal/loss, exact frame delta/noop and
recording capability/token protocol. These use an offline bridge, not Mesen.
`SocketServer.cpp` passes Clang C++17 syntax checking. CLI help and offline scene
planning run through the real client entry point. No full emulator build,
installation, native capture, ROM build, ASM changes, user-save write or live
session interaction occurred. The shared SPC/headless changes are untouched.
