package ca.mesen.oos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Handheld beta-testing cheats, sprite/time manipulators, and frame-step controls.
 * Frame-locked cheats push WRAM Pro Action Replay overrides into Mesen's zero-debugger
 * `CheatManager` NMI hook (`RefreshRamCheats`). Pure Java so host tests run directly.
 */
public final class TestingTools {
    public enum Cheat {
        INF_HEALTH("Infinite health", "Frame-locks HP ($7EF36D) to current max hearts"),
        NO_DAMAGE("Invulnerable (No damage)", "Sets $7E037B = 1 so hazards and hits deal 0 damage"),
        INF_MAGIC_AMMO("Infinite magic & ammo", "Locks magic, bombs, arrows, bombchus & deku sticks full"),
        WALK_WALLS("Walk through walls", "Sets $7E037F = 1 for collision bypass (CHEAT flag)"),
        FREEZE_SPRITES("Freeze sprites", "Sets $7E0FC1 = 1 to pause active sprite AI");

        public final String label;
        public final String detail;

        Cheat(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    public enum LiveAction {
        TOGGLE_LAYER("Toggle Link layer", "Swaps Link floor $7E00EE between BG2 (0) and BG1 (1)"),
        TIME_OF_DAY("Cycle time of day", "Advances $7EE000 Dawn/Noon/Dusk/Midnight & forces reload"),
        CLEAR_ENEMIES("Clear room enemies", "Marks active non-impervious sprites as dying ($7E0DD0=6)"),
        SPAWN_SPRITE("Spawn test sprite", "Places selected sprite ID 16 px right of Link"),
        STEP_1("Step 1 frame", "Advances emulation 1 frame while paused"),
        STEP_10("Step 10 frames", "Advances emulation 10 frames while paused");

        public final String label;
        public final String detail;

        LiveAction(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    public interface CheatSink {
        void apply(int[] addrs, int[] values);
    }

    public interface StepSink {
        boolean stepFrames(int frames);
    }

    static final int WRAM_NOPAIN = 0x7E037B;
    static final int WRAM_CHEAT_WALLS = 0x7E037F;
    static final int WRAM_FREEZE_SPR = 0x7E0FC1;
    static final int WRAM_LINK_LAYER = 0x7E00EE;
    static final int WRAM_LINK_X = 0x7E0022;
    static final int WRAM_LINK_Y = 0x7E0020;

    static final int WRAM_TIME_HOURS = 0x7EE000;
    static final int WRAM_TIME_MINUTES = 0x7EE001;
    static final int WRAM_TIME_DIRTY = 0x7EE007;

    static final int SPR_SLOTS = 16;
    static final int SPR_YL = 0x7E0D00;
    static final int SPR_XL = 0x7E0D10;
    static final int SPR_YH = 0x7E0D20;
    static final int SPR_XH = 0x7E0D30;
    static final int SPR_STATE = 0x7E0DD0;
    static final int SPR_AUX = 0x7E0DE0;
    static final int SPR_TIMER = 0x7E0DF0;
    static final int SPR_ID = 0x7E0E20;
    static final int SPR_HP = 0x7E0E50;
    static final int SPR_PROPS = 0x7E0E60;
    static final int SPR_FLOOR = 0x7E0F20;
    static final int SPR_COLPROP = 0x7E0F60;

    private static final int[] TIME_HOURS = {6, 12, 18, 0};
    private static final String[] TIME_LABELS = {"06:00 Dawn", "12:00 Noon", "18:00 Dusk", "00:00 Midnight"};

    private final CheatSink cheatSink;
    private final EnumSet<Cheat> activeCheats = EnumSet.noneOf(Cheat.class);
    private int timePhaseIndex = 1; // Next tap advances to 12:00 -> 18:00 -> 00:00 -> 06:00
    private int spawnSpriteId = 0x41; // Green Soldier by default

    public TestingTools(CheatSink cheatSink) {
        this.cheatSink = cheatSink;
    }

    public boolean isCheatActive(Cheat cheat) {
        return activeCheats.contains(cheat);
    }

    public int activeCheatCount() {
        return activeCheats.size();
    }

    public int spawnSpriteId() {
        return spawnSpriteId;
    }

    public String spawnSpriteText() {
        return String.format(Locale.US, "$%02X", spawnSpriteId);
    }

    public String timePhaseText() {
        return TIME_LABELS[timePhaseIndex];
    }

    public void stepSpawnSpriteId(int delta) {
        spawnSpriteId = (spawnSpriteId + delta) & 0xFF;
    }

    public String toggleCheat(Cheat cheat, Inventory.Memory memory) {
        if (cheat == null) return "";
        boolean enabled;
        if (activeCheats.contains(cheat)) {
            activeCheats.remove(cheat);
            enabled = false;
            clearReleasedFlag(cheat, memory);
        } else {
            activeCheats.add(cheat);
            enabled = true;
            if (memory != null && Inventory.gameplayProblem(memory) == null) {
                if (cheat == Cheat.INF_HEALTH) {
                    int maxHp = memory.readByte(Inventory.HEALTH_MAX);
                    memory.writeByte(Inventory.HEALTH_CURRENT, maxHp > 0 ? maxHp : 0x18);
                } else if (cheat == Cheat.INF_MAGIC_AMMO) {
                    memory.writeByte(Inventory.MAGIC, 0x80);
                    memory.writeByte(Inventory.BOMBS, 50);
                    memory.writeByte(Inventory.ARROWS, 70);
                    memory.writeByte(Inventory.BOMBCHU_AMMO, Inventory.BOMBCHU_AMMO_MAX);
                    memory.writeByte(Inventory.DEKU_STICKS, 20);
                }
            }
        }
        syncCheats(memory);
        return cheat.label + ": " + (enabled ? "ON" : "OFF");
    }

    public void syncCheats(Inventory.Memory memory) {
        if (cheatSink == null) return;
        List<Integer> addrs = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();

        if (activeCheats.contains(Cheat.INF_HEALTH)) {
            int maxHp = memory != null ? memory.readByte(Inventory.HEALTH_MAX) : -1;
            int targetHp = maxHp > 0 ? maxHp : 0x18;
            addrs.add(Inventory.HEALTH_CURRENT);
            vals.add(targetHp);
        }
        if (activeCheats.contains(Cheat.NO_DAMAGE)) {
            addrs.add(WRAM_NOPAIN);
            vals.add(1);
        }
        if (activeCheats.contains(Cheat.INF_MAGIC_AMMO)) {
            addrs.add(Inventory.MAGIC);
            vals.add(0x80);
            addrs.add(Inventory.BOMBS);
            vals.add(50);
            addrs.add(Inventory.ARROWS);
            vals.add(70);
            addrs.add(Inventory.BOMBCHU_AMMO);
            vals.add(Inventory.BOMBCHU_AMMO_MAX);
            addrs.add(Inventory.DEKU_STICKS);
            vals.add(20);
        }
        if (activeCheats.contains(Cheat.WALK_WALLS)) {
            addrs.add(WRAM_CHEAT_WALLS);
            vals.add(1);
        }
        if (activeCheats.contains(Cheat.FREEZE_SPRITES)) {
            addrs.add(WRAM_FREEZE_SPR);
            vals.add(1);
        }

        int[] a = new int[addrs.size()];
        int[] v = new int[vals.size()];
        for (int i = 0; i < addrs.size(); i++) {
            a[i] = addrs.get(i);
            v[i] = vals.get(i) & 0xFF;
        }
        cheatSink.apply(a, v);
    }

    public String clearAll(Inventory.Memory memory) {
        boolean hadAny = !activeCheats.isEmpty();
        activeCheats.clear();
        clearReleasedFlag(Cheat.NO_DAMAGE, memory);
        clearReleasedFlag(Cheat.WALK_WALLS, memory);
        clearReleasedFlag(Cheat.FREEZE_SPRITES, memory);
        if (cheatSink != null) {
            cheatSink.apply(new int[0], new int[0]);
        }
        return hadAny ? "All testing cheats cleared" : "No active testing cheats";
    }

    private static void clearReleasedFlag(Cheat cheat, Inventory.Memory memory) {
        if (memory == null) return;
        int addr;
        switch (cheat) {
            case NO_DAMAGE: addr = WRAM_NOPAIN; break;
            case WALK_WALLS: addr = WRAM_CHEAT_WALLS; break;
            case FREEZE_SPRITES: addr = WRAM_FREEZE_SPR; break;
            default: return;
        }
        if (memory.readByte(addr) >= 0) {
            memory.writeByte(addr, 0);
        }
    }

    public String runAction(LiveAction action, Inventory.Memory memory, StepSink stepSink) {
        if (action == null) return "";
        if (action == LiveAction.STEP_1 || action == LiveAction.STEP_10) {
            int count = action == LiveAction.STEP_1 ? 1 : 10;
            if (stepSink == null) return "Frame stepper unavailable";
            return stepSink.stepFrames(count)
                    ? String.format(Locale.US, "Stepped %d frame%s", count, count == 1 ? "" : "s")
                    : "Resume/load a ROM first (emulator must be running)";
        }

        if (memory == null) return "Memory unavailable";
        String problem = Inventory.gameplayProblem(memory);
        if (problem != null) return problem;

        switch (action) {
            case TOGGLE_LAYER: {
                int cur = memory.readByte(WRAM_LINK_LAYER);
                if (cur < 0) return "Could not read Link layer ($7E00EE)";
                int next = cur == 0 ? 1 : 0;
                if (!memory.writeByte(WRAM_LINK_LAYER, next)) {
                    return "Write failed: $7E00EE";
                }
                return next == 1 ? "Link layer: 1 (Upper)" : "Link layer: 0 (Lower)";
            }
            case TIME_OF_DAY: {
                timePhaseIndex = (timePhaseIndex + 1) % TIME_HOURS.length;
                int hour = TIME_HOURS[timePhaseIndex];
                if (!memory.writeByte(WRAM_TIME_HOURS, hour)
                        || !memory.writeByte(WRAM_TIME_MINUTES, 0)
                        || !memory.writeByte(WRAM_TIME_DIRTY, 1)) {
                    return "Write failed: TimeState";
                }
                return "Time set: " + TIME_LABELS[timePhaseIndex];
            }
            case CLEAR_ENEMIES: {
                int cleared = 0;
                for (int i = 0; i < SPR_SLOTS; i++) {
                    int state = memory.readByte(SPR_STATE + i);
                    if (state != 0x09 && state != 0x0B) continue;
                    int colProp = memory.readByte(SPR_COLPROP + i);
                    int props = memory.readByte(SPR_PROPS + i);
                    if (colProp < 0 || props < 0) continue;
                    if ((colProp & 0x40) != 0 || (props & 0x80) != 0) continue;
                    memory.writeByte(SPR_HP + i, 0);
                    memory.writeByte(SPR_TIMER + i, 4);
                    memory.writeByte(SPR_STATE + i, 6);
                    cleared++;
                }
                return cleared == 0
                        ? "No clearable enemies active"
                        : String.format(Locale.US, "Cleared %d %s", cleared, cleared == 1 ? "enemy" : "enemies");
            }
            case SPAWN_SPRITE: {
                int xLo = memory.readByte(WRAM_LINK_X);
                int xHi = memory.readByte(WRAM_LINK_X + 1);
                int yLo = memory.readByte(WRAM_LINK_Y);
                int yHi = memory.readByte(WRAM_LINK_Y + 1);
                int layer = memory.readByte(WRAM_LINK_LAYER);
                if (xLo < 0 || xHi < 0 || yLo < 0 || yHi < 0 || layer < 0) {
                    return "Could not read Link coordinates";
                }
                int slot = -1;
                for (int i = SPR_SLOTS - 1; i >= 0; i--) {
                    if (memory.readByte(SPR_STATE + i) == 0) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) return "All 16 sprite slots are full";
                int x = ((xLo | (xHi << 8)) + 16) & 0xFFFF;
                int y = (yLo | (yHi << 8)) & 0xFFFF;
                memory.writeByte(SPR_ID + slot, spawnSpriteId & 0xFF);
                memory.writeByte(SPR_XL + slot, x & 0xFF);
                memory.writeByte(SPR_XH + slot, (x >> 8) & 0xFF);
                memory.writeByte(SPR_YL + slot, y & 0xFF);
                memory.writeByte(SPR_YH + slot, (y >> 8) & 0xFF);
                memory.writeByte(SPR_FLOOR + slot, layer & 0xFF);
                memory.writeByte(SPR_AUX + slot, 0);
                memory.writeByte(SPR_TIMER + slot, 0);
                memory.writeByte(SPR_STATE + slot, 0x08);
                return String.format(Locale.US, "Spawned $%02X in slot %d", spawnSpriteId & 0xFF, slot);
            }
            default:
                return "";
        }
    }
}
