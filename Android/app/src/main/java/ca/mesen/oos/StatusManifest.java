package ca.mesen.oos;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Publishes the runtime status snapshot that host tools read
 * (oos-rg353p oos_handheld.py and oos_rg353p.sh pull files/workshop.json; the
 * file keeps that name for them). The control socket itself is native and
 * binds 127.0.0.1 only on Android (SocketServer.cpp ParseTcpBindFromEnv,
 * mesen_android_main.cpp MESEN2_TCP_BIND); hosts reach it through
 * {@code adb forward tcp:27015 tcp:27015}. Nothing here opens a port.
 */
final class StatusManifest {
    private static final String TAG = "StatusManifest";
    static final int PORT = 27015;
    static final String FILE_NAME = "workshop.json";

    private final Context mContext;
    private long mLastWriteMs;

    StatusManifest(Context context) {
        mContext = context.getApplicationContext();
    }

    String adbEndpoint() {
        return "tcp://127.0.0.1:" + PORT;
    }

    int agentCount() {
        File status = new File(mContext.getFilesDir(), "mesen2-tcp-" + PORT + ".status");
        if (!status.isFile() || status.length() > 64 * 1024) {
            return 0;
        }
        try (FileInputStream in = new FileInputStream(status)) {
            byte[] raw = new byte[(int) status.length()];
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(raw, 0, off, StandardCharsets.UTF_8)).optInt("registeredAgents", 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Throttled to one write per 1.5 s. */
    void write(JSONObject status) {
        long now = System.currentTimeMillis();
        if (now - mLastWriteMs < 1500) {
            return;
        }
        mLastWriteMs = now;
        try {
            JSONObject json = status == null ? new JSONObject() : status;
            json.put("service", "mesen2-oos");
            json.put("package", "ca.mesen.oos");
            json.put("port", PORT);
            json.put("bind", "127.0.0.1");
            json.put("transport", "adb-forward");
            json.put("adbEndpoint", adbEndpoint());
            json.put("agents", agentCount());
            byte[] bytes = (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            atomicWrite(new File(mContext.getFilesDir(), FILE_NAME), bytes);
            File ext = mContext.getExternalFilesDir(null);
            if (ext != null) {
                atomicWrite(new File(ext, FILE_NAME), bytes);
            }
        } catch (Exception e) {
            Log.w(TAG, FILE_NAME + ": " + e.getMessage());
        }
    }

    private static void atomicWrite(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new java.io.IOException("rename failed for " + file.getName());
        }
    }
}
