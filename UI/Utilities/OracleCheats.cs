using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;

namespace Mesen.Utilities
{
	/// <summary>One SRAM field the cheats editor shows: a bit (Mask != 0) or a whole-byte choice.</summary>
	public sealed class OracleCheatField
	{
		public string Group { get; init; } = "";
		public string Label { get; init; } = "";
		public uint Address { get; init; }
		public byte Mask { get; init; }
		/// <summary>For toggles without a mask: the value written when checked (0 when cleared).</summary>
		public int OnValue { get; init; } = 1;
		public (int Value, string Label)[] Choices { get; init; } = Array.Empty<(int, string)>();
		/// <summary>Also OR these bits into AbilityFlags when set (Boots need the Run bit).</summary>
		public byte AbilityBits { get; init; }
		/// <summary>Choice value is hearts: writes value*8 to MaxHealth and CurrentHealth.</summary>
		public bool IsHearts { get; init; }
		public bool IsToggle => Choices.Length == 0;
	}

	/// <summary>Item and flag definitions from oracle-of-secrets Core/sram.asm (line numbers as of 2026-09-26).</summary>
	public static class OracleCheatFields
	{
		private static (int, string)[] C(params (int, string)[] c) => c;

		// Core/sram.asm items/equipment/bottles ($7EF340-$7EF35F), Items/ocarina.asm:437-443 (songs).
		public static readonly IReadOnlyList<OracleCheatField> ItemChoices = new List<OracleCheatField>() {
			new() { Label = "Sword", Address = 0x7EF359, Choices = C((0, "None"), (1, "Lv1"), (2, "Lv2"), (3, "Lv3"), (4, "Lv4 Master")) },
			new() { Label = "Shield", Address = 0x7EF35A, Choices = C((0, "None"), (1, "Fighter"), (2, "Fire"), (3, "Mirror")) },
			new() { Label = "Mail", Address = 0x7EF35B, Choices = C((0, "Green"), (1, "Blue"), (2, "Red")) },
			new() { Label = "Gloves", Address = 0x7EF354, Choices = C((0, "None"), (1, "Power Glove"), (2, "Titan's Mitt")) },
			new() { Label = "Hearts", Address = 0x7EF36C, IsHearts = true, Choices = Enumerable.Range(3, 18).Select(h => (h, $"{h} hearts")).ToArray() },
			new() { Label = "Bow", Address = 0x7EF340, Choices = C((0, "None"), (1, "Bow"), (2, "Bow + arrows"), (3, "Silver"), (4, "Silver + arrows")) },
			new() { Label = "Boomerang", Address = 0x7EF341, Choices = C((0, "None"), (1, "Blue"), (2, "Red")) },
			new() { Label = "Hookshot", Address = 0x7EF342, Choices = C((0, "None"), (1, "Hookshot"), (2, "Goldstar")) },
			new() { Label = "Powder", Address = 0x7EF344, Choices = C((0, "None"), (1, "Mushroom"), (2, "Magic Powder")) },
			new() { Label = "Ocarina", Address = 0x7EF34C, Choices = C((0, "None"), (1, "No songs"), (2, "Healing"), (3, "+ Storms"), (4, "+ Soaring"), (5, "+ Time"), (6, "+ Secrets"), (7, "+ Stone")) },
			new() { Label = "Mirror", Address = 0x7EF353, Choices = C((0, "None"), (1, "Letter"), (2, "Mirror")) },
			new() { Label = "Rod", Address = 0x7EF351, Choices = C((0, "None"), (1, "Fishing Rod"), (2, "Portal Rod")) },
			new() { Label = "Bottle 1", Address = 0x7EF35C, Choices = Bottle() },
			new() { Label = "Bottle 2", Address = 0x7EF35D, Choices = Bottle() },
			new() { Label = "Bottle 3", Address = 0x7EF35E, Choices = Bottle() },
			new() { Label = "Bottle 4", Address = 0x7EF35F, Choices = Bottle() },
		};

		// Vanilla bottle contents (Core/sram.asm Bottles: 0 = no bottle, 2 = empty, 3+ = contents).
		private static (int, string)[] Bottle() => C((0, "No bottle"), (2, "Empty"), (3, "Red potion"), (4, "Green potion"), (5, "Blue potion"), (6, "Fairy"), (7, "Bee"), (8, "Good bee"));

		public static readonly IReadOnlyList<OracleCheatField> ItemToggles = new List<OracleCheatField>() {
			new() { Label = "Flippers", Address = 0x7EF356 },
			new() { Label = "Moon Pearl", Address = 0x7EF357 },
			new() { Label = "Pegasus Boots", Address = 0x7EF355, AbilityBits = 0x04 },
			new() { Label = "Lamp", Address = 0x7EF34A },
			new() { Label = "Hammer", Address = 0x7EF34B },
			new() { Label = "Fire Rod", Address = 0x7EF345 },
			new() { Label = "Ice Rod", Address = 0x7EF346 },
			new() { Label = "Roc's Feather", Address = 0x7EF34D },
			new() { Label = "Book of Secrets", Address = 0x7EF34E },
			new() { Label = "Cane of Somaria", Address = 0x7EF350 },
			new() { Label = "Zora Mask", Address = 0x7EF347 },
			new() { Label = "Deku Mask", Address = 0x7EF349 },
			new() { Label = "Bunny Hood", Address = 0x7EF348 },
			new() { Label = "Stone Mask", Address = 0x7EF352 },
			new() { Label = "Wolf Mask", Address = 0x7EF358 },
		};

