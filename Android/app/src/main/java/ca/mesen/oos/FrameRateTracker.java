package ca.mesen.oos;

/**
 * Computes observed emulator frame rate from monotonically increasing frame
 * counters. Time is supplied by the caller so the tracker is host-testable.
 */
final class FrameRateTracker {
    private static final long MIN_SAMPLE_MS = 500;

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

    private void setBaseline(String rom, long frames, long nowMs, boolean paused) {
        mRom = rom;
        mFrames = frames;
        mSampleTimeMs = nowMs;
        mWasPaused = paused;
    }
}
