"""Offline inventory coverage against a named Oracle source revision.

OOS_SOURCE_ROOT=/path/to/oracle OOS_SOURCE_COMMIT=<sha> \
    python3 -B -m unittest test_oracle_inventory_contract -v

This checks the native preset's data against its ASM consumers. It does not
load Mesen, touch a save, or claim to exercise C# memory-write behavior.
"""

from functools import reduce
import operator
import os
from pathlib import Path
import re
import subprocess
import unittest


def block(text, marker):
    start = text.index("{", text.index(marker))
    depth = 1
    for end in range(start + 1, len(text)):
        depth += (text[end] == "{") - (text[end] == "}")
        if depth == 0:
            return text[start + 1:end]
    raise ValueError(f"Unclosed block: {marker}")


@unittest.skipUnless(os.environ.get("OOS_SOURCE_ROOT") and os.environ.get("OOS_SOURCE_COMMIT"),
                     "Set OOS_SOURCE_ROOT and OOS_SOURCE_COMMIT for the Oracle contract check")
class OracleInventoryContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        def asm(path):
            return subprocess.check_output(
                ["git", "-C", os.environ["OOS_SOURCE_ROOT"], "show",
                 f"{os.environ['OOS_SOURCE_COMMIT']}:{path}"], text=True)

        cls.asm = staticmethod(asm)
        cls.sram = asm("Core/sram.asm")
        cls.symbols = dict(re.findall(r"^(!?\w+)\s*=\s*([^;\n]+)", cls.sram, re.M))
        # The first branch is the current RC relocated ring block; do not use
        # the disabled legacy aliases, which overlap story/collectible bytes.
        rings = asm("Items/magic_rings.asm").split("else", 1)[0]
        cls.symbols.update(re.findall(r"^(\w+)\s*=\s*([^;\n]+)", rings, re.M))
        bridge_path = Path(os.environ.get("OOS_BRIDGE_SOURCE", Path(__file__).parent /
                                          "UI/Utilities/OraclePanelBridge.cs"))
        cls.bridge = bridge_path.read_text()
        constants = dict(re.findall(r"const uint (\w+) = (0x[0-9A-Fa-f]+)", cls.bridge))
        entries = re.findall(r'\["(\w+)"\]\s*=\s*(\w+)',
                             block(cls.bridge, "PresetItemAddresses ="))
        cls.addresses = {key: int(constants.get(value, value), 0) for key, value in entries}
        preset_start = cls.bridge.rfind("new() {", 0, cls.bridge.index('Id = "all_items"'))
        cls.preset = block(cls.bridge[preset_start:], "new()")
        cls.items = {key: int(value, 0) for key, value in
                     re.findall(r'\["(\w+)"\]\s*=\s*(0x[0-9A-Fa-f]+|\d+)', cls.preset)}
        cls.grants = {cls.addresses[key]: value for key, value in cls.items.items()}

    @classmethod
    def address(cls, symbol):
        expression = cls.symbols[symbol].strip()
        if expression.startswith("$"):
            return int(expression[1:], 16)
        if "+" in expression:
            base, offset = expression.split("+")
            return cls.address(base) + int(offset)
        return cls.address(expression)

    def test_all_four_bottle_slots_receive_potions_and_fairy(self):
        names = re.findall(r"^(Bottle\d+)\s*=", self.sram, re.M)
        self.assertEqual(len(names), 4)
        gfx = self.asm("Menu/menu_gfx_table.asm").split("BottlesGFX:", 1)[1].split("SomariaGFX:", 1)[0]
        variants = re.findall(r"^\s*dw [^;]+; ([^\n]+)", gfx, re.M)
        for name, content in zip(names, ["Red potion", "Green potion", "Blue potion", "Fairy"]):
            with self.subTest(name=name):
                # DrawMenuItem uses one-based variants (zero draws NothingGFX).
                value = variants.index(content) + 1
                self.assertEqual(self.grants.get(self.address(name)), value)

    def test_every_magic_bag_slot_gets_a_displayable_count(self):
        menu = self.asm("Menu/menu.asm")
        table = block(menu, "MagicBag_ConsumeItem:").split(".sram_addresses\n")[-1]
        names = re.findall(r"^\s*dd (\w+)", table, re.M)
        self.assertEqual(len(names), 6)
        labels = self.asm("Menu/menu_text.asm").split("TimeLabels:", 1)[1].split("PlaytimeLabel:", 1)[0]
        max_count = max(map(int, re.findall(r'"(\d+)"', labels)))
        for name in names:
            with self.subTest(name=name):
                value = self.grants.get(self.address(name), 0)
                self.assertGreater(value, 0)
                self.assertLessEqual(value, max_count)

    def test_rock_meat_meets_the_normal_goron_dialogue_gate(self):
        gate = block(self.asm("Sprites/NPCs/goron.asm"), "KalyxoGoron_Main:")
        threshold = int(re.search(r"RockMeat.*?CMP\.b #\$([0-9A-F]+)", gate, re.S).group(1), 16)
        self.assertGreaterEqual(self.grants.get(self.address("RockMeat"), 0), threshold)

    def test_split_rods_goldstar_and_saved_song_are_covered(self):
        for name, value in [("CustomRods", 2), ("PortalRodOwned", 1),
                            ("GoldstarOwned", 1), ("SavedOcarinaSong", 4)]:
            with self.subTest(name=name):
                self.assertEqual(self.grants.get(self.address(name)), value)
        migration = block(self.asm("Items/goldstar.asm"), "GoldstarInventory_Migrate:")
        marker = int(re.search(r"GoldstarInventoryVersion : CMP\.b #\$([0-9A-F]+)", migration).group(1), 16)
        self.assertEqual(self.grants.get(self.address("GoldstarInventoryVersion")), marker)
        self.assertEqual(self.grants[self.address("Hookshot")], 1)

    def test_all_ring_map_and_scroll_ownership_bits_are_granted(self):
        ring_table = self.asm("Menu/menu_page3.asm").split("Menu_Page3_RingBits:\n", 1)[1].split("\n\n", 1)[0]
        ring_bits = [int(v, 16) for v in re.findall(r"\$([0-9A-F]+)", ring_table)]
        map_table = block(self.asm("Sprites/NPCs/tingle.asm"), "Tingle_MapSales:").split(".dungeon\n", 1)[1]
        map_bits = [int(v) for v in re.findall(r"\bdb (\d+)", map_table)]
        scroll_bits = [int(v, 16) for v in re.findall(r"^!Scroll_\w+\s*=\s*\$([0-9A-F]+)", self.sram, re.M)]
        for name, bits in [("MAGICRINGS", ring_bits), ("TingleMaps", map_bits),
                           ("DungeonScrolls", scroll_bits)]:
            with self.subTest(name=name):
                self.assertTrue(bits)
                self.assertEqual(self.grants.get(self.address(name)), reduce(operator.or_, bits))

    def test_item_grant_does_not_write_story_or_selection_state(self):
        self.assertNotIn("Flags =", self.preset)
        for name in ["GameState", "StoryProgress", "StoryProgress2", "SideQuestProgress",
                     "SideQuestProgress2", "Crystals", "Pendants", "Dreams", "MagicBeanProgress",
                     "TingleId", "PreviousScroll", "BoundMask", "RingSlot1", "RingSlot2",
                     "RingSlot3", "FOUNDRINGS", "BottleIndex"]:
            with self.subTest(name=name):
                self.assertNotIn(self.address(name), self.grants)
        self.assertFalse(any(0x7EF280 <= addr <= 0x7EF2FF for addr in self.grants))


if __name__ == "__main__":
    unittest.main()
