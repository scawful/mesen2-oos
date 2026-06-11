using Mesen.Interop;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;

namespace Mesen.Utilities
{
	public sealed class OraclePanelRuntimeSummary
	{
		public string ModeName { get; init; } = "Unknown";
		public int Submode { get; init; }
		public bool Indoors { get; init; }
		public string MapName { get; init; } = "Unknown";
		public int AreaId { get; init; }
		public string AreaName { get; init; } = "Unknown";
		public int RoomLayout { get; init; }
		public string RoomName { get; init; } = "Unknown";
		public int RoomValue { get; init; }
		public string RoomLabel { get; init; } = "0x00";
		public int DungeonRoom { get; init; }
		public int LinkX { get; init; }
		public int LinkY { get; init; }
		public string LinkDirection { get; init; } = "?";
		public int LinkState { get; init; }
		public string LinkFormName { get; init; } = "Unknown";
		public int Health { get; init; }
		public int MaxHealth { get; init; }
		public int Magic { get; init; }
		public int Rupees { get; init; }
		public int GameState { get; init; }
		public int Crystals { get; init; }
		public int Pendants { get; init; }
	}

	public sealed class OraclePanelToggleOption
	{
		public string Group { get; init; } = "General";
		public string Key { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
		public bool Checked { get; init; }
		public int Value { get; init; }
	}

	public sealed class OraclePanelChoiceValue
	{
		public int Value { get; init; }
		public string Label { get; init; } = string.Empty;
	}

	public sealed class OraclePanelChoiceField
	{
		public string Group { get; init; } = "General";
		public string Key { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
		public int Value { get; init; }
		public List<OraclePanelChoiceValue> Choices { get; init; } = new();
	}

	public sealed class OraclePanelPreset
	{
		public string Id { get; init; } = string.Empty;
		public string Label { get; init; } = string.Empty;
		public string Description { get; init; } = string.Empty;
	}

	public sealed class OraclePanelEditorData
	{
		public OraclePanelRuntimeSummary State { get; init; } = new();
		public List<OraclePanelToggleOption> Items { get; init; } = new();
		public List<OraclePanelToggleOption> Flags { get; init; } = new();
		public List<OraclePanelChoiceField> ItemChoices { get; init; } = new();
		public List<OraclePanelChoiceField> FlagChoices { get; init; } = new();
		public List<OraclePanelPreset> Presets { get; init; } = new();
	}

	public sealed class OraclePanelApplyResult
	{
		public int ChangedItems { get; init; }
		public int ChangedFlags { get; init; }
		public bool Synced { get; init; }
		public int? SyncSlot { get; init; }
		public OraclePanelRuntimeSummary State { get; init; } = new();
	}

	public sealed class OraclePanelValueUpdate
	{
		public string Key { get; init; } = string.Empty;
		public int Value { get; init; }
	}

	public static class OraclePanelBridge
	{
		private const uint ModeAddr = 0x7E0010;
		private const uint SubmodeAddr = 0x7E0011;
		private const uint IndoorsAddr = 0x7E001B;
		private const uint AreaIdAddr = 0x7E008A;
		private const uint RoomLayoutAddr = 0x7E00A0;
		private const uint DungeonRoomAddr = 0x7E048E;
		private const uint LinkYAddr = 0x7E0020;
		private const uint LinkXAddr = 0x7E0022;
		private const uint LinkDirectionAddr = 0x7E002F;
		private const uint LinkStateAddr = 0x7E005D;
		private const uint LinkFormAddr = 0x7E02B2;
		private const uint HealthCurrentAddr = 0x7EF36D;
		private const uint HealthMaxAddr = 0x7EF36C;
		private const uint MagicAddr = 0x7EF36E;
		private const uint RupeesAddr = 0x7EF360;
		private const uint GameStateAddr = 0x7EF3C5;
		private const uint OosProgAddr = 0x7EF3D6;
		private const uint OosProg2Addr = 0x7EF3C6;
		private const uint SideQuestAddr = 0x7EF3D7;
		private const uint CrystalsAddr = 0x7EF37A;
		private const uint PendantsAddr = 0x7EF374;
		private const uint MakuTreeQuestAddr = 0x7EF3D4;
		private const uint DekuQuestDoneAddr = 0x7EF301;
		private const uint ZoraQuestDoneAddr = 0x7EF302;
		private const uint WramSaveStart = 0x7EF000;
		private const int WramSaveSize = 0x0500;
		private const uint ActiveSaveSlotAddr = 0x701FFE;

