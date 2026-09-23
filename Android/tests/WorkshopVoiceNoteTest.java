package ca.mesen.oos;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

final class WorkshopVoiceNoteTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("voice-note-test").toFile();
        try {
            WorkshopVoiceNote.Stamp stamp = new WorkshopVoiceNote.Stamp(
                    4242L,
                    "2671E543D83A96AD73F1D7D3CCB55580593DCA14",
                    "oos168x.sfc",
                    true,
                    "bluetooth-sco");
            WorkshopVoiceNote.Session session = WorkshopVoiceNote.begin(root, stamp, 1_700_000_000_000L);
            check(session.id.contains("-voice-"), "id missing voice marker");
            check(session.directory.getParentFile().getName().equals("voice-notes"), "wrong group folder");
            check(session.manifest.getString("schema").equals(WorkshopVoiceNote.SCHEMA), "schema");
            check(session.manifest.getString("status").equals("recording"), "status recording");
            check(session.manifest.getLong("frame") == 4242L, "frame stamp");
            check(session.manifest.getString("input").equals("bluetooth-sco"), "input hint");

            byte[] audio = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
            Files.write(session.audioFile.toPath(), audio);
            String digest = WorkshopArtifactFiles.sha256(session.audioFile);
            WorkshopVoiceNote.complete(session, 1500, digest, audio.length);
            JSONObject done = new JSONObject(new String(
                    Files.readAllBytes(new File(session.directory, "manifest.json").toPath())));
            check(done.getString("status").equals("complete"), "complete status");
            check(done.getLong("duration_ms") == 1500L, "duration");
            check(done.getJSONArray("files").length() == 1, "file list");
            check(done.getJSONArray("files").getJSONObject(0).getString("name")
                    .equals(WorkshopVoiceNote.AUDIO_NAME), "audio name");

            WorkshopVoiceNote.Stamp fromStatus = WorkshopVoiceNote.stampFromStatus(
                    new JSONObject().put("frames", 9).put("sha1", "abc").put("rom", "x.sfc").put("paused", false),
                    "mic");
            check(fromStatus.frame == 9L, "status frames");
            check(fromStatus.inputHint.equals("mic"), "default input");

            boolean shortFailed = false;
            WorkshopVoiceNote.Session shortSession = WorkshopVoiceNote.begin(
                    root, stamp, 1_700_000_000_100L);
            Files.write(shortSession.audioFile.toPath(), audio);
            try {
                WorkshopVoiceNote.complete(shortSession, 10,
                        WorkshopArtifactFiles.sha256(shortSession.audioFile), audio.length);
            } catch (Exception expected) {
                shortFailed = true;
            }
            check(shortFailed, "short note should fail");

            System.out.println("WorkshopVoiceNoteTest OK");
        } finally {
            deleteTree(root);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