		// Core/sram.asm bit constants and addresses (StoryProgress, Crystals, Pendants, SideQuest*, Dreams, EonOwlFlags, ...).
		public static readonly IReadOnlyList<OracleCheatField> FlagChoices = new List<OracleCheatField>() {
			new() { Group = "Story", Label = "GameState", Address = 0x7EF3C5, Choices = C((0, "0 Start"), (1, "1 Loom Beach"), (2, "2 Sent to Abyss"), (3, "3 Farore rescued")) },
			new() { Group = "Story", Label = "MapIcon", Address = 0x7EF3C7, Choices = C((0, "0 Maku Tree"), (1, "1 D1"), (2, "2 D2"), (3, "3 D3"), (4, "4 D4"), (5, "5 D5"), (6, "6 D6"), (7, "7 D7"), (8, "8 Fortress"), (9, "9 Tail Pond")) },
		};

		public static readonly IReadOnlyList<OracleCheatField> FlagBits = new List<OracleCheatField>() {
			Bit("OOSPROG $7EF3D6", "Intro complete", 0x7EF3D6, 0x01),
			Bit("OOSPROG $7EF3D6", "Hall of Secrets", 0x7EF3D6, 0x02),
			Bit("OOSPROG $7EF3D6", "Pendant quest", 0x7EF3D6, 0x04),
			Bit("OOSPROG $7EF3D6", "Elder met", 0x7EF3D6, 0x10),
			Bit("OOSPROG $7EF3D6", "Fortress done", 0x7EF3D6, 0x80),
			Bit("OOSPROG2 $7EF3C6", "Impa intro", 0x7EF3C6, 0x01),
			Bit("OOSPROG2 $7EF3C6", "Sanctuary visit", 0x7EF3C6, 0x02),
			Bit("OOSPROG2 $7EF3C6", "Kydrog met", 0x7EF3C6, 0x04),
			Bit("OOSPROG2 $7EF3C6", "Impa left house", 0x7EF3C6, 0x08),
			Bit("OOSPROG2 $7EF3C6", "House (legacy)", 0x7EF3C6, 0x10),
			Bit("OOSPROG2 $7EF3C6", "Book of Secrets", 0x7EF3C6, 0x20),
			Bit("OOSPROG2 $7EF3C6", "Fortune flip", 0x7EF3C6, 0x40),
			Bit("OOSPROG2 $7EF3C6", "Part 0 storm", 0x7EF3C6, 0x80),
			// D1 = $02, D6 = $01: ROM dungeon IDs $0C/$0E (Docs/Debugging/Issues/world_map_icons_2026-09-26.md 5.0).
			Bit("Crystals $7EF37A", "D1 Mushroom", 0x7EF37A, 0x02),
			Bit("Crystals $7EF37A", "D2 Tail", 0x7EF37A, 0x10),
			Bit("Crystals $7EF37A", "D3 Kalyxo", 0x7EF37A, 0x40),
			Bit("Crystals $7EF37A", "D4 Zora", 0x7EF37A, 0x20),
			Bit("Crystals $7EF37A", "D5 Glacia", 0x7EF37A, 0x04),
			Bit("Crystals $7EF37A", "D6 Goron", 0x7EF37A, 0x01),
			Bit("Crystals $7EF37A", "D7 Dragon", 0x7EF37A, 0x08),
			Bit("Pendants $7EF374", "Wisdom", 0x7EF374, 0x01),
			Bit("Pendants $7EF374", "Power", 0x7EF374, 0x02),
			Bit("Pendants $7EF374", "Courage", 0x7EF374, 0x04),
			Bit("Side quest $7EF3D7", "Mask Salesman met", 0x7EF3D7, 0x01),
			Bit("Side quest $7EF3D7", "Cursed Cucco", 0x7EF3D7, 0x02),
			Bit("Side quest $7EF3D7", "Deku found", 0x7EF3D7, 0x04),
			Bit("Side quest $7EF3D7", "Got mushroom", 0x7EF3D7, 0x08),
			Bit("Side quest $7EF3D7", "Old man mtn.", 0x7EF3D7, 0x10),
			Bit("Side quest $7EF3D7", "Goron quest", 0x7EF3D7, 0x20),
			Bit("Side quest 2 $7EF3D8", "Ranch Girl freed", 0x7EF3D8, 0x01),
			Bit("Side quest 2 $7EF3D8", "Song of Healing", 0x7EF3D8, 0x04),
			Bit("Side quest 2 $7EF3D8", "Fortune shown", 0x7EF3D8, 0x08),
			Bit("Side quest 2 $7EF3D8", "Deku soul freed", 0x7EF3D8, 0x10),
			Bit("Side quest 2 $7EF3D8", "Tingle met", 0x7EF3D8, 0x20),
			Bit("Side quest 2 $7EF3D8", "Beanstalk grown", 0x7EF3D8, 0x40),
			Bit("Dreams $7EF410", "Wisdom", 0x7EF410, 0x01),
			Bit("Dreams $7EF410", "Power", 0x7EF410, 0x02),
			Bit("Dreams $7EF410", "Courage", 0x7EF410, 0x04),
			Bit("Eon Owl $7EF3A7", "Arrival Owl", 0x7EF3A7, 0x01),
			Bit("Eon Owl $7EF3A7", "Sword Owl ($E6)", 0x7EF3A7, 0x02),
			Bit("Misc", "Maku Tree met", 0x7EF3D4, 0x01),
			Bit("Misc", "Kydrog/Farore gone", 0x7EF300, 0x01),
			Bit("Misc", "Deku Mask quest", 0x7EF301, 0x01),
			Bit("Misc", "Zora Mask quest", 0x7EF302, 0x01),
			Bit("Misc", "Zora falls hint", 0x7EF305, 0x01),
			Bit("Misc", "D3 captured", 0x7EF306, 0x01),
			Bit("Misc", "D3 escaped", 0x7EF306, 0x02),
			Bit("Misc", "Water gate $27", 0x7EF411, 0x01),
			Bit("Misc", "Water gate $25", 0x7EF411, 0x02),
		};

