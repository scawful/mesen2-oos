package ca.mesen.oos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class WorkshopCheatsTest {
    private static int checks;
    static final class Ram implements WorkshopCheats.Memory {
        final Map<Integer, Integer> data = new HashMap<>();
        final Set<Integer> writes = new HashSet<>();
        int failRead = -1;
        int failWrite = -1;
        Ram() { data.put(0x7E0010, 9); }
        public int readByte(int address) { return address == failRead ? -1 : data.getOrDefault(address, 0); }
        public boolean writeByte(int address, int value) {
            if (address == failWrite) return false;
            writes.add(address); data.put(address, value); return true;
        }
        public boolean writeWord(int address, int value) {
            return writeByte(address, value & 255) && writeByte(address + 1, value >> 8);
        }
    }
    static WorkshopCheats.Action action(String label) {
        for (WorkshopCheats.Action a : WorkshopCheats.catalog()) if (a.label.equals(label)) return a;
        throw new AssertionError("Missing action: " + label);
    }
    public static void main(String[] args) {
        check(!WorkshopCheats.groups().contains(WorkshopCheats.GROUP_STORY), "raw story controls removed");
        for (WorkshopCheats.Action a : WorkshopCheats.catalog()) {
            check(a.addr != WorkshopCheats.GAME_STATE && a.addr != WorkshopCheats.OOSPROG
                    && a.addr != WorkshopCheats.OOSPROG2, "no progression writes: " + a.label);
        }
        Ram ram = new Ram();
        ram.data.put(WorkshopCheats.SWORD, 4);
        ram.data.put(WorkshopCheats.SHIELD, 3);
        ram.data.put(WorkshopCheats.BOMBS, 25);
        WorkshopCheats.apply(action("Basic starter kit"), ram);
        check(ram.readByte(WorkshopCheats.SWORD) == 4, "starter preserves upgraded sword");
        check(ram.readByte(WorkshopCheats.SHIELD) == 3, "starter preserves upgraded shield");
        check(ram.readByte(WorkshopCheats.BOMBS) == 25, "starter preserves supplies");
        check(ram.readByte(WorkshopCheats.LAMP) == 1, "starter grants lamp");
        check(ram.writes.size() == 5, "starter touches only five inventory bytes");
        ram = new Ram(); ram.failRead = WorkshopCheats.LAMP;
        WorkshopCheats.apply(action("Basic starter kit"), ram);
        check(ram.writes.isEmpty(), "failed starter preflight writes nothing");
        ram = new Ram(); ram.failWrite = WorkshopCheats.LAMP;
        check(WorkshopCheats.apply(action("Basic starter kit"), ram).contains("incomplete"), "partial writes disclosed");
        for (int module : new int[]{0, 1, 6, 14, 18, -1}) {
            ram = new Ram(); ram.data.put(0x7E0010, module);
            WorkshopCheats.apply(action("Fill magic"), ram);
            check(ram.writes.isEmpty(), "no grant outside normal gameplay");
        }
        ram = new Ram(); ram.data.put(0x7E0011, 1);
        WorkshopCheats.apply(action("Fill magic"), ram);
        check(ram.writes.isEmpty(), "no grants during transitions");
        ram = new Ram(); ram.data.put(WorkshopCheats.HEALTH_MAX, 0x38);
        WorkshopCheats.apply(action("Full heal"), ram);
        check(ram.readByte(WorkshopCheats.HEALTH_CURRENT) == 0x38, "heal uses actual max");
        WorkshopCheats.apply(action("999 rupees"), ram);
        check(ram.readByte(WorkshopCheats.RUPEES) == 231 && ram.readByte(WorkshopCheats.RUPEES+1) == 3, "rupees little endian");
        check(WorkshopCheats.rowLabel(action("999 rupees"), ram).contains("999"), "current word value");
        for (String name : new String[]{"Sword level", "Shield level"}) {
            WorkshopCheats.Action a = action(name);
            ram = new Ram();
            for (int level = 1; level <= a.value; level++) {
                WorkshopCheats.apply(a, ram);
                check(ram.readByte(a.addr) == level, "level selector reaches " + level);
            }
            WorkshopCheats.apply(a, ram);
            check(ram.readByte(a.addr) == 0, "selector wraps to none");
            ram.failRead = a.addr; ram.writes.clear(); WorkshopCheats.apply(a, ram);
            check(ram.writes.isEmpty(), "failed level read does not write");
        }
        System.out.println("WorkshopCheatsTest OK (" + checks + " checks)");
        // Features group (2026-11-15)
        check(WorkshopCheats.groups().get(1).equals(WorkshopCheats.GROUP_FEATURES), "features second");
        check(WorkshopCheats.groups().get(2).equals(WorkshopCheats.GROUP_PROGRESS), "progress third");
        check(WorkshopCheats.forGroup(WorkshopCheats.GROUP_PROGRESS).isEmpty(), "progress rows are not catalog actions");
        Ram f = new Ram();
        f.data.put(WorkshopCheats.HOOKSHOT, 2);
        check(WorkshopCheats.apply(action("Switch Hook (+Hookshot)"), f).startsWith("Gave Switch Hook"), "switch hook ok");
        check(f.readByte(WorkshopCheats.HOOKSHOT) == 2, "switch hook keeps hookshot upgrade");
        check(f.readByte(WorkshopCheats.SWITCH_HOOK_OWNED) == 1, "switch hook owned");
        Ram g = new Ram();
        WorkshopCheats.apply(action("Switch Hook (+Hookshot)"), g);
        check(g.readByte(WorkshopCheats.HOOKSHOT) == 1, "switch hook grants hookshot");
        WorkshopCheats.apply(action("Bombchus x20"), g);
        check(g.readByte(WorkshopCheats.BOMBCHU_OWNED) == 1 && g.readByte(WorkshopCheats.BOMBCHU_AMMO) == 20, "bombchus");
        WorkshopCheats.apply(action("Deku Sticks x10"), g);
        check(g.readByte(WorkshopCheats.DEKU_STICKS) == 10, "deku sticks");
        g.data.put(WorkshopCheats.SWORD_SLOT_FLAGS, 0x02);
        WorkshopCheats.apply(action("Heavy sword (broken)"), g);
        check(g.readByte(WorkshopCheats.SWORD_SLOT_FLAGS) == 0x83, "heavy sword ORs owned+init, keeps equipped bit");
        Ram info = new Ram();
        String r = WorkshopCheats.apply(action("Epona ready (ranch cured)"), info);
        check(r.startsWith("Read-only") && info.writes.isEmpty(), "epona row never writes");
        check(WorkshopCheats.rowLabel(action("Epona ready (ranch cured)"), info).contains(": no"), "epona no");
        info.data.put(WorkshopCheats.SIDE_QUEST_2, 0x05);
        check(WorkshopCheats.rowLabel(action("Epona ready (ranch cured)"), info).contains(": yes"), "epona yes");
        check(WorkshopCheats.apply(action("HUD Y/X binding"), info).startsWith("Read-only") && info.writes.isEmpty(), "binding never writes");
        check(WorkshopCheats.rowLabel(action("HUD Y/X binding"), info).contains("not saved"), "binding unsaved");
        info.data.put(WorkshopCheats.BINDING_VERSION, 1);
        info.data.put(WorkshopCheats.BINDING_Y, 0x03);
        info.data.put(WorkshopCheats.BINDING_X, 0x01);
        check(WorkshopCheats.rowLabel(action("HUD Y/X binding"), info).contains("Y=$03 X=$01"), "binding shown");
        Ram title = new Ram();
        title.data.put(0x7E0010, 0x01);
        check(WorkshopCheats.apply(action("Deku Sticks x10"), title).startsWith("Resume"), "feature grants gated to gameplay");
        // Max everything (2026-11-16)
        check(WorkshopCheats.forGroup(WorkshopCheats.GROUP_QUICK).get(0).label.equals(WorkshopCheats.MAX_EVERYTHING), "max first in Quick");
        Ram m = new Ram();
        m.data.put(WorkshopCheats.HOOKSHOT, 2);
        m.data.put(WorkshopCheats.BOTTLE_1 + 1, 8); // good bee kept
        m.data.put(WorkshopCheats.ABILITY, 0x01);
        m.data.put(WorkshopCheats.MAGIC_RINGS, 0x40);
        String mr = WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), m);
        check(mr.startsWith("Maxed"), "max ok: " + mr);
        check(m.readByte(WorkshopCheats.FLUTE) == 7, "all six songs");
        check(m.readByte(WorkshopCheats.HOOKSHOT) == 2, "keeps legacy hookshot 2");
        for (int mask : WorkshopCheats.MASKS) check(m.readByte(mask) == 1, "mask " + Integer.toHexString(mask));
        check(m.readByte(WorkshopCheats.MAGIC_RINGS) == 0x7F && m.readByte(WorkshopCheats.FOUND_RINGS) == 0x3F, "rings OR in");
        check(m.readByte(WorkshopCheats.BOTTLE_1) == 6 && m.readByte(WorkshopCheats.BOTTLE_1 + 1) == 8
                && m.readByte(WorkshopCheats.BOTTLE_1 + 3) == 5 && m.readByte(WorkshopCheats.BOTTLE_INDEX) == 1, "bottles");
        check(m.readByte(WorkshopCheats.ABILITY) == 0x87, "ability ORs lift/run/swim");
        check(m.readByte(WorkshopCheats.BOMB_CAPACITY) == 7 && m.readByte(WorkshopCheats.ARROW_CAPACITY) == 7
                && m.readByte(WorkshopCheats.BOMBS) == 50 && m.readByte(WorkshopCheats.ARROWS) == 70, "capacity + ammo");
        check(m.readByte(WorkshopCheats.HEALTH_MAX) == 0xA0 && m.readByte(WorkshopCheats.HEALTH_CURRENT) == 0xA0
                && m.readByte(WorkshopCheats.MAGIC) == 0x80, "hearts + magic");
        check(m.readByte(WorkshopCheats.RUPEES) == 231 && m.readByte(WorkshopCheats.RUPEES + 1) == 3, "999 rupees");
        check(m.readByte(WorkshopCheats.SWORD) == 4 && m.readByte(WorkshopCheats.SHIELD) == 3 && m.readByte(WorkshopCheats.ARMOR) == 2
                && m.readByte(WorkshopCheats.GLOVES) == 2, "gear max");
        check(m.readByte(WorkshopCheats.PORTAL_ROD_OWNED) == 1 && m.readByte(WorkshopCheats.CUSTOM_RODS) == 2
                && m.readByte(WorkshopCheats.GOLDSTAR_OWNED) == 1 && m.readByte(WorkshopCheats.SWITCH_HOOK_OWNED) == 1
                && m.readByte(WorkshopCheats.BYRNA_OWNED) == 1 && m.readByte(WorkshopCheats.BOMBCHU_AMMO) == 20, "feature items");
        for (int a : m.writes) {
            check(a != WorkshopCheats.GAME_STATE && a != WorkshopCheats.OOSPROG && a != WorkshopCheats.OOSPROG2
                    && !(a >= 0x7EF37C && a <= 0x7EF389), "max never writes story/keys: " + Integer.toHexString(a));
        }
        check(m.readByte(WorkshopCheats.SIDE_QUEST_2) == WorkshopCheats.EPONA_SONG, "max teaches only Epona's Song");
        Ram story = new Ram();
        story.data.put(WorkshopCheats.SIDE_QUEST_2, 0x15);
        WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), story);
        check(story.readByte(WorkshopCheats.SIDE_QUEST_2) == 0x17, "max keeps other SideQuestProg2 bits");
        Ram hi = new Ram();
        hi.data.put(WorkshopCheats.HEALTH_MAX, 0xA0); hi.data.put(WorkshopCheats.SWORD, 4);
        WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), hi);
        check(hi.readByte(WorkshopCheats.SWORD) == 4, "no downgrade");
        Ram bad = new Ram(); bad.failRead = WorkshopCheats.BOTTLE_1 + 2;
        check(WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), bad).startsWith("Could not read") && bad.writes.isEmpty(), "max preflight writes nothing");
        Ram half = new Ram(); half.failWrite = WorkshopCheats.MAGIC;
        check(WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), half).startsWith("Incomplete"), "partial max disclosed");
        Ram outside = new Ram(); outside.data.put(0x7E0010, 0x0E);
        WorkshopCheats.apply(action(WorkshopCheats.MAX_EVERYTHING), outside);
        check(outside.writes.isEmpty(), "max gated to gameplay");
        Ram r2 = new Ram();
        WorkshopCheats.apply(action("All rings"), r2);
        check(r2.readByte(WorkshopCheats.MAGIC_RINGS) == 0x3F, "all rings row");
        WorkshopCheats.apply(action("All masks"), r2);
        check(r2.readByte(0x7EF3AF) == 1, "all masks includes Garo");
        WorkshopCheats.apply(action("Ocarina (all songs)"), r2);
        check(r2.readByte(WorkshopCheats.FLUTE) == 7, "ocarina row all songs");
        System.out.println("WorkshopCheatsTest max OK (" + checks + " checks)");
    }
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
