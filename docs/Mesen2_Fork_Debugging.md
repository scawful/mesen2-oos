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

## Oracle Control Center
- Open from **Oracle > Control Center...**.
- Centralizes Oracle workflow actions (build/test/yaze/gateway), status, overlays, and docs.
- Save States tab provides labeled saves and quick access to the State Library.
- Diagnostics tab is reserved for ZSCustomOverworld v3 + Day/Night checks (see `docs/Oracle_Menu_TODO.md`).

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

Request format (newline-delimited JSON):
`{"type":"COMMAND","param":"value"}`

Notes:
- Requests are single-line JSON objects (newline terminated).
- Strings may include escaped quotes/newlines.
- Requests larger than ~1MB or stalled longer than ~5s are rejected.

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
- `READ`, `READ16`, `READBLOCK`, `WRITE`, `WRITE16`, `WRITEBLOCK`.
- `READBLOCK_BINARY` - bulk read with base64 encoding (50% smaller than hex).
- `DISASM` - disassemble at address (CPU memory only).
- `CPU` - compact CPU register snapshot.
- `SNAPSHOT`, `DIFF` - memory diff workflow.
- `SEARCH` - byte pattern search.
- `LABELS` - set/get/lookup/clear labels.
- `BREAKPOINT` - add/list/remove/enable/disable/clear breakpoints.

Files & tooling:
- `SCREENSHOT` - PNG as base64.
- `SAVESTATE` (optional `label`), `LOADSTATE`.
- `SAVESTATE_LABEL` - get/set/clear save state labels.
- `LOADSCRIPT` - load script by path or inline content.
- `CHEAT` - add/list/clear cheats.
- `INPUT` - set input overrides.
- `STATEINSPECT` - compact system/CPU/PPU summary + watch HUD text.
- `STATEINSPECT` also includes `cpus` (per-CPU state) and `watchEntries` (structured watch data by CPU).
- `BATCH` - execute multiple commands in a single request.
- `HELP` - API discovery; lists all commands or gives details on a specific command.

Save state slots:
- Default slot count is 20.
- Override with `MESEN2_SAVE_STATE_SLOTS` or `OOS_SAVE_STATE_SLOTS` environment variables.

### READBLOCK_BINARY Command
The `READBLOCK_BINARY` command reads a block of memory and returns it as base64-encoded data, reducing transfer size by 50% compared to hex string encoding.

Request format:
```json
{"type":"READBLOCK_BINARY","addr":"0x7E0000","size":"8192"}
```

Response format:
```json
{"success":true,"data":{"bytes":"<base64-encoded-data>","size":8192,"addr":"0x7E0000"}}
```

Parameters:
- `addr` - Start address (hex string)
- `size` or `len` - Number of bytes to read (up to 1MB)
- `memtype` - Optional memory type (default: SnesMemory)

### HELP Command
The `HELP` command provides API discovery, listing all available commands or giving details on a specific command.

List all commands:
```json
{"type":"HELP"}
```
Response:
```json
{"success":true,"data":{"version":"1.0.0","commands":["PING","STATE",...],"count":45,"usage":"..."}}
```

Get help for a specific command:
```json
{"type":"HELP","command":"BREAKPOINT"}
```
Response:
```json
{"success":true,"data":{"command":"BREAKPOINT","description":"Manage breakpoints","params":"action, addr, bptype, condition","example":{...}}}
```

### BATCH Command
The `BATCH` command allows executing multiple commands in a single socket request, reducing round-trip latency for multi-read scenarios.

Request format:
```json
{"type":"BATCH","commands":"[{\"type\":\"PING\"},{\"type\":\"READ\",\"addr\":\"0x7E0000\"}]"}
```

Response format:
```json
{"success":true,"data":{"results":[{"type":"PING","success":true,"data":"PONG"},{"type":"READ","success":true,"data":"0x42"}]}}
```

### TRACE Command
The `TRACE` command retrieves execution history from the trace logger.

Request format:
```json
{"type":"TRACE","count":"20","offset":"0"}
```

Response format:
```json
{"success":true,"data":{"count":20,"offset":0,"entries":[{"pc":"0x008000","cpu":0,"bytes":"A90042","disasm":"LDA #$42"}]}}
```

Note: Trace logging must be enabled in the debugger settings for entries to be captured.

### P Register Tracking (new)
Tracks changes to the SNES processor status register (P) with PC attribution.

- `P_WATCH` - Enable/disable P register change logging.
  - `action`: `start` | `stop` | `status`
  - `depth`: Max changes to keep (default 1000)
- `P_LOG` - Get recent P register changes.
  - `count`: Number of changes to return (default 50)
  - Returns: `[{pc, old_p, new_p, opcode, flags_changed, cycle}]`
- `P_ASSERT` - Set breakpoint when P doesn't match expected value.
  - `addr`: Address to check (hex string)
  - `expected_p`: Expected P value (hex string)
  - `mask`: Comparison mask (default 0xFF)

### Memory Write Attribution (new)
Tracks writes to watched memory regions with PC attribution.

