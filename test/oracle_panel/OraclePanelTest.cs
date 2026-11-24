using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Mesen.Interop;
using Mesen.Utilities;

namespace Mesen.Interop
{
	public enum MemoryType
	{
		SnesMemory,
		SnesWorkRam,
		SnesSaveRam,
		SnesPrgRom
	}

	public static class EmuApi
	{
		public static bool Running { get; set; } = true;
		public static bool Paused { get; set; } = true;
		public static bool IsRunning() => Running;
		public static bool IsPaused() => Paused;
		public static void Pause() => Paused = true;
		public static void Resume() => Paused = false;

		public static bool TryReadGameMemoryByte(MemoryType type, uint offset, out byte value)
		{
			if(type == MemoryType.SnesWorkRam && offset <= 0x1FFFF) {
				value = DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7E0000 + offset);
				return true;
			}
			if(type == MemoryType.SnesSaveRam && offset < DebugApi.Sram.Length) {
				value = DebugApi.Sram[offset];
				return true;
			}
			value = 0;
			return false;
		}
	}

	public static class DebugApi
	{
		public static readonly byte[] Wram = new byte[0x20000];
		public static readonly byte[] Sram = new byte[0x2000];
		public static bool DebuggerRunning { get; set; } = true;

		public static void Clear()
		{
			Array.Clear(Wram);
			Array.Clear(Sram);
		}

		public static bool IsDebuggerRunning() => DebuggerRunning;
		public static void InitializeDebugger() => DebuggerRunning = true;

		public static byte GetMemoryValue(MemoryType type, uint address)
		{
			if(type == MemoryType.SnesSaveRam) {
				return address < Sram.Length ? Sram[address] : (byte)0;
			}
			if(type == MemoryType.SnesWorkRam) {
				return address < Wram.Length ? Wram[address] : (byte)0;
			}
			if(address >= 0x7E0000 && address <= 0x7FFFFF) {
				return Wram[address - 0x7E0000];
			}
			return 0;
		}

		public static void SetMemoryValue(MemoryType type, uint address, byte value)
		{
			if(type == MemoryType.SnesSaveRam) {
				if(address < Sram.Length) Sram[address] = value;
				return;
			}
			if(type == MemoryType.SnesWorkRam) {
				if(address < Wram.Length) Wram[address] = value;
				return;
			}
			if(address >= 0x7E0000 && address <= 0x7FFFFF) {
				Wram[address - 0x7E0000] = value;
			}
		}

		public static byte[] GetMemoryValues(MemoryType type, uint start, uint end)
		{
			byte[] result = new byte[end - start + 1];
			for(uint i = 0; i < result.Length; i++) {
				result[i] = GetMemoryValue(type, start + i);
			}
			return result;
		}

		public static void SetMemoryValues(MemoryType type, uint address, byte[] data, int length)
		{
			for(int i = 0; i < length; i++) {
				SetMemoryValue(type, address + (uint)i, data[i]);
			}
		}
	}
}

namespace Mesen.Utilities
{
	public sealed record OracleActionResult(bool Success, string Message)
	{
		public static OracleActionResult Ok(string message) => new(true, message);
		public static OracleActionResult Fail(string message) => new(false, message);
	}

	public static class AgentLauncher
	{
		public static string? ResolveProjectRoot()
		{
			string? env = Environment.GetEnvironmentVariable("ORACLE_OF_SECRETS_ROOT");
			if(!string.IsNullOrWhiteSpace(env) && Directory.Exists(env)) return env;
			string home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "src", "hobby", "oracle-of-secrets");
			return Directory.Exists(home) ? home : null;
		}
	}

	public static class OracleDebugActions
	{
		public static OracleActionResult FallToRoom(int room) => OracleActionResult.Ok($"fall {room}");
	}
}

internal static class OraclePanelTest
{
	private static int _checks;

	private static void Check(bool condition, string message)
	{
		if(!condition) {
			throw new Exception(message);
		}
		_checks++;
	}

