"""Offline Save/Quit lifecycle tests; no native emulator proof is inferred."""
import hashlib
import json
import socket
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

from mesen2_client_lib import cli
from mesen2_client_lib import save_quit as sq
from mesen2_client_lib.cart_sram import apply_inverse_checksum


def synthetic_choice_rom():
    rom = bytearray(0x80000)
    pc = lambda address: (address >> 16)*0x8000 + (address & 0x7FFF)
    def put(address, data):
        rom[pc(address):pc(address)+len(data)] = data
    put(0x0ECA19, b"\x72\xcf")
    put(0x0ECFBB, b"\xbd\x6e\xcf")
    put(0x0ECF6E, b"\x09\x00\x0a\x00")
    put(0x0EC707, b"\xce\xc7\xd0\xc7")
    put(0x0EC7CE, b"\x59\x59")
    put(0x0E8000, b"\x7a\x00\x74\x8a\x75\x44\x72\x7f")
    return bytes(rom)


class MenuBridge:
    socket_path = "/tmp/owned-save-test.sock"

    def __init__(self, rom, *, slot=1):
        self.rom = rom
        self.frame = 100
        self.phase = "game"
        self.choice = 0
        self.ready_at = 0
        self.title_at = 0
        self.commands = []
        self.buttons = []
        self.slot = slot
        self.bad_mirror = False
        self.bad_checksum = False
        self.skip_save_module = False
        self.wrong_dialog = False
        self.disconnected = False
        self.start_module = 9
        self.paused = True
        self.changed_rom = False
        self.redraw_frames = 44
        self.replace_redraw = False
        self.wrong_redraw_command = False
        self.redraw_module = 0x0E
        self.redraw_submodule = 0x0B
        self.initial_message = 0x186
        self.redraw_message = 0xA

    def script(self):
        initial = b"\x59"*0x20 + b"\x72\x7f"
        if self.choice == 0:
            return initial.ljust(0x800, b"\x00")
        redraw = bytearray(b"\x7a\x00\x74\x59\x59\x75\x44\x72\x7f")
        if self.replace_redraw:
            redraw[6] = 0x45
        if self.wrong_redraw_command:
            redraw[7] = 0x71
        return (bytes(redraw) + initial[len(redraw):]).ljust(0x800, b"\x00")

    @property
    def ready(self):
        return self.phase == "menu" and self.frame >= self.ready_at

    @property
    def module(self):
        return {"game": self.start_module, "menu": 0x0E,
                "saving": 0x17, "title": 0}[self.phase]

    def sram(self):
        blob = bytearray(0x2000)
        primary = bytearray(0x500)
        primary[0x40] = 2
        primary = bytearray(apply_inverse_checksum(primary))
        if self.bad_checksum:
            primary[0] ^= 1
        start = (self.slot-1)*0x500
        blob[start:start+0x500] = primary
        blob[start+0xF00:start+0x1400] = primary
        if self.bad_mirror:
            blob[start+0xF00] ^= 1
        blob[0x1FFE:0x2000] = (self.slot*2).to_bytes(2, "little")
        return bytes(blob)

    def send_command(self, command, params=None, **kwargs):
        params = params or {}
        self.commands.append((command, dict(params), self.frame))
        if self.disconnected:
            raise ConnectionError("owned socket disconnected")
        if command == "ROMINFO":
            data = {"sha1": "changed" if self.changed_rom else hashlib.sha1(self.rom).hexdigest()}
        elif command == "STATE":
            data = {"frame": self.frame, "paused": self.paused}
        elif command == "READBLOCK":
            address, size = int(params["addr"], 16), int(params["len"])
            if params.get("memtype") == "SRAM":
                data = self.sram()[address:address+size].hex()
            elif address == 0x7E0000:
                ram = bytearray(size)
                ram[0x10] = self.redraw_module if self.phase == "menu" and self.choice else self.module
                ram[0x11] = (self.redraw_submodule if self.choice else 0x0B) if self.phase == "menu" else 0
                data = ram.hex()
            elif address == 0x7E1CD0:
                ram = bytearray(size)
                ram[4], ram[8] = 3, 1
                offset = 0x20 if self.choice == 0 else (7 if self.ready else 0)
                ram[9:11] = offset.to_bytes(2, "little")
                ram[0x18], ram[0x19] = self.choice, 0 if self.ready else 2
                message = 0x123 if self.wrong_dialog else (self.redraw_message if self.choice else self.initial_message)
                ram[0x20:0x22] = message.to_bytes(2, "little")
                data = ram.hex()
            elif address == 0x7F1200:
                data = self.script()[:size].hex()
            elif address == 0x7F71C0+0xA*3:
                data = bytes.fromhex("00800e").hex()
            else:
                raise AssertionError(hex(address))
        elif command == "INPUT":
            button = params["buttons"]
            if button:
                self.buttons.append((button, self.frame, self.ready))
            if button == "select":
                assert self.phase == "game"
                self.phase, self.ready_at = "menu", self.frame+9
            elif button == "down":
                assert self.ready and self.choice == 0, "Down before input-ready"
                self.choice, self.ready_at = 1, self.frame+self.redraw_frames
            elif button == "a":
                assert self.ready and self.choice == 1, "A before choice redraw finished"
                self.phase = "title" if self.skip_save_module else "saving"
                self.title_at = self.frame+4
            data = "OK"
        elif command == "FRAME":
            self.frame += int(params["count"])
            if self.phase == "saving" and self.frame >= self.title_at:
                self.phase = "title"
            data = "OK"
        elif command == "PAUSE":
            self.paused = True
            data = "OK"
        else:
            raise AssertionError(command)
        return {"success": True, "data": data}


