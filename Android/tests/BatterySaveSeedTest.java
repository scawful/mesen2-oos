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
        System.out.println("BatterySaveSeedTest: " + checks + " checks passed");
    }
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
