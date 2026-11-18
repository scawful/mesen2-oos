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
import java.util.Locale;
import java.util.UUID;

public class MesenActivity extends SDLActivity implements OracleUi.Host {
    private static final String TAG = "MesenActivity";
    private static final String PREFS = "oracle";
    private static final String PREF_LAST_ROM = "last_rom";
    private static final String PREF_HUD = "keep_hud";
    private static final String PREF_FIT = "fill_screen";
    private static final String PREF_PLAY = "smooth_play";
    private static final String PREF_GPU = "gpu_blit";
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
    private TextView mNoteView;
    private final Runnable mHideNote = () -> {
        if (mNoteView != null) {
            mNoteView.setVisibility(View.GONE);
        }
    };

    private StatusManifest mStatusManifest;
    private CheckpointData mCheckpointData;
    private PresetData mPresetData;
    private OracleUi mUi;
    private ComposeView mUiView;
    private ComposeHost mComposeHost;

    private boolean mDestroyed;
    private boolean mNativeReady;
    private boolean mUiVisible;
    private volatile boolean mProgressionBusy;
    private boolean mStickyFast;
    private int mUiHatY;
    private int mUiHatX;
    private boolean mKeepHud;
    private boolean mFillScreen;
    private boolean mSmoothPlay;
    private boolean mPreferGpu;
    private boolean mFastForwardHeld;
    private boolean mFastForwardKeyHeld;
    private boolean mFastForwardTriggerHeld;

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
        String warpsJson = readAssetText(CheckpointData.WARPS_ASSET);
        mCheckpointData = CheckpointData.parse(
                readAssetText(CheckpointData.CHECKPOINTS_ASSET),
                warpsJson);
        mPresetData = PresetData.parse(
                readAssetText(PresetData.PRESETS_ASSET),
                mCheckpointData,
                warpsJson);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mKeepHud = prefs.getBoolean(PREF_HUD, false);
        mFillScreen = prefs.getBoolean(PREF_FIT, false);
        mSmoothPlay = prefs.getBoolean(PREF_PLAY, true);
        mPreferGpu = prefs.getBoolean(PREF_GPU, true);
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
        mHandler.removeCallbacksAndMessages(null);
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
    }

    @Override
    protected void onPause() {
        clearFastForward();
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
            String rom = intent.getStringExtra("rom");
            if (rom != null && !rom.isEmpty() && mNativeReady) {
                seedBatterySave(rom);
                if (MesenNative.loadRom(rom)) {
                    rememberRom(rom);
                    applyPerformanceMode();
                    refreshHud();
                }
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
    public String currentRomPath() {
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, null);
        return last != null && new File(last).isFile() ? last : null;
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
        mUiView.setVisibility(View.VISIBLE);
        setSurfaceFocusable(false);
        if (mHud != null) {
            mHud.animate().cancel();
            mHud.setVisibility(View.GONE);
        }
        if (mNativeReady) {
            MesenNative.pauseEmu();
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
            MesenNative.resumeEmu();
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
        if (!mNativeReady) {
            return false;
        }
        BatterySaveSeed.Status seeded = seedBatterySave(path);
        if (seeded == BatterySaveSeed.Status.SEEDED) {
            Toast.makeText(this, "Imported " + BatterySaveSeed.sidecar(new File(path)).getName(),
                    Toast.LENGTH_SHORT).show();
        }
        if (!MesenNative.loadRom(path)) {
            return false;
        }
        rememberRom(path);
        applyPerformanceMode();
        refreshHud();
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
    }

    @Override
    public boolean setting(String key) {
        switch (key) {
            case "hud": return mKeepHud;
            case "fit": return mFillScreen;
            case "play": return mSmoothPlay;
            case "gpu": return mPreferGpu;
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
            default: break;
        }
    }

    @Override
    public void startCheckpoint(String id) {
        if (mCheckpointData == null) {
            return;
        }
        runProgression(mCheckpointData.find(id));
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

    private void runProgression(final Progression.Checkpoint target) {
        if (mProgressionBusy || !mNativeReady || target == null) {
            return;
        }
        mProgressionBusy = true;
        Toast.makeText(this, getString(R.string.progression_running, target.label), Toast.LENGTH_SHORT).show();
        Thread worker = new Thread(() -> {
            String result = Progression.apply(target, Progression.LIVE);
            mHandler.post(() -> {
                mProgressionBusy = false;
                Log.i(TAG, "progression: " + result);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
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
                performance += " · " + FrameRateTracker.formatFastForwardLabel(observedFps);
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
            int agents = mStatusManifest != null ? mStatusManifest.agentCount() : 0;
            String endpoint = mStatusManifest != null ? mStatusManifest.adbEndpoint() : socket;
            String agentBit = agents > 0 ? (agents + " agents") : "ready";
            mHudSocket.setText(socket + (pad ? " · pad" : " · no pad"));
            if (mHudLink != null) {
                mHudLink.setText(endpoint + "  ·  " + modeLabel(json)
                        + "  ·  " + performance + "  ·  " + agentBit);
                mHudLink.setVisibility(mKeepHud ? View.VISIBLE : View.GONE);
            }
            if (mStatusManifest != null) {
                mStatusManifest.write(json);
            }
        } catch (Exception ignored) {
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
            updateFastForward();
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
