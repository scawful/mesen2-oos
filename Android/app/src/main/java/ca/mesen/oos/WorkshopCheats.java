package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated Oracle of Secrets WRAM cheats for Workshop. Addresses match the
 * mesen2_client OracleRAM / ITEMS registry ($7EFxxx save mirror).
 */
final class WorkshopCheats {
    enum Kind {
        BYTE,
        WORD,
        CYCLE,
        OR_BITS,
        INFO
    }

    static final class Action {
        final String group;
        final String label;
        final int addr;
        final int value;
        final Kind kind;

        Action(String group, String label, int addr, int value, Kind kind) {
            this.group = group;
            this.label = label;
            this.addr = addr;
            this.value = value;
            this.kind = kind;
        }
    }

    // OracleRAM / ALTTP save mirror
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
    static final int SOMARIA = 0x7EF350;
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
    static final int BOTTLE_INDEX = 0x7EF34F;
    static final int CUSTOM_RODS = 0x7EF351;
    static final int BOMB_CAPACITY = 0x7EF370;
    static final int ARROW_CAPACITY = 0x7EF371;
    static final int MAGIC_USAGE = 0x7EF37B;
    static final int RUPEES = 0x7EF360;
    static final int HEALTH_MAX = 0x7EF36C;
    static final int HEALTH_CURRENT = 0x7EF36D;
    static final int MAGIC = 0x7EF36E;
    static final int ARROWS = 0x7EF377;
    static final int ABILITY = 0x7EF379;
    static final int CRYSTALS = 0x7EF37A;
    static final int PENDANTS = 0x7EF374;
    static final int GAME_STATE = 0x7EF3C5;
    static final int OOSPROG = 0x7EF3D6;
    static final int OOSPROG2 = 0x7EF3C6;
    // New-feature SRAM (oracle-of-secrets Core/sram.asm, master 2026-11-15)
    static final int DEKU_STICKS = 0x7EF395;
    // Magic rings with !ENABLE_RING_SRAM_RELOCATE = 1 (Items/magic_rings.asm):
    // FOUNDRINGS + MAGICRINGS, six rings in bits 0-5.
    static final int FOUND_RINGS = 0x7EF3A1;
    static final int MAGIC_RINGS = 0x7EF3A2;
    static final int ALL_RINGS = 0x3F;
    static final int PORTAL_ROD_OWNED = 0x7EF3A6;
    static final int GOLDSTAR_OWNED = 0x7EF3A9;
    static final int BYRNA_OWNED = 0x7EF3AC;
    static final int BOMBCHU_OWNED = 0x7EF3AD;
    static final int SWITCH_HOOK_OWNED = 0x7EF3AE;
    static final int GARO_MASK_OWNED = 0x7EF3AF;
    static final int EPONA_SONG = 0x02; // !SideQuest2_EponaSong (song 6)
    static final int BOMBCHU_AMMO = 0x7EF3B0;
    static final int BINDING_VERSION = 0x7EF3B9;
    static final int BINDING_Y = 0x7EF3BA;
    static final int BINDING_X = 0x7EF3BB;
    static final int SIDE_QUEST_2 = 0x7EF3D8;
    static final int RANCH_GIRL_CURED = 0x01;

    static final String GROUP_QUICK = "Quick";
    static final String GROUP_FEATURES = "Features";
    static final String GROUP_ITEMS = "Items";
    static final String GROUP_GEAR = "Gear";
    static final String GROUP_STORY = "Story";
    /** Story checkpoints (Progression); rows come from ProgressionData, not catalog(). */
    static final String GROUP_PROGRESS = "Progress";

    static final String MAX_EVERYTHING = "Max everything";
    /** Flute byte: 1 = Ocarina, 2..7 = Ocarina plus 1..6 songs (Items/ocarina.asm). */
    static final int FLUTE_ALL_SONGS = 7;
    static final int[] MASKS = {0x7EF347, 0x7EF348, 0x7EF349, 0x7EF352, 0x7EF358, 0x7EF3AF};

    private WorkshopCheats() {}

