package ca.mesen.oos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Standalone host test: javac RomFiles.java RomFilesTest.java, then run this main. */
public final class RomFilesTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("oracle-rom-files-test-");
        try {
            Path app = Files.createDirectory(temp.resolve("app"));
            Path drop = Files.createDirectory(temp.resolve("drop"));
            check(RomFiles.discover(new File[] {null, app.toFile(),
                    temp.resolve("missing").toFile()}).isEmpty(), "Empty/missing roots");

            Path older = app.resolve("oos168x.sfc");
            Path newer = drop.resolve("oos168x.sfc");
            Files.write(older, "old!".getBytes(StandardCharsets.UTF_8));
            Files.write(newer, "new!".getBytes(StandardCharsets.UTF_8));
            List<File> builds = RomFiles.discover(new File[] {app.toFile(), drop.toFile()});
            check(builds.size() == 2, "Same name and length must preserve different builds");
            check(builds.get(0).equals(older.toFile()) && builds.get(1).equals(newer.toFile()),
                    "Preserve root order and original source paths");

            List<File> repeated = RomFiles.discover(new File[] {
                    app.toFile(), app.resolve(".").toFile(), drop.toFile()});
            check(repeated.size() == 2, "Repeated/aliased roots must not duplicate ROMs");

            Files.createSymbolicLink(drop.resolve("alias.sfc"), older);
            check(RomFiles.discover(new File[] {app.toFile(), drop.toFile()}).size() == 2,
                    "A symlink alias of an existing ROM must not add another entry");

            Files.write(drop.resolve("Alpha.SMC"), new byte[] {1});
            Files.write(drop.resolve("readme.txt"), new byte[] {2});
            Files.createDirectory(drop.resolve("folder.sfc"));
            List<File> filtered = RomFiles.discover(new File[] {app.toFile(), drop.toFile()});
            check(filtered.size() == 3, "Accept uppercase SMC, reject folders and unrelated files");
            check(filtered.get(1).getName().equals("Alpha.SMC"),
                    "Sort ROM names within each root without moving roots");
            check(RomFiles.discover(new File[] {older.toFile()}).isEmpty(),
                    "A file passed as a root is ignored");
            System.out.println("RomFilesTest: " + checks + " checks passed");
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
