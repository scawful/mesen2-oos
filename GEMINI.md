# GEMINI.md - Mesen2 Fork Build Instructions

_Extends: ~/AGENTS.md, ~/GEMINI.md_

Build and test instructions for the Mesen2-OoS fork.

## Usage Rule

**Do NOT use CMake directly.** This fork uses a custom `makefile` for the unified Mac build.

## Build Commands

### macOS (Apple Silicon)

```bash
# Full Rebuild
cd ~/src/hobby/mesen2-oos
make clean && make

# Incremental (Fast)
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
# Verify P1 Handlers (State, Log, Watch)
python3 test_p1_handlers.py

# Verify P2 Events (Breakpoints)
python3 test_p2_events.py
```

## Running (Multi-Instance)

To run Mesen with best practices (especially for agents or multiple sessions), use the `mesen-run` wrapper installed in `~/bin`.

```bash
# Launch standard instance
mesen-run

# Launch a separate isolated instance (random ID)
mesen-run --multi

# Launch a named isolated instance (persistent config for 'test1')
mesen-run --name=test1
```

This ensures `MESEN2_HOME` is set correctly to avoid database locking collisions.

## Documentation

- **Architecture**: [`oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md`](../../../hobby/oracle-of-secrets/Docs/Tooling/Mesen2_Architecture.md)
- **Handoff**: `.context/HANDOFF.md`
