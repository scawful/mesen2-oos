# CLAUDE.md

_Extends: ~/AGENTS.md, ~/CLAUDE.md_

**Mesen2-OoS Fork** - The dedicated emulator for Oracle of Secrets Agentic Debugging.

## Build Commands

### macOS (Apple Silicon)

```bash
# Full Rebuild
cd ~/src/hobby/mesen2-oos
make clean && make

# Incremental (Fast)
make
```

### Run

1.  **Use wrapper**: `mesen-run` (handles multi-instance)
2.  **Run direct**: `bin/osx-arm64/Release/osx-arm64/publish/Mesen`
