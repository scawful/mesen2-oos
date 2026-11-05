"""Offline lifecycle checks. These do not establish native emulator behavior."""
import hashlib
import json
from pathlib import Path

import tempfile
import unittest

from mesen2_client_lib.gameplay import advance_exact, plan_scene, prepare_scene, video_record


class Bridge:
    socket_path = "/tmp/owned-test.sock"

    def __init__(self, rom):
        self.rom = rom
        self.frame = 10
        self.step_delta = None
        self.ram = bytearray(0x500)
        for addr, val in ((0x10, 9), (0x8A, 0xA1)):
            self.ram[addr] = val
        for addr, val in ((0x22, 100), (0x20, 100)):
            self.ram[addr:addr+2] = val.to_bytes(2, "little")
        self.loads = []
        self.fail_check = False
        self.commands = []

    def send_command(self, command, params):
        self.commands.append((command, params))
        if command == "STATE":
            data = {"paused": True, "frame": self.frame}
        elif command == "ROMINFO":
            data = {"sha1": hashlib.sha1(self.rom).hexdigest()}
        elif command == "FRAME":
            self.frame += self.step_delta if self.step_delta is not None else int(params["count"])
            data = "OK"
        elif command == "CAPABILITIES":
            data = {"features": ["native_video_record_v1"]}
        elif command == "VIDEO_RECORD":
            data = {"active": False}
        else:
            raise AssertionError(command)
        return {"success": True, "data": data}

    def read_block(self, address, count):
        return bytes(self.ram)

    def read_memory(self, address):
        return 0 if self.fail_check else 24

    def save_state(self, path):
        Path(path).write_bytes(b"rollback")
        return True

    def load_state(self, path):
        self.loads.append(path)
        return True


def make_setup(tmp_path):
    rom = tmp_path / "rom.sfc"
    rom.write_bytes(b"current ROM")
    fixture = tmp_path / "scene.mss"
    fixture.write_bytes(b"compatible fixture")
    scene = {"kind": "overworld", "map_id": 0xA1, "preset": "basic",
             "fixture": fixture.name, "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
             "rom_sha256": hashlib.sha256(rom.read_bytes()).hexdigest(),
             "expected": {"module": 9, "submodule": 0, "indoors": False, "area": 0xA1,
                          "link_x": 100, "link_y": 100, "scroll_x": 0, "scroll_y": 0},
             "checks": [{"name": "health", "address": 0x7EF36D, "value": 24}]}
    catalog = tmp_path / "catalog.json"
    catalog.write_text(json.dumps({"schema": "mesen.scene-catalog.v1", "scenes": [scene]}))
    bridge = Bridge(rom.read_bytes())
    client = type("Client", (), {"bridge": bridge})()
    return catalog, scene, rom, client, tmp_path / "rollback.mss"


def prepare(setup, **kwargs):
    catalog, scene, rom, client, rollback = setup
    return prepare_scene(client, plan_scene(catalog, 0xA1, "basic"), rom,
                         endpoint=client.bridge.socket_path, authorize=kwargs.pop("authorize", lambda _: True),
                         rollback_path=rollback, **kwargs)


class GameplayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.scene_setup = make_setup(Path(self.tmp.name))

    def test_camera_failure_rolls_back(self):
        self.check_postcondition_failure("camera")

    def test_preset_failure_rolls_back(self):
        self.check_postcondition_failure("preset")

    def test_hidden_link_rolls_back(self):
        self.check_postcondition_failure("hidden_link")

    def test_exact_expanded_map_selected_without_aliasing(self):
        catalog, _, _, _, _ = self.scene_setup
        assert plan_scene(catalog, 0xA1, "basic")["map_id"] == 0xA1
        with self.assertRaisesRegex(ValueError, "exact"):
            plan_scene(catalog, 0x21, "basic")


    def test_scene_verified_and_private_rollback_retained(self):
        result = prepare(self.scene_setup)
        assert result["ok"]
        assert result["observed"]["link_in_viewport"]
        assert result["visual_visibility"] == "requires_image_review"
        assert self.scene_setup[-1].read_bytes() == b"rollback"


    def test_stale_rom_refused_before_save_or_load(self):
        self.scene_setup[2].write_bytes(b"different build")
        result = prepare(self.scene_setup)
        assert not result["ok"] and "must match" in result["error"]
        assert not self.scene_setup[-1].exists() and not self.scene_setup[3].bridge.loads


    def test_changed_fixture_refused(self):
        (self.scene_setup[0].parent / "scene.mss").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            prepare(self.scene_setup)


    def check_postcondition_failure(self, failure):
        bridge = self.scene_setup[3].bridge
        if failure == "camera":
            bridge.ram[0xE2] = 1
        elif failure == "hidden_link":
            bridge.ram[0x20] = 5
            catalog = json.loads(self.scene_setup[0].read_text())
            catalog["scenes"][0]["expected"]["link_y"] = 5
            self.scene_setup[0].write_text(json.dumps(catalog))
        else:
            bridge.fail_check = True
        result = prepare(self.scene_setup)
        assert not result["ok"] and result["rollback"] == "restored"
        assert bridge.loads[-1] == str(self.scene_setup[-1].resolve())


    def test_unowned_target_never_mutated(self):
        result = prepare(self.scene_setup, authorize=lambda _: False)
        assert not result["ok"] and "lease" in result["error"]
        assert not self.scene_setup[-1].exists() and not self.scene_setup[3].bridge.loads

    def test_existing_rollback_file_is_preserved(self):
        self.scene_setup[-1].write_bytes(b"do not replace")
        result = prepare(self.scene_setup)
        assert not result["ok"]
        assert self.scene_setup[-1].read_bytes() == b"do not replace"
        assert not self.scene_setup[3].bridge.loads

    def test_wrong_endpoint_is_refused(self):
        catalog, _, rom, client, rollback = self.scene_setup
        result = prepare_scene(client, plan_scene(catalog, 0xA1, "basic"), rom,
                               endpoint="/tmp/not-owned.sock", authorize=lambda _: True,
                               rollback_path=rollback)
        assert not result["ok"] and "endpoint" in result["error"]
        assert not rollback.exists()

    def test_duplicate_map_preset_is_ambiguous(self):
        catalog = self.scene_setup[0]
        payload = json.loads(catalog.read_text())
        payload["scenes"].append(payload["scenes"][0])
        catalog.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "exact"):
            plan_scene(catalog, 0xA1, "basic")

    def test_fixture_changed_after_plan_is_refused_before_mutation(self):
        catalog, _, rom, client, rollback = self.scene_setup
        plan = plan_scene(catalog, 0xA1, "basic")
        Path(plan["fixture"]).write_bytes(b"replaced")
        result = prepare_scene(client, plan, rom, endpoint=client.bridge.socket_path,
                               authorize=lambda _: True, rollback_path=rollback)
        assert not result["ok"] and "changed after planning" in result["error"]
        assert not rollback.exists() and not client.bridge.loads


    def test_lease_loss_prevents_rollback_mutation(self):
        calls = iter([True, True, False, False])
        result = prepare(self.scene_setup, authorize=lambda _: next(calls))
        assert result["rollback"] == "failed_or_lease_lost"
        assert self.scene_setup[3].bridge.loads == []


    def test_exact_frame_step_checks_actual_delta(self):
        bridge = self.scene_setup[3].bridge
        assert advance_exact(bridge, 3)["frames"] == 3
        bridge.step_delta = 4
        with self.assertRaisesRegex(ValueError, "diverged"):
            advance_exact(bridge, 3)


    def test_frame_noop_times_out_instead_of_claiming_success(self):
        bridge = self.scene_setup[3].bridge
        bridge.step_delta = 0
        with self.assertRaisesRegex(ValueError, "did not complete"):
            advance_exact(bridge, 1, timeout=0.001)


    def test_unsupported_recorder_binary_fails_closed(self):
        bridge = self.scene_setup[3].bridge
        bridge.send_command = lambda *_: {"success": True, "data": {"features": []}}
        with self.assertRaisesRegex(ValueError, "parity"):
            video_record(bridge, "start", token="owned", path="/tmp/take.avi")


    def test_native_record_protocol_preserves_token(self):
        bridge = self.scene_setup[3].bridge
        video_record(bridge, "start", token="owned", path="/tmp/take.avi")
        assert bridge.commands[-1] == ("VIDEO_RECORD", {"action": "start", "token": "owned", "path": str(Path("/tmp/take.avi").resolve())})

if __name__ == "__main__":
    unittest.main()
