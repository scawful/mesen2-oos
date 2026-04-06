# Mesen2 Agent Integration Guide

This guide provides best practices for integrating AI agents and automation tools with the Mesen2 fork's socket API.

## Quick Start

### Finding the Socket

**Method 1: Deterministic Path (Recommended for Agents)**

Set the `MESEN2_SOCKET_PATH` environment variable when launching Mesen2. This guarantees the socket location.

```bash
# Launch Mesen2
export MESEN2_SOCKET_PATH="/tmp/mesen2-agent.sock"
./bin/mesen-run --headless --profile agent
```

Then simply connect to `/tmp/mesen2-agent.sock`.

**Method 2: Canonical discovery (when env is not set)**

Use this order; do **not** assume socket names contain a PID (e.g. `mesen2-isolation.sock` is valid):

1. **Env:** `MESEN2_SOCKET_PATH` (then deprecated `MESEN2_SOCKET`); use if set and path exists.
2. **Status files:** Glob `/tmp/mesen2-*.status`, read JSON `socketPath`, optionally verify with PING; sort by status file mtime (newest first) if multiple.
3. **Fallback:** Glob `/tmp/mesen2-*.sock`, sort by **socket file mtime** (most recent first).

Copy-pasteable Python (canonical order):

```python
import glob
import json
import os

def discover_socket_path():
    """Return Mesen2 socket path or None."""
    for env_var in ("MESEN2_SOCKET_PATH", "MESEN2_SOCKET"):
        path = os.environ.get(env_var)
        if path and os.path.exists(path):
            return path
    for sf in sorted(glob.glob("/tmp/mesen2-*.status"), key=lambda p: -os.path.getmtime(p)):
        try:
            with open(sf) as f:
                sp = json.load(f).get("socketPath")
            if sp and os.path.exists(sp):
                return sp
        except (OSError, json.JSONDecodeError, KeyError):
            continue
    socks = glob.glob("/tmp/mesen2-*.sock")
    return sorted(socks, key=lambda p: -os.path.getmtime(p))[0] if socks else None
```

### Status file schema

When the socket server is running, it writes a JSON status file next to the socket (same path with `.sock` replaced by `.status`). Use it for discovery and instance metadata.

| Field | Type | Description |
|-------|------|-------------|
| `pid` | number | Process ID of the Mesen2 instance |
| `socketPath` | string | Unix socket path (e.g. `/tmp/mesen2-12345.sock` or `/tmp/mesen2-isolation.sock`) |
| `statusPath` | string | Path to this status file |
| `emulatorRunning` | boolean | Whether a ROM is loaded |
| `romHash` | string | SHA-1 of loaded ROM (empty if none) |
| `paused` | boolean | Whether emulation is paused |
| `frameCount` | number | Current frame count |
| `scriptRunning` | boolean | Whether a Lua script is active |
| `registeredAgents` | number | Count of agents registered via AGENT_REGISTER |
| `lastSave` | object | Result of last SAVESTATE (slot/path, success) |
| `lastLoad` | object | Result of last LOADSTATE |

Custom socket paths (e.g. set via `MESEN2_SOCKET_PATH`) have a matching status file (e.g. `mesen2-isolation.status`).

### Timeouts and limits

- **Server:** The socket server closes the connection after about **5 seconds** without a complete request. Maximum request size is **1 MB** (2 MB for READBLOCK/READBLOCK_BINARY). Long BATCH or large TRACE fetches may approach these limits.
- **Clients:** Use a socket timeout of **at least 5 seconds** for normal commands. For long-running or bulk operations, use a larger timeout or split work into smaller requests.

### Basic Connection (Python)

