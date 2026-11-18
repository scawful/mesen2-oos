package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Story checkpoints for the Progression page. Mirrors the desktop
 * oracle_cheats apply_checkpoint rule: resolve the 'after' chain oldest first,
 * write the bytes to WRAM, copy $7EF000-$7EF4FF to the active SRAM slot (main +
 * mirror + inverse checksum), then warp. Bytes no checkpoint names keep their
 * current values. Data comes from CheckpointData (bundled checkpoints.json).
 */
final class Progression {
    enum Kind { ENTRANCE, AREA }

    static final class Checkpoint {
        final String id;
        final String label;
        final int beat;
        final Kind kind;
        final int target;
        /** ENTRANCE only: a house entrance whose exit comes from the cache. */
        final boolean cacheExit;
        /** Area to warp to (AREA target, or the house area for a cache entrance); -1 if none. */
        final int area;
        final int viaRoom;
        /** AREA only: entrance to reload through first; -1 for ENTRANCE. */
        final int primeEntrance;
        /** ENTRANCE only: room the entrance leads to; -1 if unknown. */
        final int room;
        final int[] expectModules;
        final int expectRoom;
        final int expectArea;
        final String[] chain;
        /** Triples: addr, op (0 value, 1 set_bits, 2 clear_bits), operand. */
        final int[] writes;

        Checkpoint(String id, String label, int beat, Kind kind, int target, boolean cacheExit,
                   int area, int viaRoom, int primeEntrance, int room,
                   int[] expectModules, int expectRoom, int expectArea,
                   String[] chain, int[] writes) {
            this.id = id;
            this.label = label;
            this.beat = beat;
            this.kind = kind;
            this.target = target;
            this.cacheExit = cacheExit;
            this.area = area;
            this.viaRoom = viaRoom;
            this.primeEntrance = primeEntrance;
            this.room = room;
            this.expectModules = expectModules;
            this.expectRoom = expectRoom;
            this.expectArea = expectArea;
            this.chain = chain;
            this.writes = writes;
        }

        String rowLabel() {
            return String.format(Locale.ROOT, "%d · %s", beat, label);
        }
    }

    /** Emulator access. LIVE uses MesenNative; tests use a fake. */
    interface Host {
        int readByte(int addr);
        boolean writeByte(int addr, int value);
        int readSram(int offset);
        boolean writeSram(int offset, int value);
        void pause();
        void resume();
        long nowMs();
        void sleepMs(long ms);
    }

    static final int MODULE = 0x7E0010;
    static final int SUBMODULE = 0x7E0011;
    static final int INDOORS = 0x7E001B;
    static final int BRIGHTNESS = 0x7E0013;
    static final int AREA = 0x7E008A;
    static final int HDMA_ENABLE = 0x7E009B;
    static final int ROOM = 0x7E00A0;
    static final int SUBSUB = 0x7E00B0;
    static final int CONTINUE_FLAG = 0x7E010A;
    static final int NEXT_MODULE = 0x7E010C;
    static final int ENTRANCE = 0x7E010E;
    static final int RESPAWN_FLAG = 0x7E04AA;
    static final int FOLLOWER = 0x7EF3CC;
    static final int GAME_STATE = 0x7EF3C5;
    static final int CRYSTALS = 0x7EF37A;
    static final int SPAWN_FOLLOWER = 0x02;

    static final int MOD_DUNGEON = 0x07;
    static final int MOD_OVERWORLD = 0x09;
    static final int MOD_SPECIAL = 0x0B;
    static final int MOD_MESSAGE = 0x0E;

    static final int SAVE_START = 0x7EF000;
    static final int SAVE_SIZE = 0x500;
    static final int SRAM_MIRROR = 0xF00;
    static final int SRAM_ACTIVE_SLOT = 0x1FFE;
    static final int CHECKSUM_OFFSET = 0x4FE;

    static final long WAIT_TIMEOUT_MS = 15000;
    static final long WAIT_SETTLE_MS = 300;
    static final long WAIT_POLL_MS = 50;

    private Progression() {}

