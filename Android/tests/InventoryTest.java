package ca.mesen.oos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InventoryTest {
    private static int checks;

    static final class Ram implements Inventory.Memory {
        final Map<Integer, Integer> data = new HashMap<>();
        final Set<Integer> writes = new HashSet<>();
        int failRead = -1;
        int failWrite = -1;
        int sramSyncs;

        Ram() {
            data.put(Inventory.MODULE, 0x09);
            data.put(Inventory.SUBMODULE, 0x00);
        }

        public int readByte(int address) {
            return address == failRead ? -1 : data.getOrDefault(address, 0);
        }

        public boolean writeByte(int address, int value) {
            if (address == failWrite) return false;
            writes.add(address);
            data.put(address, value & 0xFF);
            return true;
        }

        public boolean writeWord(int address, int value) {
            return writeByte(address, value & 0xFF) && writeByte(address + 1, (value >> 8) & 0xFF);
        }

        public boolean syncSram() {
            sramSyncs++;
            return true;
        }
    }

    private static Inventory.Row row(String tab, String label) {
        for (Inventory.Row r : Inventory.rows(tab, null)) {
            if (r.label.equals(label)) return r;
        }
        throw new AssertionError("Missing row: " + tab + "/" + label);
    }

    public static void main(String[] args) {
        testGameplayGating();
        testQuickActions();
        testMaxEverything();
        testStepAndToggleAndCompanions();
        testFlagsAndSongs();
        testDungeonAndStoryTabs();
        System.out.println("InventoryTest OK (" + checks + " checks)");
    }

    private static void testGameplayGating() {
        for (int module : new int[] { 0x00, 0x01, 0x06, 0x0E, 0x14, -1 }) {
            Ram ram = new Ram();
            ram.data.put(Inventory.MODULE, module);
            String msg = Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.FILL_MAGIC), ram, 0);
            String prefix = module < 0 ? "Could not read" : "Resume";
            check(ram.writes.isEmpty() && ram.sramSyncs == 0 && msg.startsWith(prefix),
                    "refused outside play: " + module + " (" + msg + ")");
        }
        Ram trans = new Ram();
        trans.data.put(Inventory.SUBMODULE, 1);
        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.FILL_MAGIC), trans, 0);
        check(trans.writes.isEmpty() && trans.sramSyncs == 0, "refused during submodule transition");
    }

    private static void testQuickActions() {
        Ram ram = new Ram();
        ram.data.put(Inventory.SWORD, 4);
        ram.data.put(Inventory.SHIELD, 3);
        ram.data.put(Inventory.BOMBS, 25);
        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.STARTER_KIT), ram, 0);
        check(ram.readByte(Inventory.SWORD) == 4, "starter keeps higher sword");
        check(ram.readByte(Inventory.SHIELD) == 3, "starter keeps higher shield");
        check(ram.readByte(Inventory.BOMBS) == 25, "starter keeps higher bombs");
        check(ram.readByte(Inventory.LAMP) == 1, "starter grants lamp");
        check(ram.sramSyncs == 1, "starter syncs SRAM");

        Ram bad = new Ram();
        bad.failRead = Inventory.LAMP;
        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.STARTER_KIT), bad, 0);
        check(bad.writes.isEmpty() && bad.sramSyncs == 0, "failed starter preflight writes nothing");

        Ram partial = new Ram();
        partial.failWrite = Inventory.LAMP;
        String res = Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.STARTER_KIT), partial, 0);
        check(res.contains("Incomplete"), "partial starter writes disclosed");

        Ram heal = new Ram();
        heal.data.put(Inventory.HEALTH_MAX, 0x38);
        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.FULL_HEAL), heal, 0);
        check(heal.readByte(Inventory.HEALTH_CURRENT) == 0x38, "heal uses actual max");

        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.RUPEES_999), heal, 0);
        check(heal.readByte(Inventory.RUPEES) == 231 && heal.readByte(Inventory.RUPEES + 1) == 3,
                "rupees little endian");
        check(heal.readByte(Inventory.RUPEES_DISPLAY) == 231 && heal.readByte(Inventory.RUPEES_DISPLAY + 1) == 3,
                "rupees display HUD mirror synced");

        Ram heavy = new Ram();
        heavy.data.put(Inventory.SWORD_SLOT_FLAGS, 0x02);
        Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.HEAVY_SWORD), heavy, 0);
        check(heavy.readByte(Inventory.SWORD_SLOT_FLAGS) == 0x83, "heavy sword ORs $81");
    }

    private static void testMaxEverything() {
        Ram m = new Ram();
        m.data.put(Inventory.HOOKSHOT, 2);
        m.data.put(Inventory.BOTTLE_1 + 1, 8);
        m.data.put(Inventory.ABILITY, 0x01);
        m.data.put(Inventory.MAGIC_RINGS, 0x40);
        m.data.put(Inventory.SIDE_QUEST_2, 0x15);

        String mr = Inventory.toggle(row(Inventory.TAB_ITEMS, Inventory.MAX_EVERYTHING), m, 0);
        check(mr.startsWith("Maxed"), "max ok");
        check(m.readByte(Inventory.FLUTE) == 7, "all six ocarina songs");
        check(m.readByte(Inventory.SKY_SOARING_LEARNED) == Inventory.SKY_SOARING_TOKEN, "Sky Soaring learned");
        check(m.readByte(Inventory.HOOKSHOT) == 2, "keeps hookshot 2");
        check(m.readByte(Inventory.GOLDSTAR_OWNED) == 1
                && m.readByte(Inventory.GOLDSTAR_VERSION) == Inventory.GOLDSTAR_VERSION_OK, "Goldstar + version");
        for (int mask : Inventory.MASKS) check(m.readByte(mask) == 1, "mask set");
        check(m.readByte(Inventory.MAGIC_RINGS) == 0x7F && m.readByte(Inventory.FOUND_RINGS) == 0x3F, "rings ORed");
        check(m.readByte(Inventory.BOTTLE_1) == 6 && m.readByte(Inventory.BOTTLE_1 + 1) == 8, "bottles filled/kept");
        check(m.readByte(Inventory.ABILITY) == 0x87, "ability ORed");
        check(m.readByte(Inventory.SIDE_QUEST_2) == 0x17, "teaches Epona song only");
        check(m.readByte(Inventory.RUPEES) == 231 && m.readByte(Inventory.RUPEES + 1) == 3, "999 rupees");
        check(m.readByte(Inventory.RUPEES_DISPLAY) == 231 && m.readByte(Inventory.RUPEES_DISPLAY + 1) == 3,
                "999 rupees display");
        check(m.sramSyncs == 1, "max synced SRAM");
        for (int a : m.writes) {
            check(a != Inventory.GAME_STATE && a != Inventory.OOSPROG && a != Inventory.OOSPROG2,
                    "max never touches story state: " + Integer.toHexString(a));
        }
    }

    private static void testStepAndToggleAndCompanions() {
        Ram ram = new Ram();
        Inventory.Row sword = row(Inventory.TAB_ITEMS, "Sword");
        Inventory.step(sword, ram, 1);
        check(ram.readByte(Inventory.SWORD) == 1, "sword step up");
        check("Fighter".equals(Inventory.valueText(sword, Inventory.read(sword, ram))), "sword label");
        Inventory.step(sword, ram, -1);
        Inventory.step(sword, ram, -1);
        check(ram.readByte(Inventory.SWORD) == 0, "sword clamped at 0");
        Inventory.toggle(sword, ram, 0);
        check(ram.readByte(Inventory.SWORD) == 4, "sword toggle goes 0 -> max");
        Inventory.toggle(sword, ram, 0);
        check(ram.readByte(Inventory.SWORD) == 0, "sword toggle goes max -> 0");

        Inventory.Row rupees = row(Inventory.TAB_ITEMS, "Rupees");
        Inventory.step(rupees, ram, 1);
        check(Inventory.read(rupees, ram) == 50, "rupees step by 50");
        check(ram.readByte(Inventory.RUPEES_DISPLAY) == 50, "rupees step syncs display");

        Inventory.Row switchHook = row(Inventory.TAB_ITEMS, "Switch Hook");
        Inventory.toggle(switchHook, ram, 0);
        check(ram.readByte(Inventory.SWITCH_HOOK_OWNED) == 1 && ram.readByte(Inventory.HOOKSHOT) == 1,
                "switch hook raises hookshot");

        Inventory.Row goldstar = row(Inventory.TAB_ITEMS, "Goldstar");
        Inventory.toggle(goldstar, ram, 0);
        check(ram.readByte(Inventory.GOLDSTAR_OWNED) == 1
                && ram.readByte(Inventory.GOLDSTAR_VERSION) == Inventory.GOLDSTAR_VERSION_OK,
                "goldstar sets migration version");

        Inventory.Row portalRod = row(Inventory.TAB_ITEMS, "Portal Rod owned");
        Inventory.toggle(portalRod, ram, 0);
        check(ram.readByte(Inventory.PORTAL_ROD_OWNED) == 1 && ram.readByte(Inventory.CUSTOM_RODS) == 2,
                "portal rod raises CustomRods to 2");

        Inventory.Row boots = row(Inventory.TAB_ITEMS, "Pegasus boots");
        Inventory.toggle(boots, ram, 0);
        check((ram.readByte(Inventory.ABILITY) & 0x04) == 0x04, "boots OR run ability");

        Inventory.Row flippers = row(Inventory.TAB_ITEMS, "Zora flippers");
        Inventory.toggle(flippers, ram, 0);
        check((ram.readByte(Inventory.ABILITY) & 0x02) == 0x02, "flippers OR swim ability");
        check(ram.sramSyncs > 0, "individual toggles sync SRAM");
    }

    private static void testFlagsAndSongs() {
        Ram ram = new Ram();
        Inventory.Row song3 = row(Inventory.TAB_SONGS, "Song 3: Song of Soaring");
        check(Inventory.read(song3, ram) == 0, "Soaring initially OFF");
        Inventory.toggle(song3, ram, 0);
        check(ram.readByte(Inventory.FLUTE) == 4 && Inventory.read(song3, ram) == 1,
                "Soaring sets Flute=$04");
        Inventory.toggle(song3, ram, 0);
        check(ram.readByte(Inventory.FLUTE) == 3 && Inventory.read(song3, ram) == 0,
                "Soaring off drops Flute to $03 leaving Storms");

        Inventory.Row epona = row(Inventory.TAB_SONGS, "Song 6: Epona's Song ($7EF3D8)");
        Inventory.toggle(epona, ram, 0);
        check((ram.readByte(Inventory.SIDE_QUEST_2) & 0x02) == 0x02 && Inventory.read(epona, ram) == 1,
                "Epona toggles SideQuestProgress2 bit 1");

        Inventory.Row soaring = row(Inventory.TAB_SONGS, "Sky Soaring learned ($A5)");
        Inventory.toggle(soaring, ram, 0);
        check(ram.readByte(Inventory.SKY_SOARING_LEARNED) == 0xA5
                && "ON ($A5)".equals(Inventory.valueText(soaring, Inventory.read(soaring, ram))),
                "Sky Soaring toggles to $A5");
        Inventory.toggle(soaring, ram, 0);
        check(ram.readByte(Inventory.SKY_SOARING_LEARNED) == 0, "Sky Soaring toggles back to 0");

        Inventory.Row heavyOwned = row(Inventory.TAB_ITEMS, "Heavy sword: Owned");
        Inventory.toggle(heavyOwned, ram, 0);
        check(ram.readByte(Inventory.SWORD_SLOT_FLAGS) == 0x81, "Heavy Sword flag initializes SwordSlotFlags");
        check(ram.sramSyncs == 6, "all song/flag toggles sync SRAM");
    }

    private static void testDungeonAndStoryTabs() {
        List<String> tabs = Inventory.tabs();
        check(tabs.size() == 4
                && tabs.get(0).equals(Inventory.TAB_ITEMS)
                && tabs.get(1).equals(Inventory.TAB_SONGS)
                && tabs.get(2).equals(Inventory.TAB_DUNGEONS)
                && tabs.get(3).equals(Inventory.TAB_STORY), "4 grouped inventory tabs");

        Ram ram = new Ram();
        Inventory.Row d4 = row(Inventory.TAB_DUNGEONS, "Crystal: D4 Zora Temple");
        Inventory.toggle(d4, ram, 0);
        check(ram.readByte(Inventory.CRYSTALS) == 0x20, "D4 Crystal is bit 5 ($20)");
        Inventory.Row d6 = row(Inventory.TAB_DUNGEONS, "Crystal: D6 Goron Mines");
        Inventory.toggle(d6, ram, 0);
        check(ram.readByte(Inventory.CRYSTALS) == 0x21, "D6 Crystal is bit 0 ($01)");

        Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0x7EF3D6, "OOSPROG");
        names.put(0x7EF3C9, "MiscProgress"); // not explicitly listed in CATALOG -> included as extra
        names.put(0x7EF359, "Sword"); // covered on Items tab -> must not duplicate on Story flags
        names.put(0x7E005D, "LinkState"); // outside $7EF000..$7EF4FF -> excluded
        List<Inventory.Row> story = Inventory.rows(Inventory.TAB_STORY, names);
        boolean sawGameState = false, sawMiscProgress = false, sawSword = false, sawLinkState = false;
        for (Inventory.Row r : story) {
            if (r.addr == Inventory.GAME_STATE) sawGameState = true;
            if (r.addr == 0x7EF3C9) sawMiscProgress = true;
            if (r.addr == Inventory.SWORD) sawSword = true;
            if (r.addr == 0x7E005D) sawLinkState = true;
        }
        check(sawGameState && sawMiscProgress, "story + extra checkpoint save bytes listed");
        check(!sawSword && !sawLinkState, "items and non-save WRAM excluded from Story flags tab");

        Inventory.Row hall = row(Inventory.TAB_STORY, "OOSPROG: HallOfSecrets");
        Inventory.toggle(hall, ram, 0);
        check(ram.readByte(Inventory.OOSPROG) == 0x02, "bit 1 set via named FLAG on OOSPROG");
        Inventory.toggle(hall, ram, 0);
        check(ram.readByte(Inventory.OOSPROG) == 0x00, "bit 1 cleared via named FLAG on OOSPROG");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