@pytest.fixture
def setup(tmp_path):
    path = tmp_path/"exact.sfc"
    path.write_bytes(synthetic_choice_rom())
    bridge = MenuBridge(path.read_bytes())
    return bridge, path


def run(setup, **kwargs):
    bridge, path = setup
    return sq.save_quit(bridge, path, endpoint=bridge.socket_path,
                        authorize=kwargs.pop("authorize", lambda _: True), **kwargs)


@pytest.mark.parametrize("slot", (1, 2, 3))
def test_waits_for_both_ready_states_and_validates_active_slot(setup, slot):
    bridge, _ = setup
    bridge.slot = slot
    result = run(setup)
    assert result["ok"], result
    assert result["slot"] == slot
    assert result["module17_seen"]
    assert result["phase"] == "saved_at_title"
    assert result["sram"]["main_mirror_equal"] and result["sram"]["checksum_valid"]
    assert result["cold_reopen"] == "not_run"
    assert [(b, ready) for b, _, ready in bridge.buttons] == [("select", False), ("down", True), ("a", True)]
    assert bridge.buttons[1][1] >= 109
    assert bridge.buttons[2][1] >= bridge.buttons[1][1]+6
    assert not any(cmd in ("WRITE", "WRITEBLOCK", "LOADSTATE", "SAVESTATE", "RESUME") for cmd, _, _ in bridge.commands)


def test_native_message_scratch_redraw_waits_for_authenticated_script(setup):
    result = run(setup)
    assert result["ok"], result
    before, after = result["events"][:2]
    assert (before["message"], before["choice"]) == (0x186, 0)
    assert (after["message_scratch"], after["choice"]) == (0xA, 1)
    down = next(frame for button, frame, _ in setup[0].buttons if button == "down")
    confirm = next(frame for button, frame, _ in setup[0].buttons if button == "a")
    assert confirm >= down+44
    assert result["dialog_contract"]["choice_command"] == 0x72
    assert result["dialog_contract"]["redraw_offset"] == 7
    assert result["dialog_contract"]["initial_script_sha256"] != result["dialog_contract"]["redraw_script_sha256"]


def test_redraw_message_is_not_accepted_as_initial_dialog_identity(setup):
    setup[0].initial_message = 0xA
    result = run(setup)
    assert not result["ok"] and "Unexpected dialog" in result["error"]
    assert [button for button, _, _ in setup[0].buttons] == ["select"]


def test_other_message_cannot_reuse_the_authenticated_redraw_script(setup):
    setup[0].redraw_message = 0xB
    result = run(setup)
    assert not result["ok"] and "script changed" in result["error"]
    assert "a" not in [button for button, _, _ in setup[0].buttons]


