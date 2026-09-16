package ca.mesen.oos;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Blocking backend. Call only from Workshop's single worker while the game is paused. */
public final class WorkshopArtifacts {
    static final String SCHEMA = "oos-workshop-artifact-v1";
    static final int TITLE_MAX = 48;
    static final int NOTE_MAX = 200;
    private static final List<String> CATEGORIES = Arrays.asList("weather", "collision", "audio", "graphics", "other");
    private final Context context;
    private final File root;
    private final Transport transport;
    private final Decoder decoder;
    private JSONObject emulator;

    public static final class Entry {
        public final String id, title, compatibilityReason;
        public final File directory;

        Entry(String id, String title, String reason, File directory) {
            this.id = id;
            this.title = title;
            this.compatibilityReason = reason;
            this.directory = directory;
        }
    }

    interface Transport { Object command(JSONObject request) throws Exception; }
    interface Decoder { byte[] decode(String encoded) throws Exception; }

    public WorkshopArtifacts(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(this.context.getFilesDir(), "Workshop");
        this.transport = new LoopbackTransport();
        this.decoder = encoded -> Base64.decode(encoded, Base64.DEFAULT);
    }

    // Inject only filesystem/protocol adapters for host tests, never a public remote endpoint.
    WorkshopArtifacts(File root, JSONObject emulator, Transport transport, Decoder decoder) {
        this.context = null;
        this.root = root;
        this.emulator = emulator;
        this.transport = transport;
        this.decoder = decoder;
    }

    public synchronized Entry saveCheckpoint() throws Exception {
        return saveCheckpoint(null, null);
    }

    public synchronized Entry saveCheckpoint(String title, String note) throws Exception {
        return capture("checkpoint", null, title, note);
    }

    public synchronized Entry captureBug(String category) throws Exception {
        return captureBug(category, null);
    }

    public synchronized Entry captureBug(String category, String note) throws Exception {
        if (!CATEGORIES.contains(category)) throw new IOException("Choose a bug category first");
        return capture("issue", category, null, note);
    }

    public synchronized Entry captureBug(String category, String title, String note) throws Exception {
        if (!CATEGORIES.contains(category)) throw new IOException("Choose a bug category first");
        return capture("issue", category, title, note);
    }

    private Entry capture(String kind, String category, String title, String note) throws Exception {
        requireWorker();
        String labeledTitle = optionalText(title, TITLE_MAX, "Name");
        String labeledNote = optionalText(note, NOTE_MAX, "Note");
        JSONObject app = emulatorIdentity();
        JSONObject before = state();
        JSONObject rom = rom();
        requireStable(before, state());
        String created = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        String id = utc("yyyyMMdd'T'HHmmssSSS'Z'") + "-" + kind + "-" + rom.getString("sha1").substring(0, 8)
                + "-" + UUID.randomUUID().toString();
        File parent = folder("checkpoint".equals(kind) ? "checkpoints" : "issues");
        File directory = WorkshopArtifactFiles.child(parent, id);
        if (!directory.mkdir()) throw new IOException("Could not create a new Workshop capture folder");
        JSONObject manifest = new JSONObject().put("schema", SCHEMA).put("kind", kind).put("status", "partial")
                .put("id", id).put("created_at", created).put("rom", rom).put("emulator", app)
                .put("category", category == null ? JSONObject.NULL : category)
                .put("title", labeledTitle == null ? JSONObject.NULL : labeledTitle)
                .put("note", labeledNote == null ? JSONObject.NULL : labeledNote)
                .put("before", before)
                .put("game_state", JSONObject.NULL)
                .put("sprites", JSONObject.NULL)
                .put("after", JSONObject.NULL).put("screenshot_source", "emulator framebuffer")
                .put("files", new JSONArray()).put("warnings", new JSONArray())
                .put("errors", new JSONArray());
        try {
            writeManifest(directory, manifest);
            // Game-state metadata makes a collected artifact useful without
            // first restoring it. Keep this best-effort: a debugger failure
            // must not discard an otherwise valid state and screenshot.
            try {
                Object gameState = command("GAMESTATE");
                if (!(gameState instanceof JSONObject))
                    throw new IOException("Emulator returned no game-state metadata");
                manifest.put("game_state", gameState);
            } catch (Exception warning) {
                recordWarning(manifest, "game_state", warning);
            }
            try {
                Object sprites = command("SPRITES");
                if (!(sprites instanceof JSONObject))
                    throw new IOException("Emulator returned no sprite metadata");
                manifest.put("sprites", sprites);
            } catch (Exception warning) {
                recordWarning(manifest, "sprites", warning);
            }
            File stateFile = WorkshopArtifactFiles.child(directory, "state.mss");
            try {
                if (stateFile.exists()) throw new IOException("Checkpoint file already exists");
                requireOk(command("SAVESTATE", stateFile));
                WorkshopArtifactFiles.verifyState(stateFile);
                manifest.getJSONArray("files").put(fileInfo(stateFile));
            } catch (Exception error) { recordError(manifest, "state", error); }
            try {
                requireStable(before, state());
                requireSameRom(rom, rom());
                Object screenshot = command("SCREENSHOT");
                if (!(screenshot instanceof String)) throw new IOException("Emulator returned no screenshot image");
                byte[] png = decoder.decode((String)screenshot);
                if (png.length > WorkshopArtifactFiles.MAX_FILE) throw new IOException("Screenshot is too large");
                File screenshotFile = WorkshopArtifactFiles.child(directory, "screenshot.png");
                WorkshopArtifactFiles.atomicWrite(screenshotFile, png);
                WorkshopArtifactFiles.verifyPng(screenshotFile);
                manifest.getJSONArray("files").put(fileInfo(screenshotFile));
            } catch (Exception error) { recordError(manifest, "screenshot", error); }
            try {
                JSONObject after = state();
                JSONObject afterRom = rom();
                manifest.put("after", after).put("rom_after", afterRom);
                requireStable(before, after);
                requireSameRom(rom, afterRom);
                requireStable(after, state());
            } catch (Exception error) { recordError(manifest, "session", error); }
            if (manifest.getJSONArray("errors").length() != 0) {
                writeManifest(directory, manifest);
                throw new IOException("Capture is partial (" + id + "): " + manifest.getJSONArray("errors").getJSONObject(0).getString("message"));
            }
            manifest.put("status", "complete");
            writeManifest(directory, manifest);
            return entry(directory, manifest, "");
        } catch (Exception failure) {
            manifest.put("status", "partial");
            if (manifest.getJSONArray("errors").length() == 0) recordError(manifest, "capture", failure);
            try { writeManifest(directory, manifest); } catch (Exception writeError) { failure.addSuppressed(writeError); }
            throw new IOException("Workshop capture was not completed. Partial files: " + id + ". " + message(failure), failure);
        }
    }