    /**
     * Every Y item, mask, ring, sword/shield/mail/glove upgrade, capacity
     * upgrade and full supply. Reads first and writes nothing if any read
     * fails. Keeps bottle contents and higher values already owned. Teaches
     * Epona's Song (SideQuestProg2 bit 1). Leaves other
     * story flags, keys, quest items and the equipped ring alone.
     */
    static Map<Integer, Integer> maxEverythingWrites(Memory memory) {
        Map<Integer, Integer> w = new LinkedHashMap<>();
        int[][] floors = {
                {BOW, 4}, {BOOMERANG, 2}, {HOOKSHOT, 1}, {POWDER, 2}, {FIRE_ROD, 1},
                {ICE_ROD, 1}, {LAMP, 1}, {HAMMER, 1}, {FLUTE, FLUTE_ALL_SONGS}, {FEATHER, 1},
                {BOOK, 1}, {SOMARIA, 1}, {CUSTOM_RODS, 2}, {MIRROR, 2},
                {GLOVES, 2}, {BOOTS, 1}, {FLIPPERS, 1}, {MOON_PEARL, 1},
                {SWORD, 4}, {SHIELD, 3}, {ARMOR, 2},
                {BOMB_CAPACITY, 7}, {ARROW_CAPACITY, 7}, {MAGIC_USAGE, 1},
                {PORTAL_ROD_OWNED, 1}, {GOLDSTAR_OWNED, 1}, {BYRNA_OWNED, 1},
                {BOMBCHU_OWNED, 1}, {SWITCH_HOOK_OWNED, 1},
                {BOMBS, 50}, {ARROWS, 70}, {DEKU_STICKS, 10}, {BOMBCHU_AMMO, 20},
                {HEALTH_MAX, 0xA0}, {MAGIC, 0x80},
        };
        for (int[] f : floors) {
            int cur = memory.readByte(f[0]);
            if (cur < 0) return null;
            w.put(f[0], Math.max(cur, f[1]));
        }
        for (int mask : MASKS) w.put(mask, 1);
        for (int ring : new int[]{FOUND_RINGS, MAGIC_RINGS}) {
            int cur = memory.readByte(ring);
            if (cur < 0) return null;
            w.put(ring, cur | ALL_RINGS);
        }
        int ability = memory.readByte(ABILITY);
        if (ability < 0) return null;
        w.put(ABILITY, ability | 0x86); // lift, run (boots), swim (flippers)
        int quest2 = memory.readByte(SIDE_QUEST_2);
        if (quest2 < 0) return null;
        w.put(SIDE_QUEST_2, quest2 | EPONA_SONG); // song only; Ranch Girl bit 0 untouched
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
                return "Incomplete: write failed at $" + Integer.toHexString(e.getKey()).toUpperCase(java.util.Locale.ROOT)
                        + "; earlier grants applied";
            }
        }
        return done;
    }

    static List<String> groups() {
        List<String> groups = new ArrayList<>();
        groups.add(GROUP_QUICK);
        groups.add(GROUP_FEATURES);
        groups.add(GROUP_PROGRESS);
        groups.add(GROUP_ITEMS);
        groups.add(GROUP_GEAR);

        return groups;
    }

    static List<Action> catalog() {
        List<Action> actions = new ArrayList<>();
        // Quick
        actions.add(new Action(GROUP_QUICK, MAX_EVERYTHING, 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "Basic starter kit", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "Full heal", HEALTH_CURRENT, 0, Kind.BYTE)); // value filled at apply
        actions.add(new Action(GROUP_QUICK, "Fill magic", MAGIC, 0x80, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "999 rupees", RUPEES, 999, Kind.WORD));
        actions.add(new Action(GROUP_QUICK, "50 bombs", BOMBS, 50, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "50 arrows", ARROWS, 50, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "20 hearts (max+fill)", HEALTH_MAX, 0xA0, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "Max Y-items pack", 0, 0, Kind.BYTE)); // special id via label
        // Features: grants for the newest playtest items. Builds without a
        // feature ignore its byte; read the ROM notes before trusting a result.
        actions.add(new Action(GROUP_FEATURES, "Switch Hook (+Hookshot)", SWITCH_HOOK_OWNED, 1, Kind.BYTE));
        actions.add(new Action(GROUP_FEATURES, "Deku Sticks x10", DEKU_STICKS, 10, Kind.BYTE));
        actions.add(new Action(GROUP_FEATURES, "Epona ready (ranch cured)", SIDE_QUEST_2, RANCH_GIRL_CURED, Kind.INFO));
        actions.add(new Action(GROUP_FEATURES, "Bombchus x20", BOMBCHU_AMMO, 20, Kind.BYTE));
        actions.add(new Action(GROUP_FEATURES, "Cane of Byrna", BYRNA_OWNED, 1, Kind.BYTE));
        actions.add(new Action(GROUP_FEATURES, "Garo mask", GARO_MASK_OWNED, 1, Kind.BYTE));
        actions.add(new Action(GROUP_FEATURES, "HUD Y/X binding", BINDING_Y, 0, Kind.INFO));
        // Items
        actions.add(new Action(GROUP_ITEMS, "Bow (silver+arrows)", BOW, 4, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Red boomerang", BOOMERANG, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Hookshot", HOOKSHOT, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Magic powder", POWDER, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fire rod", FIRE_ROD, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Ice rod", ICE_ROD, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Lamp", LAMP, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Hammer", HAMMER, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Ocarina (all songs)", FLUTE, FLUTE_ALL_SONGS, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Roc's feather", FEATHER, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Book of Secrets", BOOK, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Cane of Somaria", SOMARIA, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Magic mirror", MIRROR, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "All masks", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "All rings", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fishing + Portal Rod", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fill 4 bottles", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fairy in bottle 1", BOTTLE_1, 6, Kind.BYTE));
        // Gear
        actions.add(new Action(GROUP_GEAR, "Sword level", SWORD, 4, Kind.CYCLE));
        actions.add(new Action(GROUP_GEAR, "Shield level", SHIELD, 3, Kind.CYCLE));
        actions.add(new Action(GROUP_GEAR, "Red mail", ARMOR, 2, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Titan's mitt", GLOVES, 2, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Pegasus boots", BOOTS, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Zora flippers", FLIPPERS, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Moon pearl", MOON_PEARL, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Ability flags (dash…)", ABILITY, 0xDB, Kind.BYTE));
        return Collections.unmodifiableList(actions);
    }

    static List<Action> forGroup(String group) {
        List<Action> out = new ArrayList<>();
        for (Action action : catalog()) {
            if (action.group.equals(group)) {
                out.add(action);
            }
        }
        return out;
    }

    interface Memory {
        int readByte(int address);
        boolean writeByte(int address, int value);
        boolean writeWord(int address, int value);
    }

    private static final Memory LIVE = new Memory() {
        public int readByte(int address) { return MesenNative.readByte(address); }
        public boolean writeByte(int address, int value) { return MesenNative.writeByte(address, value); }
        public boolean writeWord(int address, int value) { return MesenNative.writeWord(address, value); }
    };

    static String rowLabel(Action action) { return rowLabel(action, LIVE); }

    static String rowLabel(Action action, Memory memory) {
        if (action.addr == 0) return action.label + " · one-time grant";
        if (action.kind == Kind.INFO) {
            if (action.addr == BINDING_Y) return bindingLabel(memory);
            int bits = memory.readByte(action.addr);
            String state = bits < 0 ? "unavailable"
                    : ((bits & action.value) == action.value ? "yes" : "no");
            return action.label + ": " + state + " · read-only";
        }
        int current = memory.readByte(action.addr);
        if (action.kind == Kind.WORD && current >= 0) {
            int high = memory.readByte(action.addr + 1);
            current = high < 0 ? -1 : current | (high << 8);
        }
        String value = current < 0 ? "unavailable" : Integer.toString(current);
        if (action.kind == Kind.CYCLE && current >= 0 && current <= action.value) {
            String[] labels = action.addr == SWORD
                    ? new String[]{"None", "Fighter", "Master", "Tempered", "Golden"}
                    : new String[]{"None", "Fighter", "Fire", "Mirror"};
            value = labels[current];
        }
        return action.label + ": " + value
                + (action.kind == Kind.CYCLE ? " · A: next level" : " · one-time grant");
    }

    /** Read-only view of the dual-item prototype's saved Y/X item ids. */
    static String bindingLabel(Memory memory) {
        int version = memory.readByte(BINDING_VERSION);
        int y = memory.readByte(BINDING_Y);
        int x = memory.readByte(BINDING_X);
        if (version < 0 || y < 0 || x < 0) return "HUD Y/X binding: unavailable";
        if (version == 0) return "HUD Y/X binding: not saved (bind from Items page)";
        return String.format(java.util.Locale.ROOT,
                "HUD Y/X binding: Y=$%02X X=$%02X (v%d) · read-only", y, x, version);
    }

    static String apply(Action action) { return apply(action, LIVE); }

    static String apply(Action action, Memory memory) {
        if (action == null) {
            return "No cheat selected";
        }
        // Only normal indoor/outdoor gameplay; title, death, menus and
        // transitions must not receive save-mirror writes.
        int module = memory.readByte(0x7E0010);
        if (action.kind == Kind.INFO) {
            if (action.addr == BINDING_Y) {
                return "Read-only: press Start, then Y or X on an item to bind it";
            }
            return "Read-only story flag: load the Horse test save (cured ranch)";
        }
        if ((module != 0x07 && module != 0x09) || memory.readByte(0x7E0011) != 0) {
            return "Resume normal gameplay before applying a grant";
        }
        if (action.addr == SWITCH_HOOK_OWNED) {
            int hookshot = memory.readByte(HOOKSHOT);
            if (hookshot < 0) return "Could not read Hookshot";
            boolean ok = memory.writeByte(HOOKSHOT, Math.max(hookshot, 1))
                    && memory.writeByte(SWITCH_HOOK_OWNED, 1);
            return ok ? "Gave Switch Hook: select the Hookshot slot" : "Switch Hook write failed";
        }
        if (action.addr == BOMBCHU_AMMO) {
            boolean ok = memory.writeByte(BOMBCHU_OWNED, 1)
                    && memory.writeByte(BOMBCHU_AMMO, 20);
            return ok ? "Gave Bombchus x20" : "Bombchu write failed";
        }
        if (MAX_EVERYTHING.equals(action.label)) {
            Map<Integer, Integer> writes = maxEverythingWrites(memory);
            if (writes == null) return "Could not read inventory; nothing written";
            if (!memory.writeWord(RUPEES, 999)) return "Rupee write failed; nothing else written";
            return applyAll(writes, memory,
                    "Maxed: all items, masks, rings, upgrades, 4 bottles, 20 hearts, 999 rupees");
        }
        if ("All rings".equals(action.label)) {
            Map<Integer, Integer> w = new LinkedHashMap<>();
            for (int ring : new int[]{FOUND_RINGS, MAGIC_RINGS}) {
                int cur = memory.readByte(ring);
                if (cur < 0) return "Could not read rings";
                w.put(ring, cur | ALL_RINGS);
            }
            return applyAll(w, memory, "Gave all 6 rings: equip one from the Rings page");
        }
        if ("Fishing + Portal Rod".equals(action.label)) {
            Map<Integer, Integer> w = new LinkedHashMap<>();
            w.put(CUSTOM_RODS, 2);
            w.put(PORTAL_ROD_OWNED, 1);
            return applyAll(w, memory, "Gave Fishing Rod and Portal Rod");
        }
        if ("Fill 4 bottles".equals(action.label)) {
            Map<Integer, Integer> w = new LinkedHashMap<>();
            int[] fill = {6, 6, 5, 5};
            for (int i = 0; i < 4; i++) w.put(BOTTLE_1 + i, fill[i]);
            int index = memory.readByte(BOTTLE_INDEX);
            if (index < 0) return "Could not read bottle index";
            if (index < 1 || index > 4) w.put(BOTTLE_INDEX, 1);
            return applyAll(w, memory, "Filled 4 bottles: 2 fairies, 2 blue potions");
        }
        if ("Basic starter kit".equals(action.label)) {
            int[] addresses = {SWORD, SHIELD, LAMP, BOMBS, ARROWS};
            int[] minimums = {1, 1, 1, 10, 30};
            int[] values = new int[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                int current = memory.readByte(addresses[i]);
                if (current < 0) return "Starter kit could not read inventory";
                values[i] = Math.max(current, minimums[i]);
            }
            for (int i = 0; i < addresses.length; i++) {
                if (!memory.writeByte(addresses[i], values[i])) {
                    return "Starter kit incomplete; some grants may have applied";
                }
            }
            return "Granted starter kit: sword, shield, lamp, bombs and arrows";
        }
        if ("Full heal".equals(action.label)) {
            int max = memory.readByte(HEALTH_MAX);
            if (max < 0) {
                return "Could not read max health";
            }
            if (max == 0) {
                max = 0x18; // 3 hearts fallback
            }
            if (!memory.writeByte(HEALTH_CURRENT, max)) {
                return "Heal failed";
            }
            return "Healed to max (" + max + ")";
        }
        if ("20 hearts (max+fill)".equals(action.label)) {
            if (!memory.writeByte(HEALTH_MAX, 0xA0)
                    || !memory.writeByte(HEALTH_CURRENT, 0xA0)) {
                return "Heart cheat failed";
            }
            return "Set 20 hearts";
        }
        if ("Max Y-items pack".equals(action.label)) {
            boolean ok = true;
            ok &= memory.writeByte(BOW, 4);
            ok &= memory.writeByte(BOOMERANG, 2);
            ok &= memory.writeByte(HOOKSHOT, 1);
            ok &= memory.writeByte(BOMBS, 50);
            ok &= memory.writeByte(POWDER, 2);
            ok &= memory.writeByte(FIRE_ROD, 1);
            ok &= memory.writeByte(ICE_ROD, 1);
            ok &= memory.writeByte(LAMP, 1);
            ok &= memory.writeByte(HAMMER, 1);
            ok &= memory.writeByte(FLUTE, FLUTE_ALL_SONGS);
            ok &= memory.writeByte(FEATHER, 1);
            ok &= memory.writeByte(BOOK, 1);
            ok &= memory.writeByte(SOMARIA, 1);
            ok &= memory.writeByte(MIRROR, 2);
            ok &= memory.writeByte(ARROWS, 50);
            return ok ? "Gave Y-item pack" : "Some Y-item writes failed";
        }
        if ("All masks".equals(action.label)) {
            Map<Integer, Integer> w = new LinkedHashMap<>();
            for (int mask : MASKS) w.put(mask, 1);
            return applyAll(w, memory, "Gave all 6 masks (Deku, Zora, Wolf, Bunny, Stone, Garo)");
        }
        if ("Pegasus boots".equals(action.label)) {
            boolean ok = memory.writeByte(BOOTS, 1);
            int ability = memory.readByte(ABILITY);
            if (ability < 0) return "Boots granted, but ability flags could not be read";
            ok &= memory.writeByte(ABILITY, ability | 0x04);
            return ok ? "Gave Pegasus boots" : "Boots write failed";
        }

        boolean ok;
        switch (action.kind) {
            case CYCLE: {
                int current = memory.readByte(action.addr);
                if (current < 0) return "Could not read " + action.label;
                int next = current >= action.value ? 0 : current + 1;
                ok = memory.writeByte(action.addr, next);
                break;
            }
            case WORD:
                ok = memory.writeWord(action.addr, action.value);
                break;
            case OR_BITS: {
                int cur = memory.readByte(action.addr);
                if (cur < 0) {
                    return "Read failed for " + action.label;
                }
                ok = memory.writeByte(action.addr, cur | action.value);
                break;
            }
            case BYTE:
            default:
                ok = memory.writeByte(action.addr, action.value);
                break;
        }
        return ok ? ("Applied: " + action.label) : ("Failed: " + action.label);
    }
}
