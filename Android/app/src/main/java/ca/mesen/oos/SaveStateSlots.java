package ca.mesen.oos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Save-state slots. Mesen names a state after the ROM file, not its hash:
 * {@code SaveStates/<rom base>_<slot>.mss} plus an optional {@code .label}
 * (Core/Shared/SaveStateManager.cpp GetStateFilepath, same as desktop
 * SaveStateSlotHelper). With one ROM file per channel, states therefore
 * belong to the channel and survive a ROM replace. This class adds a
 * {@code .build} sidecar (build ID + save time) so a state from an older
 * build can be flagged when it loads.
 */
final class SaveStateSlots {
    static final int SLOT_COUNT = 10;
    /** Mesen's auto-save: one past the handheld slot count (EmuApiWrapper SaveStateSlotCount = 10). */
    static final int AUTO_SLOT = SLOT_COUNT + 1;
    static final String LABEL_SUFFIX = ".label";
    static final String BUILD_SUFFIX = ".build";

    static final class Entry {
        final int slot;
        final File file;
        final boolean occupied;
        final long size;
        final long modified;
        final String label;
        /** Build ID the state was saved on ("b61"), or "" when unknown. */
        final String build;

        Entry(int slot, File file, boolean occupied, long size, long modified, String label, String build) {
            this.slot = slot;
            this.file = file;
            this.occupied = occupied;
            this.size = size;
            this.modified = modified;
            this.label = label;
            this.build = build;
        }

        String title() {
            if (!occupied) {
                return "Slot " + slot + "  ·  Empty";
            }
            StringBuilder value = new StringBuilder("Slot ").append(slot);
            if (!label.isEmpty()) {
                value.append("  ·  ").append(label);
            }
            if (!build.isEmpty()) {
                value.append("  ·  ").append(build);
            }
            value.append("\n")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(modified)))
                    .append("  ·  ")
                    .append(formatSize(size));
            return value.toString();
        }
    }

    private SaveStateSlots() {}

    static Entry inspect(int slot, String path) {
        if (slot < 1 || slot > AUTO_SLOT || path == null || path.isEmpty()) {
            return new Entry(slot, null, false, 0, 0, "", "");
        }
        File file = new File(path);
        boolean occupied = file.isFile() && file.length() > 0;
        return new Entry(
                slot,
                file,
                occupied,
                occupied ? file.length() : 0,
                occupied ? file.lastModified() : 0,
                occupied ? readFirstLine(new File(path + LABEL_SUFFIX)) : "",
                occupied ? readFirstLine(new File(path + BUILD_SUFFIX)) : ""
        );
    }

    /** Records which build wrote the state. Best effort: a missing sidecar only skips the warning. */
    static boolean writeBuild(File state, String build) {
        if (state == null || build == null || build.isEmpty()) {
            return false;
        }
        File target = new File(state.getPath() + BUILD_SUFFIX);
        File tmp = new File(state.getPath() + BUILD_SUFFIX + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write((build + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target) || (target.delete() && tmp.renameTo(target));
    }

    /** Deletes the state and its .label/.build sidecars. */
    static boolean delete(File state) {
        if (state == null) {
            return false;
        }
        new File(state.getPath() + LABEL_SUFFIX).delete();
        new File(state.getPath() + BUILD_SUFFIX).delete();
        return state.delete();
    }

    /** True when both builds are known and differ. Unknown builds never warn. */
    static boolean buildMismatch(String stateBuild, String romBuild) {
        return stateBuild != null && romBuild != null && !stateBuild.isEmpty() && !romBuild.isEmpty()
                && !stateBuild.equalsIgnoreCase(romBuild);
    }

    /** Warns once per state file version per session, then stays quiet. */
    static final class MismatchWarnings {
        private final Set<String> warned = new HashSet<>();

        /** Returns the warning text the first time, null afterwards or when builds match. */
        String check(Entry entry, String romBuild) {
            if (entry == null || entry.file == null || !buildMismatch(entry.build, romBuild)) {
                return null;
            }
            String key = entry.file.getPath() + "@" + entry.modified;
            if (!warned.add(key)) {
                return null;
            }
            return "Saved on " + entry.build + ", running " + romBuild
                    + ". Older-build states can glitch; save in-game and reload if they do.";
        }
    }

    private static String readFirstLine(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > 4096) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return "";
            }
            line = line.trim().replace('\n', ' ').replace('\r', ' ');
            return line.length() > 80 ? line.substring(0, 80) : line;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }
}
