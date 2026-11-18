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
    public static native boolean writeByte(int addr, int value);
    public static native boolean writeWord(int addr, int value);
    /** @return unsigned byte 0..255, or -1 on failure */
    public static native int readByte(int addr);
    /** Cartridge SRAM offset ($70:0000 = 0). @return 0..255, or -1 on failure */
    public static native int readSram(int offset);
    public static native boolean writeSram(int offset, int value);
    public static native boolean saveState(int slot);
    public static native boolean loadState(int slot);
    public static native String saveStatePath(int slot);
    public static native boolean saveStateFile(String path);
    public static native boolean loadStateFile(String path);
    /** PNG bytes of the preview inside a .mss file, or null. */
    public static native byte[] statePreviewPng(String path);
    public static native boolean takeScreenshot(String path);
    public static native boolean startVideoRecording(String path, boolean recordInputHud, boolean recordSystemHud);
    public static native void stopVideoRecording();
    public static native boolean isVideoRecording();
    public static native String videoRecordingPath();
    public static native void setRamCheats(int[] addrs, int[] values);
    public static native boolean stepFrames(int frames);
    public static native boolean loadRom(String path);
    public static native String statusJson(boolean detailed);
    public static native void setPresentMode(int mode);
    public static native void setPreferAccelerated(boolean enabled);
    public static native boolean isRenderAccelerated();
    /** Writes Clang instrumentation profile when built with YAZE_PGO_GENERATE. */
    public static native boolean dumpPgoProfile();
}
