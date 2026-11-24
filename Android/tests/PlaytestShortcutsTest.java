package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Standalone host test for PlaytestShortcuts discovery, sorting, backup, and launch logic. */
public final class PlaytestShortcutsTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeLauncher implements PlaytestShortcuts.Launcher {
        String currentRom;
        File[] romRoots;
        File savesDir;
        boolean bootResult = true;
        boolean loadStateResult = true;
        final List<String> calls = new ArrayList<>();
        byte[] lastWrittenSram;

        @Override
        public String currentRomPath() {
            return currentRom;
        }

        @Override
        public File[] romRoots() {
            return romRoots;
        }

        @Override
        public File savesDir() {
            return savesDir;
        }

        @Override
        public boolean bootRom(String path) {
            calls.add("boot:" + new File(path).getName());
            if (bootResult) {
                currentRom = path;
            }
            return bootResult;
        }

        @Override
        public boolean loadStateFile(String path) {
            calls.add("state:" + new File(path).getName());
            return loadStateResult;
        }

        @Override
        public boolean writeSram(byte[] data) {
            calls.add("sram:" + data.length);
            lastWrittenSram = Arrays.copyOf(data, data.length);
            return true;
        }

        @Override
        public void settleAfterRomLoad() {
            calls.add("settle");
        }
    }

    private static File writeCard(Path root, String folder, String json) throws Exception {
        Path dir = Files.createDirectories(root.resolve(folder));
        Files.write(dir.resolve("card.json"), json.getBytes(StandardCharsets.UTF_8));
        return dir.toFile();
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("playtest-shortcuts-test-");
        try {
            Path extPlaytest = Files.createDirectory(temp.resolve("ext-playtest"));
            Path intPlaytest = Files.createDirectory(temp.resolve("int-playtest"));
            Path romsDir = Files.createDirectory(temp.resolve("roms"));
            Path savesDir = Files.createDirectory(temp.resolve("saves"));

            // 1. Empty / missing roots
            check(PlaytestShortcuts.listCards(new File[] {null, temp.resolve("missing").toFile(), extPlaytest.toFile()}).isEmpty(),
                    "Empty or missing roots return empty list");

            // 2. Sorting, defaults, external precedence over internal, and malformed card skipping
            File eponaDir = writeCard(extPlaytest, "epona_ranch",
                    "{\"title\":\"Test Epona Ranch\",\"subtitle\":\"Area $00 · Gallop, jump\",\"badge\":\"NEW\",\"order\":10}");
            Files.write(new File(eponaDir, "state.mss").toPath(), "MSS-EPONA".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(eponaDir, "thumb.png").toPath(), "PNG-EPONA".getBytes(StandardCharsets.UTF_8));

            // Same id in internal dir should be shadowed by external dir
            writeCard(intPlaytest, "epona_ranch",
                    "{\"title\":\"Old Internal Epona\",\"order\":1}");

            // Internal-only card should still appear
            File boatDir = writeCard(intPlaytest, "king_boat",
                    "{\"id\":\"boat_card\",\"title\":\"King of Red Lions\",\"subtitle\":\"Lyra Sea\",\"badge\":\"PREVIEW\",\"order\":20,\"state\":\"boat.mss\",\"thumb\":\"shot.png\"}");
            Files.write(new File(boatDir, "boat.mss").toPath(), "MSS-BOAT".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(boatDir, "shot.png").toPath(), "PNG-BOAT".getBytes(StandardCharsets.UTF_8));

            // Default order (100) tiebreak by title ascending
            File zetaDir = writeCard(extPlaytest, "zeta_card", "{\"title\":\"Zeta Room\"}");
            Files.write(new File(zetaDir, "state.mss").toPath(), "MSS-Z".getBytes(StandardCharsets.UTF_8));
            File alphaDir = writeCard(extPlaytest, "alpha_card", "{\"title\":\"Alpha Room\"}");
            Files.write(new File(alphaDir, "state.mss").toPath(), "MSS-A".getBytes(StandardCharsets.UTF_8));

            // Malformed and missing-title cards must be skipped cleanly
            writeCard(extPlaytest, "broken_json", "{not valid json");
            writeCard(extPlaytest, "missing_title", "{\"subtitle\":\"No title here\"}");
            writeCard(extPlaytest, "blank_title", "{\"title\":\"   \"}");

            List<PlaytestShortcuts.Card> cards = PlaytestShortcuts.listCards(
                    new File[] {extPlaytest.toFile(), extPlaytest.resolve(".").toFile(), intPlaytest.toFile()});
            check(cards.size() == 4, "Discovers 4 valid unique cards (got " + cards.size() + ")");
            check(cards.get(0).id.equals("epona_ranch") && cards.get(0).title.equals("Test Epona Ranch")
                    && cards.get(0).badge.equals("NEW") && cards.get(0).order == 10
                    && cards.get(0).hasState() && cards.get(0).hasThumb(),
                    "External card wins precedence and parses defaults");
            check(cards.get(1).id.equals("boat_card") && cards.get(1).order == 20
                    && cards.get(1).stateFile.getName().equals("boat.mss")
                    && cards.get(1).thumbFile.getName().equals("shot.png"),
                    "Internal fallback card included with custom state/thumb names");
            check(cards.get(2).title.equals("Alpha Room") && cards.get(2).order == 100,
                    "Default order 100 sorts alphabetically by title first");
            check(cards.get(3).title.equals("Zeta Room") && cards.get(3).order == 100,
                    "Second default-order card follows alphabetically");

            // 3. Path traversal rejection
            File escapeDir = writeCard(extPlaytest, "escape_test",
                    "{\"title\":\"Escape\",\"state\":\"../epona_ranch/state.mss\",\"thumb\":\"../epona_ranch/thumb.png\"}");
            PlaytestShortcuts.Card escapeCard = PlaytestShortcuts.parseCard(escapeDir,
                    new String(Files.readAllBytes(new File(escapeDir, "card.json").toPath()), StandardCharsets.UTF_8));
            check(escapeCard != null && escapeCard.stateFile == null && escapeCard.thumbFile == null,
                    "Parent directory traversal is blocked for state and thumb");

            // 4. Launch same ROM + state.mss
            File mainRom = romsDir.resolve("Oracle - Main.sfc").toFile();
            File previewRom = romsDir.resolve("oos_desert_goron_preview.sfc").toFile();
            Files.write(mainRom.toPath(), "MAIN-ROM".getBytes(StandardCharsets.UTF_8));
            Files.write(previewRom.toPath(), "PREVIEW-ROM".getBytes(StandardCharsets.UTF_8));

            FakeLauncher launcher = new FakeLauncher();
            launcher.currentRom = mainRom.getAbsolutePath();
            launcher.romRoots = new File[] {romsDir.toFile()};
            launcher.savesDir = savesDir.toFile();

            PlaytestShortcuts.LaunchResult sameRomRes = PlaytestShortcuts.launchCard(cards.get(0), launcher);
            check(sameRomRes.ok && !sameRomRes.backedUpSrm, "Same ROM + state.mss launches cleanly");
            check(launcher.calls.equals(Arrays.asList("state:state.mss")),
                    "Same ROM skips bootRom and loads state directly: " + launcher.calls);

            // 5. Launch different ROM + state.mss
            File previewCardDir = writeCard(extPlaytest, "desert_preview",
                    "{\"title\":\"Desert Goron Preview\",\"rom\":\"oos_desert_goron_preview.sfc\",\"order\":5}");
            Files.write(new File(previewCardDir, "state.mss").toPath(), "MSS-DESERT".getBytes(StandardCharsets.UTF_8));
            PlaytestShortcuts.Card previewCard = PlaytestShortcuts.parseCard(previewCardDir,
                    new String(Files.readAllBytes(new File(previewCardDir, "card.json").toPath()), StandardCharsets.UTF_8));
            launcher.calls.clear();
            PlaytestShortcuts.LaunchResult diffRomRes = PlaytestShortcuts.launchCard(previewCard, launcher);
            check(diffRomRes.ok && diffRomRes.romPath.equals(previewRom.getAbsolutePath()),
                    "Different ROM card boots preview ROM and loads state");
            check(launcher.calls.equals(Arrays.asList("boot:oos_desert_goron_preview.sfc", "settle", "state:state.mss")),
                    "Different ROM runs boot -> settle -> loadState: " + launcher.calls);

            // 6. SRM swap on same ROM without state.mss: one-time user-backup + live SRAM write + ROM reload
            launcher.currentRom = mainRom.getAbsolutePath();
            File liveMainSrm = BatterySaveSeed.target(mainRom, savesDir.toFile());
            byte[] originalUserSave = "ORIGINAL-USER-SRAM-BYTES".getBytes(StandardCharsets.UTF_8);
            Files.write(liveMainSrm.toPath(), originalUserSave);

            File srmCardDir1 = writeCard(extPlaytest, "srm_card_1",
                    "{\"title\":\"Midgame Save\",\"srm\":\"midgame.srm\"}");
            byte[] cardSave1 = "CARD-1-SRAM".getBytes(StandardCharsets.UTF_8);
            Files.write(new File(srmCardDir1, "midgame.srm").toPath(), cardSave1);
            PlaytestShortcuts.Card srmCard1 = PlaytestShortcuts.parseCard(srmCardDir1,
                    new String(Files.readAllBytes(new File(srmCardDir1, "card.json").toPath()), StandardCharsets.UTF_8));

            launcher.calls.clear();
            PlaytestShortcuts.LaunchResult srmRes1 = PlaytestShortcuts.launchCard(srmCard1, launcher);
            check(srmRes1.ok && srmRes1.backedUpSrm, "First SRM swap backs up original user .srm");
            File backupFile = PlaytestShortcuts.userBackupFile(liveMainSrm);
            check(backupFile.isFile() && Arrays.equals(Files.readAllBytes(backupFile.toPath()), originalUserSave),
                    "User backup preserves exact original .srm bytes");
            check(Arrays.equals(Files.readAllBytes(liveMainSrm.toPath()), cardSave1),
                    "Live .srm replaced with card 1 save");
            check(Arrays.equals(launcher.lastWrittenSram, cardSave1),
                    "Live emulator SRAM updated before same-ROM reload");
            check(launcher.calls.equals(Arrays.asList("sram:" + cardSave1.length, "boot:Oracle - Main.sfc")),
                    "Same-ROM SRM-only card writes SRAM and reloads ROM: " + launcher.calls);

            // Second SRM swap must NOT overwrite .user-backup!
            File srmCardDir2 = writeCard(extPlaytest, "srm_card_2",
                    "{\"title\":\"Endgame Save\",\"srm\":\"endgame.srm\"}");
            byte[] cardSave2 = "CARD-2-SRAM-LONGER".getBytes(StandardCharsets.UTF_8);
            Files.write(new File(srmCardDir2, "endgame.srm").toPath(), cardSave2);
            PlaytestShortcuts.Card srmCard2 = PlaytestShortcuts.parseCard(srmCardDir2,
                    new String(Files.readAllBytes(new File(srmCardDir2, "card.json").toPath()), StandardCharsets.UTF_8));

            launcher.calls.clear();
            PlaytestShortcuts.LaunchResult srmRes2 = PlaytestShortcuts.launchCard(srmCard2, launcher);
            check(srmRes2.ok && !srmRes2.backedUpSrm, "Second SRM swap reports backup already exists");
            check(Arrays.equals(Files.readAllBytes(backupFile.toPath()), originalUserSave),
                    "Original .srm.user-backup is NEVER overwritten by later shortcut swaps");
            check(Arrays.equals(Files.readAllBytes(liveMainSrm.toPath()), cardSave2),
                    "Live .srm replaced with card 2 save");

            // 7. Validation errors
            File missingStateDir = writeCard(extPlaytest, "missing_state",
                    "{\"title\":\"Missing State\",\"state\":\"nope.mss\"}");
            PlaytestShortcuts.Card missingStateCard = PlaytestShortcuts.parseCard(missingStateDir,
                    new String(Files.readAllBytes(new File(missingStateDir, "card.json").toPath()), StandardCharsets.UTF_8));
            PlaytestShortcuts.LaunchResult errState = PlaytestShortcuts.launchCard(missingStateCard, launcher);
            check(!errState.ok && errState.message.contains("nope.mss"), "Explicit missing state fails cleanly");

            File missingRomDir = writeCard(extPlaytest, "missing_rom",
                    "{\"title\":\"Missing ROM\",\"rom\":\"does_not_exist.sfc\"}");
            PlaytestShortcuts.Card missingRomCard = PlaytestShortcuts.parseCard(missingRomDir,
                    new String(Files.readAllBytes(new File(missingRomDir, "card.json").toPath()), StandardCharsets.UTF_8));
            PlaytestShortcuts.LaunchResult errRom = PlaytestShortcuts.launchCard(missingRomCard, launcher);
            check(!errRom.ok && errRom.message.contains("does_not_exist.sfc"), "Missing ROM fails cleanly");

            File emptyCardDir = writeCard(extPlaytest, "empty_card", "{\"title\":\"Nothing Here\"}");
            PlaytestShortcuts.Card emptyCard = PlaytestShortcuts.parseCard(emptyCardDir,
                    new String(Files.readAllBytes(new File(emptyCardDir, "card.json").toPath()), StandardCharsets.UTF_8));
            PlaytestShortcuts.LaunchResult errEmpty = PlaytestShortcuts.launchCard(emptyCard, launcher);
            check(!errEmpty.ok && errEmpty.message.contains("no state, save, or ROM"), "Card with no payload fails cleanly");

            System.out.println("PlaytestShortcutsTest: " + checks + " checks passed");
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
