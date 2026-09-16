package ca.mesen.oos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/** File checks shared by capture and restore; never invokes the emulator. */
final class WorkshopArtifactFiles {
    static final int MAX_FILE = 32 * 1024 * 1024;

    static File child(File parent, String name) throws IOException {
        File root = parent.getCanonicalFile();
        File file = new File(root, name).getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("Artifact path leaves its folder");
        return file;
    }

    static byte[] read(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit) throw new IOException("Missing or oversized " + file.getName());
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException("Oversized " + file.getName());
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    static void atomicWrite(File file, byte[] bytes) throws IOException {
        File temp = child(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        // Android's internal filesystem supports atomic same-directory replacement.
        if (!temp.renameTo(file)) throw new IOException("Could not finish writing " + file.getName());
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    static void verifyPng(File file) throws Exception {
        byte[] png = read(file, MAX_FILE);
        byte[] signature = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < 57) throw new IOException("Screenshot PNG is incomplete");
        for (int i = 0; i < 8; i++) if (png[i] != signature[i]) throw new IOException("Screenshot is not PNG");
        int offset = 8;
        boolean header = false, pixels = false;
        while (offset + 12 <= png.length) {
            long length = be32(png, offset);
            if (length > png.length - offset - 12) throw new IOException("Screenshot PNG is truncated");
            int n = (int)length;
            String type = new String(png, offset + 4, 4, "US-ASCII");
            CRC32 crc = new CRC32();
            crc.update(png, offset + 4, n + 4);
            if (crc.getValue() != be32(png, offset + 8 + n)) throw new IOException("Screenshot PNG checksum failed");
            if (!header) {
                if (!"IHDR".equals(type) || n != 13 || be32(png, offset + 8) == 0 || be32(png, offset + 12) == 0)
                    throw new IOException("Screenshot PNG header is invalid");
                header = true;
            }
            if ("IDAT".equals(type) && n > 0) pixels = true;
            offset += n + 12;
            if ("IEND".equals(type)) {
                if (n == 0 && pixels && offset == png.length) return;
                throw new IOException("Screenshot PNG ending is invalid");
            }
        }
        throw new IOException("Screenshot PNG has no complete ending");
    }

    private static long be32(byte[] bytes, int at) {
        return ((long)(bytes[at] & 255) << 24) | ((long)(bytes[at + 1] & 255) << 16)
                | ((long)(bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }

    /** Validate MSS v4 framing and both compressed payloads without loading a state. */
    static void verifyState(File file) throws Exception {
        if (!file.isFile() || file.length() < 48 || file.length() > MAX_FILE)
            throw new IOException("Checkpoint state is missing or incomplete");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.read() != 'M' || in.read() != 'S' || in.read() != 'S') throw new IOException("Checkpoint has no MSS header");
            le32(in); // Emulator version; exact APK compatibility is enforced by the manifest.
            if (le32(in) != 4) throw new IOException("Unsupported checkpoint state format");
            le32(in); // Console type is also captured in ROMINFO/STATE.
            long frameBytes = le32(in), width = le32(in), height = le32(in), scale = le32(in);
            if (width == 0 || height == 0 || scale == 0) throw new IOException("Checkpoint image header is invalid");
            verifyCompressed(in, frameBytes, le32(in), 2 * 1024 * 1024);
            long nameLength = le32(in);
            if (nameLength > 4096 || nameLength > in.length() - in.getFilePointer()) throw new IOException("Checkpoint ROM name is truncated");
            in.seek(in.getFilePointer() + nameLength);
            if (in.read() != 1) throw new IOException("Checkpoint serialized payload is not compressed");
            long stateBytes = le32(in), compressedBytes = le32(in);
            verifyCompressed(in, stateBytes, compressedBytes, 10 * 1024 * 1024);
            if (in.getFilePointer() != in.length()) throw new IOException("Checkpoint has unexpected trailing data");
        }
    }

    private static long le32(RandomAccessFile in) throws IOException {
        return Integer.reverseBytes(in.readInt()) & 0xffffffffL;
    }

    private static void verifyCompressed(RandomAccessFile in, long size, long compressed, int limit) throws Exception {
        if (size <= 0 || size > MAX_FILE || compressed <= 0 || compressed >= limit || compressed > in.length() - in.getFilePointer())
            throw new IOException("Checkpoint compressed data is missing or truncated");
        byte[] input = new byte[(int)compressed];
        in.readFully(input);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] buffer = new byte[8192];
            long total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 || (total += count) > size) throw new IOException("Checkpoint compressed data is invalid");
            }
            if (total != size || inflater.getRemaining() != 0) throw new IOException("Checkpoint compressed length does not match");
        } finally {
            inflater.end();
        }
    }
}