		private static bool _debuggerInitialized;
		private static readonly object _debuggerLock = new object();

		private sealed class ToggleDef
		{
			public string Group { get; init; } = string.Empty;
			public string Key { get; init; } = string.Empty;
			public string Label { get; init; } = string.Empty;
			public uint Address { get; init; }
		}

		private sealed class ChoiceDef
		{
			public string Group { get; init; } = string.Empty;
			public string Key { get; init; } = string.Empty;
			public string Label { get; init; } = string.Empty;
			public uint Address { get; init; }
			public List<OraclePanelChoiceValue> Choices { get; init; } = new();
		}

		private sealed class FlagBitDef
		{
			public string Group { get; init; } = string.Empty;
			public string Key { get; init; } = string.Empty;
			public string Label { get; init; } = string.Empty;
			public uint Address { get; init; }
			public byte Mask { get; init; }
		}

		private sealed class PresetDef
		{
			public string Id { get; init; } = string.Empty;
			public string Label { get; init; } = string.Empty;
			public string Description { get; init; } = string.Empty;
			public Dictionary<string, int> Items { get; init; } = new();
			public Dictionary<string, int> Flags { get; init; } = new();
		}

		private static readonly Dictionary<int, string> OverworldAreas = new() {
			[0x20] = "Loom Ranch", [0x21] = "Forest", [0x22] = "Fields", [0x23] = "Ranch", [0x24] = "Castle",
			[0x25] = "Zora Temple Area", [0x26] = "East Fields", [0x27] = "Ponds", [0x28] = "Underwater", [0x29] = "Church",
			[0x2A] = "Graveyard", [0x2B] = "Potion Shop", [0x2C] = "West Fields", [0x2D] = "Deku Pond", [0x2E] = "Tail Path",
			[0x2F] = "Maku Tree", [0x30] = "Beach", [0x31] = "Coast", [0x32] = "Mountain Base", [0x33] = "Lanmolas/Loom Beach",
			[0x34] = "South Fields", [0x35] = "Zora Domain", [0x36] = "Goron Desert", [0x37] = "Desert Edge", [0x38] = "Dragon Ship Area",
			[0x39] = "Dragon Ship Path", [0x3A] = "Dragon Ship Dock", [0x3B] = "Pyramid", [0x3C] = "West Coast", [0x3D] = "Volcano",
			[0x3E] = "Ice Mountain", [0x3F] = "Village", [0x40] = "Temporal Pyramid", [0x41] = "Pyramid / Swamp", [0x42] = "Desert",
			[0x43] = "Forest / Mountain Shrines", [0x44] = "Dark Castle", [0x45] = "Dark Temple", [0x46] = "Dark Forest", [0x47] = "Dark Ponds",
			[0x48] = "Dark Underwater", [0x49] = "Dark Church", [0x4A] = "Shrine of Wisdom", [0x4B] = "Shrine of Power", [0x4C] = "Dark West",
			[0x4D] = "Dark Pond", [0x4E] = "Eon Fields", [0x4F] = "Dark Maku", [0x50] = "Shrine of Courage", [0x57] = "Final Area",
			[0x5B] = "DW Church", [0x5E] = "Fortress", [0x70] = "Underwater (Dark)", [0x75] = "Underwater Area", [0x7A] = "Deep Underwater"
		};

		private static readonly Dictionary<int, string> ModeNames = new() {
			[0x00] = "Title/Reset", [0x01] = "File Select", [0x02] = "Copy Player", [0x03] = "Erase Player", [0x04] = "Name Entry",
			[0x05] = "Load File", [0x06] = "Dungeon Load", [0x07] = "Dungeon", [0x08] = "Overworld Load", [0x09] = "Overworld",
			[0x0A] = "Special Area Load", [0x0B] = "Special Area", [0x0E] = "Menu"
		};

		private static readonly Dictionary<int, string> DirectionNames = new() {
			[0] = "Up", [2] = "Down", [4] = "Left", [6] = "Right"
		};

		private static readonly Dictionary<int, string> FormNames = new() {
			[0x00] = "Normal", [0x03] = "Wolf", [0x05] = "Minish", [0x06] = "GBC Link"
		};

