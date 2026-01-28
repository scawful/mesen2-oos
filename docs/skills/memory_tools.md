# Memory Tools Guide

## Memory Editor
- **Access**: `Debug -> Memory Tools -> Memory Editor`
- **View Modes**: Hex, ASCII, PRG ROM view (for NES), etc.
- **Features**:
  - **Search**: Find specific byte sequences.
  - **Go To Address**: Jump to any specific mapped memory.
  - **Freeze**: Lock values (cheats).

## Watch Window
- **Access**: `Debug -> Watch`.
- Add addresses to monitor them in real-time.
- Supports expressions (e.g. `[7E0010] + 5`).

## Symbol Map
- Load `.sym` or `.mlb` files to see function names in the debugger.
- **Mesen-CLI**: use `mesen2ctl labels --label MyFunc --addr 8000` to define dynamic labels.

## Cheat Finder
1.  **Start Search**: `Debug -> Memory Tools -> Cheat Finder`.
2.  **Initial Scan**: usually "Unknown Value".
3.  **Refine**: Change game state (e.g. take damage), then search "Less Than Last".
4.  **Repeat**: Until few candidates remain.
