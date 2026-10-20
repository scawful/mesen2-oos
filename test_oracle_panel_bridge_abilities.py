"""Offline behavior tests for OraclePanelBridge item grants.

Compiles only UI/Utilities/OraclePanelBridge.cs against in-memory Mesen.Interop
stubs, then drives the public ApplySaveDataChangesAsync entry point used by the
Save Data editor (manual item rows, presets, and WRAM -> SRAM sync).
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BRIDGE = ROOT / "UI" / "Utilities" / "OraclePanelBridge.cs"

BOOTS = 0x7EF355
ABILITY = 0x7EF379
RUPEES = 0x7EF360
ACTIVE_SLOT = 0x701FFE
RUN = 0x04
SAVE_SIZE = 0x500
MIRROR_OFFSET = 0xF00

CSPROJ = """<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net{tfm}</TargetFramework>
    <Nullable>enable</Nullable>
    <NoWarn>$(NoWarn);CS1998</NoWarn>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="{bridge}" />
  </ItemGroup>
</Project>
"""

STUBS = """
using System;
using System.Collections.Generic;

namespace Mesen.Interop
{
	public enum MemoryType { SnesMemory, SnesWorkRam, SnesSaveRam }

	public static class EmuApi
	{
		public static bool IsRunning() => true;
	}

	public static class DebugApi
	{
		public static readonly Dictionary<(MemoryType, uint), byte> Memory = new();
		public static readonly List<(MemoryType Type, uint Address)> Accesses = new();
		public static readonly List<(MemoryType Type, uint Address, int Length)> BlockWrites = new();

		public static (MemoryType, uint) Resolve(MemoryType type, uint address)
		{
			if(type == MemoryType.SnesMemory && address >= 0x7E0000 && address <= 0x7FFFFF) {
				return (MemoryType.SnesWorkRam, address - 0x7E0000);
			}
			return (type, address);
		}

		public static byte Peek(MemoryType type, uint address) => Memory.TryGetValue(Resolve(type, address), out byte v) ? v : (byte)0;

		public static void InitializeDebugger() { }

		public static byte GetMemoryValue(MemoryType type, uint address)
		{
			Accesses.Add((type, address));
			return Peek(type, address);
		}

		public static void SetMemoryValue(MemoryType type, uint address, byte value)
		{
			Accesses.Add((type, address));
			Memory[Resolve(type, address)] = value;
		}

		public static byte[] GetMemoryValues(MemoryType type, uint start, uint end)
		{
			byte[] data = new byte[end - start + 1];
			for(uint i = 0; i < data.Length; i++) {
				data[i] = Peek(type, start + i);
			}
			return data;
		}

		public static void SetMemoryValues(MemoryType type, uint address, byte[] data, int length)
		{
			BlockWrites.Add((type, address, length));
			for(uint i = 0; i < length; i++) {
				Memory[Resolve(type, address + i)] = data[i];
			}
		}
	}
}
"""

DRIVER = """
using Mesen.Interop;
using Mesen.Utilities;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

static class Driver
{
	static void Main(string[] args)
	{
		var scenarios = JsonSerializer.Deserialize<List<Scenario>>(Console.In.ReadToEnd())!;
		var results = new List<object>();
		foreach(Scenario s in scenarios) {
			DebugApi.Memory.Clear();
			DebugApi.Accesses.Clear();
			DebugApi.BlockWrites.Clear();
			foreach(var pair in s.Initial) {
				DebugApi.Memory[DebugApi.Resolve(MemoryType.SnesMemory, Convert.ToUInt32(pair.Key, 16))] = pair.Value;
			}
			var items = s.Items.Select(i => new OraclePanelValueUpdate() { Key = i.Key, Value = i.Value }).ToList();
			OraclePanelApplyResult result = OraclePanelBridge.ApplySaveDataChangesAsync(items, new List<OraclePanelValueUpdate>(), s.Sync, s.Preset).Result!;
			results.Add(new {
				s.Name,
				result.Synced,
				result.SyncSlot,
				Boots = (int)DebugApi.Peek(MemoryType.SnesMemory, 0x7EF355),
				Ability = (int)DebugApi.Peek(MemoryType.SnesMemory, 0x7EF379),
				AbilityDomains = DebugApi.Accesses.Where(a => a.Address == 0x7EF379).Select(a => a.Type.ToString()).Distinct().ToList(),
				BlockWrites = DebugApi.BlockWrites.Select(w => new object[] { w.Type.ToString(), w.Address, w.Length }).ToList(),
				Wram = Block(MemoryType.SnesWorkRam, 0xF000),
				Sram = Block(MemoryType.SnesSaveRam, 0x0000, 0x2000)
			});
		}
		Console.WriteLine(JsonSerializer.Serialize(results));
	}

