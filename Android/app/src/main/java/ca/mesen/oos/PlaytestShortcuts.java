package ca.mesen.oos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * File-backed playtest shortcut cards for instant jump-in testing on handhelds.
 *
 * <p>Cards live in {@code Playtest/<id>/} under the app's external files dir
 * ({@code /storage/emulated/0/Android/data/ca.mesen.oos/files/Playtest/}) with
 * fallback to internal {@code files/Playtest/}. Each folder contains a UTF-8
 * {@code card.json} alongside optional {@code thumb.png}, {@code state.mss},
 * and/or a custom {@code .srm} battery save.
 *
 * <p>Pure Java (no Android framework imports) so host unit tests run directly
 * with {@code javac}/{@code java}.
 */
public final class PlaytestShortcuts {
    public static final String FOLDER_NAME = "Playtest";
    public static final String CARD_FILE = "card.json";
    public static final String DEFAULT_STATE = "state.mss";
    public static final String DEFAULT_THUMB = "thumb.png";
    public static final String USER_BACKUP_SUFFIX = ".user-backup";
    public static final String DISPLAY_PATH = "/Android/data/ca.mesen.oos/files/Playtest/";
    public static final int DEFAULT_ORDER = 100;
    static final int MAX_CARD_JSON_BYTES = 256 * 1024;

    private PlaytestShortcuts() {}

    /** Immutable descriptor for one discovered playtest shortcut card. */
    public static final class Card {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String badge;
        public final int order;
        public final File directory;
        public final String stateName;
        public final boolean stateExplicit;
        public final File stateFile;
        public final String thumbName;
        public final File thumbFile;
        public final String srmName;
        public final File srmFile;
        public final String rom;
        public final long thumbModified;

        Card(
                String id,
                String title,
                String subtitle,
                String badge,
                int order,
                File directory,
                String stateName,
                boolean stateExplicit,
                File stateFile,
                String thumbName,
                File thumbFile,
                String srmName,
                File srmFile,
                String rom) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.order = order;
            this.directory = directory;
            this.stateName = stateName;
            this.stateExplicit = stateExplicit;
            this.stateFile = stateFile;
            this.thumbName = thumbName;
            this.thumbFile = thumbFile;
            this.srmName = srmName;
            this.srmFile = srmFile;
            this.rom = rom;
            if (thumbFile != null && thumbFile.isFile()) {
                this.thumbModified = thumbFile.lastModified();
            } else if (stateFile != null && stateFile.isFile()) {
                this.thumbModified = stateFile.lastModified();
            } else {
                this.thumbModified = 0L;
            }
        }

        public boolean hasThumb() {
            return thumbFile != null && thumbFile.isFile();
        }

        public boolean hasState() {
            return stateFile != null && stateFile.isFile();
        }

        public boolean hasSrm() {
            return srmFile != null && srmFile.isFile();
        }

