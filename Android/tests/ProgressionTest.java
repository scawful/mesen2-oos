package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ProgressionTest {
    private static int checks;
    private static CheckpointData data;

    /** Fake emulator: WRAM/SRAM maps plus a module state machine driven by warp pokes. */
    static final class Emu implements Progression.Host {
        final Map<Integer, Integer> wram = new HashMap<>();
        final Map<Integer, Integer> sram = new HashMap<>();
        final List<String> log = new ArrayList<>();
        long now;
        int paused;
        int pending = -1;
        boolean neverLand;
        int landArea = -1;
        int landRoom = -1;

        Emu(int module, int indoors) {
            wram.put(Progression.MODULE, module);
            wram.put(Progression.SUBMODULE, 0);
            wram.put(Progression.INDOORS, indoors);
        }

        public int readByte(int addr) { return wram.getOrDefault(addr, 0); }
        public boolean writeByte(int addr, int value) {
            wram.put(addr, value & 0xFF);
            if (addr == Progression.MODULE && (value == 0x05 || value == 0x0F || value == 0x08)) {
                pending = value;
                log.add(String.format("module %02X entrance %02X", value, readByte(Progression.ENTRANCE)));
            } else if (addr == Progression.SUBMODULE && value == Progression.SUBMODE_FALL) {
                pending = 0x15;
                log.add(String.format("fall room %04X", readByte(Progression.ROOM) | (readByte(Progression.ROOM + 1) << 8)));
            }
            return true;
        }
        public int readSram(int offset) { return sram.getOrDefault(offset, 0); }
        public boolean writeSram(int offset, int value) { sram.put(offset, value & 0xFF); return true; }
        public void pause() { paused++; }
        public void resume() { paused--; }
        public long nowMs() { return now; }
        public void sleepMs(long ms) {
            now += ms;
            if (pending < 0 || neverLand) return;
            if (pending == 0x08) {
                wram.put(Progression.MODULE, 0x09);
                wram.put(Progression.INDOORS, 0);
                wram.put(Progression.AREA, landArea);
            } else if (pending == 0x15) {
                wram.put(Progression.MODULE, 0x07);
                wram.put(Progression.INDOORS, 1);
            } else {
                wram.put(Progression.MODULE, 0x07);
                wram.put(Progression.INDOORS, 1);
                wram.put(Progression.ROOM, landRoom & 0xFF);
                wram.put(Progression.ROOM + 1, landRoom >> 8);
            }
            wram.put(Progression.SUBMODULE, 0);
            pending = -1;
        }
    }

    public static void main(String[] args) throws Exception {
        data = loadBundledData();
        testBundledData();
        testBadJsonSafety();
        testResolveChain();
        testChecksumAndSlot();
        testRefusesOutsidePlay();
        testAreaCheckpointEndToEnd();
        testEntranceFromOverworldUsesDoor();
        testWarpTimeout();
        testApplyWritesInPlace();
        testWarpOnlyWithoutSramOverwrite();
        testFallToRoom();
        System.out.println("ProgressionTest OK (" + checks + " checks)");
    }

    private static CheckpointData loadBundledData() throws Exception {
        String[] roots = {
            "Android/app/src/main/assets/oracle",
            "app/src/main/assets/oracle",
            "../app/src/main/assets/oracle"
        };
        for (String root : roots) {
            File cp = new File(root, "checkpoints.json");
            File warps = new File(root, "warps.json");
            if (cp.isFile() && warps.isFile()) {
                return CheckpointData.parse(
                        new String(Files.readAllBytes(cp.toPath()), StandardCharsets.UTF_8),
                        new String(Files.readAllBytes(warps.toPath()), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("bundled checkpoints.json / warps.json not found");
    }

    static Progression.Checkpoint find(String id) {
        Progression.Checkpoint cp = data.find(id);
        if (cp == null) throw new AssertionError("missing checkpoint " + id);
        return cp;
    }

    static void testBundledData() {
        check(data.error == null, "bundled data parsed without error: " + data.error);
        List<Progression.Checkpoint> all = data.checkpoints;
        check(all.size() >= 40, "at least 40 checkpoints, got " + all.size());
        check(data.skipped.contains("abyss-owl"), "abyss-owl skipped");
        check("GameState".equals(data.names.get(0x7EF3C5)), "GameState name collected");
        check("OOSPROG".equals(data.names.get(0x7EF3D6)), "OOSPROG name collected");
        for (Progression.Checkpoint cp : all) {
            check(!cp.id.equals("abyss-owl"), "abyss-owl has no warps.json area, so it is skipped");
            if (!cp.id.startsWith("jump-")) {
                check(cp.chain[0].equals("intro-bed"), cp.id + " chain starts at intro-bed");
            }
            check(cp.chain[cp.chain.length - 1].equals(cp.id), cp.id + " chain ends at itself");
            check(cp.writes.length % 3 == 0, cp.id + " write triples");
            if (cp.kind == Progression.Kind.AREA) {
                check(cp.viaRoom >= 0 && cp.primeEntrance >= 0, cp.id + " area warp plan");
            }
        }
        check(find("d8-entrance").kind == Progression.Kind.ENTRANCE, "d8 is an entrance");
        check(data.areaWarps.size() >= 36, "at least 36 overworld area warps, got " + data.areaWarps.size());
        check(data.entranceWarps.size() == 80, "80 entrance warps, got " + data.entranceWarps.size());
        for (Progression.Checkpoint aw : data.areaWarps) {
            check(aw.kind == Progression.Kind.AREA && aw.viaRoom >= 0 && aw.primeEntrance >= 0,
                    "area warp valid: " + aw.id);
        }
        for (Progression.Checkpoint ew : data.entranceWarps) {
            check(ew.kind == Progression.Kind.ENTRANCE && ew.target >= 0, "entrance warp valid: " + ew.id);
        }
    }

    static void testBadJsonSafety() {
        CheckpointData missing = CheckpointData.parse(null, "{}");
        check(missing.error != null && missing.checkpoints.isEmpty(), "null json returns error cleanly");
        CheckpointData bad = CheckpointData.parse("{not json", "{}");
        check(bad.error != null && bad.checkpoints.isEmpty(), "bad json returns error cleanly");
        String loop = "{\"checkpoints\":[{\"id\":\"a\",\"after\":\"a\",\"location\":{\"entrance\":\"0x01\"},\"writes\":[]}]}";
        CheckpointData looped = CheckpointData.parse(loop, "{\"entrances\":[],\"areas\":[]}");
        check(looped.error != null && looped.error.contains("loops"), "loop detected cleanly");
    }

    static void testResolveChain() {
        Emu emu = new Emu(0x09, 0);
        emu.wram.put(0x7EF30F, 0x80);
        emu.wram.put(0x7EF343, 30); // bombs: no checkpoint names it
        Progression.Checkpoint cp = find("loom-beach");
        Map<Integer, Integer> bytes;
        try {
            bytes = Progression.resolveBytes(cp, emu);
        } catch (Progression.Failure f) {
            throw new AssertionError(f.getMessage());
        }
        check(bytes.get(0x7EF39E) == 0x02, "later link overrides IntroState");
        check(bytes.get(0x7EF30F) == 0x81, "set_bits ORs onto current value");
        check(bytes.get(0x7EF36C) == 0x18, "intro-bed resets max health");
        check(!bytes.containsKey(0x7EF343), "unnamed bytes are left alone");
    }

    static void testChecksumAndSlot() {
        byte[] block = new byte[Progression.SAVE_SIZE];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i * 7);
        int inv = Progression.inverseChecksum(block);
        block[0x4FE] = (byte) (inv & 0xFF);
        block[0x4FF] = (byte) (inv >> 8);
        int total = 0;
        for (int i = 0; i < Progression.SAVE_SIZE; i += 2) {
            total += (block[i] & 0xFF) | ((block[i + 1] & 0xFF) << 8);
        }
        check((total & 0xFFFF) == 0x5A5A, "words incl. checksum sum to $5A5A");

        Emu emu = new Emu(0x09, 0);
        check(Progression.activeSlot(emu) == 1, "slot defaults to 1");
        emu.sram.put(0x1FFE, 4);
        check(Progression.activeSlot(emu) == 2, "SRAM $1FFE=4 is slot 2");
        emu.sram.put(0x1FFE, 3);
        check(Progression.activeSlot(emu) == 1, "odd value falls back to slot 1");
        emu.sram.put(0x1FFE, 6);
        emu.wram.put(0x7EF359, 2);
        int slot;
        try {
            slot = Progression.syncSram(emu);
        } catch (Progression.Failure f) {
            throw new AssertionError(f.getMessage());
        }
        check(slot == 3, "sync wrote slot 3");
        check(emu.sram.get(0xA00 + 0x359) == 2, "main copy at $A00");
        check(emu.sram.get(0xA00 + 0xF00 + 0x359) == 2, "mirror copy at +$F00");
        check(!emu.sram.containsKey(0x359), "slot 1 untouched");
        check(emu.sram.get(0xA00 + 0x4FE).equals(emu.wram.get(0x7EF4FE)), "WRAM checksum matches SRAM");
    }

    static void testRefusesOutsidePlay() {
        Emu emu = new Emu(0x0E, 0);
        String result = Progression.apply(find("d1-entrance"), emu);
        check(result.startsWith("Resume normal play"), "refuses in message module: " + result);
        check(emu.log.isEmpty() && emu.sram.isEmpty(), "nothing written when refused");
        emu = new Emu(0x09, 0);
        emu.wram.put(Progression.SUBMODULE, 0x23);
        check(Progression.apply(find("d1-entrance"), emu).startsWith("Resume normal play"), "refuses mid-transition");
    }

    static void testAreaCheckpointEndToEnd() {
        // kalyxo-return is after the skipped abyss-owl row; its chain still applies the Moon Pearl.
        Progression.Checkpoint cp = find("kalyxo-return");
        check(java.util.Arrays.asList(cp.chain).contains("abyss-owl"), "chain keeps abyss-owl writes");
        Emu emu = new Emu(0x07, 1);
        emu.landArea = cp.target;
        emu.landRoom = 0x0012;
        String result = Progression.apply(cp, emu);
        check(result.startsWith("Checkpoint kalyxo-return") && !result.contains("failed") && !result.contains("CHECK"),
                "area checkpoint succeeds: " + result);
        check(emu.log.size() == 2, "two warps: " + emu.log);
        check(emu.log.get(0).startsWith("module 05"), "first a reload");
        check(emu.log.get(1).startsWith("module 08"), "then an area warp");
        check(emu.wram.get(0x7EF357) == 1, "Moon Pearl written");
        check(emu.paused == 0, "pause/resume balanced");
        check(emu.sram.get(0x357) == 1, "slot 1 got the Moon Pearl");
    }

    static void testEntranceFromOverworldUsesDoor() {
        Progression.Checkpoint cp = find("d1-entrance");
        Emu emu = new Emu(0x09, 0);
        emu.landRoom = cp.expectRoom >= 0 ? cp.expectRoom : 0;
        String result = Progression.apply(cp, emu);
        check(emu.log.size() == 1 && emu.log.get(0).startsWith("module 0F entrance 26"),
                "door warp from overworld: " + emu.log);
        check(!result.contains("failed"), "entrance succeeds: " + result);
    }

    static void testWarpTimeout() {
        Emu emu = new Emu(0x07, 1);
        emu.neverLand = true;
        String result = Progression.apply(find("d2-entrance"), emu);
        check(result.contains("failed") && result.contains("did not finish") && result.contains("slot 1"),
                "timeout reported with done steps: " + result);
        check(emu.paused == 0, "pause balanced after failure");
    }

    static void testApplyWritesInPlace() {
        Emu emu = new Emu(0x09, 0);
        emu.sram.put(0x1FFE, 4); // slot 2
        emu.wram.put(0x7EF3D8, 0x01);
        int[] ops = {
            0x7EF3D8, 1, 0x02, // set_bits $02 -> $03
            0x7EF3B2, 0, 0xA5, // SkySoaringLearned
        };
        String res = Progression.applyWrites("FULL", ops, emu);
        check(res.contains("Applied FULL") && res.contains("slot 2"), "in-place status: " + res);
        check(emu.paused == 0, "in-place keeps pause state untouched");
        check(emu.wram.get(0x7EF3D8) == 0x03 && emu.sram.get(0x500 + 0x3D8) == 0x03,
                "in-place writes WRAM + slot 2 SRAM");
        check(emu.sram.get(0x500 + 0x3B2) == 0xA5 && emu.sram.get(0x500 + 0xF00 + 0x3B2) == 0xA5,
                "mirror SRAM synced");
        check(emu.sram.get(0x500 + 0x4FE).equals(emu.wram.get(0x7EF4FE)), "checksum updated");

        Emu blocked = new Emu(0x01, 0);
        check(Progression.applyWrites("FULL", ops, blocked).startsWith("Resume normal play"),
                "in-place refused outside play");
    }

    static void testWarpOnlyWithoutSramOverwrite() {
        Progression.Checkpoint darkArea = null;
        for (Progression.Checkpoint cp : data.areaWarps) {
            if ((cp.area & 0x40) != 0) {
                darkArea = cp;
                break;
            }
        }
        check(darkArea != null, "found abyss area warp target");
        Emu emu = new Emu(0x09, 0);
        emu.wram.put(Progression.SAVED_WORLD, 0);
        emu.wram.put(0x7EF359, 3); // existing inventory
        emu.sram.put(0x359, 3);
        emu.landArea = darkArea.area;
        emu.landRoom = darkArea.viaRoom;
        String res = Progression.warpOnly(darkArea, emu);
        check(res.startsWith("Warped") && !res.contains("failed"), "warpOnly succeeds: " + res);
        check(emu.wram.get(Progression.SAVED_WORLD) == 0x40 && emu.wram.get(Progression.WORLD_FLAG) == 0x40,
                "world flags switched for abyss area");
        check(emu.wram.get(0x7EF359) == 3 && emu.sram.get(0x359) == 3,
                "warpOnly did not clobber SRAM/inventory");
    }

    static void testFallToRoom() {
        Emu overworld = new Emu(0x09, 0);
        check(Progression.fallToRoom(0x0104, overworld).contains("module $07"),
                "fallToRoom refused outdoors");
        Emu dungeon = new Emu(0x07, 1);
        check(Progression.fallToRoom(Progression.MAX_ROOM_ID + 1, dungeon).contains("out of range"),
                "out of range room refused");
        dungeon.wram.put(Progression.ROOM, 0x12);
        String ok = Progression.fallToRoom(0x0104, dungeon);
        check("Fell into room $0104".equals(ok), "fallToRoom succeeded: " + ok);
        check(dungeon.wram.get(Progression.PREV_ROOM) == 0x12, "prev room recorded");
        check(dungeon.wram.get(Progression.ROOM) == 0x04 && dungeon.wram.get(Progression.ROOM + 1) == 0x01,
                "target room $0104 written");
    }

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
