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
		private const uint BootsAddr = 0x7EF355;
		private const uint AbilityFlagsAddr = 0x7EF379;
		private const byte AbilityRunBit = 0x04;
		private const uint MakuTreeQuestAddr = 0x7EF3D4;
		private const uint DekuQuestDoneAddr = 0x7EF301;
		private const uint ZoraQuestDoneAddr = 0x7EF302;
		private const uint WramSaveStart = 0x7EF000;
		private const int WramSaveSize = 0x0500;
		private const uint ActiveSaveSlotAddr = 0x701FFE;

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
			[0x00] = "Normal",
			[0x01] = "Deku",
			[0x02] = "Zora",
			[0x03] = "Wolf",
			[0x04] = "Bunny",
			[0x05] = "Minish",
			[0x06] = "GBC Link",
			[0x07] = "Moosh",
			[0x08] = "Garo",
			[0x09] = "Horse",
			[0x0A] = "Farore",
			[0x0B] = "Stalfos",
			[0x0C] = "Frog",
			[0x0D] = "Fierce Deity",
			[0x0E] = "Mermaid"
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
			new() { Group = "Masks", Key = "stalfosmask", Label = "Stalfos Mask", Address = 0x7EF466 },
			new() { Group = "Masks", Key = "frogmask", Label = "Frog Mask", Address = 0x7EF467 },
			new() { Group = "Masks", Key = "fiercedeitymask", Label = "Fierce Deity Mask", Address = 0x7EF468 },
			new() { Group = "Travel / Gear", Key = "boots", Label = "Pegasus Boots", Address = 0x7EF355 },
			new() { Group = "Travel / Gear", Key = "flippers", Label = "Flippers", Address = 0x7EF356 },
			new() { Group = "Travel / Gear", Key = "moonpearl", Label = "Moon Pearl", Address = 0x7EF357 },
			// Oracle ownership bytes: the game treats only exactly 1 as owned (Core/sram.asm).
			new() { Group = "Oracle Items", Key = "byrna", Label = "Cane of Byrna", Address = 0x7EF3AC },
			new() { Group = "Oracle Items", Key = "bombchu", Label = "Bombchu", Address = 0x7EF3AD },
			new() { Group = "Oracle Items", Key = "switchhook", Label = "Switch Hook", Address = 0x7EF3AE },
			new() { Group = "Oracle Items", Key = "garomask", Label = "Garo Mask", Address = 0x7EF3AF },
			new() { Group = "Oracle Items", Key = "portalrod", Label = "Portal Rod", Address = 0x7EF3A6 },
			new() { Group = "Oracle Items", Key = "goldstar", Label = "Goldstar", Address = 0x7EF3A9 },
			new() { Group = "Oracle Items", Key = "fishingrod", Label = "Fishing Rod (Supply)", Address = 0x7EF38A },
			new() { Group = "Col 21 Items", Key = "clockworkbeetle", Label = "Clockwork Beetle", Address = 0x7EF45A },
			new() { Group = "Col 21 Items", Key = "switchhook_col21", Label = "Switch Hook Col 21", Address = 0x7EF45B },
			new() { Group = "Col 21 Items", Key = "lensoftruth", Label = "Lens of Truth", Address = 0x7EF45C },
			new() { Group = "Col 21 Items", Key = "tempestharp", Label = "Tempest Harp", Address = 0x7EF45D }
		};

		private static readonly List<ChoiceDef> ItemChoiceDefs = new() {
			new() { Group = "Equipment", Key = "sword", Label = "Sword", Address = 0x7EF359, Choices = ChoiceList((0, "None"), (1, "Fighter"), (2, "Master"), (3, "Tempered"), (4, "Golden")) },
			new() { Group = "Equipment", Key = "shield", Label = "Shield", Address = 0x7EF35A, Choices = ChoiceList((0, "None"), (1, "Fighter"), (2, "Fire"), (3, "Mirror")) },
			new() { Group = "Equipment", Key = "armor", Label = "Armor", Address = 0x7EF35B, Choices = ChoiceList((0, "Green"), (1, "Blue"), (2, "Red")) },
			new() { Group = "Equipment", Key = "wardrobe_outfit", Label = "Wardrobe Outfit", Address = 0x7EF459, Choices = ChoiceList((0, "0 Default MC"), (1, "1 ALTTP Link"), (2, "2 Parallel Worlds"), (3, "3 Awakening"), (4, "4 NES Link"), (5, "5 TP Zelda"), (6, "6 Classic Zelda"), (7, "7 Tetra"), (8, "8 Tingle")) },
			new() { Group = "Equipment", Key = "gloves", Label = "Gloves", Address = 0x7EF354, Choices = ChoiceList((0, "None"), (1, "Power"), (2, "Titan")) },
			new() { Group = "Equipment", Key = "ringslot1", Label = "Equipped Ring", Address = 0x7EF3A3, Choices = ChoiceList((0, "None"), (2, "Power"), (3, "Armor"), (4, "Heart"), (5, "Light"), (6, "Blast"), (7, "Steadfast"), (8, "Mana"), (9, "Charge"), (10, "Whisp")) },
			new() { Group = "Masks", Key = "boundmask", Label = "Bound Mask", Address = 0x7EF3A8, Choices = ChoiceList((0, "None"), (1, "Deku"), (2, "Zora"), (3, "Wolf"), (4, "Bunny"), (5, "Stone"), (6, "Garo"), (7, "Stalfos"), (8, "Frog"), (9, "Fierce Deity")) },
			new() { Group = "Masks", Key = "active_form", Label = "Active Form", Address = 0x7E02B2, Choices = ChoiceList((0, "$00 Normal"), (1, "$01 Deku"), (2, "$02 Zora"), (3, "$03 Wolf"), (4, "$04 Bunny"), (5, "$05 Minish"), (6, "$06 GBC Link"), (7, "$07 Moosh"), (8, "$08 Garo"), (9, "$09 Horse"), (10, "$0A Farore"), (11, "$0B Stalfos"), (12, "$0C Frog"), (13, "$0D Fierce Deity"), (14, "$0E Mermaid")) },
			new() { Group = "Inventory Upgrades", Key = "bow", Label = "Bow", Address = 0x7EF340, Choices = ChoiceList((0, "None"), (1, "Bow"), (2, "+Arrows"), (3, "Silver"), (4, "Silver+Arrows")) },
			new() { Group = "Inventory Upgrades", Key = "boomerang", Label = "Boomerang", Address = 0x7EF341, Choices = ChoiceList((0, "None"), (1, "Blue"), (2, "Red")) },
			new() { Group = "Inventory Upgrades", Key = "hookshot", Label = "Hookshot", Address = 0x7EF342, Choices = ChoiceList((0, "None"), (1, "Hookshot"), (2, "Goldstar")) },
			new() { Group = "Inventory Upgrades", Key = "mirror", Label = "Mirror", Address = 0x7EF353, Choices = ChoiceList((0, "None"), (1, "Letter"), (2, "Mirror")) },
			new() { Group = "Inventory Upgrades", Key = "flute", Label = "Ocarina Songs", Address = 0x7EF34C, Choices = ChoiceList((0, "None"), (1, "Ocarina"), (2, "Healing"), (3, "Healing + Storms"), (4, "Healing + Storms + Soaring"), (5, "+ Time"), (6, "+ Secrets"), (7, "All Six Songs")) },
			new() { Group = "Inventory Upgrades", Key = "ocarina_song", Label = "Selected Song", Address = 0x7E030F, Choices = ChoiceList((0, "None"), (1, "Healing"), (2, "Storms"), (3, "Soaring"), (4, "Time"), (5, "Secrets"), (6, "Stone")) },
			// The game clears Bombchu ammo above 20 to 0.
			new() { Group = "Oracle Items", Key = "bombchu_ammo", Label = "Bombchus", Address = 0x7EF3B0, Choices = ChoiceList((0, "0"), (5, "5"), (10, "10"), (20, "20")) },
			new() { Group = "Oracle Items", Key = "dekusticks", Label = "Deku Sticks", Address = 0x7EF395, Choices = ChoiceList((0, "0"), (1, "1"), (5, "5"), (10, "10")) },
			new() { Group = "Dungeon Keys", Key = "keys_1c", Label = "Keys Dungeon $1C", Address = 0x7EF450, Choices = ChoiceList(Enumerable.Range(0, 10).Select(k => (k, $"{k}")).ToArray()) },
			new() { Group = "Dungeon Keys", Key = "keys_1e", Label = "Keys Dungeon $1E", Address = 0x7EF451, Choices = ChoiceList(Enumerable.Range(0, 10).Select(k => (k, $"{k}")).ToArray()) }
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
			new() { Group = "Story", Key = "makuseed", Label = "Maku Seed", Address = MakuTreeQuestAddr, Mask = 0x02 },
			new() { Group = "Story", Key = "dekuquest", Label = "Deku Quest Done", Address = DekuQuestDoneAddr, Mask = 0x01 },
			new() { Group = "Story", Key = "zoraquest", Label = "Zora Quest Done", Address = ZoraQuestDoneAddr, Mask = 0x01 },
			new() { Group = "Story", Key = "korl_unlocked", Label = "KORL Boat Unlocked", Address = 0x7EF439, Mask = 0x01 },
			// D1 = $02 and D6 = $01 (ROM dungeon IDs $0C/$0E; oracle-of-secrets Core/sram.asm, fixed 2026-09-26).
			new() { Group = "Dungeons", Key = "d1", Label = "D1 Mushroom Grotto", Address = CrystalsAddr, Mask = 0x02 },
			new() { Group = "Dungeons", Key = "d2", Label = "D2 Tail Palace", Address = CrystalsAddr, Mask = 0x10 },
			new() { Group = "Dungeons", Key = "d3", Label = "D3 Kalyxo Castle", Address = CrystalsAddr, Mask = 0x40 },
			new() { Group = "Dungeons", Key = "d4", Label = "D4 Zora Temple", Address = CrystalsAddr, Mask = 0x20 },
			new() { Group = "Dungeons", Key = "d5", Label = "D5 Glacia Estate", Address = CrystalsAddr, Mask = 0x04 },
			new() { Group = "Dungeons", Key = "d6", Label = "D6 Goron Mines", Address = CrystalsAddr, Mask = 0x01 },
			new() { Group = "Dungeons", Key = "d7", Label = "D7 Dragon Ship", Address = CrystalsAddr, Mask = 0x08 },
			new() { Group = "Dungeons", Key = "d8", Label = "D8 Sky", Address = CrystalsAddr, Mask = 0x80 },
			new() { Group = "Pendants", Key = "wisdom", Label = "Pendant of Wisdom", Address = PendantsAddr, Mask = 0x01 },
			new() { Group = "Pendants", Key = "power", Label = "Pendant of Power", Address = PendantsAddr, Mask = 0x02 },
			new() { Group = "Pendants", Key = "courage", Label = "Pendant of Courage", Address = PendantsAddr, Mask = 0x04 },
			new() { Group = "Side Quests", Key = "masksalesman", Label = "Met Mask Salesman", Address = SideQuestAddr, Mask = 0x01 },
			new() { Group = "Side Quests", Key = "dekufound", Label = "Deku Scrub Found", Address = SideQuestAddr, Mask = 0x04 },
			new() { Group = "Side Quests", Key = "goronquest", Label = "Goron Quest", Address = SideQuestAddr, Mask = 0x20 },
			new() { Group = "Side Quests", Key = "eponasong", Label = "Epona's Song", Address = 0x7EF3D8, Mask = 0x02 },
			new() { Group = "Side Quests", Key = "dekuvillage_king", Label = "Deku Village: Met King", Address = 0x7EF456, Mask = 0x01 },
			new() { Group = "Side Quests", Key = "dekuvillage_trial_active", Label = "Deku Village: Trial Active", Address = 0x7EF456, Mask = 0x02 },
			new() { Group = "Side Quests", Key = "dekuvillage_trial_won", Label = "Deku Village: Trial Won", Address = 0x7EF456, Mask = 0x04 },
			new() { Group = "Side Quests", Key = "dekuvillage_blessed", Label = "Deku Village: King Blessed", Address = 0x7EF456, Mask = 0x08 },
			new() { Group = "Side Quests", Key = "dekuvillage_gift", Label = "Deku Village: Merchant Gift", Address = 0x7EF456, Mask = 0x10 },
			new() { Group = "Side Quests", Key = "dekuvillage_cursed", Label = "Deku Village: Cursed", Address = 0x7EF456, Mask = 0x20 },
			new() { Group = "Side Quests", Key = "glaciawitches_courtyard", Label = "Glacia Witches: Courtyard", Address = 0x7EF483, Mask = 0x01 },
			new() { Group = "Side Quests", Key = "glaciawitches_mountain", Label = "Glacia Witches: Mountain", Address = 0x7EF483, Mask = 0x02 },
			new() { Group = "Songs", Key = "songbit_healing", Label = "Song: Healing", Address = 0x7EF3B1, Mask = 0x01 },
			new() { Group = "Songs", Key = "songbit_storms", Label = "Song: Storms", Address = 0x7EF3B1, Mask = 0x02 },
			new() { Group = "Songs", Key = "songbit_soaring", Label = "Song: Soaring", Address = 0x7EF3B1, Mask = 0x04 },
			new() { Group = "Songs", Key = "songbit_time", Label = "Song: Time", Address = 0x7EF3B1, Mask = 0x08 },
			new() { Group = "Songs", Key = "songbit_secrets", Label = "Song: Secrets", Address = 0x7EF3B1, Mask = 0x10 },
			new() { Group = "Songs", Key = "songbit_epona", Label = "Song: Epona", Address = 0x7EF3B1, Mask = 0x20 },
			new() { Group = "Songs", Key = "songbit_active", Label = "SongBits Active", Address = 0x7EF3B1, Mask = 0x80 },
			new() { Group = "Equipment Flags", Key = "slingshot_owned", Label = "Slingshot Owned", Address = 0x7EF3C3, Mask = 0x01 },
			new() { Group = "Equipment Flags", Key = "fullbow_owned", Label = "Full Bow Owned", Address = 0x7EF3C3, Mask = 0x02 },
			new() { Group = "Equipment Flags", Key = "slingshotbow_init", Label = "SlingshotBow Init", Address = 0x7EF3C3, Mask = 0x80 },
			new() { Group = "Rings Ex", Key = "foundring_mana", Label = "Found: Mana Ring", Address = 0x7EF469, Mask = 0x01 },
			new() { Group = "Rings Ex", Key = "foundring_charge", Label = "Found: Charge Ring", Address = 0x7EF469, Mask = 0x02 },
			new() { Group = "Rings Ex", Key = "foundring_whisp", Label = "Found: Whisp Ring", Address = 0x7EF469, Mask = 0x04 },
			new() { Group = "Rings Ex", Key = "magicring_mana", Label = "Owned: Mana Ring", Address = 0x7EF46A, Mask = 0x01 },
			new() { Group = "Rings Ex", Key = "magicring_charge", Label = "Owned: Charge Ring", Address = 0x7EF46A, Mask = 0x02 },
			new() { Group = "Rings Ex", Key = "magicring_whisp", Label = "Owned: Whisp Ring", Address = 0x7EF46A, Mask = 0x04 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_0", Label = "Spot 0 ($00)", Address = 0x7EF412, Mask = 0x01 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_1", Label = "Spot 1 ($0B)", Address = 0x7EF412, Mask = 0x02 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_2", Label = "Spot 2 ($06)", Address = 0x7EF412, Mask = 0x04 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_3", Label = "Spot 3 ($10)", Address = 0x7EF412, Mask = 0x08 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_4", Label = "Spot 4 ($1E)", Address = 0x7EF412, Mask = 0x10 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_5", Label = "Spot 5 ($38)", Address = 0x7EF412, Mask = 0x20 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_6", Label = "Spot 6 ($2F)", Address = 0x7EF412, Mask = 0x40 },
			new() { Group = "Kalyxo Soaring", Key = "travel_spot_7", Label = "Spot 7 ($36)", Address = 0x7EF412, Mask = 0x80 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_korok", Label = "Korok Cove ($81)", Address = 0x7EF438, Mask = 0x01 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_garrison", Label = "Royal Garrison ($83)", Address = 0x7EF438, Mask = 0x02 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_zoraquay", Label = "Zora Quay ($99)", Address = 0x7EF438, Mask = 0x04 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_goroncoast", Label = "Goron Coast ($B0)", Address = 0x7EF438, Mask = 0x08 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_crescent", Label = "Crescent Isle ($B3)", Address = 0x7EF438, Mask = 0x10 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_courage", Label = "Courage Isle ($A6)", Address = 0x7EF438, Mask = 0x20 },
			new() { Group = "Lyra Sea Soaring", Key = "specialtravel_glacier", Label = "Frost Glacier ($BE)", Address = 0x7EF438, Mask = 0x40 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_pyramid", Label = "Temporal Pyramid ($40)", Address = 0x7EF43A, Mask = 0x01 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_windmill", Label = "Abyss Windmill ($63)", Address = 0x7EF43A, Mask = 0x02 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_fortress", Label = "Fortress Secrets ($4B)", Address = 0x7EF43A, Mask = 0x04 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_twinbros", Label = "Twin Brothers ($56)", Address = 0x7EF43A, Mask = 0x08 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_courage", Label = "Shrine Courage ($50)", Address = 0x7EF43A, Mask = 0x10 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_lupo", Label = "Lupo Temple ($5E)", Address = 0x7EF43A, Mask = 0x20 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_dreams", Label = "Forest of Dreams ($58)", Address = 0x7EF43A, Mask = 0x40 },
			new() { Group = "Abyss Soaring", Key = "abysstravel_beach", Label = "Abyss Beach ($76)", Address = 0x7EF43A, Mask = 0x80 },
			new() { Group = "Eon Owl", Key = "eonowl_arrival", Label = "Arrival Owl", Address = 0x7EF3A7, Mask = 0x01 },
			new() { Group = "Eon Owl", Key = "eonowl_sword", Label = "Sword Owl ($E6)", Address = 0x7EF3A7, Mask = 0x02 },
			new() { Group = "Eon Owl", Key = "eonowl_kaepora", Label = "Kaepora ($0E)", Address = 0x7EF3A7, Mask = 0x04 },
			new() { Group = "Eon Owl", Key = "eonowl_soaring", Label = "Soaring Owl ($63)", Address = 0x7EF3A7, Mask = 0x08 }
		};

		private static readonly List<ChoiceDef> FlagChoiceDefs = new() {
			new() { Group = "Progress Values", Key = "gamestate", Label = "Game State", Address = GameStateAddr, Choices = ChoiceList((0, "Start"), (1, "Loom Beach"), (2, "Kydrog Complete"), (3, "Farore Rescued")) },
			new() { Group = "Progress Values", Key = "mapicon", Label = "Map Icon", Address = 0x7EF3C7, Choices = ChoiceList((0, "0 Maku Tree"), (1, "1 D1"), (2, "2 D2"), (3, "3 D3"), (4, "4 D4"), (5, "5 D5"), (6, "6 D6"), (7, "7 D7"), (8, "8 Fortress"), (9, "9 Tail Pond")) },
			new() { Group = "Progress Values", Key = "eonjabuquest", Label = "Eon Jabu Quest", Address = 0x7EF481, Choices = ChoiceList((0x00, "00 None"), (0xA1, "$A1 Heard"), (0xA2, "$A2 Errand"), (0xA3, "$A3 Pearl"), (0xA4, "$A4 Maw Open")) },
			new() { Group = "Progress Values", Key = "jabuwaterlevel", Label = "Jabu Water Level", Address = 0x7EF482, Choices = ChoiceList((0, "0 Drained"), (1, "1 B1 Flooded"), (2, "2 1F+B1 Flooded")) },
			new() { Group = "Progress Values", Key = "tradingqueststep", Label = "Trading Quest Step", Address = 0x7EF43D, Choices = ChoiceList((0, "0 None"), (1, "1 Yoshi Doll"), (2, "2 Velvet Cloak"), (3, "3 Pocket Watch"), (4, "4 Ghost Shroom"), (5, "5 Chateau Brew"), (6, "6 Secret Diary"), (7, "7 Lost Melody"), (8, "8 Owl Quill"), (9, "9 Moth Lantern"), (10, "10 Secrets Seal")) },
			new() { Group = "Progress Values", Key = "skysoaring", Label = "Sky Soaring Learned", Address = 0x7EF3B2, Choices = ChoiceList((0x00, "00 Locked"), (0xA5, "$A5 Learned")) }
		};

		private static readonly Dictionary<string, uint> PresetItemAddresses = new() {
			["bow"] = 0x7EF340, ["boomerang"] = 0x7EF341, ["hookshot"] = 0x7EF342, ["bombs"] = 0x7EF343, ["powder"] = 0x7EF344,
			["firerod"] = 0x7EF345, ["icerod"] = 0x7EF346, ["zoramask"] = 0x7EF347, ["bunnymask"] = 0x7EF348, ["dekumask"] = 0x7EF349,
			["lamp"] = 0x7EF34A, ["hammer"] = 0x7EF34B, ["flute"] = 0x7EF34C, ["ocarina_song"] = 0x7E030F, ["feather"] = 0x7EF34D,
			["book"] = 0x7EF34E, ["somaria"] = 0x7EF350, ["stonemask"] = 0x7EF352, ["mirror"] = 0x7EF353, ["gloves"] = 0x7EF354,
			["boots"] = 0x7EF355, ["flippers"] = 0x7EF356, ["moonpearl"] = 0x7EF357, ["wolfmask"] = 0x7EF358, ["sword"] = 0x7EF359,
			["shield"] = 0x7EF35A, ["armor"] = 0x7EF35B, ["rupees"] = RupeesAddr, ["health"] = HealthCurrentAddr, ["maxhealth"] = HealthMaxAddr,
			["magic"] = MagicAddr, ["arrows"] = 0x7EF377, ["keys"] = 0x7EF36F,
			// Oracle RC inventory: 0 is absent, 2 is an empty bottle, 3..10 are contents.
			["bottle1"] = 0x7EF35C, ["bottle2"] = 0x7EF35D, ["bottle3"] = 0x7EF35E, ["bottle4"] = 0x7EF35F,
			["customrods"] = 0x7EF351, ["portalrod"] = 0x7EF3A6, ["goldstar"] = 0x7EF3A9, ["fishingrod"] = 0x7EF38A,
			["boundmask"] = 0x7EF3A8, ["active_form"] = 0x7E02B2, ["ringslot1"] = 0x7EF3A3,
			["goldstar_inventory_version"] = 0x7EF3AA, ["saved_ocarina_song"] = 0x7EF3AB, ["rings"] = 0x7EF3A2,
			["byrna"] = 0x7EF3AC, ["bombchu"] = 0x7EF3AD, ["switchhook"] = 0x7EF3AE, ["garomask"] = 0x7EF3AF,
			["bombchu_ammo"] = 0x7EF3B0, ["songbits"] = 0x7EF3B1, ["skysoaring"] = 0x7EF3B2, ["slingshotbowflags"] = 0x7EF3C3,
			["bananas"] = 0x7EF38B, ["pineapples"] = 0x7EF38D, ["rockmeat"] = 0x7EF38F,
			["seashells"] = 0x7EF391, ["honeycombs"] = 0x7EF393, ["dekusticks"] = 0x7EF395,
			["tinglemaps"] = 0x7EF396, ["dungeonscrolls"] = 0x7EF398, ["scrollsfound"] = 0x7EF3BF,
			["swordstory"] = 0x7EF3BC, ["swordslotflags"] = 0x7EF3BD,
			["compass1"] = 0x7EF364, ["compass2"] = 0x7EF365, ["bigkey1"] = 0x7EF366, ["bigkey2"] = 0x7EF367,
			["dngmap1"] = 0x7EF368, ["dngmap2"] = 0x7EF369,
			["travelspots"] = 0x7EF412, ["specialtravelspots"] = 0x7EF438, ["korl_unlocked"] = 0x7EF439, ["abysstravelspots"] = 0x7EF43A,
			["tradingqueststep"] = 0x7EF43D, ["keys_1c"] = 0x7EF450, ["keys_1e"] = 0x7EF451, ["dekuvillagequest"] = 0x7EF456,
			["wardrobe_outfit"] = 0x7EF459,
			["clockworkbeetle"] = 0x7EF45A, ["switchhook_col21"] = 0x7EF45B, ["lensoftruth"] = 0x7EF45C, ["tempestharp"] = 0x7EF45D,
			["stalfosmask"] = 0x7EF466, ["frogmask"] = 0x7EF467, ["fiercedeitymask"] = 0x7EF468,
			["foundrings_ex"] = 0x7EF469, ["magicrings_ex"] = 0x7EF46A,
			["eonjabuquest"] = 0x7EF481, ["jabuwaterlevel"] = 0x7EF482, ["glaciawitches"] = 0x7EF483,
			// Ownership is exactly 1; Bombchu ammo is 0..20.
			["bombchuammo"] = 0x7EF3B0
		};

		private static readonly Dictionary<string, FlagBitDef> FlagBitIndex = FlagToggleDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);
		private static readonly Dictionary<string, ChoiceDef> ItemChoiceIndex = ItemChoiceDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);
		private static readonly Dictionary<string, ChoiceDef> FlagChoiceIndex = FlagChoiceDefs.ToDictionary(def => def.Key, def => def, StringComparer.OrdinalIgnoreCase);

		private static readonly List<PresetDef> Presets = new() {
			new() {
				Id = "all_items",
				Label = "All Items",
				Description = "Tools, Col 21 items, all 9 masks (Fierce Deity ready on R), six songs (+ SongBits + Epona's Song), all 9 rings, maps, compasses, all 8 scrolls, soaring spots (Kalyxo/Lyra/Abyss/Sky), KORL boat, Tunic/Sword slots, 1C/1E keys and Oracle collectibles. Fills missing or empty bottles; keeps filled bottles, higher counts and story progress.",
				Items = new Dictionary<string, int> {
					["bow"] = 4, ["boomerang"] = 2, ["hookshot"] = 1, ["bombs"] = 50, ["powder"] = 2, ["firerod"] = 1,
					["icerod"] = 1, ["lamp"] = 1, ["hammer"] = 1, ["flute"] = 7, ["ocarina_song"] = 1, ["feather"] = 1,
					["book"] = 1, ["somaria"] = 1, ["mirror"] = 2, ["zoramask"] = 1, ["dekumask"] = 1, ["bunnymask"] = 1,
					["stonemask"] = 1, ["wolfmask"] = 1, ["stalfosmask"] = 1, ["frogmask"] = 1, ["fiercedeitymask"] = 1,
					["boundmask"] = 9, ["sword"] = 4, ["shield"] = 3, ["armor"] = 2, ["gloves"] = 2,
					["boots"] = 1, ["flippers"] = 1, ["moonpearl"] = 1, ["health"] = 160, ["maxhealth"] = 160, ["magic"] = 128,
					["rupees"] = 9999, ["arrows"] = 70, ["keys"] = 9, ["keys_1c"] = 3, ["keys_1e"] = 3,
					["bottle1"] = 3, ["bottle2"] = 4, ["bottle3"] = 5, ["bottle4"] = 6,
					["customrods"] = 2, ["portalrod"] = 1, ["goldstar"] = 1, ["goldstar_inventory_version"] = 0xA5,
					["fishingrod"] = 1,
					["saved_ocarina_song"] = 1, ["rings"] = 0x3F, ["foundrings_ex"] = 0x07, ["magicrings_ex"] = 0x07,
					["tinglemaps"] = 0x7F, ["dungeonscrolls"] = 0xFF, ["scrollsfound"] = 0xFF,
					["swordstory"] = 4, ["swordslotflags"] = 0x85,
					["compass1"] = 0xFF, ["compass2"] = 0xFF, ["dngmap1"] = 0xFF, ["dngmap2"] = 0xFF,
					["byrna"] = 1, ["bombchu"] = 1, ["switchhook"] = 1, ["garomask"] = 1, ["bombchu_ammo"] = 20,
					["clockworkbeetle"] = 1, ["switchhook_col21"] = 1, ["lensoftruth"] = 1, ["tempestharp"] = 1,
					["songbits"] = 0xBF, ["slingshotbowflags"] = 0x83,
					["travelspots"] = 0xFF, ["specialtravelspots"] = 0x7F, ["abysstravelspots"] = 0xFF, ["skysoaring"] = 0xA5,
					// Five Rock Meat meets
					// the Goron's normal dialogue gate; do not set its world event.
					["bananas"] = 20, ["pineapples"] = 20, ["rockmeat"] = 5,
					["seashells"] = 20, ["honeycombs"] = 20, ["dekusticks"] = 20
				},
				Flags = new Dictionary<string, int> {
					["eponasong"] = 1, ["korl_unlocked"] = 1
				}
			},
			new() {
				Id = "fierce_deity_showcase",
				Label = "Fierce Deity Showcase",
				Description = "Full showcase kit with Fierce Deity Mask bound (R), Col 21 items, Golden Sword, Mirror Shield, Red Mail, 20 hearts, all 9 masks/rings, Mana Ring equipped, SongBits, KORL boat and soaring destinations.",
				Items = new Dictionary<string, int> {
					["bow"] = 4, ["boomerang"] = 2, ["hookshot"] = 1, ["bombs"] = 50, ["powder"] = 2, ["firerod"] = 1,
					["icerod"] = 1, ["lamp"] = 1, ["hammer"] = 1, ["flute"] = 7, ["ocarina_song"] = 3, ["feather"] = 1,
					["book"] = 1, ["somaria"] = 1, ["mirror"] = 2, ["zoramask"] = 1, ["dekumask"] = 1, ["bunnymask"] = 1,
					["stonemask"] = 1, ["wolfmask"] = 1, ["stalfosmask"] = 1, ["frogmask"] = 1, ["fiercedeitymask"] = 1,
					["boundmask"] = 9, ["sword"] = 4, ["shield"] = 3, ["armor"] = 2, ["gloves"] = 2,
					["boots"] = 1, ["flippers"] = 1, ["moonpearl"] = 1, ["health"] = 160, ["maxhealth"] = 160, ["magic"] = 128,
					["rupees"] = 9999, ["arrows"] = 70, ["keys"] = 9, ["keys_1c"] = 3, ["keys_1e"] = 3,
					["bottle1"] = 3, ["bottle2"] = 4, ["bottle3"] = 5, ["bottle4"] = 6,
					["customrods"] = 2, ["portalrod"] = 1, ["goldstar"] = 1, ["goldstar_inventory_version"] = 0xA5,
					["fishingrod"] = 1,
					["saved_ocarina_song"] = 3, ["rings"] = 0x3F, ["foundrings_ex"] = 0x07, ["magicrings_ex"] = 0x07, ["ringslot1"] = 8,
					["tinglemaps"] = 0x7F, ["dungeonscrolls"] = 0xFF, ["scrollsfound"] = 0xFF,
					["swordstory"] = 4, ["swordslotflags"] = 0x85,
					["compass1"] = 0xFF, ["compass2"] = 0xFF, ["dngmap1"] = 0xFF, ["dngmap2"] = 0xFF,
					["byrna"] = 1, ["bombchu"] = 1, ["switchhook"] = 1, ["garomask"] = 1, ["bombchu_ammo"] = 20,
					["clockworkbeetle"] = 1, ["switchhook_col21"] = 1, ["lensoftruth"] = 1, ["tempestharp"] = 1,
					["songbits"] = 0xBF, ["slingshotbowflags"] = 0x83,
					["travelspots"] = 0xFF, ["specialtravelspots"] = 0x7F, ["abysstravelspots"] = 0xFF, ["skysoaring"] = 0xA5,
					["bananas"] = 20, ["pineapples"] = 20, ["rockmeat"] = 5,
					["seashells"] = 20, ["honeycombs"] = 20, ["dekusticks"] = 20
				},
				Flags = new Dictionary<string, int> {
					["eponasong"] = 1, ["korl_unlocked"] = 1
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
			return Task.Run(ReadRuntimeSummary);
		}

		public static IReadOnlyList<OraclePanelPreset> GetPresets()
		{
			return Presets.Select(preset => new OraclePanelPreset() {
				Id = preset.Id,
				Label = preset.Label,
				Description = preset.Description
			}).ToList();
		}

		public static IReadOnlyList<OraclePanelToggleOption> GetFlagDefinitions()
		{
			return FlagToggleDefs.Select(def => new OraclePanelToggleOption() {
				Group = def.Group,
				Key = def.Key,
				Label = def.Label,
				Description = $"{def.Group}: {def.Label}"
			}).ToList();
		}

		public static Task<OraclePanelEditorData?> FetchEditorDataAsync()
		{
			return Task.Run(ReadEditorData);
		}

		internal static OraclePanelEditorData? ReadEditorData()
		{
			if(!CanReadState()) {
				return null;
			}

			OraclePanelRuntimeSummary? summary = ReadRuntimeSummary();
			if(summary == null) {
				return null;
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

			return data;
		}

		public static Task<OraclePanelApplyResult?> ApplySaveDataChangesAsync(IReadOnlyList<OraclePanelValueUpdate> items, IReadOnlyList<OraclePanelValueUpdate> flags, bool syncToSram, string? presetId = null)
		{
			return Task.Run(() => ApplySaveDataChanges(items, flags, syncToSram, presetId));
		}

		public static OraclePanelApplyResult? ApplySaveDataChanges(IReadOnlyList<OraclePanelValueUpdate> items, IReadOnlyList<OraclePanelValueUpdate> flags, bool syncToSram, string? presetId = null)
		{
			if(!CanReadState()) {
				return null;
			}
			EnsureDebuggerInitialized();

			int changedItems = 0;
			int changedFlags = 0;
			if(!string.IsNullOrWhiteSpace(presetId)) {
				PresetDef? preset = Presets.FirstOrDefault(p => p.Id == presetId);
				if(preset == null) {
					return null;
				}
				changedItems += ApplyItemValues(preset.Items.Select(entry => new OraclePanelValueUpdate() { Key = entry.Key, Value = entry.Value }).ToList(), preserveInventory: preset.Id == "all_items");
				changedFlags += ApplyFlagValues(preset.Flags.Select(entry => new OraclePanelValueUpdate() { Key = entry.Key, Value = entry.Value }).ToList());
			}

			changedItems += ApplyItemValues(items);
			changedFlags += ApplyFlagValues(flags);

			int? syncedSlot = syncToSram ? SyncWramSaveToSram() : null;
			OraclePanelRuntimeSummary summary = ReadRuntimeSummary() ?? new OraclePanelRuntimeSummary();
			return new OraclePanelApplyResult() {
				ChangedItems = changedItems,
				ChangedFlags = changedFlags,
				Synced = syncedSlot.HasValue,
				SyncSlot = syncedSlot,
				State = summary
			};
		}

		internal static bool CanReadState()
		{
			return EmuApi.IsRunning();
		}

		internal static void EnsureDebuggerInitialized()
		{
			if(!EmuApi.IsRunning() || DebugApi.IsDebuggerRunning()) {
				return;
			}

			lock(_debuggerLock) {
				if(!EmuApi.IsRunning() || DebugApi.IsDebuggerRunning()) {
					return;
				}

				DebugApi.InitializeDebugger();
			}
		}

		internal static OraclePanelRuntimeSummary? ReadRuntimeSummary()
		{
			if(!CanReadState() || !TryReadPassiveByte(ModeAddr, out byte mode)) {
				return null;
			}

			int submode = ReadPassiveByte(SubmodeAddr);
			bool indoors = ReadPassiveByte(IndoorsAddr) != 0;
			int areaId = ReadPassiveByte(AreaIdAddr);
			int roomLayout = ReadPassiveByte(RoomLayoutAddr);
			int dungeonRoom = ReadPassiveWord(DungeonRoomAddr);
			int roomValue = indoors ? dungeonRoom : roomLayout;

			string areaName = OverworldAreas.TryGetValue(areaId, out string? namedArea)
				? namedArea
				: $"Area 0x{areaId:X2}";
			string roomLabel = indoors ? $"0x{roomValue:X4}" : $"0x{roomValue:X2}";
			string roomName = indoors ? ("Room " + roomLabel) : areaName;
			byte linkDirection = ReadPassiveByte(LinkDirectionAddr);
			byte linkForm = ReadPassiveByte(LinkFormAddr);

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
				LinkX = ReadPassiveWord(LinkXAddr),
				LinkY = ReadPassiveWord(LinkYAddr),
				LinkDirection = DirectionNames.TryGetValue(linkDirection, out string? directionName) ? directionName : "?",
				LinkState = ReadPassiveByte(LinkStateAddr),
				LinkFormName = FormNames.TryGetValue(linkForm, out string? formName) ? formName : $"0x{linkForm:X2}",
				Health = ReadPassiveByte(HealthCurrentAddr),
				MaxHealth = ReadPassiveByte(HealthMaxAddr),
				Magic = ReadPassiveByte(MagicAddr),
				Rupees = ReadPassiveWord(RupeesAddr),
				GameState = ReadPassiveByte(GameStateAddr),
				Crystals = ReadPassiveByte(CrystalsAddr),
				Pendants = ReadPassiveByte(PendantsAddr)
			};
		}

		internal static int ApplyItemValues(IReadOnlyList<OraclePanelValueUpdate> updates, bool preserveInventory = false)
		{
			int changed = 0;
			foreach(OraclePanelValueUpdate update in updates) {
				if(!PresetItemAddresses.TryGetValue(update.Key, out uint address)) {
					continue;
				}

				int value = update.Value;
				if(preserveInventory) {
					int current = ReadChoiceValue(address);
					// Fill missing/empty bottles without deleting valid contents (3..10).
					if(address >= 0x7EF35C && address <= 0x7EF35F && current >= 3 && current <= 10) {
						continue;
					}
					switch(address) {
						case 0x7E030F: // Current song: keep a valid choice, then saved choice, then Healing.
						case 0x7EF3AB: // Match the ROM selection restored after a save/reload.
							value = ResolveOcarinaSongSelection();
							break;
						case 0x7EF3A2: // Appraised rings
						case 0x7EF469: case 0x7EF46A: // FoundRingsEx / MagicRingsEx
						case 0x7EF396: // Purchased maps
						case 0x7EF398: // Collected dungeon scrolls
						case 0x7EF3BF: // ScrollsFound
						case 0x7EF3B1: // SongBits
						case 0x7EF3C3: // SlingshotBowFlags
						case 0x7EF3BD: // SwordSlotFlags (keep story/tunic bits)
						case 0x7EF364: case 0x7EF365: // Compass1/2
						case 0x7EF368: case 0x7EF369: // DngMap1/2
						case 0x7EF412: // TravelSpotsVisited
						case 0x7EF438: // SpecialTravelSpotsVisited
						case 0x7EF43A: // AbyssTravelSpotsVisited
							value |= current;
							break;
						case 0x7EF3BC: // SwordStoryBlade
							value = Math.Max(current, value);
							break;
						case 0x7EF38B: case 0x7EF38D: case 0x7EF38F: // Magic Bag
						case 0x7EF391: case 0x7EF393: case 0x7EF395:
						case 0x7EF343: case 0x7EF377: case 0x7EF36F: // Ammo and keys
						case 0x7EF450: case 0x7EF451: // KeysDungeon1C / 1E
						case RupeesAddr: case HealthCurrentAddr: case HealthMaxAddr: case MagicAddr:
							value = Math.Max(current, value);
							break;
					}
				}

				WriteValue(address, value, update.Key.Equals("rupees", StringComparison.OrdinalIgnoreCase));
				// Dashing gates on AbilityFlags Run ($079C32), not the Boots byte, so the editor keeps them paired.
				if(address == BootsAddr) {
					byte ability = ReadByte(AbilityFlagsAddr);
					WriteByte(AbilityFlagsAddr, value != 0 ? ability | AbilityRunBit : ability & ~AbilityRunBit);
				}
				changed++;
			}
			return changed;
		}

		private static int ResolveOcarinaSongSelection()
		{
			int current = ReadByte(0x7E030F);
			if(current >= 1 && current <= 6) {
				return current;
			}
			int saved = ReadByte(0x7EF3AB);
			return saved >= 1 && saved <= 6 ? saved : 1;
		}

		internal static int ApplyFlagValues(IReadOnlyList<OraclePanelValueUpdate> updates)
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
					case "songbits":
						WriteByte(0x7EF3B1, update.Value);
						changed++;
						break;
					case "slingshotbowflags":
						WriteByte(0x7EF3C3, update.Value);
						changed++;
						break;
					case "travelspots":
						WriteByte(0x7EF412, update.Value);
						changed++;
						break;
					case "specialtravelspots":
						WriteByte(0x7EF438, update.Value);
						changed++;
						break;
					case "abysstravelspots":
						WriteByte(0x7EF43A, update.Value);
						changed++;
						break;
					case "dekuvillagequest":
						WriteByte(0x7EF456, update.Value);
						changed++;
						break;
					case "foundrings_ex":
						WriteByte(0x7EF469, update.Value);
						changed++;
						break;
					case "magicrings_ex":
						WriteByte(0x7EF46A, update.Value);
						changed++;
						break;
					case "glaciawitches":
						WriteByte(0x7EF483, update.Value);
						changed++;
						break;
				}
			}
			return changed;
		}

		internal static int? SyncWramSaveToSram()
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

		internal static bool TryReadPassiveByte(uint address, out byte value)
		{
			if(TryReadPassiveWramByte(address, out value)) {
				return true;
			}
			if(DebugApi.IsDebuggerRunning()) {
				value = DebugApi.GetMemoryValue(MemoryType.SnesMemory, address);
				return true;
			}
			value = 0;
			return false;
		}

		private static bool TryReadPassiveWramByte(uint address, out byte value)
		{
			if(address >= 0x7E0000 && address <= 0x7FFFFF) {
				return EmuApi.TryReadGameMemoryByte(MemoryType.SnesWorkRam, address - 0x7E0000, out value);
			}
			if((address <= 0x3FFFFF || (address >= 0x800000 && address <= 0xBFFFFF)) && (address & 0xFFFF) < 0x2000) {
				return EmuApi.TryReadGameMemoryByte(MemoryType.SnesWorkRam, address & 0x1FFF, out value);
			}
			value = 0;
			return false;
		}

		private static byte ReadPassiveByte(uint address)
		{
			return TryReadPassiveByte(address, out byte value) ? value : (byte)0;
		}

		private static int ReadPassiveWord(uint address)
		{
			return ReadPassiveByte(address) | (ReadPassiveByte(address + 1) << 8);
		}

		internal static byte ReadMemoryByte(MemoryType type, uint offset)
		{
			if(EmuApi.TryReadGameMemoryByte(type, offset, out byte value)) {
				return value;
			}
			return DebugApi.GetMemoryValue(type, offset);
		}

		internal static byte ReadByte(uint address)
		{
			if(TryReadPassiveWramByte(address, out byte value)) {
				return value;
			}
			return DebugApi.GetMemoryValue(MemoryType.SnesMemory, address);
		}

		internal static int ReadWord(uint address)
		{
			return ReadByte(address) | (ReadByte(address + 1) << 8);
		}

		internal static void WriteByte(uint address, int value)
		{
			DebugApi.SetMemoryValue(MemoryType.SnesMemory, address, (byte)(value & 0xFF));
		}

		internal static void WriteWord(uint address, int value)
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
