package ca.mesen.oos;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

final class WorkshopLink {
    private static final String TAG = "WorkshopLink";
    static final int PORT = 27015;

    private final Context mContext;
    private long mLastWriteMs;

    WorkshopLink(Context context) {
        mContext = context.getApplicationContext();
    }

    void refresh(boolean force) {
        if (force) {
            mLastWriteMs = 0;
        }
    }

    String adbEndpoint() {
        return "tcp://127.0.0.1:" + PORT;
    }

    int agentCount() {
        File status = new File(mContext.getFilesDir(), "mesen2-tcp-27015.status");
        if (!status.isFile()) {
            return 0;
        }
        try {
            byte[] raw = readAll(status);
            JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            return json.optInt("registeredAgents", 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    void writeManifest(JSONObject extra) {
        long now = System.currentTimeMillis();
        if (now - mLastWriteMs < 1500) {
            return;
        }
        mLastWriteMs = now;
        try {
            JSONObject json = extra == null ? new JSONObject() : extra;
            json.put("service", "mesen2-oos");
            json.put("package", "ca.mesen.oos");
            json.put("port", PORT);
            json.put("bind", "127.0.0.1");
            json.put("transport", "adb-forward");
            json.put("adbEndpoint", adbEndpoint());
            json.put("agents", agentCount());
            byte[] bytes = (json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            writeFile(new File(mContext.getFilesDir(), "workshop.json"), bytes);
            File ext = mContext.getExternalFilesDir(null);
            if (ext != null) {
                writeFile(new File(ext, "workshop.json"), bytes);
            }
            File drop = new File("/storage/emulated/0/OracleOfSecrets");
            if (drop.isDirectory() || drop.mkdirs()) {
                writeFile(new File(drop, "workshop.json"), bytes);
            }
        } catch (Exception e) {
            Log.w(TAG, "workshop.json: " + e.getMessage());
        }
    }

    private static byte[] readAll(File file) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buf = new byte[(int) file.length()];
            int n = in.read(buf);
            if (n < 0) {
                return new byte[0];
            }
            if (n == buf.length) {
                return buf;
            }
            byte[] slim = new byte[n];
            System.arraycopy(buf, 0, slim, 0, n);
            return slim;
        } finally {
            in.close();
        }
    }

    private static void writeFile(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        WorkshopArtifactFiles.atomicWrite(file, bytes);
    }
}
