package ca.mesen.oos;

public final class MesenNative {
    private MesenNative() {}

    public static native void pauseEmu();
    public static native void resumeEmu();
    public static native void resetEmu();
    public static native void setFastForward(boolean enabled);
    public static native boolean isFastForward();
    public static native void setPerformanceMode(boolean enabled);
    public static native boolean isPerformanceMode();
    public static native boolean saveState(int slot);
    public static native boolean loadState(int slot);
    public static native String saveStatePath(int slot);
    public static native boolean loadRom(String path);
    public static native String statusJson();
    public static native void setPresentMode(int mode);
    public static native void setPreferAccelerated(boolean enabled);
    public static native boolean isRenderAccelerated();
}
