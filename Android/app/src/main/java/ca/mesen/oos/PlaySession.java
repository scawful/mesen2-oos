package ca.mesen.oos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Shared jump catalog and in-play feedback file. Matches yaze
 * docs/internal/play_session_contract.md and desktop PlaySession.cs.
 */
public final class PlaySession {
    public static final String FEEDBACK_SCHEMA = "yaze.review_board_feedback.v2";
    public static final String PLAY_SCHEMA = "oos.play_feedback.v1";
    public static final String BOARD_ID = "in-play";
    public static final String SOURCE_ANDROID = "mesen-android";
    public static final String SOURCE_DESKTOP = "mesen-desktop";
    public static final String SOURCE_YAZE = "yaze";

    public static final class Jump {
        public final String catalogId;
        public final String sourceId;
        public final String kind;
        public final String label;
        public final String status;
        public final String statePath;
        public final String checkpointId;
        public final String location;

        Jump(String catalogId, String sourceId, String kind, String label, String status,
             String statePath, String checkpointId, String location) {
            this.catalogId = catalogId;
            this.sourceId = sourceId;
            this.kind = kind;
            this.label = label;
            this.status = status;
            this.statePath = statePath;
            this.checkpointId = checkpointId;
            this.location = location;
        }
    }

    private PlaySession() {}

    public static List<Jump> loadJumps(String statesJson, String checkpointsJson, String warpsJson) {
        List<Jump> jumps = new ArrayList<>();
        addSavestates(statesJson, jumps);
        addCheckpoints(checkpointsJson, jumps);
        addWarps(warpsJson, jumps);
        return jumps;
    }

    public static List<Jump> savestateJumps(String statesJson) {
        List<Jump> jumps = new ArrayList<>();
        addSavestates(statesJson, jumps);
        return jumps;
    }

    public static File resolveStateFile(File libraryRoot, String statePath) {
        if (libraryRoot == null || statePath == null || statePath.isEmpty()) return null;
        String slashed = statePath.replace('\\', '/');
        if (slashed.startsWith("/") || !safeRelative(slashed)) return null;
        return new File(libraryRoot, slashed);
    }

    public static File feedbackFile(File reviewBoardsDir) {
        return new File(new File(reviewBoardsDir, BOARD_ID), "feedback-" + BOARD_ID + ".json");
    }

    public static void appendFeedback(File reviewBoardsDir, String decision, String category,
                                      String comment, String source, Jump jump) throws IOException {
        if (!"comment".equals(decision) && !"needs_changes".equals(decision) && !"looks_good".equals(decision)) {
            throw new IllegalArgumentException("Unknown decision");
        }
        if (!"bug".equals(category) && !"review".equals(category) && !"general".equals(category)
                && !"layout".equals(category)) {
            throw new IllegalArgumentException("Unknown category");
        }
        if (!SOURCE_ANDROID.equals(source) && !SOURCE_DESKTOP.equals(source) && !SOURCE_YAZE.equals(source)) {
            throw new IllegalArgumentException("Unknown feedback source");
        }
        if (comment == null) comment = "";
        if (comment.length() > 4000) throw new IllegalArgumentException("Comment is too long");
        if (!"looks_good".equals(decision) && comment.trim().isEmpty()) {
            throw new IllegalArgumentException("Write a comment first");
        }
        File target = feedbackFile(reviewBoardsDir);
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("Could not create in-play feedback folder");
        }
        Map<String, Object> root;
        List<Object> history;
        if (target.isFile()) {
            String text = readUtf8(target);
            root = MiniJson.object(MiniJson.parse(text));
            Object schema = root.get("schema");
            if (schema instanceof String && !schema.equals("yaze.review_board_feedback.v1")
                    && !schema.equals(FEEDBACK_SCHEMA)) {
                throw new IOException("Existing feedback schema is unknown");
            }
            if (!BOARD_ID.equals(root.get("id"))) {
                throw new IOException("Existing feedback id is not in-play");
            }
            history = MiniJson.array(root.get("history"));
            if (!(root.get("history") instanceof List)) {
                throw new IOException("Existing feedback has no history");
            }
        } else {
            root = new LinkedHashMap<>();
            history = new ArrayList<>();
        }

