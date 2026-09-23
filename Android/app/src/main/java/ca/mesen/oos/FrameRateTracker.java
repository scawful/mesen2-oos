package ca.mesen.oos;

import java.util.Locale;

/**
 * Computes observed emulator frame rate from monotonically increasing frame
 * counters. Time is supplied by the caller so the tracker is host-testable.
 */
final class FrameRateTracker {
    private static final long MIN_SAMPLE_MS = 500;
    private static final double NTSC_FPS = 60.1;
    /** Today's main handheld build (2026-09-19 14:01); includes the tree tile fix. */
    static final String MAIN_ROM_SHA1 = "2671E543D83A96AD73F1D7D3CCB55580593DCA14";
    /** Sep14 rain-fix + same 16-byte tree patch; tree tile16 matches MAIN. */
    static final String TREEFIX_ROM_SHA1 = "B4F48B2133E0C43667370A3E4235B12844C4ED7A";
    /** @deprecated Pre-tree-fix Weather outdoor build. */
    @Deprecated
    static final String WEATHER_ROM_SHA1 = "0E365616014911F7C6FFDA04D9A0CF718BB204C2";

    private String mRom = "";
    private long mFrames = -1;
    private long mSampleTimeMs = -1;
    private boolean mWasPaused = true;
    private Double mLatestFps;

    Double sample(String rom, long frames, boolean paused, long nowMs) {
        String currentRom = rom == null ? "" : rom;
        boolean romChanged = !currentRom.equals(mRom);
        if (romChanged) {
            mLatestFps = null;
        }

        if (frames < 0 || nowMs < 0 || romChanged || frames < mFrames
                || nowMs <= mSampleTimeMs) {
            setBaseline(currentRom, frames, nowMs, paused);
            return mLatestFps;
        }

        if (paused) {
            setBaseline(currentRom, frames, nowMs, true);
            return mLatestFps;
        }

        if (mWasPaused || mFrames < 0 || mSampleTimeMs < 0) {
            mLatestFps = null;
            setBaseline(currentRom, frames, nowMs, false);
            return null;
        }

        long elapsedMs = nowMs - mSampleTimeMs;
        if (elapsedMs >= MIN_SAMPLE_MS) {
            mLatestFps = (frames - mFrames) * 1000.0 / elapsedMs;
            setBaseline(currentRom, frames, nowMs, false);
        }
        return mLatestFps;
    }

    /**
     * Formats the fast-forward HUD chip. When a live FPS sample is available,
     * reports the measured multiplier against NTSC; otherwise a plain "FF".
     */
    static String formatFastForwardLabel(Double observedFps) {
        if (observedFps == null || observedFps <= 0) {
            return "FF";
        }
        return String.format(Locale.US, "FF ×%.1f", observedFps / NTSC_FPS);
    }

    /** Short present-rate chip for the HUD (unique uploads reaching SDL). */
    static String formatPresentLabel(Double presentFps) {
        if (presentFps == null || presentFps <= 0) {
            return null;
        }
        return String.format(Locale.US, "P%.0f", presentFps);
    }

    /**
     * Workshop ROM identity chip. Main is today's build with the tree fix.
     * Treefix is an allowed alternate with identical tree tile16 data.
     */
    static String romIdentityLabel(String sha1) {
        if (sha1 == null || sha1.isEmpty()) {
            return null;
        }
        if (MAIN_ROM_SHA1.equalsIgnoreCase(sha1)) {
            return "Main";
        }
        if (TREEFIX_ROM_SHA1.equalsIgnoreCase(sha1)) {
            return "Treefix";
        }
        if (WEATHER_ROM_SHA1.equalsIgnoreCase(sha1)) {
            return "Weather-old";
        }
        return "ROM≠";
    }

    /**
     * Turbo visual-present interval used by the native PPU skip path.
     * 96 ms targets roughly 10-12 presented frames per second while the core
     * advances as fast as the device allows.
     */
    static int turboVisualIntervalMs() {
        return 96;
    }

    /**
     * Smooth-play visual-present interval (alternate-frame style).
     */
    static int smoothVisualIntervalMs() {
        return 10;
    }

    /**
     * Whether accelerated presentation should be preferred. Creation or present
     * failures stick to software until the user re-enables the GPU chip.
     */
    static boolean shouldPreferAccelerated(boolean userPrefersGpu, boolean forceSoftware) {
        return userPrefersGpu && !forceSoftware;
    }

    /**
     * Whether the next emulated frame should skip the video pipeline given
     * elapsed ms since the last presented frame. Mirrors the native policy:
     * turbo / unlimited / speed>1.5x use the 96 ms window; Smooth play uses 10.
     */
    static boolean shouldSkipVisualFrame(boolean turboOrFastSpeed, boolean allowSkip,
            long elapsedMsSincePresent) {
        if (!allowSkip || elapsedMsSincePresent < 0) {
            return false;
        }
        int window = turboOrFastSpeed ? turboVisualIntervalMs() : smoothVisualIntervalMs();
        return elapsedMsSincePresent < window;
    }

    private void setBaseline(String rom, long frames, long nowMs, boolean paused) {
        mRom = rom;
        mFrames = frames;
        mSampleTimeMs = nowMs;
        mWasPaused = paused;
    }
}
