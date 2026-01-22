# Mesen2 Fork Debugging Notes

This fork adds a few quality-of-life features for live debugging and state inspection.

## Watch file auto-load
- When a debug workspace loads and there are no watch entries yet, Mesen2 will look for a watch file next to the ROM.
- Preferred extension is `.watch` (e.g. `MyRom.watch`).
- Legacy `.txt` watch files are still supported and will be loaded as a fallback.

## Watch HUD overlay
- Toggle in **Debugger Settings > Watch HUD**.
- The overlay shows the current watch list on the game screen (top-left), using the main CPU.
- `Max entries` limits how many watch rows are drawn.
- The HUD is updated at ~20 fps while the debugger windows are open.

## State Inspector window
- Open from **Tools > State Inspector**.
- Shows a compact summary:
  - System: ROM name, frame count, master clock, etc.
  - CPU (SNES): A/X/Y/SP/D/PC/K/DBR and flags.
  - PPU (SNES): scanline, cycle, frame, forced blank, brightness.
  - Watch: current watch list values.
- Refresh behavior follows the standard **Refresh Timing** controls at the bottom of the window.

## .symbols auto-load
- `.symbols` files (asar output) are accepted as aliases for `.sym` when auto-loading symbols.