        Map<String, Object> play = new LinkedHashMap<>();
        play.put("source", source);
        if (jump != null) {
            Map<String, Object> jumpObj = new LinkedHashMap<>();
            jumpObj.put("kind", jump.kind);
            jumpObj.put("id", jump.sourceId);
            jumpObj.put("label", jump.label);
            play.put("jump", jumpObj);
            if ("savestate".equals(jump.kind) && !jump.statePath.isEmpty()) {
                play.put("savestate", jump.statePath);
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        entry.put("at", stamp.format(new Date()));
        entry.put("decision", decision);
        entry.put("category", category);
        entry.put("comment", comment);
        entry.put("answers", new LinkedHashMap<String, Object>());
        entry.put("play", play);
        history.add(entry);

        String status = "commented";
        for (int i = history.size() - 1; i >= 0; i--) {
            Object prior = MiniJson.object(history.get(i)).get("decision");
            if ("looks_good".equals(prior) || "needs_changes".equals(prior)) {
                status = String.valueOf(prior);
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", FEEDBACK_SCHEMA);
        out.put("play_schema", PLAY_SCHEMA);
        out.put("id", BOARD_ID);
        out.put("status", status);
        Object answers = root.get("answers");
        out.put("answers", answers instanceof Map ? answers : new LinkedHashMap<String, Object>());
        out.put("history", history);

        if (Files.isSymbolicLink(target.toPath())) {
            throw new IOException("Feedback file is a symlink");
        }
        // Unique temp name (matches desktop Guid) so concurrent bug snapshots
        // cannot race on a fixed *.tmp path.
        File temp = new File(folder, target.getName() + ".tmp-" + UUID.randomUUID());
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write((toJson(out) + "\n").getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        try {
            Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replaceFailed) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("Could not replace in-play feedback", replaceFailed);
            }
        }
    }

    private static void addSavestates(String json, List<Jump> jumps) {
        if (json == null || json.isEmpty()) return;
        Map<String, Object> root = MiniJson.object(MiniJson.parse(json));
        String libraryRoot = text(root.get("library_root"));
        if (!safeRelative(libraryRoot)) libraryRoot = "Roms/SaveStates/library";
        for (Object item : MiniJson.array(root.get("entries"))) {
            Map<String, Object> row = MiniJson.object(item);
            String sourceId = text(row.get("id"));
            if (sourceId.isEmpty()) continue;
            Map<String, Object> meta = MiniJson.object(row.get("meta"));
            Map<String, Object> metadata = MiniJson.object(row.get("metadata"));
            String label = text(row.get("label"));
            if (label.isEmpty()) label = text(meta.get("label"));
            if (label.isEmpty()) label = text(row.get("description"));
            if (label.isEmpty()) label = sourceId;
            String location = text(meta.get("location"));
            if (location.isEmpty()) location = text(metadata.get("location"));
            String raw = text(row.get("state_path")).replace('\\', '/');
            if (raw.isEmpty()) {
                raw = text(row.get("path")).replace('\\', '/');
                if (!raw.isEmpty() && !raw.contains("/") && !(raw.length() > 1 && raw.charAt(1) == ':')) {
                    raw = libraryRoot.replaceAll("/+$", "") + "/" + raw;
                }
            }
            boolean absolute = !raw.isEmpty() && (raw.charAt(0) == '/' || (raw.length() > 2 && raw.charAt(1) == ':'));
            if (!raw.isEmpty() && !absolute && !safeRelative(raw)) raw = "";
            String status = text(row.get("status"));
            jumps.add(new Jump("savestate:" + sourceId, sourceId, "savestate", label,
                    status.isEmpty() ? "draft" : status, raw, "", location));
        }
    }

    private static void addCheckpoints(String json, List<Jump> jumps) {
        if (json == null || json.isEmpty()) return;
        Map<String, Object> root = MiniJson.object(MiniJson.parse(json));
        for (Object item : MiniJson.array(root.get("checkpoints"))) {
            Map<String, Object> row = MiniJson.object(item);
            String sourceId = text(row.get("id"));
            if (sourceId.isEmpty()) continue;
            Map<String, Object> location = MiniJson.object(row.get("location"));
            String where = "";
            String entrance = text(location.get("entrance"));
            String area = text(location.get("area"));
            if (!entrance.isEmpty()) where = "entrance " + entrance;
            else if (!area.isEmpty()) where = "area " + area;
            String label = text(row.get("label"));
            jumps.add(new Jump("checkpoint:" + sourceId, sourceId, "checkpoint",
                    label.isEmpty() ? sourceId : label, "story", "", sourceId, where));
        }
    }

    private static void addWarps(String json, List<Jump> jumps) {
        if (json == null || json.isEmpty()) return;
        Map<String, Object> root = MiniJson.object(MiniJson.parse(json));
        for (Object item : MiniJson.array(root.get("entrances"))) {
            addWarp(MiniJson.object(item), "entrance", "id", jumps);
        }
        for (Object item : MiniJson.array(root.get("areas"))) {
            addWarp(MiniJson.object(item), "area", "area", jumps);
        }
    }

    private static void addWarp(Map<String, Object> row, String group, String idKey, List<Jump> jumps) {
        String sourceId = text(row.get(idKey));
        if (sourceId.isEmpty()) return;
        String label = text(row.get("label"));
        if (label.isEmpty()) label = text(row.get("name"));
        if (label.isEmpty()) label = sourceId;
        String location = text(row.get("landing"));
        if (location.isEmpty()) location = text(row.get("area_name"));
        jumps.add(new Jump("warp-" + group + ":" + sourceId, sourceId, "warp-" + group, label, "warp", "", "", location));
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Map || value instanceof List) return "";
        return String.valueOf(value);
    }

    private static boolean safeRelative(String text) {
        if (text == null || text.isEmpty() || text.length() > 512 || text.indexOf('\\') >= 0) return false;
        if (text.charAt(0) == '/' || (text.length() > 1 && text.charAt(1) == ':')) return false;
        for (String part : text.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(entry.getKey())).append(':').append(toJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) out.append(',');
                first = false;
                out.append(toJson(item));
            }
            return out.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                    break;
            }
        }
        return out.append('"').toString();
    }
}
