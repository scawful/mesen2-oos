package ca.mesen.oos;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu-only segmented gameplay recorder and telemetry logger for Android Study Sessions.
 *
 * <p>Writes crash-safe rotating MP4 segments ({@code video_seg_NNN.mp4}), ~4 Hz / event
 * {@code telemetry.ndjson}, rolling FIFO {@code anchors/anchor_NNN.mss} snapshots,
 * anomaly captures ({@code anchors/anom_NNN.*}), and non-evicted manual bookmarks
 * ({@code bookmarks/bm_NNN_<tag>.*}).
 */
public final class StudySessionRecorder {
    public static final String SCHEMA_VERSION = "oos-study-session-v1";
    public static final long DEFAULT_SEGMENT_SECONDS = 600L;
    public static final long DEFAULT_ANCHOR_INTERVAL_MS = 180_000L;
    public static final long DEFAULT_TRANSITION_ANCHOR_COOLDOWN_MS = 45_000L;
    public static final long DEFAULT_ANOMALY_COOLDOWN_MS = 15_000L;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 2_000L;
    public static final long SAMPLE_INTERVAL_MS = 200L;
    public static final long STUCK_TRANSITION_MS = 5_000L;
    public static final int LOW_FPS_CONSECUTIVE_TICKS = 3;
    public static final double LOW_FPS_THRESHOLD = 45.0;
    public static final int LAG_SPIKE_THRESHOLD = 4;
    public static final int MAX_AUTO_ANCHORS = 24;

    public interface Hooks {
        boolean startVideoSegment(File mp4File);
        boolean stopVideoSegment();
        boolean saveState(File stateFile);
        boolean saveScreenshot(File pngFile);
        String statusJson(boolean detailed);
        int readByte(int addr);
        String romPath();
        String romDisplayName();
        default long nowMs() { return System.currentTimeMillis(); }
    }

    public static final class SessionStatus {
        public final boolean active;
        public final File sessionDir;
        public final int segmentIndex;
        public final long elapsedSeconds;
        public final long segmentElapsedSeconds;
        public final int anchorCount;
        public final int bookmarkCount;
        public final int anomalyCount;
        public final int sampleCount;

        SessionStatus(boolean active, File sessionDir, int segmentIndex, long elapsedSeconds,
                      long segmentElapsedSeconds, int anchorCount, int bookmarkCount,
                      int anomalyCount, int sampleCount) {
            this.active = active;
            this.sessionDir = sessionDir;
            this.segmentIndex = segmentIndex;
            this.elapsedSeconds = elapsedSeconds;
            this.segmentElapsedSeconds = segmentElapsedSeconds;
            this.anchorCount = anchorCount;
            this.bookmarkCount = bookmarkCount;
            this.anomalyCount = anomalyCount;
            this.sampleCount = sampleCount;
        }
    }

    private static final class SegmentRecord {
        final int index;
        final String fileName;
        final File file;
        final long startSessionSec;
        long endSessionSec;
        long bytes;
        boolean closed;

        SegmentRecord(int index, File file, long startSessionSec) {
            this.index = index;
            this.fileName = file.getName();
            this.file = file;
            this.startSessionSec = startSessionSec;
        }
    }

    private static final class AnchorRecord {
        final String fileName;
        final File file;
        final String reason;
        final long sessionSec;
        final int segmentIndex;
        final long segSec;
        final int module;
        final int area;
        final int room;

        AnchorRecord(File file, String reason, long sessionSec, int segmentIndex, long segSec,
                     int module, int area, int room) {
            this.fileName = file.getName();
            this.file = file;
            this.reason = reason;
            this.sessionSec = sessionSec;
            this.segmentIndex = segmentIndex;
            this.segSec = segSec;
            this.module = module;
            this.area = area;
            this.room = room;
        }
    }

    private static final class BookmarkRecord {
        final int index;
        final String tag;
        final String note;
        final long tsMs;
        final long sessionSec;
        final int segmentIndex;
        final String segmentFile;
        final long segSec;
        final String stateRel;
        final String screenshotRel;
        final String statusRel;
        final int module;
        final int sub;
        final int indoors;
        final int area;
        final int room;
        final int world;
        final int x;
        final int y;

        BookmarkRecord(int index, String tag, String note, long tsMs, long sessionSec,
                       int segmentIndex, String segmentFile, long segSec, String stateRel,
                       String screenshotRel, String statusRel, int module, int sub,
                       int indoors, int area, int room, int world, int x, int y) {
            this.index = index;
            this.tag = tag;
            this.note = note;
            this.tsMs = tsMs;
            this.sessionSec = sessionSec;
            this.segmentIndex = segmentIndex;
            this.segmentFile = segmentFile;
            this.segSec = segSec;
            this.stateRel = stateRel;
            this.screenshotRel = screenshotRel;
            this.statusRel = statusRel;
            this.module = module;
            this.sub = sub;
            this.indoors = indoors;
            this.area = area;
            this.room = room;
            this.world = world;
            this.x = x;
            this.y = y;
        }
    }

    private static final class AnomalyRecord {
        final int index;
        final String kind;
        final String detail;
        final long tsMs;
        final long sessionSec;
        final int segmentIndex;
        final String segmentFile;
        final long segSec;
        final String stateRel;
        final String screenshotRel;

        AnomalyRecord(int index, String kind, String detail, long tsMs, long sessionSec,
                      int segmentIndex, String segmentFile, long segSec,
                      String stateRel, String screenshotRel) {
            this.index = index;
            this.kind = kind;
            this.detail = detail;
            this.tsMs = tsMs;
            this.sessionSec = sessionSec;
            this.segmentIndex = segmentIndex;
            this.segmentFile = segmentFile;
            this.segSec = segSec;
            this.stateRel = stateRel;
            this.screenshotRel = screenshotRel;
        }
    }

