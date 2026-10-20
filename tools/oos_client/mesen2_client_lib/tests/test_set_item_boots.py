"""Boots ownership/run-ability pairing with a fake bridge; never attach to an emulator."""

import pytest

from mesen2_client_lib.client import OracleDebugClient
from mesen2_client_lib.constants import OracleRAM

BOOTS = OracleRAM.BOOTS
ABILITY = OracleRAM.ABILITY_FLAGS


class ItemBridge:
    def __init__(self, memory=None):
        self.memory = dict(memory or {})
        self.writes = []
        self.fail_write = None
        self.read_result = None
        self.raise_read = False

    def read_block(self, addr, length):
        if self.raise_read:
            raise ConnectionError("Socket error: down")
        if self.read_result is not None:
            return self.read_result
        return bytes(self.memory.get(addr + i, 0) for i in range(length))

    def read_memory(self, addr):
        return self.memory.get(addr, 0)

    def write_memory(self, addr, value):
        self.writes.append((addr, value))
        if addr == self.fail_write:
            return False
        self.memory[addr] = value
        return True

    def write_memory16(self, addr, value):
        self.writes.append((addr, value))
        self.memory[addr] = value
        return True


def make_client(memory=None):
    instance = OracleDebugClient.__new__(OracleDebugClient)
    instance.bridge = ItemBridge(memory)
    instance.last_error = ""
    return instance


def test_boots_on_sets_run_bit_f8_to_fc():
    client = make_client({BOOTS: 0, ABILITY: 0xF8})
    assert client.set_item("boots", 1) is True
    assert client.bridge.writes == [(BOOTS, 1), (ABILITY, 0xFC)]


def test_boots_off_clears_run_bit_fc_to_f8():
    client = make_client({BOOTS: 1, ABILITY: 0xFC})
    assert client.set_item("boots", 0) is True
    assert client.bridge.writes == [(BOOTS, 0), (ABILITY, 0xF8)]


@pytest.mark.parametrize("before", [0x00, 0x03, 0x5A, 0xFB])
def test_boots_on_preserves_other_ability_bits(before):
    client = make_client({ABILITY: before})
    assert client.set_item("boots", 1) is True
    assert client.bridge.memory[ABILITY] == before | 0x04


@pytest.mark.parametrize("before", [0x00, 0x04, 0x5E, 0xFF])
def test_boots_zero_clears_only_run_bit(before):
    client = make_client({ABILITY: before})
    assert client.set_item("boots", 0) is True
    assert client.bridge.memory[ABILITY] == before & ~0x04 & 0xFF


@pytest.mark.parametrize("item", ["flippers", "gloves", "sword", "moonpearl"])
def test_unrelated_items_do_not_touch_ability(item):
    client = make_client({ABILITY: 0xF8})
    assert client.set_item(item, 1) is True
    assert len(client.bridge.writes) == 1
    assert client.bridge.writes[0][0] != ABILITY
    assert client.bridge.memory[ABILITY] == 0xF8


def test_rupees_still_uses_16bit_write():
    client = make_client()
    assert client.set_item("rupees", 0x1234) is True
    assert client.bridge.writes == [(OracleRAM.RUPEES, 0x1234)]


def test_ownership_write_failure_skips_ability_write():
    client = make_client({ABILITY: 0xF8})
    client.bridge.fail_write = BOOTS
    assert client.set_item("boots", 1) is False
    assert client.bridge.writes == [(BOOTS, 1)]
    assert client.bridge.memory[ABILITY] == 0xF8
    assert client.last_error


def test_ability_write_failure_returns_false():
    client = make_client({BOOTS: 0, ABILITY: 0xF8})
    client.bridge.fail_write = ABILITY
    assert client.set_item("boots", 1) is False
    assert client.bridge.memory[ABILITY] == 0xF8
    assert client.last_error


def test_ability_read_empty_returns_false_without_writes():
    client = make_client({BOOTS: 0, ABILITY: 0xF8})
    client.bridge.read_result = b""
    assert client.set_item("boots", 1) is False
    assert client.bridge.writes == []
    assert client.last_error


def test_ability_read_connection_error_returns_false_without_writes():
    client = make_client({BOOTS: 0, ABILITY: 0xF8})
    client.bridge.raise_read = True
    assert client.set_item("boots", 1) is False
    assert client.bridge.writes == []
    assert client.last_error
