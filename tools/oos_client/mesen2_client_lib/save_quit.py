"""Bounded controller-driven Save/Quit for the ALTTP/Oracle message layout.

No discovery, launch, savestate load, WRAM/SRAM write or process shutdown.
The caller retains its runtime lease; library callers supply authorize(endpoint).
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from .cart_sram import read_cart_sram, validate_inverse_checksum
from .gameplay import _reply, advance_exact


def bind_local_authority(endpoint, rom_path):
    """Bind existing public status/PID identity, plus an agent registry if set.

    This reads the existing status and NativeSession records; it creates no
    registry and acquires no lease. A manual invocation supplies user intent.
    The process check uses the supported desktop Unix `ps` interface.
    """
    if not endpoint or endpoint.startswith("tcp://"):
        raise ValueError("save-quit requires an explicit local --socket")
    status_path = Path(endpoint[:-5] + ".status" if endpoint.endswith(".sock")
                       else endpoint + ".status")
    rom_sha1 = hashlib.sha1(Path(rom_path).read_bytes()).hexdigest()
    registry = os.environ.get("OOS_NATIVE_SESSION_REGISTRY")
    instance = os.environ.get("OOS_NATIVE_SESSION_INSTANCE")
    if (("OOS_NATIVE_SESSION_REGISTRY" in os.environ and not registry)
            or (instance and not registry)):
        raise ValueError("Agent session requires its existing owned native registry")

    def identity():
        status = json.loads(status_path.read_text())
        pid = status.get("pid")
        if (status.get("socketPath") != endpoint or type(pid) is not int or pid <= 0
                or str(status.get("romHash", "")).lower() != rom_sha1):
            raise ValueError("Status endpoint/PID/ROM does not match the selected instance")
        start = " ".join(subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "lstart="], text=True, timeout=1).split())
        if not start:
            raise ValueError("Selected emulator process has exited")
        if registry:
            rows = [json.loads(line) for line in Path(registry).read_text().splitlines() if line]
            matches = [r for r in rows if r.get("pid") == pid
                       and r.get("socket") == endpoint and r.get("pid_start") == start
                       and (not instance or r.get("instance") == instance)]
            if len(matches) != 1:
                raise ValueError("Selected instance is not in the current owned native registry")
        return pid, start

    original = identity()

    def authorize(selected):
        return selected == endpoint and identity() == original

    return authorize, {"pid": original[0], "pid_start": original[1],
                       "status_path": str(status_path),
                       "native_registry_enforced": bool(registry)}


def save_quit(bridge, rom_path, *, endpoint, authorize, timeout=60.0,
              max_frames=1500, message_id=0x186):
    """Save through the game's menu and return a JSON-compatible receipt.

    Requires paused stable gameplay and an explicit matching owned endpoint.
    Success means module17 was observed before title/file select and live SRAM
    has the active slot's valid main/mirror copies. It does not establish disk
    flush, cold reopening or an earned route. Every mutation rechecks ownership
    and loaded ROM. A timeout preserves the first error and pauses only the
    same still-authorized instance; no fallback session or replay is attempted.
    """
    started = time.monotonic()
    deadline = started + timeout
    receipt = {"schema": "mesen.save-quit.v1", "ok": False, "endpoint": endpoint,
               "profile": "alttp_oracle_save_menu_v1", "message_id": message_id,
               "phase": "preflight", "frames": 0, "module17_seen": False,
               "events": [], "cold_reopen": "not_run"}
    mutated = False
    rom_sha1 = None

    def raw(command, params=None, *, limit=None):
        remaining = (deadline - time.monotonic()) if limit is None else limit
        if remaining <= 0:
            raise TimeoutError("Save/Quit wall-clock deadline reached")
        return bridge.send_command(command, params or {}, timeout=min(1.0, remaining))

    def same_rom(*, limit=None):
        response = raw("ROMINFO", limit=limit)
        data = response.get("data")
        if isinstance(data, str):
            data = json.loads(data)
        if not response.get("success") or not isinstance(data, dict) or str(data.get("sha1", "")).lower() != rom_sha1:
            raise ValueError("Loaded ROM identity changed or is unavailable")

    class BoundedBridge:
        def send_command(self, command, params=None):
            nonlocal mutated
            if bridge.socket_path != endpoint:
                raise ValueError("Explicit endpoint changed")
            if command in ("FRAME", "INPUT"):
                if not authorize(endpoint):
                    raise ValueError("Instance ownership lost")
                same_rom()
                mutated = True
            return raw(command, params)

        def read_block(self, address, length, memtype=None):
            params = {"addr": f"0x{address:06X}", "len": str(length)}
            if memtype:
                params["memtype"] = memtype
            response = self.send_command("READBLOCK", params)
            if not response.get("success") or not isinstance(response.get("data"), str):
                raise ValueError("READBLOCK failed during Save/Quit")
            result = bytes.fromhex(response["data"].strip('"'))
            if len(result) != length:
                raise ValueError("Incomplete Save/Quit memory read")
            return result

    bounded = BoundedBridge()

    def observe():
        before = _reply(bounded, "STATE")
        if before.get("paused") is not True:
            raise ValueError("Save/Quit requires a paused owned instance")
        ram = bounded.read_block(0x7E0000, 0x70)
        dialog = bounded.read_block(0x7E1CD0, 0x24)
        offset = int.from_bytes(dialog[9:11], "little")
        ready = (dialog[8] == 1 and dialog[4] == 3 and dialog[0x19] == 0
                 and offset < 0x800
                 and bounded.read_block(0x7F1200+offset, 1)[0] in (0x68, 0x6F, 0x71, 0x72))
        after = _reply(bounded, "STATE")
        if after.get("paused") is not True or after.get("frame") != before.get("frame"):
            raise ValueError("Instance advanced during Save/Quit observation")
        result = {"frame": after["frame"], "module": ram[0x10], "submodule": ram[0x11],
                  "indoors": ram[0x1B], "hero": ram[0x5D],
                  "message": int.from_bytes(dialog[0x20:0x22], "little"),
                  "choice": dialog[0x18], "choice_ready": ready}
        receipt["last_state"] = result
        receipt["module17_seen"] |= result["module"] == 0x17
        return result

    def step():
        if receipt["frames"] >= max_frames:
            raise ValueError("Save/Quit frame limit reached")
        advance_exact(bounded, 1, timeout=max(0.001, deadline-time.monotonic()))
        receipt["frames"] += 1
        return observe()

    def release():
        _reply(bounded, "INPUT", {"buttons": "", "frames": "0", "player": "0"})

    def press(button):
        _reply(bounded, "INPUT", {"buttons": button, "frames": "2", "player": "0"})
        step()
        state = step()
        release()
        return state

    def wait(predicate, phase):
        receipt["phase"] = phase
        state = observe()
        while not predicate(state):
            if state["module"] == 0x0E and (state["submodule"] != 0x0B or state["message"] != message_id):
                raise ValueError("Unexpected dialog while waiting for Save/Quit")
            state = step()
        receipt["events"].append({"phase": phase, **state})
        return state

    try:
        if not callable(authorize) or not endpoint or bridge.socket_path != endpoint:
            raise ValueError("Explicit matching endpoint and ownership callback required")
        if not 0 < timeout <= 120 or type(max_frames) is not int or not 1 <= max_frames <= 3600:
            raise ValueError("Require timeout in (0,120] and max_frames in 1..3600")
        if type(message_id) is not int or not 0 <= message_id <= 0xFFFF:
            raise ValueError("Save/Quit message ID must be an unsigned word")
        if not authorize(endpoint):
            raise ValueError("Instance is not owned")
        blob = Path(rom_path).read_bytes()
        rom_sha1 = hashlib.sha1(blob).hexdigest()
        receipt["rom_sha256"] = hashlib.sha256(blob).hexdigest()
        same_rom()
        state = observe()
        if state["module"] not in (7, 9, 11) or state["submodule"] != 0 or state["hero"] != 0:
            raise ValueError("Start in stable gameplay with no active Link action")
        slot_index = int.from_bytes(bounded.read_block(0x1FFE, 2, "SRAM"), "little")
        if slot_index not in (0, 2, 4, 6):
            raise ValueError("Active SRAM slot selector is invalid")
        slot = 1 if slot_index == 0 else slot_index//2
        receipt["slot"] = slot
        press("select")
        ready = lambda s: s["module"] == 0x0E and s["submodule"] == 0x0B and s["message"] == message_id and s["choice_ready"]
        state = wait(ready, "choice_ready_before_down")
        if state["choice"] != 0:
            raise ValueError("Save/Quit did not start at Continue")
        press("down")
        state = wait(ready, "choice_ready_before_confirm")
        if state["choice"] != 1:
            raise ValueError("Save and Quit choice was not selected")
        press("a")
        wait(lambda s: receipt["module17_seen"] and s["module"] in (0, 1), "save_to_title")
        same_rom()
        sram = read_cart_sram(bounded)
        base = (slot-1)*0x500
        primary, mirror = sram[base:base+0x500], sram[base+0xF00:base+0x1400]
        after_index = int.from_bytes(sram[0x1FFE:0x2000], "little")
        if after_index not in ((0, 2) if slot == 1 else (slot*2,)):
            raise ValueError("Active SRAM slot changed")
        if primary != mirror or not validate_inverse_checksum(primary):
            raise ValueError("Native save main/mirror or checksum is invalid")
        receipt.update(ok=True, phase="saved_at_title", sram={"bytes": len(sram),
                       "sha256": hashlib.sha256(sram).hexdigest(),
                       "main_mirror_equal": True, "checksum_valid": True})
    except Exception as exc:
        receipt["error"] = str(exc)
    finally:
        if mutated and not receipt["ok"]:
            try:
                for command, params in (("INPUT", {"buttons": "", "frames": "0", "player": "0"}), ("PAUSE", {})):
                    if bridge.socket_path != endpoint or not authorize(endpoint):
                        raise ValueError("Ownership lost; cleanup cannot mutate instance")
                    same_rom(limit=1)
                    if not raw(command, params, limit=1).get("success"):
                        raise ValueError("Owned cleanup command failed")
                receipt["cleanup"] = "input_released_and_paused"
            except Exception as exc:
                receipt["cleanup"] = "unconfirmed"
                receipt["cleanup_error"] = str(exc)
        receipt["elapsed_seconds"] = round(time.monotonic()-started, 3)
    return receipt
