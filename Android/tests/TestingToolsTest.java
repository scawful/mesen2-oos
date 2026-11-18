package ca.mesen.oos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure-Java checks for frame-locked testing cheats and live WRAM debug actions. */
public final class TestingToolsTest {
    private static int checks;

    private static final class FakeMemory implements Inventory.Memory {
        final Map<Integer, Integer> wram = new HashMap<>();

        FakeMemory(int module, int sub) {
            wram.put(Inventory.MODULE, module);
            wram.put(Inventory.SUBMODULE, sub);
        }

        @Override
        public int readByte(int address) {
            return wram.getOrDefault(address, 0);
        }

        @Override
        public boolean writeByte(int address, int value) {
            wram.put(address, value & 0xFF);
            return true;
        }

        @Override
        public boolean writeWord(int address, int value) {
            wram.put(address, value & 0xFF);
            wram.put(address + 1, (value >> 8) & 0xFF);
            return true;
        }
    }

    private static final class RecordingSink implements TestingTools.CheatSink {
        Map<Integer, Integer> active = new LinkedHashMap<>();

        @Override
        public void apply(int[] addrs, int[] values) {
            active.clear();
            for (int i = 0; i < addrs.length; i++) {
                active.put(addrs[i], values[i]);
            }
        }
    }

    public static void main(String[] args) {
        testCheatsToggleAndClear();
        testLiveActionsAndRefusals();
        testClearEnemiesAndSpawnSprite();
        testFrameStepperWorksWhenPaused();
        System.out.println("TestingToolsTest OK (" + checks + " checks)");
    }

    static void testCheatsToggleAndClear() {
        FakeMemory mem = new FakeMemory(0x09, 0);
        mem.wram.put(Inventory.HEALTH_MAX, 0x50);
        mem.wram.put(Inventory.HEALTH_CURRENT, 0x08);

        RecordingSink sink = new RecordingSink();
        TestingTools tools = new TestingTools(sink);

        String msg = tools.toggleCheat(TestingTools.Cheat.INF_HEALTH, mem);
        check(msg.contains("ON"), "inf health enabled");
        check(mem.readByte(Inventory.HEALTH_CURRENT) == 0x50, "immediately fills to max HP");
        check(sink.active.get(Inventory.HEALTH_CURRENT) == 0x50, "cheat sink locks HP to max");

        tools.toggleCheat(TestingTools.Cheat.NO_DAMAGE, mem);
        tools.toggleCheat(TestingTools.Cheat.WALK_WALLS, mem);
        tools.toggleCheat(TestingTools.Cheat.FREEZE_SPRITES, mem);
        tools.toggleCheat(TestingTools.Cheat.INF_MAGIC_AMMO, mem);
        check(tools.activeCheatCount() == 5, "all 5 cheats active");
        check(sink.active.get(TestingTools.WRAM_NOPAIN) == 1, "no damage cheat pushed");
        check(sink.active.get(TestingTools.WRAM_CHEAT_WALLS) == 1, "walk walls cheat pushed");
        check(sink.active.get(TestingTools.WRAM_FREEZE_SPR) == 1, "freeze sprites cheat pushed");
        check(sink.active.get(Inventory.ARROWS) == 70, "arrows locked full");

        // Disabling WALK_WALLS writes 0 to $7E037F so Link stops walking through walls.
        mem.wram.put(TestingTools.WRAM_CHEAT_WALLS, 1);
        tools.toggleCheat(TestingTools.Cheat.WALK_WALLS, mem);
        check(mem.readByte(TestingTools.WRAM_CHEAT_WALLS) == 0, "walk walls cleared on disable");
        check(!sink.active.containsKey(TestingTools.WRAM_CHEAT_WALLS), "walk walls removed from cheat sink");

        tools.clearAll(mem);
        check(tools.activeCheatCount() == 0, "clearAll removes all active cheats");
        check(sink.active.isEmpty(), "cheat sink emptied");
        check(mem.readByte(TestingTools.WRAM_NOPAIN) == 0, "no damage cleared");
        check(mem.readByte(TestingTools.WRAM_FREEZE_SPR) == 0, "freeze sprites cleared");
    }

