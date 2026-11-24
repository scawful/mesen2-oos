package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Oracle save-block inventory and progression editor for the compose UI.
 * Addresses are sourced from Core/sram.asm and Data/debug/checkpoints.json.
 * Every write targets the WRAM save mirror ($7EF000-$7EF4FF) and calls
 * {@link Memory#syncSram()} so edits survive battery save/reload.
 * Writes are gated on normal play (module $07/$09/$0B, submodule 0).
 */
final class Inventory {
    enum Kind { VALUE, WORD, BITS, FLAG, SONG, ACTION }

    static final class Row {
        final String tab;
        final String label;
        final int addr;
        final int max;
        final int step;
        final int mask;
        final Kind kind;
        final String[] names;

        Row(String tab, String label, int addr, int max, int step, int mask, Kind kind, String[] names) {
            this.tab = tab;
            this.label = label;
            this.addr = addr;
            this.max = max;
            this.step = step;
            this.mask = mask;
            this.kind = kind;
            this.names = names;
        }
    }

    interface Memory {
        int readByte(int address);
        boolean writeByte(int address, int value);
        boolean writeWord(int address, int value);
        default boolean syncSram() { return true; }
    }

    static final Memory LIVE = new Memory() {
        public int readByte(int address) { return MesenNative.readByte(address); }
        public boolean writeByte(int address, int value) { return MesenNative.writeByte(address, value); }
        public boolean writeWord(int address, int value) { return MesenNative.writeWord(address, value); }
        public boolean syncSram() {
            try {
                Progression.syncSram(Progression.LIVE);
                return true;
            } catch (Progression.Failure f) {
                return false;
            }
        }
    };

    static final int MODULE = 0x7E0010;
    static final int SUBMODULE = 0x7E0011;
    static final int LINK_GRAPHICS = 0x7E00BC;
    static final int ACTIVE_FORM = 0x7E02B2;
    static final int FORM_FIERCE_DEITY = 0x0D;

    // Custom story flags ($7EF300-$7EF30F, Core/sram.asm:228-254, 490-494)
    static final int KYDROG_FARORE_REMOVED = 0x7EF300;
    static final int DEKU_MASK_QUEST_DONE = 0x7EF301;
    static final int ZORA_MASK_QUEST_DONE = 0x7EF302;
    static final int IN_CUTSCENE_FLAG = 0x7EF303;
    static final int ELDER_GUIDE_STAGE = 0x7EF304;
    static final int ZORA_WATERFALL_HINT = 0x7EF305;
    static final int CASTLE_AMBUSH_FLAGS = 0x7EF306;
    static final int PART00_FLAGS = 0x7EF30F;

    // Y-items & equipment ($7EF340-$7EF35F, Core/sram.asm:309-349)
    static final int BOW = 0x7EF340;
    static final int BOOMERANG = 0x7EF341;
    static final int HOOKSHOT = 0x7EF342;
    static final int BOMBS = 0x7EF343;
    static final int POWDER = 0x7EF344;
    static final int FIRE_ROD = 0x7EF345;
    static final int ICE_ROD = 0x7EF346;
    static final int ZORA_MASK = 0x7EF347;
    static final int BUNNY_HOOD = 0x7EF348;
    static final int DEKU_MASK = 0x7EF349;
    static final int LAMP = 0x7EF34A;
    static final int HAMMER = 0x7EF34B;
    static final int FLUTE = 0x7EF34C;
    static final int FEATHER = 0x7EF34D;
    static final int BOOK = 0x7EF34E;
    static final int BOTTLE_INDEX = 0x7EF34F;
    static final int SOMARIA = 0x7EF350;
    static final int CUSTOM_RODS = 0x7EF351;
    static final int STONE_MASK = 0x7EF352;
    static final int MIRROR = 0x7EF353;
    static final int GLOVES = 0x7EF354;
    static final int BOOTS = 0x7EF355;
    static final int FLIPPERS = 0x7EF356;
    static final int MOON_PEARL = 0x7EF357;
    static final int WOLF_MASK = 0x7EF358;
    static final int SWORD = 0x7EF359;
    static final int SHIELD = 0x7EF35A;
    static final int ARMOR = 0x7EF35B;
    static final int BOTTLE_1 = 0x7EF35C;

    // Rupees, dungeon sets, health/magic/capacity ($7EF360-$7EF389, Core/sram.asm:358-438)
    static final int RUPEES = 0x7EF360;
    static final int RUPEES_DISPLAY = 0x7EF362;
    static final int COMPASS_SET_1 = 0x7EF364;
    static final int COMPASS_SET_2 = 0x7EF365;
    static final int BIG_KEY_SET_1 = 0x7EF366;
    static final int BIG_KEY_SET_2 = 0x7EF367;
    static final int DUNGEON_MAP_SET_1 = 0x7EF368;
    static final int DUNGEON_MAP_SET_2 = 0x7EF369;
    static final int HEART_PIECES = 0x7EF36B;
    static final int HEALTH_MAX = 0x7EF36C;
    static final int HEALTH_CURRENT = 0x7EF36D;
    static final int MAGIC = 0x7EF36E;
    static final int CURRENT_KEYS = 0x7EF36F;
    static final int BOMB_CAPACITY = 0x7EF370;
    static final int ARROW_CAPACITY = 0x7EF371;
    static final int PENDANTS = 0x7EF374;
    static final int ARROWS = 0x7EF377;
    static final int ABILITY = 0x7EF379;
    static final int CRYSTALS = 0x7EF37A;
    static final int MAGIC_USAGE = 0x7EF37B;
    static final int KEYS_SEWER = 0x7EF37C;

    // Collectibles, Tingle maps, scrolls, beans, intro ($7EF38A-$7EF39E, Core/sram.asm:447-481)
    static final int FISHING_ROD = 0x7EF38A;
    static final int BANANAS = 0x7EF38B;
    static final int PINEAPPLES = 0x7EF38D;
    static final int ROCK_MEAT = 0x7EF38F;
    static final int SEASHELLS = 0x7EF391;
    static final int HONEYCOMB = 0x7EF393;
    static final int DEKU_STICKS = 0x7EF395;
    static final int TINGLE_MAPS = 0x7EF396;
    static final int TINGLE_ID = 0x7EF397;
    static final int DUNGEON_SCROLLS = 0x7EF398;
    static final int MAGIC_BEAN_PROGRESS = 0x7EF39B;
    static final int INTRO_STATE = 0x7EF39E;

    // Extended SRAM block ($7EF3A0-$7EF3DE, Core/sram.asm:502-883)
    static final int WOLF_DIG_FLAGS = 0x7EF3A0;
    static final int FOUND_RINGS = 0x7EF3A1;
    static final int MAGIC_RINGS = 0x7EF3A2;
    static final int RING_SLOT_1 = 0x7EF3A3;
    static final int ALL_RINGS = 0x3F;
    static final int PORTAL_ROD_OWNED = 0x7EF3A6;
    static final int EON_OWL_FLAGS = 0x7EF3A7;
    static final int BOUND_MASK = 0x7EF3A8;
    static final int GOLDSTAR_OWNED = 0x7EF3A9;
    static final int GOLDSTAR_VERSION = 0x7EF3AA;
    static final int GOLDSTAR_VERSION_OK = 0xA5;
    static final int SAVED_OCARINA_SONG = 0x7EF3AB;
    static final int BYRNA_OWNED = 0x7EF3AC;
    static final int BOMBCHU_OWNED = 0x7EF3AD;
    static final int SWITCH_HOOK_OWNED = 0x7EF3AE;
    static final int GARO_MASK_OWNED = 0x7EF3AF;
    static final int BOMBCHU_AMMO = 0x7EF3B0;
    static final int BOMBCHU_AMMO_MAX = 20;
    static final int SONG_BITS = 0x7EF3B1;
    static final int ALL_SONG_BITS = 0xBF;
    static final int SKY_SOARING_LEARNED = 0x7EF3B2;
    static final int SKY_SOARING_TOKEN = 0xA5;
    static final int SWORD_STORY_BLADE = 0x7EF3BC;
    static final int SWORD_SLOT_FLAGS = 0x7EF3BD;
    static final int HEAVY_SWORD_GRANT = 0x81;
    static final int EON_GORON_QUEST = 0x7EF3BE;
    static final int SLINGSHOT_BOW_FLAGS = 0x7EF3C3;
    static final int ALL_SLINGSHOT_BOW_FLAGS = 0x83;
    static final int RICHARD_QUEST_FLAGS = 0x7EF3C4;
    static final int GAME_STATE = 0x7EF3C5;
    static final int OOSPROG2 = 0x7EF3C6;
    static final int MAP_ICON = 0x7EF3C7;
    static final int SPAWN_POINT = 0x7EF3C8;
    static final int SAVED_WORLD = 0x7EF3CA;
    static final int FOLLOWER = 0x7EF3CC;
    static final int MAKU_TREE_QUEST = 0x7EF3D4;
    static final int OOSPROG = 0x7EF3D6;
    static final int SIDE_QUEST = 0x7EF3D7;
    static final int SIDE_QUEST_2 = 0x7EF3D8;
    static final int EPONA_SONG = 0x02;

    // Dreams, Water gates, Travel spots, Quests, Col 21, Extra Masks & Rings ($7EF410-$7EF483)
    static final int DREAMS = 0x7EF410;
    static final int WATER_GATE_STATES = 0x7EF411;
    static final int TRAVEL_SPOTS = 0x7EF412;
    static final int SPECIAL_TRAVEL_SPOTS = 0x7EF438;
    static final int KORL_UNLOCKED = 0x7EF439;
    static final int ABYSS_TRAVEL_SPOTS = 0x7EF43A;
    static final int TRADING_QUEST_STEP = 0x7EF43D;
    static final int KEYS_DUNGEON_1C = 0x7EF450;
    static final int KEYS_DUNGEON_1E = 0x7EF451;
    static final int DEKU_VILLAGE_QUEST = 0x7EF456;
    static final int WARDROBE_OUTFIT_ID = 0x7EF459;
    static final int CLOCKWORK_BEETLE_OWNED = 0x7EF45A;
    static final int SWITCH_HOOK_COL21_OWNED = 0x7EF45B;
    static final int LENS_OF_TRUTH_OWNED = 0x7EF45C;
    static final int TEMPEST_HARP_OWNED = 0x7EF45D;
    static final int STALFOS_MASK_OWNED = 0x7EF466;
    static final int FROG_MASK_OWNED = 0x7EF467;
    static final int FIERCE_DEITY_MASK_OWNED = 0x7EF468;
    static final int FOUND_RINGS_EX = 0x7EF469;
    static final int MAGIC_RINGS_EX = 0x7EF46A;
    static final int ALL_RINGS_EX = 0x07;
    static final int EON_JABU_QUEST = 0x7EF481;
    static final int JABU_WATER_LEVEL = 0x7EF482;
    static final int GLACIA_WITCHES_FLAGS = 0x7EF483;

    /** Flute byte: 1 = Ocarina, 2..7 = Ocarina plus songs (Items/ocarina.asm:687-700). */
    static final int FLUTE_ALL_SONGS = 7;
    static final int[] MASKS = {
            ZORA_MASK, BUNNY_HOOD, DEKU_MASK, STONE_MASK, WOLF_MASK,
            GARO_MASK_OWNED, STALFOS_MASK_OWNED, FROG_MASK_OWNED, FIERCE_DEITY_MASK_OWNED
    };

    static final String TAB_ITEMS = "Items";
    static final String TAB_SONGS = "Songs";
    static final String TAB_DUNGEONS = "Dungeons";
    static final String TAB_STORY = "Story flags";

    static final String MAX_EVERYTHING = "Max items & gear";
    static final String STARTER_KIT = "Basic starter kit";
    static final String FULL_HEAL = "Full heal";
    static final String FILL_MAGIC = "Fill magic";
    static final String RUPEES_999 = "999 rupees";
    static final String FILL_BOTTLES = "Fill 4 bottles";
    static final String ALL_MASKS = "All masks";
    static final String ALL_RINGS_ACTION = "All rings";
    static final String EQUIP_FIERCE_DEITY = "Equip Fierce Deity";
    static final String HEAVY_SWORD = "Heavy sword (broken)";

    private static final String[] SWORDS = {"None", "Fighter", "Master", "Tempered", "Golden"};
    private static final String[] SHIELDS = {"None", "Fighter", "Fire", "Mirror"};
    private static final String[] ARMORS = {"Green", "Blue", "Red"};
    private static final String[] GLOVE_NAMES = {"None", "Power Glove", "Titan's Mitt"};
    private static final String[] MAGIC_USE_NAMES = {"Normal", "Half", "Quarter"};
    private static final String[] ROD_NAMES = {"None", "Fishing Rod", "Portal Rod"};
    private static final String[] BOTTLES = {"None", "Mushroom", "Empty", "Red potion", "Green potion",
            "Blue potion", "Fairy", "Bee", "Good bee"};
    private static final String[] RING_NAMES = {"Empty", "Reserved", "Power", "Armor",
            "Heart", "Light", "Blast", "Steadfast", "Mana", "Charge", "Whisp"};
    private static final String[] BOUND_MASK_NAMES = {"None", "Deku", "Zora", "Wolf",
            "Bunny", "Stone", "Garo", "Stalfos", "Frog", "Fierce Deity"};
    private static final String[] FORM_NAMES = {"Normal", "Deku", "Zora", "Wolf",
            "Bunny", "Minish", "GBC", "Moosh", "Garo", "Horse", "Farore",
            "Stalfos", "Frog", "Fierce Deity", "Mermaid"};
    private static final int[] FORM_GFX_BANKS = {
            0x10, 0x35, 0x36, 0x38, 0x37, 0x39, 0x3B, 0x33,
            0x42, 0x10, 0x61, 0x7B, 0x7C, 0x7D, 0x3B
    };
    private static final String[] WARDROBE_NAMES = {"Minish Link", "ALTTP Link", "Parallel Worlds",
            "Awakening", "NES Link", "TP Zelda", "Classic Zelda", "Tetra", "Tingle"};
    private static final String[] TRADING_STEP_NAMES = {"None", "Yoshi Doll", "Velvet Cloak",
            "Pocket Watch", "Ghost Shroom", "Chateau Brew", "Secret Diary", "Lost Melody",
            "Owl Quill", "Moth Lantern", "Secrets Seal"};
    private static final String[] JABU_WATER_NAMES = {"Drained", "B1 Flooded", "1F & B1 Flooded"};
    private static final String[] OCARINA_SONGS = {"Unset", "Healing", "Storms", "Soaring",
            "Time", "Secrets", "Stone"};
    private static final String[] GAME_STATES = {"Start", "LoomBeach", "KydrogComplete", "FaroreRescued"};
    private static final String[] MAP_ICONS = {"MakuTree", "D1", "D2", "D3", "D4", "D5",
            "D6", "D7", "Fortress", "TailPond"};
    private static final String[] SPAWN_POINTS = {"LinkHouse", "Sanctuary", "Prison", "Uncle",
            "Throne", "OldManCave", "OldManHome", "GoronCkpt"};
    private static final String[] KEY_DUNGEONS = {
            "Sewer", "Hyrule Castle", "Eastern (D1)", "Desert", "Agahnim", "Swamp",
            "Palace of Darkness", "Misery Mire", "Skull Woods", "Ice Palace",
            "Tower of Hera", "Thieves Town", "Turtle Rock", "Ganons Tower"
    };

    private static final List<Row> CATALOG = buildCatalog();

    private Inventory() {}

    static List<String> tabs() {
        List<String> tabs = new ArrayList<>();
        tabs.add(TAB_ITEMS);
        tabs.add(TAB_SONGS);
        tabs.add(TAB_DUNGEONS);
        tabs.add(TAB_STORY);
        return tabs;
    }

    private static Row value(String tab, String label, int addr, int max) {
        return new Row(tab, label, addr, max, 1, 0, Kind.VALUE, null);
    }

    private static Row value(String tab, String label, int addr, int max, int step, String[] names) {
        return new Row(tab, label, addr, max, step, 0, Kind.VALUE, names);
    }

    private static Row bits(String tab, String label, int addr) {
        return new Row(tab, label, addr, 0xFF, 0, 0, Kind.BITS, null);
    }

    private static Row flag(String tab, String label, int addr, int mask) {
        return new Row(tab, label, addr, 1, 0, mask, Kind.FLAG, null);
    }

    private static Row song(String label, int threshold) {
        return new Row(TAB_SONGS, label, FLUTE, 1, 0, threshold, Kind.SONG, null);
    }

    private static Row action(String label) {
        return new Row(TAB_ITEMS, label, 0, 0, 0, 0, Kind.ACTION, null);
    }

    static List<Row> catalog() {
        return CATALOG;
    }

    private static List<Row> buildCatalog() {
        List<Row> rows = new ArrayList<>();

        // Items tab: quick actions, all Y-items & newest prototypes, masks, bottles, gear, capacities, rings
        for (String label : new String[]{MAX_EVERYTHING, FULL_HEAL, FILL_MAGIC, RUPEES_999,
                FILL_BOTTLES, ALL_MASKS, ALL_RINGS_ACTION, EQUIP_FIERCE_DEITY, HEAVY_SWORD, STARTER_KIT}) {
            rows.add(action(label));
        }
        rows.add(value(TAB_ITEMS, "Bow", BOW, 4));
        rows.add(flag(TAB_ITEMS, "Slingshot owned ($7EF3C3)", SLINGSHOT_BOW_FLAGS, 0x01));
        rows.add(flag(TAB_ITEMS, "Full Bow owned ($7EF3C3)", SLINGSHOT_BOW_FLAGS, 0x02));
        rows.add(flag(TAB_ITEMS, "Slingshot/Bow init ($80)", SLINGSHOT_BOW_FLAGS, 0x80));
        rows.add(bits(TAB_ITEMS, "Slingshot/Bow raw ($7EF3C3)", SLINGSHOT_BOW_FLAGS));
        rows.add(value(TAB_ITEMS, "Boomerang", BOOMERANG, 2, 1, new String[]{"None", "Blue", "Red"}));
        rows.add(value(TAB_ITEMS, "Hookshot", HOOKSHOT, 2));
        rows.add(value(TAB_ITEMS, "Switch Hook", SWITCH_HOOK_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Goldstar", GOLDSTAR_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Bombs", BOMBS, 50));
        rows.add(value(TAB_ITEMS, "Bomb capacity", BOMB_CAPACITY, 7));
        rows.add(value(TAB_ITEMS, "Bombchus owned", BOMBCHU_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Bombchus ammo", BOMBCHU_AMMO, BOMBCHU_AMMO_MAX));
        rows.add(value(TAB_ITEMS, "Arrows", ARROWS, 70));
        rows.add(value(TAB_ITEMS, "Arrow capacity", ARROW_CAPACITY, 7));
        rows.add(value(TAB_ITEMS, "Magic powder", POWDER, 2, 1, new String[]{"None", "Mushroom", "Powder"}));
        rows.add(value(TAB_ITEMS, "Fire rod", FIRE_ROD, 1));
        rows.add(value(TAB_ITEMS, "Ice rod", ICE_ROD, 1));
        rows.add(value(TAB_ITEMS, "Lamp", LAMP, 1));
        rows.add(value(TAB_ITEMS, "Hammer", HAMMER, 1));
        rows.add(value(TAB_ITEMS, "Roc's feather", FEATHER, 1));
        rows.add(value(TAB_ITEMS, "Book of Secrets", BOOK, 1));
        rows.add(value(TAB_ITEMS, "Cane of Somaria", SOMARIA, 1));
        rows.add(value(TAB_ITEMS, "Cane of Byrna", BYRNA_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Fishing/Portal rods", CUSTOM_RODS, 2, 1, ROD_NAMES));
        rows.add(value(TAB_ITEMS, "Portal Rod owned", PORTAL_ROD_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Fishing Rod flag", FISHING_ROD, 1));
        rows.add(value(TAB_ITEMS, "Magic mirror", MIRROR, 2));
        rows.add(value(TAB_ITEMS, "Clockwork Beetle", CLOCKWORK_BEETLE_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Switch Hook (Col 21)", SWITCH_HOOK_COL21_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Lens of Truth", LENS_OF_TRUTH_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Tempest Harp", TEMPEST_HARP_OWNED, 1));

        rows.add(value(TAB_ITEMS, "Zora Mask", ZORA_MASK, 1));
        rows.add(value(TAB_ITEMS, "Bunny Hood", BUNNY_HOOD, 1));
        rows.add(value(TAB_ITEMS, "Deku Mask", DEKU_MASK, 1));
        rows.add(value(TAB_ITEMS, "Stone Mask", STONE_MASK, 1));
        rows.add(value(TAB_ITEMS, "Wolf Mask", WOLF_MASK, 1));
        rows.add(value(TAB_ITEMS, "Garo Mask", GARO_MASK_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Stalfos Mask", STALFOS_MASK_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Frog Mask", FROG_MASK_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Fierce Deity Mask", FIERCE_DEITY_MASK_OWNED, 1));
        rows.add(value(TAB_ITEMS, "Bound mask (R slot)", BOUND_MASK, 9, 1, BOUND_MASK_NAMES));
        rows.add(value(TAB_ITEMS, "Active form ($7E02B2)", ACTIVE_FORM, 14, 1, FORM_NAMES));
        rows.add(value(TAB_ITEMS, "Wardrobe outfit ($7EF459)", WARDROBE_OUTFIT_ID, 8, 1, WARDROBE_NAMES));

        rows.add(value(TAB_ITEMS, "Bottle index", BOTTLE_INDEX, 4));
        for (int i = 0; i < 4; i++) {
            rows.add(value(TAB_ITEMS, "Bottle " + (i + 1), BOTTLE_1 + i, 8, 1, BOTTLES));
        }

        rows.add(value(TAB_ITEMS, "Sword", SWORD, 4, 1, SWORDS));
        rows.add(value(TAB_ITEMS, "Story blade", SWORD_STORY_BLADE, 4, 1, SWORDS));
        rows.add(flag(TAB_ITEMS, "Heavy sword: Owned", SWORD_SLOT_FLAGS, 0x01));
        rows.add(flag(TAB_ITEMS, "Heavy sword: Equipped", SWORD_SLOT_FLAGS, 0x02));
        rows.add(flag(TAB_ITEMS, "Heavy sword: Reforged", SWORD_SLOT_FLAGS, 0x04));
        rows.add(flag(TAB_ITEMS, "Sword slots: Initialized", SWORD_SLOT_FLAGS, 0x80));
        rows.add(value(TAB_ITEMS, "Shield", SHIELD, 3, 1, SHIELDS));
        rows.add(value(TAB_ITEMS, "Mail", ARMOR, 2, 1, ARMORS));
        rows.add(value(TAB_ITEMS, "Gloves", GLOVES, 2, 1, GLOVE_NAMES));
        rows.add(value(TAB_ITEMS, "Pegasus boots", BOOTS, 1));
        rows.add(value(TAB_ITEMS, "Zora flippers", FLIPPERS, 1));
        rows.add(value(TAB_ITEMS, "Moon pearl", MOON_PEARL, 1));

        rows.add(value(TAB_ITEMS, "Max health (8 = 1 heart)", HEALTH_MAX, 0xA0, 8, null));
        rows.add(value(TAB_ITEMS, "Health", HEALTH_CURRENT, 0xA0, 8, null));
        rows.add(value(TAB_ITEMS, "Heart pieces (0-3)", HEART_PIECES, 3));
        rows.add(value(TAB_ITEMS, "Magic", MAGIC, 0x80, 0x10, null));
        rows.add(value(TAB_ITEMS, "Magic usage", MAGIC_USAGE, 2, 1, MAGIC_USE_NAMES));
        rows.add(new Row(TAB_ITEMS, "Rupees", RUPEES, 999, 50, 0, Kind.WORD, null));

        rows.add(flag(TAB_ITEMS, "Ability: Lift", ABILITY, 0x80));
        rows.add(flag(TAB_ITEMS, "Ability: Read", ABILITY, 0x20));
        rows.add(flag(TAB_ITEMS, "Ability: Talk", ABILITY, 0x10));
        rows.add(flag(TAB_ITEMS, "Ability: Pull", ABILITY, 0x08));
        rows.add(flag(TAB_ITEMS, "Ability: Run", ABILITY, 0x04));
        rows.add(flag(TAB_ITEMS, "Ability: Swim", ABILITY, 0x02));
        rows.add(flag(TAB_ITEMS, "Ability: Pray", ABILITY, 0x01));

        rows.add(value(TAB_ITEMS, "Equipped ring (Slot 1)", RING_SLOT_1, 10, 1, RING_NAMES));
        rows.add(flag(TAB_ITEMS, "Ring owned: Power", MAGIC_RINGS, 0x20));
        rows.add(flag(TAB_ITEMS, "Ring owned: Armor", MAGIC_RINGS, 0x10));
        rows.add(flag(TAB_ITEMS, "Ring owned: Heart", MAGIC_RINGS, 0x08));
        rows.add(flag(TAB_ITEMS, "Ring owned: Light", MAGIC_RINGS, 0x04));
        rows.add(flag(TAB_ITEMS, "Ring owned: Blast", MAGIC_RINGS, 0x02));
        rows.add(flag(TAB_ITEMS, "Ring owned: Steadfast", MAGIC_RINGS, 0x01));
        rows.add(flag(TAB_ITEMS, "Ring owned: Mana", MAGIC_RINGS_EX, 0x01));
        rows.add(flag(TAB_ITEMS, "Ring owned: Charge", MAGIC_RINGS_EX, 0x02));
        rows.add(flag(TAB_ITEMS, "Ring owned: Whisp", MAGIC_RINGS_EX, 0x04));
        rows.add(bits(TAB_ITEMS, "Rings found ($7EF3A1)", FOUND_RINGS));
        rows.add(bits(TAB_ITEMS, "Rings owned ($7EF3A2)", MAGIC_RINGS));
        rows.add(bits(TAB_ITEMS, "Rings found EX ($7EF469)", FOUND_RINGS_EX));
        rows.add(bits(TAB_ITEMS, "Rings owned EX ($7EF46A)", MAGIC_RINGS_EX));

        rows.add(value(TAB_ITEMS, "Deku sticks", DEKU_STICKS, 30));
        rows.add(value(TAB_ITEMS, "Bananas", BANANAS, 10));
        rows.add(value(TAB_ITEMS, "Rock Meat", ROCK_MEAT, 10));
        rows.add(value(TAB_ITEMS, "Pineapples", PINEAPPLES, 20));
        rows.add(value(TAB_ITEMS, "Seashells", SEASHELLS, 20));
        rows.add(value(TAB_ITEMS, "Honeycomb", HONEYCOMB, 20));

        // Songs tab: Ocarina songs, SongBits, Epona bit, Sky Soaring, Travel spots, KORL
        rows.add(value(TAB_SONGS, "Ocarina level (1=flute, 2-7=songs)", FLUTE, FLUTE_ALL_SONGS));
        rows.add(song("Song 1: Song of Healing", 2));
        rows.add(song("Song 2: Song of Storms", 3));
        rows.add(song("Song 3: Song of Soaring", 4));
        rows.add(song("Song 4: Song of Time", 5));
        rows.add(song("Song 5: Song of Secrets", 6));
        rows.add(flag(TAB_SONGS, "Song 6: Epona's Song ($7EF3D8)", SIDE_QUEST_2, EPONA_SONG));
        rows.add(song("Elegy of Stone (count 7)", 7));
        rows.add(value(TAB_SONGS, "Saved Ocarina song", SAVED_OCARINA_SONG, 6, 1, OCARINA_SONGS));
        rows.add(value(TAB_SONGS, "Sky Soaring learned ($A5)", SKY_SOARING_LEARNED, SKY_SOARING_TOKEN));
        rows.add(flag(TAB_SONGS, "SongBit: Healing ($7EF3B1)", SONG_BITS, 0x01));
        rows.add(flag(TAB_SONGS, "SongBit: Storms", SONG_BITS, 0x02));
        rows.add(flag(TAB_SONGS, "SongBit: Soaring", SONG_BITS, 0x04));
        rows.add(flag(TAB_SONGS, "SongBit: Time", SONG_BITS, 0x08));
        rows.add(flag(TAB_SONGS, "SongBit: Secrets", SONG_BITS, 0x10));
        rows.add(flag(TAB_SONGS, "SongBit: Epona", SONG_BITS, 0x20));
        rows.add(flag(TAB_SONGS, "SongBits: Active ($80)", SONG_BITS, 0x80));
        rows.add(bits(TAB_SONGS, "SongBits raw ($7EF3B1)", SONG_BITS));
        for (int i = 0; i < 8; i++) {
            rows.add(flag(TAB_SONGS, "Travel spot " + i + " ($7EF412)", TRAVEL_SPOTS, 1 << i));
        }
        rows.add(bits(TAB_SONGS, "TravelSpotsVisited raw", TRAVEL_SPOTS));
        String[] specialSpotNames = {
                "Korok Cove", "Royal Garrison", "Zora Quay", "Goron Coast",
                "Crescent Island", "Courage Island", "Frost Glacier"
        };
        for (int i = 0; i < specialSpotNames.length; i++) {
            rows.add(flag(TAB_SONGS, "SpecialTravel: " + specialSpotNames[i], SPECIAL_TRAVEL_SPOTS, 1 << i));
        }
        rows.add(value(TAB_SONGS, "SpecialTravelSpots ($7EF438)", SPECIAL_TRAVEL_SPOTS, 0x7F));
        rows.add(bits(TAB_SONGS, "AbyssTravelSpots ($7EF43A)", ABYSS_TRAVEL_SPOTS));
        rows.add(value(TAB_SONGS, "King of Red Lions ($7EF439)", KORL_UNLOCKED, 1));

        // Dungeons tab: 8 Crystals/Essences, 3 Pendants, 7 Scrolls, D4 Water gates, Tingle, Keys, Dungeon gear
        rows.add(flag(TAB_DUNGEONS, "Crystal: D1 Mushroom Grotto", CRYSTALS, 0x02));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D2 Tail Palace", CRYSTALS, 0x10));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D3 Kalyxo Castle", CRYSTALS, 0x40));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D4 Zora Temple", CRYSTALS, 0x20));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D5 Glacia Estate", CRYSTALS, 0x04));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D6 Goron Mines", CRYSTALS, 0x01));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D7 Dragon Ship", CRYSTALS, 0x08));
        rows.add(flag(TAB_DUNGEONS, "Crystal: D8 Sky Essence", CRYSTALS, 0x80));
        rows.add(bits(TAB_DUNGEONS, "Crystals raw ($7EF37A)", CRYSTALS));

        rows.add(flag(TAB_DUNGEONS, "Pendant: Wisdom (Red)", PENDANTS, 0x01));
        rows.add(flag(TAB_DUNGEONS, "Pendant: Power (Blue)", PENDANTS, 0x02));
        rows.add(flag(TAB_DUNGEONS, "Pendant: Courage (Green)", PENDANTS, 0x04));
        rows.add(bits(TAB_DUNGEONS, "Pendants raw ($7EF374)", PENDANTS));

        rows.add(flag(TAB_DUNGEONS, "Scroll: D1 Mushroom Grotto", DUNGEON_SCROLLS, 0x01));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D2 Tail Palace", DUNGEON_SCROLLS, 0x02));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D3 Kalyxo Castle", DUNGEON_SCROLLS, 0x04));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D4 Zora Temple", DUNGEON_SCROLLS, 0x08));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D5 Glacia Estate", DUNGEON_SCROLLS, 0x10));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D6 Goron Mines", DUNGEON_SCROLLS, 0x20));
        rows.add(flag(TAB_DUNGEONS, "Scroll: D7 Dragon Ship", DUNGEON_SCROLLS, 0x40));

        rows.add(flag(TAB_DUNGEONS, "WaterGate: Legacy gate 27", WATER_GATE_STATES, 0x01));
        rows.add(flag(TAB_DUNGEONS, "WaterGate: Legacy gate 25", WATER_GATE_STATES, 0x02));
        rows.add(flag(TAB_DUNGEONS, "WaterGate: D4 Reservoir High", WATER_GATE_STATES, 0x04));
        rows.add(flag(TAB_DUNGEONS, "WaterGate: D4 Service Released", WATER_GATE_STATES, 0x08));
        rows.add(flag(TAB_DUNGEONS, "WaterGate: D4 Inlet Released", WATER_GATE_STATES, 0x10));

        rows.add(bits(TAB_DUNGEONS, "TingleMaps ($7EF396)", TINGLE_MAPS));
        rows.add(value(TAB_DUNGEONS, "TingleId (next map 0-7)", TINGLE_ID, 7));
        rows.add(value(TAB_DUNGEONS, "Current dungeon keys", CURRENT_KEYS, 9));
        for (int i = 0; i < KEY_DUNGEONS.length; i++) {
            rows.add(value(TAB_DUNGEONS, "Keys: " + KEY_DUNGEONS[i], KEYS_SEWER + i, 9));
        }
        rows.add(value(TAB_DUNGEONS, "Keys: Dungeon $1C ($7EF450)", KEYS_DUNGEON_1C, 9));
        rows.add(value(TAB_DUNGEONS, "Keys: Dungeon $1E ($7EF451)", KEYS_DUNGEON_1E, 9));
        rows.add(value(TAB_DUNGEONS, "Jabu Water Level ($7EF482)", JABU_WATER_LEVEL, 2, 1, JABU_WATER_NAMES));
        rows.add(bits(TAB_DUNGEONS, "CompassSet1 ($7EF364)", COMPASS_SET_1));
        rows.add(bits(TAB_DUNGEONS, "CompassSet2 ($7EF365)", COMPASS_SET_2));
        rows.add(bits(TAB_DUNGEONS, "BigKeySet1 ($7EF366)", BIG_KEY_SET_1));
        rows.add(bits(TAB_DUNGEONS, "BigKeySet2 ($7EF367)", BIG_KEY_SET_2));
        rows.add(bits(TAB_DUNGEONS, "DungeonMapSet1 ($7EF368)", DUNGEON_MAP_SET_1));
        rows.add(bits(TAB_DUNGEONS, "DungeonMapSet2 ($7EF369)", DUNGEON_MAP_SET_2));

        // Story flags tab: every named progress byte and individual bit from Core/sram.asm
        rows.add(value(TAB_STORY, "GameState ($7EF3C5)", GAME_STATE, 3, 1, GAME_STATES));
        rows.add(value(TAB_STORY, "IntroState ($7EF39E)", INTRO_STATE, 7));
        rows.add(value(TAB_STORY, "MapIcon ($7EF3C7)", MAP_ICON, 9, 1, MAP_ICONS));
        rows.add(value(TAB_STORY, "SpawnPoint ($7EF3C8)", SPAWN_POINT, 7, 1, SPAWN_POINTS));
        rows.add(flag(TAB_STORY, "SavedWorld: Dark World ($40)", SAVED_WORLD, 0x40));
        rows.add(value(TAB_STORY, "FollowerId ($7EF3CC)", FOLLOWER, 15));

        rows.add(flag(TAB_STORY, "OOSPROG: IntroComplete", OOSPROG, 0x01));
        rows.add(flag(TAB_STORY, "OOSPROG: HallOfSecrets", OOSPROG, 0x02));
        rows.add(flag(TAB_STORY, "OOSPROG: PendantQuest", OOSPROG, 0x04));
        rows.add(flag(TAB_STORY, "OOSPROG: VillageElderMet", OOSPROG, 0x10));
        rows.add(flag(TAB_STORY, "OOSPROG: FortressComplete", OOSPROG, 0x80));
        rows.add(bits(TAB_STORY, "OOSPROG raw ($7EF3D6)", OOSPROG));

        rows.add(flag(TAB_STORY, "OOSPROG2: ImpaIntro", OOSPROG2, 0x01));
        rows.add(flag(TAB_STORY, "OOSPROG2: SanctuaryVisit", OOSPROG2, 0x02));
        rows.add(flag(TAB_STORY, "OOSPROG2: KydrogEncounter", OOSPROG2, 0x04));
        rows.add(flag(TAB_STORY, "OOSPROG2: ImpaLeftHouse", OOSPROG2, 0x08));
        rows.add(flag(TAB_STORY, "OOSPROG2: LegacyHouseFlag", OOSPROG2, 0x10));
        rows.add(flag(TAB_STORY, "OOSPROG2: BookOfSecrets", OOSPROG2, 0x20));
        rows.add(flag(TAB_STORY, "OOSPROG2: FortuneTellerFlip", OOSPROG2, 0x40));
        rows.add(flag(TAB_STORY, "OOSPROG2: Part0Storm (rain/dim)", OOSPROG2, 0x80));
        rows.add(bits(TAB_STORY, "OOSPROG2 raw ($7EF3C6)", OOSPROG2));

        rows.add(flag(TAB_STORY, "MakuTree: Met Link", MAKU_TREE_QUEST, 0x01));
        rows.add(flag(TAB_STORY, "MakuTree: Has Maku Seed", MAKU_TREE_QUEST, 0x02));
        rows.add(bits(TAB_STORY, "MakuTreeQuest raw ($7EF3D4)", MAKU_TREE_QUEST));

        rows.add(flag(TAB_STORY, "SideQuest: Met Mask Salesman", SIDE_QUEST, 0x01));
        rows.add(flag(TAB_STORY, "SideQuest: Cursed Cucco", SIDE_QUEST, 0x02));
        rows.add(flag(TAB_STORY, "SideQuest: Deku Scrub Found", SIDE_QUEST, 0x04));
        rows.add(flag(TAB_STORY, "SideQuest: Got Mushroom", SIDE_QUEST, 0x08));
        rows.add(flag(TAB_STORY, "SideQuest: Old Man Mountain", SIDE_QUEST, 0x10));
        rows.add(flag(TAB_STORY, "SideQuest: Goron Quest", SIDE_QUEST, 0x20));
        rows.add(bits(TAB_STORY, "SideQuestProgress raw ($7EF3D7)", SIDE_QUEST));

        rows.add(flag(TAB_STORY, "SideQuest2: Ranch Girl (Ocarina)", SIDE_QUEST_2, 0x01));
        rows.add(flag(TAB_STORY, "SideQuest2: Epona's Song", SIDE_QUEST_2, 0x02));
        rows.add(flag(TAB_STORY, "SideQuest2: Song of Healing", SIDE_QUEST_2, 0x04));
        rows.add(flag(TAB_STORY, "SideQuest2: Fortune Teller", SIDE_QUEST_2, 0x08));
        rows.add(flag(TAB_STORY, "SideQuest2: Deku Soul Freed", SIDE_QUEST_2, 0x10));
        rows.add(flag(TAB_STORY, "SideQuest2: Tingle Met", SIDE_QUEST_2, 0x20));
        rows.add(flag(TAB_STORY, "SideQuest2: Beanstalk Grown", SIDE_QUEST_2, 0x40));
        rows.add(bits(TAB_STORY, "SideQuestProgress2 raw ($7EF3D8)", SIDE_QUEST_2));

        rows.add(value(TAB_STORY, "KydrogFaroreRemoved ($7EF300)", KYDROG_FARORE_REMOVED, 1));
        rows.add(value(TAB_STORY, "DekuMaskQuestDone ($7EF301)", DEKU_MASK_QUEST_DONE, 1));
        rows.add(value(TAB_STORY, "ZoraMaskQuestDone ($7EF302)", ZORA_MASK_QUEST_DONE, 1));
        rows.add(value(TAB_STORY, "InCutSceneFlag ($7EF303)", IN_CUTSCENE_FLAG, 1));
        rows.add(value(TAB_STORY, "ElderGuideStage ($7EF304)", ELDER_GUIDE_STAGE, 15));
        rows.add(value(TAB_STORY, "ZoraWaterfallHint ($7EF305)", ZORA_WATERFALL_HINT, 1));
        rows.add(flag(TAB_STORY, "CastleAmbush: Captured", CASTLE_AMBUSH_FLAGS, 0x01));
        rows.add(flag(TAB_STORY, "CastleAmbush: Escaped", CASTLE_AMBUSH_FLAGS, 0x02));

        rows.add(flag(TAB_STORY, "Part00: Villager B shown", PART00_FLAGS, 0x01));
        rows.add(flag(TAB_STORY, "Part00: Village hole checkpoint", PART00_FLAGS, 0x02));
        rows.add(flag(TAB_STORY, "Part00: Death pending B2", PART00_FLAGS, 0x04));
        rows.add(flag(TAB_STORY, "Part00: House revisit B2", PART00_FLAGS, 0x08));

        rows.add(flag(TAB_STORY, "Dream 1: Wisdom (Sealing War)", DREAMS, 0x01));
        rows.add(flag(TAB_STORY, "Dream 2: Power (Oracle's Choice)", DREAMS, 0x02));
        rows.add(flag(TAB_STORY, "Dream 3: Courage (Healing Rev)", DREAMS, 0x04));

        rows.add(flag(TAB_STORY, "Bean: Planted", MAGIC_BEAN_PROGRESS, 0x01));
        rows.add(flag(TAB_STORY, "Bean: Pollinated", MAGIC_BEAN_PROGRESS, 0x02));
        rows.add(flag(TAB_STORY, "Bean: Watered", MAGIC_BEAN_PROGRESS, 0x04));
        rows.add(flag(TAB_STORY, "Bean: Day 1", MAGIC_BEAN_PROGRESS, 0x08));
        rows.add(flag(TAB_STORY, "Bean: Day 2", MAGIC_BEAN_PROGRESS, 0x10));
        rows.add(flag(TAB_STORY, "Bean: Day 3", MAGIC_BEAN_PROGRESS, 0x20));
        rows.add(flag(TAB_STORY, "Bean: Complete", MAGIC_BEAN_PROGRESS, 0x40));

        rows.add(flag(TAB_STORY, "EonOwl: Arrival talked", EON_OWL_FLAGS, 0x01));
        rows.add(flag(TAB_STORY, "EonOwl: Sword ($E6) talked", EON_OWL_FLAGS, 0x02));
        rows.add(flag(TAB_STORY, "WolfDig: Ranch cache", WOLF_DIG_FLAGS, 0x01));

        rows.add(flag(TAB_STORY, "Goron: Sentry C checked", EON_GORON_QUEST, 0x01));
        rows.add(flag(TAB_STORY, "Goron: Sentry D checked", EON_GORON_QUEST, 0x02));
        rows.add(flag(TAB_STORY, "Goron: Sentry E checked", EON_GORON_QUEST, 0x04));
        rows.add(flag(TAB_STORY, "Goron: Sentry G checked", EON_GORON_QUEST, 0x08));
        rows.add(flag(TAB_STORY, "Goron: Sentry H checked", EON_GORON_QUEST, 0x10));
        rows.add(flag(TAB_STORY, "Goron: Roll Call HP paid", EON_GORON_QUEST, 0x20));
        rows.add(flag(TAB_STORY, "Goron: Meat Feast done", EON_GORON_QUEST, 0x80));

        rows.add(flag(TAB_STORY, "Richard: Asked", RICHARD_QUEST_FLAGS, 0x01));
        rows.add(flag(TAB_STORY, "Richard: Found at night", RICHARD_QUEST_FLAGS, 0x02));
        rows.add(flag(TAB_STORY, "Richard: Delivered", RICHARD_QUEST_FLAGS, 0x04));
        rows.add(flag(TAB_STORY, "Richard: Rewarded", RICHARD_QUEST_FLAGS, 0x08));

        rows.add(value(TAB_STORY, "Trading Quest Step ($7EF43D)", TRADING_QUEST_STEP, 10, 1, TRADING_STEP_NAMES));
        rows.add(flag(TAB_STORY, "DekuVillage: Met King", DEKU_VILLAGE_QUEST, 0x01));
        rows.add(flag(TAB_STORY, "DekuVillage: Trial Active", DEKU_VILLAGE_QUEST, 0x02));
        rows.add(flag(TAB_STORY, "DekuVillage: Trial Won", DEKU_VILLAGE_QUEST, 0x04));
        rows.add(flag(TAB_STORY, "DekuVillage: King Blessed", DEKU_VILLAGE_QUEST, 0x08));
        rows.add(flag(TAB_STORY, "DekuVillage: Merchant Gift", DEKU_VILLAGE_QUEST, 0x10));
        rows.add(flag(TAB_STORY, "DekuVillage: Cursed", DEKU_VILLAGE_QUEST, 0x20));
        rows.add(bits(TAB_STORY, "DekuVillageQuest raw ($7EF456)", DEKU_VILLAGE_QUEST));
        rows.add(bits(TAB_STORY, "EonJabuQuest ($7EF481)", EON_JABU_QUEST));
        rows.add(flag(TAB_STORY, "GlaciaWitches: Courtyard", GLACIA_WITCHES_FLAGS, 0x01));
        rows.add(flag(TAB_STORY, "GlaciaWitches: Mountain", GLACIA_WITCHES_FLAGS, 0x02));
        rows.add(bits(TAB_STORY, "GlaciaWitches raw ($7EF483)", GLACIA_WITCHES_FLAGS));

        return Collections.unmodifiableList(rows);
    }

    /** Every save-block byte from checkpoints.json not already listed in CATALOG. */
    private static List<Row> extraCheckpointStoryRows(Map<Integer, String> checkpointNames) {
        if (checkpointNames == null || checkpointNames.isEmpty()) return Collections.emptyList();
        Set<Integer> covered = new HashSet<>();
        for (Row row : CATALOG) {
            if (row.kind != Kind.ACTION) covered.add(row.addr);
            if (row.kind == Kind.WORD) covered.add(row.addr + 1);
        }
        covered.add(RUPEES_DISPLAY);
        covered.add(RUPEES_DISPLAY + 1);
        covered.add(GOLDSTAR_VERSION);

        List<Row> extra = new ArrayList<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(checkpointNames).entrySet()) {
            int addr = e.getKey();
            if (addr < 0x7EF000 || addr > 0x7EF4FF || covered.contains(addr)) continue;
            extra.add(bits(TAB_STORY, e.getValue() + String.format(Locale.ROOT, " ($%06X)", addr), addr));
        }
        return extra;
    }

    static List<Row> rows(String tab, Map<Integer, String> checkpointNames) {
        List<Row> out = new ArrayList<>();
        for (Row row : CATALOG) {
            if (row.tab.equals(tab)) out.add(row);
        }
        if (TAB_STORY.equals(tab)) {
            out.addAll(extraCheckpointStoryRows(checkpointNames));
        }
        return out;
    }

    /** Current value, or -1 when it cannot be read. FLAG/SONG return 1 (set) or 0 (clear). */
    static int read(Row row, Memory memory) {
        if (row.kind == Kind.ACTION) return 0;
        int lo = memory.readByte(row.addr);
        if (lo < 0) return -1;
        if (row.kind == Kind.FLAG) return (lo & row.mask) != 0 ? 1 : 0;
        if (row.kind == Kind.SONG) return lo >= row.mask ? 1 : 0;
        if (row.kind != Kind.WORD) return lo;
        int hi = memory.readByte(row.addr + 1);
        return hi < 0 ? -1 : lo | (hi << 8);
    }

    static String valueText(Row row, int value) {
        if (row.kind == Kind.ACTION) return "";
        if (value < 0) return "unavailable";
        if (row.kind == Kind.BITS) return String.format(Locale.ROOT, "$%02X", value);
        if (row.kind == Kind.FLAG) {
            return value != 0
                    ? String.format(Locale.ROOT, "ON ($%02X)", row.mask)
                    : String.format(Locale.ROOT, "OFF ($%02X)", row.mask);
        }
        if (row.kind == Kind.SONG) return value != 0 ? "ON" : "OFF";
        if (row.addr == SKY_SOARING_LEARNED) {
            return value == SKY_SOARING_TOKEN
                    ? "ON ($A5)"
                    : (value == 0 ? "OFF" : String.format(Locale.ROOT, "OFF ($%02X)", value));
        }
        if (row.names != null && value < row.names.length) return row.names[value];
        if (row.max == 1) return value == 0 ? "no" : (value == 1 ? "yes" : Integer.toString(value));
        return Integer.toString(value);
    }

    /** Null during normal play; otherwise why writes are refused. */
    static String gameplayProblem(Memory memory) {
        int module = memory.readByte(MODULE);
        int sub = memory.readByte(SUBMODULE);
        if (module < 0 || sub < 0) return "Could not read the game state";
        if (!Progression.isPlayModule(module) || sub != 0) return "Resume normal play before editing items";
        return null;
    }

    /** Left/Right on a VALUE or WORD row: one step down or up, clamped to 0..max. */
    static String step(Row row, Memory memory, int direction) {
        if (row.kind != Kind.VALUE && row.kind != Kind.WORD) return null;
        String problem = gameplayProblem(memory);
        if (problem != null) return problem;
        int current = read(row, memory);
        if (current < 0) return "Could not read " + row.label;
        int next = row.addr == SKY_SOARING_LEARNED
                ? (direction > 0 ? SKY_SOARING_TOKEN : 0)
                : Math.max(0, Math.min(row.max, current + direction * Math.max(1, row.step)));
        return set(row, memory, next);
    }

    /**
     * A on VALUE/WORD: 0 <-> max. A on BITS: flip bit.
     * A on FLAG: flip named bit and sync SRAM. A on SONG: raise/lower Ocarina threshold.
     */
    static String toggle(Row row, Memory memory, int bit) {
        String problem = gameplayProblem(memory);
        if (problem != null) return problem;
        if (row.kind == Kind.ACTION) return runAction(row.label, memory);
        if (row.kind == Kind.FLAG) {
            int raw = memory.readByte(row.addr);
            if (raw < 0) return "Could not read " + row.label;
            int next = raw ^ row.mask;
            if (row.addr == SWORD_SLOT_FLAGS && (next & row.mask) != 0) {
                next |= 0x80; // Keep two-slot flags initialized so menu sync won't clear heavy bit.
            }
            if (!memory.writeByte(row.addr, next)) return "Write failed: " + row.label;
            if (!memory.syncSram()) return row.label + " written to WRAM; SRAM sync failed";
            return row.label + ": " + ((next & row.mask) != 0 ? "ON" : "OFF");
        }
        if (row.kind == Kind.SONG) {
            int raw = memory.readByte(row.addr);
            if (raw < 0) return "Could not read " + row.label;
            int next = raw >= row.mask ? row.mask - 1 : row.mask;
            if (!memory.writeByte(row.addr, next)) return "Write failed: " + row.label;
            if (!memory.syncSram()) return row.label + " written to WRAM; SRAM sync failed";
            return row.label + ": " + (next >= row.mask ? "ON" : "OFF")
                    + String.format(Locale.ROOT, " ($7EF34C=%d)", next);
        }
        int current = read(row, memory);
        if (current < 0) return "Could not read " + row.label;
        if (row.kind == Kind.BITS) {
            int next = current ^ (1 << (bit & 7));
            if (!memory.writeByte(row.addr, next)) return "Write failed: " + row.label;
            if (!memory.syncSram()) return row.label + " written to WRAM; SRAM sync failed";
            return String.format(Locale.ROOT, "%s bit %d %s ($%02X)", row.label, bit & 7,
                    (next & (1 << (bit & 7))) != 0 ? "set" : "cleared", next);
        }
        if (row.addr == SKY_SOARING_LEARNED) {
            return set(row, memory, current == SKY_SOARING_TOKEN ? 0 : SKY_SOARING_TOKEN);
        }
        return set(row, memory, current == 0 ? row.max : 0);
    }

    private static String set(Row row, Memory memory, int value) {
        boolean ok = row.kind == Kind.WORD ? memory.writeWord(row.addr, value) : memory.writeByte(row.addr, value);
        if (!ok) return "Write failed: " + row.label;
        // Companion bytes so newly granted items & HUD display immediately and survive save.
        if (row.addr == RUPEES) ok = memory.writeWord(RUPEES_DISPLAY, value);
        if (value > 0 && row.addr == SWITCH_HOOK_OWNED) ok = raise(memory, HOOKSHOT, 1);
        if (value > 0 && row.addr == GOLDSTAR_OWNED) {
            ok = raise(memory, HOOKSHOT, 1) && memory.writeByte(GOLDSTAR_VERSION, GOLDSTAR_VERSION_OK);
        }
        if (value > 0 && row.addr == PORTAL_ROD_OWNED) ok = raise(memory, CUSTOM_RODS, 2);
        if (value > 0 && row.addr == BOMBCHU_AMMO) ok = raise(memory, BOMBCHU_OWNED, 1);
        if (value > 0 && row.addr == BOOTS) ok = orBits(memory, ABILITY, 0x04);
        if (value > 0 && row.addr == FLIPPERS) ok = orBits(memory, ABILITY, 0x02);
        if (row.addr == ACTIVE_FORM && value >= 0 && value < FORM_GFX_BANKS.length) {
            ok = memory.writeByte(LINK_GRAPHICS, FORM_GFX_BANKS[value]);
        }
        if (!ok) return row.label + " set, but its companion byte did not write";
        if (!memory.syncSram()) return row.label + " written to WRAM; SRAM sync failed";
        return row.label + ": " + valueText(row, value);
    }

    private static boolean raise(Memory memory, int addr, int floor) {
        int cur = memory.readByte(addr);
        return cur >= 0 && (cur >= floor || memory.writeByte(addr, floor));
    }

    private static boolean orBits(Memory memory, int addr, int bits) {
        int cur = memory.readByte(addr);
        return cur >= 0 && memory.writeByte(addr, cur | bits);
    }

    /**
     * Every Y-item, mask, ring, sword/shield/mail/glove upgrade, capacity
     * upgrade and full supply. Reads first and writes nothing if any read
     * fails. Keeps higher values already owned. Teaches Epona's Song and Sky
     * Soaring. Leaves story flags, keys and quests alone (use the FULL preset
     * on the Presets tab to set story/dungeon flags as well).
     */
    static Map<Integer, Integer> maxEverythingWrites(Memory memory) {
        Map<Integer, Integer> w = new LinkedHashMap<>();
        int[][] floors = {
                {BOW, 4}, {BOOMERANG, 2}, {HOOKSHOT, 1}, {POWDER, 2}, {FIRE_ROD, 1},
                {ICE_ROD, 1}, {LAMP, 1}, {HAMMER, 1}, {FLUTE, FLUTE_ALL_SONGS}, {FEATHER, 1},
                {BOOK, 1}, {SOMARIA, 1}, {CUSTOM_RODS, 2}, {FISHING_ROD, 1}, {MIRROR, 2},
                {GLOVES, 2}, {BOOTS, 1}, {FLIPPERS, 1}, {MOON_PEARL, 1},
                {SWORD, 4}, {SHIELD, 3}, {ARMOR, 2},
                {BOMB_CAPACITY, 7}, {ARROW_CAPACITY, 7}, {MAGIC_USAGE, 2},
                {PORTAL_ROD_OWNED, 1}, {GOLDSTAR_OWNED, 1}, {BYRNA_OWNED, 1},
                {BOMBCHU_OWNED, 1}, {SWITCH_HOOK_OWNED, 1},
                {CLOCKWORK_BEETLE_OWNED, 1}, {SWITCH_HOOK_COL21_OWNED, 1},
                {LENS_OF_TRUTH_OWNED, 1}, {TEMPEST_HARP_OWNED, 1},
                {BOMBS, 50}, {ARROWS, 70}, {DEKU_STICKS, 10}, {BOMBCHU_AMMO, BOMBCHU_AMMO_MAX},
                {BANANAS, 10}, {ROCK_MEAT, 5}, {HEALTH_MAX, 0xA0}, {HEART_PIECES, 3}, {MAGIC, 0x80},
        };
        for (int[] f : floors) {
            int cur = memory.readByte(f[0]);
            if (cur < 0) return null;
            w.put(f[0], Math.max(cur, f[1]));
        }
        w.put(GOLDSTAR_VERSION, GOLDSTAR_VERSION_OK);
        w.put(SKY_SOARING_LEARNED, SKY_SOARING_TOKEN);
        for (int mask : MASKS) w.put(mask, 1);
        for (int ring : new int[]{FOUND_RINGS, MAGIC_RINGS}) {
            int cur = memory.readByte(ring);
            if (cur < 0) return null;
            w.put(ring, cur | ALL_RINGS);
        }
        for (int ringEx : new int[]{FOUND_RINGS_EX, MAGIC_RINGS_EX}) {
            int cur = memory.readByte(ringEx);
            if (cur < 0) return null;
            w.put(ringEx, cur | ALL_RINGS_EX);
        }
        int songBits = memory.readByte(SONG_BITS);
        if (songBits < 0) return null;
        w.put(SONG_BITS, songBits | ALL_SONG_BITS);
        int bowFlags = memory.readByte(SLINGSHOT_BOW_FLAGS);
        if (bowFlags < 0) return null;
        w.put(SLINGSHOT_BOW_FLAGS, bowFlags | ALL_SLINGSHOT_BOW_FLAGS);
        int ability = memory.readByte(ABILITY);
        if (ability < 0) return null;
        w.put(ABILITY, ability | 0x86); // lift, run (boots), swim (flippers)
        int quest2 = memory.readByte(SIDE_QUEST_2);
        if (quest2 < 0) return null;
        w.put(SIDE_QUEST_2, quest2 | EPONA_SONG);
        int[] fill = {6, 6, 5, 5}; // fairy, fairy, blue potion, blue potion
        for (int i = 0; i < 4; i++) {
            int cur = memory.readByte(BOTTLE_1 + i);
            if (cur < 0) return null;
            w.put(BOTTLE_1 + i, cur >= 3 ? cur : fill[i]); // 0 none, 2 empty
        }
        int index = memory.readByte(BOTTLE_INDEX);
        if (index < 0) return null;
        w.put(BOTTLE_INDEX, index >= 1 && index <= 4 ? index : 1);
        w.put(HEALTH_CURRENT, w.get(HEALTH_MAX));
        return w;
    }

    static String applyAll(Map<Integer, Integer> writes, Memory memory, String done) {
        if (writes == null) return "Could not read inventory; nothing written";
        for (Map.Entry<Integer, Integer> e : writes.entrySet()) {
            if (!memory.writeByte(e.getKey(), e.getValue())) {
                return String.format(Locale.ROOT, "Incomplete: write failed at $%06X; earlier grants applied", e.getKey());
            }
        }
        if (!memory.syncSram()) return done + " (SRAM sync failed)";
        return done;
    }

    /** Quick one-tap grants on the Items tab. */
    static String runAction(String label, Memory memory) {
        Map<Integer, Integer> w = new LinkedHashMap<>();
        switch (label) {
            case MAX_EVERYTHING: {
                Map<Integer, Integer> writes = maxEverythingWrites(memory);
                if (writes == null) return "Could not read inventory; nothing written";
                if (!memory.writeWord(RUPEES, 999) || !memory.writeWord(RUPEES_DISPLAY, 999)) {
                    return "Rupee write failed; nothing else written";
                }
                return applyAll(writes, memory, "Maxed: all items, masks, rings, songs, upgrades, 4 bottles, 20 hearts, 999 rupees");
            }
            case STARTER_KIT: {
                int[] addresses = {SWORD, SHIELD, LAMP, BOMBS, ARROWS};
                int[] minimums = {1, 1, 1, 10, 30};
                for (int i = 0; i < addresses.length; i++) {
                    int current = memory.readByte(addresses[i]);
                    if (current < 0) return "Starter kit could not read inventory; nothing written";
                    w.put(addresses[i], Math.max(current, minimums[i]));
                }
                return applyAll(w, memory, "Granted starter kit: sword, shield, lamp, bombs and arrows");
            }
            case FULL_HEAL: {
                int max = memory.readByte(HEALTH_MAX);
                if (max < 0) return "Could not read max health";
                if (max == 0) max = 0x18;
                w.put(HEALTH_CURRENT, max);
                return applyAll(w, memory, "Healed to max (" + max + ")");
            }
            case FILL_MAGIC:
                w.put(MAGIC, 0x80);
                return applyAll(w, memory, "Magic full");
            case RUPEES_999:
                if (!memory.writeWord(RUPEES, 999) || !memory.writeWord(RUPEES_DISPLAY, 999)) {
                    return "Rupee write failed";
                }
                return memory.syncSram() ? "999 rupees (synced to SRAM)" : "999 rupees (SRAM sync failed)";
            case FILL_BOTTLES: {
                int[] fill = {6, 6, 5, 5};
                for (int i = 0; i < 4; i++) w.put(BOTTLE_1 + i, fill[i]);
                int index = memory.readByte(BOTTLE_INDEX);
                if (index < 0) return "Could not read bottle index";
                if (index < 1 || index > 4) w.put(BOTTLE_INDEX, 1);
                return applyAll(w, memory, "Filled 4 bottles: 2 fairies, 2 blue potions");
            }
            case ALL_MASKS:
                for (int mask : MASKS) w.put(mask, 1);
                return applyAll(w, memory, "Gave all 9 masks (Deku, Zora, Wolf, Bunny, Stone, Garo, Stalfos, Frog, Fierce Deity)");
            case ALL_RINGS_ACTION:
                for (int ring : new int[]{FOUND_RINGS, MAGIC_RINGS}) {
                    int cur = memory.readByte(ring);
                    if (cur < 0) return "Could not read rings";
                    w.put(ring, cur | ALL_RINGS);
                }
                for (int ringEx : new int[]{FOUND_RINGS_EX, MAGIC_RINGS_EX}) {
                    int cur = memory.readByte(ringEx);
                    if (cur < 0) return "Could not read extra rings";
                    w.put(ringEx, cur | ALL_RINGS_EX);
                }
                return applyAll(w, memory, "Gave all 9 rings: equip one from Rings");
            case EQUIP_FIERCE_DEITY: {
                w.put(FIERCE_DEITY_MASK_OWNED, 1);
                w.put(BOUND_MASK, 9);
                boolean inPlay = gameplayProblem(memory) == null;
                if (inPlay) {
                    w.put(ACTIVE_FORM, FORM_FIERCE_DEITY);
                    w.put(LINK_GRAPHICS, FORM_GFX_BANKS[FORM_FIERCE_DEITY]);
                }
                return applyAll(w, memory, inPlay
                        ? "Equipped Fierce Deity (Mask owned, R=Fierce Deity, form=$0D)"
                        : "Granted Fierce Deity Mask & R binding");
            }
            case HEAVY_SWORD: {
                int cur = memory.readByte(SWORD_SLOT_FLAGS);
                if (cur < 0) return "Could not read sword slots";
                w.put(SWORD_SLOT_FLAGS, cur | HEAVY_SWORD_GRANT);
                return applyAll(w, memory, "Gave the heavy sword (broken)");
            }
            default:
                return "Unknown action " + label;
        }
    }
}