		private static readonly List<ToggleDef> ItemToggleDefs = new() {
			new() { Group = "Core Tools", Key = "lamp", Label = "Lamp", Address = 0x7EF34A },
			new() { Group = "Core Tools", Key = "hammer", Label = "Hammer", Address = 0x7EF34B },
			new() { Group = "Core Tools", Key = "firerod", Label = "Fire Rod", Address = 0x7EF345 },
			new() { Group = "Core Tools", Key = "icerod", Label = "Ice Rod", Address = 0x7EF346 },
			new() { Group = "Core Tools", Key = "feather", Label = "Roc's Feather", Address = 0x7EF34D },
			new() { Group = "Core Tools", Key = "book", Label = "Book of Secrets", Address = 0x7EF34E },
			new() { Group = "Core Tools", Key = "somaria", Label = "Cane of Somaria", Address = 0x7EF350 },
			new() { Group = "Masks", Key = "zoramask", Label = "Zora Mask", Address = 0x7EF347 },
			new() { Group = "Masks", Key = "dekumask", Label = "Deku Mask", Address = 0x7EF349 },
			new() { Group = "Masks", Key = "bunnymask", Label = "Bunny Hood", Address = 0x7EF348 },
			new() { Group = "Masks", Key = "stonemask", Label = "Stone Mask", Address = 0x7EF352 },
			new() { Group = "Masks", Key = "wolfmask", Label = "Wolf Mask", Address = 0x7EF358 },
			new() { Group = "Travel / Gear", Key = "boots", Label = "Pegasus Boots", Address = 0x7EF355 },
			new() { Group = "Travel / Gear", Key = "flippers", Label = "Flippers", Address = 0x7EF356 },
			new() { Group = "Travel / Gear", Key = "moonpearl", Label = "Moon Pearl", Address = 0x7EF357 }
		};

		private static readonly List<ChoiceDef> ItemChoiceDefs = new() {
			new() { Group = "Equipment", Key = "sword", Label = "Sword", Address = 0x7EF359, Choices = ChoiceList((0, "None"), (1, "Fighter"), (2, "Master"), (3, "Tempered"), (4, "Golden")) },
			new() { Group = "Equipment", Key = "shield", Label = "Shield", Address = 0x7EF35A, Choices = ChoiceList((0, "None"), (1, "Fighter"), (2, "Fire"), (3, "Mirror")) },
			new() { Group = "Equipment", Key = "armor", Label = "Armor", Address = 0x7EF35B, Choices = ChoiceList((0, "Green"), (1, "Blue"), (2, "Red")) },
			new() { Group = "Equipment", Key = "gloves", Label = "Gloves", Address = 0x7EF354, Choices = ChoiceList((0, "None"), (1, "Power"), (2, "Titan")) },
			new() { Group = "Inventory Upgrades", Key = "bow", Label = "Bow", Address = 0x7EF340, Choices = ChoiceList((0, "None"), (1, "Bow"), (2, "+Arrows"), (3, "Silver"), (4, "Silver+Arrows")) },
			new() { Group = "Inventory Upgrades", Key = "boomerang", Label = "Boomerang", Address = 0x7EF341, Choices = ChoiceList((0, "None"), (1, "Blue"), (2, "Red")) },
			new() { Group = "Inventory Upgrades", Key = "hookshot", Label = "Hookshot", Address = 0x7EF342, Choices = ChoiceList((0, "None"), (1, "Hookshot"), (2, "Goldstar")) },
			new() { Group = "Inventory Upgrades", Key = "mirror", Label = "Mirror", Address = 0x7EF353, Choices = ChoiceList((0, "None"), (1, "Letter"), (2, "Mirror")) },
			new() { Group = "Inventory Upgrades", Key = "flute", Label = "Ocarina Songs", Address = 0x7EF34C, Choices = ChoiceList((0, "None"), (1, "Ocarina"), (2, "Healing"), (3, "Healing + Storms"), (4, "Healing + Storms + Soaring"), (5, "All Songs")) },
			new() { Group = "Inventory Upgrades", Key = "ocarina_song", Label = "Selected Song", Address = 0x7E030F, Choices = ChoiceList((0, "None"), (1, "Healing"), (2, "Storms"), (3, "Soaring"), (4, "Time")) }
		};