		private static OracleCheatField Bit(string group, string label, uint address, byte mask)
		{
			return new OracleCheatField() { Group = group, Label = label, Address = address, Mask = mask };
		}

		public static int Read(OracleCheatField field)
		{
			int raw = OraclePanelBridge.ReadByte(field.Address);
			if(field.IsHearts) {
				return raw / 8;
			}
			return field.Mask != 0 ? (raw & field.Mask) != 0 ? 1 : 0 : raw;
		}

		/// <summary>Write one field to WRAM. Toggles take 0/1, choices take the choice value.</summary>
		public static void Write(OracleCheatField field, int value)
		{
			if(!OraclePanelBridge.CanReadState()) {
				return;
			}
			if(field.IsHearts) {
				OraclePanelBridge.WriteByte(0x7EF36C, value * 8);
				OraclePanelBridge.WriteByte(0x7EF36D, value * 8);
				return;
			}
			if(field.Mask != 0) {
				int raw = OraclePanelBridge.ReadByte(field.Address);
				OraclePanelBridge.WriteByte(field.Address, value != 0 ? raw | field.Mask : raw & ~field.Mask);
				return;
			}
			if(field.IsToggle) {
				OraclePanelBridge.WriteByte(field.Address, value != 0 ? field.OnValue : 0);
				if(value != 0 && field.AbilityBits != 0) {
					OraclePanelBridge.WriteByte(0x7EF379, OraclePanelBridge.ReadByte(0x7EF379) | field.AbilityBits);
				}
				return;
			}
			OraclePanelBridge.WriteByte(field.Address, value);
		}
	}

	public sealed class OracleCheatEntrance
	{
		public int Id { get; init; }
		public string Name { get; init; } = "";
		public string Label { get; init; } = "";
		public string Group { get; init; } = "";
		public int? Room { get; init; }
		public int? Area { get; init; }
		public string AreaName { get; init; } = "";
		public string Exit { get; init; } = "";
	}

	public sealed class OracleCheatArea
	{
		public int Area { get; init; }
		public string Name { get; init; } = "";
		public string World { get; init; } = "";
		public int ViaRoom { get; init; }
		public string Landing { get; init; } = "";
	}

	public sealed class OracleCheckpointWrite
	{
		public uint Address { get; init; }
		public string Op { get; init; } = "value";
		public int Value { get; init; }
		public string Name { get; init; } = "";

		public int Apply(int current)
		{
			return Op switch {
				"set_bits" => (current | Value) & 0xFF,
				"clear_bits" => current & ~Value & 0xFF,
				_ => Value & 0xFF
			};
		}
	}

	public sealed class OracleCheckpoint
	{
		public string Id { get; init; } = "";
		public string Label { get; init; } = "";
		public int Beat { get; init; }
		public string? After { get; init; }
		public int? Entrance { get; init; }
		public int? Area { get; init; }
		public List<int> ExpectModules { get; init; } = new();
		public List<OracleCheckpointWrite> Writes { get; init; } = new();
		public string Notes { get; init; } = "";
		public bool IsTodo { get; init; }
	}

	public sealed class OracleCheatData
	{
		public List<OracleCheatEntrance> Entrances { get; init; } = new();
		public List<OracleCheatArea> Areas { get; init; } = new();
		public List<OracleCheckpoint> Checkpoints { get; init; } = new();
		public string? Error { get; init; }
	}

