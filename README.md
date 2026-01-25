# Mesen2-OOS

**The Hacker's SNES Emulator.**

Mesen2-OOS is a specialized fork of Mesen2, optimized for:
- **Oracle of Secrets** development.
- **Advanced Reverse Engineering:** Deep integration with external tools via sockets/gRPC.
- **Automation:** First-class support for headless operation and AI agent control.

## Mission
Unlike the upstream Mesen2, which aims for accuracy and user experience, this fork prioritizes **developer ergonomics** and **instrumentation**. We break things to make them observable.

## Key Features
- **Socket API:** Control the emulator from Python/Node/Go.
- **Headless Mode:** Run tests in CI without a display.
- **Custom Debuggers:** Specialized views for Zelda 3 and Oracle of Secrets memory structures.

## Build
```bash
make
```