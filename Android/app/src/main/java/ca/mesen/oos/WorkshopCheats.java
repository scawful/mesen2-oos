package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Curated Oracle of Secrets WRAM cheats for Workshop. Addresses match the
 * mesen2_client OracleRAM / ITEMS registry ($7EFxxx save mirror).
 */
final class WorkshopCheats {
    enum Kind {
        BYTE,
        WORD,
        OR_BITS
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

    static final String GROUP_QUICK = "Quick";
    static final String GROUP_ITEMS = "Items";
    static final String GROUP_GEAR = "Gear";
    static final String GROUP_STORY = "Story";

    private WorkshopCheats() {}

    static List<String> groups() {
        List<String> groups = new ArrayList<>();
        groups.add(GROUP_QUICK);
        groups.add(GROUP_ITEMS);
        groups.add(GROUP_GEAR);
        groups.add(GROUP_STORY);
        return groups;
    }

    static List<Action> catalog() {
        List<Action> actions = new ArrayList<>();
        // Quick
        actions.add(new Action(GROUP_QUICK, "Full heal", HEALTH_CURRENT, 0, Kind.BYTE)); // value filled at apply
        actions.add(new Action(GROUP_QUICK, "Fill magic", MAGIC, 0x80, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "999 rupees", RUPEES, 999, Kind.WORD));
        actions.add(new Action(GROUP_QUICK, "50 bombs", BOMBS, 50, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "50 arrows", ARROWS, 50, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "20 hearts (max+fill)", HEALTH_MAX, 0xA0, Kind.BYTE));
        actions.add(new Action(GROUP_QUICK, "Max Y-items pack", 0, 0, Kind.BYTE)); // special id via label
        // Items
        actions.add(new Action(GROUP_ITEMS, "Bow (silver+arrows)", BOW, 4, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Red boomerang", BOOMERANG, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Hookshot", HOOKSHOT, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Magic powder", POWDER, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fire rod", FIRE_ROD, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Ice rod", ICE_ROD, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Lamp", LAMP, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Hammer", HAMMER, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Ocarina (all songs)", FLUTE, 5, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Roc's feather", FEATHER, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Book of Secrets", BOOK, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Cane of Somaria", SOMARIA, 1, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Magic mirror", MIRROR, 2, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "All masks", 0, 0, Kind.BYTE));
        actions.add(new Action(GROUP_ITEMS, "Fairy in bottle 1", BOTTLE_1, 6, Kind.BYTE));
        // Gear
        actions.add(new Action(GROUP_GEAR, "Golden sword", SWORD, 4, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Mirror shield", SHIELD, 3, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Red mail", ARMOR, 2, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Titan's mitt", GLOVES, 2, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Pegasus boots", BOOTS, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Zora flippers", FLIPPERS, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Moon pearl", MOON_PEARL, 1, Kind.BYTE));
        actions.add(new Action(GROUP_GEAR, "Ability flags (dash…)", ABILITY, 0xDB, Kind.BYTE));
        // Story
        actions.add(new Action(GROUP_STORY, "All crystals", CRYSTALS, 0x7F, Kind.BYTE));
        actions.add(new Action(GROUP_STORY, "All pendants", PENDANTS, 0x07, Kind.BYTE));
        actions.add(new Action(GROUP_STORY, "GameState: Loom Beach", GAME_STATE, 1, Kind.BYTE));
        actions.add(new Action(GROUP_STORY, "GameState: Kydrog done", GAME_STATE, 2, Kind.BYTE));
        actions.add(new Action(GROUP_STORY, "OOSPROG: intro+hall", OOSPROG, 0x03, Kind.OR_BITS));
        actions.add(new Action(GROUP_STORY, "OOSPROG2: Impa+sanctuary", OOSPROG2, 0x03, Kind.OR_BITS));
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

    static String apply(Action action) {
        if (action == null) {
            return "No cheat selected";
        }
        if ("Full heal".equals(action.label)) {
            int max = MesenNative.readByte(HEALTH_MAX);
            if (max < 0) {
                return "Could not read max health";
            }
            if (max == 0) {
                max = 0x18; // 3 hearts fallback
            }
            if (!MesenNative.writeByte(HEALTH_CURRENT, max)) {
                return "Heal failed";
            }
            return "Healed to max (" + max + ")";
        }
        if ("20 hearts (max+fill)".equals(action.label)) {
            if (!MesenNative.writeByte(HEALTH_MAX, 0xA0)
                    || !MesenNative.writeByte(HEALTH_CURRENT, 0xA0)) {
                return "Heart cheat failed";
            }
            return "Set 20 hearts";
        }
        if ("Max Y-items pack".equals(action.label)) {
            boolean ok = true;
            ok &= MesenNative.writeByte(BOW, 4);
            ok &= MesenNative.writeByte(BOOMERANG, 2);
            ok &= MesenNative.writeByte(HOOKSHOT, 1);
            ok &= MesenNative.writeByte(BOMBS, 50);
            ok &= MesenNative.writeByte(POWDER, 2);
            ok &= MesenNative.writeByte(FIRE_ROD, 1);
            ok &= MesenNative.writeByte(ICE_ROD, 1);
            ok &= MesenNative.writeByte(LAMP, 1);
            ok &= MesenNative.writeByte(HAMMER, 1);
            ok &= MesenNative.writeByte(FLUTE, 5);
            ok &= MesenNative.writeByte(FEATHER, 1);
            ok &= MesenNative.writeByte(BOOK, 1);
            ok &= MesenNative.writeByte(SOMARIA, 1);
            ok &= MesenNative.writeByte(MIRROR, 2);
            ok &= MesenNative.writeByte(ARROWS, 50);
            return ok ? "Gave Y-item pack" : "Some Y-item writes failed";
        }
        if ("All masks".equals(action.label)) {
            boolean ok = MesenNative.writeByte(ZORA_MASK, 1)
                    && MesenNative.writeByte(BUNNY_HOOD, 1)
                    && MesenNative.writeByte(DEKU_MASK, 1)
                    && MesenNative.writeByte(STONE_MASK, 1)
                    && MesenNative.writeByte(WOLF_MASK, 1);
            return ok ? "Gave all masks" : "Mask writes failed";
        }
        if ("Pegasus boots".equals(action.label)) {
            boolean ok = MesenNative.writeByte(BOOTS, 1);
            int ability = MesenNative.readByte(ABILITY);
            if (ability >= 0) {
                ok &= MesenNative.writeByte(ABILITY, ability | 0x04);
            }
            return ok ? "Gave Pegasus boots" : "Boots write failed";
        }

        boolean ok;
        switch (action.kind) {
            case WORD:
                ok = MesenNative.writeWord(action.addr, action.value);
                break;
            case OR_BITS: {
                int cur = MesenNative.readByte(action.addr);
                if (cur < 0) {
                    return "Read failed for " + action.label;
                }
                ok = MesenNative.writeByte(action.addr, cur | action.value);
                break;
            }
            case BYTE:
            default:
                ok = MesenNative.writeByte(action.addr, action.value);
                break;
        }
        return ok ? ("Applied: " + action.label) : ("Failed: " + action.label);
    }
}
