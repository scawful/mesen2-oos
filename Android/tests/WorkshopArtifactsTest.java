package ca.mesen.oos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Host behavior tests. Uses a fake command boundary, never a real emulator/socket. */
public final class WorkshopArtifactsTest {
    private static int checks;
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String APK = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    interface Checked { void run() throws Exception; }

    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    static void fails(Checked action, String text) throws Exception {
        try { action.run(); throw new AssertionError("Expected refusal: " + text); }
        catch (IOException expected) { check(expected.getMessage().contains(text), "Expected '" + text + "', got " + expected); }
    }

    static JSONObject app() throws Exception {
        return new JSONObject().put("package", "ca.mesen.oos").put("version_name", "test")
                .put("version_code", 3).put("apk_sha256", APK);
    }

    static final class Fake implements WorkshopArtifacts.Transport {
        long frame = 1234;
        boolean paused = true, emptySave, screenshotFailure, gameStateFailure,
                spritesFailure, drift, changeRom, loadUnpauses;
        String sha1 = SHA1;
        byte[] state = mss();
        List<String> calls = new ArrayList<>();
        File lastSave, lastLoad;

        Fake() throws Exception {}

        @Override public Object command(JSONObject request) throws Exception {
            String command = request.getString("type");
            calls.add(command);
            switch (command) {
                case "STATE": return new JSONObject().put("running", true).put("paused", paused).put("frame", frame).put("consoleType", 0);
                case "ROMINFO": return new JSONObject().put("filename", "oos-168-weather.sfc").put("sha1", sha1).put("crc32", "9109C3A5").put("frameCount", frame);
                case "GAMESTATE":
                    if (gameStateFailure) throw new IOException("Debugger unavailable");
                    return new JSONObject()
                            .put("link", new JSONObject().put("x", 0x1234).put("y", 0x5678))
                            .put("game", new JSONObject().put("mode", 0x09)
                                    .put("overworld_area", "0x2A"));
                case "SPRITES":
                    if (spritesFailure) throw new IOException("Sprite metadata unavailable");
                    return new JSONObject().put("count", 1).put("sprites",
                            new JSONArray().put(new JSONObject().put("slot", 3).put("type", 0x6C)));
                case "SAVESTATE":
                    check(request.getBoolean("pause") && request.getBoolean("allow_external") && !request.has("slot"), "save uses explicit owned path and pause=true");
                    lastSave = new File(request.getString("path"));
                    check(!lastSave.exists() && lastSave.getName().equals("state.mss"), "save never overwrites a state");
                    Files.write(lastSave.toPath(), emptySave ? new byte[0] : state);
                    return "OK";
                case "SCREENSHOT":
                    if (screenshotFailure) throw new IOException("Screenshot unavailable");
                    if (drift) frame++;
                    if (changeRom) sha1 = "ffffffffffffffffffffffffffffffffffffffff";
                    return Base64.getEncoder().encodeToString(png());
                case "LOADSTATE":
                    check(request.getBoolean("pause") && request.getBoolean("allow_external"), "load preserves paused session");
                    lastLoad = new File(request.getString("path"));
                    frame = 1234;
                    if (loadUnpauses) paused = false;
                    return "OK";
                default: throw new AssertionError("Unexpected emulator mutation: " + command);
            }
        }
    }

    static WorkshopArtifacts backend(File root, Fake fake) throws Exception {
        return new WorkshopArtifacts(root, app(), fake, value -> Base64.getDecoder().decode(value));
    }

