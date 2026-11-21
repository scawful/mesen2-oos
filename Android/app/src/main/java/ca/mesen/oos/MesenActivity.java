package ca.mesen.oos;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.compose.ui.platform.ComposeView;

import org.json.JSONObject;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MesenActivity extends SDLActivity implements OracleUi.Host {
    private static final String TAG = "MesenActivity";
    private static final String PREFS = "oracle";
    private static final String PREF_LAST_ROM = "last_rom";
    private static final String PREF_HUD = "keep_hud";
    private static final String PREF_FIT = "fill_screen";
    private static final String PREF_PLAY = "smooth_play";
    private static final String PREF_GPU = "gpu_blit";
    private static final String PREF_HOTKEYS = "capture_hotkeys";
    private static final String PREF_REC_HUD = "record_input_hud";
    private static final String PREF_REPLAY_BUF = "replay_buffer";
    private static final String PREF_TURBO_SPEED = "turbo_speed";
    private static final String HOME_SHORTCUT_ID = "oracle-open";
    private static final String HOME_SHORTCUT_PINNED = "ca.mesen.oos.HOME_SHORTCUT_PINNED";
    private static final String HOME_REQUEST_TOKEN = "home_request_token";
    private static final String ACTION_DUMP_PGO = "ca.mesen.oos.DUMP_PGO";

    private static final int TEXT_PRIMARY = Color.parseColor("#EDF0F5");
    private static final int GOLD = Color.parseColor("#E8C35A");
    private static final int TEAL = Color.parseColor("#4FB3A9");

    private static MesenActivity sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FrameRateTracker mFrameRateTracker = new FrameRateTracker();
    private final FrameRateTracker mPresentRateTracker = new FrameRateTracker();

    // Keep runtime telemetry fresh even when the compact top HUD is hidden.
    private final Runnable mStatusPoller = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed) {
                return;
            }
            if (!mUiVisible) {
                refreshHud();
            }
            mHandler.postDelayed(this, 1000);
        }
    };

    private LinearLayout mHud;
    private TextView mHudRom;
    private TextView mHudSocket;
    private TextView mHudLink;
    private TextView mRecBadge;
    private TextView mNoteView;
    private final Runnable mHideNote = () -> {
        if (mNoteView != null) {
            mNoteView.setVisibility(View.GONE);
        }
    };

    private StatusManifest mStatusManifest;
    private StatusWriter mStatusWriter;
    private CheckpointData mCheckpointData;
    private PlaySession.Jump mLastJump;
    private PresetData mPresetData;
    private List<PlaySession.Jump> mFeatureStates = Collections.emptyList();
    private OracleUi mUi;
    private ComposeView mUiView;
    private ComposeHost mComposeHost;

    private final ExecutorService mNativeControlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oos-native-ctrl");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService mBgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oos-activity-bg");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean mDestroyed;
    private volatile boolean mNativeReady;
    private volatile boolean mUiVisible;
    private volatile boolean mProgressionBusy;
    private boolean mStickyFast;
    private int mUiHatY;
    private int mUiHatX;
    private volatile boolean mKeepHud;
    private volatile boolean mFillScreen;
    private volatile boolean mSmoothPlay;
    private volatile boolean mPreferGpu;
    private volatile boolean mCaptureHotkeys = true;
    private volatile boolean mRecordInputHud;
    private volatile boolean mReplayBufferEnabled;
    private volatile int mOverlayMask;
    private boolean mFastForwardHeld;
    private boolean mFastForwardKeyHeld;
    private boolean mFastForwardTriggerHeld;
    private volatile int mTurboSpeed = FrameRateTracker.DEFAULT_TURBO_SPEED;

    private final Object mCaptureLock = new Object();
    private final TestingTools mTestingTools = new TestingTools((addrs, vals) -> {
        if (mNativeReady && !mDestroyed) {
            MesenNative.setRamCheats(addrs, vals);
        }
    });
    private volatile long mVideoStartElapsedMs;
    private boolean mL2KeyHeld;
    private boolean mL2TriggerHeld;
    private boolean mL2Held;
    private boolean mL2HoldFired;
    private final Runnable mL2HoldRunnable = () -> {
        if (mL2Held && !mUiVisible && mCaptureHotkeys && !mDestroyed) {
            mL2HoldFired = true;
            triggerToggleVideoFromHotkey();
        }
    };
    private boolean mPendingStartVideo;
    private boolean mPendingScreenshot;
    private boolean mPendingDumpReplay;
    private Boolean mPendingReplayBuffer;
    private Integer mPendingOverlayMask;
    private String mPendingBugSnapshotCategory;

    private boolean mHomeReceiverRegistered;
    private boolean mPgoDumpReceiverRegistered;
    private String mHomeRequestToken;

    private final BroadcastReceiver mHomeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HOME_SHORTCUT_PINNED.equals(intent.getAction()) && mHomeRequestToken != null
                    && mHomeRequestToken.equals(intent.getStringExtra(HOME_REQUEST_TOKEN))) {
                mHomeRequestToken = null;
                Toast.makeText(MesenActivity.this, R.string.home_added, Toast.LENGTH_LONG).show();
            }
        }
    };

    private final BroadcastReceiver mPgoDumpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DUMP_PGO.equals(intent.getAction()) || !mNativeReady) {
                return;
            }
            try {
                MesenNative.dumpPgoProfile();
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
    };

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        if (intent != null) {
            String rom = intent.getStringExtra("rom");
            if (rom != null && !rom.isEmpty()) {
                rememberRom(rom);
                seedBatterySave(rom);
                return new String[] { rom };
            }
            Uri data = intent.getData();
            if (data != null && data.getPath() != null && !data.getPath().isEmpty()) {
                rememberRom(data.getPath());
                seedBatterySave(data.getPath());
                return new String[] { data.getPath() };
            }
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, null);
        if (last != null && new File(last).isFile()) {
            seedBatterySave(last);
            return new String[] { last };
        }
        return new String[0];
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        sInstance = this;
        super.onCreate(savedInstanceState);
        applyImmersive();
        requestPlaybackFocus();
        mStatusManifest = new StatusManifest(this);
        mStatusWriter = new StatusWriter(rawJson -> mStatusManifest.write(new JSONObject(rawJson)));
        String warpsJson = readAssetText(CheckpointData.WARPS_ASSET);
        mCheckpointData = CheckpointData.parse(
                readAssetText(CheckpointData.CHECKPOINTS_ASSET),
                warpsJson);
        mPresetData = PresetData.parse(
                readAssetText(PresetData.PRESETS_ASSET),
                mCheckpointData,
                warpsJson);
        mFeatureStates = PlaySession.savestateJumps(
                readAssetText("oracle/save_state_library.json"));
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mKeepHud = prefs.getBoolean(PREF_HUD, false);
        mFillScreen = prefs.getBoolean(PREF_FIT, false);
        mSmoothPlay = prefs.getBoolean(PREF_PLAY, true);
        mPreferGpu = prefs.getBoolean(PREF_GPU, true);
        mCaptureHotkeys = prefs.getBoolean(PREF_HOTKEYS, true);
        mRecordInputHud = prefs.getBoolean(PREF_REC_HUD, false);
        mReplayBufferEnabled = prefs.getBoolean(PREF_REPLAY_BUF, false);
        mTurboSpeed = FrameRateTracker.sanitizeTurboSpeed(
                prefs.getInt(PREF_TURBO_SPEED, FrameRateTracker.DEFAULT_TURBO_SPEED));
        attachHud();
        attachOracleUi();
        applyIntentPrefs(getIntent());
        // Delay shade collapse until after SurfaceView binds.
        startNativeIfNeeded();
        mHandler.post(this::startNativeIfNeeded);
        for (int delayMs : new int[] {250, 500, 1000, 2000, 4000}) {
            mHandler.postDelayed(this::startNativeIfNeeded, delayMs);
        }
        mHandler.postDelayed(this::collapseNotificationShade, 600);
        mHandler.postDelayed(this::markNativeReady, 1200);
        mHandler.postDelayed(this::refreshHud, 1500);
        mHandler.postDelayed(this::hideHudBar, 4500);
        keepVisibleScreenOn();
        registerPgoDumpReceiver();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (sInstance == this) {
            sInstance = null;
        }
        clearCaptureHotkeys();
        stopRecordingIfActive(false);
        mHandler.removeCallbacksAndMessages(null);
        if (mStatusWriter != null) {
            mStatusWriter.shutdown();
        }
        mNativeControlExecutor.shutdown();
        mBgExecutor.shutdown();
        if (mHomeReceiverRegistered) {
            unregisterReceiver(mHomeReceiver);
            mHomeReceiverRegistered = false;
        }
        if (mPgoDumpReceiverRegistered) {
            unregisterReceiver(mPgoDumpReceiver);
            mPgoDumpReceiverRegistered = false;
        }
        mHomeRequestToken = null;
        if (mComposeHost != null) {
            mComposeHost.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            startNativeIfNeeded();
            mHandler.postDelayed(this::collapseNotificationShade, 300);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepVisibleScreenOn();
        applyImmersive();
        startNativeIfNeeded();
        mHandler.postDelayed(this::collapseNotificationShade, 400);
        if (mComposeHost != null) {
            mComposeHost.onResume();
        }
        if (mNativeReady && mReplayBufferEnabled && !mBgExecutor.isShutdown()) {
            mBgExecutor.execute(this::syncReplayBuffer);
        }
    }

    @Override
    protected void onPause() {
        clearFastForward();
        clearCaptureHotkeys();
        if (mComposeHost != null) {
            mComposeHost.onPause();
        }
        if (mNativeReady) {
            try {
                MesenNative.dumpPgoProfile();
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        clearFastForward();
        clearCaptureHotkeys();
        stopRecordingIfActive(true);
        if (mNativeReady) {
            try {
                MesenNative.dumpPgoProfile();
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentPrefs(intent);
        if (intent != null) {
            final String rom = intent.getStringExtra("rom");
            if (rom != null && !rom.isEmpty() && mNativeReady) {
                mBgExecutor.execute(() -> {
                    if (!mDestroyed) {
                        bootRom(rom);
                    }
                });
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mUiVisible) {
            mUi.onBack();
        } else {
            openQuickMenu();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mUiVisible) {
                    mUi.onBack();
                } else {
                    openQuickMenu();
                }
            }
            return true;
        }
        if (mUiVisible) {
            // Keep SDL's pad state current so the game does not see a stuck button after closing.
            forwardPadToSdl(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mUi.onKey(code, event.getRepeatCount());
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                setFastForwardKeyHeld(true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                setFastForwardKeyHeld(false);
            }
            return true;
        }
        if (mCaptureHotkeys) {
            if (code == KeyEvent.KEYCODE_BUTTON_L2) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    mL2KeyHeld = true;
                    updateL2CaptureHeld();
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    mL2KeyHeld = false;
                    updateL2CaptureHeld();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    triggerScreenshotFromHotkey();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    triggerToggleVideoFromHotkey();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        if (mUiVisible) {
            if ((source & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD
                    | InputDevice.SOURCE_GAMEPAD)) == 0) {
                return super.dispatchGenericMotionEvent(event);
            }
            SDLControllerManager.handleJoystickMotionEvent(event);
            int dirY = axisDir(event.getAxisValue(MotionEvent.AXIS_HAT_Y), event.getAxisValue(MotionEvent.AXIS_Y));
            if (dirY != 0 && dirY != mUiHatY) {
                mUi.onKey(dirY < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            }
            mUiHatY = dirY;
            int dirX = axisDir(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_X));
            if (dirX != 0 && dirX != mUiHatX) {
                mUi.onKey(dirX < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, 0);
            }
            mUiHatX = dirX;
            return true;
        }
        if ((source & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) != 0) {
            float trigger = Math.max(
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS));
            setFastForwardTriggerHeld(trigger > 0.55f);
            float lTrigger = Math.max(
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE));
            if (mCaptureHotkeys) {
                mL2TriggerHeld = lTrigger > 0.55f;
                updateL2CaptureHeld();
            } else {
                mL2TriggerHeld = false;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    public static void showPauseFromNative() {
        MesenActivity activity = sInstance;
        if (activity == null) {
            return;
        }
        activity.mHandler.post(activity::openQuickMenu);
    }

    private void attachHud() {
        if (mLayout == null) {
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        int padH = Math.round(10 * dp);
        int padV = Math.round(6 * dp);

        mHud = new LinearLayout(this);
        mHud.setOrientation(LinearLayout.VERTICAL);
        mHud.setBackgroundResource(R.drawable.hud_scrim);
        mHud.setPadding(padH, padV, padH, padV);
        mHud.setFocusable(false);
        mHud.setClickable(true);
        mHud.setOnClickListener(v -> openQuickMenu());
        mHud.setVisibility(View.GONE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText(R.string.hud_brand);
        brand.setTextColor(GOLD);
        brand.setTextSize(11);
        brand.setAllCaps(true);
        brand.setTypeface(Typeface.DEFAULT_BOLD);

        mHudRom = new TextView(this);
        mHudRom.setText(R.string.hud_waiting);
        mHudRom.setTextColor(TEXT_PRIMARY);
        mHudRom.setTextSize(11);
        mHudRom.setSingleLine(true);
        mHudRom.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams romLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        romLp.setMarginStart(Math.round(12 * dp));

        mHudSocket = new TextView(this);
        mHudSocket.setText("tcp:" + StatusManifest.PORT);
        mHudSocket.setTextColor(TEAL);
        mHudSocket.setTextSize(10);
        mHudSocket.setTypeface(Typeface.MONOSPACE);

        top.addView(brand);
        top.addView(mHudRom, romLp);
        top.addView(mHudSocket);

        mHudLink = new TextView(this);
        mHudLink.setTextColor(TEAL);
        mHudLink.setTextSize(10);
        mHudLink.setTypeface(Typeface.MONOSPACE);
        mHudLink.setSingleLine(true);
        mHudLink.setEllipsize(TextUtils.TruncateAt.END);
        mHudLink.setVisibility(View.GONE);

        mHud.addView(top);
        mHud.addView(mHudLink);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLayout.addView(mHud, params);

        mRecBadge = new TextView(this);
        mRecBadge.setTextColor(Color.WHITE);
        mRecBadge.setTextSize(11);
        mRecBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        int rPadH = Math.round(8 * dp);
        int rPadV = Math.round(4 * dp);
        mRecBadge.setPadding(rPadH, rPadV, rPadH, rPadV);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.argb(204, 158, 27, 27));
        badgeBg.setCornerRadius(6 * dp);
        mRecBadge.setBackground(badgeBg);
        mRecBadge.setFocusable(false);
        mRecBadge.setClickable(true);
        mRecBadge.setOnClickListener(v -> openQuickMenu());
        mRecBadge.setVisibility(View.GONE);
        RelativeLayout.LayoutParams recLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        recLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        int rMargin = Math.round(8 * dp);
        recLp.setMargins(rMargin, rMargin, rMargin, rMargin);
        mLayout.addView(mRecBadge, recLp);
    }

    private void attachOracleUi() {
        mComposeHost = new ComposeHost();
        mUiView = new ComposeView(this);
        mComposeHost.attach(mUiView);
        if (mLayout != null) {
            mComposeHost.tag(mLayout);
            mComposeHost.tag(mLayout.getRootView());
            mLayout.addView(mUiView, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mComposeHost.tag(getWindow().getDecorView());
        mUiView.setVisibility(View.GONE);
        mUiView.setFocusable(false);
        mUi = new OracleUi(this);
        mUi.install(mUiView);
    }

    private static int axisDir(float hat, float axis) {
        if (hat < -0.5f || axis < -0.6f) {
            return -1;
        }
        if (hat > 0.5f || axis > 0.6f) {
            return 1;
        }
        return 0;
    }

    private void openQuickMenu() {
        if (mUi == null || mUiVisible || mProgressionBusy) {
            return;
        }
        mUi.openMenu();
    }

    // ---- OracleUi.Host ---------------------------------------------------

    @Override
    public boolean nativeReady() {
        return mNativeReady && !mDestroyed;
    }

    @Override
    public File[] romRoots() {
        return new File[] {
                new File(getFilesDir(), "Roms"),
                getFilesDir(),
                getExternalFilesDir(null),
                new File("/storage/emulated/0/Android/data/ca.mesen.oos/files"),
                new File("/storage/emulated/0/OracleOfSecrets"),
                new File("/storage/0000-0000/snes")
        };
    }

    @Override
    public File[] playtestRoots() {
        File ext = getExternalFilesDir(null);
        return new File[] {
                ext != null ? new File(ext, PlaytestShortcuts.FOLDER_NAME) : null,
                new File("/storage/emulated/0/Android/data/ca.mesen.oos/files", PlaytestShortcuts.FOLDER_NAME),
                new File(getFilesDir(), PlaytestShortcuts.FOLDER_NAME)
        };
    }

    @Override
    public PlaytestShortcuts.LaunchResult launchPlaytestShortcut(PlaytestShortcuts.Card card) {
        return PlaytestShortcuts.launchCard(card, new PlaytestShortcuts.Launcher() {
            @Override
            public String currentRomPath() {
                return MesenActivity.this.currentRomPath();
            }

            @Override
            public File[] romRoots() {
                return MesenActivity.this.romRoots();
            }

            @Override
            public File savesDir() {
                return MesenActivity.this.savesDir();
            }

            @Override
            public boolean bootRom(String path) {
                return MesenActivity.this.bootRom(path);
            }

            @Override
            public boolean loadStateFile(String path) {
                return mNativeReady && !mDestroyed && path != null && MesenNative.loadStateFile(path);
            }

            @Override
            public boolean writeSram(byte[] data) {
                if (!mNativeReady || mDestroyed || data == null) {
                    return false;
                }
                int written = 0;
                for (int i = 0; i < data.length; i++) {
                    if (!MesenNative.writeSram(i, data[i] & 0xFF)) {
                        break;
                    }
                    written++;
                }
                return written > 0;
            }

            @Override
            public void settleAfterRomLoad() {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @Override
    public String currentRomPath() {
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, null);
        if (last != null && new File(last).isFile()) {
            return last;
        }
        if (mNativeReady && !mDestroyed) {
            try {
                String raw = MesenNative.statusJson(false);
                if (raw != null && !raw.isEmpty()) {
                    String path = new JSONObject(raw).optString("romPath", "");
                    if (!path.isEmpty() && new File(path).isFile()) {
                        return path;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Override
    public String batterySaveLabel(File rom) {
        if (BatterySaveSeed.target(rom, savesDir()).isFile()) {
            return "Save";
        }
        File sidecar = BatterySaveSeed.sidecar(rom);
        return sidecar != null && sidecar.isFile() ? "Test save" : "";
    }

    @Override
    public String romSourceLabel(File rom) {
        return romSource(rom);
    }

    @Override
    public File statesDir() {
        if (mNativeReady) {
            String path = MesenNative.saveStatePath(1);
            if (path != null && !path.isEmpty()) {
                File parent = new File(path).getParentFile();
                if (parent != null) {
                    return parent;
                }
            }
        }
        return new File(getFilesDir(), "SaveStates");
    }

    @Override
    public File savesDir() {
        return new File(getFilesDir(), "Saves");
    }

    @Override
    public File capturesDir() {
        return MediaCapture.capturesRoot(getExternalFilesDir(null), getFilesDir());
    }

    @Override
    public List<MediaCapture.CaptureItem> recentCaptures() {
        return MediaCapture.listRecent(capturesDir(), 24);
    }

    @Override
    public String takeScreenshot() {
        if (!mNativeReady || mDestroyed) {
            return "Emulator is still starting";
        }
        synchronized (mCaptureLock) {
            File dir = MediaCapture.screenshotsDir(capturesDir());
            File out = MediaCapture.nextMediaFile(dir, romDisplayName(), "png", System.currentTimeMillis());
            if (out == null || !MesenNative.takeScreenshot(out.getAbsolutePath()) || !out.isFile() || out.length() == 0) {
                return "Screenshot failed (no frame ready)";
            }
            MediaGallery.publish(this, out, false);
            return "Saved screenshot: " + out.getName();
        }
    }

    @Override
    public boolean isVideoRecording() {
        return mNativeReady && !mDestroyed && MesenNative.isVideoRecording();
    }

    @Override
    public long videoRecordingElapsedSeconds() {
        if (!isVideoRecording()) {
            return 0L;
        }
        long start = mVideoStartElapsedMs;
        if (start <= 0L) {
            return 0L;
        }
        return Math.max(0L, (SystemClock.elapsedRealtime() - start) / 1000L);
    }

    @Override
    public String toggleVideoRecording() {
        if (!mNativeReady || mDestroyed) {
            return "Emulator is still starting";
        }
        synchronized (mCaptureLock) {
            if (MesenNative.isVideoRecording()) {
                return stopRecordingLocked(true);
            }
            File dir = MediaCapture.videosDir(capturesDir());
            File out = MediaCapture.nextMediaFile(dir, romDisplayName(), "mp4", System.currentTimeMillis());
            if (out == null || !MesenNative.startVideoRecording(out.getAbsolutePath(), mRecordInputHud, false)) {
                return "Failed to start MP4 video recorder";
            }
            mVideoStartElapsedMs = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                if (!mDestroyed) {
                    updateRecBadgeUi(true);
                }
            });
            return "Recording MP4: " + out.getName();
        }
    }

    @Override
    public String captureBugSnapshot(String category) {
        if (!mNativeReady || mDestroyed) {
            return "Emulator is still starting";
        }
        synchronized (mCaptureLock) {
            File reports = MediaCapture.reportsDir(capturesDir());
            MediaCapture.BugSnapshotResult res = MediaCapture.writeBugSnapshot(
                    reports,
                    romDisplayName(),
                    category,
                    "",
                    new MediaCapture.SnapshotHooks() {
                        @Override
                        public boolean saveScreenshot(File pngFile) {
                            return MesenNative.takeScreenshot(pngFile.getAbsolutePath());
                        }

                        @Override
                        public boolean saveState(File stateFile) {
                            return MesenNative.saveStateFile(stateFile.getAbsolutePath());
                        }

                        @Override
                        public boolean saveReplayVideo(File mp4File) {
                            return MesenNative.isReplayBufferActive()
                                    && MesenNative.dumpReplayBuffer(mp4File.getAbsolutePath());
                        }

                        @Override
                        public String statusJson() {
                            return MesenNative.statusJson(true);
                        }

                        @Override
                        public int readByte(int addr) {
                            return MesenNative.readByte(addr);
                        }

                        @Override
                        public String romPath() {
                            return currentRomPath();
                        }
                    },
                    System.currentTimeMillis(),
                    mLastJump);
            return res.message;
        }
    }

    @Override
    public boolean isReplayBufferActive() {
        return mNativeReady && !mDestroyed && MesenNative.isReplayBufferActive();
    }

    @Override
    public int replayBufferedSeconds() {
        return mNativeReady && !mDestroyed ? MesenNative.replayBufferedSeconds() : 0;
    }

    @Override
    public String dumpReplayClip() {
        if (!mNativeReady || mDestroyed) {
            return "Emulator is still starting";
        }
        synchronized (mCaptureLock) {
            if (!MesenNative.isReplayBufferActive()) {
                return "Replay buffer is off — enable Replay buffer (20s) first";
            }
            File dir = MediaCapture.videosDir(capturesDir());
            File out = MediaCapture.nextMediaFile(dir, "replay_" + romDisplayName(), "mp4", System.currentTimeMillis());
            if (out == null || !MesenNative.dumpReplayBuffer(out.getAbsolutePath()) || !out.isFile() || out.length() == 0) {
                return "Replay buffer has no complete keyframe yet (wait ~2s)";
            }
            MediaGallery.publish(this, out, true);
            return "Saved 20s replay: " + out.getName();
        }
    }

    @Override
    public int debugOverlayMask() {
        return mNativeReady && !mDestroyed ? MesenNative.debugOverlayMask() : mOverlayMask;
    }

    @Override
    public String toggleDebugOverlayBit(int bit) {
        if (!mNativeReady || mDestroyed) {
            return "Emulator is still starting";
        }
        int cur = MesenNative.debugOverlayMask();
        int next = (cur ^ bit) & 0x07;
        mOverlayMask = next;
        MesenNative.setDebugOverlayMask(next);
        String name;
        switch (bit) {
            case 0x01: name = "Hitboxes & sprites"; break;
            case 0x02: name = "Dungeon collision"; break;
            case 0x04: name = "16x16 grid & seams"; break;
            default: name = "Overlay"; break;
        }
        return name + ": " + ((next & bit) != 0 ? "ON" : "OFF");
    }

    @Override
    public List<PlaySession.Jump> featureStates() {
        return mFeatureStates;
    }

    @Override
    public boolean loadFeatureState(String statePath) {
        if (!mNativeReady || mDestroyed) return false;
        File file = PlaySession.resolveStateFile(oracleLibraryRoot(), statePath);
        return file != null && file.isFile() && MesenNative.loadStateFile(file.getAbsolutePath());
    }

    private File oracleLibraryRoot() {
        File external = getExternalFilesDir(null);
        return new File(external != null ? external : getFilesDir(), "OracleLibrary");
    }

    @Override
    public TestingTools testingTools() {
        return mTestingTools;
    }

    @Override
    public int activeSaveSlot() {
        return mNativeReady && !mDestroyed ? Progression.activeSlot(Progression.LIVE) : 1;
    }

    @Override
    public String saveStatePath(int slot) {
        return mNativeReady && !mDestroyed ? MesenNative.saveStatePath(slot) : null;
    }

    @Override
    public String toggleCheat(TestingTools.Cheat cheat) {
        return mTestingTools.toggleCheat(cheat, Inventory.LIVE);
    }

    @Override
    public String clearCheats() {
        return mTestingTools.clearAll(Inventory.LIVE);
    }

    @Override
    public String runLiveAction(TestingTools.LiveAction action) {
        return mTestingTools.runAction(action, Inventory.LIVE, this::stepFrames);
    }

    @Override
    public boolean loadCaptureState(File stateFile) {
        boolean loaded = mNativeReady && !mDestroyed && stateFile != null
                && MesenNative.loadStateFile(stateFile.getAbsolutePath());
        if (loaded) {
            mLastJump = new PlaySession.Jump("savestate:" + stateFile.getName(), stateFile.getName(),
                    "savestate", stateFile.getName(), "", stateFile.getName(), "", "");
        }
        return loaded;
    }

    @Override
    public boolean stepFrames(int frames) {
        return mNativeReady && !mDestroyed && MesenNative.stepFrames(frames);
    }

    @Override
    public CheckpointData checkpointData() {
        return mCheckpointData;
    }

    @Override
    public PresetData presetData() {
        return mPresetData;
    }

    @Override
    public String socketSummary() {
        if (mStatusManifest == null) {
            return "tcp://127.0.0.1:" + StatusManifest.PORT;
        }
        int agents = mStatusManifest.agentCount();
        String agentBit = agents > 0 ? (agents + " agents") : "adb-forward ready";
        return mStatusManifest.adbEndpoint() + " · " + agentBit;
    }

    @Override
    public void uiOpened() {
        if (mUiView == null) {
            return;
        }
        mUiVisible = true;
        mUiHatY = 0;
        mUiHatX = 0;
        clearFastForward();
        clearCaptureHotkeys();
        updateRecBadgeUi(false);
        mUiView.setVisibility(View.VISIBLE);
        setSurfaceFocusable(false);
        if (mHud != null) {
            mHud.animate().cancel();
            mHud.setVisibility(View.GONE);
        }
        if (mNativeReady) {
            mNativeControlExecutor.execute(() -> {
                if (mNativeReady && !mDestroyed && mUiVisible) {
                    MesenNative.pauseEmu();
                }
            });
        }
    }

    @Override
    public void uiClosed() {
        mUiVisible = false;
        mUiHatY = 0;
        mUiHatX = 0;
        if (mUiView != null) {
            mUiView.setVisibility(View.GONE);
        }
        if (mNativeReady) {
            mNativeControlExecutor.execute(() -> {
                if (mNativeReady && !mDestroyed && !mUiVisible) {
                    MesenNative.resumeEmu();
                }
            });
        }
        setSurfaceFocusable(true);
        updateFastForward();
        if (mKeepHud) {
            showHudBar();
        }
        refreshHud();
    }

    @Override
    public boolean bootRom(String path) {
        if (!mNativeReady || mDestroyed) {
            return false;
        }
        stopRecordingIfActive(true);
        BatterySaveSeed.Status seeded = seedBatterySave(path);
        if (seeded == BatterySaveSeed.Status.SEEDED) {
            File sidecar = BatterySaveSeed.sidecar(new File(path));
            if (sidecar != null) {
                final String msg = "Imported " + sidecar.getName();
                mHandler.post(() -> {
                    if (!mDestroyed) {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        if (!MesenNative.loadRom(path)) {
            return false;
        }
        rememberRom(path);
        applyTurboSpeed();
        applyPerformanceMode();
        if (mTestingTools.activeCheatCount() > 0) {
            mTestingTools.syncCheats(Inventory.LIVE);
        }
        if (mReplayBufferEnabled) {
            syncReplayBuffer();
        }
        if (mOverlayMask != 0) {
            MesenNative.setDebugOverlayMask(mOverlayMask);
        }
        // Warm ROM SHA1 cache off the main thread before refreshHud() runs.
        MesenNative.statusJson(false);
        mHandler.post(() -> {
            if (!mDestroyed) {
                refreshHud();
            }
        });
        return true;
    }

    @Override
    public void resetConsole() {
        if (mNativeReady) {
            MesenNative.resetEmu();
        }
    }

    @Override
    public boolean isStickyFastForward() {
        return mStickyFast;
    }

    @Override
    public void setStickyFastForward(boolean on) {
        mStickyFast = on;
        updateFastForward();
    }

    @Override
    public int turboSpeedPercent() {
        return mTurboSpeed;
    }

    @Override
    public String turboSpeedLabel() {
        return FrameRateTracker.turboSpeedLabel(mTurboSpeed);
    }

    @Override
    public void setTurboSpeedPercent(int percent) {
        int next = FrameRateTracker.sanitizeTurboSpeed(percent);
        if (next == mTurboSpeed && mNativeReady) {
            // Still push through in case ApplyHandheld reset the core value.
            applyTurboSpeed();
            return;
        }
        mTurboSpeed = next;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_TURBO_SPEED, next).apply();
        applyTurboSpeed();
        refreshHud();
    }

    @Override
    public int cycleTurboSpeed(int delta) {
        int next = FrameRateTracker.cycleTurboSpeed(mTurboSpeed, delta);
        setTurboSpeedPercent(next);
        return next;
    }

    @Override
    public boolean setting(String key) {
        switch (key) {
            case "hud": return mKeepHud;
            case "fit": return mFillScreen;
            case "play": return mSmoothPlay;
            case "gpu": return mPreferGpu;
            case "hotkeys": return mCaptureHotkeys;
            case "rec_hud": return mRecordInputHud;
            case "replay": return mReplayBufferEnabled;
            default: return false;
        }
    }

    @Override
    public void setSetting(String key, boolean on) {
        switch (key) {
            case "hud":
                setKeepHud(on);
                if (mUiVisible && mHud != null) {
                    mHud.animate().cancel();
                    mHud.setVisibility(View.GONE);
                }
                break;
            case "fit": setFillScreen(on); break;
            case "play": setSmoothPlay(on); break;
            case "gpu": setPreferGpu(on); break;
            case "hotkeys": setCaptureHotkeys(on); break;
            case "rec_hud": setRecordInputHud(on); break;
            case "replay": setReplayBufferEnabled(on); break;
            default: break;
        }
    }

    @Override
    public void startCheckpoint(String id) {
        if (mCheckpointData == null) {
            return;
        }
        Progression.Checkpoint target = mCheckpointData.find(id);
        if (target != null) {
            mLastJump = new PlaySession.Jump("checkpoint:" + target.id, target.id, "checkpoint",
                    target.label, "", "", target.id, "");
        }
        runProgression(target);
    }

    @Override
    public void startPreset(String id) {
        if (mPresetData == null) {
            return;
        }
        PresetData.Preset preset = mPresetData.find(id);
        if (preset == null || preset.checkpoint == null) {
            return;
        }
        runProgression(preset.checkpoint);
    }

    @Override
    public void startWarp(Progression.Checkpoint target) {
        if (target == null) {
            return;
        }
        String kind = target.kind == Progression.Kind.AREA ? "warp-area" : "warp-entrance";
        String warpId = String.format(Locale.ROOT, "0x%02X", target.target & 0xFF);
        mLastJump = new PlaySession.Jump(kind + ":" + warpId, warpId, kind, target.label, "", "", "", warpId);
        runBlockingAction("Warping to " + target.label + "…",
                () -> Progression.warpOnly(target, Progression.LIVE));
    }

    @Override
    public void fallToDungeonRoom(int roomId) {
        String label = String.format(Locale.ROOT, "$%04X", roomId & 0xFFFF);
        runBlockingAction("Dropping into room " + label + "…",
                () -> Progression.fallToRoom(roomId, Progression.LIVE));
    }

    private void runProgression(final Progression.Checkpoint target) {
        if (target == null) {
            return;
        }
        runBlockingAction(getString(R.string.progression_running, target.label),
                () -> Progression.apply(target, Progression.LIVE));
    }

    private void runBlockingAction(String startToast, Callable<String> task) {
        if (mProgressionBusy || !mNativeReady || task == null) {
            return;
        }
        mProgressionBusy = true;
        Toast.makeText(this, startToast, Toast.LENGTH_SHORT).show();
        Thread worker = new Thread(() -> {
            String result;
            try {
                result = task.call();
            } catch (Exception e) {
                result = "Failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            final String finalResult = result;
            mHandler.post(() -> {
                mProgressionBusy = false;
                Log.i(TAG, "action: " + finalResult);
                Toast.makeText(this, finalResult, Toast.LENGTH_LONG).show();
            });
        }, "oos-progression");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void toast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        mHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void requestHomeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast(getString(R.string.home_unsupported));
            return;
        }
        try {
            ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
            if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
                toast(getString(R.string.home_unsupported));
                return;
            }
            if (!mHomeReceiverRegistered) {
                IntentFilter filter = new IntentFilter(HOME_SHORTCUT_PINNED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(mHomeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(mHomeReceiver, filter);
                }
                mHomeReceiverRegistered = true;
            }
            Intent open = new Intent(Intent.ACTION_MAIN, null, this, MesenActivity.class);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, HOME_SHORTCUT_ID)
                    .setShortLabel(getString(R.string.app_name))
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(open)
                    .build();
            mHomeRequestToken = UUID.randomUUID().toString();
            Intent result = new Intent(HOME_SHORTCUT_PINNED).setPackage(getPackageName())
                    .putExtra(HOME_REQUEST_TOKEN, mHomeRequestToken);
            PendingIntent callback = PendingIntent.getBroadcast(this, 0, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_ONE_SHOT);
            if (shortcuts.requestPinShortcut(shortcut, callback.getIntentSender())) {
                toast(getString(R.string.home_requested));
            } else {
                mHomeRequestToken = null;
                toast(getString(R.string.home_unsupported));
            }
        } catch (RuntimeException e) {
            mHomeRequestToken = null;
            Log.w(TAG, "Home shortcut request failed", e);
            toast(getString(R.string.home_failed));
        }
    }

    private String readAssetText(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Missing asset " + path + ": " + e.getMessage());
            return null;
        }
    }

    private void markNativeReady() {
        mNativeReady = true;
        applyPresentMode();
        applyTurboSpeed();
        applyPerformanceMode();
        applyPreferAccelerated();
        refreshHud();
        mHandler.removeCallbacks(mStatusPoller);
        mHandler.post(mStatusPoller);
        if (mKeepHud) {
            showHudBar();
        }
        Intent intent = getIntent();
        boolean romFromIntent = intent != null && intent.hasExtra("rom");
        if (mUi != null && !romFromIntent && !mUiVisible) {
            mUi.openLibrary();
        }
        if (mOverlayMask != 0) {
            MesenNative.setDebugOverlayMask(mOverlayMask);
        }
        if (mReplayBufferEnabled && !mBgExecutor.isShutdown()) {
            mHandler.postDelayed(() -> {
                if (!mDestroyed && !mBgExecutor.isShutdown()) {
                    mBgExecutor.execute(this::syncReplayBuffer);
                }
            }, 350);
        }
        if (mPendingStartVideo || mPendingScreenshot || mPendingDumpReplay
                || mPendingReplayBuffer != null || mPendingOverlayMask != null
                || mPendingBugSnapshotCategory != null) {
            mHandler.postDelayed(this::dispatchPendingCaptureActions, 400);
        }
    }

    private void keepVisibleScreenOn() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        window.getDecorView().setKeepScreenOn(true);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && Build.VERSION.SDK_INT >= 26) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void applyImmersive() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = window.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
    }

    /** Best-effort collapse of the notification shade during adb sessions. */
    private void collapseNotificationShade() {
        try {
            Object statusBar = getSystemService("statusbar");
            if (statusBar == null) {
                return;
            }
            java.lang.reflect.Method collapse = statusBar.getClass()
                    .getMethod(Build.VERSION.SDK_INT >= 17 ? "collapsePanels" : "collapse");
            collapse.invoke(statusBar);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private void registerPgoDumpReceiver() {
        if (mPgoDumpReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_DUMP_PGO);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mPgoDumpReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mPgoDumpReceiver, filter);
        }
        mPgoDumpReceiverRegistered = true;
    }

    private void showHudBar() {
        if (mHud == null || mUiVisible) {
            return;
        }
        mHud.setVisibility(View.VISIBLE);
        mHud.animate().alpha(1f).setDuration(180).start();
    }

    private void hideHudBar() {
        if (mHud == null || mKeepHud) {
            return;
        }
        mHud.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (!mKeepHud) {
                mHud.setVisibility(View.GONE);
            }
        }).start();
    }

    private void refreshHud() {
        if (!mNativeReady || mHudRom == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject(MesenNative.statusJson(mKeepHud));
            String rom = json.optString("rom", "");
            mHudRom.setText(rom.isEmpty() ? getString(R.string.hud_waiting) : rom);
            String socket = json.optString("socket", "tcp:" + StatusManifest.PORT);
            boolean pad = json.optBoolean("pad", false);
            String sha1 = json.optString("sha1", "");
            boolean paused = json.optBoolean("paused", false);
            Double observedFps = mFrameRateTracker.sample(
                    rom, json.optLong("frames", -1), paused,
                    SystemClock.elapsedRealtime());
            Double presentFps = mPresentRateTracker.sample(
                    rom, json.optLong("renderPresents", -1), paused,
                    SystemClock.elapsedRealtime());
            String performance = performanceLabel(paused, observedFps);
            if (json.optBoolean("fastForward", mFastForwardHeld)) {
                performance += " · " + FrameRateTracker.formatFastForwardLabel(observedFps)
                        + " " + FrameRateTracker.turboSpeedLabel(mTurboSpeed);
            }
            if (json.optBoolean("performanceMode", mSmoothPlay)) {
                performance += " · SMOOTH";
            }
            if (json.optBoolean("renderAccelerated", false)) {
                performance += " · GPU";
            } else if (json.optBoolean("preferAccelerated", mPreferGpu)) {
                performance += " · SW";
            }
            String presentLabel = FrameRateTracker.formatPresentLabel(presentFps);
            if (presentLabel != null) {
                performance += " · " + presentLabel;
            }
            String romId = FrameRateTracker.romIdentityLabel(sha1);
            if (romId != null) {
                performance += " · " + romId;
            }
            json.put("observedFps", observedFps == null ? JSONObject.NULL : observedFps);
            json.put("presentFps", presentFps == null ? JSONObject.NULL : presentFps);
            json.put("observedFpsState",
                    paused ? (observedFps == null ? "paused" : "last_running")
                            : (observedFps == null ? "measuring" : "live"));
            int agents = mStatusManifest != null ? mStatusManifest.cachedAgentCount() : 0;
            String endpoint = mStatusManifest != null ? mStatusManifest.adbEndpoint() : socket;
            String agentBit = agents > 0 ? (agents + " agents") : "ready";
            mHudSocket.setText(socket + (pad ? " · pad" : " · no pad"));
            if (mHudLink != null) {
                mHudLink.setText(endpoint + "  ·  " + modeLabel(json)
                        + "  ·  " + performance + "  ·  " + agentBit);
                mHudLink.setVisibility(mKeepHud ? View.VISIBLE : View.GONE);
            }
            updateRecBadgeUi(json.optBoolean("recording", false));
            if (mStatusWriter != null) {
                mStatusWriter.enqueue(json);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Single-daemon-thread coalescing status manifest writer. Callers snapshot
     * JSON immediately on enqueue; while a disk write is in flight, newer
     * snapshots overwrite the single pending slot so writes stay bounded,
     * thread-safe, and chronologically ordered without stalling the UI thread.
     */
    static final class StatusWriter {
        interface Sink {
            void write(String snapshotJson) throws Exception;
        }

        private final Sink mSink;
        private final Object mLock = new Object();
        private String mPendingJson;
        private boolean mStopped;
        private final Thread mThread;

        StatusWriter(Sink sink) {
            mSink = sink;
            mThread = new Thread(this::runLoop, "oos-status-writer");
            mThread.setDaemon(true);
            mThread.start();
        }

        void enqueue(JSONObject json) {
            if (json == null) {
                return;
            }
            enqueueSnapshot(json.toString());
        }

        void enqueueSnapshot(String snapshotJson) {
            if (snapshotJson == null) {
                return;
            }
            synchronized (mLock) {
                if (mStopped) {
                    return;
                }
                mPendingJson = snapshotJson;
                mLock.notifyAll();
            }
        }

        void shutdown() {
            synchronized (mLock) {
                mStopped = true;
                mPendingJson = null;
                mLock.notifyAll();
            }
        }

        boolean isAlive() {
            return mThread.isAlive();
        }

        private void runLoop() {
            while (true) {
                final String next;
                synchronized (mLock) {
                    while (mPendingJson == null && !mStopped) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException ignored) {
                            if (mStopped) {
                                return;
                            }
                        }
                    }
                    if (mStopped) {
                        return;
                    }
                    next = mPendingJson;
                    mPendingJson = null;
                }
                try {
                    mSink.write(next);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isSdlSurfaceReady() {
        if (SDLActivity.mSurface == null) {
            return false;
        }
        if (SDLActivity.mSurface.mIsSurfaceReady) {
            return true;
        }
        try {
            android.view.SurfaceHolder holder = SDLActivity.mSurface.getHolder();
            return holder != null
                    && holder.getSurface() != null
                    && holder.getSurface().isValid();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void startNativeIfNeeded() {
        SDLActivity.mHasFocus = true;
        SDLActivity.mIsResumedCalled = true;
        if (SDLActivity.mSurface == null || !isSdlSurfaceReady()) {
            Log.v(TAG, "deferring SDL thread start until surface ready");
            return;
        }
        resumeNativeThread();
        if (SDLActivity.mSDLThread != null) {
            return;
        }
        Log.v(TAG, "starting SDL thread (surface ready)");
        SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
        SDLActivity.handleNativeState();
        if (SDLActivity.mSDLThread == null) {
            Log.w(TAG, "handleNativeState did not start SDLThread; forcing");
            Thread thread = new Thread(() -> {
                Log.v(TAG, "starting native SDL_main");
                SDLActivity.nativeRunMain(getMainSharedObject(), getMainFunction(), getArguments());
                Log.v(TAG, "native SDL_main finished");
            }, "SDLThread");
            SDLActivity.mSDLThread = thread;
            thread.start();
        }
    }

    private void requestPlaybackFocus() {
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void rememberRom(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST_ROM, path).apply();
    }

    private void setKeepHud(boolean keep) {
        mKeepHud = keep;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HUD, keep).apply();
        if (keep && !mUiVisible) {
            showHudBar();
        } else {
            hideHudBar();
        }
        refreshHud();
    }

    private void setFillScreen(boolean fill) {
        mFillScreen = fill;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_FIT, fill).apply();
        applyPresentMode();
    }

    private void setSmoothPlay(boolean enabled) {
        mSmoothPlay = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_PLAY, enabled).apply();
        applyPerformanceMode();
        refreshHud();
    }

    private void setPreferGpu(boolean enabled) {
        mPreferGpu = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GPU, enabled).apply();
        applyPreferAccelerated();
        refreshHud();
    }

    private void setCaptureHotkeys(boolean enabled) {
        mCaptureHotkeys = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HOTKEYS, enabled).apply();
        if (!enabled) {
            clearCaptureHotkeys();
        }
    }

    private void setRecordInputHud(boolean enabled) {
        mRecordInputHud = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_REC_HUD, enabled).apply();
    }

    private void setReplayBufferEnabled(boolean enabled) {
        mReplayBufferEnabled = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_REPLAY_BUF, enabled).apply();
        if (mNativeReady && !mDestroyed && !mBgExecutor.isShutdown()) {
            mBgExecutor.execute(this::syncReplayBuffer);
        }
    }

    private void syncReplayBuffer() {
        if (!mNativeReady || mDestroyed) {
            return;
        }
        synchronized (mCaptureLock) {
            if (mReplayBufferEnabled) {
                if (!MesenNative.isVideoRecording() && !MesenNative.isReplayBufferActive()) {
                    MesenNative.startReplayBuffer(20, mRecordInputHud);
                }
            } else if (MesenNative.isReplayBufferActive()) {
                MesenNative.stopReplayBuffer();
            }
        }
    }

    private String romDisplayName() {
        String path = currentRomPath();
        return (path != null && !path.isEmpty()) ? new File(path).getName() : "oracle";
    }

    private void updateRecBadgeUi(boolean recording) {
        if (!recording) {
            mVideoStartElapsedMs = 0L;
            if (mRecBadge != null) {
                mRecBadge.setVisibility(View.GONE);
            }
            return;
        }
        if (mVideoStartElapsedMs <= 0L) {
            mVideoStartElapsedMs = SystemClock.elapsedRealtime();
        }
        if (mRecBadge != null) {
            if (mUiVisible) {
                mRecBadge.setVisibility(View.GONE);
            } else {
                long secs = Math.max(0L, (SystemClock.elapsedRealtime() - mVideoStartElapsedMs) / 1000L);
                mRecBadge.setText("\u25CF REC " + MediaCapture.formatDuration(secs));
                mRecBadge.setVisibility(View.VISIBLE);
                mRecBadge.bringToFront();
            }
        }
    }

    private String stopRecordingLocked(boolean publishGallery) {
        String activePath = MesenNative.videoRecordingPath();
        MesenNative.stopVideoRecording();
        mVideoStartElapsedMs = 0L;
        mHandler.post(() -> {
            if (!mDestroyed) {
                updateRecBadgeUi(false);
            }
        });
        if (activePath == null || activePath.isEmpty()) {
            return "Video recording stopped";
        }
        File out = new File(activePath);
        if (publishGallery && out.isFile() && out.length() > 0) {
            MediaGallery.publish(this, out, true);
        }
        if (publishGallery && mReplayBufferEnabled && !mDestroyed) {
            MesenNative.startReplayBuffer(20, mRecordInputHud);
        }
        return "Saved video: " + out.getName();
    }

    private void stopRecordingIfActive(boolean publishGallery) {
        if (!mNativeReady) {
            return;
        }
        synchronized (mCaptureLock) {
            if (MesenNative.isVideoRecording()) {
                stopRecordingLocked(publishGallery);
            }
            if (MesenNative.isReplayBufferActive()) {
                MesenNative.stopReplayBuffer();
            }
        }
    }

    private void clearCaptureHotkeys() {
        mL2KeyHeld = false;
        mL2TriggerHeld = false;
        mL2Held = false;
        mL2HoldFired = false;
        mHandler.removeCallbacks(mL2HoldRunnable);
    }

    private void updateL2CaptureHeld() {
        boolean next = !mUiVisible && mCaptureHotkeys && (mL2KeyHeld || mL2TriggerHeld);
        if (next == mL2Held) {
            return;
        }
        mL2Held = next;
        if (next) {
            mL2HoldFired = false;
            mHandler.removeCallbacks(mL2HoldRunnable);
            mHandler.postDelayed(mL2HoldRunnable, 550L);
        } else {
            mHandler.removeCallbacks(mL2HoldRunnable);
            boolean fireScreenshot = !mL2HoldFired && !mUiVisible && mCaptureHotkeys && !mDestroyed;
            mL2HoldFired = false;
            if (fireScreenshot) {
                triggerScreenshotFromHotkey();
            }
        }
    }

    private void triggerScreenshotFromHotkey() {
        if (!mNativeReady || mDestroyed || mBgExecutor.isShutdown()) {
            return;
        }
        mBgExecutor.execute(() -> {
            if (mDestroyed) {
                return;
            }
            String msg = takeScreenshot();
            mHandler.post(() -> {
                if (!mDestroyed) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void triggerToggleVideoFromHotkey() {
        if (!mNativeReady || mDestroyed || mBgExecutor.isShutdown()) {
            return;
        }
        mBgExecutor.execute(() -> {
            if (mDestroyed) {
                return;
            }
            String msg = toggleVideoRecording();
            mHandler.post(() -> {
                if (!mDestroyed) {
                    refreshHud();
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void dispatchPendingCaptureActions() {
        if (!mNativeReady || mDestroyed || mBgExecutor.isShutdown()) {
            return;
        }
        final boolean startVideo = mPendingStartVideo;
        final boolean screenshot = mPendingScreenshot;
        final boolean dumpReplay = mPendingDumpReplay;
        final Boolean replayBuf = mPendingReplayBuffer;
        final Integer overlayMask = mPendingOverlayMask;
        final String bugCat = mPendingBugSnapshotCategory;
        mPendingStartVideo = false;
        mPendingScreenshot = false;
        mPendingDumpReplay = false;
        mPendingReplayBuffer = null;
        mPendingOverlayMask = null;
        mPendingBugSnapshotCategory = null;
        if (!startVideo && !screenshot && !dumpReplay && replayBuf == null && overlayMask == null && bugCat == null) {
            return;
        }
        mBgExecutor.execute(() -> {
            if (mDestroyed) {
                return;
            }
            if (overlayMask != null) {
                mOverlayMask = overlayMask & 0x07;
                MesenNative.setDebugOverlayMask(mOverlayMask);
            }
            if (replayBuf != null) {
                mReplayBufferEnabled = replayBuf;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_REPLAY_BUF, replayBuf).apply();
                syncReplayBuffer();
            }
            if (startVideo && !MesenNative.isVideoRecording()) {
                toast(toggleVideoRecording());
            }
            if (screenshot) {
                toast(takeScreenshot());
            }
            if (dumpReplay) {
                toast(dumpReplayClip());
            }
            if (bugCat != null) {
                toast(captureBugSnapshot(bugCat));
            }
        });
    }

    private void setFastForwardKeyHeld(boolean enabled) {
        mFastForwardKeyHeld = enabled;
        updateFastForward();
    }

    private void setFastForwardTriggerHeld(boolean enabled) {
        mFastForwardTriggerHeld = enabled;
        updateFastForward();
    }

    private void clearFastForward() {
        mFastForwardKeyHeld = false;
        mFastForwardTriggerHeld = false;
        updateFastForward();
    }

    private void updateFastForward() {
        setFastForwardHeld(!mUiVisible
                && (mFastForwardKeyHeld || mFastForwardTriggerHeld || mStickyFast));
    }

    private void setFastForwardHeld(boolean enabled) {
        if (mFastForwardHeld == enabled) {
            return;
        }
        mFastForwardHeld = enabled;
        if (mNativeReady) {
            MesenNative.setFastForward(enabled);
        }
        if (enabled && !mUiVisible) {
            showHudBar();
        } else if (!mUiVisible) {
            mHandler.postDelayed(this::hideHudBar, 800);
        }
        refreshHud();
    }

    private void applyIntentPrefs(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra("note")) {
            showPlaytestNote(intent.getStringExtra("note"), intent.getIntExtra("note_secs", 20));
        }
        if (intent.hasExtra("keep_hud")) {
            setKeepHud(intent.getBooleanExtra("keep_hud", false));
        }
        if (intent.hasExtra("fill_screen")) {
            setFillScreen(intent.getBooleanExtra("fill_screen", false));
        }
        if (intent.hasExtra("smooth_play")) {
            setSmoothPlay(intent.getBooleanExtra("smooth_play", true));
        }
        if (intent.hasExtra("gpu_blit")) {
            setPreferGpu(intent.getBooleanExtra("gpu_blit", true));
        }
        if (intent.hasExtra("fast_forward")) {
            setStickyFastForward(intent.getBooleanExtra("fast_forward", false));
        }
        if (intent.hasExtra("turbo_speed")) {
            setTurboSpeedPercent(intent.getIntExtra("turbo_speed", FrameRateTracker.DEFAULT_TURBO_SPEED));
        }
        if (intent.hasExtra("record_video")) {
            boolean wantRec = intent.getBooleanExtra("record_video", false);
            if (wantRec) {
                mPendingStartVideo = true;
            } else {
                mPendingStartVideo = false;
                if (mNativeReady && !mDestroyed && !mBgExecutor.isShutdown()) {
                    mBgExecutor.execute(() -> {
                        if (mNativeReady && !mDestroyed) {
                            synchronized (mCaptureLock) {
                                if (MesenNative.isVideoRecording()) {
                                    toast(stopRecordingLocked(true));
                                }
                            }
                        }
                    });
                }
            }
        }
        if (intent.getBooleanExtra("screenshot", false)) {
            mPendingScreenshot = true;
        }
        if (intent.hasExtra("replay_buffer")) {
            mPendingReplayBuffer = intent.getBooleanExtra("replay_buffer", false);
        }
        if (intent.getBooleanExtra("dump_replay", false)) {
            mPendingDumpReplay = true;
        }
        if (intent.hasExtra("overlay_mask")) {
            mPendingOverlayMask = intent.getIntExtra("overlay_mask", 0);
        }
        if (intent.hasExtra("bug_snapshot")) {
            String cat = intent.getStringExtra("bug_snapshot");
            mPendingBugSnapshotCategory = (cat == null || cat.trim().isEmpty())
                    ? MediaCapture.BUG_CATEGORIES[0]
                    : cat;
        }
        if (mNativeReady && (mPendingStartVideo || mPendingScreenshot || mPendingDumpReplay
                || mPendingReplayBuffer != null || mPendingOverlayMask != null
                || mPendingBugSnapshotCategory != null)) {
            dispatchPendingCaptureActions();
        }
    }

    private void showPlaytestNote(String text, int seconds) {
        if (mLayout == null) {
            return;
        }
        mHandler.removeCallbacks(mHideNote);
        if (text == null || text.trim().isEmpty()) {
            mHideNote.run();
            return;
        }
        if (mNoteView == null) {
            float dp = getResources().getDisplayMetrics().density;
            mNoteView = new TextView(this);
            mNoteView.setTextColor(TEXT_PRIMARY);
            mNoteView.setTextSize(17);
            mNoteView.setBackgroundColor(Color.parseColor("#E6141A22"));
            int pad = Math.round(10 * dp);
            mNoteView.setPadding(pad, pad, pad, pad);
            mNoteView.setGravity(Gravity.CENTER);
            mNoteView.setFocusable(false);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            int margin = Math.round(8 * dp);
            lp.setMargins(margin, margin, margin, margin);
            mLayout.addView(mNoteView, lp);
        }
        mNoteView.setText(text);
        mNoteView.setVisibility(View.VISIBLE);
        mNoteView.bringToFront();
        Log.i(TAG, "playtest note: " + text);
        if (seconds > 0) {
            mHandler.postDelayed(mHideNote, seconds * 1000L);
        }
    }

    private void applyPresentMode() {
        if (mNativeReady) {
            MesenNative.setPresentMode(mFillScreen ? 1 : 0);
        }
    }

    private void applyTurboSpeed() {
        if (mNativeReady) {
            MesenNative.setTurboSpeed(mTurboSpeed);
        }
    }

    private void applyPerformanceMode() {
        if (mNativeReady) {
            MesenNative.setPerformanceMode(mSmoothPlay);
        }
    }

    private void applyPreferAccelerated() {
        if (mNativeReady) {
            MesenNative.setPreferAccelerated(mPreferGpu);
        }
    }

    /** Copies Name.srm beside the ROM into Saves/ once; never overwrites. */
    private BatterySaveSeed.Status seedBatterySave(String romPath) {
        BatterySaveSeed.Status status = BatterySaveSeed.seed(new File(romPath), savesDir());
        Log.v(TAG, "battery save seed " + status + " for " + romPath);
        return status;
    }

    private static String romSource(File rom) {
        String path = rom.getAbsolutePath();
        if (path.contains("/snes/")) {
            return "snes";
        }
        if (path.contains("OracleOfSecrets")) {
            return "drop";
        }
        if (path.contains("ca.mesen.oos")) {
            return "app";
        }
        return "";
    }

    private static String modeLabel(JSONObject json) {
        int mode = json.optInt("mode", -1);
        int sub = json.optInt("sub", -1);
        int indoor = json.optInt("indoor", 0);
        int area = json.optInt("area", 0);
        int room = json.optInt("room", 0);
        String name;
        switch (mode) {
            case 0x00: name = "TITLE"; break;
            case 0x01: name = "FILE"; break;
            case 0x02: name = "DUNGEON"; break;
            case 0x09: name = "FIELD"; break;
            case 0x0E: name = "TEXT"; break;
            case 0x14: name = "INTRO"; break;
            default: name = mode < 0 ? "—" : String.format("$%02X", mode);
        }
        if (mode == 0x02) {
            return String.format("%s $%02X", name, room & 0xFF);
        }
        if (mode == 0x09) {
            return String.format("%s $%02X", name, area & 0xFF);
        }
        if (indoor != 0 && indoor != -1 && mode > 0) {
            return String.format("%s in $%02X", name, room & 0xFF);
        }
        if (sub >= 0) {
            return String.format("%s.%02X", name, sub & 0xFF);
        }
        return name;
    }

    private static String performanceLabel(boolean paused, Double observedFps) {
        if (paused) {
            return observedFps == null
                    ? "PAUSED"
                    : String.format(Locale.US, "PAUSED · last %.1f fps", observedFps);
        }
        return observedFps == null
                ? "measuring"
                : String.format(Locale.US, "%.1f fps", observedFps);
    }

    private void setSurfaceFocusable(boolean focusable) {
        if (mSurface == null) {
            return;
        }
        mSurface.setFocusable(focusable);
        mSurface.setFocusableInTouchMode(focusable);
        if (focusable) {
            mSurface.requestFocus();
        } else if (mSurface.isFocused()) {
            mSurface.clearFocus();
        }
    }

    private static void forwardPadToSdl(KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (!SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            return;
        }
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            SDLControllerManager.onNativePadDown(deviceId, keyCode);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            SDLControllerManager.onNativePadUp(deviceId, keyCode);
        }
    }
}
