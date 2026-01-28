# ALTTP Hacking & Debugging Guide

## Common Breakpoints (Snes9x/Mesen Style)

### Execution Breakpoints
- `Exec`: Break when CPU executes an instruction at address.
- `Read`: Break when CPU reads from address.
- `Write`: Break when CPU writes to address.

### Specific Addresses (US ROM)
- **Link's X Coordinate**: `$7E0022` (2 bytes)
- **Link's Y Coordinate**: `$7E0020` (2 bytes)
- **Room ID**: `$7E00A0` (2 bytes)
- **Health**: `$7E036D` (1 byte) - Max is `$A0` (20 hearts)
- **Magic**: `$7E36E1` (1 byte)

### Event Scripts
When hacking events, you often want to break on the script pointer changes or specific tile interactions.
- **Script Pointer**: `$7E02D8` (approx, varies by context)

## Debugger Usage
1.  **Open Debugger**: `Debug -> Debugger`.
2.  **Breakpoints**: Right-click in code view or use the Breakpoints list.
3.  **Step Into (`F11`)**: execute one instruction.
4.  **Step Over (`F10`)**: execute one instruction (skip subroutines).
5.  **Run (`F5`)**: Resume execution.

## Trace Logger
- Use `Debug -> Trace Logger` to record execution history.
- Helpful for finding what code ran before a crash.
- **Tip**: Enable "Internal Registers" logging for detailed state.
