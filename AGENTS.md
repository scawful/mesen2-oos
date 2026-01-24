# Mesen2 Fork Usage

This repository is the active Mesen2 fork used for Oracle of Secrets debugging.

- Use this repo for all Mesen2 changes: `/Users/scawful/src/third_party/forks/Mesen2`.
- Do not edit any other Mesen2 clone or upstream repo unless the user explicitly asks.
- If multiple clones appear to be in use, verify the active one by comparing recent activity
  (e.g., `git log -1 --stat` or file timestamps) and ask the user if it is ambiguous.
- When a task mentions "Mesen2" or emulator debugging for Oracle of Secrets, default to this fork.

## Building

**IMPORTANT: Use `make`, not CMake:**
```bash
cd /Users/scawful/src/third_party/forks/Mesen2
make clean && make
```

CMake builds may have incompatible settings and cause crashes.

## Running the Fork

**Do NOT replace the library in ~/Documents/Mesen2/ or /Applications/Mesen.app.**
The installed apps extract their own bundled library on startup.

**Run the built version directly:**
```bash
/Users/scawful/src/third_party/forks/Mesen2/bin/osx-arm64/Release/Mesen
```

## Socket API

After the fork starts, a socket is created at `/tmp/mesen2-<pid>.sock`.
See `docs/Mesen2_Fork_Debugging.md` for command documentation.

## Key Directories

| Path | Purpose |
|------|---------|
| `Core/Shared/SocketServer.cpp` | Socket API handlers |
| `Core/Shared/SocketServer.h` | Socket API structs and declarations |
| `Core/Shared/Video/WatchHud.cpp` | Overlay rendering |
| `bin/osx-arm64/Release/` | Built executable and library |
| `docs/Mesen2_Fork_Debugging.md` | Full API documentation |