    private static final class TelemetrySnapshot {
        final int module;
        final int sub;
        final int indoors;
        final int area;
        final int room;
        final int world;
        final int x;
        final int y;
        final int hp;
        final int mask;
        final int state;
        final double fps;
        final double presentFps;
        final String sha1;
        final int snesFrame;
        final int snesLagFlag;
        final long emuFrames;
        final long renderPresents;
        final boolean paused;
        final boolean fastForward;
        final int snesLagFrames;
        final int presentDrops;

        TelemetrySnapshot(int module, int sub, int indoors, int area, int room, int world,
                          int x, int y, int hp, int mask, int state, double fps, double presentFps,
                          String sha1, int snesFrame, int snesLagFlag, long emuFrames,
                          long renderPresents, boolean paused, boolean fastForward,
                          int snesLagFrames, int presentDrops) {
            this.module = module;
            this.sub = sub;
            this.indoors = indoors;
            this.area = area;
            this.room = room;
            this.world = world;
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.mask = mask;
            this.state = state;
            this.fps = fps;
            this.presentFps = presentFps;
            this.sha1 = sha1;
            this.snesFrame = snesFrame;
            this.snesLagFlag = snesLagFlag;
            this.emuFrames = emuFrames;
            this.renderPresents = renderPresents;
            this.paused = paused;
            this.fastForward = fastForward;
            this.snesLagFrames = snesLagFrames;
            this.presentDrops = presentDrops;
        }
    }

    private final File mSessionsRoot;
    private final Hooks mHooks;
    private final long mSegmentDurationMs;
    private final long mAnchorIntervalMs;
    private final long mTransitionAnchorCooldownMs;
    private final long mAnomalyCooldownMs;
    private final long mFlushIntervalMs;
    private final int mMaxAutoAnchors;

    private boolean mActive;
    private boolean mCleanShutdown;
    private String mStopReason = "";
    private File mSessionDir;
    private File mAnchorsDir;
    private File mBookmarksDir;
    private File mTelemetryFile;
    private File mSessionJsonFile;
    private File mBookmarksJsonFile;
    private BufferedWriter mTelemetryWriter;

    private long mStartWallMs;
    private long mLastWallMs;
    private long mActiveElapsedMs;
    private long mSegmentActiveMs;
    private long mLastAnchorWallMs;
    private long mLastFlushWallMs;
    private long mLastSampleWallMs;
    private long mLastAnomalyWallMs = -1L;

    private int mSegmentIndex;
    private int mAnchorSeq;
    private int mBookmarkSeq;
    private int mAnomalySeq;
    private int mSampleCount;
    private int mTransitionCount;

    private final List<SegmentRecord> mSegments = new ArrayList<>();
    private final Deque<AnchorRecord> mRollingAnchors = new ArrayDeque<>();
    private final List<BookmarkRecord> mBookmarks = new ArrayList<>();
    private final List<AnomalyRecord> mAnomalies = new ArrayList<>();

    private String mRomPath = "";
    private String mRomName = "oracle";
    private String mRomSha1 = "";

    private boolean mHasPrevLoc;
    private int mPrevModule = -1;
    private int mPrevSub = -1;
    private int mPrevIndoors = -1;
    private int mPrevArea = -1;
    private int mPrevRoom = -1;
    private int mPrevWorld = -1;
    private boolean mPrevWasPlayable;
    private long mStuckTransitionMs;
    private int mLowFpsStreak;
    private long mPrevEmuFrames = -1L;
    private long mPrevRenderPresents = -1L;
    private int mPrevSnesFrame = -1;
    private long mTotalGameplayEmuFrames;
    private long mTotalSnesLagFrames;
    private long mTotalPresentDrops;
    private int mMaxTickSnesLag;
    private int mMaxTickPresentDrops;
    private TelemetrySnapshot mLatestSnapshot;

    public StudySessionRecorder(File sessionsRoot, Hooks hooks) {
        this(sessionsRoot, hooks, DEFAULT_SEGMENT_SECONDS, DEFAULT_ANCHOR_INTERVAL_MS,
                DEFAULT_TRANSITION_ANCHOR_COOLDOWN_MS, DEFAULT_ANOMALY_COOLDOWN_MS,
                DEFAULT_FLUSH_INTERVAL_MS, MAX_AUTO_ANCHORS);
    }

    StudySessionRecorder(File sessionsRoot, Hooks hooks, long segmentSeconds,
                         long anchorIntervalMs, long transitionAnchorCooldownMs,
                         long anomalyCooldownMs, long flushIntervalMs, int maxAutoAnchors) {
        mSessionsRoot = sessionsRoot;
        mHooks = hooks;
        mSegmentDurationMs = Math.max(1L, segmentSeconds) * 1000L;
        mAnchorIntervalMs = Math.max(1000L, anchorIntervalMs);
        mTransitionAnchorCooldownMs = Math.max(0L, transitionAnchorCooldownMs);
        mAnomalyCooldownMs = Math.max(0L, anomalyCooldownMs);
        mFlushIntervalMs = Math.max(100L, flushIntervalMs);
        mMaxAutoAnchors = Math.max(1, maxAutoAnchors);
    }

    public static File sessionsDir(File capturesRoot) {
        if (capturesRoot == null) return null;
        File dir = new File(capturesRoot, "Sessions");
        dir.mkdirs();
        return dir;
    }