		private static readonly List<FlagBitDef> FlagToggleDefs = new() {
			new() { Group = "Story", Key = "intro", Label = "Intro Complete", Address = OosProgAddr, Mask = 0x01 },
			new() { Group = "Story", Key = "hall", Label = "Hall of Secrets", Address = OosProgAddr, Mask = 0x02 },
			new() { Group = "Story", Key = "pendant", Label = "Pendant Quest", Address = OosProgAddr, Mask = 0x04 },
			new() { Group = "Story", Key = "mastersword", Label = "Master Sword", Address = OosProgAddr, Mask = 0x10 },
			new() { Group = "Story", Key = "fortress", Label = "Fortress Complete", Address = OosProgAddr, Mask = 0x80 },
			new() { Group = "Story", Key = "impa", Label = "Impa Intro", Address = OosProg2Addr, Mask = 0x01 },
			new() { Group = "Story", Key = "sanctuary", Label = "Sanctuary Visit", Address = OosProg2Addr, Mask = 0x02 },
			new() { Group = "Story", Key = "kydrog", Label = "Kydrog Encounter", Address = OosProg2Addr, Mask = 0x04 },
			new() { Group = "Story", Key = "bookflag", Label = "Book of Secrets", Address = OosProg2Addr, Mask = 0x20 },
			new() { Group = "Story", Key = "makutree", Label = "Maku Tree Quest", Address = MakuTreeQuestAddr, Mask = 0x01 },
			new() { Group = "Story", Key = "dekuquest", Label = "Deku Quest Done", Address = DekuQuestDoneAddr, Mask = 0x01 },
			new() { Group = "Story", Key = "zoraquest", Label = "Zora Quest Done", Address = ZoraQuestDoneAddr, Mask = 0x01 },
			new() { Group = "Dungeons", Key = "d1", Label = "D1 Mushroom Grotto", Address = CrystalsAddr, Mask = 0x01 },
			new() { Group = "Dungeons", Key = "d2", Label = "D2 Tail Palace", Address = CrystalsAddr, Mask = 0x10 },
			new() { Group = "Dungeons", Key = "d3", Label = "D3 Kalyxo Castle", Address = CrystalsAddr, Mask = 0x40 },
			new() { Group = "Dungeons", Key = "d4", Label = "D4 Zora Temple", Address = CrystalsAddr, Mask = 0x20 },
			new() { Group = "Dungeons", Key = "d5", Label = "D5 Glacia Estate", Address = CrystalsAddr, Mask = 0x04 },
			new() { Group = "Dungeons", Key = "d6", Label = "D6 Goron Mines", Address = CrystalsAddr, Mask = 0x02 },
			new() { Group = "Dungeons", Key = "d7", Label = "D7 Dragon Ship", Address = CrystalsAddr, Mask = 0x08 },
			new() { Group = "Pendants", Key = "wisdom", Label = "Pendant of Wisdom", Address = PendantsAddr, Mask = 0x01 },
			new() { Group = "Pendants", Key = "power", Label = "Pendant of Power", Address = PendantsAddr, Mask = 0x02 },
			new() { Group = "Pendants", Key = "courage", Label = "Pendant of Courage", Address = PendantsAddr, Mask = 0x04 },
			new() { Group = "Side Quests", Key = "masksalesman", Label = "Met Mask Salesman", Address = SideQuestAddr, Mask = 0x01 },
			new() { Group = "Side Quests", Key = "dekufound", Label = "Deku Scrub Found", Address = SideQuestAddr, Mask = 0x04 },
			new() { Group = "Side Quests", Key = "goronquest", Label = "Goron Quest", Address = SideQuestAddr, Mask = 0x20 }
		};

		private static readonly List<ChoiceDef> FlagChoiceDefs = new() {
			new() { Group = "Progress Values", Key = "gamestate", Label = "Game State", Address = GameStateAddr, Choices = ChoiceList((0, "Start"), (1, "Loom Beach"), (2, "Kydrog Complete"), (3, "Farore Rescued")) }
		};

