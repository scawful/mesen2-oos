#!/usr/bin/env python3
"""Headless checks for Oracle checkpoints and warps (Data/debug/*.json).

Needs a running Mesen2-OOS instance with an Oracle ROM and a save in the active slot.
Use a copied ROM and a copied .srm: every checkpoint rewrites the active SRAM slot.

  test_cheats_headless.py --socket /tmp/mesen2-<instance>.sock [--boot] [--only ids] [--out report.json]

--boot resets the console and presses Start/A through the title and file select.
Exit status 0 when every check passes.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
CLIENT = Path(os.environ.get("MESEN2_OOS_ROOT", HERE.parents[1])) / "tools" / "oos_client"
for candidate in (CLIENT, Path.home() / "src/hobby/mesen2-oos/tools/oos_client"):
    if (candidate / "mesen2_client_lib" / "bridge.py").is_file():
        sys.path.insert(0, str(candidate))
        break

from mesen2_client_lib.bridge import MesenBridge  # noqa: E402
import oracle_cheats as oc  # noqa: E402

# Warps checked after the checkpoints: (kind, target, expected room or area).
WARP_CHECKS = [
    ("entrance", "d1", 0x004A),
    ("entrance", "0x27", 0x0098),
    ("entrance", "origins", 0x0005),
    ("entrance", "hall of secrets", 0x0012),
    ("entrance", "village shop", 0x011F),      # house room: area step, then door
    ("entrance", "0x6B", 0x011F),              # from overworld: door path
    ("area", "0x10", 0x10),
    ("area", "0x33", 0x33),
    ("area", "0x40", 0x40),
    ("area", "0x36", 0x36),
    ("area", "0x50", 0x50),
    ("area", "0x2F", 0x2F),
]


def boot(cheats: oc.OracleCheats, timeout: float = 45.0) -> bool:
    b = cheats.b
    b.reset()
    time.sleep(0.5)
    deadline = time.time() + 10
    while time.time() < deadline and cheats.r(oc.MODULE) in (0x05, 0x06, 0x07, 0x08, 0x09, 0x0E, 0x0F, 0x10, 0x11):
        time.sleep(0.2)
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = cheats.state()
        if s.module in oc.PLAY_MODULES and s.submodule == 0 and cheats.r(0x7EF3C5) > 0:
            time.sleep(0.3)
            return True
        if s.module == 0x01 and s.submodule >= 2:
            b.press_button("a", frames=6)
            time.sleep(0.6)
        elif s.module in (0x00, 0x01, 0x14):
            b.press_button("start", frames=6)
            time.sleep(0.5)
        else:
            time.sleep(0.2)
    return False


def settle(cheats: oc.OracleCheats, timeout: float = 12.0) -> bool:
    """Close text boxes until normal play (idle submodule) or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = cheats.state()
        if s.module in oc.PLAY_MODULES and s.submodule == 0:
            return True
        if s.module == oc.MOD_MESSAGE:
            cheats.b.press_button("a", frames=4)
            time.sleep(0.5)
        else:
            time.sleep(0.2)
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--socket", required=True)
    ap.add_argument("--boot", action="store_true")
    ap.add_argument("--only", help="Comma-separated checkpoint ids (default: all)")
    ap.add_argument("--no-warps", action="store_true")
    ap.add_argument("--out", help="Write a JSON report here")
    ap.add_argument("--shots", help="Directory for a screenshot per check")
    args = ap.parse_args()

    cheats = oc.OracleCheats(MesenBridge(args.socket))
    rom = cheats.b.get_rom_info()
    report: dict = {"rom": rom, "checks": []}
    if args.boot and not boot(cheats):
        print("FAIL boot: did not reach gameplay")
        return 1
    if not settle(cheats):
        print(f"FAIL start: not in normal play ({cheats.state().describe()})")
        return 1

    ids = [c["id"] for c in cheats.checkpoints["checkpoints"]]
    if args.only:
        ids = [i for i in args.only.split(",") if i]
    # The new-file checkpoint ends in the intro scenes (input needed), so run it last.
    ids = [i for i in ids if i != "intro-bed"] + (["intro-bed"] if "intro-bed" in ids else [])

    def shot(name: str) -> None:
        if args.shots:
            Path(args.shots).mkdir(parents=True, exist_ok=True)
            data = cheats.b.screenshot()
            if data:
                (Path(args.shots) / f"{name}.png").write_bytes(data)

    def record(kind: str, name: str, ok: bool, detail: dict, started: float) -> None:
        secs = round(time.time() - started, 1)
        report["checks"].append({"kind": kind, "name": name, "ok": ok, "seconds": secs, **detail})
        state = detail.get("state") or {}
        where = (f"room 0x{state.get('room', 0):04X}" if state.get("indoors") else f"area 0x{state.get('area', 0):02X}") if state else ""
        print(f"{'PASS' if ok else 'FAIL'} {kind:<10} {name:<18} {secs:>5}s  {where}  {detail.get('error', '')}")

    warps_first = [w for w in WARP_CHECKS] if not args.no_warps else []
    for kind, target, expected in warps_first:
        if not settle(cheats):
            record(kind, target, False, {"error": f"not idle: {cheats.state().describe()}"}, time.time())
            continue
        t0 = time.time()
        try:
            res = cheats.warp_entrance(target) if kind == "entrance" else cheats.warp_area(target)
        except Exception as exc:  # noqa: BLE001
            record(kind, target, False, {"error": str(exc)}, t0)
            continue
        st = res.get("state") or {}
        got = st.get("room") if kind == "entrance" else st.get("area")
        ok = bool(res.get("ok")) and got == expected
        if not ok and "error" not in res:
            res["error"] = f"expected 0x{expected:X}, got {got}"
        record(kind, target, ok, {k: res[k] for k in ("state", "error", "method", "steps") if k in res}, t0)
        shot(f"warp_{kind}_{target.replace(' ', '_')}")

    for cp_id in ids:
        if not settle(cheats):
            record("checkpoint", cp_id, False, {"error": f"not idle: {cheats.state().describe()}"}, time.time())
            continue
        t0 = time.time()
        try:
            res = cheats.apply_checkpoint(cp_id)
        except Exception as exc:  # noqa: BLE001
            record("checkpoint", cp_id, False, {"error": str(exc)}, t0)
            continue
        resolved = oc.resolve_checkpoint(cheats.checkpoints, cp_id)
        # GameState and crystals must equal the resolved JSON values.
        detail = {k: res[k] for k in ("state", "error", "sram_slot", "prime") if k in res}
        for addr, name in ((0x7EF3C5, "GameState"), (0x7EF37A, "Crystals")):
            want = None
            for write in resolved["writes"]:
                if oc.hexint(write["addr"]) == addr:
                    want = oc.apply_write(want or 0, write)
            got = cheats.r(addr)
            detail[name] = f"0x{got:02X}"
            if want is not None and got != want:
                res["ok"] = False
                detail["error"] = (detail.get("error", "") + f" {name} 0x{got:02X} != 0x{want:02X}").strip()
        # SRAM slot copy must match WRAM for the progression bytes.
        slot = res.get("sram_slot")
        if slot:
            base = (slot - 1) * 0x500
            sram = cheats.b.read_block(base, 0x500, memtype="sram")
            wram = cheats.b.read_block(oc.SAVE_START, 0x500)
            same = [a for a in (0x3C5, 0x37A, 0x3D6, 0x3C6, 0x374) if sram[a] == wram[a]]
            if len(same) != 5:
                res["ok"] = False
                detail["error"] = (detail.get("error", "") + " SRAM slot differs from WRAM").strip()
        record("checkpoint", cp_id, bool(res.get("ok")), detail, t0)
        shot(f"cp_{cp_id}")

    failed = [c for c in report["checks"] if not c["ok"]]
    print(f"{len(report['checks']) - len(failed)}/{len(report['checks'])} passed")
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
