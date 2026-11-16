#!/usr/bin/env python3
"""Set up a playtest scene on the RG353P over (wireless) adb and hand it to the player.

One command, run on the Mac:
  1. park:  save the running game to a free state slot (never overwrites a slot),
  2. push:  copy a ROM (+ optional seed .srm) into the app's ROM folder (sha256 checked),
  3. launch the app on that ROM and wait for it on the Workshop socket,
  4. scene: load a prepared .mss, or press through the file select and apply a
            checkpoint (Oracle Data/debug/checkpoints.json) plus optional RAM writes,
  5. note:  show a banner on the device ("Test: ..."), needs app 0.1.43+,
  6. proof: adb screencap back to the Mac.

Approval rules (AFS rg353p README): every ROM push and every APK install needs
scawful's own words in chat. Use --dry-run to show the plan first.

Example:
  python3 Android/tools/playtest_drop.py \
      --rom ~/.context/.../oos168x-integ-20261116l2.sfc --srm "rg353p-staging/roms/Oracle - Main b58.srm" \
      --name "OoS integ l2" --checkpoint garo-desert \
      --note "Test: Epona desert jump at \\$36 - jump west" --shot rg353p-staging/drops/l2-epona.png
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

PKG = "ca.mesen.oos"
ACTIVITY = f"{PKG}/.MesenActivity"
ROM_DIR = f"/sdcard/Android/data/{PKG}/files"
DROP_DIR = f"{ROM_DIR}/drops"
TMP = "/data/local/tmp/rg353p-push.bin"
PORT = 27015
PLAY_MODULES = (0x07, 0x09, 0x0B)
HOME = Path.home()
DEFAULT_CLIENT = HOME / "src/hobby/mesen2-oos/tools/oos_client"
DEFAULT_ORACLE = HOME / "src/hobby/oracle-of-secrets"


class DropError(RuntimeError):
    pass


def log(msg: str) -> None:
    print(f"[drop] {msg}", flush=True)


# ---------------------------------------------------------------- adb

def adb(serial: str, *args: str, check: bool = True, binary: bool = False, timeout: float = 120):
    cmd = ["adb", "-s", serial, *args]
    proc = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise DropError(f"{' '.join(cmd)} -> rc {proc.returncode}: {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def shell(serial: str, command: str, check: bool = True) -> str:
    return adb(serial, "shell", command, check=check).strip()


def pick_serial(requested: str | None) -> str:
    if requested:
        return requested
    if os.environ.get("ANDROID_SERIAL"):
        return os.environ["ANDROID_SERIAL"]
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    wifi = [l.split()[0] for l in out.splitlines()[1:]
            if l.strip().endswith("device") and re.match(r"\d+\.\d+\.\d+\.\d+:\d+", l)]
    if wifi:
        return wifi[0]
    # The wireless-debug port changes after a reboot: rediscover it over mDNS.
    mdns = subprocess.run(["adb", "mdns", "services"], capture_output=True, text=True).stdout
    m = re.search(r"_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)", mdns)
    if m:
        subprocess.run(["adb", "connect", m.group(1)], capture_output=True, text=True)
        return m.group(1)
    raise DropError("no Wi-Fi adb device; pass --serial (USB serial 26489f9a104f2273 also works)")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def remote_sha(serial: str, path: str) -> str | None:
    out = shell(serial, f"sha256sum {shlex.quote(path)} 2>/dev/null", check=False)
    return out.split()[0] if out else None


def push_file(serial: str, local: Path, remote: str, dry: bool) -> str:
    """Push via /data/local/tmp + cp (Android 11 refuses adb push into Android/data)."""
    want = sha256_file(local)
    have = remote_sha(serial, remote)
    if have == want:
        log(f"same file already on device: {remote}")
        return "same"
    if have is not None:
        raise DropError(f"{remote} exists with a different sha256; use a new --name (old saves stay tied to it)")
    if dry:
        log(f"DRY push {local} -> {remote} ({local.stat().st_size} bytes, sha {want[:8]})")
        return "dry"
    adb(serial, "push", str(local), TMP, timeout=300)
    shell(serial, f"mkdir -p {shlex.quote(str(Path(remote).parent))} && cp {TMP} {shlex.quote(remote)} && rm -f {TMP}")
    if remote_sha(serial, remote) != want:
        raise DropError(f"sha256 mismatch after push: {remote}")
    log(f"pushed {remote} (sha {want[:8]})")
    return "pushed"


# ---------------------------------------------------------------- socket

def connect_bridge(client_root: Path, serial: str):
    sys.path.insert(0, str(client_root))
    from mesen2_client_lib.bridge import MesenBridge  # type: ignore
    adb(serial, "forward", f"tcp:{PORT}", f"tcp:{PORT}")
    return MesenBridge(f"tcp://127.0.0.1:{PORT}")


def rom_name(bridge) -> str:
    try:
        info = bridge.get_rom_info()
    except Exception:
        return ""
    return str(info.get("name") or info.get("filename") or info.get("path") or "")


def game_state(bridge) -> tuple[int, int]:
    return bridge.read_memory(0x7E0010), bridge.read_memory(0x7E0011)


def park_running_game(bridge, serial: str, slots: list[int], dry: bool) -> dict:
    """Save the player's running session to the first slot that has no file yet."""
    name = rom_name(bridge)
    if not name:
        log("no game running; nothing to park")
        return {}
    stem = Path(name).stem
    existing = shell(serial, f"run-as {PKG} ls files/SaveStates 2>/dev/null", check=False).splitlines()
    for slot in slots:
        if f"{stem}_{slot}.mss" not in existing:
            if dry:
                log(f"DRY park '{stem}' to slot {slot}")
                return {"rom": stem, "slot": slot, "dry": True}
            if not bridge.save_state(slot=slot, allow_external=False):
                raise DropError(f"park save to slot {slot} failed")
            log(f"parked '{stem}' in state slot {slot} (Load state > slot {slot} restores it)")
            return {"rom": stem, "slot": slot}
    raise DropError(f"no free park slot among {slots} for '{stem}'; pass --park-slots")