@pytest.mark.parametrize("address", [0x0ECA19, 0x0E8000])
def test_unsupported_rom_dispatch_or_redraw_side_effect_is_refused_before_down(setup, address):
    server, path = setup
    rom = bytearray(path.read_bytes())
    pc = (address >> 16)*0x8000 + (address & 0x7FFF)
    rom[pc] = 0x6B  # Different handler, or an unsupported window-type command.
    path.write_bytes(rom)
    server.rom = bytes(rom)
    result = run(setup)
    assert not result["ok"] and "Unsupported Save/Quit" in result["error"]
    assert [button for button, _, _ in server.buttons] == ["select"]


@pytest.mark.parametrize("fault", ["replace_redraw", "wrong_redraw_command"])
def test_replaced_script_is_refused_even_with_expected_redraw_message(setup, fault):
    setattr(setup[0], fault, True)
    result = run(setup)
    assert not result["ok"] and "script changed" in result["error"]
    assert result["last_state"]["message"] == 0xA
    assert "a" not in [button for button, _, _ in setup[0].buttons]


@pytest.mark.parametrize("field,value", [("redraw_module", 7), ("redraw_submodule", 2)])
def test_changed_context_during_redraw_is_not_confirmed(setup, field, value):
    setattr(setup[0], field, value)
    result = run(setup)
    assert not result["ok"] and "context after authentication" in result["error"]
    assert "a" not in [button for button, _, _ in setup[0].buttons]


def test_default_retrying_transport_is_refused_without_changing_caller_policy(setup):
    from mesen2_client_lib.bridge import MesenBridge
    bridge = MesenBridge(socket_path=setup[0].socket_path, auto_reconnect=True, max_retries=2)
    with mock.patch.object(bridge, "send_command") as send:
        result = sq.save_quit(bridge, setup[1], endpoint=bridge.socket_path, authorize=lambda _: True)
    assert not result["ok"] and "non-retrying transport" in result["error"]
    send.assert_not_called()
    assert bridge._auto_reconnect is True and bridge._max_retries == 2


def test_real_no_retry_transport_does_not_replay_a_completed_frame_after_lost_reply(setup, monkeypatch):
    from mesen2_client_lib import bridge as transport
    server, path = setup
    class Connection:
        def sendall(self, data):
            request = json.loads(data)
            command = request.pop("type")
            self.result = server.send_command(command, request)
            self.lost = command == "FRAME"
        def recv(self, size):
            if self.lost:
                raise socket.timeout("reply lost after native frame completed")
            return (json.dumps(self.result)+"\n").encode()
        def close(self):
            pass
    monkeypatch.setattr(transport, "_connect_socket", lambda *args: Connection())
    bridge = transport.MesenBridge(socket_path=server.socket_path, auto_reconnect=False, max_retries=0)
    result = sq.save_quit(bridge, path, endpoint=server.socket_path, authorize=lambda _: True)
    assert not result["ok"] and "No response" in result["error"]
    assert sum(command == "FRAME" for command, _, _ in server.commands) == 1
    assert server.frame == 101  # The missing reply never causes a second step.
    assert result["cleanup"] == "input_released_and_paused"
    assert "a" not in [button for button, _, _ in server.buttons]


@pytest.mark.parametrize("module", (0, 1, 0x0C, 0x0E, 0x17))
def test_wrong_context_refused_before_input(setup, module):
    setup[0].start_module = module
    result = run(setup)
    assert not result["ok"] and "stable gameplay" in result["error"]
    assert not setup[0].buttons


def test_running_instance_refused_without_pause_mutation(setup):
    setup[0].paused = False
    result = run(setup)
    assert not result["ok"] and "paused" in result["error"]
    assert not any(cmd == "PAUSE" for cmd, _, _ in setup[0].commands)


def test_unowned_instance_is_not_observed_or_mutated(setup):
    result = run(setup, authorize=lambda _: False)
    assert not result["ok"] and "not owned" in result["error"]
    assert not setup[0].commands


def test_wrong_rom_refused_before_input(setup):
    setup[0].changed_rom = True
    result = run(setup)
    assert not result["ok"] and "ROM identity" in result["error"]
    assert not setup[0].buttons