    /** Includes refused and partial checkpoints, so an APK update never silently hides old work. */
    public synchronized List<Entry> listCheckpoints() throws Exception {
        requireWorker();
        File parent = folder("checkpoints");
        File[] children = parent.listFiles();
        if (children == null) throw new IOException("Could not read Workshop checkpoints");
        JSONObject currentRom = null, app = null;
        String unavailable = "";
        try { currentRom = rom(); app = emulatorIdentity(); } catch (Exception error) { unavailable = message(error); }
        List<Entry> entries = new ArrayList<>();
        Arrays.sort(children, Collections.reverseOrder());
        for (File directory : children) {
            if (!directory.isDirectory()) continue;
            try {
                ownedCheckpoint(directory);
                JSONObject manifest = readManifest(directory);
                String reason = unavailable.isEmpty() ? refusal(manifest, currentRom, app) : "Cannot verify current game: " + unavailable;
                if (reason.isEmpty()) {
                    try { verifyFiles(directory, manifest); } catch (Exception error) { reason = message(error); }
                }
                entries.add(entry(directory, manifest, reason));
            } catch (Exception error) {
                entries.add(new Entry(directory.getName(), directory.getName(), "Checkpoint is incomplete or unreadable: " + message(error), directory));
            }
        }
        return entries;
    }

    /** Restore is explicit, revalidates bytes/identity, and never resumes or writes save RAM. */
    public synchronized void restoreCheckpoint(Entry selected) throws Exception {
        requireWorker();
        if (selected == null) throw new IOException("Choose a checkpoint first");
        File directory = ownedCheckpoint(selected.directory);
        JSONObject manifest = readManifest(directory);
        JSONObject currentRom = rom();
        String reason = refusal(manifest, currentRom, emulatorIdentity());
        if (!reason.isEmpty()) throw new IOException(reason);
        verifyFiles(directory, manifest);
        JSONObject before = state();
        requireSameRom(currentRom, rom());
        requireStable(before, state());
        File stateFile = WorkshopArtifactFiles.child(directory, "state.mss");
        requireOk(command("LOADSTATE", stateFile));
        JSONObject after = state();
        requireSameRom(currentRom, rom());
        requireStable(after, state());
        if (after.getLong("frame") != manifest.getJSONObject("before").getLong("frame"))
            throw new IOException("Checkpoint loaded, but its frame could not be verified. Keep Workshop open.");
    }

