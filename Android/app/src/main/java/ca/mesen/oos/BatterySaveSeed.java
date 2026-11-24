package ca.mesen.oos;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-time import of a playtest battery save. Test ROM folders ship
 * "Name.sfc" beside "Name.srm"; Mesen reads battery RAM from its own
 * Saves/Name.srm. When that file does not exist yet, copy the sidecar in.
 * An existing Mesen save is never overwritten.
 */
final class BatterySaveSeed {
    enum Status { NO_SIDECAR, ALREADY_PRESENT, SEEDED, FAILED }

    static final long MAX_SRM_BYTES = 128 * 1024;

    private BatterySaveSeed() {}

    static String baseName(File rom) {
        String name = rom.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static File sidecar(File rom) {
        File parent = rom.getParentFile();
        return parent == null ? null : new File(parent, baseName(rom) + ".srm");
    }

    static File target(File rom, File savesDir) {
        return new File(savesDir, baseName(rom) + ".srm");
    }

    static Status seed(File rom, File savesDir) {
        if (rom == null || savesDir == null) {
            return Status.FAILED;
        }
        File source = sidecar(rom);
        File dest = target(rom, savesDir);
        if (dest.isFile()) {
            return Status.ALREADY_PRESENT;
        }
        if (!isValidSidecar(source)) {
            return Status.NO_SIDECAR;
        }
        if (source.getAbsoluteFile().equals(dest.getAbsoluteFile())) {
            return Status.ALREADY_PRESENT;
        }
        if (!savesDir.isDirectory() && !savesDir.mkdirs()) {
            return Status.FAILED;
        }
        return copyAtomic(source, dest, ".seed-tmp") ? Status.SEEDED : Status.FAILED;
    }

    /**
     * Unconditionally seeds {@code Saves/<base>.srm} from a valid sidecar.
     * If {@code Saves/<base>.srm} already exists, backs it up atomically to
     * {@code Saves/<base>.srm.bak} before overwriting.
     */
    static Status forceSeed(File rom, File savesDir) {
        if (rom == null || savesDir == null) {
            return Status.FAILED;
        }
        File source = sidecar(rom);
        File dest = target(rom, savesDir);
        if (!isValidSidecar(source)) {
            return Status.NO_SIDECAR;
        }
        if (source.getAbsoluteFile().equals(dest.getAbsoluteFile())) {
            return Status.ALREADY_PRESENT;
        }
        if (!savesDir.isDirectory() && !savesDir.mkdirs()) {
            return Status.FAILED;
        }
        if (dest.isFile()) {
            File backup = new File(savesDir, dest.getName() + ".bak");
            if (!copyAtomic(dest, backup, ".bak-tmp")) {
                return Status.FAILED;
            }
        }
        return copyAtomic(source, dest, ".seed-tmp") ? Status.SEEDED : Status.FAILED;
    }

    private static boolean isValidSidecar(File source) {
        return source != null && source.isFile() && source.length() > 0
                && source.length() <= MAX_SRM_BYTES;
    }

    private static boolean copyAtomic(File source, File dest, String tempSuffix) {
        File tmp = new File(dest.getParentFile(), dest.getName() + tempSuffix);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (tmp.length() != source.length() || !tmp.renameTo(dest)) {
            tmp.delete();
            return false;
        }
        return true;
    }
}
