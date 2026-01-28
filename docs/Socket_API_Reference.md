# Mesen2 Socket API Reference

**Version:** 1.0.0 | **Socket Path:** `/tmp/mesen2-<pid>.sock`

## Quick Reference

| Category | Commands |
|----------|----------|
| Control | PING, STATE, HEALTH, PAUSE, RESUME, RESET, FRAME, STEP |
| Memory | READ, READ16, READBLOCK, READBLOCK_BINARY, WRITE, WRITE16, WRITEBLOCK |
| Debugging | CPU, DISASM, BREAKPOINT, TRACE, STEP |
| Analysis | SNAPSHOT, DIFF, SEARCH, LABELS |
| State | SAVESTATE, LOADSTATE, SAVESTATE_LABEL, SCREENSHOT |
| P-Register | P_WATCH, P_LOG, P_ASSERT |
| Memory Watch | MEM_WATCH_WRITES, MEM_BLAME |
| Symbols | SYMBOLS_LOAD, SYMBOLS_RESOLVE |
| ALTTP | GAMESTATE, SPRITES, COLLISION_OVERLAY, COLLISION_DUMP |
| Events | SUBSCRIBE, LOGPOINT |
| Utility | HELP, BATCH, ROMINFO, SPEED, REWIND, CHEAT, INPUT, DEBUG_LOG |

---

## Request/Response Format

**Request:** Newline-delimited JSON
```json
{"type":"COMMAND","param":"value"}
```

**Response:**
```json
{"success":true,"data":{...},"error":"...","errorCode":0,"retryable":false}
```

**Error Codes:**
- `0` - None (success)
- `1` - InvalidRequest
- `2` - MissingParameter
- `3` - InvalidParameter
- `4` - CommandNotFound
- `5` - NotImplemented
- `6` - EmulatorNotRunning
- `7` - DebuggerNotAvailable
- `8` - MemoryOutOfRange
- `9` - RequestTooLarge
- `10` - Timeout
- `11` - ConnectionError
- `12` - InternalError
- `13` - PermissionDenied
- `14` - ResourceExhausted
- `15` - InvalidState

See [Agent Integration Guide](Agent_Integration_Guide.md) for error handling details.

---

## Core Commands

### PING
Check connectivity.
```json
{"type":"PING"}
→ {"success":true,"data":"PONG"}
```

### STATE
Get emulation state.
```json
{"type":"STATE"}
→ {"success":true,"data":{"running":true,"paused":false,"frame":12345,"fps":60.0,"consoleType":1}}
```

### HEALTH
Detailed health check with PC sample.
```json
{"type":"HEALTH"}
→ {"success":true,"data":{"running":true,"paused":false,"debugging":true,"pc":"0x008000",...}}
```

### PAUSE / RESUME / RESET
Control emulation.
```json
{"type":"PAUSE"}
{"type":"RESUME"}
{"type":"RESET"}
```

### FRAME
Run exactly one frame.
```json
{"type":"FRAME"}
```

### STEP
Step N instructions (default 1).
```json
{"type":"STEP","count":"10"}
```

---

## Memory Commands

### READ / READ16
Read 1 or 2 bytes.
```json
{"type":"READ","addr":"0x7E0022"}
{"type":"READ16","addr":"0x7E0022"}
→ {"success":true,"data":"0x42"}
```

### READBLOCK
Read N bytes as hex string.
```json
{"type":"READBLOCK","addr":"0x7E0000","len":"256"}
→ {"success":true,"data":"00FF42..."}
```

### READBLOCK_BINARY
Read N bytes as base64 (50% smaller).
```json
{"type":"READBLOCK_BINARY","addr":"0x7E0000","size":"8192"}
→ {"success":true,"data":{"bytes":"<base64>","size":8192,"addr":"0x7E0000"}}
```

### WRITE / WRITE16 / WRITEBLOCK
Write to memory.
```json
{"type":"WRITE","addr":"0x7E0022","value":"0x42"}
{"type":"WRITE16","addr":"0x7E0022","value":"0x1234"}
{"type":"WRITEBLOCK","addr":"0x7E0000","hex":"A9008D"}
```

---

## Debugging Commands

### CPU
Get CPU register state.
```json
{"type":"CPU"}
→ {"success":true,"data":{"A":"0x42","X":"0x00","Y":"0x00","SP":"0x01FF","PC":"0x008000",...}}
```

### DISASM
Disassemble at address.
```json
{"type":"DISASM","addr":"0x008000","count":"10"}
→ {"success":true,"data":{"entries":[{"addr":"0x008000","bytes":"A9 42","disasm":"LDA #$42"},...]}}
```

