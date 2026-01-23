# Mesen2 Fork Debugging Notes

This fork adds a few quality-of-life features for live debugging and state inspection.

## Watch file auto-load
- When a debug workspace loads and there are no watch entries yet, Mesen2 will look for a watch file next to the ROM.
- Preferred extension is `.watch` (e.g. `MyRom.watch`).
- Legacy `.txt` watch files are still supported and will be loaded as a fallback.

## Watch HUD overlay
- Toggle in **Debugger Settings > Watch HUD**.
- The overlay shows the current watch list on the game screen (top-left), using the main CPU.
- `Max entries` limits how many watch rows are drawn.
- The HUD is updated at ~20 fps while emulation runs (debugger windows not required).

## State Inspector window
- Open from **Tools > State Inspector**.
- Shows a compact summary:
  - System: ROM name, frame count, master clock, etc.
  - CPU (SNES): A/X/Y/SP/D/PC/K/DBR and flags.
  - PPU (SNES): scanline, cycle, frame, forced blank, brightness.
  - Watch: current watch list values.
- Refresh behavior follows the standard **Refresh Timing** controls at the bottom of the window.

## .symbols auto-load
- `.symbols` files (asar output) are accepted as aliases for `.sym` when auto-loading symbols.

## CLI automation
You can automate the debugging setup from the command line:

- `--openDebugger` - Open the main debugger window after a ROM loads.
- `--openStateInspector` - Open the State Inspector window after a ROM loads.
- `--enableWatchHud` - Enable the watch HUD overlay.
- `--autoDebug` - Enable watch HUD and open Debugger + State Inspector after a ROM loads.

Example:
`Mesen <rom>.sfc --autoDebug`

## Socket automation (local)
The fork also exposes a simple local socket API for automation. The socket path is
`/tmp/mesen2-<pid>.sock` and is logged when the server starts.

Request format (single line JSON):
`{"type":"COMMAND","param":"value"}`

Response format:
`{"success":true,"data":...,"error":"..."}`.

### Supported commands (current)
Core control:
- `PING` - simple connectivity check.
- `HEALTH` - running/paused/debugging + a disasm sanity check sample.
- `STATE` - basic emulation state.
- `PAUSE`, `RESUME`, `RESET`, `FRAME`, `STEP`.
- `ROMINFO`, `SPEED`, `REWIND`.

Memory & debugging:
- `READ`, `READ16`, `READBLOCK`, `WRITE`, `WRITE16`.
- `DISASM` - disassemble at address (CPU memory only).
- `SNAPSHOT`, `DIFF` - memory diff workflow.
- `SEARCH` - byte pattern search.
- `LABELS` - set/get/lookup/clear labels.
- `BREAKPOINT` - add/list/remove/toggle breakpoints.

Files & tooling:
- `SCREENSHOT` - PNG as base64.
- `SAVESTATE`, `LOADSTATE`.
- `LOADSCRIPT` - load script by path or inline content.
- `CHEAT` - add/list/clear cheats.
- `INPUT` - set input overrides.
- `STATEINSPECT` - compact system/CPU/PPU summary + watch HUD text.

### Roadmap (planned additions)
- Structured watch data in socket responses (per-entry address/value/type).
- Multi-CPU state inspector output (SPC/SA-1/GSU/Cx4).
- Additional PPU fields and per-console display state.
- Optional binary/bulk memory read API for faster tooling.
