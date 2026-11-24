package ca.mesen.oos;

import java.io.File;
import java.nio.file.Files;

final class BatterySaveSeedTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("seed").toFile();
        File roms = new File(root, "roms"); roms.mkdirs();
        File saves = new File(root, "Saves");
        File rom = new File(roms, "Oracle - Main b58.sfc");
        Files.write(rom.toPath(), new byte[] {1});
        check(BatterySaveSeed.seed(rom, saves) == BatterySaveSeed.Status.NO_SIDECAR, "no sidecar");
        Files.write(new File(roms, "Oracle - Main b58.srm").toPath(), new byte[] {7, 8, 9});
        check(BatterySaveSeed.seed(rom, saves) == BatterySaveSeed.Status.SEEDED, "seeded");
        File dest = new File(saves, "Oracle - Main b58.srm");
        check(dest.length() == 3, "copied bytes");
        Files.write(dest.toPath(), new byte[] {42});
        check(BatterySaveSeed.seed(rom, saves) == BatterySaveSeed.Status.ALREADY_PRESENT, "no overwrite");
        check(Files.readAllBytes(dest.toPath())[0] == 42, "existing save kept");
        check(!new File(saves, "Oracle - Main b58.srm.seed-tmp").exists(), "no temp left");

        // forceSeed when Saves/<base>.srm does not exist yet
        File romFresh = new File(roms, "Oracle - Fresh.sfc");
        Files.write(romFresh.toPath(), new byte[] {1});
        Files.write(new File(roms, "Oracle - Fresh.srm").toPath(), new byte[] {11, 22, 33});
        check(BatterySaveSeed.forceSeed(romFresh, saves) == BatterySaveSeed.Status.SEEDED, "forceSeed missing dest");
        File freshDest = new File(saves, "Oracle - Fresh.srm");
        check(java.util.Arrays.equals(Files.readAllBytes(freshDest.toPath()), new byte[] {11, 22, 33}),
                "forceSeed wrote fresh dest");
        check(!new File(saves, "Oracle - Fresh.srm.bak").exists(), "no backup when dest did not exist");

        // forceSeed overwrites existing .srm and creates atomic .bak
        File sidecar = new File(roms, "Oracle - Main b58.srm");
        Files.write(sidecar.toPath(), new byte[] {99, 100});
        check(BatterySaveSeed.forceSeed(rom, saves) == BatterySaveSeed.Status.SEEDED, "forceSeed overwrites existing");
        File bak = new File(saves, "Oracle - Main b58.srm.bak");
        check(bak.isFile() && java.util.Arrays.equals(Files.readAllBytes(bak.toPath()), new byte[] {42}),
                "forceSeed backed up previous .srm to .bak");
        check(java.util.Arrays.equals(Files.readAllBytes(dest.toPath()), new byte[] {99, 100}),
                "forceSeed replaced .srm with sidecar");
        check(!new File(saves, "Oracle - Main b58.srm.bak-tmp").exists(), "no bak temp left");
        check(!new File(saves, "Oracle - Main b58.srm.seed-tmp").exists(), "no seed temp left");

        // Second forceSeed overwrites .bak cleanly with previous .srm
        Files.write(sidecar.toPath(), new byte[] {77, 78, 79});
        check(BatterySaveSeed.forceSeed(rom, saves) == BatterySaveSeed.Status.SEEDED, "second forceSeed");
        check(java.util.Arrays.equals(Files.readAllBytes(bak.toPath()), new byte[] {99, 100}),
                "second forceSeed replaced .bak with previous .srm");
        check(java.util.Arrays.equals(Files.readAllBytes(dest.toPath()), new byte[] {77, 78, 79}),
                "second forceSeed wrote updated sidecar");

        // Missing or oversized sidecar preserves existing .srm and .bak untouched
        Files.delete(sidecar.toPath());
        check(BatterySaveSeed.forceSeed(rom, saves) == BatterySaveSeed.Status.NO_SIDECAR, "missing sidecar safe");
        check(java.util.Arrays.equals(Files.readAllBytes(dest.toPath()), new byte[] {77, 78, 79}),
                "missing sidecar keeps .srm");
        check(java.util.Arrays.equals(Files.readAllBytes(bak.toPath()), new byte[] {99, 100}),
                "missing sidecar keeps .bak");

        Files.write(sidecar.toPath(), new byte[(int) BatterySaveSeed.MAX_SRM_BYTES + 1]);
        check(BatterySaveSeed.forceSeed(rom, saves) == BatterySaveSeed.Status.NO_SIDECAR, "oversized sidecar safe");
        check(java.util.Arrays.equals(Files.readAllBytes(dest.toPath()), new byte[] {77, 78, 79}),
                "oversized sidecar keeps .srm");

        Files.write(sidecar.toPath(), new byte[0]);
        check(BatterySaveSeed.forceSeed(rom, saves) == BatterySaveSeed.Status.NO_SIDECAR, "empty sidecar safe");
        check(java.util.Arrays.equals(Files.readAllBytes(dest.toPath()), new byte[] {77, 78, 79}),
                "empty sidecar keeps .srm");

        System.out.println("BatterySaveSeedTest: " + checks + " checks passed");
    }
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
