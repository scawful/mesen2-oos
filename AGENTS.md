# Mesen2 Fork Usage

_Extends: [unified_agent_protocol.md](file:///Users/scawful/.context/memory/unified_agent_protocol.md)_

This repository is the active Mesen2 fork used for Oracle of Secrets debugging (~/src/hobby/mesen2-oos).

- Use this repo for all Mesen2 changes: `/Users/scawful/src/hobby/mesen2-oos`.
- Do not edit any other Mesen2 clone or upstream repo unless the user explicitly asks.
- If multiple clones appear to be in use, verify the active one by comparing recent activity
  (e.g., `git log -1 --stat` or file timestamps) and ask the user if it is ambiguous.
- When a task mentions "Mesen2" or emulator debugging for Oracle of Secrets, default to this fork.

## Critical Notes (2026-01-24)

- **Socket Stability:** Improved with request validation and error handling. Monitor for any remaining issues.
- **Save State Mirroring:** YAZE save state integration implemented via `SAVESTATE_SYNC` and `SAVESTATE_WATCH` commands. See [Agent Integration Guide](docs/Agent_Integration_Guide.md).

## Building

**IMPORTANT: Use `make`, not CMake:**
```bash
cd /Users/scawful/src/hobby/mesen2-oos
make clean && make
```

CMake builds may have incompatible settings and cause crashes.

## Running the Fork

**Do NOT replace the library in ~/Documents/Mesen2/ or /Applications/Mesen.app.**
The installed apps extract their own bundled library on startup.

**Run using the wrapper (Recommended):**
```bash
mesen-run
```

**Or run the built version directly:**
```bash
/Users/scawful/src/hobby/mesen2-oos/bin/osx-arm64/Release/osx-arm64/publish/Mesen
```

## Socket API

After the fork starts, a socket is created at `/tmp/mesen2-<pid>.sock`.

**Golden Path Reference:**
[`oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md`](../../../hobby/oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md)


## Key Directories

| Path | Purpose |
|------|---------|
| `Core/Shared/SocketServer.cpp` | Socket API handlers |
| `Core/Shared/SocketServer.h` | Socket API structs and declarations |
| `Core/Shared/YazeStateBridge.cpp` | YAZE save state synchronization |
| `Core/Shared/Video/WatchHud.cpp` | Overlay rendering |
| `bin/osx-arm64/Release/` | Built executable and library |
| `docs/Mesen2_Fork_Debugging.md` | Full API documentation |
| `docs/Agent_Integration_Guide.md` | Agent integration guide |
| `docs/Socket_API_Reference.md` | Complete API reference |

## New Agent Features (2026-01-24)

### Error Handling
- **Error Codes:** All responses include `errorCode` field (0-15)
- **Retry Hints:** `retryable` field indicates if error is retryable
- **Request Validation:**** Commands validated before processing

### Agent Discovery
- **Status File:** `/tmp/mesen2-<pid>.status` - JSON file with instance info
- **CAPABILITIES:** List supported features and API version
- **AGENT_REGISTER:** Register agent for tracking
- **METRICS:** Performance statistics (latency, error rates, etc.)

### YAZE Integration
- **SAVESTATE_SYNC:** Notify YAZE of state saves and configure bridge path
- **SAVESTATE_WATCH:** Monitor YAZE state file changes
- **Bidirectional Sync:** Mesen2 ↔ YAZE state synchronization

### Event Stream (P2.1)
- **breakpoint_hit:** Fired when execution stops (breakpoint/pause)
- **frame_complete:** Fired at the end of every frame

### Enhanced Commands
- **HEALTH:** Enhanced with diagnostics (agent count, YAZE sync status)
- **COMMAND_HISTORY:** Recent command history for debugging
- **STATE_DIFF:** Get state changes (requires caching)
- **WATCH_TRIGGER:** Notify on watch value changes

### Observability
- **Command History:** Track recent commands with latency and error codes
- **Metrics Collection:** Performance metrics via METRICS command
- **Status File:** JSON status file for service discovery

See [Agent Integration Guide](docs/Agent_Integration_Guide.md) for usage examples.
