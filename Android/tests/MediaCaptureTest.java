package ca.mesen.oos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure-Java checks for screenshot/video paths, bug snapshot JSON, and recent sorting. */
public final class MediaCaptureTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testSlugifyAndMediaNaming();
        testBugSnapshotBundleAndSprites();
        testListRecentOrderingAndLimit();
        testFormatDuration();
        System.out.println("MediaCaptureTest OK (" + checks + " checks)");
    }

    static void testSlugifyAndMediaNaming() throws Exception {
        check("oracle".equals(MediaCapture.slugify(null)), "null slug");
        check("oracle".equals(MediaCapture.slugify("   ")), "blank slug");
        check("Oracle_-_Main_b58".equals(MediaCapture.slugify("/storage/emulated/0/Oracle - Main b58.sfc")),
                "rom basename slug: " + MediaCapture.slugify("/storage/emulated/0/Oracle - Main b58.sfc"));

        File temp = Files.createTempDirectory("oos-mc-name").toFile();
        File first = MediaCapture.nextMediaFile(temp, "Oracle - Main.sfc", "png", 1700000000000L);
        check(first.getName().endsWith(".png"), "png extension");
        Files.write(first.toPath(), new byte[]{1, 2, 3});
        File second = MediaCapture.nextMediaFile(temp, "Oracle - Main.sfc", ".PNG", 1700000000000L);
        check(second.getName().endsWith("_1.png"), "collision adds _1: " + second.getName());
    }

    static void testBugSnapshotBundleAndSprites() throws Exception {
        File temp = Files.createTempDirectory("oos-mc-bug").toFile();
        File reports = MediaCapture.reportsDir(temp);

        Map<Integer, Integer> wram = new HashMap<>();
        wram.put(0x7E00A0, 0x24);
        wram.put(0x7E00A1, 0x01); // room $0124
        wram.put(0x7E005C, 0x20);
        wram.put(Inventory.OOSPROG, 0x07);
        wram.put(Inventory.GAME_STATE, 0x02);
        wram.put(Inventory.OOSPROG2, 0x11);
        // Active sprite slot 3
        wram.put(0x7E0DD0 + 3, 0x09);
        wram.put(0x7E0E20 + 3, 0x41);
        wram.put(0x7E0D10 + 3, 0x80);
        wram.put(0x7E0D30 + 3, 0x02);
        wram.put(0x7E0D00 + 3, 0x40);
        wram.put(0x7E0D20 + 3, 0x01);
        wram.put(0x7E0F20 + 3, 0x01);
        wram.put(0x7E0E50 + 3, 0x06);

        MediaCapture.SnapshotHooks hooks = new MediaCapture.SnapshotHooks() {
            @Override
            public boolean saveScreenshot(File pngFile) {
                try {
                    Files.write(pngFile.toPath(), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public boolean saveState(File stateFile) {
                try {
                    Files.write(stateFile.toPath(), new byte[]{0x4D, 0x53, 0x53});
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public String statusJson() {
                return "{\"rom\":\"Oracle - Main.sfc\",\"sha1\":\"abc123\",\"frames\":9001,"
                        + "\"mode\":7,\"sub\":0,\"indoor\":1,\"area\":0,\"room\":36,"
                        + "\"linkX\":512,\"linkY\":384,\"linkLayer\":0,\"linkDir\":2,"
                        + "\"linkState\":0,\"linkForm\":0,\"health\":24,\"maxHealth\":40,"
                        + "\"magic\":64,\"rupees\":300,\"paused\":true,\"fastForward\":false,\"recording\":true}";
            }

            @Override
            public int readByte(int addr) {
                return wram.getOrDefault(addr, 0);
            }

            @Override
            public String romPath() {
                return "/data/user/0/ca.mesen.oos/files/Roms/Oracle - Main.sfc";
            }
        };

        MediaCapture.BugSnapshotResult res = MediaCapture.writeBugSnapshot(
                reports, "Oracle - Main.sfc", "Collision / Layer", "Link clipped \"wall\"\nline2", hooks, 1700000000000L);
        check(res.ok, "snapshot ok: " + res.message);
        check(res.screenshotFile != null && res.screenshotFile.isFile(), "screenshot.png created");
        check(res.stateFile != null && res.stateFile.isFile(), "state.mss created");
        check(res.reportFile != null && res.reportFile.isFile(), "report.json created");

        String raw = new String(Files.readAllBytes(res.reportFile.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> root = MiniJson.object(MiniJson.parse(raw));
        check(MediaCapture.SCHEMA_VERSION.equals(root.get("schema")), "schema matches");
        check("Collision / Layer".equals(root.get("category")), "category matches");
        check("Link clipped \"wall\"\nline2".equals(root.get("note")), "escaped note round-trips");

        Map<String, Object> telemetry = MiniJson.object(root.get("telemetry"));
        check(((Number) telemetry.get("room")).intValue() == 0x0124, "16-bit room preserved");
        check("$0124".equals(telemetry.get("roomHex")), "roomHex formatted");
        check(((Number) telemetry.get("linkX")).intValue() == 512, "linkX preserved");
        check(((Number) telemetry.get("health")).intValue() == 24, "health preserved");

        List<Object> sprites = MiniJson.array(root.get("activeSprites"));
        check(sprites.size() == 1, "one active sprite serialized");
        Map<String, Object> spr = MiniJson.object(sprites.get(0));
        check(((Number) spr.get("slot")).intValue() == 3, "sprite slot 3");
        check("$41".equals(spr.get("idHex")), "sprite idHex");
        check(((Number) spr.get("x")).intValue() == 0x0280, "sprite 16-bit X");
        check(((Number) spr.get("y")).intValue() == 0x0140, "sprite 16-bit Y");
    }

    static void testListRecentOrderingAndLimit() throws Exception {
        File root = Files.createTempDirectory("oos-mc-recent").toFile();
        File ssDir = MediaCapture.screenshotsDir(root);
        File vidDir = MediaCapture.videosDir(root);
        File bugDir = new File(MediaCapture.reportsDir(root), "bug_oracle_20261118_120000");
        bugDir.mkdirs();

        File p1 = new File(ssDir, "old.png");
        File v1 = new File(vidDir, "mid.mp4");
        File r1 = new File(bugDir, "report.json");
        Files.write(p1.toPath(), new byte[]{1, 2, 3, 4});
        Files.write(v1.toPath(), new byte[]{1, 2, 3, 4, 5, 6});
        Files.write(r1.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        p1.setLastModified(1000L);
        v1.setLastModified(2000L);
        r1.setLastModified(3000L);

        List<MediaCapture.CaptureItem> recent = MediaCapture.listRecent(root, 10);
        check(recent.size() == 3, "all 3 capture types listed");
        check(recent.get(0).kind == MediaCapture.Kind.BUG_SNAPSHOT, "newest bug snapshot first");
        check(recent.get(1).kind == MediaCapture.Kind.VIDEO, "second video");
        check(recent.get(2).kind == MediaCapture.Kind.SCREENSHOT, "oldest screenshot");
        check(MediaCapture.listRecent(root, 2).size() == 2, "limit respected");
    }

    static void testFormatDuration() {
        check("00:00".equals(MediaCapture.formatDuration(-5)), "negative clamps to 00:00");
        check("01:05".equals(MediaCapture.formatDuration(65)), "65s -> 01:05");
        check("59:59".equals(MediaCapture.formatDuration(3599)), "3599s -> 59:59");
    }

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
