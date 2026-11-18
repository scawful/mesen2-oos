package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class PresetDataTest {
    private static int checks;
    private static CheckpointData cpData;
    private static PresetData presetData;

    public static void main(String[] args) throws Exception {
        loadBundledData();
        testBundledPresetsOrderAndMetadata();
        testFullPresetInPlaceAndSramChecksum();
        testZoraAndGoronMilestones();
        testBadJsonSafety();
        System.out.println("PresetDataTest OK (" + checks + " checks)");
    }

    private static void loadBundledData() throws Exception {
        String[] roots = {
            "Android/app/src/main/assets/oracle",
            "app/src/main/assets/oracle",
            "../app/src/main/assets/oracle"
        };
        for (String root : roots) {
            File presets = new File(root, "presets.json");
            File cp = new File(root, "checkpoints.json");
            File warps = new File(root, "warps.json");
            if (presets.isFile() && cp.isFile() && warps.isFile()) {
                String pJson = new String(Files.readAllBytes(presets.toPath()), StandardCharsets.UTF_8);
                String cJson = new String(Files.readAllBytes(cp.toPath()), StandardCharsets.UTF_8);
                String wJson = new String(Files.readAllBytes(warps.toPath()), StandardCharsets.UTF_8);
                cpData = CheckpointData.parse(cJson, wJson);
                presetData = PresetData.parse(pJson, cpData, wJson);
                return;
            }
        }
        throw new AssertionError("bundled presets.json / checkpoints.json / warps.json not found");
    }

    private static void testBundledPresetsOrderAndMetadata() {
        check(cpData.error == null && presetData.error == null, "parsed without error: " + presetData.error);
        String[] expectedNames = {"FULL", "POST_INTRO", "D1", "D2", "D3", "ZORA", "D5", "GORON", "D7", "D8"};
        check(presetData.presets.size() == expectedNames.length,
                "10 presets, got " + presetData.presets.size());
        for (int i = 0; i < expectedNames.length; i++) {
            PresetData.Preset p = presetData.presets.get(i);
            check(p.name.equals(expectedNames[i]), "preset[" + i + "] = " + expectedNames[i] + ", got " + p.name);
            check(p.proposed, p.name + " marked proposed");
            check(p.writes.length % 3 == 0, p.name + " packed triples");
            if (i == 0) {
                check(p.inPlace && p.checkpoint == null, "FULL is in-place without warp");
            } else {
                check(!p.inPlace && p.checkpoint != null, p.name + " has warp checkpoint");
                check(!p.baseCheckpoint.isEmpty(), p.name + " has base_checkpoint");
            }
        }
    }

    private static void testFullPresetInPlaceAndSramChecksum() {
        PresetData.Preset full = presetData.find("full");
        check(full != null && full.displayTag().contains("PROPOSED") && full.displayTag().contains("in-place"),
                "FULL tag marks PROPOSED + in-place");

        ProgressionTest.Emu emu = new ProgressionTest.Emu(0x09, 0);
        emu.wram.put(Inventory.OOSPROG2, 0xFF); // verify storm bit $80 gets cleared on FULL
        String msg = PresetData.applyInPlace(full, emu);
        check(msg.startsWith("Applied FULL"), "FULL applied: " + msg);
        check(emu.log.isEmpty() && emu.paused == 0, "FULL does not warp or alter pause state");

        // Post-intro, no Part 0 storm, endgame story + all dungeons + all items + all songs
        check(emu.readByte(Inventory.INTRO_STATE) == 0x02, "FULL IntroState=2");
        check(emu.readByte(Inventory.GAME_STATE) == 0x03, "FULL GameState=3");
        check((emu.readByte(Inventory.OOSPROG2) & 0x80) == 0, "FULL clears Part0 storm bit $80");
        check(emu.readByte(Inventory.MAKU_TREE_QUEST) == 0x03, "FULL Maku Seed");
        check(emu.readByte(Inventory.CRYSTALS) == 0xFF, "FULL all 8 essences");
        check(emu.readByte(Inventory.PENDANTS) == 0x07, "FULL all 3 pendants");
        check(emu.readByte(Inventory.FLUTE) == 7, "FULL Ocarina count 7");
        check((emu.readByte(Inventory.SIDE_QUEST_2) & 0x02) == 0x02, "FULL Epona song");
        check(emu.readByte(Inventory.SKY_SOARING_LEARNED) == 0xA5, "FULL Sky Soaring learned");
        check(emu.readByte(Inventory.GOLDSTAR_OWNED) == 1
                && emu.readByte(Inventory.GOLDSTAR_VERSION) == 0xA5, "FULL Goldstar + version");
        check(emu.readByte(Inventory.SWITCH_HOOK_OWNED) == 1, "FULL Switch Hook");
        check(emu.readByte(Inventory.BOMBCHU_OWNED) == 1 && emu.readByte(Inventory.BOMBCHU_AMMO) == 20, "FULL Bombchus");
        check(emu.readByte(Inventory.BYRNA_OWNED) == 1 && emu.readByte(Inventory.PORTAL_ROD_OWNED) == 1
                && emu.readByte(Inventory.FISHING_ROD) == 1 && emu.readByte(Inventory.GARO_MASK_OWNED) == 1,
                "FULL newest items");
        check(emu.readByte(Inventory.SWORD) == 4 && emu.readByte(Inventory.SWORD_STORY_BLADE) == 4
                && emu.readByte(Inventory.SWORD_SLOT_FLAGS) == 0x85, "FULL L4 + Heavy Sword Reforged");
        check(emu.readByte(Inventory.RUPEES) == 0xE7 && emu.readByte(Inventory.RUPEES + 1) == 0x03
                && emu.readByte(Inventory.RUPEES_DISPLAY) == 0xE7 && emu.readByte(Inventory.RUPEES_DISPLAY + 1) == 0x03,
                "FULL 999 rupees + HUD display");
        check(emu.readByte(Inventory.HEALTH_MAX) == 0xA0 && emu.readByte(Inventory.HEALTH_CURRENT) == 0xA0
                && emu.readByte(Inventory.HEART_PIECES) == 3, "FULL 20 hearts + 3 pieces");

        // Verify SRAM persistence + valid $5A5A checksum in slot 1 main & mirror
        byte[] block = new byte[Progression.SAVE_SIZE];
        for (int i = 0; i < Progression.SAVE_SIZE; i++) {
            block[i] = (byte) emu.readSram(i);
            check(emu.readSram(i) == emu.readSram(Progression.SRAM_MIRROR + i), "mirror byte " + i);
        }
        int total = 0;
        for (int i = 0; i < Progression.SAVE_SIZE; i += 2) {
            total += (block[i] & 0xFF) | ((block[i + 1] & 0xFF) << 8);
        }
        check((total & 0xFFFF) == 0x5A5A, "FULL SRAM checksum sums to $5A5A");
    }

    private static void testZoraAndGoronMilestones() {
        PresetData.Preset zora = presetData.find("zora");
        check(zora != null && zora.checkpoint != null, "ZORA preset present");
        ProgressionTest.Emu zoraEmu = new ProgressionTest.Emu(0x09, 0);
        zoraEmu.landRoom = zora.checkpoint.expectRoom;
        String zoraRes = Progression.apply(zora.checkpoint, zoraEmu);
        check(!zoraRes.contains("failed") && !zoraRes.contains("CHECK"), "ZORA warp: " + zoraRes);
        check(zoraEmu.readByte(Inventory.FLIPPERS) == 1 && zoraEmu.readByte(Inventory.SWORD_STORY_BLADE) == 2
                && (zoraEmu.readByte(Inventory.PENDANTS) & 0x01) == 0x01
                && (zoraEmu.readByte(Inventory.ABILITY) & 0x06) == 0x06
                && zoraEmu.readByte(Inventory.BOMBS) == 30 && zoraEmu.readByte(Inventory.ARROWS) == 40
                && zoraEmu.readSram(0x356) == 1, "ZORA kit + Flippers + SRAM synced");

        PresetData.Preset goron = presetData.find("goron");
        check(goron != null && goron.checkpoint != null, "GORON preset present");
        ProgressionTest.Emu goronEmu = new ProgressionTest.Emu(0x09, 0);
        goronEmu.landRoom = goron.checkpoint.expectRoom;
        String goronRes = Progression.apply(goron.checkpoint, goronEmu);
        check(!goronRes.contains("failed") && !goronRes.contains("CHECK"), "GORON warp: " + goronRes);
        check(goronEmu.readByte(Inventory.GLOVES) == 1 && goronEmu.readByte(Inventory.ROCK_MEAT) == 5
                && (goronEmu.readByte(Inventory.PENDANTS) & 0x03) == 0x03
                && (goronEmu.readByte(Inventory.SIDE_QUEST) & 0x20) == 0x20
                && goronEmu.readByte(Inventory.GARO_MASK_OWNED) == 1
                && goronEmu.readByte(Inventory.BOMBCHU_AMMO) == 15
                && goronEmu.readSram(0x38F) == 5, "GORON kit + Gloves + RockMeat + SRAM synced");
    }

    private static void testBadJsonSafety() {
        PresetData missing = PresetData.parse(null, cpData, "{}");
        check(missing.error != null && missing.presets.isEmpty(), "null presets returns error cleanly");

        String badBase = "{\"presets\":[{\"id\":\"x\",\"base_checkpoint\":\"no-such-cp\",\"writes\":[]}]}";
        PresetData bad = PresetData.parse(badBase, cpData, "{}");
        check(bad.error != null && bad.error.contains("no-such-cp"), "unknown base_checkpoint caught: " + bad.error);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
