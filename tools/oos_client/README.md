# oos_client

Python socket client for Mesen2-OoS, tuned for Oracle of Secrets.
Moved from `oracle-of-secrets/Scripts/Mesen2/` on 2026-09-23.

## Layout

- `mesen2_client.py` — CLI entry point.
- `mesen2_registry.py` — named-instance registry.
- `agent_spectator.py` — passive live bot viewer and event recorder; see [AGENT_SPECTATOR.md](AGENT_SPECTATOR.md).
- `mesen2_client_lib/` — library (bridge, client, CLI, state library, save data, symbols).
- `mesen2_client_lib/tests/` — run `python3 -m pytest mesen2_client_lib/tests`.

## Oracle of Secrets checkout

The client reads RAM symbols, the save-state library, and `.context/` from an Oracle of Secrets checkout.
Default: sibling `../oracle-of-secrets` next to this `mesen2-oos` checkout.
Override with `OOS_ROOT` (or `ORACLE_OF_SECRETS_ROOT`).

## Callers in oracle-of-secrets

`Scripts/Mesen2/mesen2_client.py`, `Scripts/Mesen2/mesen2_registry.py`, and
`Scripts/Mesen2/mesen2_client_lib/__init__.py` are shims that forward here.
Override the mesen2-oos location with `MESEN2_OOS_ROOT`.

## Grounded dungeon captures

Use the existing `capture` command with an explicit owned, paused test instance:

```sh
python3 mesen2_client.py --socket /tmp/mesen2-owned.sock capture \
  --scene-rom /absolute/exact-loaded.sfc --z3ed /absolute/z3ed \
  --target door:2 --report /absolute/door-review.html > /absolute/door-review.json
```

`--target object:49` selects an encoded object by stream index instead. These
identifiers are separate: a prison-cell object is not a door record. The report
uses `z3ed dungeon-describe-room --include-objects` against a hash-matched ROM,
includes catalog name provenance, and projects entity anchors/render bounds
using the captured BG2 scroll. This is a debug capture, not a passive spectator;
it never pauses, resumes, writes memory, or presses buttons itself. An unstable
scene, different ROM, nonexistent target or capture-time state change is refused.

The single-frame result always says traversal is unevaluated. For movement
acceptance, retain controller inputs, room/submodule, position and camera before
and after reaching the selected door. Interior doors can scroll to another
quadrant without changing the room ID. Passing a nearby object or merely moving
closer to a door is not door-traversal evidence. Object catalog names describe
vanilla objects; hacks may reuse their art or behavior. Overlays show anchors and
render bounds, not live collision footprints.

## Scene requests and native video/audio

`scene-plan` resolves an exact hash-verified overworld fixture by map ID and
preset. `video-record` exposes the versioned native AVI/audio recorder on an
explicit owned socket. See [the capture foundation contract](../../docs/Gameplay_Capture_Engine.md)
for supported interfaces, lease requirements, validation scope and pending native acceptance.