### BREAKPOINT
Manage breakpoints.
```json
{"type":"BREAKPOINT","action":"add","addr":"0x008000","bptype":"exec"}
{"type":"BREAKPOINT","action":"add","addr":"0x7E0022","bptype":"write","condition":"A == 0x42"}
{"type":"BREAKPOINT","action":"list"}
{"type":"BREAKPOINT","action":"remove","id":"1"}
{"type":"BREAKPOINT","action":"clear"}
```
**bptype:** `exec`, `read`, `write`, or combinations like `rw`

### TRACE
Get execution trace log.
```json
{"type":"TRACE","count":"20"}
→ {"success":true,"data":{"entries":[{"pc":"0x008000","bytes":"A9 42","disasm":"LDA #$42"},...]}}
```

---

## ALTTP Game State

### GAMESTATE
Get comprehensive game state snapshot.
```json
{"type":"GAMESTATE"}
→ {
  "link": {"x":512,"y":128,"layer":0,"direction":"down","state":0,"pose":0},
  "health": {"current":24,"max":24,"hearts":3.0,"max_hearts":3.0},
  "items": {"magic":128,"rupees":100,"bombs":10,"arrows":30},
  "game": {"mode":7,"submode":0,"indoors":false,"overworld_area":"0x00"}
}
```

### SPRITES
Inspect active sprites (16 slots).
```json
{"type":"SPRITES"}
{"type":"SPRITES","slot":"5"}
{"type":"SPRITES","all":"true"}
→ {"count":5,"sprites":[{"slot":0,"type":"0x0E","state":9,"x":256,"y":192,"health":4,"subtype":0},...]}
```

### COLLISION_OVERLAY
Toggle collision visualization.
```json
{"type":"COLLISION_OVERLAY","action":"enable","colmap":"A"}
{"type":"COLLISION_OVERLAY","action":"disable"}
{"type":"COLLISION_OVERLAY","action":"status"}
```

### COLLISION_DUMP
Export collision map data.
```json
{"type":"COLLISION_DUMP","colmap":"A"}
→ {"colmap":"A","width":64,"height":64,"data":[[0,0,1,...],...]}}
```

---

## Event Subscription

### SUBSCRIBE
Subscribe to real-time events with filtering.
```json
{"type":"SUBSCRIBE","events":"breakpoint_hit,frame_complete"}
{"type":"SUBSCRIBE","action":"list"}
{"type":"SUBSCRIBE","action":"unsubscribe"}
```

**Event types:** `breakpoint_hit`, `frame_complete`, `state_changed`, `logpoint`, `memory_changed`, `p_changed`, `all`

**Events pushed as:**
```json
{"type":"EVENT","event":"breakpoint_hit","data":{...}}
```

---

## P-Register Tracking

### P_WATCH
Enable/disable processor status tracking.
```json
{"type":"P_WATCH","action":"start","depth":"500"}
{"type":"P_WATCH","action":"stop"}
{"type":"P_WATCH","action":"status"}
```

### P_LOG
Get recent P register changes.
```json
{"type":"P_LOG","count":"50"}
→ {"entries":[{"pc":"0x008000","old_p":"0x30","new_p":"0x32","flags_changed":"NZ",...}],"total":150}
```

### DEBUG_LOG
Get recent debug log lines (optional filter).
```json
{"type":"DEBUG_LOG","count":"50","contains":"[SP]"}
→ {"lines":["[SP] SetSP PC=..."],"count":1,"total":128}
```

### P_ASSERT
Break when P doesn't match expected value.
```json
{"type":"P_ASSERT","addr":"0x008000","expected_p":"0x30","mask":"0xFF"}
```

---

## Memory Write Attribution

### MEM_WATCH_WRITES
Track writes to memory regions.
```json
{"type":"MEM_WATCH_WRITES","action":"add","addr":"0x7E0022","size":"2","depth":"100"}
{"type":"MEM_WATCH_WRITES","action":"list"}
{"type":"MEM_WATCH_WRITES","action":"remove","watch_id":"1"}
{"type":"MEM_WATCH_WRITES","action":"clear"}
```

### MEM_BLAME
Get write attribution for watched address.
```json
{"type":"MEM_BLAME","watch_id":"1"}
{"type":"MEM_BLAME","addr":"0x7E0022"}
→ {"writes":[{"pc":"0x00ABCD","addr":"0x7E0022","value":"0x42","size":1,"sp":"0x01FF","cycle":12345},...]}
```

---

## Agent Discovery Commands

