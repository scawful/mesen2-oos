package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Host checks for the shared jump catalog and in-play feedback file. */
public final class PlaySessionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String states = "{\"library_root\":\"Roms/SaveStates/library\",\"entries\":["
                + "{\"id\":\"pre_d6\",\"state_path\":\"Roms/SaveStates/library/pre_d6.mss\",\"meta\":{\"label\":\"D6 gate\"}},"
                + "{\"id\":\"named\",\"path\":\"named.mss\",\"label\":\"Named\"},"
                + "{\"id\":\"escape\",\"path\":\"../secret.mss\",\"label\":\"nope\"}]}";
        String checkpoints = "{\"checkpoints\":[{\"id\":\"intro-bed\",\"label\":\"House\",\"location\":{\"entrance\":\"0x01\"}}],"
                + "\"todo\":[{\"id\":\"later\"}]}";
        String warps = "{\"entrances\":[{\"id\":\"0x01\",\"name\":\"Link's House\"}],"
                + "\"areas\":[{\"area\":\"0x33\",\"name\":\"Beach\",\"landing\":\"outside\"}]}";
        List<PlaySession.Jump> jumps = PlaySession.loadJumps(states, checkpoints, warps);
        PlaySession.Jump gate = find(jumps, "savestate:pre_d6");
        check(gate != null && "D6 gate".equals(gate.label), "savestate label");
        check(gate != null && "Roms/SaveStates/library/pre_d6.mss".equals(gate.statePath), "state path");
        PlaySession.Jump named = find(jumps, "savestate:named");
        check(named != null && "Roms/SaveStates/library/named.mss".equals(named.statePath), "bare filename");
        PlaySession.Jump escaped = find(jumps, "savestate:escape");
        check(escaped != null && escaped.statePath.isEmpty(), "dotdot dropped");
        PlaySession.Jump bed = find(jumps, "checkpoint:intro-bed");
        check(bed != null && "entrance 0x01".equals(bed.location), "checkpoint location");
        check(find(jumps, "checkpoint:later") == null, "todo omitted");
        PlaySession.Jump area = find(jumps, "warp-area:0x33");
        check(area != null && "outside".equals(area.location), "area warp");

        File root = Files.createTempDirectory("oos-play-session").toFile();
        File library = new File(root, "OracleLibrary");
        File resolved = PlaySession.resolveStateFile(library, gate.statePath);
        check(resolved != null && resolved.getPath().endsWith("OracleLibrary/Roms/SaveStates/library/pre_d6.mss")
                || (resolved != null && resolved.getPath().replace('\\', '/').endsWith(
                "OracleLibrary/Roms/SaveStates/library/pre_d6.mss")), "library join");
        check(PlaySession.resolveStateFile(library, "../secret.mss") == null, "resolve rejects dotdot");

        File boards = new File(root, "review_boards");
        PlaySession.appendFeedback(boards, "needs_changes", "bug", "clipped the door",
                PlaySession.SOURCE_ANDROID, bed);
        PlaySession.appendFeedback(boards, "comment", "review", "landing reads",
                PlaySession.SOURCE_YAZE, gate);
        File feedback = PlaySession.feedbackFile(boards);
        Map<String, Object> parsed = MiniJson.object(MiniJson.parse(
                new String(Files.readAllBytes(feedback.toPath()), StandardCharsets.UTF_8)));
        check(PlaySession.FEEDBACK_SCHEMA.equals(parsed.get("schema")), "schema");
        check(PlaySession.PLAY_SCHEMA.equals(parsed.get("play_schema")), "play schema");
        check("in-play".equals(parsed.get("id")), "id");
        check("needs_changes".equals(parsed.get("status")), "status stays on the bug");
        check(MiniJson.array(parsed.get("history")).size() == 2, "two history rows");

        boolean rejected = false;
        try {
            PlaySession.appendFeedback(boards, "needs_changes", "bug", " ", PlaySession.SOURCE_ANDROID, null);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "blank bug note rejected");
        System.out.println("PlaySessionTest OK (" + checks + " checks)");
    }

    private static PlaySession.Jump find(List<PlaySession.Jump> jumps, String id) {
        for (PlaySession.Jump jump : jumps) {
            if (id.equals(jump.catalogId)) return jump;
        }
        return null;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
