"""ITEMS entries for Oracle inventory match Oracle master and the panel bridge."""

import re
from pathlib import Path

import pytest

from mesen2_client_lib.constants import ITEMS

REPO_ROOT = Path(__file__).resolve().parents[4]
BRIDGE = REPO_ROOT / "UI" / "Utilities" / "OraclePanelBridge.cs"

# Oracle master Core/sram.asm (ring/Portal Rod cells use the default-on flags).
ORACLE_SRAM = {
    "bottle1": 0x7EF35C,
    "bottle2": 0x7EF35D,
    "bottle3": 0x7EF35E,
    "bottle4": 0x7EF35F,
    "customrods": 0x7EF351,
    "portalrod": 0x7EF3A6,
    "goldstar": 0x7EF3A9,
    "goldstar_inventory_version": 0x7EF3AA,
    "saved_ocarina_song": 0x7EF3AB,
    "rings": 0x7EF3A2,
    "bananas": 0x7EF38B,
    "pineapples": 0x7EF38D,
    "rockmeat": 0x7EF38F,
    "seashells": 0x7EF391,
    "honeycombs": 0x7EF393,
    "dekusticks": 0x7EF395,
    "tinglemaps": 0x7EF396,
    "dungeonscrolls": 0x7EF398,
}

SONGS = {1: "Healing", 2: "Storms", 3: "Soaring", 4: "Time", 5: "Secrets", 6: "Stone"}


def _bridge_preset_addresses():
    text = BRIDGE.read_text(encoding="utf-8")
    block = re.search(r"PresetItemAddresses = new\(\) \{(.*?)\};", text, re.S)
    assert block, "PresetItemAddresses not found"
    return {key: int(addr, 16) for key, addr in re.findall(r'\["(\w+)"\] = (0x[0-9A-Fa-f]+)', block.group(1))}


@pytest.mark.parametrize("key,addr", sorted(ORACLE_SRAM.items()))
def test_item_address_matches_oracle_sram(key, addr):
    assert ITEMS[key][0] == addr


def test_items_match_bridge_preset_addresses():
    if not BRIDGE.exists():
        pytest.skip("panel bridge source not present")
    bridge = _bridge_preset_addresses()
    for key, addr in ORACLE_SRAM.items():
        assert bridge[key] == addr, key
        assert ITEMS[key][0] == bridge[key], key


@pytest.mark.parametrize("key", ["ocarina_song", "saved_ocarina_song"])
def test_song_labels_cover_secrets_and_stone(key):
    labels = ITEMS[key][2]
    for value, name in SONGS.items():
        assert name in labels[value]
    assert max(labels) == 6


def test_flute_progression_reaches_six_songs():
    labels = ITEMS["flute"][2]
    assert max(labels) == 7
    assert labels[6].endswith("Secrets")
    assert labels[7].endswith("Stone")


def test_bottle_contents_include_oracle_values():
    for n in range(1, 5):
        labels = ITEMS[f"bottle{n}"][2]
        assert labels[2] == "Empty"
        assert labels[9] == "Magic Bean"
        assert labels[10] == "Milk"