	static int[] Block(MemoryType type, uint start, int length = 0x500) =>
		Enumerable.Range(0, length).Select(i => (int)DebugApi.Peek(type, start + (uint)i)).ToArray();

	sealed class Item
	{
		public string Key { get; set; } = "";
		public int Value { get; set; }
	}

	sealed class Scenario
	{
		public string Name { get; set; } = "";
		public string? Preset { get; set; }
		public bool Sync { get; set; }
		public List<Item> Items { get; set; } = new();
		public Dictionary<string, byte> Initial { get; set; } = new();
	}
}
"""


def scenario(name, ability, items=(), preset=None, boots=0, sync=False, slot_raw=None):
    initial = {f"{BOOTS:X}": boots, f"{ABILITY:X}": ability, f"{RUPEES:X}": 0x34}
    if slot_raw is not None:
        initial[f"{ACTIVE_SLOT:X}"] = slot_raw & 0xFF
        initial[f"{ACTIVE_SLOT + 1:X}"] = slot_raw >> 8
    return {
        "Name": name,
        "Preset": preset,
        "Sync": sync,
        "Items": [{"Key": k, "Value": v} for k, v in items],
        "Initial": initial,
    }


SCENARIOS = [
    scenario("grant_f8", 0xF8, [("boots", 1)]),
    scenario("remove_fc", 0xFC, [("boots", 0)], boots=1),
    scenario("remove_absent_f8", 0xF8, [("boots", 0)]),
    scenario("grant_preserves_all_bits", 0xFB, [("boots", 1)]),
    scenario("remove_preserves_all_bits", 0xFF, [("boots", 0)], boots=1),
    scenario("unrelated_items_with_run", 0xFC, [("flippers", 0), ("sword", 1), ("rupees", 500)], boots=1),
    scenario("unrelated_items_without_run", 0xF8, [("flippers", 1), ("moonpearl", 1)]),
    scenario("preset_all_items", 0xF8, preset="all_items"),
    scenario("preset_early_game", 0xF8, preset="early_game"),
    scenario("preset_dungeon_ready_no_boots", 0xFC, preset="dungeon_ready", boots=1),
    scenario("preset_clear_progress", 0xFC, preset="clear_progress", boots=1),
    scenario("all_items_then_manual_off", 0xF8, [("boots", 0)], preset="all_items"),
    scenario("clear_progress_then_manual_on", 0xFC, [("boots", 1)], preset="clear_progress", boots=1),
    scenario("manual_on_then_off", 0xF8, [("boots", 1), ("boots", 0)]),
    scenario("manual_off_then_on", 0xFC, [("boots", 0), ("boots", 1)], boots=1),
    scenario("sync_grant_slot2", 0xF8, [("boots", 1)], sync=True, slot_raw=4),
    scenario("sync_clear_progress_slot1", 0xFC, preset="clear_progress", boots=1, sync=True, slot_raw=2),
]


def save_checksum_total(block):
    return sum(block[i] | (block[i + 1] << 8) for i in range(0, SAVE_SIZE, 2)) & 0xFFFF


def _installed_tfm():
    dotnet = shutil.which("dotnet")
    if not dotnet:
        return None
    out = subprocess.run([dotnet, "--list-runtimes"], capture_output=True, text=True).stdout
    versions = [line.split()[1] for line in out.splitlines() if line.startswith("Microsoft.NETCore.App ")]
    if not versions:
        return None
    major, minor = max(tuple(int(p) for p in v.split(".")[:2]) for v in versions)
    return f"{major}.{minor}"


@unittest.skipUnless(_installed_tfm(), "dotnet runtime not available")
class OraclePanelBridgeBootsAbilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory(prefix="oracle_bridge_test_") as tmp:
            work = Path(tmp)
            (work / "BridgeHarness.csproj").write_text(CSPROJ.format(tfm=_installed_tfm(), bridge=BRIDGE))
            (work / "Stubs.cs").write_text(STUBS)
            (work / "Driver.cs").write_text(DRIVER)
            env = dict(os.environ, DOTNET_NOLOGO="1", DOTNET_CLI_TELEMETRY_OPTOUT="1")
            proc = subprocess.run(
                ["dotnet", "run", "--project", str(work), "-v", "q"],
                input=json.dumps(SCENARIOS), capture_output=True, text=True, env=env, timeout=600,
            )
        if proc.returncode != 0:
            raise RuntimeError(f"bridge harness failed:\n{proc.stdout}\n{proc.stderr}")
        cls.results = {r["Name"]: r for r in json.loads(proc.stdout.strip().splitlines()[-1])}

    def assertState(self, name, boots, ability):
        r = self.results[name]
        self.assertEqual((r["Boots"], r["Ability"]), (boots, ability), name)

    def test_manual_boots_grant_sets_run(self):
        self.assertState("grant_f8", 1, 0xFC)

    def test_manual_boots_removal_clears_run(self):
        self.assertState("remove_fc", 0, 0xF8)

    def test_manual_boots_removal_with_run_absent_is_unchanged(self):
        self.assertState("remove_absent_f8", 0, 0xF8)

    def test_boots_updates_preserve_every_other_ability_bit(self):
        self.assertState("grant_preserves_all_bits", 1, 0xFF)
        self.assertState("remove_preserves_all_bits", 0, 0xFB)

    def test_unrelated_item_updates_leave_ability_flags_alone(self):
        for name, ability in (("unrelated_items_with_run", 0xFC), ("unrelated_items_without_run", 0xF8)):
            r = self.results[name]
            self.assertEqual(r["Ability"], ability, name)
            self.assertEqual(r["AbilityDomains"], [], name)

    def test_presets_granting_boots_set_run(self):
        self.assertState("preset_all_items", 1, 0xFC)
        self.assertState("preset_early_game", 1, 0xFC)

    def test_preset_without_boots_key_leaves_boots_and_run(self):
        self.assertState("preset_dungeon_ready_no_boots", 1, 0xFC)

    def test_clear_progress_clears_run(self):
        self.assertState("preset_clear_progress", 0, 0xF8)

    def test_manual_updates_apply_after_preset(self):
        self.assertState("all_items_then_manual_off", 0, 0xF8)
        self.assertState("clear_progress_then_manual_on", 1, 0xFC)

    def test_last_manual_boots_update_wins(self):
        self.assertState("manual_on_then_off", 0, 0xF8)
        self.assertState("manual_off_then_on", 1, 0xFC)

    def test_ability_flags_use_same_memory_domain_as_item_writes(self):
        for name in ("grant_f8", "remove_fc", "preset_all_items", "preset_clear_progress"):
            self.assertEqual(self.results[name]["AbilityDomains"], ["SnesMemory"], name)

    def assertSynced(self, name, slot, boots, ability):
        r = self.results[name]
        self.assertTrue(r["Synced"], name)
        self.assertEqual(r["SyncSlot"], slot, name)
        main = (slot - 1) * SAVE_SIZE
        mirror = main + MIRROR_OFFSET
        self.assertEqual(r["BlockWrites"], [
            ["SnesWorkRam", 0xF000, SAVE_SIZE],
            ["SnesSaveRam", main, SAVE_SIZE],
            ["SnesSaveRam", mirror, SAVE_SIZE],
        ], name)
        wram, sram = r["Wram"], r["Sram"]
        primary = sram[main:main + SAVE_SIZE]
        self.assertEqual(primary, wram, name)
        self.assertEqual(sram[mirror:mirror + SAVE_SIZE], primary, name)
        self.assertEqual(primary[BOOTS - 0x7EF000], boots, name)
        self.assertEqual(primary[ABILITY - 0x7EF000], ability, name)
        self.assertEqual(primary[RUPEES - 0x7EF000], 0x34 if name == "sync_grant_slot2" else 0, name)
        self.assertEqual(save_checksum_total(primary), 0x5A5A, name)
        written = set(range(main, main + SAVE_SIZE)) | set(range(mirror, mirror + SAVE_SIZE))
        self.assertFalse([i for i, v in enumerate(sram) if v and i not in written], name)

    def test_sync_writes_granted_run_to_primary_and_mirror_with_valid_checksum(self):
        self.assertSynced("sync_grant_slot2", 2, 1, 0xFC)

    def test_sync_writes_cleared_run_to_primary_and_mirror_with_valid_checksum(self):
        self.assertSynced("sync_clear_progress_slot1", 1, 0, 0xF8)


if __name__ == "__main__":
    unittest.main()