```python
import glob
import socket
import json
import os

def _send_ping(path, timeout=1.0):
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(timeout)
            s.connect(path)
            s.sendall(b'{"type":"PING"}\n')
            buf = b""
            while b"\n" not in buf:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        if not buf:
            return False
        resp = json.loads(buf.decode().strip())
        return resp.get("success") is True and resp.get("data") == "PONG"
    except Exception:
        return False

def discover_socket_path():
    for env_var in ("MESEN2_SOCKET_PATH", "MESEN2_SOCKET"):
        path = os.environ.get(env_var)
        if path and os.path.exists(path):
            return path

    status_files = sorted(glob.glob("/tmp/mesen2-*.status"), key=lambda p: -os.path.getmtime(p))
    for sf in status_files:
        try:
            with open(sf) as f:
                sp = json.load(f).get("socketPath")
            if sp and os.path.exists(sp):
                return sp
        except Exception:
            pass

    sockets = glob.glob("/tmp/mesen2-*.sock")
    return sorted(sockets, key=lambda p: -os.path.getmtime(p))[0] if sockets else None

def connect_mesen2():
    socket_path = discover_socket_path()
    if not socket_path:
        raise RuntimeError("No Mesen2 instance found")
    if not _send_ping(socket_path):
        raise RuntimeError(f"Mesen2 socket not responsive: {socket_path}")

    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(socket_path)
    return sock

def send_command(sock, command_type, **params):
    """Send a command and return response."""
    cmd = {"type": command_type, **params}
    sock.sendall((json.dumps(cmd) + "\n").encode())
    
    response = b""
    while True:
        chunk = sock.recv(4096)
        if not chunk:
            break
        response += chunk
        if b"\n" in response:
            break
    
    return json.loads(response.decode().strip())

# Example usage
sock = connect_mesen2()
response = send_command(sock, "PING")
print(response)  # {"success": true, "data": "PONG"}
```

### Debug Log Fetch (Optional)

Retrieve recent emulator debug log lines (useful for SP/K corruption traces):

```python
response = send_command(sock, "DEBUG_LOG", count="50", contains="[SP]")
print(response["data"]["lines"])
```

## Error Handling

### Error Codes

All error responses include an `errorCode` field:

| Code | Name | Description | Retryable |
|------|------|-------------|-----------|
| 0 | None | Success | - |
| 1 | InvalidRequest | Malformed JSON or missing type | Yes |
| 2 | MissingParameter | Required parameter missing | Yes |
| 3 | InvalidParameter | Parameter value invalid | Yes |
| 4 | CommandNotFound | Unknown command type | No |
| 5 | NotImplemented | Command not implemented | No |
| 6 | EmulatorNotRunning | ROM not loaded | No |
| 7 | DebuggerNotAvailable | Debugger not initialized | No |
| 8 | MemoryOutOfRange | Address out of bounds | No |
| 9 | RequestTooLarge | Request exceeds size limit | Yes |
| 10 | Timeout | Operation timed out | Yes |
| 11 | ConnectionError | Socket connection issue | Yes |
| 12 | InternalError | Server-side error | No |
| 13 | PermissionDenied | Operation not allowed | No |
| 14 | ResourceExhausted | Server resource limit | Yes |
| 15 | InvalidState | Invalid emulator state | No |

### Retry Strategy

```python
def send_command_with_retry(sock, command_type, max_retries=3, **params):
    """Send command with automatic retry on retryable errors."""
    for attempt in range(max_retries):
        response = send_command(sock, command_type, **params)
        
        if response.get("success"):
            return response
        
        # Check if retryable
        if not response.get("retryable", False):
            return response  # Don't retry non-retryable errors
        
        # Exponential backoff
        time.sleep(0.1 * (2 ** attempt))
    
    return response  # Return last attempt
```

## Agent Registration

Register your agent for better tracking and diagnostics:

```python
def register_agent(sock, agent_id, agent_name="MyAgent", version="1.0.0"):
    """Register agent with Mesen2."""
    response = send_command(
        sock,
        "AGENT_REGISTER",
        agentId=agent_id,
        agentName=agent_name,
        version=version
    )
    return response.get("success", False)
```

## Health Checks

Use the enhanced `HEALTH` command for comprehensive diagnostics:

```python
def check_health(sock):
    """Get detailed health information."""
    response = send_command(sock, "HEALTH")
    if response.get("success"):
        data = json.loads(response["data"])
        return {
            "running": data.get("running"),
            "paused": data.get("paused"),
            "frame": data.get("frameCount", 0),
            "agents": data.get("diagnostics", {}).get("registeredAgents", 0),
            "yaze_synced": data.get("diagnostics", {}).get("yazeSync", {}).get("lastFrame", 0) > 0
        }
    return None
```

## Command Batching

Use `BATCH` to reduce round-trip latency:

