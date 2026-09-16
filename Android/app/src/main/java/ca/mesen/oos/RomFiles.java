package ca.mesen.oos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Lists distinct ROM paths without reading ROM contents on the UI thread. */
final class RomFiles {
    private RomFiles() {}

    static List<File> discover(File[] roots) {
        LinkedHashMap<String, File> unique = new LinkedHashMap<>();
        for (File root : roots) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            File[] files = root.listFiles(file -> {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                return file.isFile() && (lower.endsWith(".sfc") || lower.endsWith(".smc"));
            });
            if (files == null) {
                continue;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                String key;
                try {
                    key = file.getCanonicalPath();
                } catch (IOException e) {
                    key = file.getAbsolutePath();
                }
                // Different builds commonly share both a filename and a size.
                // Collapse only aliases of the same path, keeping source labels.
                if (!unique.containsKey(key)) {
                    unique.put(key, file);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }
}
