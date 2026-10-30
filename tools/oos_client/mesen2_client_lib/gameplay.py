"""Scene fixture contracts for yaze and bounded Mesen test episodes.

No discovery, emulator launch, RAM teleport, or wall-clock frame approximation.
The caller owns the native lease and supplies its current authorization check.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import time

from .scene_context import decode_scene_state


def _sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _reply(bridge, command, params=None):
    reply = bridge.send_command(command, params or {})
    if not reply.get("success"):
        raise ValueError(f"{command}: {reply.get('error', 'no response')}")
    data = reply.get("data")
    if isinstance(data, str):
        try:
            return json.loads(data)
        except json.JSONDecodeError:
            return data
    return data


def plan_scene(manifest_path, map_id, preset):
    """Resolve exact map/preset fixtures; never mask expanded IDs into vanilla IDs."""
    if type(map_id) is not int or not 0 <= map_id <= 0xFF:
        raise ValueError("Overworld map ID must be an integer in 0..255")
    path = Path(manifest_path).resolve()
    manifest = json.loads(path.read_text())
    if manifest.get("schema") != "mesen.scene-catalog.v1":
        raise ValueError("Unsupported scene catalog schema")
    matches = [s for s in manifest["scenes"] if s.get("map_id") == map_id
               and s.get("preset") == preset and s.get("kind") == "overworld"]
    if len(matches) != 1:
        raise ValueError("Require one exact map/preset fixture; dynamic loading is not implemented")
    scene = matches[0]
    expected = scene["expected"]
    if (expected.get("module") != 9 or expected.get("submodule") != 0
            or expected.get("indoors") is not False or expected.get("area") != map_id):
        raise ValueError("Fixture must describe stable overworld gameplay on the requested map")
    for key in ("link_x", "link_y", "scroll_x", "scroll_y"):
        if type(expected.get(key)) is not int or not 0 <= expected[key] <= 65535:
            raise ValueError(f"Expected {key} must be an unsigned word")
    checks = scene.get("checks", [])
    # Loadout and flags are assertions against the fixture, never unchecked writes.
    if not checks:
        raise ValueError("A preset requires explicit gear/health/flag readback checks")
    for check in checks:
        if (not check.get("name") or type(check.get("address")) is not int
                or not 0x7E0000 <= check["address"] < 0x800000
                or type(check.get("value")) is not int or not 0 <= check["value"] <= 255):
            raise ValueError("Preset checks require a name, WRAM address and byte value")
    fixture = (path.parent / scene["fixture"]).resolve()
    if _sha256(fixture) != scene["fixture_sha256"]:
        raise ValueError("Scene fixture SHA-256 mismatch")
    rom_hash = scene["rom_sha256"]
    if len(rom_hash) != 64 or any(c not in "0123456789abcdef" for c in rom_hash):
        raise ValueError("Require lowercase ROM SHA-256")
    return {"schema": "mesen.scene-plan.v1", "method": "verified_fixture",
            "map_id": map_id, "preset": preset, "fixture": str(fixture),
            "fixture_sha256": scene["fixture_sha256"], "rom_sha256": rom_hash,
            "expected": expected, "checks": checks}


def observe_scene(bridge):
    before = _reply(bridge, "STATE")
    if before.get("paused") is not True:
        raise ValueError("Scene observation requires a paused owned instance")
    ram = bridge.read_block(0x7E0000, 0x500)
    scene = decode_scene_state(ram)
    scene["area"] = ram[0x8A]
    after = _reply(bridge, "STATE")
    if after.get("paused") is not True or after.get("frame") != before.get("frame"):
        raise ValueError("Scene changed during observation")
    scene["frame"] = after["frame"]
    # Link origin uses the BG2 scroll mirrors; HUD is the top 32 pixels.
    x = (scene["link_x"] - scene["scroll_x"] + 32768) % 65536 - 32768
    y = (scene["link_y"] - scene["scroll_y"] + 32768) % 65536 - 32768
    scene["link_screen_origin"] = [x, y]
    scene["link_in_viewport"] = 0 <= x <= 240 and 32 <= y <= 200
    # This geometric test cannot prove Link isn't occluded or blinking.
    scene["visual_visibility"] = "requires_image_review"
    return scene


def prepare_scene(client, plan, rom_path, *, endpoint, authorize, rollback_path):
    """Load and verify a fixture, restoring the prior paused state on first failure.

    authorize(endpoint) must validate the caller's live native lease. It is called
    before each mutation. This function neither acquires nor releases that lease.
    Keep rollback_path and the returned first-failure receipt, even on success.
    Runtime binary/ABI attestation remains the lease owner's responsibility.
    """
    bridge = client.bridge
    receipt = {"schema": "mesen.scene-result.v1", "ok": False, "endpoint": endpoint,
               "plan": plan, "rollback": "not_needed", "phase": "preflight"}
    saved = False
    try:
        if not endpoint or bridge.socket_path != endpoint:
            raise ValueError("An explicit matching owned endpoint is required")
        if not authorize(endpoint):
            raise ValueError("Native lease is not owned")
        state = _reply(bridge, "STATE")
        if state.get("paused") is not True:
            raise ValueError("Pause the owned instance before scene preparation")
        if _reply(bridge, "VIDEO_RECORD").get("active"):
            raise ValueError("Stop recording before loading a scene")
        rom = Path(rom_path).read_bytes()
        rom_sha1 = hashlib.sha1(rom).hexdigest()
        if (hashlib.sha256(rom).hexdigest() != plan["rom_sha256"]
                or _reply(bridge, "ROMINFO").get("sha1", "").lower() != rom_sha1):
            raise ValueError("Loaded ROM, scene fixture and supplied ROM must match")
        if _sha256(plan["fixture"]) != plan["fixture_sha256"]:
            raise ValueError("Fixture changed after planning")
        rollback = Path(rollback_path).resolve()
        if rollback.exists() or rollback == Path(plan["fixture"]).resolve():
            raise ValueError("Rollback output must be a new private path")
        if not authorize(endpoint):
            raise ValueError("Native lease lost before rollback save")
        if not bridge.save_state(path=str(rollback)):
            raise ValueError("Could not save rollback state")
        saved = True
        receipt["rollback_path"] = str(rollback)
        receipt["phase"] = "load"
        if not authorize(endpoint) or not bridge.load_state(path=plan["fixture"]):
            raise ValueError("Scene load refused or failed")
        receipt["phase"] = "validate"
        observed = observe_scene(bridge)
        receipt["observed"] = observed
        for key, value in plan["expected"].items():
            if observed.get(key) != value:
                raise ValueError(f"Scene postcondition failed: {key}")
        if not observed["link_in_viewport"]:
            raise ValueError("Link is outside the gameplay viewport")
        for check in plan["checks"]:
            if bridge.read_memory(check["address"]) != check["value"]:
                raise ValueError(f"Preset postcondition failed: {check['name']}")
        if _reply(bridge, "ROMINFO").get("sha1", "").lower() != rom_sha1:
            raise ValueError("ROM changed during scene preparation")
        final = _reply(bridge, "STATE")
        if final.get("paused") is not True or final.get("frame") != observed["frame"]:
            raise ValueError("Instance advanced during preset validation")
        receipt.update(ok=True, phase="ready", runtime_attestation="caller_owned",
                       visual_visibility="requires_image_review")
    except Exception as exc:
        receipt["error"] = str(exc)
        if saved:
            try:
                receipt["rollback"] = ("restored" if authorize(endpoint)
                                       and bridge.load_state(path=str(rollback)) else "failed_or_lease_lost")
            except Exception as rollback_error:
                receipt["rollback"] = "failed"
                receipt["rollback_error"] = str(rollback_error)
    return receipt


def advance_exact(bridge, count, *, timeout=5.0):
    """Verify native FRAME completion; never silently substitute wall-clock play."""
    if type(count) is not int or not 1 <= count <= 600:
        raise ValueError("Frame step must be in 1..600")
    before = _reply(bridge, "STATE")
    if before.get("paused") is not True:
        raise ValueError("Exact stepping requires a paused owned instance")
    _reply(bridge, "FRAME", {"count": str(count)})
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        after = _reply(bridge, "STATE")
        delta = after["frame"] - before["frame"]
        if delta > count or delta < 0:
            raise ValueError("Native frame step diverged; stop the episode")
        if after.get("paused") and delta == count:
            return {"before": before["frame"], "after": after["frame"], "frames": count}
        time.sleep(0.01)
    raise ValueError("Native frame step did not complete; stop the episode")


def video_record(bridge, action="status", *, token="", path=None):
    """Use the versioned native recorder; an unsupported binary fails closed."""
    capabilities = _reply(bridge, "CAPABILITIES")
    if "native_video_record_v1" not in capabilities.get("features", []):
        raise ValueError("Selected emulator lacks native_video_record_v1; build/install parity required")
    if action not in ("start", "stop", "status"):
        raise ValueError("Unknown recording action")
    params = {"action": action, "token": token}
    if path is not None:
        params["path"] = str(Path(path).resolve())
    return _reply(bridge, "VIDEO_RECORD", params)
