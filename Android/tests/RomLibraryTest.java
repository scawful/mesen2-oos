package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

final class RomLibraryTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("rom-library-test").toFile();
        testNameParsing();
        testChannelGroupingAndOrder(root);
        testSidecarAndVersionLine(root);
        testRollbackAndPrune(root);
        System.out.println("RomLibraryTest OK (" + checks + " checks)");
    }

    private static void testNameParsing() {
        RomLibrary.Name a = RomLibrary.parse(new File("Oracle - Main.sfc"));
        check("Main".equals(a.channel) && a.tag.isEmpty(), "untagged main");

        RomLibrary.Name b = RomLibrary.parse(new File("Oracle - Main b58.sfc"));
        check("Main".equals(b.channel) && "b58".equals(b.tag), "tagged main");

        RomLibrary.Name c = RomLibrary.parse(new File("Oracle - Latest l14.sfc"));
        check("Latest".equals(c.channel) && "l14".equals(c.tag), "tagged latest");

        RomLibrary.Name d = RomLibrary.parse(new File("Oracle - b58.sfc"));
        check("b58".equals(d.channel) && d.tag.isEmpty(), "pinned build channel");
    }

    private static void testChannelGroupingAndOrder(File root) throws Exception {
        File dir = new File(root, "group");
        dir.mkdirs();
        File mainPrimary = new File(dir, "Oracle - Main.sfc");
        File mainOld = new File(dir, "Oracle - Main b58.sfc");
        File playtest = new File(dir, "Oracle - Playtest.sfc");
        File latest = new File(dir, "Oracle - Latest.sfc");
        File pinned = new File(dir, "Oracle - b55.sfc");
        for (File f : new File[] { mainPrimary, mainOld, playtest, latest, pinned }) {
            Files.write(f.toPath(), new byte[] { 1, 2, 3 });
        }
        Files.write(new File(dir, "Oracle - Main.build.json").toPath(),
                "{\"build\":62}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "Oracle - Latest.build.json").toPath(),
                "{\"build\":\"l14\"}".getBytes(StandardCharsets.UTF_8));

        List<RomLibrary.Entry> entries = RomLibrary.scan(new File[] { dir });
        check(entries.size() == 4, "4 channels (Main grouped)");
        check("Latest".equals(entries.get(0).channel) && "l14".equals(entries.get(0).build), "Latest first with l14");
        check("Playtest".equals(entries.get(1).channel), "Playtest second");
        check("Main".equals(entries.get(2).channel) && "b62".equals(entries.get(2).build), "Main third with b62");
        check(entries.get(2).file.equals(mainPrimary), "untagged primary wins");
        check(entries.get(2).olderCopies.size() == 1 && entries.get(2).olderCopies.get(0).equals(mainOld), "old copy listed");
        check("b55".equals(entries.get(3).channel) && entries.get(3).pinned, "pinned fourth");
    }

    private static void testSidecarAndVersionLine(File root) throws Exception {
        File dir = new File(root, "ver");
        dir.mkdirs();
        File oversized = new File(dir, "big.build.json");
        Files.write(oversized.toPath(), new byte[65 * 1024]);
        check(RomLibrary.fromSidecar(oversized).isEmpty(), "oversized build report ignored");
        check("b61".equals(RomLibrary.fromVersionText("v0.9.0-b61")), "version text b61");
        check("l14".equals(RomLibrary.fromVersionText("v0.9.0-l14")), "version text l14");

        // Synthetic text region 1: message 0 is empty, message $C7 has [2] v0.9-b62 [3] [END]
        byte[] lengths = new byte[0x80];
        byte[] region = new byte[256];
        int p = 0;
        for (int i = 0; i < RomLibrary.VERSION_MESSAGE; i++) {
            region[p++] = 0x7F;
        }
        region[p++] = 0x75; // LINE2
        String stamp = "v0.9-b62";
        for (int i = 0; i < stamp.length(); i++) {
            char ch = stamp.charAt(i);
            byte enc;
            if (ch >= 'A' && ch <= 'Z') enc = (byte) (ch - 'A');
            else if (ch >= 'a' && ch <= 'z') enc = (byte) (0x1A + ch - 'a');
            else if (ch >= '0' && ch <= '9') enc = (byte) (0x34 + ch - '0');
            else if (ch == '-') enc = 0x40;
            else if (ch == '.') enc = 0x41;
            else enc = 0x59;
            region[p++] = enc;
        }
        region[p++] = 0x76; // LINE3
        region[p++] = 0x7F; // END
        check("v0.9-b62".equals(RomLibrary.decodeVersionLine(lengths, region)), "decoded version line");
    }

    private static void testRollbackAndPrune(File root) throws Exception {
        File roms = new File(root, "roms");
        File states = new File(root, "SaveStates");
        File saves = new File(root, "Saves");
        roms.mkdirs();
        states.mkdirs();
        saves.mkdirs();

        File primary = new File(roms, "Oracle - Main.sfc");
        File prev = new File(roms, "Oracle - Main.sfc.prev");
        File old = new File(roms, "Oracle - Main b58.sfc");
        Files.write(primary.toPath(), new byte[] { 2 });
        Files.write(prev.toPath(), new byte[] { 1 });
        Files.write(old.toPath(), new byte[] { 0 });
        Files.write(new File(roms, "Oracle - Main.build.json").toPath(), "{\"build\":\"b62\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(roms, "Oracle - Main.build.json.prev").toPath(), "{\"build\":\"b61\"}".getBytes(StandardCharsets.UTF_8));

        Files.write(new File(states, "Oracle - Main b58_3.mss").toPath(), new byte[] { 7 });
        Files.write(new File(states, "Oracle - Main b58_3.mss.build").toPath(), "b58\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(saves, "Oracle - Main b58.srm").toPath(), new byte[] { 8 });

        List<RomLibrary.Entry> before = RomLibrary.scan(new File[] { roms });
        check("b62".equals(before.get(0).build) && "b61".equals(before.get(0).previousBuild), "prev build detected");
        check(RomLibrary.rollback(before.get(0)) == null, "rollback succeeds");
        check(Files.readAllBytes(primary.toPath())[0] == 1, "primary swapped to prev bytes");
        check("b61".equals(RomLibrary.buildId(primary)), "primary sidecar swapped to b61");

        List<RomLibrary.Entry> afterRollback = RomLibrary.scan(new File[] { roms });
        RomLibrary.Cleanup cleanup = RomLibrary.removeOlderCopies(afterRollback.get(0), states, saves);
        check(cleanup.removed == 1 && cleanup.statesMoved == 1 && cleanup.saveMoved, "older copy pruned + state/save migrated");
        check(!old.exists(), "legacy ROM deleted");
        check(new File(states, "Oracle - Main_3.mss").isFile(), "state moved to channel base");
        check(new File(states, "Oracle - Main_3.mss.build").isFile(), "state build sidecar moved");
        check(new File(saves, "Oracle - Main.srm").isFile(), "battery save moved to channel base");
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
