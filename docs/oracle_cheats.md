# Oracle Cheats & Warps

Story checkpoints, entrance/area warps, item toggles and a named-flag editor for
Oracle of Secrets testing. Data lives in the Oracle checkout:
`oracle-of-secrets/Data/debug/{warps,checkpoints}.json` (see its README for the
checkpoint rules, citations and limits).

## Where

| Surface | Code |
|---------|------|
| Oracle tools pane (Cmd/Ctrl+Shift+O): Warp search, Checkpoints, Items, Flags | `UI/Views/OracleToolsPaneView.axaml(.cs)`, `UI/ViewModels/OracleCheatsViewModel.cs` |
| Warp, checkpoint, item/flag logic (in process) | `UI/Utilities/OracleCheats.cs` |
| `Entrance $` box and macro `warp` step | `OracleDebugActions.WarpToEntrance` -> `OracleCheats.WarpToEntrance` |
| Client commands `checkpoint`, `warp-entrance`, `warp-area` | `tools/oracle_cheats/oracle_cheats.py`, installed as `tools/oos_client/mesen2_client_lib/oracle_cheats.py` |
| `warps.json` generator | `tools/oracle_cheats/gen_warps.py` |
| Headless check | `tools/oracle_cheats/test_cheats_headless.py` |

The client is tracked in this consolidated Mac branch. Use the maintained
`tools/oos_client/mesen2_client_lib/oracle_cheats.py` implementation; the old
`tools/oracle_cheats/oracle_cheats.py` path forwards to it. Historical patch
files are retained as provenance. Do not copy their older implementations over
the maintained client.

## Warp paths

Pokes happen while paused; then the emulator runs until the module settles.

| Case | Writes | Game path |
|------|--------|-----------|
| Entrance, overworld play (`$10` = `$09`) | `$010E`=id, `$010A`=0, `$04AA`=0, `$4D`=`$46`=0, `$010C`=`$06`, `$10`=`$0F` | FindEntrance door entry: Module0F -> Module06; the overworld spot is cached, so leaving a house returns there |
| Entrance, indoors / special area | `$010E`=id, `$010A`=1, `$04AA`=0, `$1B`=1, `$10`=`$05` | Death-continue reload: Module05 -> Module06 (no recache) |
| House entrance (room `$0100-$017F`) from indoors | area warp to the door's area, then the door entry | keeps the exit cache valid |
| Overworld area | `$A0`=exit-table room, `$13`=`$80`, `$9B`=0, `$10`=`$08` | Dungeon-exit load (LoadOverworldFromUnderworld) |
| Checkpoint | resolve `after` chain, write WRAM, copy `$7EF000-$7EF4FF` to the active SRAM slot (main + mirror, checksum), warp | area checkpoints reload through an entrance first so HUD hearts and follower graphics refresh |

Refused: not in normal play (module `$07/$09/$0B`, submodule 0), follower `$02`
(the game would load the spawn point), areas without an exit-table room, special
areas `$80+`.

## Verification (2026-09-26)

Headless, `/Applications/Mesen2 OOS.app`, copy of `Roms/Playtest/oos-v0.9.0-b17.sfc`
with a copy of the SNES Classic save `20260926T111512Z-CLV-H-OOSRA`:
`test_cheats_headless.py --boot` -> 27/27 (12 warps, 15 checkpoints; GameState,
crystals, room/area, SRAM slot = WRAM). The dev bundle built from this branch
renders the pane (off-Space window capture); clicking through the pane was not
done.
