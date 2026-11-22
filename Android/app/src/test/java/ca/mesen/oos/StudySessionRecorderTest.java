package ca.mesen.oos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StudySessionRecorderTest {
    private static final class FakeHooks implements StudySessionRecorder.Hooks {
        long nowMs = 1_760_000_000_000L;
        final List<File> startedSegments = new ArrayList<>();
        int stopCount = 0;
        final Map<Integer, Integer> wram = new HashMap<>();
        double fps = 60.0;

        FakeHooks() {
            wram.put(0x7E0010, 0x09); // Overworld
            wram.put(0x7E0011, 0x00);
            wram.put(0x7E001B, 0x00); // Outdoors
            wram.put(0x7E008A, 0x33); // Loom Beach
            wram.put(0x7E00A0, 0x04);
            wram.put(0x7E00A1, 0x01);
            wram.put(0x7EF3CA, 0x00);
            wram.put(0x7E0022, 0x78);
            wram.put(0x7E0023, 0x07);
            wram.put(0x7E0020, 0x80);
            wram.put(0x7E0021, 0x0D);
            wram.put(Inventory.HEALTH_CURRENT, 0x28);
            wram.put(0x7E02B2, 0x00);
            wram.put(0x7E005D, 0x00);
        }

        @Override
        public boolean startVideoSegment(File mp4File) {
            try {
                Files.write(mp4File.toPath(), "fake-mp4".getBytes(StandardCharsets.UTF_8));
                startedSegments.add(mp4File);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean stopVideoSegment() {
            stopCount++;
            return true;
        }

        @Override
        public boolean saveState(File stateFile) {
            try {
                Files.write(stateFile.toPath(), "fake-mss".getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public boolean saveScreenshot(File pngFile) {
            try {
                Files.write(pngFile.toPath(), "fake-png".getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public String statusJson(boolean detailed) {
            return String.format("{\"sha1\":\"deadbeef\",\"observedFps\":%.1f}", fps);
        }

        @Override
        public int readByte(int addr) {
            Integer v = wram.get(addr);
            return v != null ? v : 0;
        }

        @Override
        public String romPath() {
            return "/tmp/Oracle - Main.sfc";
        }

        @Override
        public String romDisplayName() {
            return "Oracle - Main.sfc";
        }

        @Override
        public long nowMs() {
            return nowMs;
        }
    }

    @Test
    public void rotatesSegmentsLogsTransitionsRateLimitsAnomaliesAndPreservesBookmarks() throws Exception {
        File tmp = Files.createTempDirectory("oos-study-test").toFile();
        File sessionsRoot = StudySessionRecorder.sessionsDir(tmp);
        FakeHooks hooks = new FakeHooks();

        // 10s segment for test, 2s periodic anchor, 1s transition anchor cooldown, 15s anomaly cooldown, cap=3 anchors
        StudySessionRecorder rec = new StudySessionRecorder(
                sessionsRoot, hooks, 10L, 2_000L, 1_000L, 15_000L, 500L, 3);

        String startMsg = rec.start();
        assertTrue(startMsg.contains("Study Session started"));
        assertTrue(rec.isActive());
        assertEquals(1, hooks.startedSegments.size());
        File sessionDir = rec.status().sessionDir;
        assertTrue(new File(sessionDir, "video_seg_001.mp4").isFile());

        // Normal gameplay sample + manual bookmark
        hooks.nowMs += 250L;
        rec.tick(false, 60.0);
        String bmMsg = rec.addBookmark("korok_cove", "looks great");
        assertTrue(bmMsg.contains("korok_cove"));
        assertEquals(1, rec.status().bookmarkCount);

        // Transition into D1 Room $004A after transition cooldown -> logs transition & captures rolling anchor
        hooks.nowMs += 1_500L;
        hooks.wram.put(0x7E0010, 0x07);
        hooks.wram.put(0x7E001B, 0x01);
        hooks.wram.put(0x7E00A0, 0x4A);
        hooks.wram.put(0x7E00A1, 0x00);
        rec.tick(false, 60.0);

        // Trigger more rolling anchors via periodic interval to verify FIFO cap = 3 while bookmark stays untouched
        for (int i = 0; i < 3; i++) {
            hooks.nowMs += 2_100L;
            rec.tick(false, 60.0);
        }
        assertEquals(1, rec.status().segmentIndex);
        assertEquals(3, rec.status().anchorCount);
        File[] anchorFiles = new File(sessionDir, "anchors").listFiles((d, name) -> name.startsWith("anchor_"));
        assertEquals(3, anchorFiles != null ? anchorFiles.length : 0);
        assertTrue(new File(sessionDir, "bookmarks/bm_001_korok_cove.mss").isFile());
        assertTrue(new File(sessionDir, "bookmarks/bm_001_korok_cove.png").isFile());

        // Cross 10s boundary -> segment 2 rotates automatically
        hooks.nowMs += 2_500L;
        rec.tick(false, 60.0);
        assertEquals(2, rec.status().segmentIndex);
        assertEquals(2, hooks.startedSegments.size());
        assertTrue(new File(sessionDir, "video_seg_002.mp4").isFile());

        // Low FPS for 3 ticks -> anomaly #1, 4th tick within 15s cooldown -> rate limited
        hooks.fps = 32.0;
        for (int i = 0; i < 4; i++) {
            hooks.nowMs += 300L;
            rec.tick(false, 32.0);
        }
        assertEquals(1, rec.status().anomalyCount);
        assertTrue(new File(sessionDir, "anchors/anom_001.mss").isFile());

        // Advance past 15s cooldown and force stuck transition > 5s -> anomaly #2
        hooks.fps = 60.0;
        hooks.nowMs += 16_000L;
        hooks.wram.put(0x7E0010, 0x08);
        hooks.wram.put(0x7E0011, 0x02);
        rec.tick(false, 60.0);
        hooks.nowMs += 5_500L;
        rec.tick(false, 60.0);
        assertEquals(2, rec.status().anomalyCount);

        String stopMsg = rec.stop("test_done");
        assertTrue(stopMsg.contains("Saved Study Session"));
        assertFalse(rec.isActive());

        String telemetry = new String(Files.readAllBytes(new File(sessionDir, "telemetry.ndjson").toPath()), StandardCharsets.UTF_8);
        assertTrue(telemetry.contains("\"event\":\"transition\""));
        assertTrue(telemetry.contains("\"event\":\"segment_rotate\""));
        assertTrue(telemetry.contains("\"event\":\"anomaly\""));
        assertTrue(telemetry.contains("\"event\":\"bookmark\""));
        assertTrue(telemetry.contains("\"event\":\"session_end\""));

        String sessionJson = new String(Files.readAllBytes(new File(sessionDir, "session.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(sessionJson.contains("\"cleanShutdown\": true"));
        assertTrue(sessionJson.contains("\"bookmarks\": 1"));
        assertTrue(sessionJson.contains("\"anomalies\": 2"));
        assertTrue(sessionJson.contains("video_seg_002.mp4"));

        String bookmarksJson = new String(Files.readAllBytes(new File(sessionDir, "bookmarks.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(bookmarksJson.contains("bm_001_korok_cove.mss"));
    }
}