    static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }

    static boolean isPlayModule(int module) {
        return module == MOD_DUNGEON || module == MOD_OVERWORLD || module == MOD_SPECIAL;
    }

    /** Null when Link is in normal play; otherwise the reason to refuse. */
    static String idleProblem(Host host) {
        int module = host.readByte(MODULE);
        int sub = host.readByte(SUBMODULE);
        if (module < 0 || sub < 0) return "Could not read the game state";
        if (!isPlayModule(module) || sub != 0) {
            return String.format(Locale.ROOT,
                    "Resume normal play first (module $%02X sub $%02X)", module, sub);
        }
        return null;
    }

    /** Final byte per address after applying a checkpoint's writes to current values. */
    static Map<Integer, Integer> resolveBytes(Checkpoint cp, Host host) throws Failure {
        return resolveBytes(cp.writes, host);
    }

    /** Final byte per address after applying packed {addr, op, operand} triples. */
    static Map<Integer, Integer> resolveBytes(int[] writes, Host host) throws Failure {
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        for (int i = 0; i + 2 < writes.length; i += 3) {
            int addr = writes[i];
            int op = writes[i + 1];
            int operand = writes[i + 2] & 0xFF;
            Integer known = expected.get(addr);
            int current = known != null ? known : host.readByte(addr);
            if (current < 0) throw new Failure(String.format(Locale.ROOT, "Could not read $%06X", addr));
            int next;
            switch (op) {
                case 0: next = operand; break;
                case 1: next = (current | operand) & 0xFF; break;
                case 2: next = current & ~operand & 0xFF; break;
                default: throw new Failure("Bad write op " + op);
            }
            expected.put(addr, next);
        }
        return expected;
    }

    /**
     * Write WRAM bytes in-place and sync SRAM (active slot + mirror + checksum)
     * without changing pause state. Used when applying presets/edits from the
     * paused menu overlay during normal play.
     */
    static String applyWrites(String label, int[] writes, Host host) {
        try {
            String idle = idleProblem(host);
            if (idle != null) return idle;
            Map<Integer, Integer> expected = resolveBytes(writes, host);
            for (Map.Entry<Integer, Integer> e : expected.entrySet()) {
                if (!host.writeByte(e.getKey(), e.getValue())) {
                    throw new Failure(String.format(Locale.ROOT, "Write failed at $%06X", e.getKey()));
                }
            }
            int slot = syncSram(host);
            return String.format(Locale.ROOT, "Applied %s (WRAM + slot %d SRAM)", label, slot);
        } catch (Failure f) {
            return "Preset " + label + " failed: " + f.getMessage();
        }
    }

    static int activeSlot(Host host) {
        int lo = host.readSram(SRAM_ACTIVE_SLOT);
        int hi = host.readSram(SRAM_ACTIVE_SLOT + 1);
        int value = (lo < 0 ? 0 : lo) | ((hi < 0 ? 0 : hi) << 8);
        return (value == 2 || value == 4 || value == 6) ? value / 2 : 1;
    }

    /** Inverse checksum the game expects at +$4FE: 0x5A5A minus the word sum of 0..$4FD. */
    static int inverseChecksum(byte[] block) {
        int total = 0;
        for (int i = 0; i < CHECKSUM_OFFSET; i += 2) {
            total += (block[i] & 0xFF) | ((block[i + 1] & 0xFF) << 8);
        }
        return (0x5A5A - total) & 0xFFFF;
    }

    /** Copy the WRAM save block to the active slot (main + mirror). Returns the slot. */
    static int syncSram(Host host) throws Failure {
        byte[] block = new byte[SAVE_SIZE];
        for (int i = 0; i < SAVE_SIZE; i++) {
            int b = host.readByte(SAVE_START + i);
            if (b < 0) throw new Failure(String.format(Locale.ROOT, "Could not read $%06X", SAVE_START + i));
            block[i] = (byte) b;
        }
        int inverse = inverseChecksum(block);
        block[CHECKSUM_OFFSET] = (byte) (inverse & 0xFF);
        block[CHECKSUM_OFFSET + 1] = (byte) (inverse >> 8);
        if (!host.writeByte(SAVE_START + CHECKSUM_OFFSET, inverse & 0xFF)
                || !host.writeByte(SAVE_START + CHECKSUM_OFFSET + 1, inverse >> 8)) {
            throw new Failure("Checksum write failed");
        }
        int slot = activeSlot(host);
        int base = (slot - 1) * SAVE_SIZE;
        for (int start : new int[]{base, base + SRAM_MIRROR}) {
            for (int i = 0; i < SAVE_SIZE; i++) {
                if (!host.writeSram(start + i, block[i] & 0xFF)) {
                    throw new Failure(String.format(Locale.ROOT, "SRAM write failed at $%04X", start + i));
                }
            }
        }
        return slot;
    }

    /** Write a batch while paused so no frame sees half of it. */
    static void poke(Host host, int... pairs) throws Failure {
        host.pause();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (!host.writeByte(pairs[i], pairs[i + 1])) {
                    throw new Failure(String.format(Locale.ROOT, "Write failed at $%06X", pairs[i]));
                }
            }
        } finally {
            host.resume();
        }
    }

    static boolean landed(Host host, int[] modules) {
        int module = host.readByte(MODULE);
        int sub = host.readByte(SUBMODULE);
        if (module < 0 || sub < 0) return false;
        boolean wanted = false;
        for (int m : modules) wanted |= m == module;
        return wanted && (sub == 0 || !isPlayModule(module));
    }

    static boolean waitPlay(Host host, int... modules) {
        long deadline = host.nowMs() + WAIT_TIMEOUT_MS;
        while (host.nowMs() < deadline) {
            if (landed(host, modules)) {
                host.sleepMs(WAIT_SETTLE_MS);
                if (landed(host, modules)) return true;
            }
            host.sleepMs(WAIT_POLL_MS);
        }
        return false;
    }

    static String describe(Host host) {
        int lo = host.readByte(ROOM);
        int hi = host.readByte(ROOM + 1);
        return String.format(Locale.ROOT, "module $%02X sub $%02X room $%04X area $%02X",
                host.readByte(MODULE), host.readByte(SUBMODULE),
                (lo < 0 ? 0 : lo) | ((hi < 0 ? 0 : hi) << 8), host.readByte(AREA));
    }

    static void doorWarp(Host host, int eid) throws Failure {
        poke(host, ENTRANCE, eid, ENTRANCE + 1, 0, CONTINUE_FLAG, 0, RESPAWN_FLAG, 0,
                0x7E004D, 0, 0x7E0046, 0, NEXT_MODULE, 0x06, SUBMODULE, 0, SUBSUB, 0, MODULE, 0x0F);
    }

    static void reloadWarp(Host host, int eid) throws Failure {
        poke(host, ENTRANCE, eid, ENTRANCE + 1, 0, CONTINUE_FLAG, 1, RESPAWN_FLAG, 0,
                INDOORS, 1, SUBMODULE, 0, SUBSUB, 0, MODULE, 0x05);
    }

    static void areaWarp(Host host, int viaRoom) throws Failure {
        poke(host, ROOM, viaRoom & 0xFF, ROOM + 1, (viaRoom >> 8) & 0xFF, CONTINUE_FLAG, 0,
                RESPAWN_FLAG, 0, BRIGHTNESS, 0x80, HDMA_ENABLE, 0, SUBMODULE, 0, SUBSUB, 0, MODULE, 0x08);
    }

    static void requireNoSpawnFollower(Host host) throws Failure {
        if (host.readByte(FOLLOWER) == SPAWN_FOLLOWER) {
            throw new Failure("Follower $02 makes the entrance load the spawn point; clear it first");
        }
    }

    /** Applies one checkpoint end to end. Blocks for the warp; run off the UI thread. */
    static String apply(Checkpoint cp, Host host) {
        List<String> steps = new ArrayList<>();
        try {
            String idle = idleProblem(host);
            if (idle != null) return idle;
            Map<Integer, Integer> expected = resolveBytes(cp, host);
            int slot;
            host.pause();
            try {
                for (Map.Entry<Integer, Integer> e : expected.entrySet()) {
                    if (!host.writeByte(e.getKey(), e.getValue())) {
                        throw new Failure(String.format(Locale.ROOT, "Write failed at $%06X", e.getKey()));
                    }
                }
                slot = syncSram(host);
            } finally {
                host.resume();
            }
            steps.add("slot " + slot);
            requireNoSpawnFollower(host);
            int[] expectMods = cp.expectModules.length > 0 ? cp.expectModules : new int[]{MOD_DUNGEON};
            if (cp.kind == Kind.ENTRANCE) {
                int module = host.readByte(MODULE);
                boolean outdoors = module == MOD_OVERWORLD && host.readByte(INDOORS) == 0;
                String method;
                if (outdoors) {
                    method = "door";
                } else if (cp.cacheExit && cp.viaRoom >= 0) {
                    areaWarp(host, cp.viaRoom);
                    if (!waitPlay(host, MOD_OVERWORLD, MOD_SPECIAL)) {
                        throw new Failure("House area step did not finish; " + describe(host));
                    }
                    method = host.readByte(MODULE) == MOD_OVERWORLD ? "door" : "reload";
                    steps.add(String.format(Locale.ROOT, "area $%02X", cp.area));
                } else {
                    method = "reload";
                }
                if (method.equals("door")) doorWarp(host, cp.target);
                else reloadWarp(host, cp.target);
                steps.add(String.format(Locale.ROOT, "entrance $%02X %s", cp.target, method));
                if (!waitPlay(host, concat(expectMods, MOD_MESSAGE))) {
                    throw new Failure("Warp did not finish; " + describe(host));
                }
            } else {
                // Module08 keeps the previous HUD and follower graphics; a full
                // entrance reload first rebuilds both, then the area warp follows.
                reloadWarp(host, cp.primeEntrance);
                if (!waitPlay(host, MOD_DUNGEON, MOD_MESSAGE)) {
                    throw new Failure("Reload step did not finish; " + describe(host));
                }
                steps.add(String.format(Locale.ROOT, "reload $%02X", cp.primeEntrance));
                areaWarp(host, cp.viaRoom);
                if (!waitPlay(host, MOD_OVERWORLD, MOD_SPECIAL, MOD_MESSAGE)) {
                    throw new Failure("Area warp did not finish; " + describe(host));
                }
                steps.add(String.format(Locale.ROOT, "area $%02X", cp.target));
            }
            String problem = verify(cp, host, expected);
            String summary = cp.id + ": " + String.join(", ", steps);
            return problem == null ? "Checkpoint " + summary : "Checkpoint " + summary + " · CHECK: " + problem;
        } catch (Failure f) {
            String done = steps.isEmpty() ? "" : " (done: " + String.join(", ", steps) + ")";
            return "Checkpoint " + cp.id + " failed: " + f.getMessage() + done;
        }
    }

    /** Null when the landing matches checkpoints.json 'expect'; else the first mismatch. */
    static String verify(Checkpoint cp, Host host, Map<Integer, Integer> expected) {
        int module = host.readByte(MODULE);
        boolean cutscene = !isPlayModule(module) && module != MOD_MESSAGE;
        boolean indoors = host.readByte(INDOORS) != 0;
        if (cp.expectModules.length > 0) {
            boolean ok = false;
            for (int m : cp.expectModules) ok |= m == module;
            if (!ok) return String.format(Locale.ROOT, "module $%02X not expected", module);
        }
        if (cp.expectRoom >= 0 && indoors && !cutscene) {
            int room = host.readByte(ROOM) | (host.readByte(ROOM + 1) << 8);
            if (room != cp.expectRoom) {
                return String.format(Locale.ROOT, "room $%04X != $%04X", room, cp.expectRoom);
            }
        }
        if (cp.expectArea >= 0 && !indoors && !cutscene) {
            int area = host.readByte(AREA);
            if (area != cp.expectArea) {
                return String.format(Locale.ROOT, "area $%02X != $%02X", area, cp.expectArea);
            }
        }
        for (int addr : new int[]{GAME_STATE, CRYSTALS}) {
            Integer want = expected.get(addr);
            if (want != null && host.readByte(addr) != want) {
                return String.format(Locale.ROOT, "$%06X = $%02X, expected $%02X",
                        addr, host.readByte(addr), want);
            }
        }
        return null;
    }

    private static int[] concat(int[] a, int extra) {
        int[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = extra;
        return out;
    }

    static final Host LIVE = new Host() {
        public int readByte(int addr) { return MesenNative.readByte(addr); }
        public boolean writeByte(int addr, int value) { return MesenNative.writeByte(addr, value); }
        public int readSram(int offset) { return MesenNative.readSram(offset); }
        public boolean writeSram(int offset, int value) { return MesenNative.writeSram(offset, value); }
        public void pause() { MesenNative.pauseEmu(); }
        public void resume() { MesenNative.resumeEmu(); }
        public long nowMs() { return System.currentTimeMillis(); }
        public void sleepMs(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };
}
