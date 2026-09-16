package ca.mesen.oos;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

final class WorkshopLink {
    private static final String TAG = "WorkshopLink";
    static final int PORT = 27015;
    static final String SERVICE_TYPE = "_mesen2-oos._tcp.";

    private final Context mContext;
    private NsdManager mNsd;
    private NsdManager.RegistrationListener mRegistration;
    private WifiManager.MulticastLock mMulticast;
    private WifiManager.WifiLock mWifi;
    private String mLan = "";
    private long mLastWriteMs;

    WorkshopLink(Context context) {
        mContext = context.getApplicationContext();
    }

    void start() {
        acquireWifi();
        mLan = detectLan();
        acquireMulticast();
        registerNsd();
    }

    void refresh(boolean force) {
        String previous = mLan == null ? "" : mLan;
        String current = detectLan();
        boolean changed = !previous.equals(current);
        mLan = current;
        if (!force && !changed) {
            return;
        }
        if (changed) {
            unregisterNsd();
            registerNsd();
        }
        mLastWriteMs = 0;
    }

    void stop() {
        unregisterNsd();
        releaseMulticast();
        releaseWifi();
    }

    String lanAddress() {
        if (mLan == null || mLan.isEmpty()) {
            mLan = detectLan();
        }
        return mLan;
    }

    String adbEndpoint() {
        return "tcp://127.0.0.1:" + PORT;
    }

    String lanEndpoint() {
        String lan = lanAddress();
        if (lan.isEmpty()) {
            return adbEndpoint();
        }
        return "tcp://" + lan + ":" + PORT;
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
            json.put("bind", "0.0.0.0");
            json.put("lan", lanAddress());
            json.put("lanEndpoint", lanEndpoint());
            json.put("adbEndpoint", adbEndpoint());
            json.put("agents", agentCount());
            json.put("nsdType", SERVICE_TYPE);
            json.put("nsdName", "oracle-rg353p");
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

    private void registerNsd() {
        try {
            mNsd = (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
            if (mNsd == null) {
                return;
            }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("oracle-rg353p");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            info.setAttribute("app", "mesen2-oos");
            info.setAttribute("pkg", "ca.mesen.oos");
            mRegistration = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "nsd register failed " + errorCode);
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "nsd registered " + serviceInfo.getServiceName());
                }
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            };
            mNsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, mRegistration);
        } catch (Exception e) {
            Log.w(TAG, "nsd: " + e.getMessage());
        }
    }

    private void unregisterNsd() {
        if (mNsd != null && mRegistration != null) {
            try {
                mNsd.unregisterService(mRegistration);
            } catch (Exception ignored) {
            }
        }
        mRegistration = null;
    }

    private void acquireWifi() {
        try {
            WifiManager wifi = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            // Unicast health uses the STA radio. The multicast lock alone does
            // not keep it associated after sleep/wake on this vendor build.
            mWifi = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "oracle-socket");
            mWifi.setReferenceCounted(false);
            mWifi.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWifi() {
        if (mWifi != null && mWifi.isHeld()) {
            mWifi.release();
        }
        mWifi = null;
    }

    private void acquireMulticast() {
        try {
            WifiManager wifi = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return;
            }
            mMulticast = wifi.createMulticastLock("oracle-nsd");
            mMulticast.setReferenceCounted(false);
            mMulticast.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticast() {
        if (mMulticast != null && mMulticast.isHeld()) {
            mMulticast.release();
        }
        mMulticast = null;
    }

    private String detectLan() {
        String wlan = addressFor("wlan");
        if (!wlan.isEmpty()) {
            return wlan;
        }
        String rndis = addressFor("rndis");
        if (!rndis.isEmpty()) {
            return rndis;
        }
        String any = addressFor("");
        if (!any.isEmpty()) {
            return any;
        }
        try {
            WifiManager wifi = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String addressFor(String nameContains) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                if (!nameContains.isEmpty() && !name.contains(nameContains)) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
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
