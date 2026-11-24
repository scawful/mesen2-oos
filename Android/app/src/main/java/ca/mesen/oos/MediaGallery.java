package ca.mesen.oos;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * Mirrors saved PNG screenshots and MP4 videos into Android's shared MediaStore
 * Gallery collections (`Pictures/OracleOfSecrets`, `Movies/OracleOfSecrets`) on
 * Android 10+ (API 29+) without requiring external storage write permissions.
 */
final class MediaGallery {
    private static final String SUBDIR = "OracleOfSecrets";

    private MediaGallery() {}

    static boolean publish(Context context, File file, boolean isVideo) {
        if (context == null || file == null || !file.isFile() || file.length() <= 0) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) return false;

            Uri collection = isVideo
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String relPath = (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                    + "/" + SUBDIR;

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/png");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(collection, values);
            if (uri == null) return false;

            boolean copied = false;
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out != null) {
                    byte[] buf = new byte[16384];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                    copied = true;
                }
            } catch (Throwable t) {
                copied = false;
            }

            if (!copied) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {}
                return false;
            }

            ContentValues finish = new ContentValues();
            finish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, finish, null, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
