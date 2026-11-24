package ca.mesen.oos;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ROM library with one entry per channel. A channel is the ROM title without
 * its build tag: "Oracle - Main.sfc" and "Oracle - Main b58.sfc" are both
 * channel Main. The untagged file is the channel file the host push replaces
 * in place; it keeps exactly one rollback copy, {@code <name>.sfc.prev}.
 * Tagged legacy copies are listed as older copies that one action removes.
 * A ROM named only by a build ("Oracle - b58.sfc") is a pinned channel.
 *
 * Save states and the battery save follow the ROM file name (Mesen keys them
 * by file base), so they stay with the channel across replacements.
 */
final class RomLibrary {
    static final String PREV_SUFFIX = ".prev";
    static final String SIDECAR_SUFFIX = ".build.json";
    static final List<String> CHANNEL_ORDER = Collections.unmodifiableList(Arrays.asList("Latest", "Playtest", "Main"));

    private static final String[] PREFIXES = {"Oracle - ", "Oracle-", "Oracle "};
    // A build tag: b61, l12, v0.9.0, dev, or a short hex sha that contains a digit.
    private static final Pattern TAG = Pattern.compile(
            "^(b\\d+|l\\d+|v\\d[\\w.]*|dev|(?=[0-9a-f]*[0-9])[0-9a-f]{3,8})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PINNED = Pattern.compile("^b\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_BUILD = Pattern.compile("(?:^|[-\\s])([bl]\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    static final class Name {
        final String channel;
        final String tag;

        Name(String channel, String tag) {
            this.channel = channel;
            this.tag = tag;
        }
    }

    static final class Entry {
        final String channel;
        final File file;
        final String build;
        final long date;
        final boolean pinned;
        /** Rollback copy kept by the host push, or null. */
        final File previous;
        final String previousBuild;
        /** Other ROM files of the same channel (legacy per-build names), newest first. */
        final List<File> olderCopies;

        Entry(String channel, File file, String build, long date, boolean pinned,
              File previous, String previousBuild, List<File> olderCopies) {
            this.channel = channel;
            this.file = file;
            this.build = build;
            this.date = date;
            this.pinned = pinned;
            this.previous = previous;
            this.previousBuild = previousBuild;
            this.olderCopies = Collections.unmodifiableList(olderCopies);
        }
    }

    private RomLibrary() {}

    static String baseName(File rom) {
        return BatterySaveSeed.baseName(rom);
    }

    /** "Oracle - Main b58.sfc" -> (Main, b58); "Oracle - Main.sfc" -> (Main, ""); "Oracle - b58.sfc" -> (b58, ""). */
    static Name parse(File rom) {
        String name = baseName(rom).trim();
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                name = name.substring(prefix.length()).trim();
                break;
            }
        }
        String[] parts = name.split("\\s+");
        if (parts.length > 1 && TAG.matcher(parts[parts.length - 1]).matches()) {
            String channel = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
            return new Name(channel, parts[parts.length - 1]);
        }
        return new Name(name, "");
    }

    static File previous(File rom) {
        return new File(rom.getPath() + PREV_SUFFIX);
    }

    static File sidecar(File rom) {
        File parent = rom.getParentFile();
        return new File(parent, baseName(rom) + SIDECAR_SUFFIX);
    }

    static File previousSidecar(File rom) {
        return new File(sidecar(rom).getPath() + PREV_SUFFIX);
    }

    /** One entry per channel across all roots, in display order. */
    static List<Entry> scan(File[] roots) {
        Map<String, List<File>> groups = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        for (File rom : RomFiles.discover(roots)) {
            Name n = parse(rom);
            String key = n.channel.toLowerCase(Locale.ROOT);
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
                display.put(key, n.channel);
            }
            groups.get(key).add(rom);
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<File>> g : groups.entrySet()) {
            List<File> files = new ArrayList<>(g.getValue());
            // Newest first; the untagged channel file always wins.
            Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            File primary = files.get(0);
            for (File f : files) {
                if (parse(f).tag.isEmpty()) {
                    primary = f;
                    break;
                }
            }
            files.remove(primary);
            String channel = display.get(g.getKey());
            File prev = previous(primary);
            boolean hasPrev = prev.isFile() && prev.length() > 0;
            entries.add(new Entry(channel, primary, buildId(primary), buildDate(primary),
                    PINNED.matcher(channel).matches(),
                    hasPrev ? prev : null,
                    hasPrev ? buildIdOf(prev, previousSidecar(primary)) : "",
                    files));
        }
        Collections.sort(entries, RomLibrary::compare);
        return entries;
    }

    private static int rank(Entry e) {
        for (int i = 0; i < CHANNEL_ORDER.size(); i++) {
            if (CHANNEL_ORDER.get(i).equalsIgnoreCase(e.channel)) return i;
        }
        return e.pinned ? CHANNEL_ORDER.size() : CHANNEL_ORDER.size() + 1;
    }

    private static int compare(Entry a, Entry b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        if (a.pinned && b.pinned) {
            return Integer.compare(Integer.parseInt(b.channel.substring(1)), Integer.parseInt(a.channel.substring(1)));
        }
        return Long.compare(b.date, a.date);
    }

    // ---- build identity -------------------------------------------------

    private static final Map<String, String> BUILD_CACHE = new HashMap<>();

    /** Build ID for a library ROM: sidecar, else ROM version line, else filename tag, else "". */
    static String buildId(File rom) {
        return buildIdOf(rom, sidecar(rom));
    }

    static String buildIdOf(File rom, File sidecar) {
        String key = rom.getPath() + "|" + rom.length() + "|" + rom.lastModified()
                + "|" + sidecar.length() + "|" + sidecar.lastModified();
        synchronized (BUILD_CACHE) {
            String cached = BUILD_CACHE.get(key);
            if (cached != null) return cached;
        }
        String id = fromSidecar(sidecar);
        if (id.isEmpty()) id = fromVersionText(readVersionLine(rom));
        if (id.isEmpty()) {
            String tag = parse(rom.getName().endsWith(PREV_SUFFIX)
                    ? new File(rom.getPath().substring(0, rom.getPath().length() - PREV_SUFFIX.length()))
                    : rom).tag;
            id = tag.matches("(?i)[bl]\\d+") ? tag.toLowerCase(Locale.ROOT) : "";
        }
        synchronized (BUILD_CACHE) {
            if (BUILD_CACHE.size() > 64) BUILD_CACHE.clear();
            BUILD_CACHE.put(key, id);
        }
        return id;
    }

    /** Date shown for a build: the ROM file time, which is the push time on the device. */
    static long buildDate(File rom) {
        return rom.lastModified();
    }

    /** {@code {"build": 61}} or {@code {"build": "b61"}} -> "b61". */
    static String fromSidecar(File sidecar) {
        if (sidecar == null || !sidecar.isFile() || sidecar.length() <= 0 || sidecar.length() > 64 * 1024) {
            return "";
        }
        try {
            byte[] raw = new byte[(int) sidecar.length()];
            try (FileInputStream in = new FileInputStream(sidecar)) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            Object build = MiniJson.object(MiniJson.parse(new String(raw, StandardCharsets.UTF_8))).get("build");
            if (build instanceof Number) return "b" + ((Number) build).longValue();
            if (build == null) return "";
            String s = String.valueOf(build).trim();
            return s.matches("\\d+") ? "b" + s : s;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** "v0.9.0-b61" -> "b61"; "" when no build number is present. */
    static String fromVersionText(String text) {
        if (text == null) return "";
        Matcher m = VERSION_BUILD.matcher(text.trim());
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    // Message $C7 line 2 holds "v<version>-b<build>" (oracle Scripts/Build/version_stamp.py).
    static final int VERSION_MESSAGE = 0xC7;
    static final int TEXT_REGION_PC = 0x0E0000;
    static final int TEXT_REGION_SIZE = 0x10000;
    static final int CMD_LENGTHS_PC = 0x07536B;
    private static final int LINE2 = 0x75;
    private static final int LINE3 = 0x76;
    private static final int END = 0x7F;
    private static final int BANK = 0x80;

    /** Reads the in-game version line from a ROM file, or "" when absent. */
    static String readVersionLine(File rom) {
        if (rom == null || !rom.isFile() || rom.length() < TEXT_REGION_PC + 0x100) return "";
        long header = rom.length() % 1024 == 512 ? 512 : 0;
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            byte[] lengths = new byte[0x80];
            in.seek(header + CMD_LENGTHS_PC);
            in.readFully(lengths);
            int size = (int) Math.min(TEXT_REGION_SIZE, rom.length() - header - TEXT_REGION_PC);
            byte[] region = new byte[size];
            in.seek(header + TEXT_REGION_PC);
            in.readFully(region);
            return decodeVersionLine(lengths, region);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Walks text region 1 the way CreateMessagePointers does (commands $67-$7F
     * take their length from the table) to message $C7, then decodes the bytes
     * between its [2] and [3] line commands.
     */
    static String decodeVersionLine(byte[] cmdLengths, byte[] region) {
        int pc = 0;
        int index = 0;
        int start = 0;
        int end = -1;
        while (pc < region.length) {
            int b = region[pc] & 0xFF;
            if (b == BANK) return "";
            if (b == END) {
                if (index == VERSION_MESSAGE) {
                    end = pc;
                    break;
                }
                index++;
                pc++;
                start = pc;
                continue;
            }
            pc += cmdLength(cmdLengths, b);
        }
        if (end < 0) return "";
        int l2 = -1;
        int l3 = -1;
        int i = start;
        while (i < end) {
            int b = region[i] & 0xFF;
            if (b == LINE2 && l2 < 0) l2 = i;
            else if (b == LINE3 && l3 < 0) l3 = i;
            i += cmdLength(cmdLengths, b);
        }
        if (l2 < 0 || l3 <= l2) return "";
        StringBuilder out = new StringBuilder();
        for (int p = l2 + 1; p < l3; p++) {
            out.append(decodeChar(region[p] & 0xFF));
        }
        return out.toString().trim();
    }

    private static int cmdLength(byte[] lengths, int b) {
        if (b >= 0x67 && b < 0x80) {
            int n = lengths[b] & 0xFF;
            return n == 0 ? 1 : n;
        }
        return 1;
    }

    private static String decodeChar(int b) {
        if (b <= 0x19) return String.valueOf((char) ('A' + b));
        if (b <= 0x33) return String.valueOf((char) ('a' + b - 0x1A));
        if (b <= 0x3D) return String.valueOf((char) ('0' + b - 0x34));
        if (b == 0x40) return "-";
        if (b == 0x41) return ".";
        if (b == 0x59 || b >= 0x88) return " "; // space, or a dictionary run (the stamp only uses spaces)
        return "";
    }

    // ---- file actions -------------------------------------------------

    /** Swaps the channel file with its .prev copy (and the sidecars). Returns null or the problem. */
    static String rollback(Entry entry) {
        if (entry.previous == null || !entry.previous.isFile()) return "No previous build kept for " + entry.channel;
        String problem = swap(entry.file, entry.previous);
        if (problem != null) return problem;
        File side = sidecar(entry.file);
        File prevSide = previousSidecar(entry.file);
        if (side.isFile() && prevSide.isFile()) {
            swap(side, prevSide);
        } else if (side.isFile()) {
            side.renameTo(prevSide);
        } else if (prevSide.isFile()) {
            prevSide.renameTo(side);
        }
        synchronized (BUILD_CACHE) {
            BUILD_CACHE.clear();
        }
        return null;
    }

    private static String swap(File a, File b) {
        File tmp = new File(a.getPath() + ".swap");
        if (tmp.exists() && !tmp.delete()) return "Could not clear " + tmp.getName();
        if (!a.renameTo(tmp)) return "Could not move " + a.getName();
        if (!b.renameTo(a)) {
            tmp.renameTo(a);
            return "Could not move " + b.getName();
        }
        if (!tmp.renameTo(b)) return "Rolled back, but the old file stayed as " + tmp.getName();
        return null;
    }

    /** Result of {@link #removeOlderCopies}. */
    static final class Cleanup {
        int removed;
        int failed;
        int statesMoved;
        int statesKept;
        boolean saveMoved;

        String summary(String channel) {
            StringBuilder s = new StringBuilder();
            s.append("Removed ").append(removed).append(removed == 1 ? " older copy" : " older copies")
                    .append(" of ").append(channel);
            if (failed > 0) s.append(" · ").append(failed).append(" read-only");
            if (statesMoved > 0) s.append(" · ").append(statesMoved).append(" states moved to the channel");
            if (statesKept > 0) s.append(" · ").append(statesKept).append(" states kept under the old name (slot taken)");
            if (saveMoved) s.append(" · battery save moved");
            return s.toString();
        }
    }

    /**
     * Deletes the entry's older copies. Before each delete, its save states
     * move to the same slot of the channel when that slot is free (otherwise
     * they stay under the old name), and the newest older battery save becomes
     * the channel's save when the channel has none.
     */
    static Cleanup removeOlderCopies(Entry entry, File statesDir, File savesDir) {
        Cleanup result = new Cleanup();
        String channelBase = baseName(entry.file);
        for (File old : entry.olderCopies) {
            String oldBase = baseName(old);
            if (statesDir != null && !oldBase.equals(channelBase)) {
                for (int slot = 1; slot <= SaveStateSlots.AUTO_SLOT; slot++) {
                    File src = new File(statesDir, oldBase + "_" + slot + ".mss");
                    if (!src.isFile()) continue;
                    File dst = new File(statesDir, channelBase + "_" + slot + ".mss");
                    if (dst.exists() || !src.renameTo(dst)) {
                        result.statesKept++;
                        continue;
                    }
                    for (String suffix : new String[]{SaveStateSlots.LABEL_SUFFIX, SaveStateSlots.BUILD_SUFFIX}) {
                        new File(src.getPath() + suffix).renameTo(new File(dst.getPath() + suffix));
                    }
                    result.statesMoved++;
                }
            }
            if (savesDir != null && !oldBase.equals(channelBase)) {
                File srcSave = new File(savesDir, oldBase + ".srm");
                File dstSave = new File(savesDir, channelBase + ".srm");
                if (srcSave.isFile() && !dstSave.exists() && srcSave.renameTo(dstSave)) {
                    result.saveMoved = true;
                }
            }
            if (old.delete()) {
                result.removed++;
                previous(old).delete();
                sidecar(old).delete();
                File seed = BatterySaveSeed.sidecar(old);
                if (seed != null) seed.delete();
            } else {
                result.failed++;
            }
        }
        return result;
    }
}
