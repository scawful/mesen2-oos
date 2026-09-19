package ca.mesen.oos;

import java.util.Locale;

/**
 * Computes observed emulator frame rate from monotonically increasing frame
 * counters. Time is supplied by the caller so the tracker is host-testable.
 */
final class FrameRateTracker {
    private static final long MIN_SAMPLE_MS = 500;
    private static final double NTSC_FPS = 60.1;

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

    /**
     * Turbo visual-present interval used by the native PPU skip path.
     * 48 ms targets roughly 20-21 presented frames per second while the core
     * advances as fast as the device allows.
     */
    static int turboVisualIntervalMs() {
        return 48;
    }

    /**
     * Smooth-play visual-present interval (alternate-frame style).
     */
    static int smoothVisualIntervalMs() {
        return 10;
    }

    /**
     * Whether the next emulated frame should skip the video pipeline given
     * elapsed ms since the last presented frame. Mirrors the native policy:
     * turbo / unlimited / speed>1.5x use the 40 ms window; Smooth play uses 10.
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
