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

**Method 2: Discovery (Legacy/Default)**

If no path is specified, the socket defaults to `/tmp/mesen2-<pid>.sock`. You can discover it via:

1. **Status File** (recommended):
   ```bash
   cat /tmp/mesen2-*.status | jq .socketPath
   ```

2. **Socket Discovery (Python)**:
   ```python
   import glob
   sockets = glob.glob("/tmp/mesen2-*.sock")
   # Test each socket with PING command
   ```

### Basic Connection (Python)

```python
import socket
import json

def connect_mesen2():
    """Connect to Mesen2 socket."""
    sockets = glob.glob("/tmp/mesen2-*.sock")
    if not sockets:
        raise RuntimeError("No Mesen2 instance found")
    
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(sockets[0])
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
- Check `/tmp/mesen2-*.sock` exists
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