		private static readonly Dictionary<string, uint> PresetItemAddresses = new() {
			["bow"] = 0x7EF340, ["boomerang"] = 0x7EF341, ["hookshot"] = 0x7EF342, ["bombs"] = 0x7EF343, ["powder"] = 0x7EF344,
			["firerod"] = 0x7EF345, ["icerod"] = 0x7EF346, ["zoramask"] = 0x7EF347, ["bunnymask"] = 0x7EF348, ["dekumask"] = 0x7EF349,
			["lamp"] = 0x7EF34A, ["hammer"] = 0x7EF34B, ["flute"] = 0x7EF34C, ["ocarina_song"] = 0x7E030F, ["feather"] = 0x7EF34D,
			["book"] = 0x7EF34E, ["somaria"] = 0x7EF350, ["stonemask"] = 0x7EF352, ["mirror"] = 0x7EF353, ["gloves"] = 0x7EF354,
			["boots"] = 0x7EF355, ["flippers"] = 0x7EF356, ["moonpearl"] = 0x7EF357, ["wolfmask"] = 0x7EF358, ["sword"] = 0x7EF359,
			["shield"] = 0x7EF35A, ["armor"] = 0x7EF35B, ["rupees"] = RupeesAddr, ["health"] = HealthCurrentAddr, ["maxhealth"] = HealthMaxAddr,
			["magic"] = MagicAddr, ["arrows"] = 0x7EF377, ["keys"] = 0x7EF36F
		};

		private static readonly Dictionary<string, FlagBitDef> FlagBitIndex = FlagToggleDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);
		private static readonly Dictionary<string, ChoiceDef> ItemChoiceIndex = ItemChoiceDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);
		private static readonly Dictionary<string, ChoiceDef> FlagChoiceIndex = FlagChoiceDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);

		private static readonly List<PresetDef> Presets = new() {
			new() {
				Id = "all_items",
				Label = "All Items",
				Description = "Full gameplay loadout without story progress bits.",
				Items = new Dictionary<string, int> {
					["bow"] = 4, ["boomerang"] = 2, ["hookshot"] = 2, ["bombs"] = 50, ["powder"] = 2, ["firerod"] = 1,
					["icerod"] = 1, ["lamp"] = 1, ["hammer"] = 1, ["flute"] = 5, ["ocarina_song"] = 4, ["feather"] = 1,
					["book"] = 1, ["somaria"] = 1, ["mirror"] = 2, ["zoramask"] = 1, ["dekumask"] = 1, ["bunnymask"] = 1,
					["stonemask"] = 1, ["wolfmask"] = 1, ["sword"] = 4, ["shield"] = 3, ["armor"] = 2, ["gloves"] = 2,
					["boots"] = 1, ["flippers"] = 1, ["moonpearl"] = 1, ["health"] = 160, ["maxhealth"] = 160, ["magic"] = 128,
					["rupees"] = 9999, ["arrows"] = 70, ["keys"] = 9
				}
			},
			new() {
				Id = "early_game",
				Label = "Early Game",
				Description = "Basic movement and combat kit for quick early-route testing.",
				Items = new Dictionary<string, int> {
					["sword"] = 1, ["shield"] = 1, ["lamp"] = 1, ["boots"] = 1, ["bow"] = 1, ["boomerang"] = 1,
					["bombs"] = 10, ["arrows"] = 15, ["rupees"] = 150, ["health"] = 48, ["maxhealth"] = 48, ["magic"] = 128
				},
				Flags = new Dictionary<string, int> {
					["intro"] = 1, ["impa"] = 1
				}
			},
			new() {
				Id = "dungeon_ready",
				Label = "Dungeon Ready",
				Description = "Mid-progression combat and traversal loadout for dungeon iteration.",
				Items = new Dictionary<string, int> {
					["sword"] = 2, ["shield"] = 2, ["armor"] = 1, ["gloves"] = 1, ["lamp"] = 1, ["feather"] = 1,
					["flippers"] = 1, ["bow"] = 2, ["hookshot"] = 1, ["boomerang"] = 2, ["bombs"] = 20, ["arrows"] = 30,
					["rupees"] = 500, ["health"] = 96, ["maxhealth"] = 96, ["magic"] = 128
				},
				Flags = new Dictionary<string, int> {
					["intro"] = 1, ["impa"] = 1, ["sanctuary"] = 1
				}
			},
			new() {
				Id = "clear_progress",
				Label = "Clear Progress",
				Description = "Strip inventory and story bits back to a clean baseline.",
				Items = new Dictionary<string, int> {
					["bow"] = 0, ["boomerang"] = 0, ["hookshot"] = 0, ["bombs"] = 0, ["powder"] = 0, ["firerod"] = 0,
					["icerod"] = 0, ["lamp"] = 0, ["hammer"] = 0, ["flute"] = 0, ["ocarina_song"] = 0, ["feather"] = 0,
					["book"] = 0, ["somaria"] = 0, ["mirror"] = 0, ["zoramask"] = 0, ["dekumask"] = 0, ["bunnymask"] = 0,
					["stonemask"] = 0, ["wolfmask"] = 0, ["sword"] = 0, ["shield"] = 0, ["armor"] = 0, ["gloves"] = 0,
					["boots"] = 0, ["flippers"] = 0, ["moonpearl"] = 0, ["health"] = 48, ["maxhealth"] = 48, ["magic"] = 128,
					["rupees"] = 0, ["arrows"] = 0, ["keys"] = 0
				},
				Flags = new Dictionary<string, int> {
					["gamestate"] = 0, ["oosprog"] = 0, ["oosprog2"] = 0, ["crystals"] = 0, ["pendants"] = 0, ["sidequest"] = 0,
					["makutree"] = 0, ["dekuquest"] = 0, ["zoraquest"] = 0, ["intro"] = 0, ["hall"] = 0, ["pendant"] = 0,
					["mastersword"] = 0, ["fortress"] = 0, ["impa"] = 0, ["sanctuary"] = 0, ["kydrog"] = 0, ["bookflag"] = 0,
					["d1"] = 0, ["d2"] = 0, ["d3"] = 0, ["d4"] = 0, ["d5"] = 0, ["d6"] = 0, ["d7"] = 0,
					["wisdom"] = 0, ["power"] = 0, ["courage"] = 0, ["masksalesman"] = 0, ["dekufound"] = 0, ["goronquest"] = 0
				}
			}
		};

