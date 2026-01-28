# Mesen2 Scripting Guide

## Overview
Mesen2 supports Lua scripts and a Socket API for external control.

## Lua API
Lua scripts run within the emulator process.
- **Callbacks**: `emu.addEventCallback(name, function)`
- **Memory**: `emu.read(addr, type)`, `emu.write(addr, val, type)`
- **GUI**: `emu.drawString(x, y, text, color)`

### Example: Hello World HUD
```lua
function onDraw()
  emu.drawString(10, 10, "Hello Agent!", 0xFFFFFF, 0x000000)
end
emu.addEventCallback("frame", onDraw)
```

## Socket API
Control the emulator from external tools (Python/Node/etc).
- **Default Port**: UNIX socket `/tmp/mesen2-<PID>.sock`
- **Protocol**: JSON-line based.

### Commands
- `{"type": "READ", "addr": "7E0010", "memtype": "wram"}`
- `{"type": "WRITE", "addr": "7E0010", "value": "FF", "memtype": "wram"}`
- `{"type": "PAUSE"}`
- `{"type": "RESUME"}`

### Python Helper
Use `mesen2ctl` or `mesen-agent` to interact comfortably.
```bash
mesen-agent session list
python3 tools/mesen2ctl --socket /tmp/mesen2-1234.sock read 0x7E0010
```