    private String refusal(JSONObject manifest, JSONObject currentRom, JSONObject app) throws Exception {
        if (!SCHEMA.equals(manifest.optString("schema")) || !"checkpoint".equals(manifest.optString("kind"))) return "Unknown checkpoint format";
        if (!"complete".equals(manifest.optString("status"))) return "Partial capture cannot be restored";
        JSONObject recordedRom = manifest.getJSONObject("rom"), recordedApp = manifest.getJSONObject("emulator");
        validateRom(recordedRom);
        if (!recordedRom.getString("sha1").equalsIgnoreCase(currentRom.getString("sha1"))
                || !recordedRom.getString("crc32").equalsIgnoreCase(currentRom.getString("crc32")))
            return "Different ROM build. Select the captured ROM first.";
        String hash = recordedApp.optString("apk_sha256");
        if (!hash.matches("[0-9a-fA-F]{64}") || !hash.equalsIgnoreCase(app.getString("apk_sha256"))
                || !recordedApp.getString("package").equals(app.getString("package")))
            return "Different emulator APK. This checkpoint requires its original app build.";
        requireStable(manifest.getJSONObject("before"), manifest.getJSONObject("after"));
        requireSameRom(recordedRom, manifest.getJSONObject("rom_after"));
        return "";
    }

    private static void verifyFiles(File directory, JSONObject manifest) throws Exception {
        JSONArray files = manifest.getJSONArray("files");
        boolean state = false, png = false;
        for (int i = 0; i < files.length(); i++) {
            JSONObject info = files.getJSONObject(i);
            String name = info.getString("name");
            if (!"state.mss".equals(name) && !"screenshot.png".equals(name)) throw new IOException("Unknown checkpoint file");
            if (("state.mss".equals(name) && state) || ("screenshot.png".equals(name) && png)) throw new IOException("Duplicate checkpoint file");
            File file = WorkshopArtifactFiles.child(directory, name);
            if (!file.isFile() || file.length() != info.getLong("size") || file.length() > WorkshopArtifactFiles.MAX_FILE
                    || !WorkshopArtifactFiles.sha256(file).equalsIgnoreCase(info.getString("sha256")))
                throw new IOException("Checkpoint file is missing or changed: " + name);
            if ("state.mss".equals(name)) { WorkshopArtifactFiles.verifyState(file); state = true; }
            else { WorkshopArtifactFiles.verifyPng(file); png = true; }
        }
        if (!state || !png) throw new IOException("Checkpoint is missing its state or screenshot");
    }

    private JSONObject emulatorIdentity() throws Exception {
        if (emulator == null) {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            // Split APKs need a multi-file identity contract before they can restore checkpoints.
            if (context.getApplicationInfo().splitSourceDirs != null && context.getApplicationInfo().splitSourceDirs.length != 0)
                throw new IOException("Workshop checkpoints require the single-APK app build");
            emulator = new JSONObject().put("package", context.getPackageName()).put("version_name", info.versionName)
                    .put("version_code", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode)
                    .put("apk_sha256", WorkshopArtifactFiles.sha256(new File(context.getApplicationInfo().sourceDir)));
        }
        return emulator;
    }

    private JSONObject state() throws Exception {
        Object result = command("STATE");
        if (!(result instanceof JSONObject)) throw new IOException("Emulator state is unavailable");
        JSONObject state = (JSONObject)result;
        if (!state.getBoolean("running")) throw new IOException("Load a game before using Workshop captures");
        if (!state.getBoolean("paused")) throw new IOException("Keep the game paused in Workshop, then try again");
        if (state.getLong("frame") < 0) throw new IOException("Emulator frame is unavailable");
        return state;
    }

    private JSONObject rom() throws Exception {
        Object result = command("ROMINFO");
        if (!(result instanceof JSONObject)) throw new IOException("Loaded ROM identity is unavailable");
        JSONObject rom = (JSONObject)result;
        validateRom(rom);
        return rom;
    }

    private static void validateRom(JSONObject rom) throws Exception {
        if (!rom.optString("sha1").matches("[0-9a-fA-F]{40}") || !rom.optString("crc32").matches("[0-9a-fA-F]{8}")
                || rom.optString("filename").isEmpty()) throw new IOException("Loaded ROM identity is incomplete");
    }

    private static void requireSameRom(JSONObject first, JSONObject second) throws Exception {
        validateRom(first); validateRom(second);
        if (!first.getString("sha1").equalsIgnoreCase(second.getString("sha1"))
                || !first.getString("crc32").equalsIgnoreCase(second.getString("crc32")))
            throw new IOException("The loaded ROM changed during the operation. Try again with Workshop paused.");
    }

    private static void requireStable(JSONObject first, JSONObject second) throws Exception {
        if (!first.getBoolean("running") || !second.getBoolean("running") || !first.getBoolean("paused") || !second.getBoolean("paused")
                || first.getLong("frame") != second.getLong("frame"))
            throw new IOException("The game changed during capture. Keep Workshop paused and try again.");
    }