		public static Task<OraclePanelRuntimeSummary?> FetchRuntimeSummaryAsync()
		{
			return Task.FromResult(ReadRuntimeSummary());
		}

		public static Task<OraclePanelEditorData?> FetchEditorDataAsync()
		{
			if(!CanReadState()) {
				return Task.FromResult<OraclePanelEditorData?>(null);
			}

			OraclePanelRuntimeSummary? summary = ReadRuntimeSummary();
			if(summary == null) {
				return Task.FromResult<OraclePanelEditorData?>(null);
			}

			OraclePanelEditorData data = new OraclePanelEditorData() {
				State = summary,
				Items = ItemToggleDefs.Select(def => new OraclePanelToggleOption() {
					Group = def.Group,
					Key = def.Key,
					Label = def.Label,
					Description = def.Label,
					Value = ReadByte(def.Address),
					Checked = ReadByte(def.Address) != 0
				}).ToList(),
				Flags = FlagToggleDefs.Select(def => {
					byte raw = ReadByte(def.Address);
					return new OraclePanelToggleOption() {
						Group = def.Group,
						Key = def.Key,
						Label = def.Label,
						Description = def.Label,
						Value = raw,
						Checked = (raw & def.Mask) != 0
					};
				}).ToList(),
				ItemChoices = ItemChoiceDefs.Select(def => new OraclePanelChoiceField() {
					Group = def.Group,
					Key = def.Key,
					Label = def.Label,
					Description = def.Label,
					Value = ReadChoiceValue(def.Address),
					Choices = def.Choices
				}).ToList(),
				FlagChoices = FlagChoiceDefs.Select(def => new OraclePanelChoiceField() {
					Group = def.Group,
					Key = def.Key,
					Label = def.Label,
					Description = def.Label,
					Value = ReadByte(def.Address),
					Choices = def.Choices
				}).ToList(),
				Presets = Presets.Select(preset => new OraclePanelPreset() {
					Id = preset.Id,
					Label = preset.Label,
					Description = preset.Description
				}).ToList()
			};

			return Task.FromResult<OraclePanelEditorData?>(data);
		}

		public static Task<OraclePanelApplyResult?> ApplySaveDataChangesAsync(IReadOnlyList<OraclePanelValueUpdate> items, IReadOnlyList<OraclePanelValueUpdate> flags, bool syncToSram, string? presetId = null)
		{
			if(!CanReadState()) {
				return Task.FromResult<OraclePanelApplyResult?>(null);
			}

			int changedItems = 0;
			int changedFlags = 0;
			if(!string.IsNullOrWhiteSpace(presetId)) {
				PresetDef? preset = Presets.FirstOrDefault(p => p.Id == presetId);
				if(preset == null) {
					return Task.FromResult<OraclePanelApplyResult?>(null);
				}
				changedItems += ApplyItemValues(preset.Items.Select(entry => new OraclePanelValueUpdate() { Key = entry.Key, Value = entry.Value }).ToList());
				changedFlags += ApplyFlagValues(preset.Flags.Select(entry => new OraclePanelValueUpdate() { Key = entry.Key, Value = entry.Value }).ToList());
			}

			changedItems += ApplyItemValues(items);
			changedFlags += ApplyFlagValues(flags);

			int? syncedSlot = syncToSram ? SyncWramSaveToSram() : null;
			OraclePanelRuntimeSummary summary = ReadRuntimeSummary() ?? new OraclePanelRuntimeSummary();
			OraclePanelApplyResult result = new OraclePanelApplyResult() {
				ChangedItems = changedItems,
				ChangedFlags = changedFlags,
				Synced = syncedSlot.HasValue,
				SyncSlot = syncedSlot,
				State = summary
			};

			return Task.FromResult<OraclePanelApplyResult?>(result);
		}