def test_unexpected_dialog_is_not_confirmed(setup):
    setup[0].wrong_dialog = True
    result = run(setup)
    assert not result["ok"] and "Unexpected dialog" in result["error"]
    assert [b for b, _, _ in setup[0].buttons] == ["select"]
    assert result["cleanup"] == "input_released_and_paused"


@pytest.mark.parametrize("fault,error", [("bad_mirror", "main/mirror"), ("bad_checksum", "checksum")])
def test_invalid_native_save_is_not_reported_as_success(setup, fault, error):
    setattr(setup[0], fault, True)
    result = run(setup)
    assert not result["ok"] and error in result["error"]
    assert result["module17_seen"]


def test_title_without_observed_save_module_is_not_success(setup):
    setup[0].skip_save_module = True
    result = run(setup, max_frames=30)
    assert not result["ok"] and "frame limit" in result["error"]
    assert not result["module17_seen"]


def test_frame_limit_stops_without_menu_confirmation(setup):
    result = run(setup, max_frames=5)
    assert not result["ok"] and result["frames"] == 5
    assert "frame limit" in result["error"]
    assert [b for b, _, _ in setup[0].buttons] == ["select"]


def test_wall_deadline_is_bounded_and_receipted(setup, monkeypatch):
    clock = iter(i/10 for i in range(1000))
    monkeypatch.setattr(sq, "time", SimpleNamespace(monotonic=lambda: next(clock)))
    result = run(setup, timeout=0.5)
    assert not result["ok"] and "deadline" in result["error"]
    assert "down" not in [b for b, _, _ in setup[0].buttons]


def test_lost_ownership_never_sends_confirm_or_cleanup_input(setup):
    def authorize(_):
        return not any(b == "down" for b, _, _ in setup[0].buttons)
    result = run(setup, authorize=authorize)
    assert not result["ok"] and "ownership lost" in result["error"].lower()
    assert "a" not in [b for b, _, _ in setup[0].buttons]
    assert result["cleanup"] == "unconfirmed"
    assert not any(cmd == "PAUSE" for cmd, _, _ in setup[0].commands)


def test_disconnect_returns_first_failure_without_discovery(setup):
    setup[0].disconnected = True
    result = run(setup)
    assert not result["ok"] and "disconnected" in result["error"]
    assert not setup[0].buttons


def test_disconnect_after_input_preserves_failure_and_cannot_retarget(setup, monkeypatch):
    original = setup[0].send_command

    def disconnect_after_select(command, params=None, **kwargs):
        result = original(command, params, **kwargs)
        if command == "INPUT" and params.get("buttons") == "select":
            setup[0].disconnected = True
        return result

    monkeypatch.setattr(setup[0], "send_command", disconnect_after_select)
    result = run(setup)
    assert not result["ok"] and "disconnected" in result["error"]
    assert result["cleanup"] == "unconfirmed"
    assert [b for b, _, _ in setup[0].buttons] == ["select"]


def test_changed_rom_after_choice_prevents_confirm_and_cleanup_input(setup, monkeypatch):
    original = setup[0].send_command

    def swap_after_down(command, params=None, **kwargs):
        result = original(command, params, **kwargs)
        if command == "INPUT" and params.get("buttons") == "down":
            setup[0].changed_rom = True
        return result

    monkeypatch.setattr(setup[0], "send_command", swap_after_down)
    result = run(setup)
    assert not result["ok"] and "ROM identity" in result["error"]
    assert result["cleanup"] == "unconfirmed"
    assert "a" not in [b for b, _, _ in setup[0].buttons]
    assert not any(cmd == "PAUSE" for cmd, _, _ in setup[0].commands)


def test_cleanup_rechecks_ownership_between_release_and_pause(setup):
    def authorize(_):
        releases = sum(cmd == "INPUT" and not p["buttons"] for cmd, p, _ in setup[0].commands)
        return releases < 2
    result = run(setup, max_frames=5, authorize=authorize)
    assert "frame limit" in result["error"]  # Keep original error, not cleanup failure.
    assert result["cleanup"] == "unconfirmed"
    assert not any(cmd == "PAUSE" for cmd, _, _ in setup[0].commands)


