package ca.mesen.oos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java capture storage, naming, recent-list indexing, and structured
 * bug-snapshot bundling (`screenshot.png` + `state.mss` + `report.json`).
 */
public final class MediaCapture {
    public enum Kind { SCREENSHOT, VIDEO, BUG_SNAPSHOT }

    public static final class CaptureItem {
        public final Kind kind;
        public final File file;
        public final File previewImage;
        public final File stateFile;
        public final File reportFile;
        public final String name;
        public final String detail;
        public final long modifiedMs;
        public final long bytes;

        public CaptureItem(Kind kind, File file, File previewImage, File stateFile, File reportFile,
                           String name, String detail, long modifiedMs, long bytes) {
            this.kind = kind;
            this.file = file;
            this.previewImage = previewImage;
            this.stateFile = stateFile;
            this.reportFile = reportFile;
            this.name = name;
            this.detail = detail;
            this.modifiedMs = modifiedMs;
            this.bytes = bytes;
        }
    }

    public interface SnapshotHooks {
        boolean saveScreenshot(File pngFile);
        boolean saveState(File stateFile);
        String statusJson();
        int readByte(int addr);
        String romPath();
    }

    public static final class BugSnapshotResult {
        public final boolean ok;
        public final File bundleDir;
        public final File screenshotFile;
        public final File stateFile;
        public final File reportFile;
        public final String message;

        private BugSnapshotResult(boolean ok, File bundleDir, File screenshotFile,
                                  File stateFile, File reportFile, String message) {
            this.ok = ok;
            this.bundleDir = bundleDir;
            this.screenshotFile = screenshotFile;
            this.stateFile = stateFile;
            this.reportFile = reportFile;
            this.message = message;
        }
    }

    public static final String SCHEMA_VERSION = "oos-bug-snapshot-v1";
    public static final String[] BUG_CATEGORIES = {
            "Softlock / Crash",
            "Warp / Transition",
            "Collision / Layer",
            "Visual / Palette",
            "Dialogue / Story",
            "Enemy / Boss"
    };

    private static final int SPRITE_SLOTS = 16;
    private static final int SPR_YL = 0x7E0D00;
    private static final int SPR_XL = 0x7E0D10;
    private static final int SPR_YH = 0x7E0D20;
    private static final int SPR_XH = 0x7E0D30;
    private static final int SPR_STATE = 0x7E0DD0;
    private static final int SPR_ID = 0x7E0E20;
    private static final int SPR_HP = 0x7E0E50;
    private static final int SPR_FLOOR = 0x7E0F20;

    private MediaCapture() {}

    public static File capturesRoot(File externalFilesDir, File internalFilesDir) {
        File base = externalFilesDir != null ? externalFilesDir : internalFilesDir;
        if (base == null) return null;
        File dir = new File(base, "Captures");
        dir.mkdirs();
        return dir;
    }

    public static File screenshotsDir(File capturesRoot) {
        if (capturesRoot == null) return null;
        File dir = new File(capturesRoot, "Screenshots");
        dir.mkdirs();
        return dir;
    }

    public static File videosDir(File capturesRoot) {
        if (capturesRoot == null) return null;
        File dir = new File(capturesRoot, "Videos");
        dir.mkdirs();
        return dir;
    }

    public static File reportsDir(File capturesRoot) {
        if (capturesRoot == null) return null;
        File dir = new File(capturesRoot, "BugReports");
        dir.mkdirs();
        return dir;
    }

    public static String slugify(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return "oracle";
        String base = new File(rawName.trim()).getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        boolean prevUnderscore = false;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-';
            if (keep) {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore && sb.length() > 0) {
                sb.append('_');
                prevUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty()) return "oracle";
        if (out.length() > 40) out = out.substring(0, 40);
        return out;
    }

    private static String timestamp(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return fmt.format(new Date(epochMs));
    }