        public boolean hasRom() {
            return !rom.isEmpty();
        }
    }

    /** Result of launching a playtest shortcut card. */
    public static final class LaunchResult {
        public final boolean ok;
        public final String message;
        public final String romPath;
        public final boolean backedUpSrm;

        private LaunchResult(boolean ok, String message, String romPath, boolean backedUpSrm) {
            this.ok = ok;
            this.message = message;
            this.romPath = romPath;
            this.backedUpSrm = backedUpSrm;
        }

        static LaunchResult success(String message, String romPath, boolean backedUpSrm) {
            return new LaunchResult(true, message, romPath, backedUpSrm);
        }

        static LaunchResult failure(String message) {
            return new LaunchResult(false, message, null, false);
        }
    }

    /** Native/host operations invoked off the main thread while launching a card. */
    public interface Launcher {
        String currentRomPath();
        File[] romRoots();
        File savesDir();
        boolean bootRom(String path);
        boolean loadStateFile(String path);
        boolean writeSram(byte[] data);
        void settleAfterRomLoad();
    }

    /** Returns ordered Playtest roots: external files dir first, internal fallback second. */
    public static File[] roots(File externalFilesDir, File internalFilesDir) {
        List<File> out = new ArrayList<>(2);
        if (externalFilesDir != null) {
            out.add(new File(externalFilesDir, FOLDER_NAME));
        }
        if (internalFilesDir != null) {
            out.add(new File(internalFilesDir, FOLDER_NAME));
        }
        return out.toArray(new File[0]);
    }

    /** Primary Playtest directory (external when available, else internal). */
    public static File primaryDir(File externalFilesDir, File internalFilesDir) {
        File base = externalFilesDir != null ? externalFilesDir : internalFilesDir;
        return base == null ? null : new File(base, FOLDER_NAME);
    }

    /** Discovers and sorts all valid shortcut cards across the given roots. */
    public static List<Card> listCards(File[] roots) {
        if (roots == null || roots.length == 0) {
            return Collections.emptyList();
        }
        List<Card> cards = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenDirs = new HashSet<>();

        for (File root : roots) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            String rootKey = canonicalKey(root);
            if (!seenDirs.add(rootKey)) {
                continue;
            }
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File dir : children) {
                if (!dir.isDirectory()) {
                    continue;
                }
                File cardJson = new File(dir, CARD_FILE);
                if (!cardJson.isFile() || cardJson.length() <= 0 || cardJson.length() > MAX_CARD_JSON_BYTES) {
                    continue;
                }
                try {
                    String text = new String(readBytes(cardJson, MAX_CARD_JSON_BYTES), StandardCharsets.UTF_8);
                    Card card = parseCard(dir, text);
                    if (card == null) {
                        continue;
                    }
                    String idKey = card.id.toLowerCase(Locale.ROOT);
                    if (seenIds.add(idKey)) {
                        cards.add(card);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Ignore one malformed card folder so other cards stay usable.
                }
            }
        }

        Collections.sort(cards, PlaytestShortcuts::compareCards);
        return cards;
    }

    /** Parses one {@code card.json} payload for a given card folder. Returns null if invalid. */
    static Card parseCard(File directory, String jsonText) {
        if (directory == null || jsonText == null || jsonText.trim().isEmpty()) {
            return null;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(jsonText);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<String, Object> obj = MiniJson.object(parsed);

        String title = cleanString(obj.get("title"));
        if (title.isEmpty()) {
            return null;
        }
        String id = cleanString(obj.get("id"));
        if (id.isEmpty()) {
            id = directory.getName();
        }
        String subtitle = cleanString(obj.get("subtitle"));
        String badge = cleanString(obj.get("badge"));
        int order = parseOrder(obj.get("order"));

        boolean stateExplicit = false;
        String stateName;
        if (obj.containsKey("state")) {
            stateName = cleanString(obj.get("state"));
            stateExplicit = !stateName.isEmpty();
        } else {
            stateName = DEFAULT_STATE;
        }
        File stateFile = existingInside(directory, stateName);

        String thumbName;
        if (obj.containsKey("thumb")) {
            thumbName = cleanString(obj.get("thumb"));
        } else {
            thumbName = DEFAULT_THUMB;
        }
        File thumbFile = existingInside(directory, thumbName);

        String srmName = cleanString(obj.get("srm"));
        File srmFile = existingInside(directory, srmName);

        String rom = cleanString(obj.get("rom"));

        return new Card(
                id,
                title,
                subtitle,
                badge,
                order,
                directory,
                stateName,
                stateExplicit,
                stateFile,
                thumbName,
                thumbFile,
                srmName,
                srmFile,
                rom);
    }

    /** Returns the one-time backup path for a ROM's live {@code .srm} file. */
    public static File userBackupFile(File liveSrm) {
        return new File(liveSrm.getPath() + USER_BACKUP_SUFFIX);
    }

    /** Resolves a card's {@code rom} specification across the configured ROM roots. */
    public static File resolveRom(String romSpec, File[] romRoots) {
        if (romSpec == null) {
            return null;
        }
        String trimmed = romSpec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Reject parent traversal in relative names.
        if (trimmed.contains("..")) {
            return null;
        }
        if (trimmed.startsWith("/")) {
            File abs = new File(trimmed);
            return abs.isFile() ? abs : null;
        }
        if (romRoots == null) {
            return null;
        }
        List<File> discovered = RomFiles.discover(romRoots);
        for (File candidate : discovered) {
            if (candidate.getName().equals(trimmed)) {
                return candidate;
            }
        }
        for (File candidate : discovered) {
            if (candidate.getName().equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        for (File root : romRoots) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            File direct = existingInside(root, trimmed);
            if (direct != null) {
                return direct;
            }
        }
        return null;
    }

    /**
     * Validates files, safely backs up and swaps {@code .srm} if requested, switches
     * ROM if needed, and loads {@code state.mss} or reloads the ROM.
     *
     * <p>Must be called off the main thread.
     */
    public static LaunchResult launchCard(Card card, Launcher launcher) {
        if (card == null || launcher == null) {
            return LaunchResult.failure("No playtest shortcut selected.");
        }

        String currentPath = launcher.currentRomPath();
        boolean romSpecified = !card.rom.isEmpty();
        File targetRom;
        if (romSpecified) {
            targetRom = resolveRom(card.rom, launcher.romRoots());
            if (targetRom == null || !targetRom.isFile()) {
                return LaunchResult.failure("ROM not found: " + card.rom);
            }
        } else {
            if (currentPath == null || currentPath.isEmpty()) {
                return LaunchResult.failure("Load a ROM first to launch this shortcut.");
            }
            targetRom = new File(currentPath);
            if (!targetRom.isFile()) {
                return LaunchResult.failure("Current ROM file is missing: " + targetRom.getName());
            }
        }

        File srmFile = null;
        if (!card.srmName.isEmpty()) {
            srmFile = resolveInside(card.directory, card.srmName);
            if (srmFile == null || !srmFile.isFile() || srmFile.length() == 0) {
                return LaunchResult.failure("Save file missing: " + card.srmName);
            }
            if (srmFile.length() > BatterySaveSeed.MAX_SRM_BYTES) {
                return LaunchResult.failure("Save file is too large: " + card.srmName);
            }
        }

        File stateFile = null;
        if (!card.stateName.isEmpty()) {
            File candidate = resolveInside(card.directory, card.stateName);
            if (candidate != null && candidate.isFile() && candidate.length() > 0) {
                stateFile = candidate;
            } else if (card.stateExplicit) {
                return LaunchResult.failure("State file missing: " + card.stateName);
            }
        }

        if (stateFile == null && srmFile == null && !romSpecified) {
            return LaunchResult.failure("Shortcut has no state, save, or ROM: " + card.title);
        }

        boolean sameRom = currentPath != null && !currentPath.isEmpty()
                && canonicalKey(targetRom).equals(canonicalKey(new File(currentPath)));

        boolean backedUpSrm = false;
        if (srmFile != null) {
            File savesDir = launcher.savesDir();
            if (savesDir == null || (!savesDir.isDirectory() && !savesDir.mkdirs())) {
                return LaunchResult.failure("Could not access Saves folder.");
            }
            File liveSrm = BatterySaveSeed.target(targetRom, savesDir);
            File backup = userBackupFile(liveSrm);
            try {
                if (liveSrm.isFile() && !backup.exists()) {
                    copyAtomic(liveSrm, backup);
                    backedUpSrm = true;
                }
                byte[] srmBytes = readBytes(srmFile, (int) BatterySaveSeed.MAX_SRM_BYTES);
                if (sameRom) {
                    // Mesen calls SaveBattery() when unloading the currently running ROM
                    // inside LoadRom(). Updating live SRAM before reloading ensures the
                    // flush preserves the shortcut's .srm rather than clobbering it.
                    launcher.writeSram(srmBytes);
                }
                writeAtomic(liveSrm, srmBytes);
            } catch (IOException e) {
                return LaunchResult.failure("Could not install save: " + e.getMessage());
            }
        }

        if (!sameRom) {
            if (!launcher.bootRom(targetRom.getAbsolutePath())) {
                return LaunchResult.failure("Could not load ROM: " + targetRom.getName());
            }
            if (stateFile != null) {
                launcher.settleAfterRomLoad();
                if (!launcher.loadStateFile(stateFile.getAbsolutePath())) {
                    return LaunchResult.failure("Loaded " + targetRom.getName()
                            + ", but state failed: " + stateFile.getName());
                }
            }
        } else {
            if (stateFile != null) {
                if (!launcher.loadStateFile(stateFile.getAbsolutePath())) {
                    return LaunchResult.failure("Could not load state: " + stateFile.getName());
                }
            } else {
                // Reload ROM so swapped .srm (or explicit ROM shortcut) takes effect immediately.
                if (!launcher.bootRom(targetRom.getAbsolutePath())) {
                    return LaunchResult.failure("Could not reload ROM: " + targetRom.getName());
                }
            }
        }

        String msg = backedUpSrm
                ? "Launched " + card.title + " (original save backed up)"
                : "Launched " + card.title;
        return LaunchResult.success(msg, targetRom.getAbsolutePath(), backedUpSrm);
    }

    private static int compareCards(Card a, Card b) {
        int byOrder = Integer.compare(a.order, b.order);
        if (byOrder != 0) {
            return byOrder;
        }
        int byTitle = a.title.compareToIgnoreCase(b.title);
        if (byTitle != 0) {
            return byTitle;
        }
        return a.id.compareToIgnoreCase(b.id);
    }

    private static String cleanString(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private static int parseOrder(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return DEFAULT_ORDER;
            }
        }
        return DEFAULT_ORDER;
    }

    /** Resolves {@code relativeName} strictly inside {@code parent}, blocking path traversal. */
    static File resolveInside(File parent, String relativeName) {
        if (parent == null || relativeName == null) {
            return null;
        }
        String name = relativeName.trim();
        if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
            return null;
        }
        try {
            File canonicalParent = parent.getCanonicalFile();
            File candidate = new File(canonicalParent, name).getCanonicalFile();
            String parentPath = canonicalParent.getPath();
            String prefix = parentPath.endsWith(File.separator) ? parentPath : parentPath + File.separator;
            if (!candidate.getPath().startsWith(prefix)) {
                return null;
            }
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    private static File existingInside(File parent, String relativeName) {
        File f = resolveInside(parent, relativeName);
        return (f != null && f.isFile() && f.length() > 0) ? f : null;
    }

    private static String canonicalKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static byte[] readBytes(File file, int maxBytes) throws IOException {
        if (!file.isFile() || file.length() > maxBytes) {
            throw new IOException("Missing or oversized file: " + file.getName());
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), maxBytes))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > maxBytes) {
                    throw new IOException("Oversized file: " + file.getName());
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void copyAtomic(File src, File dst) throws IOException {
        byte[] data = readBytes(src, (int) BatterySaveSeed.MAX_SRM_BYTES);
        writeAtomic(dst, data);
    }

    private static void writeAtomic(File dst, byte[] data) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create folder for " + dst.getName());
        }
        File tmp = new File(dst.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (tmp.length() != data.length) {
            tmp.delete();
            throw new IOException("Short write for " + dst.getName());
        }
        if (!tmp.renameTo(dst)) {
            if (!dst.delete() || !tmp.renameTo(dst)) {
                tmp.delete();
                throw new IOException("Could not replace " + dst.getName());
            }
        }
    }
}
