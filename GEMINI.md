# GEMINI.md - Mesen2 Fork Build Instructions

_Extends: ~/AGENTS.md, ~/GEMINI.md_

Build and test instructions for the Mesen2-OoS fork.

## Usage Rule

**Do NOT use CMake directly.** This fork uses a custom `makefile` for the unified Mac build.

## Build Commands

### macOS (Apple Silicon)

```bash
# Recommended: Use mesen-agent for unified workflow
mesen-agent build

# Legacy: Full Rebuild
cd ~/src/hobby/mesen2-oos
make clean && make

# Legacy: Incremental (Fast)
make
```

### Deploy

The `make` command automatically builds the `Mesen` app bundle in `bin/osx-arm64/Release/`.

To deploy the core library for Headless/MCP usage:
```bash
./tools/deploy_core.sh
```

## Testing

Run the Python verification scripts in the root:

```bash
# Verify Full Socket API (Control, Memory, Debug, Analysis, ALTTP)
pytest test_socket_api_full.py -v

# Verify P1 Handlers (State, Log, Watch)
python3 test_p1_handlers.py

# Verify P2 Events (Breakpoints)
python3 test_p2_events.py
```

## Running (Multi-Instance)

To run Mesen with best practices (especially for agents or multiple sessions), use the `mesen-run` wrapper installed in `~/bin`.

```bash
# Recommended: Use mesen-agent for unified session management
mesen-agent launch [session-name] [--headless] [--rom path/to/rom]

# Legacy: Launch standard instance (GUI)
mesen-run

# Legacy: Launch a separate concurrent instance (GUI)
mesen-run --multi

# Legacy: Launch a headless instance for agents
mesen-run --headless --multi
```

**Key Behavior:**
- **Unified Config**: All instances share the Mesen2 home folder (platform-specific; override via `MESEN2_HOME`).
- **Separate Sockets**: Each instance creates a unique socket at `/tmp/mesen2-<PID>.sock`.
- **Concurrent Execution**: Use `--multi` to run alongside your main playing session.

## Agent Integration

**DEPRECATION NOTICE**: The `mesen2-mcp` and `yaze-mcp` tools are deprecated.

Agents should interact with Mesen2 directly via the **Unix Socket API**.

1.  **Launch**: Start Mesen2 via `mesen-run --multi --headless`.
2.  **Connect**: Find the socket in `/tmp/mesen2-*.sock`.
3.  **Control**: Send JSON commands (see [Socket API Reference](docs/Socket_API_Reference.md)).

### Save State Hygiene

**CRITICAL RULE:** Agents must **NEVER** overwrite user save slots (0-9).

*   **Allowed:** Use file-based saves (`path="/tmp/test.mss"`) whenever possible.
*   **Allowed:** Use Slots 10-99 if file-based is not supported.
*   **Forbidden:** Slots 0-9 (Reserved for manual user play).

See [Agent Integration Guide](docs/Agent_Integration_Guide.md) for Python examples.


## Documentation

- **Architecture**: [`oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md`](../../../hobby/oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md)
- **Handoff**: `.context/HANDOFF.md`