	/// <summary>
	/// Story checkpoints and warps for Oracle of Secrets, driven by
	/// oracle-of-secrets/Data/debug/warps.json and checkpoints.json (see that README).
	/// Same steps as tools/oracle_cheats/oracle_cheats.py (mesen2_client.py checkpoint / warp-entrance / warp-area).
	/// </summary>
	public static class OracleCheats
	{
		private const uint ModuleAddr = 0x7E0010;
		private const uint SubmoduleAddr = 0x7E0011;
		private const uint BrightnessAddr = 0x7E0013;
		private const uint IndoorsAddr = 0x7E001B;
		private const uint AreaAddr = 0x7E008A;
		private const uint HdmaAddr = 0x7E009B;
		private const uint RoomAddr = 0x7E00A0;
		private const uint SubsubmoduleAddr = 0x7E00B0;
		private const uint ContinueFlagAddr = 0x7E010A;
		private const uint NextModuleAddr = 0x7E010C;
		private const uint EntranceAddr = 0x7E010E;
		private const uint RespawnFlagAddr = 0x7E04AA;
		private const uint FollowerAddr = 0x7EF3CC;
		private const uint SavedWorldAddr = 0x7EF3CA;
		private const uint WorldFlagAddr = 0x7E0FFF;
		private const uint GameStateAddr = 0x7EF3C5;
		private const uint CrystalsAddr = 0x7EF37A;
		private const int ModDungeon = 0x07;
		private const int ModOverworld = 0x09;
		private const int ModSpecial = 0x0B;
		private const int ModMessage = 0x0E;
		// Oracle patches the old-man follower check in Underworld_LoadEntrance to $02
		// (Sprites/NPCs/followers.asm:613); with it the game loads the spawn point instead.
		private const int SpawnFollower = 0x02;
		private static readonly int[] PlayModules = { ModDungeon, ModOverworld, ModSpecial };

		private static OracleCheatData? _data;
		private static DateTime _warpsStamp;
		private static DateTime _checkpointsStamp;

		public static string? DataFolder
		{
			get {
				string? root = AgentLauncher.ResolveProjectRoot();
				return root == null ? null : Path.Combine(root, "Data", "debug");
			}
		}

		/// <summary>Load (or reuse) the JSON data; reloads when either file changed on disk.</summary>
		public static OracleCheatData GetData(bool forceReload = false)
		{
			string? folder = DataFolder;
			if(folder == null) {
				return new OracleCheatData() { Error = "Oracle project root not found (set ORACLE_OF_SECRETS_ROOT)." };
			}
			string warpsPath = Path.Combine(folder, "warps.json");
			string checkpointsPath = Path.Combine(folder, "checkpoints.json");
			if(!File.Exists(warpsPath) || !File.Exists(checkpointsPath)) {
				return new OracleCheatData() { Error = $"Missing {Path.Combine("Data", "debug")}/warps.json or checkpoints.json in the Oracle checkout." };
			}
			DateTime warpsStamp = File.GetLastWriteTimeUtc(warpsPath);
			DateTime checkpointsStamp = File.GetLastWriteTimeUtc(checkpointsPath);
			if(!forceReload && _data != null && warpsStamp == _warpsStamp && checkpointsStamp == _checkpointsStamp) {
				return _data;
			}

			try {
				using JsonDocument warps = JsonDocument.Parse(File.ReadAllText(warpsPath));
				using JsonDocument checkpoints = JsonDocument.Parse(File.ReadAllText(checkpointsPath));
				_data = new OracleCheatData() {
					Entrances = warps.RootElement.GetProperty("entrances").EnumerateArray().Select(e => new OracleCheatEntrance() {
						Id = Hex(e, "id") ?? 0,
						Name = Str(e, "name"),
						Label = Str(e, "label"),
						Group = Str(e, "group"),
						Room = Hex(e, "room"),
						Area = Hex(e, "area"),
						AreaName = Str(e, "area_name"),
						Exit = Str(e, "exit")
					}).ToList(),
					Areas = warps.RootElement.GetProperty("areas").EnumerateArray().Select(a => new OracleCheatArea() {
						Area = Hex(a, "area") ?? 0,
						Name = Str(a, "name"),
						World = Str(a, "world"),
						ViaRoom = Hex(a, "via_room") ?? 0,
						Landing = Str(a, "landing")
					}).ToList(),
					Checkpoints = ParseCheckpoints(checkpoints.RootElement)
				};
				_warpsStamp = warpsStamp;
				_checkpointsStamp = checkpointsStamp;
				return _data;
			} catch(Exception ex) {
				return new OracleCheatData() { Error = "Could not read Data/debug JSON: " + ex.Message };
			}
		}

		private static List<OracleCheckpoint> ParseCheckpoints(JsonElement root)
		{
			List<OracleCheckpoint> list = new();
			foreach(JsonElement c in root.GetProperty("checkpoints").EnumerateArray()) {
				JsonElement loc = c.TryGetProperty("location", out JsonElement l) ? l : default;
				List<int> modules = new();
				if(c.TryGetProperty("expect", out JsonElement expect) && expect.TryGetProperty("modules", out JsonElement mods)) {
					modules = mods.EnumerateArray().Select(m => ParseHex(m.GetString() ?? "0")).ToList();
				}
				List<OracleCheckpointWrite> writes = new();
				if(c.TryGetProperty("writes", out JsonElement ws)) {
					foreach(JsonElement w in ws.EnumerateArray()) {
						string op = w.TryGetProperty("set_bits", out _) ? "set_bits" : w.TryGetProperty("clear_bits", out _) ? "clear_bits" : "value";
						writes.Add(new OracleCheckpointWrite() {
							Address = (uint)(Hex(w, "addr") ?? 0),
							Op = op,
							Value = Hex(w, op) ?? 0,
							Name = Str(w, "name")
						});
					}
				}
				list.Add(new OracleCheckpoint() {
					Id = Str(c, "id"),
					Label = Str(c, "label"),
					Beat = c.TryGetProperty("beat", out JsonElement beat) && beat.ValueKind == JsonValueKind.Number ? beat.GetInt32() : 0,
					After = c.TryGetProperty("after", out JsonElement after) ? after.GetString() : null,
					Entrance = loc.ValueKind == JsonValueKind.Object ? Hex(loc, "entrance") : null,
					Area = loc.ValueKind == JsonValueKind.Object ? Hex(loc, "area") : null,
					ExpectModules = modules,
					Writes = writes,
					Notes = Str(c, "notes")
				});
			}
			if(root.TryGetProperty("todo", out JsonElement todo)) {
				foreach(JsonElement t in todo.EnumerateArray()) {
					list.Add(new OracleCheckpoint() { Id = Str(t, "id"), Label = Str(t, "label"), Beat = t.TryGetProperty("beat", out JsonElement b) ? b.GetInt32() : 0, Notes = Str(t, "reason"), IsTodo = true });
				}
			}
			return list;
		}

