package ca.mesen.oos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Portable Workshop voice-note layout + manifest. Recording is handled by
 * {@link WorkshopVoiceRecorder}; this class is host-testable without Android
 * MediaRecorder so the same stamp format can ship on mic-native devices later.
 */
final class WorkshopVoiceNote {
    static final String SCHEMA = "oos-workshop-voice-v1";
    static final String KIND = "voice";
    static final String GROUP = "voice-notes";
    static final String AUDIO_NAME = "note.m4a";
    static final String MANIFEST_NAME = "manifest.json";
    /** Soft cap so a stuck recording cannot fill storage. */
    static final int MAX_DURATION_MS = 120_000;
    static final int MIN_DURATION_MS = 250;

    static final class Stamp {
        final long frame;
        final String sha1;
        final String rom;
        final boolean paused;
        final String inputHint;

        Stamp(long frame, String sha1, String rom, boolean paused, String inputHint) {
            this.frame = frame;
            this.sha1 = sha1 == null ? "" : sha1;
            this.rom = rom == null ? "" : rom;
            this.paused = paused;
            this.inputHint = inputHint == null || inputHint.isEmpty() ? "default" : inputHint;
        }
    }

    static final class Session {
        final String id;
        final File directory;
        final File audioFile;
        final JSONObject manifest;
        final long startedAtMs;

        Session(String id, File directory, File audioFile, JSONObject manifest, long startedAtMs) {
            this.id = id;
            this.directory = directory;
            this.audioFile = audioFile;
            this.manifest = manifest;
            this.startedAtMs = startedAtMs;
        }
    }

    private WorkshopVoiceNote() {}

    static File groupRoot(File workshopRoot) {
        return new File(workshopRoot, GROUP);
    }

    static Session begin(File workshopRoot, Stamp stamp, long nowMs) throws Exception {
        if (workshopRoot == null) {
            throw new IOException("Workshop root is missing");
        }
        String created = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", nowMs);
        String shaShort = stamp.sha1.length() >= 8 ? stamp.sha1.substring(0, 8) : "unknown";
        String id = utc("yyyyMMdd'T'HHmmssSSS'Z'", nowMs) + "-voice-" + shaShort
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        File parent = groupRoot(workshopRoot);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create voice-notes folder");
        }
        File directory = WorkshopArtifactFiles.child(parent, id);
        if (!directory.mkdir()) {
            throw new IOException("Could not create voice note folder");
        }
        File audio = WorkshopArtifactFiles.child(directory, AUDIO_NAME);
        JSONObject manifest = new JSONObject()
                .put("schema", SCHEMA)
                .put("kind", KIND)
                .put("status", "recording")
                .put("id", id)
                .put("created_at", created)
                .put("frame", stamp.frame)
                .put("sha1", stamp.sha1.isEmpty() ? JSONObject.NULL : stamp.sha1)
                .put("rom", stamp.rom.isEmpty() ? JSONObject.NULL : stamp.rom)
                .put("paused", stamp.paused)
                .put("input", stamp.inputHint)
                .put("duration_ms", JSONObject.NULL)
                .put("files", new JSONArray())
                .put("errors", new JSONArray());
        writeManifest(directory, manifest);
        return new Session(id, directory, audio, manifest, nowMs);
    }

    static void complete(Session session, long durationMs, String audioSha256, long audioBytes)
            throws Exception {
        if (session == null) {
            throw new IOException("No active voice session");
        }
        if (durationMs < MIN_DURATION_MS) {
            fail(session, "Voice note was too short");
        }
        if (audioBytes <= 0 || !session.audioFile.isFile()) {
            fail(session, "Voice note audio is missing");
        }
        if (audioSha256 == null || !audioSha256.matches("[0-9a-fA-F]{64}")) {
            fail(session, "Voice note checksum is missing");
        }
        session.manifest.put("status", "complete");
        session.manifest.put("duration_ms", durationMs);
        session.manifest.put("files", new JSONArray().put(new JSONObject()
                .put("name", AUDIO_NAME)
                .put("size", audioBytes)
                .put("sha256", audioSha256.toLowerCase(Locale.US))));
        writeManifest(session.directory, session.manifest);
    }

    static void fail(Session session, String message) throws IOException {
        if (session != null) {
            try {
                session.manifest.put("status", "partial");
                session.manifest.getJSONArray("errors").put(new JSONObject()
                        .put("stage", "voice")
                        .put("message", message));
                writeManifest(session.directory, session.manifest);
            } catch (Exception ignored) {
            }
            if (session.audioFile != null && session.audioFile.exists()) {
                // Keep partial audio for salvage; collect will skip incomplete.
            }
        }
        throw new IOException(message);
    }

    static void discard(Session session) {
        if (session == null) {
            return;
        }
        try {
            if (session.audioFile != null && session.audioFile.exists()) {
                session.audioFile.delete();
            }
            File manifest = WorkshopArtifactFiles.child(session.directory, MANIFEST_NAME);
            if (manifest.exists()) {
                manifest.delete();
            }
            session.directory.delete();
        } catch (Exception ignored) {
        }
    }

    static Stamp stampFromStatus(JSONObject status, String inputHint) {
        if (status == null) {
            return new Stamp(-1, "", "", false, inputHint);
        }
        return new Stamp(
                status.optLong("frames", status.optLong("frame", -1)),
                status.optString("sha1", ""),
                status.optString("rom", ""),
                status.optBoolean("paused", false),
                inputHint);
    }

    private static void writeManifest(File directory, JSONObject manifest) throws Exception {
        File file = WorkshopArtifactFiles.child(directory, MANIFEST_NAME);
        byte[] bytes = (manifest.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WorkshopArtifactFiles.atomicWrite(file, bytes);
    }

    private static String utc(String pattern, long nowMs) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(nowMs));
    }
}
