package ca.mesen.oos;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class SaveStateSlotsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("mesen-state-slots").toFile();
        testEmptySlot(root);
        testOccupiedSlot(root);
        testLabelLimit(root);
        testBuildSidecarAndWarnOnce(root);
        testDeleteRemovesSidecars(root);
        System.out.println("SaveStateSlotsTest: " + checks + " checks passed");
    }

    private static void testEmptySlot(File root) {
        SaveStateSlots.Entry entry = SaveStateSlots.inspect(
                1, new File(root, "oracle_1.mss").getAbsolutePath());
        check(!entry.occupied, "missing state is empty");
        check("Slot 1  ·  Empty".equals(entry.title()), "empty title");
    }

    private static void testOccupiedSlot(File root) throws Exception {
        File state = new File(root, "oracle_2.mss");
        write(state, new byte[1536]);
        write(new File(state.getAbsolutePath() + ".label"),
                "Before dungeon boss\nignored".getBytes(StandardCharsets.UTF_8));

        SaveStateSlots.Entry entry = SaveStateSlots.inspect(2, state.getAbsolutePath());
        check(entry.occupied, "non-empty state is occupied");
        check(entry.size == 1536, "state size retained");
        check("Before dungeon boss".equals(entry.label), "first label line retained");
        check(entry.title().contains("1.5 KB"), "human-readable state size");
    }

    private static void testLabelLimit(File root) throws Exception {
        File state = new File(root, "oracle_3.mss");
        write(state, new byte[] { 1 });
        byte[] oversized = new byte[4097];
        write(new File(state.getAbsolutePath() + ".label"), oversized);
        SaveStateSlots.Entry entry = SaveStateSlots.inspect(3, state.getAbsolutePath());
        check(entry.label.isEmpty(), "oversized label ignored");
    }

    private static void testBuildSidecarAndWarnOnce(File root) throws Exception {
        File state = new File(root, "Oracle - Main_4.mss");
        write(state, new byte[] { 1, 2, 3 });
        check(SaveStateSlots.writeBuild(state, "b61"), "build sidecar written");
        SaveStateSlots.Entry entry = SaveStateSlots.inspect(4, state.getAbsolutePath());
        check("b61".equals(entry.build), "build sidecar read back");
        check(entry.title().contains("b61"), "title includes build");

        check(SaveStateSlots.buildMismatch("b61", "b62"), "mismatch true when different");
        check(!SaveStateSlots.buildMismatch("b61", "B61"), "mismatch case-insensitive");
        check(!SaveStateSlots.buildMismatch("", "b62"), "unknown state build does not warn");
        check(!SaveStateSlots.buildMismatch("b61", ""), "unknown rom build does not warn");

        SaveStateSlots.MismatchWarnings warnings = new SaveStateSlots.MismatchWarnings();
        check(warnings.check(entry, "b61") == null, "matching build does not warn");
        String first = warnings.check(entry, "b62");
        check(first != null && first.contains("b61") && first.contains("b62"), "first mismatch warns");
        check(warnings.check(entry, "b62") == null, "second load of same state file is quiet");
    }

    private static void testDeleteRemovesSidecars(File root) throws Exception {
        File state = new File(root, "Oracle - Main_5.mss");
        write(state, new byte[] { 9, 9 });
        write(new File(state.getAbsolutePath() + ".label"), "Boss".getBytes(StandardCharsets.UTF_8));
        SaveStateSlots.writeBuild(state, "b62");
        check(SaveStateSlots.delete(state), "delete returns true");
        check(!state.exists(), "state deleted");
        check(!new File(state.getAbsolutePath() + ".label").exists(), "label deleted");
        check(!new File(state.getAbsolutePath() + ".build").exists(), "build deleted");
    }

    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