def wait_for_rom(bridge, stem: str, timeout: float) -> None:
    end = time.time() + timeout
    while time.time() < end:
        if Path(rom_name(bridge)).stem == stem:
            return
        time.sleep(1.0)
    raise DropError(f"app did not report ROM '{stem}' within {timeout:.0f}s (got '{rom_name(bridge)}')")


def enter_gameplay(bridge, timeout: float) -> tuple[int, int]:
    """Title -> file select -> file 1 -> play. Needs a battery save in slot 1."""
    end = time.time() + timeout
    presses = 0
    while time.time() < end:
        mod, sub = game_state(bridge)
        if mod in PLAY_MODULES and sub == 0:
            return mod, sub
        if mod == 0x04 or (mod == 0x01 and presses > 30):
            raise DropError(f"stuck in file select/name entry (module ${mod:02X}); seed a save with --srm")
        # Start skips the attract/title; A picks the highlighted file.
        bridge.press_button("start" if presses % 2 == 0 else "a", frames=4)
        presses += 1
        time.sleep(0.7)
    mod, sub = game_state(bridge)
    raise DropError(f"not in gameplay after {timeout:.0f}s (module ${mod:02X}/{sub:02X})")


def parse_writes(items: list[str]) -> list[tuple[int, int]]:
    out = []
    for item in items:
        addr, _, val = item.partition("=")
        out.append((int(addr.replace("$", ""), 16), int(val.replace("$", ""), 16)))
    return out


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="adb serial (default: ANDROID_SERIAL, Wi-Fi device, then mDNS)")
    ap.add_argument("--rom", type=Path, help="local .sfc to push and launch")
    ap.add_argument("--srm", type=Path, help="seed battery save; imported on first launch only")
    ap.add_argument("--name", help="device file name without extension (default: ROM stem)")
    ap.add_argument("--device-rom", help="launch a ROM already on the device (name or full path); skips push")
    ap.add_argument("--state", type=Path, help="local .mss to load instead of a checkpoint")
    ap.add_argument("--checkpoint", help="checkpoint id from Oracle Data/debug/checkpoints.json")
    ap.add_argument("--set", action="append", default=[], metavar="ADDR=VAL",
                    help="hex RAM write after the scene, e.g. 7EF3D8=01 (repeatable)")
    ap.add_argument("--note", help="banner text shown on the device")
    ap.add_argument("--note-secs", type=int, default=25, help="banner time; 0 keeps it until the next note")
    ap.add_argument("--shot", type=Path, help="screenshot output (default rg353p-staging/drops/<name>-<time>.png)")
    ap.add_argument("--park-slots", default="9,8,7,6", help="state slots tried for parking the running game")
    ap.add_argument("--no-park", action="store_true")
    ap.add_argument("--boot-timeout", type=float, default=60)
    ap.add_argument("--client-root", type=Path, default=DEFAULT_CLIENT)
    ap.add_argument("--oracle-root", type=Path, default=DEFAULT_ORACLE)
    ap.add_argument("--dry-run", action="store_true", help="print the plan; push, launch and write nothing")
    args = ap.parse_args()
    dry = args.dry_run
    summary: dict = {"dry_run": dry}

    serial = pick_serial(args.serial)
    summary["serial"] = serial
    log(f"device {serial}")
    bridge = connect_bridge(args.client_root.expanduser(), serial)

    # 1. Park the running session.
    if not args.no_park and (args.rom or args.device_rom or args.state or args.checkpoint):
        summary["parked"] = park_running_game(bridge, serial, [int(s) for s in args.park_slots.split(",")], dry)

    # 2. Push ROM (+ seed save).
    device_rom = None
    if args.rom:
        name = args.name or args.rom.stem
        device_rom = f"{ROM_DIR}/{name}{args.rom.suffix}"
        summary["rom_push"] = push_file(serial, args.rom.expanduser(), device_rom, dry)
        if args.srm:
            summary["srm_push"] = push_file(serial, args.srm.expanduser(), f"{ROM_DIR}/{name}.srm", dry)
    elif args.device_rom:
        device_rom = args.device_rom if args.device_rom.startswith("/") else f"{ROM_DIR}/{args.device_rom}"
        if not shell(serial, f"ls {shlex.quote(device_rom)} 2>/dev/null", check=False):
            raise DropError(f"not on device: {device_rom}")
    summary["device_rom"] = device_rom

    # 3. Launch (singleInstance: a running app gets onNewIntent and loads the ROM).
    if device_rom:
        stem = Path(device_rom).stem
        if dry:
            log(f"DRY am start {ACTIVITY} --es rom '{device_rom}'")
        else:
            shell(serial, f"am start -n {ACTIVITY} --es rom {shlex.quote(device_rom)}")
            wait_for_rom(bridge, stem, 30)
            log(f"running {stem}")

    # 4. Scene.
    if not dry and (args.state or args.checkpoint or args.set):
        if args.state:
            remote = f"{DROP_DIR}/{args.state.name}"
            push_file(serial, args.state.expanduser(), remote, dry)
            if not bridge.load_state(path=remote):
                raise DropError(f"load state failed: {remote}")
            summary["state"] = remote
            time.sleep(1.0)
        else:
            summary["entered"] = "$%02X/%02X" % enter_gameplay(bridge, args.boot_timeout)
        if args.checkpoint:
            sys.path.insert(0, str(args.client_root.expanduser()))
            from mesen2_client_lib.oracle_cheats import OracleCheats  # type: ignore
            res = OracleCheats(bridge, root=args.oracle_root.expanduser()).apply_checkpoint(args.checkpoint)
            summary["checkpoint"] = {k: res.get(k) for k in ("ok", "checkpoint", "chain", "error", "state")}
            if not res.get("ok"):
                raise DropError(f"checkpoint {args.checkpoint} failed: {res.get('error')}")
            log(f"checkpoint {args.checkpoint} ok")
        for addr, val in parse_writes(args.set):
            bridge.write_memory(addr, val)
            log(f"wrote ${addr:06X} = ${val:02X}")
        summary["writes"] = args.set
        mod, sub = game_state(bridge)
        summary["module"] = f"${mod:02X}/{sub:02X}"
    elif dry and (args.state or args.checkpoint or args.set):
        log(f"DRY scene: state={args.state} checkpoint={args.checkpoint} writes={args.set}")

    # 5. Note banner (intent without `rom` only shows the note).
    if args.note is not None:
        cmd = (f"am start -n {ACTIVITY} --es note {shlex.quote(args.note)} "
               f"--ei note_secs {args.note_secs}")
        if dry:
            log(f"DRY {cmd}")
        else:
            shell(serial, cmd)
            log(f"note shown: {args.note}")
        summary["note"] = args.note

    # 6. Screenshot proof.
    if not dry:
        time.sleep(1.5)
        shot = args.shot or Path(__file__).resolve().parents[2] / "rg353p-staging/drops" / \
            f"{(args.name or 'drop').replace(' ', '_')}-{time.strftime('%Y%m%d-%H%M%S')}.png"
        shot.parent.mkdir(parents=True, exist_ok=True)
        shot.write_bytes(adb(serial, "exec-out", "screencap", "-p", binary=True))
        summary["screenshot"] = str(shot)
        log(f"screenshot {shot}")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DropError as exc:
        print(f"[drop] error: {exc}", file=sys.stderr)
        sys.exit(2)