```python
def batch_read_memory(sock, addresses):
    """Read multiple memory addresses in one request."""
    commands = [
        {"type": "READ", "addr": hex(addr)}
        for addr in addresses
    ]
    
    response = send_command(
        sock,
        "BATCH",
        commands=json.dumps(commands)
    )
    
    if response.get("success"):
        results = json.loads(response["data"])["results"]
        return [r["data"] for r in results if r.get("success")]
    return []
```

## Event Subscriptions

Subscribe to real-time events:

```python
def subscribe_to_events(sock, event_types):
    """Subscribe to events (keep connection open)."""
    events_str = ",".join(event_types) if isinstance(event_types, list) else event_types
    response = send_command(sock, "SUBSCRIBE", events=events_str)
    return response.get("success", False)

# Example: Subscribe to breakpoints and frame completion
subscribe_to_events(sock, ["breakpoint_hit", "frame_complete"])

# Then read events as they arrive
while True:
    event = read_event(sock)  # Read JSON line
    if event.get("type") == "EVENT":
        handle_event(event["event"], event["data"])
```

## YAZE State Synchronization

Sync save states with YAZE editor:

```python
def sync_state_to_yaze(sock, state_path):
    """Notify YAZE of a state save."""
    response = send_command(
        sock,
        "SAVESTATE_SYNC",
        path=state_path
    )
    return response.get("success", False)

# Save state and sync
send_command(sock, "SAVESTATE", slot="1")
sync_state_to_yaze(sock, "/path/to/state.mss")
```

## Save State Labels

Attach labels to save states so agents can quickly reference scenarios. Labels are stored in `.label` sidecar files next to `.mss` files and show up in the UI menus.

```python
# Save with label
send_command(sock, "SAVESTATE", slot="12", label="Eastern Palace - boss door")

# Manage labels explicitly
send_command(sock, "SAVESTATE_LABEL", action="get", slot="12")
send_command(sock, "SAVESTATE_LABEL", action="set", slot="12", label="Basement start")
send_command(sock, "SAVESTATE_LABEL", action="clear", slot="12")
```

### Save State Slot Count

Default slot count is 20. Override with environment variables:

```bash
export MESEN2_SAVE_STATE_SLOTS=30
# or
export OOS_SAVE_STATE_SLOTS=30
```

## z3dk Integration

When using z3dk (z3lsp, z3disasm) with Mesen2:

- **Socket targeting:** Set `MESEN2_SOCKET_PATH` so z3lsp and other tools connect to the same Mesen2 instance when multiple sockets exist.
- **Symbols:** Use `SYMBOLS_LOAD` with `file` or `path`; supported formats are **JSON** (object of name → `{addr, size, type}`) or **Mesen .mlb** (line-based). Load the same `.mlb` that the GUI uses for consistent labels.
- **Blame / disasm:** Use `SYMBOLS_RESOLVE` with `addr` (e.g. `{"type":"SYMBOLS_RESOLVE","addr":"0x008000"}`) to resolve an address to the most specific symbol name; use with MEM_BLAME or DISASM output for annotation.
- **Labels refresh:** When z3dk indexes or symbol files change, reload symbols via `SYMBOLS_LOAD` (and optionally `labels-refresh` in the Oracle client) so the socket symbol table stays in sync.

## Performance Best Practices

1. **Use BATCH for multiple reads**: Reduces latency significantly
2. **Use READBLOCK_BINARY**: 50% smaller than hex encoding
3. **Keep connections open**: Avoid reconnecting for each command
4. **Cache STATE/CPU responses**: Update only when needed
5. **Use subscriptions**: Instead of polling, subscribe to events

## Metrics and Monitoring

Get performance metrics:

```python
def get_metrics(sock):
    """Get server performance metrics."""
    response = send_command(sock, "METRICS")
    if response.get("success"):
        return json.loads(response["data"])
    return None

metrics = get_metrics(sock)
print(f"Total commands: {metrics['totalCommands']}")
print(f"Average latency: {metrics['avgLatencyUs']}μs")
print(f"Error rate: {metrics['errorRate']:.2%}")
```

## Command History

Debug issues by checking recent commands:

```python
def get_command_history(sock, count=20):
    """Get recent command history."""
    response = send_command(sock, "COMMAND_HISTORY", count=str(count))
    if response.get("success"):
        return json.loads(response["data"])
    return []
```

## Status File Discovery

