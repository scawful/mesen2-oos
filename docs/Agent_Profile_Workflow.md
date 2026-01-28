# Mesen2 Agent & Profile Workflow

## Core Concepts

1. **Profiles**: Isolated configuration environments.
   - Stored under the Mesen2 home folder: `<MESEN2_HOME>/profiles/<name>/`
   - Default home path is platform-specific (e.g., `~/Library/Application Support/Mesen2` on macOS, `~/.config/Mesen2` on Linux).
   - Use for: Separating "Dev" (coding/debugging) from "Agent" (automation) from "Play" (gaming).
2. **Deterministic Sockets**: Fixed paths for reliable agent connections.
   - Use `MESEN2_SOCKET_PATH` to set.

## Usage Guide

### 1. Launching an Agent Session

**Do NOT** use `Mesen.app` directly for agents. Use the `mesen-run` wrapper.

```bash
# 1. Define where the socket should live
export MESEN2_SOCKET_PATH="/tmp/mesen-agent.sock"

# 2. Launch headless with a dedicated profile
#    --headless: No GUI, low resource usage
#    --profile agent: Uses <MESEN2_HOME>/profiles/agent/
./bin/mesen-run --headless --profile agent
```

### 2. Launching a Dev Session

```bash
# Launch with GUI, separate from your agent config
./bin/mesen-run --profile dev
```

### 3. Connecting your Agent

Your Python agent should now connect directly to the path you exported:

```python
socket_path = os.environ.get("MESEN2_SOCKET_PATH", "/tmp/mesen-default.sock")
sock.connect(socket_path)
```

## Legacy Methods (Avoid)

- ❌ Scanning `/tmp/` for random PID sockets (Flaky, race conditions).
- ❌ Running agents against your main `default` profile (Corrupts save states/inputs).
- ❌ Launching multiple GUI instances without profiles (Conflicts).
