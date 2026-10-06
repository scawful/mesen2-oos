"""Sprite readback regressions using literal WRAM snapshots, without an emulator."""

from unittest.mock import Mock

import pytest

from mesen2_client_lib.client import OracleDebugClient
from mesen2_client_lib.constants import WATCH_PROFILES


def client_for_memory(memory):
    # Avoid client initialization, socket discovery, and filesystem state.
    client = OracleDebugClient.__new__(OracleDebugClient)
    client.bridge = Mock()
    client.bridge.read_memory.side_effect = lambda address: memory.get(address, 0)
    return client


@pytest.mark.parametrize("slot", [0, 15])
def test_sprite_fields_use_distinct_vanilla_arrays(slot):
    # Literal addresses are independent of the client constants. Values differ
    # across neighboring arrays so a swapped position or timer cannot pass.
    snapshot = {
        0x7E0D00: 0x65,  # SprY
        0x7E0D10: 0x70,  # SprX
        0x7E0D20: 0x04,  # SprYH
        0x7E0D30: 0x0A,  # SprXH
        0x7E0D80: 0x02,  # SprAction
        0x7E0DB0: 0x07,  # SprMiscB (probe parent)
        0x7E0DC0: 0x31,  # SprGfx, not health
        0x7E0DD0: 0x09,  # SprState
        0x7E0DF0: 0x12,  # SprTimerA
        0x7E0E00: 0x23,  # SprTimerB
        0x7E0E10: 0x34,  # SprTimerC, not timer B
        0x7E0E20: 0x21,  # SprType
        0x7E0E50: 0x08,  # SprHealth
        0x7E0EE0: 0x45,  # SprTimerD
    }
    client = client_for_memory({address + slot: value for address, value in snapshot.items()})

    assert client.get_sprite_slot(slot) == {
        "state": 0x09,
        "x": 0x70,
        "y": 0x65,
        "x_hi": 0x0A,
        "y_hi": 0x04,
        "type": 0x21,
        "action": 0x02,
        "health": 0x08,
        "timer_a": 0x12,
        "timer_b": 0x23,
        "timer_d": 0x45,
        "parent": 0x07,
    }
    client.bridge.write_memory.assert_not_called()


def test_sprite_list_distinguishes_type_state_and_action():
    client = client_for_memory({
        0x7E0DD0: 0x09,  # Slot 0 active, action zero.
        0x7E0E20: 0x21,  # Pull switch.
        0x7E0DD1: 0x09,  # Slot 1 active, same state but different type.
        0x7E0E21: 0x39,  # Zora Baby.
        0x7E0D82: 0x05,  # Slot 2 inactive with stale action data.
        0x7E0DDF: 0x09,  # Type 00 is valid when slot 15 is active.
    })

    sprites = client.get_all_sprites()

    assert [(sprite["slot"], sprite["type"], sprite["state"], sprite["action"])
            for sprite in sprites] == [
        (0, 0x21, 0x09, 0),
        (1, 0x39, 0x09, 0),
        (15, 0, 0x09, 0),
    ]
    client.bridge.write_memory.assert_not_called()


def test_sprite_watch_profile_keeps_state_and_action_separate():
    addresses = {label: address for address, label, _ in WATCH_PROFILES["sprites"]["addresses"]}
    assert addresses == {
        "SprState[0]": 0x7E0DD0,
        "SprState[1]": 0x7E0DD1,
        "SprTimerD[0]": 0x7E0EE0,
        "SprAction[0]": 0x7E0D80,
        "SprType[0]": 0x7E0E20,
    }


def test_active_sprite_list_excludes_inactive_slot_with_stale_type():
    client = client_for_memory({
        0x7E0DD3: 0x00,  # Slot 3 is inactive.
        0x7E0E23: 0x39,  # Its previous type remains in WRAM.
        0x7E0D83: 0x05,  # Its previous action also remains.
    })

    # Direct slot inspection still exposes stale fields for diagnosis.
    assert client.get_sprite_slot(3)["type"] == 0x39
    assert client.get_all_sprites() == []
    client.bridge.write_memory.assert_not_called()
