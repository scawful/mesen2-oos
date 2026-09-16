package ca.mesen.oos;

public final class MesenNative {
    private MesenNative() {}

    public static native void pauseEmu();
    public static native void resumeEmu();
    public static native void resetEmu();
    public static native boolean loadRom(String path);
    public static native String statusJson();
    public static native void setPresentMode(int mode);
}