		// ------------------------------------------------------------------ warps

		public static OracleActionResult WarpToEntrance(int entranceId, string method = "auto")
		{
			OracleCheatData data = GetData();
			OracleCheatEntrance entrance = data.Entrances.FirstOrDefault(e => e.Id == entranceId)
				?? new OracleCheatEntrance() { Id = entranceId, Label = $"Entrance ${entranceId:X2}", Exit = "unknown" };
			return RunWithEmulation(() => WarpToEntranceCore(data, entrance, method, null));
		}

		public static OracleActionResult WarpToArea(int areaId)
		{
			OracleCheatData data = GetData();
			OracleCheatArea? area = data.Areas.FirstOrDefault(a => a.Area == areaId);
			if(area == null) {
				return OracleActionResult.Fail($"Area ${areaId:X2} has no exit-table room (see Data/debug/warps.json 'areas').");
			}
			return RunWithEmulation(() => WarpToAreaCore(area));
		}

		/// <summary>Apply a checkpoint to WRAM and the active SRAM slot, then warp to its location.</summary>
		public static OracleActionResult ApplyCheckpoint(string id)
		{
			OracleCheatData data = GetData();
			if(data.Error != null) {
				return OracleActionResult.Fail(data.Error);
			}
			OracleCheckpoint? cp = data.Checkpoints.FirstOrDefault(c => c.Id == id);
			if(cp == null || cp.IsTodo) {
				return OracleActionResult.Fail(cp == null ? $"Unknown checkpoint '{id}'." : $"'{id}' is a TODO: {cp.Notes}");
			}
			List<OracleCheckpoint> chain = new();
			for(OracleCheckpoint? cur = cp; cur != null; cur = cur.After == null ? null : data.Checkpoints.FirstOrDefault(c => c.Id == cur.After)) {
				if(chain.Contains(cur)) {
					return OracleActionResult.Fail($"Checkpoint chain loops at '{cur.Id}'.");
				}
				chain.Insert(0, cur);
			}

			return RunWithEmulation(() => {
				OracleActionResult? idle = RequireIdle();
				if(idle != null) {
					return idle;
				}

				// Persist the same world that the subsequent native warp will load.
				// Otherwise a successful checkpoint can reopen in its ancestor's world.
				int? checkpointArea = null;
				if(cp.Entrance is int checkpointEntrance) {
					if(checkpointEntrance < 0 || checkpointEntrance >= 0x85) return OracleActionResult.Fail("Invalid checkpoint entrance.");
					OracleCheatEntrance entry = data.Entrances.FirstOrDefault(e => e.Id == checkpointEntrance)
						?? new OracleCheatEntrance() { Id = checkpointEntrance };
					checkpointArea = EntranceArea(entry, OraclePanelBridge.ReadWord(0x02C813 + (uint)checkpointEntrance * 2));
					if(checkpointArea == null) return OracleActionResult.Fail("Cannot resolve checkpoint entrance in this ROM.");
				} else if(cp.Area is int requestedArea) {
					OracleCheatArea? target = data.Areas.FirstOrDefault(a => a.Area == requestedArea);
					if(target == null || requestedArea < 0 || requestedArea >= 0x80 ||
						UsesCachedExit(target.ViaRoom) || ExitAreaForRoom(target.ViaRoom) != requestedArea) {
						return OracleActionResult.Fail("Checkpoint area does not have a supported matching exit in this ROM.");
					}
					checkpointArea = requestedArea;
				}
				Dictionary<uint, int> expected = new();
				int? slot = null;
				WithPause(() => {
					foreach(OracleCheckpointWrite write in chain.SelectMany(c => c.Writes)) {
						int current = expected.TryGetValue(write.Address, out int v) ? v : OraclePanelBridge.ReadByte(write.Address);
						expected[write.Address] = write.Apply(current);
					}
					foreach(KeyValuePair<uint, int> entry in expected) {
						OraclePanelBridge.WriteByte(entry.Key, entry.Value);
					}
					if(checkpointArea is int destination) PrepareWorld(destination);
					slot = OraclePanelBridge.SyncWramSaveToSram();
				});

				OracleActionResult warp;
				int[] modules = cp.ExpectModules.Count > 0 ? cp.ExpectModules.ToArray() : new[] { ModDungeon };
				if(cp.Entrance.HasValue) {
					OracleCheatEntrance entrance = data.Entrances.FirstOrDefault(e => e.Id == cp.Entrance.Value)
						?? new OracleCheatEntrance() { Id = cp.Entrance.Value, Label = $"Entrance ${cp.Entrance.Value:X2}", Exit = "unknown" };
					// Checkpoint writes may already have changed SavedWorld, so auto cannot
					// infer the loaded graphics' original world. Rebuild table-exit entrances.
					int room = OraclePanelBridge.ReadWord(0x02C813 + (uint)entrance.Id * 2);
					warp = WarpToEntranceCore(data, entrance, UsesCachedExit(room) ? "auto" : "reload", modules);
				} else if(cp.Area.HasValue) {
					OracleCheatArea? area = data.Areas.FirstOrDefault(a => a.Area == cp.Area.Value);
					if(area == null) {
						return OracleActionResult.Fail($"Checkpoint area ${cp.Area.Value:X2} is not in warps.json.");
					}
					// Module08 keeps the HUD hearts and follower graphics of the previous load; a full
					// reload through an entrance first rebuilds both, then the area warp follows.
					OracleActionResult primed = WarpToEntranceCore(data, PrimeEntrance(data, area), "reload", null);
					if(!primed.Success) {
						return OracleActionResult.Fail("Reload step failed: " + primed.Message);
					}
					warp = WarpToAreaCore(area);
				} else {
					warp = OracleActionResult.Ok("no location");
				}
				if(!warp.Success) {
					return warp;
				}

				List<string> problems = new();
				foreach(uint addr in new[] { GameStateAddr, CrystalsAddr }) {
					if(expected.TryGetValue(addr, out int want) && OraclePanelBridge.ReadByte(addr) != want) {
						problems.Add($"${addr:X6} = ${OraclePanelBridge.ReadByte(addr):X2}, expected ${want:X2}");
					}
				}
				string summary = $"Checkpoint '{cp.Label}': {expected.Count} bytes, SRAM slot {slot}; {warp.Message}";
				return problems.Count == 0 ? OracleActionResult.Ok(summary) : OracleActionResult.Fail(summary + " — " + string.Join("; ", problems));
			});
		}

