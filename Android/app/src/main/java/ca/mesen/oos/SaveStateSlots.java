package ca.mesen.oos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SaveStateSlots {
    static final int SLOT_COUNT = 10;

    static final class Entry {
        final int slot;
        final File file;
        final boolean occupied;
        final long size;
        final long modified;
        final String label;

        Entry(int slot, File file, boolean occupied, long size, long modified, String label) {
            this.slot = slot;
            this.file = file;
            this.occupied = occupied;
            this.size = size;
            this.modified = modified;
            this.label = label;
        }

        String title() {
            if (!occupied) {
                return "Slot " + slot + "  ·  Empty";
            }
            StringBuilder value = new StringBuilder("Slot ").append(slot);
            if (!label.isEmpty()) {
                value.append("  ·  ").append(label);
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
        if (slot < 1 || slot > SLOT_COUNT || path == null || path.isEmpty()) {
            return new Entry(slot, null, false, 0, 0, "");
        }
        File file = new File(path);
        boolean occupied = file.isFile() && file.length() > 0;
        return new Entry(
                slot,
                file,
                occupied,
                occupied ? file.length() : 0,
                occupied ? file.lastModified() : 0,
                occupied ? readLabel(new File(path + ".label")) : ""
        );
    }

    private static String readLabel(File file) {
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
