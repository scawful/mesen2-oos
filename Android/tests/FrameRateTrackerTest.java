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

        System.out.println("FrameRateTrackerTest: " + checks + " checks passed");
    }
}
