"""Warp safety checks with a fake bridge; never attach to an emulator."""

from unittest.mock import Mock

import pytest

from mesen2_client_lib import cli
from mesen2_client_lib.client import OracleDebugClient
from mesen2_client_lib.constants import GameMode, OracleRAM


class WarpBridge:
    def __init__(self):
        self.memory = {
            OracleRAM.AREA_ID: 0x40,
            OracleRAM.LINK_X: 1280,
            OracleRAM.LINK_Y: 2656,
            OracleRAM.MODE: GameMode.OVERWORLD,
            OracleRAM.SUBMODE: 0,
            OracleRAM.INDOORS: 0,
        }
        self.writes = []
        self.frames = 0
        self.on_frame = None
        self.fail_write = None

    def read_memory(self, addr):
        return self.memory.get(addr, 0)

    read_memory16 = read_memory

    def write_memory(self, addr, value):
        self.writes.append((addr, value))
        if addr == self.fail_write:
            return False
        self.memory[addr] = value
        return True

    write_memory16 = write_memory

    def run_frames(self, count):
        self.frames += count
        if self.on_frame:
            self.on_frame(self)


@pytest.fixture
def client():
    # Bypass filesystem/library initialization and socket discovery entirely.
    instance = OracleDebugClient.__new__(OracleDebugClient)
    instance.bridge = WarpBridge()
    instance.last_error = ""
    paused = [True]

    def set_paused(value):
        paused[0] = value
        return True

    instance.is_paused = Mock(side_effect=lambda: paused[0])
    instance.pause = Mock(side_effect=lambda: set_paused(True))
    instance.resume = Mock(side_effect=lambda: set_paused(False))
    instance.is_connected = Mock(return_value=True)
    return instance


def assert_game_unchanged(client, before):
    for addr, value in before.items():
        assert client.bridge.memory[addr] == value


@pytest.mark.parametrize("force_reload", [False, True])
def test_cross_area_fallback_rejected_before_writes(client, force_reload):
    before = dict(client.bridge.memory)
    assert not client._fallback_overworld_warp(
        0x2A, 1312, 2688, force_reload=force_reload
    )
    assert "Cross-area fallback is unavailable" in client.last_error
    assert client.bridge.writes == []
    assert client.bridge.frames == 0
    assert_game_unchanged(client, before)


@pytest.mark.parametrize("prefer_rom", [False, True])
def test_disabled_rom_cross_area_fly_leaves_loaded_area_intact(client, prefer_rom):
    before = dict(client.bridge.memory)
    assert not client.fly_to(area=0x2A, x=1312, y=2688, prefer_rom_warp=prefer_rom)
    assert_game_unchanged(client, before)
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_REQUEST) == 0
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_ARM) == 0
    if prefer_rom:
        assert "without completion acknowledgement" in client.last_error
        client.resume.assert_called_once()
        client.pause.assert_called_once()
    else:
        assert client.bridge.writes == []
        client.resume.assert_not_called()
        client.pause.assert_not_called()


@pytest.mark.parametrize("prefer_rom", [False, True])
@pytest.mark.parametrize("mode", [GameMode.OVERWORLD, GameMode.OVERWORLD_SPECIAL])
def test_same_area_fly_changes_only_position_and_preserves_pause(client, prefer_rom, mode):
    client.bridge.memory[OracleRAM.MODE] = mode
    assert client.fly_to(area=0x40, x=1312, y=2688, prefer_rom_warp=prefer_rom)
    assert client.bridge.writes == [(OracleRAM.LINK_X, 1312), (OracleRAM.LINK_Y, 2688)]
    assert client.bridge.frames == 0
    client.resume.assert_not_called()
    client.pause.assert_not_called()


@pytest.mark.parametrize("use_rom", [False, True])
def test_same_area_fly_rejects_indoor_state_without_writes(client, use_rom):
    client.bridge.memory[OracleRAM.MODE] = GameMode.DUNGEON
    client.bridge.memory[OracleRAM.INDOORS] = 1
    assert not client.fly_to(area=0x40, x=1312, y=2688, prefer_rom_warp=use_rom)
    assert client.bridge.writes == []
    client.resume.assert_not_called()


def test_rom_teleport_timeout_never_succeeds_from_nearby_coordinates(client):
    assert not client.warp_to(area=0x40, x=1312, y=2688, use_rom_warp=True, timeout_frames=2)
    assert "without completion acknowledgement" in client.last_error
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_REQUEST) == 0
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_ARM) == 0
    client.pause.assert_called_once()


def test_acknowledged_rom_warp_succeeds_and_restores_pause(client):
    def acknowledge(bridge):
        bridge.memory[OracleRAM.AREA_ID] = bridge.memory[OracleRAM.DBG_WARP_AREA]
        bridge.memory[OracleRAM.DBG_WARP_STATUS] = 3
        bridge.memory[OracleRAM.DBG_WARP_ARM] = 0
        bridge.memory[OracleRAM.DBG_WARP_REQUEST] = 0

    client.bridge.on_frame = acknowledge
    assert client.warp_to(area=0x2A, x=1312, y=2688, use_rom_warp=True, timeout_frames=2)
    assert client.bridge.memory[OracleRAM.AREA_ID] == 0x2A
    client.pause.assert_called_once()


@pytest.mark.parametrize("failure", ["write", "frame", "rom_error"])
def test_failed_rom_request_is_disarmed(client, failure):
    if failure == "write":
        client.bridge.fail_write = OracleRAM.DBG_WARP_REQUEST
    elif failure == "frame":
        client.bridge.run_frames = Mock(side_effect=RuntimeError("connection lost"))
    else:
        client.bridge.on_frame = lambda bridge: bridge.memory.update({OracleRAM.DBG_WARP_ERROR: 3})
    assert not client.warp_to(area=0x2A, x=1312, y=2688, use_rom_warp=True, timeout_frames=2)
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_REQUEST) == 0
    assert client.bridge.read_memory(OracleRAM.DBG_WARP_ARM) == 0
    client.pause.assert_called_once()


def test_existing_rom_request_is_not_overwritten_or_cancelled(client):
    client.bridge.memory[OracleRAM.DBG_WARP_REQUEST] = 1
    assert not client.warp_to(area=0x2A, x=1312, y=2688, use_rom_warp=True)
    assert "already pending" in client.last_error
    assert client.bridge.writes == []
    client.resume.assert_not_called()


@pytest.mark.parametrize("no_rom", [False, True])
def test_cli_disabled_rom_fly_exits_failure_without_success_message(client, monkeypatch, capsys, no_rom):
    monkeypatch.setattr(cli, "OracleDebugClient", lambda: client)
    monkeypatch.setattr(cli, "_preflight_socket", lambda args: None)
    argv = ["mesen2_client.py", "fly", "--area", "0x2A", "--x", "1312", "--y", "2688"]
    if no_rom:
        argv.append("--no-rom")
    monkeypatch.setattr(cli.sys, "argv", argv)
    with pytest.raises(SystemExit) as exc:
        cli.main()
    assert exc.value.code == 2
    output = capsys.readouterr().out
    assert "Fly failed:" in output
    assert "Flyed:" not in output
    assert client.bridge.memory[OracleRAM.AREA_ID] == 0x40
