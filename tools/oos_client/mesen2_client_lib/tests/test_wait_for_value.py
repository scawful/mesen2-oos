"""Bounded semantic memory waits with caller-owned control; no emulator."""
from unittest.mock import Mock

import pytest

from mesen2_client_lib.bridge import MesenBridge
from mesen2_client_lib.client import OracleDebugClient


@pytest.fixture
def client():
    client = OracleDebugClient.__new__(OracleDebugClient)
    client.bridge = Mock(spec=MesenBridge)
    client.read_address = Mock(return_value=0)
    return client


def test_default_uses_legacy_read_without_advance(client):
    assert client.wait_for_value(0x7e00f0, 0)
    client.read_address.assert_called_once_with(0x7e00f0)
    client.bridge.read_block.assert_not_called()


def test_default_mismatch_keeps_polling(client, monkeypatch):
    client.read_address.side_effect = [8, 0]
    sleep = Mock()
    monkeypatch.setattr("mesen2_client_lib.client.time.sleep", sleep)
    assert client.wait_for_value(0x7e00f0, 0, interval=.1)
    sleep.assert_called_once_with(.1)


def test_stale_cache_then_guarded_advances_and_delayed_pause(client, monkeypatch):
    # PAUSE acknowledgement precedes actual pause; first completed tick may
    # still leave the game's NMI cache stale. Every advance stays caller-owned.
    bridge = MesenBridge.__new__(MesenBridge)
    cpu = {"success": True, "data": {"pc": "0x008000", "flags": "0x34", "consoleType": 1}}
    bridge.send_command = Mock(side_effect=[
        cpu, {"success": True}, {"success": True, "data": {"paused": False, "debugging": True}},
        {"success": True, "data": {"paused": True, "debugging": True}},
        cpu, {"success": True}, {"success": True, "data": {"paused": True, "debugging": True}},
    ])
    monkeypatch.setattr("mesen2_client_lib.bridge.time.sleep", lambda _: None)
    client.bridge.read_block.side_effect = [b'\x08', b'\x08', b'\x80']
    advances = []
    def guarded_tick():
        advances.append("health/terrain/global guards")
        assert bridge.pause(wait=True)
    assert client.wait_for_value(0x7e00f0, 0, mask=15, advance=guarded_tick, max_advances=4)
    assert len(advances) == 2
    assert client.bridge.read_block.call_count == 3
    client.read_address.assert_not_called()
    assert [c.args[0] for c in bridge.send_command.call_args_list] == ['CPU', 'PAUSE', 'STATE', 'STATE', 'CPU', 'PAUSE', 'STATE']


@pytest.mark.parametrize("cap", [0, 1, 4])
def test_nonconvergence_never_exceeds_callback_cap(client, cap):
    client.bridge.read_block.return_value = b'\x08'
    advance = Mock()
    assert not client.wait_for_value(0x7e00f0, 0, mask=15, advance=advance, max_advances=cap)
    assert advance.call_count == cap
    assert client.bridge.read_block.call_count == cap + 1


@pytest.mark.parametrize("data", [b'', b'\x00\x00', None, "00"])
def test_bad_read_cannot_become_zero_success_or_advance(client, data):
    client.bridge.read_block.return_value = data
    advance = Mock()
    with pytest.raises(RuntimeError, match="one-byte"):
        client.wait_for_value(0x7e00f0, 0, advance=advance, max_advances=4)
    advance.assert_not_called()


def test_real_bridge_protocol_failure_is_short_read_not_zero_success(client):
    bridge = MesenBridge.__new__(MesenBridge)
    bridge.send_command = Mock(return_value={"success": False, "error": "read failed"})
    client.bridge = bridge
    advance = Mock()
    with pytest.raises(RuntimeError, match="one-byte"):
        client.wait_for_value(0x7e00f0, 0, advance=advance, max_advances=4)
    advance.assert_not_called()
    assert bridge.send_command.call_args.args[0] == 'READBLOCK'


def test_guard_exception_propagates_without_extra_advance(client):
    client.bridge.read_block.return_value = b'\x08'
    advance = Mock(side_effect=RuntimeError("health guard"))
    with pytest.raises(RuntimeError, match="health guard"):
        client.wait_for_value(0x7e00f0, 0, advance=advance, max_advances=4)
    assert advance.call_count == 1
    assert client.bridge.read_block.call_count == 1


def test_protocol_exception_propagates_without_advance(client):
    client.bridge.read_block.side_effect = OSError("socket lost")
    advance = Mock()
    with pytest.raises(OSError, match="socket lost"):
        client.wait_for_value(0x7e00f0, 0, advance=advance, max_advances=4)
    advance.assert_not_called()


def test_false_callback_stops_without_new_read(client):
    client.bridge.read_block.return_value = b'\x08'
    advance = Mock(return_value=False)
    assert not client.wait_for_value(0x7e00f0, 0, advance=advance, max_advances=4)
    assert advance.call_count == client.bridge.read_block.call_count == 1


def test_callback_timeout_stops_before_another_read_or_advance(client, monkeypatch):
    now = [0.0]
    monkeypatch.setattr("mesen2_client_lib.client.time.monotonic", lambda: now[0])
    client.bridge.read_block.return_value = b'\x08'
    advance = Mock(side_effect=lambda: now.__setitem__(0, 1.0))
    assert not client.wait_for_value(0x7e00f0, 0, timeout=1, advance=advance, max_advances=4)
    assert advance.call_count == client.bridge.read_block.call_count == 1


def test_late_read_cannot_succeed_or_advance(client, monkeypatch):
    now = [0.0]
    monkeypatch.setattr("mesen2_client_lib.client.time.monotonic", lambda: now[0])
    def read(*args):
        now[0] = 1.0
        return b'\x00'
    client.bridge.read_block.side_effect = read
    advance = Mock()
    assert not client.wait_for_value(0x7e00f0, 0, timeout=1, advance=advance, max_advances=4)
    advance.assert_not_called()


@pytest.mark.parametrize("kwargs", [
    {"max_advances": 1}, {"advance": lambda: None},
    {"advance": lambda: None, "max_advances": float('inf')},
    {"advance": lambda: None, "max_advances": -1},
    {"advance": lambda: None, "max_advances": True},
    {"mask": 256}, {"mask": 15, "timeout": float('inf')},
])
def test_invalid_advanced_arguments_do_not_read(client, kwargs):
    with pytest.raises(ValueError):
        client.wait_for_value(0x7e00f0, 0, **kwargs)
    client.bridge.read_block.assert_not_called()


def test_initial_mask_match_needs_no_callback(client):
    client.bridge.read_block.return_value = b'\x80'
    advance = Mock()
    assert client.wait_for_value(0x7e00f0, 0, mask=15, advance=advance, max_advances=4)
    advance.assert_not_called()


def test_single_tick_neutral_control_fails_while_bounded_wait_succeeds(client):
    # The first tick ends before a fresh game input-cache sample. Reproduce
    # the prior neutral_check's immediate assertion, then use the SAME model.
    ticks = [0]
    def advance():
        ticks[0] += 1
    def sample(*args):
        return b'\x08' if ticks[0] < 2 else b'\x00'
    client.bridge.read_block.side_effect = sample
    with pytest.raises(AssertionError):
        advance()
        assert sample()[0] & 15 == 0
    assert ticks[0] == 1
    ticks[0] = 0
    assert client.wait_for_value(0x7e00f0, 0, mask=15, advance=advance, max_advances=4)
    assert ticks[0] == 2