    private Object command(String type) throws Exception { return transport.command(new JSONObject().put("type", type)); }
    private Object command(String type, File state) throws Exception {
        return transport.command(new JSONObject().put("type", type).put("path", state.getCanonicalPath())
                .put("pause", true).put("allow_external", true));
    }
    private static void requireOk(Object data) throws IOException {
        if (!"OK".equals(data)) throw new IOException("Emulator did not confirm the state operation");
    }
    private File folder(String name) throws IOException {
        File directory = WorkshopArtifactFiles.child(root, name);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create Workshop storage");
        return directory;
    }
    private File ownedCheckpoint(File directory) throws IOException {
        File canonical = directory.getCanonicalFile();
        if (!canonical.getParentFile().equals(folder("checkpoints").getCanonicalFile()) || !canonical.isDirectory())
            throw new IOException("Checkpoint is outside Workshop storage");
        return canonical;
    }
    private static JSONObject readManifest(File directory) throws Exception {
        JSONObject manifest = new JSONObject(new String(WorkshopArtifactFiles.read(WorkshopArtifactFiles.child(directory, "manifest.json"), 1024 * 1024), StandardCharsets.UTF_8));
        if (!directory.getName().equals(manifest.getString("id"))) throw new IOException("Checkpoint folder identity does not match");
        return manifest;
    }
    private static void writeManifest(File directory, JSONObject manifest) throws Exception {
        WorkshopArtifactFiles.atomicWrite(WorkshopArtifactFiles.child(directory, "manifest.json"), manifest.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject fileInfo(File file) throws Exception {
        return new JSONObject().put("name", file.getName()).put("size", file.length()).put("sha256", WorkshopArtifactFiles.sha256(file));
    }
    private static void recordError(JSONObject manifest, String part, Exception error) throws Exception {
        manifest.getJSONArray("errors").put(new JSONObject().put("component", part).put("message", message(error)));
    }
    private static void recordWarning(JSONObject manifest, String part, Exception warning) throws Exception {
        manifest.getJSONArray("warnings").put(
                new JSONObject().put("component", part).put("message", message(warning)));
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static Entry entry(File directory, JSONObject manifest, String reason) throws Exception {
        JSONObject rom = manifest.optJSONObject("rom");
        String named = manifest.isNull("title") ? "" : manifest.optString("title", "").trim();
        String noted = manifest.isNull("note") ? "" : manifest.optString("note", "").trim();
        String title = named.isEmpty() ? manifest.optString("created_at", directory.getName()) : named;
        if (!named.isEmpty()) title += "\n" + manifest.optString("created_at", directory.getName());
        if (rom != null) title += "\n" + rom.optString("filename", "Unknown ROM") + " · " + rom.optString("sha1", "unknown").substring(0, Math.min(8, rom.optString("sha1", "unknown").length()));
        if (!noted.isEmpty()) title += "\n" + noted;
        return new Entry(directory.getName(), title, reason, directory);
    }

    static String optionalText(String value, int max, String field) throws IOException {
        if (value == null) return null;
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') cleaned.append(' ');
            else if (Character.isISOControl(ch)) throw new IOException(field + " cannot contain control characters");
            else cleaned.append(ch);
        }
        String text = cleaned.toString().trim().replaceAll(" +", " ");
        if (text.isEmpty()) return null;
        if (text.length() > max) throw new IOException(field + " must be " + max + " characters or fewer");
        return text;
    }
    private static String utc(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
    private void requireWorker() {
        if (context != null && Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Workshop capture must run on its worker");
    }

    static final class LoopbackTransport implements Transport {
        @Override public Object command(JSONObject request) throws Exception {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 27015), 2000);
                socket.setSoTimeout(15000);
                socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                byte[] bytes = new byte[8192];
                boolean ended = false;
                while (!ended) {
                    int count = in.read(bytes);
                    if (count < 0) throw new IOException("Emulator disconnected before its response completed");
                    for (int i = 0; i < count; i++) {
                        if (bytes[i] == '\n') { ended = true; break; }
                        line.write(bytes[i]);
                    }
                    if (line.size() > WorkshopArtifactFiles.MAX_FILE) throw new IOException("Emulator response is too large");
                }
                JSONObject response = new JSONObject(new String(line.toByteArray(), StandardCharsets.UTF_8));
                if (!response.getBoolean("success")) throw new IOException(response.optString("error", "Emulator command failed"));
                if (!response.has("data")) throw new IOException("Emulator response has no data");
                return response.get("data");
            } catch (IOException error) {
                throw new IOException(request.optString("type") + ": " + message(error), error);
            }
        }
    }
}