    static File root() throws Exception { return Files.createTempDirectory("workshop-artifact-test-").toFile(); }
    static JSONObject manifest(File directory) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(directory, "manifest.json").toPath()), StandardCharsets.UTF_8));
    }
    static void manifest(File directory, JSONObject data) throws Exception {
        Files.write(new File(directory, "manifest.json").toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
    }
    static File first(File root, String kind) { return new File(root, kind).listFiles()[0]; }

    public static void main(String[] args) throws Exception {
        Fake fake = new Fake();
        File root = root();
        WorkshopArtifacts backend = backend(root, fake);
        WorkshopArtifacts.Entry saved = backend.saveCheckpoint();
        JSONObject recorded = manifest(saved.directory);
        check(recorded.getString("schema").equals("oos-workshop-artifact-v1") && recorded.getString("status").equals("complete"), "complete manifest published");
        check(recorded.getJSONArray("files").length() == 2 && recorded.getJSONArray("errors").length() == 0, "state and PNG independently verified");
        check(recorded.getJSONObject("game_state").getJSONObject("link").getInt("x") == 0x1234
                && recorded.getJSONArray("warnings").length() == 0,
                "capture includes live game-state metadata");
        check(recorded.getJSONObject("sprites").getInt("count") == 1
                && recorded.getJSONObject("sprites").getJSONArray("sprites")
                        .getJSONObject(0).getInt("slot") == 3,
                "capture includes active-sprite metadata");
        check(saved.title.contains("oos-168-weather.sfc") && saved.title.contains(SHA1.substring(0, 8)), "title exposes build identity");
        check(!new File(saved.directory, "manifest.json.tmp").exists(), "complete manifest replaces temporary file");
        check(backend.listCheckpoints().get(0).compatibilityReason.isEmpty(), "exact ROM/APK checkpoint offered");
        byte[] originalState = Files.readAllBytes(fake.lastSave.toPath());
        WorkshopArtifacts.Entry named = backend.saveCheckpoint("  Mine cart  before  89 ", "Hold Down at the junction.");
        JSONObject namedManifest = manifest(named.directory);
        check(namedManifest.getString("title").equals("Mine cart before 89"), "checkpoint name is trimmed and collapsed");
        check(namedManifest.getString("note").equals("Hold Down at the junction."), "optional checkpoint note is stored");
        check(named.title.startsWith("Mine cart before 89\n") && named.title.contains("Hold Down at the junction."), "list title leads with the chosen name");
        check(backend.saveCheckpoint("   ", null).title.contains("oos-168-weather.sfc"), "blank name keeps the automatic identity title");
        fake.gameStateFailure = true;
        JSONObject metadataWarning = manifest(backend.saveCheckpoint("No debugger", null).directory);
        check(metadataWarning.getString("status").equals("complete")
                && metadataWarning.isNull("game_state")
                && metadataWarning.getJSONArray("warnings").length() == 1,
                "missing optional game state warns without discarding checkpoint");
        fake.gameStateFailure = false;
        fake.spritesFailure = true;
        JSONObject spriteWarning = manifest(backend.saveCheckpoint("No sprites", null).directory);
        check(spriteWarning.getString("status").equals("complete")
                && spriteWarning.isNull("sprites")
                && !spriteWarning.isNull("game_state")
                && spriteWarning.getJSONArray("warnings").length() == 1,
                "missing optional sprites warn without discarding checkpoint");
        fake.spritesFailure = false;
        fails(() -> backend.saveCheckpoint("x".repeat(WorkshopArtifacts.TITLE_MAX + 1), null), "Name must be");
        fails(() -> backend.saveCheckpoint("bad\u0001name", null), "control characters");
        WorkshopArtifacts.Entry second = backend.saveCheckpoint();
        check(!saved.id.equals(second.id) && Arrays.equals(originalState, Files.readAllBytes(new File(saved.directory, "state.mss").toPath())), "repeated saves preserve prior bytes");
        fake.frame = 2222;
        backend.restoreCheckpoint(saved);
        check(fake.frame == 1234 && fake.paused && fake.lastLoad.equals(new File(saved.directory, "state.mss")), "explicit restore returns exact paused frame");

        JSONObject wrongApk = new JSONObject(recorded.toString());
        wrongApk.getJSONObject("emulator").put("apk_sha256", APK.replace('a', 'b'));
        manifest(saved.directory, wrongApk);
        fake.lastLoad = null;
        fails(() -> backend.restoreCheckpoint(saved), "Different emulator APK");
        check(fake.lastLoad == null, "APK mismatch never loads");
        manifest(saved.directory, recorded);
        fake.sha1 = SHA1.replace('0', 'f');
        fails(() -> backend.restoreCheckpoint(saved), "Different ROM build");
        check(fake.lastLoad == null, "ROM mismatch never loads");
        fake.sha1 = SHA1;

        JSONObject partial = new JSONObject(recorded.toString()).put("status", "partial");
        manifest(saved.directory, partial);
        fails(() -> backend.restoreCheckpoint(saved), "Partial capture");
        manifest(saved.directory, recorded);
        File state = new File(saved.directory, "state.mss");
        Files.write(state.toPath(), Arrays.copyOf(originalState, originalState.length - 1));
        fails(() -> backend.restoreCheckpoint(saved), "missing or changed");
        check(fake.lastLoad == null, "changed bytes never load");
        Files.write(state.toPath(), originalState);
        JSONObject duplicate = new JSONObject(recorded.toString());
        duplicate.getJSONArray("files").put(duplicate.getJSONArray("files").getJSONObject(0));
        manifest(saved.directory, duplicate);
        fails(() -> backend.restoreCheckpoint(saved), "Duplicate checkpoint file");
        manifest(saved.directory, recorded);
        fails(() -> backend.restoreCheckpoint(new WorkshopArtifacts.Entry("escape", "escape", "", root.getParentFile())), "outside Workshop storage");
        fake.paused = false;
        fails(() -> backend.restoreCheckpoint(saved), "Keep the game paused");
        fake.paused = true;
        fake.loadUnpauses = true;
        fails(() -> backend.restoreCheckpoint(saved), "Keep the game paused");

        Fake issueFake = new Fake();
        File issueRoot = root();
        WorkshopArtifacts issueBackend = backend(issueRoot, issueFake);
        WorkshopArtifacts.Entry issue = issueBackend.captureBug("audio");
        JSONObject issueManifest = manifest(issue.directory);
        check(issueManifest.getString("kind").equals("issue") && issueManifest.getString("category").equals("audio") && issueManifest.getJSONArray("files").length() == 2, "bug includes category, screenshot, and reproducible state");
        check(issueManifest.isNull("note") && issueManifest.isNull("title"), "unnamed bug leaves title and note empty");
        WorkshopArtifacts.Entry noted = issueBackend.captureBug("weather", "Outdoor rain after house exit");
        check(manifest(noted.directory).getString("note").equals("Outdoor rain after house exit"), "bug note is stored without becoming a restore candidate");
        check(noted.title.contains("Outdoor rain after house exit"), "bug list text includes the optional note");
        check(issueBackend.listCheckpoints().isEmpty(), "issues are not automatic restore candidates");
        fails(() -> issueBackend.captureBug("unknown"), "Choose a bug category");
        fails(() -> issueBackend.captureBug("audio", "n".repeat(WorkshopArtifacts.NOTE_MAX + 1)), "Note must be");

        for (String failure : Arrays.asList("empty", "screenshot", "drift", "rom")) {
            Fake broken = new Fake();
            broken.emptySave = failure.equals("empty"); broken.screenshotFailure = failure.equals("screenshot");
            broken.drift = failure.equals("drift"); broken.changeRom = failure.equals("rom");
            File brokenRoot = root();
            WorkshopArtifacts brokenBackend = backend(brokenRoot, broken);
            fails(() -> brokenBackend.saveCheckpoint(), "Partial files:");
            JSONObject failed = manifest(first(brokenRoot, "checkpoints"));
            check(failed.getString("status").equals("partial") && failed.getJSONArray("errors").length() > 0, failure + " leaves partial manifest");
            check(!brokenBackend.listCheckpoints().get(0).compatibilityReason.isEmpty(), failure + " is refused for restore");
            if (failure.equals("empty")) check(failed.getJSONArray("files").getJSONObject(0).getString("name").equals("screenshot.png"), "failed state does not suppress good image evidence");
            if (failure.equals("screenshot")) check(failed.getJSONArray("files").getJSONObject(0).getString("name").equals("state.mss"), "failed image preserves good state evidence");
        }

        File binaries = root();
        File mss = new File(binaries, "test.mss"), png = new File(binaries, "test.png");
        Files.write(mss.toPath(), mss());
        WorkshopArtifactFiles.verifyState(mss); checks++;
        byte[] broken = mss(); broken[broken.length - 1] ^= 1;
        Files.write(mss.toPath(), broken);
        try { WorkshopArtifactFiles.verifyState(mss); throw new AssertionError("Corrupt zlib accepted"); }
        catch (Exception expected) { checks++; }
        Files.write(mss.toPath(), Arrays.copyOf(mss(), 40));
        fails(() -> WorkshopArtifactFiles.verifyState(mss), "incomplete");
        Files.write(png.toPath(), png());
        WorkshopArtifactFiles.verifyPng(png); checks++;
        byte[] badPng = png(); badPng[badPng.length - 1] ^= 1;
        Files.write(png.toPath(), badPng);
        fails(() -> WorkshopArtifactFiles.verifyPng(png), "checksum failed");
        Files.write(png.toPath(), Arrays.copyOf(png(), png().length - 12));
        fails(() -> WorkshopArtifactFiles.verifyPng(png), "no complete ending");

        for (String real : args) {
            File file = new File(real);
            if (real.endsWith(".mss")) WorkshopArtifactFiles.verifyState(file);
            else WorkshopArtifactFiles.verifyPng(file);
            System.out.println("Verified real fixture: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
            checks++;
        }
        System.out.println("WorkshopArtifactsTest: " + checks + " checks passed");
    }

    static byte[] compress(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) { deflater.write(input); }
        return bytes.toByteArray();
    }
    static void le32(ByteArrayOutputStream out, int n) { for (int i = 0; i < 4; i++) out.write(n >>> (i * 8)); }
    static void be32(ByteArrayOutputStream out, long n) { for (int i = 3; i >= 0; i--) out.write((int)(n >>> (i * 8))); }
    static byte[] mss() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("MSS".getBytes(StandardCharsets.US_ASCII)); le32(out, 0x020001); le32(out, 4); le32(out, 0);
        byte[] frame = new byte[256 * 224 * 4], compressed = compress(frame);
        le32(out, frame.length); le32(out, 256); le32(out, 224); le32(out, 100); le32(out, compressed.length); out.write(compressed);
        byte[] name = "oos-168-weather".getBytes(StandardCharsets.UTF_8);
        le32(out, name.length); out.write(name); out.write(1);
        byte[] serialized = new byte[4096], state = compress(serialized);
        le32(out, serialized.length); le32(out, state.length); out.write(state);
        return out.toByteArray();
    }
    static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        be32(out, data.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name); out.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data); be32(out, crc.getValue());
    }
    static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(); be32(header, 1); be32(header, 1);
        header.write(new byte[]{8, 6, 0, 0, 0}); chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", compress(new byte[]{0, 1, 2, 3, (byte)255})); chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }
}
