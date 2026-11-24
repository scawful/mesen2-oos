#!/usr/bin/env python3
"""Post-rsync patch for SDL's Android joystick hasKeys() gap on Anbernic pads."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/org/libsdl/app/SDLControllerManager.java"

OLD = """        boolean[] has_keys = joystickDevice.hasKeys(keys);
        for (int i = 0; i < keys.length; ++i) {
            if (has_keys[i]) {
                button_mask |= masks[i];
            }
        }
        return button_mask;"""

NEW = """        boolean[] has_keys = joystickDevice.hasKeys(keys);
        for (int i = 0; i < keys.length; ++i) {
            if (has_keys[i]) {
                button_mask |= masks[i];
            }
        }
        String deviceName = joystickDevice.getName();
        if (deviceName != null) {
            String n = deviceName.toLowerCase(java.util.Locale.US);
            if (n.contains("retrogame") || n.contains("touch_joypad")
                    || n.contains("rg353") || n.contains("odroidgo")
                    || n.contains("go-super") || n.contains("go-advance")) {
                // RK gpio pads often omit hasKeys bits even though they emit BTN_*.
                button_mask |= 0x7FFF;
                button_mask |= (1 << 15) | (1 << 16);
            }
        }
        return button_mask;"""


def main() -> int:
    text = TARGET.read_text()
    if "RK gpio pads often omit hasKeys" in text:
        return 0
    if OLD not in text:
        print("error: SDLControllerManager.java did not match expected hasKeys block", file=sys.stderr)
        return 1
    TARGET.write_text(text.replace(OLD, NEW, 1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