		public static int GetActiveSaveSlot()
		{
			int raw = DebugApi.GetMemoryValue(MemoryType.SnesSaveRam, 0x1FFE) | (DebugApi.GetMemoryValue(MemoryType.SnesSaveRam, 0x1FFF) << 8);
			return raw == 2 || raw == 4 || raw == 6 ? raw / 2 : 1;
		}

		private static OracleCheatEntrance PrimeEntrance(OracleCheatData data, OracleCheatArea area)
		{
			return data.Entrances.FirstOrDefault(e => e.Room == area.ViaRoom)
				?? data.Entrances.FirstOrDefault(e => e.Area == area.Area && e.Exit == "table")
				?? data.Entrances.FirstOrDefault(e => e.Id == ((area.Area & 0x40) != 0 ? 0x76 : 0x01))
				?? new OracleCheatEntrance() { Id = 0x01, Label = "Link's House", Room = 0x104, Exit = "table" };
		}

		// Read the loaded ROM: the catalog labels/room numbers can predate entrance edits.
		private static int? ExitAreaForRoom(int room)
		{
			for(int i = 0x4E; i >= 0; i--) {
				if(OraclePanelBridge.ReadWord(0x02DD8A + (uint)i * 2) == room) {
					return OraclePanelBridge.ReadByte(0x02DE28 + (uint)i);
				}
			}
			return null;
		}

		private static bool UsesCachedExit(int room) => room >= 0x100 && room < 0x180 && room != 0x104;

		private static int? EntranceArea(OracleCheatEntrance entrance, int room)
		{
			if(!UsesCachedExit(room) && ExitAreaForRoom(room) is int exitArea) {
				return exitArea;
			}
			HashSet<int> areas = new();
			for(uint i = 0; i < 0x81; i++) {
				if(OraclePanelBridge.ReadByte(0x1BBB73 + i) == entrance.Id &&
					OraclePanelBridge.ReadWord(0x1BBA71 + i * 2) != 0xFFFF) {
					areas.Add(OraclePanelBridge.ReadWord(0x1BB96F + i * 2));
				}
			}
			// A catalog area is useful only when the current ROM still has that door.
			if(entrance.Area is int known && areas.Contains(known)) return known;
			return areas.Count == 1 ? areas.First() : null;
		}

		private static void PrepareWorld(int area)
		{
			// Special OW/Lyra are separate modes, not another interpretation of bit 6.
			// Their existing entrance paths retain the current saved world.
			if(area < 0 || area >= 0x80) return;
			int world = area & 0x40;
			OraclePanelBridge.WriteByte(SavedWorldAddr, world);
			// Sprite_Main derives this byte; seed it before any transition GBC/actor hook.
			OraclePanelBridge.WriteByte(WorldFlagAddr, world == 0 ? 0 : 1);
		}

