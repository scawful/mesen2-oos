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

## Item build profile

Pass `--item-profile default` when both `ENABLE_RING_SRAM_RELOCATE` and
`ENABLE_PORTAL_ROD_CELL` are enabled, or `--item-profile legacy` when both
are disabled; `default` also assumes `ENABLE_GOLDSTAR_CELL = 1` and `legacy`
assumes it is 0. The client refuses `rings`, `portalrod`, `customrods`, `goldstar`,
and `goldstar_inventory_version` access without a profile; listing all items, deep
diagnostics, and profile capture include them. Mixed flag combinations are not
supported. The legacy profile has no Goldstar cell (Hookshot level 2 holds both),
so it refuses both Goldstar items. In the default profile, `goldstar` writes need
`goldstar_inventory_version` 0xA5 (already in memory or set in the same profile);
otherwise the menu migration would overwrite them. Reading `goldstar` on a save
that is not migrated yet is refused for the same reason. Listing all items reports
those cases as `value: null` with the reason, and profile capture leaves them out.
A profile with `goldstar_inventory_version` but no `goldstar` is refused, and capture
never writes one. If any profile write fails, the client writes the earlier targets
back to their values from before the apply. Before applying a profile, attach and
pause the debugger; the client refuses writes unless both are confirmed and does
not pause or resume the session itself. A Goldstar dry run without a version also
needs the paused debugger because it reads the migration marker. This selection does not identify or
authenticate the loaded ROM; select the flags used to build it. Legacy Portal
Rod access preserves the Fishing Rod when clearing Portal Rod ownership. In
the default profile, opening the menu can migrate `customrods` level 2 back
into Portal Rod ownership; clearing only `portalrod` is therefore not durable
while that legacy level remains set.

```sh
python3 mesen2_client.py --socket /tmp/mesen2-owned.sock --item-profile legacy items portalrod
```

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