	private static ushort WordSum(byte[] block, int offset)
	{
		int sum = 0;
		for(int i = 0; i < 0x500; i += 2) {
			sum = (sum + block[offset + i] + (block[offset + i + 1] << 8)) & 0xFFFF;
		}
		return (ushort)sum;
	}

	public static int Main()
	{
		DebugApi.Clear();

		// 1. Verify FormNames 0x00..0x0E
		string[] expectedForms = {
			"Normal", "Deku", "Zora", "Wolf", "Bunny",
			"Minish", "GBC Link", "Moosh", "Garo", "Horse",
			"Farore", "Stalfos", "Frog", "Fierce Deity", "Mermaid"
		};
		for(int form = 0; form < expectedForms.Length; form++) {
			DebugApi.SetMemoryValue(MemoryType.SnesMemory, 0x7E02B2, (byte)form);
			OraclePanelRuntimeSummary? summary = OraclePanelBridge.ReadRuntimeSummary();
			Check(summary != null && summary.LinkFormName == expectedForms[form],
				$"Form 0x{form:X2} expected '{expectedForms[form]}', got '{summary?.LinkFormName}'");
		}

		// 2. Verify ReadEditorData exposes all new items, choices, flags, and presets
		DebugApi.Clear();
		OraclePanelEditorData? editor = OraclePanelBridge.ReadEditorData();
		Check(editor != null, "ReadEditorData returned null");
		Check(editor!.Items.Select(i => i.Key).Distinct(StringComparer.OrdinalIgnoreCase).Count() == editor.Items.Count, "Duplicate ItemToggleDefs keys");
		Check(editor.Presets.Select(p => p.Id).Distinct(StringComparer.OrdinalIgnoreCase).Count() == editor.Presets.Count, "Duplicate Preset IDs");
		foreach(string key in new[] { "clockworkbeetle", "switchhook_col21", "lensoftruth", "tempestharp", "stalfosmask", "frogmask", "fiercedeitymask" }) {
			Check(editor!.Items.Any(i => i.Key == key), $"Missing item toggle '{key}'");
		}
		foreach(string key in new[] { "wardrobe_outfit", "ringslot1", "boundmask", "active_form", "keys_1c", "keys_1e" }) {
			Check(editor!.ItemChoices.Any(c => c.Key == key), $"Missing item choice '{key}'");
		}
		OraclePanelChoiceField ringChoice = editor!.ItemChoices.First(c => c.Key == "ringslot1");
		Check(ringChoice.Choices.Any(c => c.Value == 8 && c.Label == "Mana")
			&& ringChoice.Choices.Any(c => c.Value == 9 && c.Label == "Charge")
			&& ringChoice.Choices.Any(c => c.Value == 10 && c.Label == "Whisp"),
			"ringslot1 choices missing Mana/Charge/Whisp");
		foreach(string key in new[] {
			"korl_unlocked", "songbit_healing", "songbit_active", "slingshot_owned", "fullbow_owned", "slingshotbow_init",
			"foundring_mana", "magicring_whisp", "specialtravel_korok", "specialtravel_glacier",
			"abysstravel_pyramid", "abysstravel_beach", "glaciawitches_courtyard", "glaciawitches_mountain",
			"dekuvillage_king", "dekuvillage_cursed"
		}) {
			Check(editor.Flags.Any(f => f.Key == key), $"Missing flag toggle '{key}'");
		}
		foreach(string key in new[] { "eonjabuquest", "jabuwaterlevel", "tradingqueststep", "skysoaring" }) {
			Check(editor.FlagChoices.Any(c => c.Key == key), $"Missing flag choice '{key}'");
		}
		Check(editor.Presets.Any(p => p.Id == "all_items"), "Missing all_items preset");
		Check(editor.Presets.Any(p => p.Id == "fierce_deity_showcase"), "Missing fierce_deity_showcase preset");

		// 3. Verify every single ItemToggle/ItemChoice/FlagToggle/FlagChoice applies via ApplySaveDataChanges
		foreach(OraclePanelToggleOption item in editor.Items) {
			DebugApi.Clear();
			OraclePanelApplyResult? r = OraclePanelBridge.ApplySaveDataChanges(
				new[] { new OraclePanelValueUpdate { Key = item.Key, Value = 1 } },
				Array.Empty<OraclePanelValueUpdate>(), false);
			Check(r != null && r.ChangedItems == 1, $"Item toggle '{item.Key}' did not apply");
		}
		foreach(OraclePanelChoiceField choice in editor.ItemChoices) {
			int target = choice.Choices.Last().Value;
			DebugApi.Clear();
			OraclePanelApplyResult? r = OraclePanelBridge.ApplySaveDataChanges(
				new[] { new OraclePanelValueUpdate { Key = choice.Key, Value = target } },
				Array.Empty<OraclePanelValueUpdate>(), false);
			Check(r != null && r.ChangedItems == 1, $"Item choice '{choice.Key}' did not apply");
		}
		foreach(OraclePanelToggleOption flag in editor.Flags) {
			DebugApi.Clear();
			OraclePanelApplyResult? r = OraclePanelBridge.ApplySaveDataChanges(
				Array.Empty<OraclePanelValueUpdate>(),
				new[] { new OraclePanelValueUpdate { Key = flag.Key, Value = 1 } }, false);
			Check(r != null && r.ChangedFlags == 1, $"Flag toggle '{flag.Key}' did not apply");
		}
		foreach(OraclePanelChoiceField choice in editor.FlagChoices) {
			int target = choice.Choices.Last().Value;
			DebugApi.Clear();
			OraclePanelApplyResult? r = OraclePanelBridge.ApplySaveDataChanges(
				Array.Empty<OraclePanelValueUpdate>(),
				new[] { new OraclePanelValueUpdate { Key = choice.Key, Value = target } }, false);
			Check(r != null && r.ChangedFlags == 1, $"Flag choice '{choice.Key}' did not apply");
		}

		// 4. Verify all_items preset preserves existing bottles/higher counts and sets all 2026-11-23 fields
		DebugApi.Clear();
		DebugApi.SetMemoryValue(MemoryType.SnesSaveRam, 0x1FFE, 2); // Slot 1
		DebugApi.SetMemoryValue(MemoryType.SnesMemory, 0x7EF35C, 8); // Good bee in bottle 1
		DebugApi.SetMemoryValue(MemoryType.SnesMemory, 0x7EF450, 5); // Higher 1C key count
		DebugApi.SetMemoryValue(MemoryType.SnesMemory, 0x7EF3B2, 0x55); // Stale SkySoaring garbage
		OraclePanelApplyResult? allRes = OraclePanelBridge.ApplySaveDataChanges(
			Array.Empty<OraclePanelValueUpdate>(), Array.Empty<OraclePanelValueUpdate>(), true, "all_items");
		Check(allRes != null && allRes.Synced && allRes.SyncSlot == 1, "all_items sync failed");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF35C) == 8, "Bottle 1 filled content overwritten");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF35D) == 4, "Bottle 2 missing not filled");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45A) == 1, "Clockwork Beetle not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45B) == 1, "Switch Hook Col 21 not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45C) == 1, "Lens of Truth not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45D) == 1, "Tempest Harp not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF466) == 1, "Stalfos Mask not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF467) == 1, "Frog Mask not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF468) == 1, "Fierce Deity Mask not set");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF3A8) == 9, "BoundMask != 9");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF469) == 0x07, "FoundRingsEx != 0x07");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF46A) == 0x07, "MagicRingsEx != 0x07");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF3B1) == 0xBF, "SongBits != 0xBF");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF3C3) == 0x83, "SlingshotBowFlags != 0x83");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF438) == 0x7F, "SpecialTravelSpotsVisited != 0x7F");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF439) == 0x01, "KORL_Unlocked != 1");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF43A) == 0xFF, "AbyssTravelSpotsVisited != 0xFF");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF412) == 0xFF, "TravelSpotsVisited != 0xFF");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF3B2) == 0xA5, "SkySoaringLearned != 0xA5");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF450) == 5, "KeysDungeon1C did not preserve higher count");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF451) == 3, "KeysDungeon1E != 3");
		Check(WordSum(DebugApi.Sram, 0x0000) == 0x5A5A, "SRAM primary checksum mismatch");
		Check(WordSum(DebugApi.Sram, 0x0F00) == 0x5A5A, "SRAM mirror checksum mismatch");

		// 5. Verify fierce_deity_showcase preset
		DebugApi.Clear();
		OraclePanelApplyResult? fdRes = OraclePanelBridge.ApplySaveDataChanges(
			Array.Empty<OraclePanelValueUpdate>(), Array.Empty<OraclePanelValueUpdate>(), true, "fierce_deity_showcase");
		Check(fdRes != null && fdRes.Synced, "fierce_deity_showcase sync failed");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF3A8) == 9, "FD showcase BoundMask != 9");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF359) == 4, "FD showcase Sword != 4");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF35A) == 3, "FD showcase Shield != 3");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF35B) == 2, "FD showcase Armor != 2");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF36C) == 160, "FD showcase MaxHealth != 20 hearts");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF36D) == 160, "FD showcase Health != 20 hearts");
		Check(DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45A) == 1 && DebugApi.GetMemoryValue(MemoryType.SnesMemory, 0x7EF45D) == 1, "FD showcase Col 21 items missing");
		Check(WordSum(DebugApi.Sram, 0x0000) == 0x5A5A, "FD showcase SRAM checksum mismatch");

		// 6. Verify OracleCheatFields read/write parity
		DebugApi.Clear();
		foreach(OracleCheatField field in OracleCheatFields.ItemChoices) {
			int val = field.Choices.Last().Value;
			OracleCheatFields.Write(field, val);
			Check(OracleCheatFields.Read(field) == val, $"OracleCheatFields ItemChoice '{field.Label}' round-trip failed");
		}
		foreach(OracleCheatField field in OracleCheatFields.ItemToggles) {
			OracleCheatFields.Write(field, 1);
			Check(OracleCheatFields.Read(field) != 0, $"OracleCheatFields ItemToggle '{field.Label}' write=1 failed");
			OracleCheatFields.Write(field, 0);
			Check(OracleCheatFields.Read(field) == 0, $"OracleCheatFields ItemToggle '{field.Label}' write=0 failed");
		}
		foreach(OracleCheatField field in OracleCheatFields.FlagChoices) {
			int val = field.Choices.Last().Value;
			OracleCheatFields.Write(field, val);
			Check(OracleCheatFields.Read(field) == val, $"OracleCheatFields FlagChoice '{field.Label}' round-trip failed");
		}
		foreach(OracleCheatField field in OracleCheatFields.FlagBits) {
			OracleCheatFields.Write(field, 1);
			Check(OracleCheatFields.Read(field) == 1, $"OracleCheatFields FlagBit '{field.Group}:{field.Label}' set failed");
			OracleCheatFields.Write(field, 0);
			Check(OracleCheatFields.Read(field) == 0, $"OracleCheatFields FlagBit '{field.Group}:{field.Label}' clear failed");
		}
		// Ensure non-Misc FlagBits groups share a single address so group headers read accurately
		foreach(var group in OracleCheatFields.FlagBits.GroupBy(f => f.Group)) {
			if(!group.Key.StartsWith("Misc", StringComparison.Ordinal)) {
				Check(group.Select(f => f.Address).Distinct().Count() == 1,
					$"FlagBits group '{group.Key}' mixes multiple addresses");
			}
		}

		// 7. Verify OracleCheats.GetData parses live Data/debug/{warps,checkpoints}.json when present
		if(AgentLauncher.ResolveProjectRoot() != null) {
			OracleCheatData data = OracleCheats.GetData(forceReload: true);
			Check(data.Error == null, "OracleCheats.GetData error: " + data.Error);
			Check(data.Entrances.Count > 0 && data.Areas.Count > 0 && data.Checkpoints.Count > 0, "OracleCheats.GetData returned empty lists");
		}

		Console.WriteLine($"OraclePanelTest OK ({_checks} checks)");
		return 0;
	}
}