		private static bool CanReadState()
		{
			if(!EmuApi.IsRunning()) {
				return false;
			}
			EnsureDebuggerInitialized();
			return true;
		}

		private static void EnsureDebuggerInitialized()
		{
			if(_debuggerInitialized) {
				return;
			}

			lock(_debuggerLock) {
				if(_debuggerInitialized) {
					return;
				}

				DebugApi.InitializeDebugger();
				_debuggerInitialized = true;
			}
		}

		private static OraclePanelRuntimeSummary? ReadRuntimeSummary()
		{
			if(!CanReadState()) {
				return null;
			}

			int mode = ReadByte(ModeAddr);
			int submode = ReadByte(SubmodeAddr);
			bool indoors = ReadByte(IndoorsAddr) != 0;
			int areaId = ReadByte(AreaIdAddr);
			int roomLayout = ReadByte(RoomLayoutAddr);
			int dungeonRoom = ReadWord(DungeonRoomAddr);
			int roomValue = indoors ? dungeonRoom : roomLayout;

			string areaName = OverworldAreas.TryGetValue(areaId, out string? namedArea)
				? namedArea
				: $"Area 0x{areaId:X2}";
			string roomLabel = indoors ? $"0x{roomValue:X4}" : $"0x{roomValue:X2}";
			string roomName = indoors ? ("Room " + roomLabel) : areaName;

			return new OraclePanelRuntimeSummary() {
				ModeName = ModeNames.TryGetValue(mode, out string? modeName) ? modeName : $"0x{mode:X2}",
				Submode = submode,
				Indoors = indoors,
				MapName = indoors ? "Dungeon" : "Overworld",
				AreaId = areaId,
				AreaName = areaName,
				RoomLayout = roomLayout,
				RoomName = roomName,
				RoomValue = roomValue,
				RoomLabel = roomLabel,
				DungeonRoom = dungeonRoom,
				LinkX = ReadWord(LinkXAddr),
				LinkY = ReadWord(LinkYAddr),
				LinkDirection = DirectionNames.TryGetValue(ReadByte(LinkDirectionAddr), out string? directionName) ? directionName : "?",
				LinkState = ReadByte(LinkStateAddr),
				LinkFormName = FormNames.TryGetValue(ReadByte(LinkFormAddr), out string? formName) ? formName : $"0x{ReadByte(LinkFormAddr):X2}",
				Health = ReadByte(HealthCurrentAddr),
				MaxHealth = ReadByte(HealthMaxAddr),
				Magic = ReadByte(MagicAddr),
				Rupees = ReadWord(RupeesAddr),
				GameState = ReadByte(GameStateAddr),
				Crystals = ReadByte(CrystalsAddr),
				Pendants = ReadByte(PendantsAddr)
			};
		}

		private static int ApplyItemValues(IReadOnlyList<OraclePanelValueUpdate> updates)
		{
			int changed = 0;
			foreach(OraclePanelValueUpdate update in updates) {
				if(!PresetItemAddresses.TryGetValue(update.Key, out uint address)) {
					continue;
				}

				WriteValue(address, update.Value, update.Key.Equals("rupees", StringComparison.OrdinalIgnoreCase));
				changed++;
			}
			return changed;
		}