    static void testLiveActionsAndRefusals() {
        FakeMemory blocked = new FakeMemory(0x0E, 0);
        TestingTools tools = new TestingTools(new RecordingSink());
        check(tools.runAction(TestingTools.LiveAction.TOGGLE_LAYER, blocked, f -> true).startsWith("Resume normal play"),
                "layer toggle refused during message module");

        FakeMemory play = new FakeMemory(0x07, 0);
        play.wram.put(TestingTools.WRAM_LINK_LAYER, 0);
        check(tools.runAction(TestingTools.LiveAction.TOGGLE_LAYER, play, null).contains("Layer")
                || play.readByte(TestingTools.WRAM_LINK_LAYER) == 1, "toggles layer 0 -> 1");
        check(play.readByte(TestingTools.WRAM_LINK_LAYER) == 1, "WRAM layer set to 1");
        tools.runAction(TestingTools.LiveAction.TOGGLE_LAYER, play, null);
        check(play.readByte(TestingTools.WRAM_LINK_LAYER) == 0, "WRAM layer toggled back to 0");

        String t1 = tools.runAction(TestingTools.LiveAction.TIME_OF_DAY, play, null);
        check(t1.contains("18:00") && play.readByte(TestingTools.WRAM_TIME_HOURS) == 18
                && play.readByte(TestingTools.WRAM_TIME_DIRTY) == 1, "cycles to dusk & sets dirty flag: " + t1);
        String t2 = tools.runAction(TestingTools.LiveAction.TIME_OF_DAY, play, null);
        check(t2.contains("00:00") && play.readByte(TestingTools.WRAM_TIME_HOURS) == 0, "cycles to midnight: " + t2);
        String t3 = tools.runAction(TestingTools.LiveAction.TIME_OF_DAY, play, null);
        check(t3.contains("06:00") && play.readByte(TestingTools.WRAM_TIME_HOURS) == 6, "cycles to dawn: " + t3);
    }

    static void testClearEnemiesAndSpawnSprite() {
        FakeMemory play = new FakeMemory(0x09, 0);
        play.wram.put(TestingTools.WRAM_LINK_X, 0xF8);
        play.wram.put(TestingTools.WRAM_LINK_X + 1, 0x00); // X = 248 -> +16 = 264 ($0108)
        play.wram.put(TestingTools.WRAM_LINK_Y, 0x20);
        play.wram.put(TestingTools.WRAM_LINK_Y + 1, 0x01); // Y = $0120
        play.wram.put(TestingTools.WRAM_LINK_LAYER, 1);

        // Slot 0: normal active enemy -> should die
        play.wram.put(TestingTools.SPR_STATE, 0x09);
        play.wram.put(TestingTools.SPR_HP, 0x08);
        // Slot 1: impervious colProp $40 -> skipped
        play.wram.put(TestingTools.SPR_STATE + 1, 0x09);
        play.wram.put(TestingTools.SPR_COLPROP + 1, 0x40);
        // Slot 2: harmless props $80 -> skipped
        play.wram.put(TestingTools.SPR_STATE + 2, 0x0B);
        play.wram.put(TestingTools.SPR_PROPS + 2, 0x80);

        TestingTools tools = new TestingTools(new RecordingSink());
        String clearMsg = tools.runAction(TestingTools.LiveAction.CLEAR_ENEMIES, play, null);
        check("Cleared 1 enemy".equals(clearMsg), "cleared only vulnerable active enemy: " + clearMsg);
        check(play.readByte(TestingTools.SPR_STATE) == 6 && play.readByte(TestingTools.SPR_HP) == 0,
                "slot 0 set to dying");
        check(play.readByte(TestingTools.SPR_STATE + 1) == 0x09, "impervious slot 1 untouched");
        check(play.readByte(TestingTools.SPR_STATE + 2) == 0x0B, "harmless slot 2 untouched");

        tools.stepSpawnSpriteId(-0x42); // wraps $41 -> $FF
        check(tools.spawnSpriteId() == 0xFF, "sprite ID wraps cleanly");
        tools.stepSpawnSpriteId(1); // wraps $FF -> $00
        tools.stepSpawnSpriteId(0x23);

        String spawnMsg = tools.runAction(TestingTools.LiveAction.SPAWN_SPRITE, play, null);
        check(spawnMsg.contains("slot 15") && spawnMsg.contains("$23"), "spawned into highest free slot: " + spawnMsg);
        check(play.readByte(TestingTools.SPR_ID + 15) == 0x23, "sprite ID stored");
        check(play.readByte(TestingTools.SPR_STATE + 15) == 0x08, "sprite initialized");
        check(play.readByte(TestingTools.SPR_XL + 15) == 0x08 && play.readByte(TestingTools.SPR_XH + 15) == 0x01,
                "sprite X = LinkX + 16");
        check(play.readByte(TestingTools.SPR_FLOOR + 15) == 1, "sprite layer matches Link");
    }

    static void testFrameStepperWorksWhenPaused() {
        FakeMemory paused = new FakeMemory(0x0E, 0);
        TestingTools tools = new TestingTools(new RecordingSink());
        int[] stepped = {0};
        String r1 = tools.runAction(TestingTools.LiveAction.STEP_1, paused, n -> { stepped[0] += n; return true; });
        String r10 = tools.runAction(TestingTools.LiveAction.STEP_10, paused, n -> { stepped[0] += n; return true; });
        check("Stepped 1 frame".equals(r1) && "Stepped 10 frames".equals(r10), "step messages");
        check(stepped[0] == 11, "step sink received 1 + 10 frames");
    }

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
