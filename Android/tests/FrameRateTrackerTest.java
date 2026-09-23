package ca.mesen.oos;

public final class FrameRateTrackerTest {
    private static int checks;

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        checks++;
    }

    private static boolean near(Double value, double expected) {
        return value != null && Math.abs(value - expected) < 0.01;
    }

    public static void main(String[] args) {
        FrameRateTracker tracker = new FrameRateTracker();

        check(tracker.sample("oracle.sfc", 100, false, 1_000) == null,
                "first running sample establishes a baseline");
        check(near(tracker.sample("oracle.sfc", 119, false, 1_500), 38.0),
                "frame delta reports observed FPS");
        check(near(tracker.sample("oracle.sfc", 119, true, 1_700), 38.0),
                "paused overlay retains the last running measurement");
        check(tracker.sample("oracle.sfc", 120, false, 2_000) == null,
                "resume establishes a fresh baseline");
        check(near(tracker.sample("oracle.sfc", 180, false, 3_000), 60.0),
                "post-resume measurement excludes paused time");
        check(tracker.sample("new-build.sfc", 1, false, 3_100) == null,
                "ROM changes discard stale performance data");
        check(tracker.sample("new-build.sfc", 0, false, 3_200) == null,
                "frame counter rollback establishes a fresh baseline");

        check("FF".equals(FrameRateTracker.formatFastForwardLabel(null)),
                "missing FPS keeps a plain FF label");
        check("FF ×2.0".equals(FrameRateTracker.formatFastForwardLabel(120.2)),
                "120.2 FPS is reported as ×2.0 against NTSC");
        check("FF ×1.5".equals(FrameRateTracker.formatFastForwardLabel(90.15)),
                "90.15 FPS is reported as ×1.5 against NTSC");
        check(FrameRateTracker.formatPresentLabel(null) == null,
                "missing present FPS hides the present chip");
        check("P8".equals(FrameRateTracker.formatPresentLabel(7.6)),
                "present rate rounds to a short P chip");
        check("Weather-old".equals(FrameRateTracker.romIdentityLabel(
                        FrameRateTracker.WEATHER_ROM_SHA1)),
                "legacy Weather SHA1 labels as Weather-old");
        check("Main".equals(FrameRateTracker.romIdentityLabel(
                        FrameRateTracker.MAIN_ROM_SHA1)),
                "today's main SHA1 labels as Main");
        check("Treefix".equals(FrameRateTracker.romIdentityLabel(
                        FrameRateTracker.TREEFIX_ROM_SHA1)),
                "treefix alternate SHA1 labels as Treefix");
        check("ROM≠".equals(FrameRateTracker.romIdentityLabel("DEADBEEF01234567")),
                "mismatched SHA1 flags ROM drift");
        check(FrameRateTracker.romIdentityLabel("") == null,
                "empty SHA1 hides the identity chip");

        check(FrameRateTracker.turboVisualIntervalMs() == 96,
                "turbo targets a 96 ms visual interval (~10 FPS)");
        check(FrameRateTracker.smoothVisualIntervalMs() == 10,
                "smooth play keeps the 10 ms visual interval");
        check(FrameRateTracker.shouldSkipVisualFrame(true, true, 20),
                "turbo skips while under the 96 ms window");
        check(!FrameRateTracker.shouldSkipVisualFrame(true, true, 96),
                "turbo presents once the 96 ms window elapses");
        check(FrameRateTracker.shouldSkipVisualFrame(false, true, 5),
                "smooth play skips while under the 10 ms window");
        check(!FrameRateTracker.shouldSkipVisualFrame(false, true, 10),
                "smooth play presents once the 10 ms window elapses");
        check(!FrameRateTracker.shouldSkipVisualFrame(true, false, 0),
                "full-render never skips from the visual policy alone");
        check(FrameRateTracker.shouldPreferAccelerated(true, false),
                "GPU preference allows accelerated present");
        check(!FrameRateTracker.shouldPreferAccelerated(true, true),
                "forced software overrides GPU preference");
        check(!FrameRateTracker.shouldPreferAccelerated(false, false),
                "disabled GPU chip stays on software");

        System.out.println("FrameRateTrackerTest: " + checks + " checks passed");
    }
}
