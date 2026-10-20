"""Offline behavior tests for OraclePanelBridge item grants.

Compiles only UI/Utilities/OraclePanelBridge.cs against in-memory Mesen.Interop
stubs, then drives the public ApplySaveDataChangesAsync entry point used by the
Save Data editor (manual item rows and presets).
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
RUN = 0x04

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

		public static void InitializeDebugger() { }

		public static byte GetMemoryValue(MemoryType type, uint address)
		{
			Accesses.Add((type, address));
			return Memory.TryGetValue((type, address), out byte value) ? value : (byte)0;
		}

		public static void SetMemoryValue(MemoryType type, uint address, byte value)
		{
			Accesses.Add((type, address));
			Memory[(type, address)] = value;
		}

		public static byte[] GetMemoryValues(MemoryType type, uint start, uint end) => new byte[end - start + 1];

		public static void SetMemoryValues(MemoryType type, uint address, byte[] data, int length) { }
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
			foreach(var pair in s.Initial) {
				DebugApi.Memory[(MemoryType.SnesMemory, Convert.ToUInt32(pair.Key, 16))] = pair.Value;
			}
			var items = s.Items.Select(i => new OraclePanelValueUpdate() { Key = i.Key, Value = i.Value }).ToList();
			OraclePanelBridge.ApplySaveDataChangesAsync(items, new List<OraclePanelValueUpdate>(), false, s.Preset).Wait();
			results.Add(new {
				s.Name,
				Boots = Read(0x7EF355),
				Ability = Read(0x7EF379),
				AbilityDomains = DebugApi.Accesses.Where(a => a.Address == 0x7EF379).Select(a => a.Type.ToString()).Distinct().ToList()
			});
		}
		Console.WriteLine(JsonSerializer.Serialize(results));
	}

	static int Read(uint address) => DebugApi.Memory.TryGetValue((MemoryType.SnesMemory, address), out byte v) ? v : 0;

	sealed class Scenario
	{
		public string Name { get; set; } = "";
		public string? Preset { get; set; }
		public Dictionary<string, int> Items { get; set; } = new();
		public Dictionary<string, byte> Initial { get; set; } = new();
	}
}
"""


def scenario(name, ability, items=None, preset=None, boots=0):
    return {
        "Name": name,
        "Preset": preset,
        "Items": items or {},
        "Initial": {f"{BOOTS:X}": boots, f"{ABILITY:X}": ability},
    }


SCENARIOS = [
    scenario("manual_boots_on", 0x22, items={"boots": 1}),
    scenario("manual_boots_off", 0x22, items={"boots": 0}, boots=1),
    scenario("manual_boots_off_keeps_run", 0x26, items={"boots": 0}, boots=1),
    scenario("manual_flippers_only", 0x00, items={"flippers": 1}),
    scenario("preset_all_items", 0x00, preset="all_items"),
    scenario("preset_early_game", 0x81, preset="early_game"),
    scenario("preset_dungeon_ready", 0x00, preset="dungeon_ready"),
    scenario("preset_clear_progress", 0x26, preset="clear_progress", boots=1),
]


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

    def test_manual_boots_grant_sets_run_and_preserves_other_bits(self):
        r = self.results["manual_boots_on"]
        self.assertEqual(r["Boots"], 1)
        self.assertEqual(r["Ability"], 0x22 | RUN)

    def test_manual_boots_zero_does_not_add_run(self):
        r = self.results["manual_boots_off"]
        self.assertEqual(r["Boots"], 0)
        self.assertEqual(r["Ability"], 0x22)

    def test_manual_boots_zero_does_not_clear_existing_run(self):
        self.assertEqual(self.results["manual_boots_off_keeps_run"]["Ability"], 0x26)

    def test_other_items_do_not_touch_ability_flags(self):
        self.assertEqual(self.results["manual_flippers_only"]["Ability"], 0x00)

    def test_presets_granting_boots_set_run_and_preserve_other_bits(self):
        self.assertEqual(self.results["preset_all_items"]["Boots"], 1)
        self.assertEqual(self.results["preset_all_items"]["Ability"], RUN)
        self.assertEqual(self.results["preset_early_game"]["Boots"], 1)
        self.assertEqual(self.results["preset_early_game"]["Ability"], 0x81 | RUN)

    def test_presets_without_boots_leave_ability_flags_alone(self):
        self.assertEqual(self.results["preset_dungeon_ready"]["Ability"], 0x00)
        r = self.results["preset_clear_progress"]
        self.assertEqual(r["Boots"], 0)
        self.assertEqual(r["Ability"], 0x26)

    def test_ability_flags_use_same_memory_domain_as_item_writes(self):
        for name in ("manual_boots_on", "preset_all_items", "preset_early_game"):
            self.assertEqual(self.results[name]["AbilityDomains"], ["SnesMemory"], name)


if __name__ == "__main__":
    unittest.main()