		private static OracleActionResult WarpToEntranceCore(OracleCheatData data, OracleCheatEntrance entrance, string method, int[]? modules)
		{
			OracleActionResult? idle = RequireIdle();
			if(idle != null) {
				return idle;
			}
			if(OraclePanelBridge.ReadByte(FollowerAddr) == SpawnFollower) {
				return OracleActionResult.Fail("Follower $02 makes the game load the spawn point instead; clear $7EF3CC first.");
			}

			if(entrance.Id < 0 || entrance.Id >= 0x85) {
				return OracleActionResult.Fail("Entrance must be between $00 and $84.");
			}
			int targetRoom = OraclePanelBridge.ReadWord(0x02C813 + (uint)entrance.Id * 2);
			int? destination = EntranceArea(entrance, targetRoom);
			if(destination is not int destinationArea || targetRoom > 0x01FF) {
				return OracleActionResult.Fail($"Cannot resolve entrance ${entrance.Id:X2} from this ROM's door/exit tables.");
			}
			bool changesWorld = destinationArea < 0x80 && (destinationArea & 0x40) != OraclePanelBridge.ReadByte(SavedWorldAddr);
			int module = OraclePanelBridge.ReadByte(ModuleAddr);
			bool outdoors = OraclePanelBridge.ReadByte(IndoorsAddr) == 0;
			string steps = "";
			if(method == "auto") {
				if(module == ModOverworld && outdoors && !changesWorld &&
					(!UsesCachedExit(targetRoom) || OraclePanelBridge.ReadByte(AreaAddr) == destinationArea)) {
					method = "door";
				} else if(UsesCachedExit(targetRoom)) {
					//Houses ($0100-$017F) exit to the overworld spot saved on entry: stand in that area first.
					//No exit-table room on that area: Loom Beach (Kalyxo) or the Abyss arrival point instead.
					int fallback = (destinationArea & 0x40) != 0 ? 0x40 : 0x33;
					OracleCheatArea? area = data.Areas.FirstOrDefault(a => a.Area == destinationArea)
						?? data.Areas.FirstOrDefault(a => a.Area == fallback);
					if(area == null) {
						return OracleActionResult.Fail($"No overworld area to stand in before entering {entrance.Label}.");
					}
					OracleActionResult step = WarpToAreaCore(area);
					if(!step.Success) {
						return OracleActionResult.Fail("Area step failed: " + step.Message);
					}
					steps = $"area ${area.Area:X2}, then ";
					method = OraclePanelBridge.ReadByte(ModuleAddr) == ModOverworld ? "door" : "reload";
				} else {
					method = "reload";
				}
			}

			// A cross-world table exit also needs the full native sprite-property initialization.
			if(method == "door" && changesWorld && !UsesCachedExit(targetRoom)) method = "reload";
			if(method != "door" && method != "reload") {
				return OracleActionResult.Fail($"Unknown warp method '{method}'.");
			}
			// Explicit cross-world house reloads cannot safely reuse another world's cache.
			if(UsesCachedExit(targetRoom) && destinationArea < 0x80 &&
				(destinationArea & 0x40) != OraclePanelBridge.ReadByte(SavedWorldAddr)) {
				return OracleActionResult.Fail("Cross-world house warps require the auto method to prepare an exit cache.");
			}
			WithPause(() => {
				PrepareWorld(destinationArea);
				OraclePanelBridge.WriteByte(EntranceAddr, entrance.Id);
				OraclePanelBridge.WriteByte(EntranceAddr + 1, 0);
				OraclePanelBridge.WriteByte(RespawnFlagAddr, 0);
				OraclePanelBridge.WriteByte(SubmoduleAddr, 0);
				OraclePanelBridge.WriteByte(SubsubmoduleAddr, 0);
				if(method == "door") {
					//Vanilla door entry (FindEntrance, usdasm bank_1B.asm:1060): Module0F -> Module06, which caches this spot.
					OraclePanelBridge.WriteByte(ContinueFlagAddr, 0);
					OraclePanelBridge.WriteByte(0x7E004D, 0);
					OraclePanelBridge.WriteByte(0x7E0046, 0);
					OraclePanelBridge.WriteByte(NextModuleAddr, 0x06);
					OraclePanelBridge.WriteByte(ModuleAddr, 0x0F);
				} else {
					//Death-continue reload: Module05_LoadFile -> Module06 with $010A set (no overworld recache).
					OraclePanelBridge.WriteByte(ContinueFlagAddr, 1);
					OraclePanelBridge.WriteByte(IndoorsAddr, 1);
					OraclePanelBridge.WriteByte(ModuleAddr, 0x05);
				}
			});

			int[] waitModules = modules ?? new[] { ModDungeon, ModMessage };
			if(!WaitForModules(waitModules, 15000, out int landedModule)) {
				return OracleActionResult.Fail($"{entrance.Label}: no dungeon play after 15 s (module ${OraclePanelBridge.ReadByte(ModuleAddr):X2}/${OraclePanelBridge.ReadByte(SubmoduleAddr):X2}).");
			}
			int room = OraclePanelBridge.ReadWord(RoomAddr);
			if(Array.IndexOf(PlayModules, landedModule) >= 0 && room != targetRoom) {
				return OracleActionResult.Fail($"{entrance.Label}: landed in room ${room:X3}, expected ${targetRoom:X3}.");
			}
			string where = landedModule == ModDungeon || landedModule == ModMessage ? $"room ${room:X3}" : $"module ${landedModule:X2}";
			return OracleActionResult.Ok($"Warped: {entrance.Label} (${entrance.Id:X2}, {steps}{method}) -> {where}.");
		}