    private static String isoTimestamp(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs));
    }

    public static File nextMediaFile(File dir, String romName, String ext, long epochMs) {
        if (dir == null) return null;
        dir.mkdirs();
        String cleanExt = ext == null ? "bin" : ext.trim().toLowerCase(Locale.US);
        if (cleanExt.startsWith(".")) cleanExt = cleanExt.substring(1);
        if (cleanExt.isEmpty()) cleanExt = "bin";
        String stem = slugify(romName) + "_" + timestamp(epochMs);
        File candidate = new File(dir, stem + "." + cleanExt);
        if (!candidate.exists()) return candidate;
        for (int i = 1; i <= 99; i++) {
            File alt = new File(dir, stem + "_" + i + "." + cleanExt);
            if (!alt.exists()) return alt;
        }
        return candidate;
    }

    private static File nextBundleDir(File reportsDir, String romName, long epochMs) {
        reportsDir.mkdirs();
        String stem = "bug_" + slugify(romName) + "_" + timestamp(epochMs);
        File candidate = new File(reportsDir, stem);
        if (!candidate.exists()) return candidate;
        for (int i = 1; i <= 99; i++) {
            File alt = new File(reportsDir, stem + "_" + i);
            if (!alt.exists()) return alt;
        }
        return candidate;
    }

    public static BugSnapshotResult writeBugSnapshot(File reportsDir, String romName,
                                                     String category, String note,
                                                     SnapshotHooks hooks, long epochMs) {
        if (reportsDir == null || hooks == null) {
            return new BugSnapshotResult(false, null, null, null, null, "Capture hooks unavailable");
        }
        File bundleDir = nextBundleDir(reportsDir, romName, epochMs);
        if (!bundleDir.mkdirs() && !bundleDir.isDirectory()) {
            return new BugSnapshotResult(false, null, null, null, null,
                    "Failed to create bug bundle folder: " + bundleDir.getAbsolutePath());
        }

        File pngFile = new File(bundleDir, "screenshot.png");
        File stateFile = new File(bundleDir, "state.mss");
        File reportFile = new File(bundleDir, "report.json");

        boolean pngOk = hooks.saveScreenshot(pngFile) && pngFile.isFile() && pngFile.length() > 0;
        boolean stateOk = hooks.saveState(stateFile) && stateFile.isFile() && stateFile.length() > 0;

        String rawStatus = hooks.statusJson();
        Map<String, Object> status = parseObjectSafe(rawStatus);

        int mode = intField(status, "mode", hooks.readByte(0x7E0010));
        int sub = intField(status, "sub", hooks.readByte(0x7E0011));
        int indoor = intField(status, "indoor", hooks.readByte(0x7E001B));
        int area = intField(status, "area", hooks.readByte(0x7E008A));
        int roomLo = hooks.readByte(0x7E00A0);
        int roomHi = hooks.readByte(0x7E00A1);
        int room = (roomLo >= 0 && roomHi >= 0)
                ? (roomLo | (roomHi << 8))
                : intField(status, "room", -1);
        int linkX = intField(status, "linkX", readWord(hooks, 0x7E0022));
        int linkY = intField(status, "linkY", readWord(hooks, 0x7E0020));
        int linkLayer = intField(status, "linkLayer", hooks.readByte(0x7E00EE));
        int linkDir = intField(status, "linkDir", hooks.readByte(0x7E002F));
        int linkState = intField(status, "linkState", hooks.readByte(0x7E005D));
        int linkForm = intField(status, "linkForm", hooks.readByte(0x7E02B2));
        int health = intField(status, "health", hooks.readByte(Inventory.HEALTH_CURRENT));
        int maxHealth = intField(status, "maxHealth", hooks.readByte(Inventory.HEALTH_MAX));
        int magic = intField(status, "magic", hooks.readByte(Inventory.MAGIC));
        int rupees = intField(status, "rupees", readWord(hooks, Inventory.RUPEES));
        int fallState = hooks.readByte(0x7E005C);
        int oosProg = hooks.readByte(Inventory.OOSPROG);
        int gameState = hooks.readByte(Inventory.GAME_STATE);
        int oosProg2 = hooks.readByte(Inventory.OOSPROG2);
        long frames = longField(status, "frames", -1L);
        String sha1 = MiniJson.string(status.get("sha1"), "");
        boolean paused = boolField(status, "paused", false);
        boolean fastForward = boolField(status, "fastForward", false);
        boolean recording = boolField(status, "recording", false);

        List<String> spritesJson = new ArrayList<>();
        for (int i = 0; i < SPRITE_SLOTS; i++) {
            int st = hooks.readByte(SPR_STATE + i);
            if (st <= 0) continue;
            int id = hooks.readByte(SPR_ID + i);
            int x = combineByteWord(hooks.readByte(SPR_XL + i), hooks.readByte(SPR_XH + i));
            int y = combineByteWord(hooks.readByte(SPR_YL + i), hooks.readByte(SPR_YH + i));
            int floor = hooks.readByte(SPR_FLOOR + i);
            int hp = hooks.readByte(SPR_HP + i);
            spritesJson.add(String.format(Locale.US,
                    "    {\"slot\": %d, \"state\": %d, \"stateHex\": \"%s\", \"id\": %d, \"idHex\": \"%s\", \"x\": %d, \"y\": %d, \"layer\": %d, \"hp\": %d}",
                    i, st, hex2(st), id, hex2(id), x, y, floor, hp));
        }

        String romPath = hooks.romPath();
        String romFile = (romPath != null && !romPath.isEmpty())
                ? new File(romPath).getName()
                : MiniJson.string(status.get("rom"), romName != null ? romName : "");

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema\": \"").append(SCHEMA_VERSION).append("\",\n");
        json.append("  \"timestampUtc\": \"").append(isoTimestamp(epochMs)).append("\",\n");
        json.append("  \"timestampMs\": ").append(epochMs).append(",\n");
        json.append("  \"category\": ").append(jsonQuote(category != null ? category : BUG_CATEGORIES[0])).append(",\n");
        json.append("  \"note\": ").append(jsonQuote(note != null ? note : "")).append(",\n");
        json.append("  \"rom\": {\n");
        json.append("    \"name\": ").append(jsonQuote(romFile)).append(",\n");
        json.append("    \"path\": ").append(jsonQuote(romPath != null ? romPath : "")).append(",\n");
        json.append("    \"sha1\": ").append(jsonQuote(sha1)).append("\n");
        json.append("  },\n");
        json.append("  \"artifacts\": {\n");
        json.append("    \"screenshot\": ").append(pngOk ? "\"screenshot.png\"" : "null").append(",\n");
        json.append("    \"saveState\": ").append(stateOk ? "\"state.mss\"" : "null").append(",\n");
        json.append("    \"screenshotOk\": ").append(pngOk).append(",\n");
        json.append("    \"saveStateOk\": ").append(stateOk).append("\n");
        json.append("  },\n");
        json.append("  \"emulator\": {\n");
        json.append("    \"frames\": ").append(frames).append(",\n");
        json.append("    \"paused\": ").append(paused).append(",\n");
        json.append("    \"fastForward\": ").append(fastForward).append(",\n");
        json.append("    \"recording\": ").append(recording).append("\n");
        json.append("  },\n");
        json.append("  \"telemetry\": {\n");
        json.append("    \"mode\": ").append(mode).append(", \"modeHex\": ").append(jsonQuote(hex2(mode))).append(",\n");
        json.append("    \"sub\": ").append(sub).append(", \"subHex\": ").append(jsonQuote(hex2(sub))).append(",\n");
        json.append("    \"indoor\": ").append(indoor).append(",\n");
        json.append("    \"area\": ").append(area).append(", \"areaHex\": ").append(jsonQuote(hex2(area))).append(",\n");
        json.append("    \"room\": ").append(room).append(", \"roomHex\": ").append(jsonQuote(hex4(room))).append(",\n");
        json.append("    \"linkX\": ").append(linkX).append(",\n");
        json.append("    \"linkY\": ").append(linkY).append(",\n");
        json.append("    \"linkLayer\": ").append(linkLayer).append(",\n");
        json.append("    \"linkDir\": ").append(linkDir).append(",\n");
        json.append("    \"linkState\": ").append(linkState).append(", \"fallState\": ").append(fallState).append(",\n");
        json.append("    \"linkForm\": ").append(linkForm).append(",\n");
        json.append("    \"health\": ").append(health).append(", \"maxHealth\": ").append(maxHealth).append(",\n");
        json.append("    \"magic\": ").append(magic).append(", \"rupees\": ").append(rupees).append(",\n");
        json.append("    \"gameState\": ").append(gameState).append(", \"oosProg\": ").append(oosProg).append(", \"oosProg2\": ").append(oosProg2).append("\n");
        json.append("  },\n");
        json.append("  \"activeSprites\": [\n");
        for (int i = 0; i < spritesJson.size(); i++) {
            json.append(spritesJson.get(i));
            if (i + 1 < spritesJson.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");

        if (!pngOk && !stateOk && mode < 0 && status.isEmpty()) {
            return new BugSnapshotResult(false, bundleDir, null, null, null,
                    "No emulator state or capture available to snapshot");
        }

        try {
            writeUtf8(reportFile, json.toString());
        } catch (IOException e) {
            return new BugSnapshotResult(false, bundleDir, pngOk ? pngFile : null,
                    stateOk ? stateFile : null, null, "Failed to write report.json: " + e.getMessage());
        }

        String summary = String.format(Locale.US, "Saved %s (png=%s state=%s)",
                bundleDir.getName(), pngOk ? "ok" : "no", stateOk ? "ok" : "no");
        return new BugSnapshotResult(true, bundleDir, pngOk ? pngFile : null,
                stateOk ? stateFile : null, reportFile, summary);
    }

    public static List<CaptureItem> listRecent(File capturesRoot, int limit) {
        if (capturesRoot == null || !capturesRoot.isDirectory() || limit <= 0) {
            return Collections.emptyList();
        }
        List<CaptureItem> items = new ArrayList<>();

        File ssDir = new File(capturesRoot, "Screenshots");
        File[] ssFiles = ssDir.listFiles();
        if (ssFiles != null) {
            for (File f : ssFiles) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".png")) {
                    items.add(new CaptureItem(Kind.SCREENSHOT, f, f, null, null, f.getName(),
                            formatBytes(f.length()), f.lastModified(), f.length()));
                }
            }
        }

        File vidDir = new File(capturesRoot, "Videos");
        File[] vidFiles = vidDir.listFiles();
        if (vidFiles != null) {
            for (File f : vidFiles) {
                String lower = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (lower.endsWith(".mp4") || lower.endsWith(".avi"))) {
                    items.add(new CaptureItem(Kind.VIDEO, f, null, null, null, f.getName(),
                            formatBytes(f.length()), f.lastModified(), f.length()));
                }
            }
        }

        File bugDir = new File(capturesRoot, "BugReports");
        File[] bugDirs = bugDir.listFiles();
        if (bugDirs != null) {
            for (File dir : bugDirs) {
                if (!dir.isDirectory()) continue;
                File report = new File(dir, "report.json");
                File png = new File(dir, "screenshot.png");
                File state = new File(dir, "state.mss");
                if (!report.isFile() && !png.isFile() && !state.isFile()) continue;
                long mod = report.isFile() ? report.lastModified() : dir.lastModified();
                long totalBytes = (report.isFile() ? report.length() : 0)
                        + (png.isFile() ? png.length() : 0)
                        + (state.isFile() ? state.length() : 0);
                String detail = state.isFile()
                        ? (png.isFile() ? "PNG + .mss + JSON" : ".mss + JSON")
                        : (report.isFile() ? "JSON" : "PNG");
                items.add(new CaptureItem(Kind.BUG_SNAPSHOT, dir,
                        png.isFile() ? png : null,
                        state.isFile() ? state : null,
                        report.isFile() ? report : null,
                        dir.getName(), detail, mod, totalBytes));
            }
        }

        items.sort(new Comparator<CaptureItem>() {
            @Override
            public int compare(CaptureItem a, CaptureItem b) {
                int c = Long.compare(b.modifiedMs, a.modifiedMs);
                if (c != 0) return c;
                return b.name.compareTo(a.name);
            }
        });
        if (items.size() > limit) {
            return new ArrayList<>(items.subList(0, limit));
        }
        return items;
    }

    public static String formatDuration(long seconds) {
        long s = Math.max(0L, seconds);
        long mins = s / 60L;
        long secs = s % 60L;
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        long kb = bytes / 1024L;
        if (kb < 1024L) return kb + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static Map<String, Object> parseObjectSafe(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try {
            return MiniJson.object(MiniJson.parse(json));
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    private static int intField(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return fallback;
    }

    private static long longField(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        return fallback;
    }

    private static boolean boolField(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return fallback;
    }

    private static int readWord(SnapshotHooks hooks, int addr) {
        int lo = hooks.readByte(addr);
        int hi = hooks.readByte(addr + 1);
        return (lo >= 0 && hi >= 0) ? (lo | (hi << 8)) : -1;
    }

    private static int combineByteWord(int lo, int hi) {
        return (lo >= 0 && hi >= 0) ? (lo | (hi << 8)) : -1;
    }

    private static String hex2(int value) {
        return value < 0 ? "null" : String.format(Locale.US, "$%02X", value & 0xFF);
    }

    private static String hex4(int value) {
        return value < 0 ? "null" : String.format(Locale.US, "$%04X", value & 0xFFFF);
    }

    private static String jsonQuote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 4);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }

    private static void writeUtf8(File target, String content) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                tmp.delete();
            }
        }
    }
}