Read the status file for agent discovery:

```python
import json
import glob

def find_mesen2_instances():
    """Find all running Mesen2 instances via status files."""
    instances = []
    for status_file in glob.glob("/tmp/mesen2-*.status"):
        try:
            with open(status_file) as f:
                status = json.load(f)
                instances.append({
                    "socket": status["socketPath"],
                    "running": status["emulatorRunning"],
                    "frame": status["frameCount"],
                    "agents": status["registeredAgents"]
                })
        except:
            pass
    return instances
```

## Error Recovery

Handle common error scenarios:

```python
def robust_read(sock, addr):
    """Read memory with error recovery."""
    try:
        response = send_command(sock, "READ", addr=hex(addr))
        
        if not response.get("success"):
            error_code = response.get("errorCode", 0)
            
            if error_code == 6:  # EmulatorNotRunning
                raise RuntimeError("Emulator not running - load ROM first")
            elif error_code == 8:  # MemoryOutOfRange
                raise ValueError(f"Address {addr} out of range")
            elif error_code == 11:  # ConnectionError
                # Reconnect and retry
                sock.close()
                sock = connect_mesen2()
                return robust_read(sock, addr)
            else:
                raise RuntimeError(f"Read failed: {response.get('error')}")
        
        return response["data"]
    except socket.error:
        # Connection lost, reconnect
        sock = connect_mesen2()
        return robust_read(sock, addr)
```

## Testing Your Integration

Use the test suite as a reference:

```bash
# Run integration tests
python3 test_agent_integration.py

# Test specific commands
python3 test_new_commands.py
```

## Troubleshooting

### Socket Not Found
- Ensure Mesen2 is running
- Check `MESEN2_SOCKET_PATH` first (if set), then `/tmp/mesen2-*.status`, then `/tmp/mesen2-*.sock`
- Verify permissions (socket should be readable)

### Connection Refused
- Mesen2 may have crashed
- Check Mesen2 logs
- Restart Mesen2 instance

### Timeout Errors
- Reduce request size
- Check server load (use METRICS command)
- Increase timeout value

### High Latency
- Use BATCH commands
- Reduce command frequency
- Check system load

### Stale socket cleanup

The Oracle client provides `mesen2_client.py socket-cleanup` (or `cleanup_stale_sockets()` in code) to remove dead sockets. **Only PID-named sockets** (`mesen2-<pid>.sock`) are considered: the routine parses the middle segment as a PID and unlinks the socket (and matching `.status` file) only if that process is no longer running. Custom-named sockets (e.g. `mesen2-isolation.sock` from `MESEN2_SOCKET_PATH`) are **never** removed by this routine. If a custom-named socket’s process has exited, remove the socket and status file manually.

## Advanced Features

### State Diff
Get only changed state since last call:

```python
last_state_hash = None

def get_state_diff(sock):
    """Get state changes since last call."""
    global last_state_hash
    response = send_command(sock, "STATE_DIFF")
    # Implementation would cache previous state
    return response
```

### Watch Triggers
Get notified when watched memory changes:

```python
# Add watch trigger
send_command(sock, "WATCH_TRIGGER", action="add", addr="0x7E0022", value="0x42")

# Subscribe to memory_changed events
subscribe_to_events(sock, ["memory_changed"])
```

## Example: Complete Agent Class

```python
class Mesen2Agent:
    def __init__(self, agent_id="my_agent"):
        self.sock = connect_mesen2()
        register_agent(self.sock, agent_id)
    
    def read_memory(self, addr):
        response = send_command(self.sock, "READ", addr=hex(addr))
        if response.get("success"):
            return int(response["data"], 16)
        raise RuntimeError(response.get("error"))
    
    def write_memory(self, addr, value):
        response = send_command(self.sock, "WRITE", addr=hex(addr), value=hex(value))
        return response.get("success", False)
    
    def get_game_state(self):
        response = send_command(self.sock, "GAMESTATE")
        if response.get("success"):
            return json.loads(response["data"])
        return None
    
    def close(self):
        self.sock.close()
```

## See Also

- [Socket API Reference](Socket_API_Reference.md) - Complete command reference
- [Mesen2 Fork Debugging](Mesen2_Fork_Debugging.md) - Debugging features
- `test_agent_integration.py` - Integration test examples