    private static File nextSessionDir(File sessionsRoot, String romName, long epochMs) {
        sessionsRoot.mkdirs();
        String stem = MediaCapture.timestamp(epochMs) + "_" + MediaCapture.slugify(romName);
        File candidate = new File(sessionsRoot, stem);
        if (!candidate.exists()) return candidate;
        for (int i = 1; i <= 99; i++) {
            File alt = new File(sessionsRoot, stem + "_" + i);
            if (!alt.exists()) return alt;
        }
        return candidate;
    }

    public synchronized String start() {
        if (mActive) {
            return "Study Session already active (" + mSessionDir.getName() + ")";
        }
        if (mSessionsRoot == null || mHooks == null) {
            return "Study Session storage unavailable";
        }
        long now = mHooks.nowMs();
        String romPath = mHooks.romPath();
        String romName = mHooks.romDisplayName();
        mRomPath = romPath != null ? romPath : "";
        mRomName = (romName != null && !romName.trim().isEmpty()) ? romName : "oracle";

        File dir = nextSessionDir(mSessionsRoot, mRomName, now);
        File anchors = new File(dir, "anchors");
        File bookmarks = new File(dir, "bookmarks");
        if ((!dir.mkdirs() && !dir.isDirectory())
                || (!anchors.mkdirs() && !anchors.isDirectory())
                || (!bookmarks.mkdirs() && !bookmarks.isDirectory())) {
            return "Failed to create session folder: " + dir.getAbsolutePath();
        }

        File seg1 = new File(dir, String.format(Locale.US, "video_seg_%03d.mp4", 1));
        if (!mHooks.startVideoSegment(seg1)) {
            return "Failed to start segmented MP4 recorder";
        }

        mSessionDir = dir;
        mAnchorsDir = anchors;
        mBookmarksDir = bookmarks;
        mTelemetryFile = new File(dir, "telemetry.ndjson");
        mSessionJsonFile = new File(dir, "session.json");
        mBookmarksJsonFile = new File(dir, "bookmarks.json");

        try {
            mTelemetryWriter = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(mTelemetryFile, true), StandardCharsets.UTF_8));
        } catch (IOException e) {
            mHooks.stopVideoSegment();
            return "Failed to open telemetry log: " + e.getMessage();
        }

        mActive = true;
        mCleanShutdown = false;
        mStopReason = "";
        mStartWallMs = now;
        mLastWallMs = now;
        mActiveElapsedMs = 0L;
        mSegmentActiveMs = 0L;
        mLastAnchorWallMs = now;
        mLastFlushWallMs = now;
        mLastSampleWallMs = now - SAMPLE_INTERVAL_MS;
        mLastAnomalyWallMs = -1L;

        mSegmentIndex = 1;
        mAnchorSeq = 0;
        mBookmarkSeq = 0;
        mAnomalySeq = 0;
        mSampleCount = 0;
        mTransitionCount = 0;
        mSegments.clear();
        mRollingAnchors.clear();
        mBookmarks.clear();
        mAnomalies.clear();
        mHasPrevLoc = false;
        mPrevModule = -1;
        mPrevSub = -1;
        mPrevIndoors = -1;
        mPrevArea = -1;
        mPrevRoom = -1;
        mPrevWorld = -1;
        mPrevWasPlayable = false;
        mStuckTransitionMs = 0L;
        mLowFpsStreak = 0;
        mPrevEmuFrames = -1L;
        mPrevRenderPresents = -1L;
        mPrevSnesFrame = -1;
        mTotalGameplayEmuFrames = 0L;
        mTotalSnesLagFrames = 0L;
        mTotalPresentDrops = 0L;
        mMaxTickSnesLag = 0;
        mMaxTickPresentDrops = 0;

        mSegments.add(new SegmentRecord(1, seg1, 0L));
        TelemetrySnapshot snap = readTelemetry(null, null);
        mLatestSnapshot = snap;
        mRomSha1 = snap.sha1;

        writeTelemetryLine("{\"event\":\"session_start\",\"ts_ms\":" + now
                + ",\"session_sec\":0,\"seg\":1,\"seg_sec\":0"
                + ",\"rom\":" + MediaCapture.jsonQuote(mRomName) + "}");
        captureRollingAnchor(now, "session_start", snap);
        flushTelemetryNow(now);
        writeBookmarksJsonSafe();
        writeSessionJsonSafe(now);
        return "Study Session started: " + dir.getName();
    }

    public synchronized boolean isActive() {
        return mActive;
    }

    public synchronized SessionStatus status() {
        return new SessionStatus(
                mActive,
                mSessionDir,
                mSegmentIndex,
                mActiveElapsedMs / 1000L,
                mSegmentActiveMs / 1000L,
                mRollingAnchors.size(),
                mBookmarks.size(),
                mAnomalies.size(),
                mSampleCount);
    }

    public synchronized void tick(boolean pausedOrMenuOpen, Double observedFps) {
        tick(pausedOrMenuOpen, observedFps, null);
    }

    public synchronized void tick(boolean pausedOrMenuOpen, Double observedFps, Double presentFps) {
        if (!mActive) {
            return;
        }
        long now = mHooks.nowMs();
        long deltaMs = Math.max(0L, now - mLastWallMs);
        mLastWallMs = now;

        if (pausedOrMenuOpen) {
            mLowFpsStreak = 0;
            mStuckTransitionMs = 0L;
            mPrevEmuFrames = -1L;
            mPrevRenderPresents = -1L;
            mPrevSnesFrame = -1;
            if (now - mLastFlushWallMs >= mFlushIntervalMs) {
                flushTelemetryNow(now);
            }
            return;
        }

        mActiveElapsedMs += deltaMs;
        mSegmentActiveMs += deltaMs;

        while (mSegmentActiveMs >= mSegmentDurationMs) {
            rotateSegmentLocked(now);
            mSegmentActiveMs -= mSegmentDurationMs;
        }

        TelemetrySnapshot snap = readTelemetry(observedFps, presentFps);
        mLatestSnapshot = snap;
        if (mRomSha1.isEmpty() && !snap.sha1.isEmpty()) {
            mRomSha1 = snap.sha1;
        }

        long sessionSec = mActiveElapsedMs / 1000L;
        long segSec = mSegmentActiveMs / 1000L;
        boolean areaOrRoomChanged = false;
        boolean stateChanged = !mHasPrevLoc
                || snap.module != mPrevModule
                || snap.indoors != mPrevIndoors
                || snap.area != mPrevArea
                || snap.room != mPrevRoom
                || snap.world != mPrevWorld;

        if (mHasPrevLoc) {
            areaOrRoomChanged = snap.indoors != mPrevIndoors
                    || snap.area != mPrevArea
                    || snap.room != mPrevRoom
                    || snap.world != mPrevWorld;
            if (stateChanged) {
                mTransitionCount++;
                writeTelemetryLine(formatSampleJson("transition", now, sessionSec, mSegmentIndex, segSec, snap, null, null));
                flushTelemetryNow(now);
            }
        }

        boolean shouldAnchorTransition = areaOrRoomChanged
                && (now - mLastAnchorWallMs >= mTransitionAnchorCooldownMs);
        boolean shouldAnchorPeriodic = (now - mLastAnchorWallMs >= mAnchorIntervalMs);
        if (shouldAnchorTransition) {
            captureRollingAnchor(now, "transition", snap);
        } else if (shouldAnchorPeriodic) {
            captureRollingAnchor(now, "periodic", snap);
        }

        detectAnomalies(now, deltaMs, snap);

        if (stateChanged || (now - mLastSampleWallMs >= SAMPLE_INTERVAL_MS)) {
            mSampleCount++;
            mLastSampleWallMs = now;
            writeTelemetryLine(formatSampleJson("sample", now, sessionSec, mSegmentIndex, segSec, snap, null, null));
        }

        mHasPrevLoc = true;
        mPrevModule = snap.module;
        mPrevSub = snap.sub;
        mPrevIndoors = snap.indoors;
        mPrevArea = snap.area;
        mPrevRoom = snap.room;
        mPrevWorld = snap.world;
        mPrevWasPlayable = (snap.module == 0x07 || snap.module == 0x09);
        mPrevEmuFrames = snap.emuFrames;
        mPrevRenderPresents = snap.renderPresents;
        mPrevSnesFrame = snap.snesFrame;

        if (now - mLastFlushWallMs >= mFlushIntervalMs) {
            flushTelemetryNow(now);
        }
    }

    public synchronized String addBookmark(String tag, String note) {
        if (!mActive) {
            return "No active Study Session";
        }
        long now = mHooks.nowMs();
        TelemetrySnapshot snap = mLatestSnapshot != null ? mLatestSnapshot : readTelemetry(null, null);
        mBookmarkSeq++;
        String cleanTag = (tag == null || tag.trim().isEmpty()) ? "moment" : tag.trim();
        String prefix = String.format(Locale.US, "bm_%03d_%s", mBookmarkSeq, MediaCapture.slugify(cleanTag));
        File stateFile = new File(mBookmarksDir, prefix + ".mss");
        File pngFile = new File(mBookmarksDir, prefix + ".png");
        File statusFile = new File(mBookmarksDir, prefix + "_status.json");

        boolean stateOk = mHooks.saveState(stateFile);
        boolean pngOk = mHooks.saveScreenshot(pngFile);
        String rawStatus = mHooks.statusJson(true);
        try {
            MediaCapture.writeUtf8(statusFile, rawStatus != null && !rawStatus.isEmpty() ? rawStatus : "{}\n");
        } catch (IOException ignored) {
        }

        long sessionSec = mActiveElapsedMs / 1000L;
        long segSec = mSegmentActiveMs / 1000L;
        String segFile = currentSegmentFileName();
        String stateRel = stateOk ? ("bookmarks/" + stateFile.getName()) : "";
        String pngRel = pngOk ? ("bookmarks/" + pngFile.getName()) : "";
        String statusRel = statusFile.isFile() ? ("bookmarks/" + statusFile.getName()) : "";

        BookmarkRecord rec = new BookmarkRecord(
                mBookmarkSeq, cleanTag, note != null ? note : "", now, sessionSec,
                mSegmentIndex, segFile, segSec, stateRel, pngRel, statusRel,
                snap.module, snap.sub, snap.indoors, snap.area, snap.room, snap.world, snap.x, snap.y);
        mBookmarks.add(rec);

        writeTelemetryLine("{\"event\":\"bookmark\",\"ts_ms\":" + now
                + ",\"session_sec\":" + sessionSec
                + ",\"seg\":" + mSegmentIndex
                + ",\"seg_sec\":" + segSec
                + ",\"tag\":" + MediaCapture.jsonQuote(cleanTag)
                + ",\"state\":" + MediaCapture.jsonQuote(stateRel)
                + ",\"screenshot\":" + MediaCapture.jsonQuote(pngRel)
                + ",\"module\":" + snap.module
                + ",\"area\":" + snap.area
                + ",\"room\":" + snap.room + "}");
        flushTelemetryNow(now);
        writeBookmarksJsonSafe();
        writeSessionJsonSafe(now);
        return String.format(Locale.US, "Bookmarked %s @ seg #%d %s",
                cleanTag, mSegmentIndex, MediaCapture.formatDuration(segSec));
    }

    public synchronized void flush() {
        if (!mActive) {
            return;
        }
        long now = mHooks.nowMs();
        flushTelemetryNow(now);
        writeBookmarksJsonSafe();
        writeSessionJsonSafe(now);
    }

    public synchronized String stop(String reason) {
        if (!mActive) {
            return "Study Session is not active";
        }
        long now = mHooks.nowMs();
        long sessionSec = mActiveElapsedMs / 1000L;
        mStopReason = reason != null ? reason : "manual";

        mHooks.stopVideoSegment();
        if (!mSegments.isEmpty()) {
            SegmentRecord cur = mSegments.get(mSegments.size() - 1);
            cur.endSessionSec = sessionSec;
            cur.bytes = cur.file.isFile() ? cur.file.length() : 0L;
            cur.closed = true;
        }

        writeTelemetryLine("{\"event\":\"session_end\",\"ts_ms\":" + now
                + ",\"session_sec\":" + sessionSec
                + ",\"seg\":" + mSegmentIndex
                + ",\"seg_sec\":" + (mSegmentActiveMs / 1000L)
                + ",\"reason\":" + MediaCapture.jsonQuote(mStopReason) + "}");
        flushTelemetryNow(now);
        closeTelemetryWriter();

        mActive = false;
        mCleanShutdown = true;
        writeBookmarksJsonSafe();
        writeSessionJsonSafe(now);
        return String.format(Locale.US, "Saved Study Session (%d segs, %d marks, %d anomalies)",
                mSegments.size(), mBookmarks.size(), mAnomalies.size());
    }

    private void rotateSegmentLocked(long now) {
        long sessionSec = mActiveElapsedMs / 1000L;
        mHooks.stopVideoSegment();
        if (!mSegments.isEmpty()) {
            SegmentRecord prev = mSegments.get(mSegments.size() - 1);
            prev.endSessionSec = sessionSec;
            prev.bytes = prev.file.isFile() ? prev.file.length() : 0L;
            prev.closed = true;
        }
        mSegmentIndex++;
        File nextSeg = new File(mSessionDir, String.format(Locale.US, "video_seg_%03d.mp4", mSegmentIndex));
        boolean started = mHooks.startVideoSegment(nextSeg);
        mSegments.add(new SegmentRecord(mSegmentIndex, nextSeg, sessionSec));
        writeTelemetryLine("{\"event\":\"segment_rotate\",\"ts_ms\":" + now
                + ",\"session_sec\":" + sessionSec
                + ",\"seg\":" + mSegmentIndex
                + ",\"seg_sec\":0"
                + ",\"started\":" + started + "}");
        flushTelemetryNow(now);
        writeSessionJsonSafe(now);
    }

    private void captureRollingAnchor(long now, String reason, TelemetrySnapshot snap) {
        mAnchorSeq++;
        File anchorFile = new File(mAnchorsDir, String.format(Locale.US, "anchor_%03d.mss", mAnchorSeq));
        boolean saved = mHooks.saveState(anchorFile);
        mLastAnchorWallMs = now;
        if (!saved) {
            return;
        }
        AnchorRecord rec = new AnchorRecord(anchorFile, reason, mActiveElapsedMs / 1000L,
                mSegmentIndex, mSegmentActiveMs / 1000L, snap.module, snap.area, snap.room);
        mRollingAnchors.addLast(rec);
        while (mRollingAnchors.size() > mMaxAutoAnchors) {
            AnchorRecord evicted = mRollingAnchors.removeFirst();
            if (evicted.file.exists()) {
                evicted.file.delete();
            }
        }
        writeTelemetryLine("{\"event\":\"anchor\",\"ts_ms\":" + now
                + ",\"session_sec\":" + rec.sessionSec
                + ",\"seg\":" + mSegmentIndex
                + ",\"seg_sec\":" + rec.segSec
                + ",\"reason\":" + MediaCapture.jsonQuote(reason)
                + ",\"file\":" + MediaCapture.jsonQuote("anchors/" + rec.fileName) + "}");
        writeSessionJsonSafe(now);
    }

    private void detectAnomalies(long now, long deltaMs, TelemetrySnapshot snap) {
        if (snap.fps > 0.0 && snap.fps < LOW_FPS_THRESHOLD) {
            mLowFpsStreak++;
        } else {
            mLowFpsStreak = 0;
        }

        boolean inTransitionMod = (snap.module == 0x06 || snap.module == 0x08 || snap.module == 0x0A
                || snap.module == 0x0B || snap.module == 0x0F || snap.module == 0x10)
                || ((snap.module == 0x07 || snap.module == 0x09) && snap.sub > 0);
        if (inTransitionMod && snap.module == mPrevModule && snap.sub == mPrevSub) {
            mStuckTransitionMs += deltaMs;
        } else {
            mStuckTransitionMs = 0L;
        }

        String anomalyKind = null;
        String detail = null;
        if (mPrevWasPlayable && snap.module == 0x00) {
            anomalyKind = "unexpected_title_reset";
            detail = String.format(Locale.US, "module reset to $00 from $%02X (area $%02X)", mPrevModule, mPrevArea);
        } else if (mStuckTransitionMs > STUCK_TRANSITION_MS) {
            anomalyKind = "stuck_transition";
            detail = String.format(Locale.US, "stalled at mod=$%02X sub=$%02X for %dms",
                    snap.module, snap.sub, mStuckTransitionMs);
        } else if (mLowFpsStreak >= LOW_FPS_CONSECUTIVE_TICKS) {
            anomalyKind = "low_fps";
            detail = String.format(Locale.US, "fps=%.1f (<%.0f for %d ticks)",
                    snap.fps, LOW_FPS_THRESHOLD, mLowFpsStreak);
        } else if (snap.snesLagFrames >= LAG_SPIKE_THRESHOLD) {
            anomalyKind = "snes_lag_spike";
            detail = String.format(Locale.US, "snes_lag=%df (mod=$%02X area=$%02X rm=$%04X)",
                    snap.snesLagFrames, snap.module, snap.area, snap.room);
        } else if (snap.presentDrops >= LAG_SPIKE_THRESHOLD && snap.snesLagFrames == 0) {
            anomalyKind = "emu_present_drop";
            detail = String.format(Locale.US, "present_drops=%df (presentFps=%.1f mod=$%02X)",
                    snap.presentDrops, snap.presentFps, snap.module);
        }

        if (anomalyKind == null) {
            return;
        }
        if (mLastAnomalyWallMs >= 0L && (now - mLastAnomalyWallMs < mAnomalyCooldownMs)) {
            return;
        }
        mLastAnomalyWallMs = now;
        mAnomalySeq++;

        File stateFile = new File(mAnchorsDir, String.format(Locale.US, "anom_%03d.mss", mAnomalySeq));
        File pngFile = new File(mAnchorsDir, String.format(Locale.US, "anom_%03d.png", mAnomalySeq));
        boolean stateOk = mHooks.saveState(stateFile);
        boolean pngOk = mHooks.saveScreenshot(pngFile);

        long sessionSec = mActiveElapsedMs / 1000L;
        long segSec = mSegmentActiveMs / 1000L;
        String stateRel = stateOk ? ("anchors/" + stateFile.getName()) : "";
        String pngRel = pngOk ? ("anchors/" + pngFile.getName()) : "";

        mAnomalies.add(new AnomalyRecord(mAnomalySeq, anomalyKind, detail, now, sessionSec,
                mSegmentIndex, currentSegmentFileName(), segSec, stateRel, pngRel));
        writeTelemetryLine(formatSampleJson("anomaly", now, sessionSec, mSegmentIndex, segSec, snap, anomalyKind, detail));
        flushTelemetryNow(now);
        writeSessionJsonSafe(now);
    }

    private TelemetrySnapshot readTelemetry(Double observedFps, Double presentFpsOverride) {
        Map<String, Object> status = MediaCapture.parseObjectSafe(mHooks.statusJson(false));
        int module = readByteWithStatus(status, "mode", 0x7E0010);
        int sub = readByteWithStatus(status, "sub", 0x7E0011);
        int indoors = readByteWithStatus(status, "indoor", 0x7E001B);
        int area = readByteWithStatus(status, "area", 0x7E008A);
        int room = readWordWithStatus(status, "room", 0x7E00A0, 0x7E00A1);
        int world = readByteWithStatus(status, "savedWorld", 0x7EF3CA);
        int x = readWordWithStatus(status, "linkX", 0x7E0022, 0x7E0023);
        int y = readWordWithStatus(status, "linkY", 0x7E0020, 0x7E0021);
        int hp = readByteWithStatus(status, "health", Inventory.HEALTH_CURRENT);
        int mask = readByteWithStatus(status, "linkForm", 0x7E02B2);
        int state = readByteWithStatus(status, "linkState", 0x7E005D);
        double fps = observedFps != null ? observedFps : doubleField(status, "observedFps", -1.0);
        double presentFps = presentFpsOverride != null ? presentFpsOverride : doubleField(status, "presentFps", -1.0);
        String sha1 = MiniJson.string(status.get("sha1"), "");
        int snesFrame = MediaCapture.intField(status, "snesFrame", -1);
        int snesLagFlag = MediaCapture.intField(status, "snesLagFlag", -1);
        long emuFrames = MediaCapture.longField(status, "frames", -1L);
        long renderPresents = MediaCapture.longField(status, "renderPresents", -1L);
        boolean paused = MediaCapture.boolField(status, "paused", false);
        boolean fastForward = MediaCapture.boolField(status, "fastForward", false);

        int snesLag = 0;
        int presentDrops = 0;
        if (mPrevEmuFrames >= 0L && emuFrames > mPrevEmuFrames) {
            long dEmu = emuFrames - mPrevEmuFrames;
            if (!fastForward && !paused && dEmu >= 1L && dEmu <= 60L) {
                if (mPrevRenderPresents >= 0L && renderPresents >= mPrevRenderPresents) {
                    long dPresents = renderPresents - mPrevRenderPresents;
                    presentDrops = (int) Math.max(0L, dEmu - dPresents);
                }
                boolean gameplayMod = (module == 0x07 || module == 0x09 || module == 0x0B) && sub == 0;
                if (gameplayMod && mPrevSnesFrame >= 0 && snesFrame >= 0) {
                    int dSnes = (snesFrame - mPrevSnesFrame) & 0xFF;
                    snesLag = (int) Math.max(0L, dEmu - dSnes);
                }
                mTotalGameplayEmuFrames += dEmu;
                mTotalSnesLagFrames += snesLag;
                mTotalPresentDrops += presentDrops;
                if (snesLag > mMaxTickSnesLag) {
                    mMaxTickSnesLag = snesLag;
                }
                if (presentDrops > mMaxTickPresentDrops) {
                    mMaxTickPresentDrops = presentDrops;
                }
            }
        }
        return new TelemetrySnapshot(
                module, sub, indoors, area, room, world, x, y, hp, mask, state,
                fps, presentFps, sha1, snesFrame, snesLagFlag, emuFrames,
                renderPresents, paused, fastForward, snesLag, presentDrops);
    }

    private int readByteWithStatus(Map<String, Object> status, String statusKey, int addr) {
        int val = MediaCapture.intField(status, statusKey, -1);
        return val >= 0 ? val : mHooks.readByte(addr);
    }

    private int readWordWithStatus(Map<String, Object> status, String statusKey, int loAddr, int hiAddr) {
        int val = MediaCapture.intField(status, statusKey, -1);
        if (val >= 0) {
            return val;
        }
        return MediaCapture.combineByteWord(mHooks.readByte(loAddr), mHooks.readByte(hiAddr));
    }

    private static double doubleField(Map<String, Object> map, String key, double fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return fallback;
    }

    private String formatSampleJson(String eventType, long tsMs, long sessionSec, int seg,
                                    long segSec, TelemetrySnapshot s, String anomalyKind, String detail) {
        StringBuilder sb = new StringBuilder(280);
        sb.append("{\"event\":").append(MediaCapture.jsonQuote(eventType));
        sb.append(",\"ts_ms\":").append(tsMs);
        sb.append(",\"session_sec\":").append(sessionSec);
        sb.append(",\"seg\":").append(seg);
        sb.append(",\"seg_sec\":").append(segSec);
        sb.append(",\"module\":").append(s.module);
        sb.append(",\"sub\":").append(s.sub);
        sb.append(",\"indoors\":").append(s.indoors);
        sb.append(",\"area\":").append(s.area);
        sb.append(",\"room\":").append(s.room);
        sb.append(",\"world\":").append(s.world);
        sb.append(",\"x\":").append(s.x);
        sb.append(",\"y\":").append(s.y);
        sb.append(",\"hp\":").append(s.hp);
        sb.append(",\"mask\":").append(s.mask);
        sb.append(",\"state\":").append(s.state);
        sb.append(",\"fps\":").append(s.fps > 0.0 ? String.format(Locale.US, "%.1f", s.fps) : "null");
        sb.append(",\"present_fps\":").append(s.presentFps > 0.0 ? String.format(Locale.US, "%.1f", s.presentFps) : "null");
        sb.append(",\"emu_frames\":").append(s.emuFrames);
        sb.append(",\"snes_lag_frames\":").append(s.snesLagFrames);
        sb.append(",\"present_drops\":").append(s.presentDrops);
        if (anomalyKind != null) {
            sb.append(",\"kind\":").append(MediaCapture.jsonQuote(anomalyKind));
        }
        if (detail != null) {
            sb.append(",\"detail\":").append(MediaCapture.jsonQuote(detail));
        }
        sb.append('}');
        return sb.toString();
    }

    private String currentSegmentFileName() {
        return String.format(Locale.US, "video_seg_%03d.mp4", mSegmentIndex);
    }

    private void writeTelemetryLine(String line) {
        if (mTelemetryWriter == null) return;
        try {
            mTelemetryWriter.write(line);
            mTelemetryWriter.newLine();
        } catch (IOException ignored) {
        }
    }

    private void flushTelemetryNow(long now) {
        mLastFlushWallMs = now;
        if (mTelemetryWriter == null) return;
        try {
            mTelemetryWriter.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeTelemetryWriter() {
        if (mTelemetryWriter == null) return;
        try {
            mTelemetryWriter.flush();
            mTelemetryWriter.close();
        } catch (IOException ignored) {
        } finally {
            mTelemetryWriter = null;
        }
    }

    private void writeBookmarksJsonSafe() {
        if (mBookmarksJsonFile == null) return;
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema\": ").append(MediaCapture.jsonQuote(SCHEMA_VERSION)).append(",\n");
        json.append("  \"bookmarks\": [\n");
        for (int i = 0; i < mBookmarks.size(); i++) {
            BookmarkRecord b = mBookmarks.get(i);
            json.append("    {\n");
            json.append("      \"index\": ").append(b.index).append(",\n");
            json.append("      \"tag\": ").append(MediaCapture.jsonQuote(b.tag)).append(",\n");
            json.append("      \"note\": ").append(MediaCapture.jsonQuote(b.note)).append(",\n");
            json.append("      \"ts_ms\": ").append(b.tsMs).append(",\n");
            json.append("      \"session_sec\": ").append(b.sessionSec).append(",\n");
            json.append("      \"seg\": ").append(b.segmentIndex).append(",\n");
            json.append("      \"seg_file\": ").append(MediaCapture.jsonQuote(b.segmentFile)).append(",\n");
            json.append("      \"seg_sec\": ").append(b.segSec).append(",\n");
            json.append("      \"state\": ").append(MediaCapture.jsonQuote(b.stateRel)).append(",\n");
            json.append("      \"screenshot\": ").append(MediaCapture.jsonQuote(b.screenshotRel)).append(",\n");
            json.append("      \"status\": ").append(MediaCapture.jsonQuote(b.statusRel)).append(",\n");
            json.append("      \"module\": ").append(b.module).append(", \"sub\": ").append(b.sub).append(",\n");
            json.append("      \"indoors\": ").append(b.indoors).append(", \"area\": ").append(b.area)
                    .append(", \"room\": ").append(b.room).append(", \"world\": ").append(b.world).append(",\n");
            json.append("      \"x\": ").append(b.x).append(", \"y\": ").append(b.y).append("\n");
            json.append("    }");
            if (i + 1 < mBookmarks.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        try {
            MediaCapture.writeUtf8(mBookmarksJsonFile, json.toString());
        } catch (IOException ignored) {
        }
    }

    private void writeSessionJsonSafe(long now) {
        if (mSessionJsonFile == null) return;
        long totalSec = mActiveElapsedMs / 1000L;
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema\": ").append(MediaCapture.jsonQuote(SCHEMA_VERSION)).append(",\n");
        json.append("  \"active\": ").append(mActive).append(",\n");
        json.append("  \"cleanShutdown\": ").append(mCleanShutdown).append(",\n");
        json.append("  \"stopReason\": ").append(MediaCapture.jsonQuote(mStopReason)).append(",\n");
        json.append("  \"startedUtc\": ").append(MediaCapture.jsonQuote(MediaCapture.isoTimestamp(mStartWallMs))).append(",\n");
        json.append("  \"updatedUtc\": ").append(MediaCapture.jsonQuote(MediaCapture.isoTimestamp(now))).append(",\n");
        json.append("  \"durationSec\": ").append(totalSec).append(",\n");
        json.append("  \"segmentSeconds\": ").append(mSegmentDurationMs / 1000L).append(",\n");
        json.append("  \"rom\": {\n");
        json.append("    \"name\": ").append(MediaCapture.jsonQuote(mRomName)).append(",\n");
        json.append("    \"path\": ").append(MediaCapture.jsonQuote(mRomPath)).append(",\n");
        json.append("    \"sha1\": ").append(MediaCapture.jsonQuote(mRomSha1)).append("\n");
        json.append("  },\n");
        json.append("  \"counts\": {\n");
        json.append("    \"segments\": ").append(mSegments.size()).append(",\n");
        json.append("    \"samples\": ").append(mSampleCount).append(",\n");
        json.append("    \"transitions\": ").append(mTransitionCount).append(",\n");
        json.append("    \"rollingAnchors\": ").append(mRollingAnchors.size()).append(",\n");
        json.append("    \"totalAnchorsCaptured\": ").append(mAnchorSeq).append(",\n");
        json.append("    \"bookmarks\": ").append(mBookmarks.size()).append(",\n");
        json.append("    \"anomalies\": ").append(mAnomalies.size()).append("\n");
        json.append("  },\n");
        json.append("  \"lagSummary\": {\n");
        json.append("    \"gameplayEmuFrames\": ").append(mTotalGameplayEmuFrames).append(",\n");
        json.append("    \"snesLagFrames\": ").append(mTotalSnesLagFrames).append(",\n");
        json.append("    \"presentDrops\": ").append(mTotalPresentDrops).append(",\n");
        json.append("    \"maxTickSnesLag\": ").append(mMaxTickSnesLag).append(",\n");
        json.append("    \"maxTickPresentDrops\": ").append(mMaxTickPresentDrops).append("\n");
        json.append("  },\n");
        json.append("  \"segments\": [\n");
        for (int i = 0; i < mSegments.size(); i++) {
            SegmentRecord seg = mSegments.get(i);
            long liveBytes = seg.closed ? seg.bytes : (seg.file.isFile() ? seg.file.length() : 0L);
            json.append("    {\"index\": ").append(seg.index)
                    .append(", \"file\": ").append(MediaCapture.jsonQuote(seg.fileName))
                    .append(", \"startSec\": ").append(seg.startSessionSec)
                    .append(", \"endSec\": ").append(seg.closed ? seg.endSessionSec : totalSec)
                    .append(", \"bytes\": ").append(liveBytes)
                    .append(", \"closed\": ").append(seg.closed).append("}");
            if (i + 1 < mSegments.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ],\n");
        json.append("  \"anchors\": [\n");
        int aIdx = 0;
        for (AnchorRecord a : mRollingAnchors) {
            json.append("    {\"file\": ").append(MediaCapture.jsonQuote("anchors/" + a.fileName))
                    .append(", \"reason\": ").append(MediaCapture.jsonQuote(a.reason))
                    .append(", \"sessionSec\": ").append(a.sessionSec)
                    .append(", \"seg\": ").append(a.segmentIndex)
                    .append(", \"segSec\": ").append(a.segSec)
                    .append(", \"module\": ").append(a.module)
                    .append(", \"area\": ").append(a.area)
                    .append(", \"room\": ").append(a.room).append("}");
            if (++aIdx < mRollingAnchors.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ],\n");
        json.append("  \"anomalies\": [\n");
        for (int i = 0; i < mAnomalies.size(); i++) {
            AnomalyRecord an = mAnomalies.get(i);
            json.append("    {\"index\": ").append(an.index)
                    .append(", \"kind\": ").append(MediaCapture.jsonQuote(an.kind))
                    .append(", \"detail\": ").append(MediaCapture.jsonQuote(an.detail))
                    .append(", \"sessionSec\": ").append(an.sessionSec)
                    .append(", \"seg\": ").append(an.segmentIndex)
                    .append(", \"segFile\": ").append(MediaCapture.jsonQuote(an.segmentFile))
                    .append(", \"segSec\": ").append(an.segSec)
                    .append(", \"state\": ").append(MediaCapture.jsonQuote(an.stateRel))
                    .append(", \"screenshot\": ").append(MediaCapture.jsonQuote(an.screenshotRel)).append("}");
            if (i + 1 < mAnomalies.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");
        try {
            MediaCapture.writeUtf8(mSessionJsonFile, json.toString());
        } catch (IOException ignored) {
        }
    }
}