### CAPABILITIES
List supported features and API version.
```json
{"type":"CAPABILITIES"}
→ {"version":"1.1.0","commands":55,"features":["error_codes","validation","yaze_sync",...]}
```

### AGENT_REGISTER
Register agent for tracking and diagnostics.
```json
{"type":"AGENT_REGISTER","agentId":"my_agent","agentName":"My Agent","version":"1.0.0"}
→ {"registered":true,"agentId":"my_agent"}
```

### METRICS
Get performance metrics and statistics.
```json
{"type":"METRICS"}
→ {"totalCommands":1234,"avgLatencyUs":5000,"errorCount":5,"errorRate":0.004,"registeredAgents":2,"activeSubscriptions":1}
```

### COMMAND_HISTORY
Get recent command history for debugging.
```json
{"type":"COMMAND_HISTORY","count":"20"}
→ [{"command":"READ","timestamp":"2026-01-24 10:30:45","errorCode":0,"latencyUs":1200},...]
```

### LOG_LEVEL
Control logging verbosity.
```json
{"type":"LOG_LEVEL","level":"debug"}
→ {"level":"debug"}
```

## YAZE Integration Commands

### SAVESTATE_SYNC
Notify YAZE editor of a state save.
```json
{"type":"SAVESTATE_SYNC","path":"/path/to/state.mss"}
→ {"synced":true,"path":"/path/to/state.mss","frame":12345}
```

### SAVESTATE_WATCH
Manage YAZE state file watching.
```json
{"type":"SAVESTATE_WATCH","action":"status"}
→ {"watching":true,"lastState":"/path/to/state.mss","lastFrame":12345}
```

## Agent-Friendly Commands

### STATE_DIFF
Get state changes since last call (requires state caching).
```json
{"type":"STATE_DIFF"}
→ {"running":true,"paused":false,"frame":12345,...}
```

### WATCH_TRIGGER
Set up triggers for watch value changes.
```json
{"type":"WATCH_TRIGGER","action":"add","addr":"0x7E0022","value":"0x42","condition":"eq"}
→ {"id":1}
```

## Utility Commands

### HELP
API discovery.
```json
{"type":"HELP"}
→ {"version":"1.0.0","commands":["PING","STATE",...],"count":55}

{"type":"HELP","command":"BREAKPOINT"}
→ {"command":"BREAKPOINT","description":"...","params":"...","example":{...}}
```

### BATCH
Execute multiple commands in one request.
```json
{"type":"BATCH","commands":"[{\"type\":\"PING\"},{\"type\":\"READ\",\"addr\":\"0x7E0000\"}]"}
→ {"results":[{"type":"PING","success":true,"data":"PONG"},{"type":"READ","success":true,"data":"0x42"}]}
```

### SAVESTATE / LOADSTATE / SAVESTATE_LABEL
```json
{"type":"SAVESTATE","slot":"1","label":"Boss room"}
{"type":"LOADSTATE","slot":"1"}
{"type":"SAVESTATE","path":"/path/to/state.mss"}
{"type":"SAVESTATE_LABEL","action":"get","slot":"1"}
{"type":"SAVESTATE_LABEL","action":"set","slot":"1","label":"Boss room"}
{"type":"SAVESTATE_LABEL","action":"clear","slot":"1"}
```
Notes:
- `label` is optional on `SAVESTATE` and stored in a `.label` sidecar file next to the `.mss`.
- `SAVESTATE_LABEL` supports `action`: `get` (default), `set`, `clear`.

### SCREENSHOT
Capture screen as base64 PNG.
```json
{"type":"SCREENSHOT"}
→ {"success":true,"data":"<base64 PNG>"}
```

### ROMINFO
Get loaded ROM information.
```json
{"type":"ROMINFO"}
```

### SPEED
Set emulation speed.
```json
{"type":"SPEED","speed":"2.0"}
```

### REWIND
Rewind emulation by frames.
```json
{"type":"REWIND","frames":"60"}
```

### INPUT
Set controller input.
```json
{"type":"INPUT","buttons":"right,a"}
```

---

## Memory Types

Use with `memtype` parameter:
- `SnesMemory` (default) - Full SNES address space
- `SnesWorkRam` / `WRAM` - Work RAM ($7E0000-$7FFFFF)
- `SnesSaveRam` / `SRAM` - Save RAM
- `SnesPrgRom` / `ROM` - Program ROM
- `SnesVideoRam` / `VRAM` - Video RAM
- `SnesSpriteRam` / `OAM` - Sprite attributes
- `SnesCgRam` / `CGRAM` - Color palette RAM