		private static OracleActionResult WarpToAreaCore(OracleCheatArea area)
		{
			OracleActionResult? idle = RequireIdle();
			if(idle != null) {
				return idle;
			}
			// Module08 has never supported special OW/Lyra setup; use their dedicated tools.
			if(area.Area < 0 || area.Area >= 0x80) {
				return OracleActionResult.Fail("Special overworld areas require their dedicated loader or checkpoint.");
			}
			if(UsesCachedExit(area.ViaRoom) || ExitAreaForRoom(area.ViaRoom) != area.Area) {
				return OracleActionResult.Fail($"Area ${area.Area:X2} no longer matches room ${area.ViaRoom:X3}'s exit in this ROM; refresh warps.json.");
			}
			//Dungeon-exit load: Module08 reads UnderworldExitData for room $A0.
			WithPause(() => {
				PrepareWorld(area.Area);
				OraclePanelBridge.WriteWord(RoomAddr, area.ViaRoom);
				OraclePanelBridge.WriteByte(ContinueFlagAddr, 0);
				OraclePanelBridge.WriteByte(RespawnFlagAddr, 0);
				OraclePanelBridge.WriteByte(BrightnessAddr, 0x80);
				OraclePanelBridge.WriteByte(HdmaAddr, 0);
				OraclePanelBridge.WriteByte(SubmoduleAddr, 0);
				OraclePanelBridge.WriteByte(SubsubmoduleAddr, 0);
				OraclePanelBridge.WriteByte(ModuleAddr, 0x08);
			});
			if(!WaitForModules(new[] { ModOverworld, ModSpecial, ModMessage }, 15000, out _)) {
				return OracleActionResult.Fail($"Area ${area.Area:X2}: no overworld play after 15 s (module ${OraclePanelBridge.ReadByte(ModuleAddr):X2}).");
			}
			int landed = OraclePanelBridge.ReadByte(AreaAddr);
			if(landed != area.Area) {
				return OracleActionResult.Fail($"Area warp landed in ${landed:X2}, expected ${area.Area:X2}.");
			}
			return OracleActionResult.Ok($"Warped: area ${area.Area:X2} {area.Name}, {area.Landing}.");
		}

		// ------------------------------------------------------------------ helpers

		private static OracleActionResult? RequireIdle()
		{
			int module = OraclePanelBridge.ReadByte(ModuleAddr);
			int submodule = OraclePanelBridge.ReadByte(SubmoduleAddr);
			if(Array.IndexOf(PlayModules, module) < 0 || submodule != 0) {
				return OracleActionResult.Fail($"Needs normal play (module $07/$09/$0B, submodule 0); now ${module:X2}/${submodule:X2}. Close any text box first.");
			}
			return null;
		}

		/// <summary>Poll until the module is in <paramref name="modules"/> (play modules also need submodule 0), stable for 300 ms.</summary>
		private static bool WaitForModules(int[] modules, int timeoutMs, out int module)
		{
			DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
			module = -1;
			while(DateTime.UtcNow < deadline) {
				if(Settled(modules, out module)) {
					Thread.Sleep(300);
					if(Settled(modules, out module)) {
						return true;
					}
				}
				Thread.Sleep(50);
			}
			return false;
		}

		private static bool Settled(int[] modules, out int module)
		{
			module = OraclePanelBridge.ReadByte(ModuleAddr);
			if(Array.IndexOf(modules, module) < 0) {
				return false;
			}
			return Array.IndexOf(PlayModules, module) < 0 || OraclePanelBridge.ReadByte(SubmoduleAddr) == 0;
		}

		/// <summary>Warps need frames to run: resume a paused emulator for the action, pause again after.</summary>
		private static OracleActionResult RunWithEmulation(Func<OracleActionResult> action)
		{
			if(!OraclePanelBridge.CanReadState()) {
				return OracleActionResult.Fail("No game running.");
			}
			bool wasPaused = EmuApi.IsPaused();
			if(wasPaused) {
				EmuApi.Resume();
			}
			try {
				return action();
			} catch(Exception ex) {
				return OracleActionResult.Fail(ex.Message);
			} finally {
				if(wasPaused) {
					EmuApi.Pause();
				}
			}
		}

		/// <summary>Write a batch while paused so no frame sees half of it.</summary>
		private static void WithPause(Action action)
		{
			bool wasPaused = EmuApi.IsPaused();
			if(!wasPaused) {
				EmuApi.Pause();
				for(int i = 0; i < 50 && !EmuApi.IsPaused(); i++) {
					Thread.Sleep(5);
				}
			}
			try {
				action();
			} finally {
				if(!wasPaused) {
					EmuApi.Resume();
				}
			}
		}

		private static string Str(JsonElement e, string name)
		{
			return e.TryGetProperty(name, out JsonElement v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
		}

		private static int? Hex(JsonElement e, string name)
		{
			if(!e.TryGetProperty(name, out JsonElement v)) {
				return null;
			}
			if(v.ValueKind == JsonValueKind.Number) {
				return v.GetInt32();
			}
			return v.ValueKind == JsonValueKind.String ? ParseHex(v.GetString() ?? "") : null;
		}

		public static int ParseHex(string text)
		{
			string t = text.Trim().TrimStart('$');
			if(t.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) {
				t = t.Substring(2);
			}
			return int.Parse(t, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
		}
	}
}