		private static int ApplyFlagValues(IReadOnlyList<OraclePanelValueUpdate> updates)
		{
			int changed = 0;
			foreach(OraclePanelValueUpdate update in updates) {
				if(FlagBitIndex.TryGetValue(update.Key, out FlagBitDef? bitDef)) {
					byte current = ReadByte(bitDef.Address);
					byte next = update.Value != 0 ? (byte)(current | bitDef.Mask) : (byte)(current & ~bitDef.Mask);
					WriteByte(bitDef.Address, next);
					changed++;
					continue;
				}

				if(FlagChoiceIndex.TryGetValue(update.Key, out ChoiceDef? choiceDef)) {
					WriteByte(choiceDef.Address, update.Value);
					changed++;
					continue;
				}

				switch(update.Key.ToLowerInvariant()) {
					case "oosprog":
						WriteByte(OosProgAddr, update.Value);
						changed++;
						break;
					case "oosprog2":
						WriteByte(OosProg2Addr, update.Value);
						changed++;
						break;
					case "crystals":
						WriteByte(CrystalsAddr, update.Value);
						changed++;
						break;
					case "pendants":
						WriteByte(PendantsAddr, update.Value);
						changed++;
						break;
					case "sidequest":
						WriteByte(SideQuestAddr, update.Value);
						changed++;
						break;
					case "makutree":
						WriteByte(MakuTreeQuestAddr, update.Value);
						changed++;
						break;
					case "dekuquest":
						WriteByte(DekuQuestDoneAddr, update.Value);
						changed++;
						break;
					case "zoraquest":
						WriteByte(ZoraQuestDoneAddr, update.Value);
						changed++;
						break;
				}
			}
			return changed;
		}

		private static int? SyncWramSaveToSram()
		{
			byte[] saveBlock = DebugApi.GetMemoryValues(MemoryType.SnesWorkRam, 0xF000, 0xF4FF);
			ApplyInverseChecksum(saveBlock);
			DebugApi.SetMemoryValues(MemoryType.SnesWorkRam, 0xF000, saveBlock, saveBlock.Length);

			int slot = ResolveActiveSaveSlot();
			int mainBase = slot switch {
				1 => 0x0000,
				2 => 0x0500,
				3 => 0x0A00,
				_ => 0x0000
			};
			int mirrorBase = mainBase + 0x0F00;

			DebugApi.SetMemoryValues(MemoryType.SnesSaveRam, (uint)mainBase, saveBlock, saveBlock.Length);
			DebugApi.SetMemoryValues(MemoryType.SnesSaveRam, (uint)mirrorBase, saveBlock, saveBlock.Length);
			return slot;
		}

		private static void ApplyInverseChecksum(byte[] saveBlock)
		{
			UInt16 sum = 0;
			for(int offset = 0; offset < 0x4FE; offset += 2) {
				UInt16 word = (UInt16)(saveBlock[offset] | (saveBlock[offset + 1] << 8));
				sum = (UInt16)((sum + word) & 0xFFFF);
			}

			UInt16 inverse = (UInt16)((0x5A5A - sum) & 0xFFFF);
			saveBlock[0x4FE] = (byte)(inverse & 0xFF);
			saveBlock[0x4FF] = (byte)(inverse >> 8);
		}

		private static int ResolveActiveSaveSlot()
		{
			int raw = ReadWord(ActiveSaveSlotAddr);
			if(raw == 2 || raw == 4 || raw == 6) {
				return raw / 2;
			}
			if(raw == 0) {
				return 1;
			}
			if(raw >= 0 && raw <= 2) {
				return raw + 1;
			}
			return 1;
		}

		private static List<OraclePanelChoiceValue> ChoiceList(params (int Value, string Label)[] choices)
		{
			return choices.Select(choice => new OraclePanelChoiceValue() { Value = choice.Value, Label = choice.Label }).ToList();
		}

		private static int ReadChoiceValue(uint address)
		{
			return address == RupeesAddr ? ReadWord(address) : ReadByte(address);
		}

		private static byte ReadByte(uint address)
		{
			return DebugApi.GetMemoryValue(MemoryType.SnesMemory, address);
		}

		private static int ReadWord(uint address)
		{
			return ReadByte(address) | (ReadByte(address + 1) << 8);
		}

		private static void WriteByte(uint address, int value)
		{
			DebugApi.SetMemoryValue(MemoryType.SnesMemory, address, (byte)(value & 0xFF));
		}

		private static void WriteWord(uint address, int value)
		{
			WriteByte(address, value & 0xFF);
			WriteByte(address + 1, (value >> 8) & 0xFF);
		}

		private static void WriteValue(uint address, int value, bool isWord)
		{
			if(isWord) {
				WriteWord(address, value);
			} else {
				WriteByte(address, value);
			}
		}
	}
}