- `MEM_WATCH_WRITES` - Manage memory watches.
  - `action`: `add` | `remove` | `list` | `clear`
  - `addr`: Start address (hex string)
  - `size`: Bytes to watch (default 1)
  - `depth`: Max writes to keep (default 100)
- `MEM_BLAME` - Get write history for watched address.
  - `watch_id` or `addr`: Target to query
  - Returns: `[{pc, addr, value, size, cycle, stack_pointer}]`

### Symbol Table (new)
Load and resolve Oracle symbol tables.

- `SYMBOLS_LOAD` - Load symbols from JSON file.
  - `file`: Path to symbol JSON
  - `clear`: Clear existing symbols first (default false)
- `SYMBOLS_RESOLVE` - Resolve symbol name to address.
  - `symbol`: Symbol name
  - Returns: `{addr, size, type}`

Symbol file format:
```json
{
  "Link_X_Pos": {"addr": "7E0022", "size": 2, "type": "word"},
  "GameMode": {"addr": "7E0010", "size": 1, "type": "byte"}
}
```

### Collision Overlay (new)
Visualize ALTTP collision maps on screen.

- `COLLISION_OVERLAY` - Toggle collision visualization.
  - `action`: `enable` | `disable` | `status`
  - `colmap`: `A` | `B` | `both` (default A)
  - `highlight`: Array of tile types to highlight
- `COLLISION_DUMP` - Export collision map data.
  - `colmap`: `A` | `B`
  - Returns: 2D array of collision values

ALTTP collision maps:
- COLMAPA: `$7F2000` - Primary collision map
- COLMAPB: `$7F6000` - Secondary collision map
- Water tiles: 0x09, 0x0A, 0x1A

### ALTTP Game State Inspection
Real-time game state inspection for ALTTP/Oracle debugging.

- `GAMESTATE` - Get comprehensive game state snapshot.
  - Returns: Link position/direction/state, health/magic/items, game mode, room/area info

```json
{"type":"GAMESTATE"}
```
Response includes:
- `link`: x, y, layer, direction, state, pose
- `health`: current, max, hearts, max_hearts
- `items`: magic, rupees, bombs, arrows
- `game`: mode, submode, indoors, room_id/overworld_area

- `SPRITES` - Inspect active sprites.
  - `slot`: Optional, inspect single slot (0-15)
  - `all`: If true, include inactive sprites

```json
{"type":"SPRITES"}
{"type":"SPRITES","slot":"5"}
{"type":"SPRITES","all":"true"}
```
Response: `{count, sprites: [{slot, type, state, x, y, health, subtype}]}`

### Event Subscription System
Subscribe to real-time event notifications with filtering by event type.

- `SUBSCRIBE` - Manage event subscriptions.
  - `action`: `subscribe` | `unsubscribe` | `list`
  - `events`: Comma-separated or JSON array of event types

Available event types:
- `breakpoint_hit` - Breakpoint triggered
- `frame_complete` - Frame finished rendering
- `state_changed` - Pause/resume state changed
- `logpoint` - Logpoint hit
- `memory_changed` - Watched memory modified
- `p_changed` - P register changed
- `all` - Subscribe to all events

Examples:
```json
{"type":"SUBSCRIBE","events":"breakpoint_hit,frame_complete"}
{"type":"SUBSCRIBE","action":"list"}
{"type":"SUBSCRIBE","action":"unsubscribe"}
```

Events are pushed to subscribed clients:
```json
{"type":"EVENT","event":"breakpoint_hit","data":{...}}
```

### Roadmap (planned additions)
- Conditional watch triggers (notify on specific values).
- ALTTP game state inspector (GAMESTATE command).
- Execution profiling (hotspot analysis).

---

## Building and Deployment

### Build Commands

**macOS (use make, not CMake):**
```bash
cd /Users/scawful/src/hobby/mesen2-oos
make clean && make
```

This builds:
- `bin/osx-arm64/Release/MesenCore.dylib` - Core library with socket API
- `bin/osx-arm64/Release/osx-arm64/publish/Mesen` - Executable

### Running the Built Version

**Important:** The installed `/Applications/Mesen.app` extracts its own `MesenCore.dylib`
from a bundled Dependencies.zip on startup, overwriting any manual replacements.

**To use the fork's new features, use the wrapper script:**
```bash
mesen-run
```

Or run directly from the build folder:
```bash
/Users/scawful/src/hobby/mesen2-oos/bin/osx-arm64/Release/osx-arm64/publish/Mesen
```

### Socket Path
Socket is created at `/tmp/mesen2-<pid>.sock` - check terminal output for exact path.

### Testing Commands
```bash
# Find socket
sock=$(ls /tmp/mesen2-*.sock | head -1)

# Test P_WATCH
echo '{"type":"P_WATCH","action":"start","depth":"500"}' | nc -U $sock

# Test MEM_WATCH_WRITES
echo '{"type":"MEM_WATCH_WRITES","action":"add","addr":"0x7E0116","size":"2"}' | nc -U $sock
```