def authority_setup(tmp_path, monkeypatch):
    monkeypatch.delenv("OOS_NATIVE_SESSION_REGISTRY", raising=False)
    monkeypatch.delenv("OOS_NATIVE_SESSION_INSTANCE", raising=False)
    rom = tmp_path/"exact.sfc"
    rom.write_bytes(b"identity test ROM")
    endpoint = str(tmp_path/"owned.sock")
    status = Path(endpoint).with_suffix(".status")
    status.write_text(json.dumps({"socketPath": endpoint, "pid": 42,
                                  "romHash": hashlib.sha1(rom.read_bytes()).hexdigest()}))
    monkeypatch.setattr(sq.subprocess, "check_output", lambda *a, **k: "Thu Nov 12 20:00:00 2026\n")
    return rom, endpoint, status


def test_human_authority_uses_existing_status_without_private_helper(tmp_path, monkeypatch):
    rom, endpoint, _ = authority_setup(tmp_path, monkeypatch)
    authorize, identity = sq.bind_local_authority(endpoint, rom)
    assert authorize(endpoint)
    assert not identity["native_registry_enforced"]
    assert not authorize("/tmp/another.sock")


def test_process_reuse_refuses_authority(tmp_path, monkeypatch):
    rom, endpoint, _ = authority_setup(tmp_path, monkeypatch)
    authorize, _ = sq.bind_local_authority(endpoint, rom)
    monkeypatch.setattr(sq.subprocess, "check_output", lambda *a, **k: "Fri Nov 13 00:00:00 2026\n")
    assert not authorize(endpoint)


def test_agent_environment_requires_matching_existing_registry(tmp_path, monkeypatch):
    rom, endpoint, _ = authority_setup(tmp_path, monkeypatch)
    registry = tmp_path/"owned_pids.jsonl"
    row = {"pid": 42, "pid_start": "Thu Nov 12 20:00:00 2026", "socket": endpoint, "instance": "agent-test"}
    registry.write_text(json.dumps(row)+"\n")
    monkeypatch.setenv("OOS_NATIVE_SESSION_REGISTRY", str(registry))
    monkeypatch.setenv("OOS_NATIVE_SESSION_INSTANCE", "agent-test")
    authorize, identity = sq.bind_local_authority(endpoint, rom)
    assert authorize(endpoint) and identity["native_registry_enforced"]
    registry.write_text(json.dumps({**row, "socket": "/tmp/foreign.sock"})+"\n")
    with pytest.raises(ValueError, match="owned native registry"):
        authorize(endpoint)


@pytest.mark.parametrize("registry,instance", [("", ""), (None, "agent-test")])
def test_partial_agent_environment_cannot_downgrade_to_human_authority(tmp_path, monkeypatch, registry, instance):
    rom, endpoint, _ = authority_setup(tmp_path, monkeypatch)
    if registry is not None:
        monkeypatch.setenv("OOS_NATIVE_SESSION_REGISTRY", registry)
    monkeypatch.setenv("OOS_NATIVE_SESSION_INSTANCE", instance)
    with pytest.raises(ValueError, match="owned native registry"):
        sq.bind_local_authority(endpoint, rom)


def test_cli_requires_explicit_socket_even_with_discovery_environment(setup, monkeypatch):
    monkeypatch.setenv("MESEN2_SOCKET_PATH", "/tmp/foreign.sock")
    monkeypatch.setattr(cli.sys, "argv", ["client", "save-quit", "--rom", str(setup[1])])
    with mock.patch.object(cli, "_preflight_socket") as discover, pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 2
    discover.assert_not_called()


def test_cli_uses_fixed_endpoint_without_reconnect(setup, monkeypatch, capsys):
    from mesen2_client_lib import bridge as bridge_module
    monkeypatch.setattr(cli.sys, "argv", ["client", "--socket", setup[0].socket_path,
                                         "save-quit", "--rom", str(setup[1])])
    monkeypatch.setattr(sq, "bind_local_authority", lambda *a: (lambda _: True, {"test": True}))
    factory = mock.Mock(return_value=setup[0])
    monkeypatch.setattr(bridge_module, "MesenBridge", factory)
    with mock.patch.object(cli, "_preflight_socket") as discover:
        cli.main()
    factory.assert_called_once_with(socket_path=setup[0].socket_path, auto_reconnect=False, max_retries=0)
    discover.assert_not_called()
    assert json.loads(capsys.readouterr().out)["ok"]
