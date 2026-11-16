package ca.mesen.oos;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.compose.ui.platform.ComposeView;

import org.json.JSONObject;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    private static final String HOME_SHORTCUT_ID = "oracle-open";
    private static final String HOME_SHORTCUT_PINNED = "ca.mesen.oos.HOME_SHORTCUT_PINNED";
    private static final String HOME_REQUEST_TOKEN = "home_request_token";
    private static final int ACCENT = Color.parseColor("#70B7FF");
    private static final int TEXT_PRIMARY = Color.parseColor("#EDF0F5");
    private static MesenActivity sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mArtifactExecutor = Executors.newSingleThreadExecutor();
    private final List<View> mBusyDisabledViews = new ArrayList<>();
    // Keep runtime identity/performance fresh even when the compact HUD is
    // hidden. HUD visibility is a presentation preference, not a telemetry
    // switch for Workshop captures.
    private final Runnable mStatusPoller = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed) {
                return;
            }
            if (!mPauseVisible) {
                refreshHud();
            }
            mHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable mPadPoller = new Runnable() {
        @Override
        public void run() {
            if (!mPauseVisible) {
                return;
            }
            setSurfaceFocusable(false);
            View focused = getCurrentFocus();
            if (isArtifactEditor(focused)) {
                refreshHud();
                mHandler.postDelayed(this, 180);
                return;
            }
            List<View> focusables = pauseFocusables();
            if (!mArtifactBusy && !focusables.isEmpty() && focusables.indexOf(focused) < 0) {
                focusPauseView(focusables.get(0));
            }
            refreshHud();
            mHandler.postDelayed(this, 180);
        }
    };
    private View mHud;
    private View mPauseScrim;
    private TextView mHudRom;
    private TextView mHudSocket;
    private TextView mHudLink;
    private TextView mPauseStatus;
    private TextView mPausePad;
    private TextView mWorkshopLink;
    private TextView mWorkshopMeta;
    private TextView mHomeStatus;
    private Button mChipHud;
    private Button mChipFit;
    private Button mChipPlay;
    private Button mChipGpu;
    private LinearLayout mRomList;
    private ScrollView mRomScroller;
    private TextView mArtifactBuild;
    private TextView mArtifactStatus;
    private EditText mArtifactTitle;
    private EditText mArtifactNote;
    private LinearLayout mBugCategories;
    private LinearLayout mCheckpointList;
    private ScrollView mCheckpointScroller;
    private View mRestoreReview;
    private TextView mRestoreDescription;
    private LinearLayout mStateList;
    private ScrollView mStateScroller;
    private View mStateReview;
    private TextView mStateDescription;
    private Button mStateSave;
    private Button mStateLoad;
    private Button mTabPlay;
    private Button mTabStates;
    private Button mTabCapture;
    private Button mTabCheats;
    private Button mTabSetup;
    private View mPanelPlay;
    private View mPanelStates;
    private View mPanelCheats;
    private View mPanelCapture;
    private View mPanelSetup;
    private LinearLayout mCheatGroups;
    private LinearLayout mCheatList;
    private TextView mCheatStatus;
    private final java.util.Map<String, Integer> mMenuSelection = new java.util.HashMap<>();
    private String mCheatGroup = WorkshopCheats.GROUP_QUICK;
    private static final long PROGRESSION_CONFIRM_MS = 4000;
    private String mProgressionArmed;
    private long mProgressionArmedAt;
    private volatile boolean mProgressionBusy;
    private Button mVoiceButton;
    private WorkshopVoiceRecorder mVoiceRecorder;
    private WorkshopVoiceNote.Session mVoiceSession;
    private final Runnable mVoiceAutoStop = this::stopVoiceNote;
    private static final int REQ_RECORD_AUDIO = 4401;
    private WorkshopArtifacts mArtifacts;
    private WorkshopArtifacts.Entry mSelectedCheckpoint;
    private SaveStateSlots.Entry mSelectedState;
    private boolean mArtifactBusy;
    private boolean mDestroyed;
    private boolean mPauseVisible;
    private int mPauseTab;
    private boolean mNativeReady;
    // Compose Library + quick menu. The legacy overlay stays as Workshop.
    private OracleUi mUi;
    private ComposeView mUiView;
    private ComposeHost mComposeHost;
    private boolean mUiVisible;
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
    private int mPauseHatLatch;
    private int mPauseHatXLatch;
    private final FrameRateTracker mFrameRateTracker = new FrameRateTracker();
    private final FrameRateTracker mPresentRateTracker = new FrameRateTracker();
    private WorkshopLink mWorkshop;
    private static final String ACTION_DUMP_PGO = "ca.mesen.oos.DUMP_PGO";
    private static final int TAB_PLAY = 0;
    private static final int TAB_STATES = 1;
    private static final int TAB_CHEATS = 2;
    private static final int TAB_CAPTURE = 3;
    private static final int TAB_SETUP = 4;
    private boolean mHomeReceiverRegistered;
    private boolean mPgoDumpReceiverRegistered;
    private String mHomeRequestToken;
    private final BroadcastReceiver mHomeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HOME_SHORTCUT_PINNED.equals(intent.getAction()) && mHomeRequestToken != null
                    && mHomeRequestToken.equals(intent.getStringExtra(HOME_REQUEST_TOKEN))) {
                mHomeRequestToken = null;
                setHomeStatus(R.string.home_added);
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
        mWorkshop = new WorkshopLink(this);
        mArtifacts = new WorkshopArtifacts(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mKeepHud = prefs.getBoolean(PREF_HUD, false);
        mFillScreen = prefs.getBoolean(PREF_FIT, false);
        mSmoothPlay = prefs.getBoolean(PREF_PLAY, true);
        mPreferGpu = prefs.getBoolean(PREF_GPU, true);
        attachOverlay();
        applyIntentPrefs(getIntent());
        // Do not collapse the shade here — StatusBarManager.collapsePanels during
        // first resume races WindowManager and pauses the activity before the
        // SDL SurfaceView ever receives surfaceChanged.
        startNativeIfNeeded();
        mHandler.post(this::startNativeIfNeeded);
        for (int delayMs : new int[] {250, 500, 1000, 2000, 4000}) {
            mHandler.postDelayed(this::startNativeIfNeeded, delayMs);
        }
        // Collapse after the surface has had time to bind.
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
        cancelVoiceNote(false);
        // Let an in-flight artifact finish writing; never interrupt a state save.
        mArtifactExecutor.shutdown();
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
        // Delay shade collapse so the first SurfaceView bind can complete.
        mHandler.postDelayed(this::collapseNotificationShade, 400);
        if (mWorkshop != null) {
            mWorkshop.refresh(true);
        }
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
                // Non-instrumented builds omit the symbol.
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
        if (mArtifactBusy) {
            if (intent != null && intent.hasExtra("rom")) {
                Toast.makeText(this, R.string.artifact_wait, Toast.LENGTH_LONG).show();
            }
            return;
        }
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
        } else if (mPauseVisible) {
            handlePauseBack();
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
                } else if (mPauseVisible) {
                    handlePauseBack();
                } else {
                    openQuickMenu();
                }
            }
            return true;
        }
        if (mUiVisible) {
            // Same reason as the legacy overlay: keep SDL's pad state current
            // so the game does not see a stuck button after the menu closes.
            forwardPadToSdl(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mUi.onKey(code, event.getRepeatCount());
            }
            return true;
        }
        if (!mPauseVisible && code == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                setFastForwardKeyHeld(true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                setFastForwardKeyHeld(false);
            }
            return true;
        }
        if (mPauseVisible) {
            if (mArtifactBusy) {
                // The confirm DOWN may have started this action. Its matching
                // UP must still clear SDL's pad state before Continue is used.
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    forwardPadToSdl(event);
                }
                return true;
            }
            // Overlay steals focus from SDLSurface, so pad keys never reach
            // handleKeyEvent. Forward them ourselves for the live readout.
            forwardPadToSdl(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
                    handlePauseBack();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BUTTON_A
                        || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    View focused = getCurrentFocus();
                    if (focused != null && event.getRepeatCount() == 0) {
                        focused.performClick();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
                    if (event.getRepeatCount() == 0) {
                        boolean left = code == KeyEvent.KEYCODE_BUTTON_L1;
                        if (mPauseTab == TAB_CHEATS && !railViews().contains(getCurrentFocus())) {
                            cycleCheatGroup(left);
                        } else {
                            cyclePauseTab(left);
                        }
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    movePauseFocus(code == KeyEvent.KEYCODE_DPAD_UP);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    movePauseFocusHorizontal(code == KeyEvent.KEYCODE_DPAD_LEFT);
                    return true;
                }
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
        if (!mPauseVisible) {
            if ((source & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) != 0) {
                float trigger = Math.max(
                        event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                        event.getAxisValue(MotionEvent.AXIS_GAS));
                setFastForwardTriggerHeld(trigger > 0.55f);
            }
            return super.dispatchGenericMotionEvent(event);
        }
        if ((source & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD
                | InputDevice.SOURCE_GAMEPAD)) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        SDLControllerManager.handleJoystickMotionEvent(event);
        if (mArtifactBusy) {
            // Keep axes/hat releases current while suppressing menu navigation.
            // Joystick motion does not send the Start/Select pause combo.
            return true;
        }
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float axisY = event.getAxisValue(MotionEvent.AXIS_Y);
        int dir = 0;
        if (hatY < -0.5f || axisY < -0.6f) {
            dir = -1;
        } else if (hatY > 0.5f || axisY > 0.6f) {
            dir = 1;
        }
        if (dir != 0 && dir != mPauseHatLatch) {
            movePauseFocus(dir < 0);
        }
        mPauseHatLatch = dir;

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float axisX = event.getAxisValue(MotionEvent.AXIS_X);
        int dirX = 0;
        if (hatX < -0.5f || axisX < -0.6f) {
            dirX = -1;
        } else if (hatX > 0.5f || axisX > 0.6f) {
            dirX = 1;
        }
        if (dirX != 0 && dirX != mPauseHatXLatch) {
            movePauseFocusHorizontal(dirX < 0);
        }
        mPauseHatXLatch = dirX;
        return true;
    }

    public static void showPauseFromNative() {
        MesenActivity activity = sInstance;
        if (activity == null) {
            return;
        }
        activity.mHandler.post(activity::openQuickMenu);
    }

    private void attachOverlay() {
        if (mLayout == null) {
            return;
        }
        View overlay = getLayoutInflater().inflate(R.layout.oracle_overlay, mLayout, false);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        mLayout.addView(overlay, params);

        mPauseScrim = overlay.findViewById(R.id.pause_scrim);
        mHud = overlay.findViewById(R.id.oracle_hud);
        mHudRom = overlay.findViewById(R.id.hud_rom);
        mHudSocket = overlay.findViewById(R.id.hud_socket);
        mHudLink = overlay.findViewById(R.id.hud_link);
        mPauseStatus = overlay.findViewById(R.id.pause_status);
        mPausePad = overlay.findViewById(R.id.pause_pad);
        mWorkshopLink = overlay.findViewById(R.id.workshop_link);
        mWorkshopMeta = overlay.findViewById(R.id.workshop_meta);
        mHomeStatus = overlay.findViewById(R.id.home_status);
        mChipHud = overlay.findViewById(R.id.btn_hud);
        mChipFit = overlay.findViewById(R.id.btn_fit);
        mChipPlay = overlay.findViewById(R.id.btn_play);
        mChipGpu = overlay.findViewById(R.id.btn_gpu);
        mRomList = overlay.findViewById(R.id.rom_list);
        mRomScroller = overlay.findViewById(R.id.rom_scroller);
        mArtifactBuild = overlay.findViewById(R.id.artifact_build);
        mArtifactStatus = overlay.findViewById(R.id.artifact_status);
        mArtifactTitle = overlay.findViewById(R.id.artifact_title);
        mArtifactNote = overlay.findViewById(R.id.artifact_note);
        mBugCategories = overlay.findViewById(R.id.bug_categories);
        mCheckpointList = overlay.findViewById(R.id.checkpoint_list);
        mCheckpointScroller = overlay.findViewById(R.id.checkpoint_scroller);
        mRestoreReview = overlay.findViewById(R.id.restore_review);
        mRestoreDescription = overlay.findViewById(R.id.restore_description);
        mStateList = overlay.findViewById(R.id.state_list);
        mStateScroller = overlay.findViewById(R.id.state_scroller);
        mStateReview = overlay.findViewById(R.id.state_review);
        mStateDescription = overlay.findViewById(R.id.state_description);
        mStateSave = overlay.findViewById(R.id.btn_state_save);
        mStateLoad = overlay.findViewById(R.id.btn_state_load);
        mTabPlay = overlay.findViewById(R.id.tab_play);
        mTabStates = overlay.findViewById(R.id.tab_states);
        mTabCheats = overlay.findViewById(R.id.tab_cheats);
        mTabCapture = overlay.findViewById(R.id.tab_capture);
        mTabSetup = overlay.findViewById(R.id.tab_setup);
        mPanelPlay = overlay.findViewById(R.id.panel_play);
        mPanelStates = overlay.findViewById(R.id.panel_states);
        mPanelCheats = overlay.findViewById(R.id.panel_cheats);
        mPanelCapture = overlay.findViewById(R.id.panel_capture);
        mPanelSetup = overlay.findViewById(R.id.panel_setup);
        mCheatGroups = overlay.findViewById(R.id.cheat_groups);
        mCheatList = overlay.findViewById(R.id.cheat_list);
        mCheatStatus = overlay.findViewById(R.id.cheat_status);

        overlay.findViewById(R.id.oracle_hud).setOnClickListener(v -> togglePauseMenu());
        overlay.findViewById(R.id.btn_continue).setOnClickListener(v -> hidePauseMenu());
        overlay.findViewById(R.id.btn_reset).setOnClickListener(v -> {
            if (mArtifactBusy) {
                return;
            }
            if (mNativeReady) {
                MesenNative.resetEmu();
            }
            hidePauseMenu();
        });
        overlay.findViewById(R.id.btn_roms).setOnClickListener(v -> toggleRomList());
        overlay.findViewById(R.id.btn_home).setOnClickListener(v -> requestHomeShortcut());
        overlay.findViewById(R.id.btn_checkpoint_save).setOnClickListener(v -> saveCheckpoint());
        overlay.findViewById(R.id.btn_bug_capture).setOnClickListener(v -> toggleBugCategories());
        mVoiceButton = overlay.findViewById(R.id.btn_voice_note);
        if (mVoiceButton != null) {
            mVoiceButton.setOnClickListener(v -> toggleVoiceNote());
        }
        overlay.findViewById(R.id.btn_checkpoints).setOnClickListener(v -> toggleCheckpoints());
        overlay.findViewById(R.id.btn_restore_checkpoint).setOnClickListener(v -> restoreCheckpoint());
        mTabPlay.setOnClickListener(v -> selectPauseTab(TAB_PLAY, true));
        mTabStates.setOnClickListener(v -> selectPauseTab(TAB_STATES, true));
        mTabCheats.setOnClickListener(v -> selectPauseTab(TAB_CHEATS, true));
        mTabCapture.setOnClickListener(v -> selectPauseTab(TAB_CAPTURE, true));
        mTabSetup.setOnClickListener(v -> selectPauseTab(TAB_SETUP, true));
        populateCheatGroups();
        mStateSave.setOnClickListener(v -> saveSelectedState());
        mStateLoad.setOnClickListener(v -> loadSelectedState());
        overlay.findViewById(R.id.btn_cancel_state).setOnClickListener(v -> {
            closeArtifactPanels();
            showSaveStates();
        });
        overlay.findViewById(R.id.btn_cancel_restore).setOnClickListener(v -> handlePauseBack());
        populateBugCategories();
        if (mChipHud != null) {
            mChipHud.setOnClickListener(v -> setKeepHud(!mKeepHud));
        }
        if (mChipFit != null) {
            mChipFit.setOnClickListener(v -> setFillScreen(!mFillScreen));
        }
        if (mChipPlay != null) {
            mChipPlay.setOnClickListener(v -> setSmoothPlay(!mSmoothPlay));
        }
        if (mChipGpu != null) {
            mChipGpu.setOnClickListener(v -> setPreferGpu(!mPreferGpu));
        }
        applyChipState();
        applyPresentMode();
        attachOracleUi();
    }

    private void attachOracleUi() {
        try {
            mComposeHost = new ComposeHost();
            mUiView = new ComposeView(this);
            mComposeHost.attach(mUiView);
            mComposeHost.tag(mLayout);
            mComposeHost.tag(mLayout.getRootView());
            mComposeHost.tag(getWindow().getDecorView());
            mUiView.setVisibility(View.GONE);
            mUiView.setFocusable(false);
            mLayout.addView(mUiView, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            mUi = new OracleUi(this);
            mUi.install(mUiView);
        } catch (RuntimeException e) {
            // Keep the legacy overlay usable if Compose cannot start.
            Log.e(TAG, "Oracle UI unavailable", e);
            mUi = null;
            mUiView = null;
        }
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

    /** Back/Mode/native pause: the Compose quick menu, else the legacy panel. */
    private void openQuickMenu() {
        if (mPauseVisible || mUiVisible) {
            return;
        }
        if (mUi == null || !mNativeReady || mArtifactBusy || mProgressionBusy) {
            showPauseMenu();
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
    public List<File> romFiles() {
        return discoverRoms();
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
    public void openWorkshop() {
        mUiVisible = false;
        if (mUiView != null) {
            mUiView.setVisibility(View.GONE);
        }
        // The game stays paused: showPauseMenu pauses again and takes focus.
        showPauseMenu();
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
    public boolean isStickyFastForward() {
        return mStickyFast;
    }

    @Override
    public void setStickyFastForward(boolean on) {
        // Applied by updateFastForward() when the menu closes.
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
        if (mProgressionBusy || !mNativeReady) {
            return;
        }
        Progression.Checkpoint cp = null;
        for (Progression.Checkpoint c : Progression.checkpoints()) {
            if (c.id.equals(id)) {
                cp = c;
                break;
            }
        }
        if (cp == null) {
            return;
        }
        final Progression.Checkpoint target = cp;
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
        if (mUi != null && !romFromIntent && !mPauseVisible && !mUiVisible) {
            mUi.openLibrary();
        }
    }

    private void keepVisibleScreenOn() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        // SDL may also update the window flag asynchronously. A visible-view
        // request remains part of Android's effective window attributes and
        // stops applying when this app's window is no longer visible.
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

    /** Best-effort collapse of the RG353P notification shade during adb sessions. */
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

    private void togglePauseMenu() {
        if (mPauseVisible) {
            handlePauseBack();
        } else {
            showPauseMenu();
        }
    }

    private void handlePauseBack() {
        if (mArtifactBusy) {
            return;
        }
        if (mStateReview != null && mStateReview.getVisibility() == View.VISIBLE) {
            closeArtifactPanels();
            showSaveStates();
            return;
        }
        if (mRestoreReview != null && mRestoreReview.getVisibility() == View.VISIBLE) {
            mRestoreReview.setVisibility(View.GONE);
            mSelectedCheckpoint = null;
            mCheckpointScroller.setVisibility(View.VISIBLE);
            restoreContentFocus();
            return;
        }
        if (mCheckpointScroller != null && mCheckpointScroller.getVisibility() == View.VISIBLE) {
            closeArtifactPanels();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_checkpoints));
            return;
        }
        if (mBugCategories != null && mBugCategories.getVisibility() == View.VISIBLE) {
            closeArtifactPanels();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_bug_capture));
            return;
        }
        if (mRomScroller != null && mRomScroller.getVisibility() == View.VISIBLE
                && mPauseTab == TAB_PLAY) {
            closeRomList();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_roms));
            return;
        }
        if (!railViews().contains(getCurrentFocus())) {
            focusPauseView(selectedRailView());
            return;
        }
        hidePauseMenu();
    }

    private void showPauseMenu() {
        if (mPauseScrim == null) {
            return;
        }
        clearFastForward();
        mPauseVisible = true;
        setSurfaceFocusable(false);
        mPauseScrim.setVisibility(View.VISIBLE);
        showHudBar();
        selectPauseTab(TAB_PLAY, false);
        View continueBtn = mPauseScrim.findViewById(R.id.btn_continue);
        if (continueBtn != null) {
            continueBtn.requestFocus();
            continueBtn.post(() -> {
                if (mPauseVisible) {
                    continueBtn.requestFocus();
                }
            });
        }
        if (mNativeReady) {
            MesenNative.pauseEmu();
        }
        refreshHud();
        mHandler.removeCallbacks(mPadPoller);
        mHandler.post(mPadPoller);
    }

    private void selectPauseTab(int tab, boolean focusContent) {
        if (mPauseScrim == null || mArtifactBusy) {
            return;
        }
        if (tab < TAB_PLAY || tab > TAB_SETUP) {
            tab = TAB_PLAY;
        }
        mPauseTab = tab;
        if (mPanelPlay != null) {
            mPanelPlay.setVisibility(tab == TAB_PLAY ? View.VISIBLE : View.GONE);
        }
        if (mPanelStates != null) {
            mPanelStates.setVisibility(tab == TAB_STATES ? View.VISIBLE : View.GONE);
        }
        if (mPanelCheats != null) {
            mPanelCheats.setVisibility(tab == TAB_CHEATS ? View.VISIBLE : View.GONE);
        }
        if (mPanelCapture != null) {
            mPanelCapture.setVisibility(tab == TAB_CAPTURE ? View.VISIBLE : View.GONE);
        }
        if (mPanelSetup != null) {
            mPanelSetup.setVisibility(tab == TAB_SETUP ? View.VISIBLE : View.GONE);
        }
        setTabSelected(mTabPlay, tab == TAB_PLAY);
        setTabSelected(mTabStates, tab == TAB_STATES);
        setTabSelected(mTabCheats, tab == TAB_CHEATS);
        setTabSelected(mTabCapture, tab == TAB_CAPTURE);
        setTabSelected(mTabSetup, tab == TAB_SETUP);

        closeArtifactPanels();
        closeRomList();
        if (tab == TAB_STATES) {
            showSaveStates();
        } else if (tab == TAB_CHEATS) {
            showCheatGroup(mCheatGroup);
        }
        syncMenuScreens();

        if (focusContent) {
            restoreContentFocus();
        }
    }

    private static void setTabSelected(Button tab, boolean selected) {
        if (tab == null) {
            return;
        }
        tab.setSelected(selected);
        tab.setTextColor(selected ? ACCENT : TEXT_PRIMARY);
    }

    private void populateCheatGroups() {
        if (mCheatGroups == null) {
            return;
        }
        mCheatGroups.removeAllViews();
        for (String group : WorkshopCheats.groups()) {
            Button chip = new Button(this);
            chip.setText(group);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setTextColor(TEXT_PRIMARY);
            chip.setTextSize(12);
            chip.setTypeface(android.graphics.Typeface.SANS_SERIF);
            chip.setMinHeight(0);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(4), 0);
            chip.setLayoutParams(lp);
            String selected = group;
            chip.setOnClickListener(v -> showCheatGroup(selected));
            mCheatGroups.addView(chip);
        }
    }

    private void showCheatGroup(String group) {
        if (mCheatList == null) {
            return;
        }
        mCheatGroup = group == null ? WorkshopCheats.GROUP_QUICK : group;
        if (mCheatGroups != null) {
            for (int i = 0; i < mCheatGroups.getChildCount(); i++) {
                View child = mCheatGroups.getChildAt(i);
                if (child instanceof Button) {
                    Button chip = (Button) child;
                    boolean selected = mCheatGroup.equals(chip.getText().toString());
                    chip.setSelected(selected);
                    chip.setTextColor(selected ? ACCENT : TEXT_PRIMARY);
                }
            }
        }
        mCheatList.removeAllViews();
        if (!mNativeReady) {
            if (mCheatStatus != null) {
                mCheatStatus.setText(R.string.cheats_need_rom);
            }
            return;
        }
        if (mCheatStatus != null) {
            mCheatStatus.setText(R.string.cheats_hint);
        }
        if (WorkshopCheats.GROUP_PROGRESS.equals(mCheatGroup)) {
            showProgressionRows();
            return;
        }
        View first = null;
        for (WorkshopCheats.Action action : WorkshopCheats.forGroup(mCheatGroup)) {
            Button row = artifactButton(WorkshopCheats.rowLabel(action));
            row.setOnClickListener(v -> {
                focusPauseView(row);
                applyCheat(action);
            });
            mCheatList.addView(row);
            if (first == null) {
                first = row;
            }
        }
        if (first != null && mPauseTab == TAB_CHEATS) {
            restoreContentFocus();
        }
    }

    private void showProgressionRows() {
        if (mCheatStatus != null) {
            mCheatStatus.setText(mProgressionBusy
                    ? getString(R.string.progression_busy)
                    : getString(R.string.progression_hint, Progression.activeSlot(Progression.LIVE)));
        }
        View first = null;
        for (Progression.Checkpoint cp : Progression.checkpoints()) {
            boolean armed = cp.id.equals(mProgressionArmed)
                    && SystemClock.uptimeMillis() - mProgressionArmedAt < PROGRESSION_CONFIRM_MS;
            Button row = artifactButton(armed
                    ? getString(R.string.progression_confirm, cp.label)
                    : cp.rowLabel());
            row.setOnClickListener(v -> {
                focusPauseView(row);
                onProgressionRow(cp);
            });
            mCheatList.addView(row);
            if (first == null) {
                first = row;
            }
        }
        if (first != null && mPauseTab == TAB_CHEATS) {
            restoreContentFocus();
        }
    }

    /** First A arms the row; a second A within PROGRESSION_CONFIRM_MS applies it. */
    private void onProgressionRow(Progression.Checkpoint cp) {
        if (mArtifactBusy || !mNativeReady || mProgressionBusy) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!cp.id.equals(mProgressionArmed) || now - mProgressionArmedAt >= PROGRESSION_CONFIRM_MS) {
            mProgressionArmed = cp.id;
            mProgressionArmedAt = now;
            int index = mCheatList.indexOfChild(mCheatList.getFocusedChild());
            mCheatList.removeAllViews();
            showProgressionRows();
            if (index >= 0 && index < mCheatList.getChildCount()) {
                mCheatList.getChildAt(index).requestFocus();
            }
            return;
        }
        mProgressionArmed = null;
        String problem = Progression.idleProblem(Progression.LIVE);
        if (problem != null) {
            if (mCheatStatus != null) mCheatStatus.setText(problem);
            Toast.makeText(this, problem, Toast.LENGTH_SHORT).show();
            return;
        }
        mProgressionBusy = true;
        hidePauseMenu();
        Toast.makeText(this, getString(R.string.progression_running, cp.label), Toast.LENGTH_SHORT).show();
        Thread worker = new Thread(() -> {
            String result = Progression.apply(cp, Progression.LIVE);
            mHandler.post(() -> {
                mProgressionBusy = false;
                Log.i(TAG, "progression: " + result);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            });
        }, "oos-progression");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyCheat(WorkshopCheats.Action action) {
        if (mArtifactBusy || !mNativeReady) {
            return;
        }
        String result = WorkshopCheats.apply(action);
        showCheatGroup(mCheatGroup);
        if (mCheatStatus != null) {
            mCheatStatus.setText(result);
            mCheatStatus.setVisibility(View.VISIBLE);
        }
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
    }

    private boolean isVoiceRecording() {
        return mVoiceRecorder != null && mVoiceRecorder.isRecording();
    }

    private void toggleVoiceNote() {
        if (mArtifactBusy) {
            return;
        }
        if (isVoiceRecording()) {
            stopVoiceNote();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            setArtifactStatus(getString(R.string.voice_need_mic), true);
            return;
        }
        startVoiceNote();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceNote();
        } else {
            setArtifactStatus(getString(R.string.voice_need_mic), true);
        }
    }

    private void startVoiceNote() {
        if (mDestroyed || isVoiceRecording() || mArtifactBusy) {
            return;
        }
        try {
            JSONObject status = null;
            if (mNativeReady) {
                status = new JSONObject(MesenNative.statusJson(true));
            }
            String input = voiceInputHint();
            WorkshopVoiceNote.Stamp stamp = WorkshopVoiceNote.stampFromStatus(status, input);
            File workshopRoot = new File(getFilesDir(), "Workshop");
            WorkshopVoiceNote.Session session = WorkshopVoiceNote.begin(
                    workshopRoot, stamp, System.currentTimeMillis());
            WorkshopVoiceRecorder recorder = new WorkshopVoiceRecorder();
            recorder.start(session.audioFile);
            mVoiceSession = session;
            mVoiceRecorder = recorder;
            mHandler.removeCallbacks(mVoiceAutoStop);
            mHandler.postDelayed(mVoiceAutoStop, WorkshopVoiceNote.MAX_DURATION_MS);
            updateVoiceButtonUi(true);
            setArtifactStatus(getString(R.string.voice_recording), false);
        } catch (Exception error) {
            cancelVoiceNote(true);
            setArtifactStatus(getString(R.string.voice_failed, error.getMessage()), true);
        }
    }

    private void stopVoiceNote() {
        mHandler.removeCallbacks(mVoiceAutoStop);
        WorkshopVoiceNote.Session session = mVoiceSession;
        WorkshopVoiceRecorder recorder = mVoiceRecorder;
        mVoiceSession = null;
        mVoiceRecorder = null;
        updateVoiceButtonUi(false);
        if (recorder == null || session == null) {
            return;
        }
        try {
            long durationMs = recorder.stop();
            String digest = WorkshopArtifactFiles.sha256(session.audioFile);
            long bytes = session.audioFile.length();
            WorkshopVoiceNote.complete(session, durationMs, digest, bytes);
            int seconds = Math.max(1, (int) ((durationMs + 500) / 1000));
            setArtifactStatus(getString(R.string.voice_saved, seconds), true);
            Toast.makeText(this, getString(R.string.voice_saved, seconds), Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            WorkshopVoiceNote.discard(session);
            setArtifactStatus(getString(R.string.voice_failed, error.getMessage()), true);
        }
    }

    private void cancelVoiceNote(boolean discardFiles) {
        mHandler.removeCallbacks(mVoiceAutoStop);
        WorkshopVoiceNote.Session session = mVoiceSession;
        WorkshopVoiceRecorder recorder = mVoiceRecorder;
        mVoiceSession = null;
        mVoiceRecorder = null;
        if (recorder != null) {
            recorder.cancel();
        }
        if (discardFiles && session != null) {
            WorkshopVoiceNote.discard(session);
        }
        updateVoiceButtonUi(false);
    }

    private void updateVoiceButtonUi(boolean recording) {
        if (mVoiceButton == null) {
            return;
        }
        mVoiceButton.setText(recording ? R.string.voice_recording : R.string.voice_note);
        mVoiceButton.setTextColor(recording ? ACCENT : TEXT_PRIMARY);
    }

    private String voiceInputHint() {
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null && audio.isBluetoothScoOn()) {
                return "bluetooth-sco";
            }
            if (audio != null && audio.isWiredHeadsetOn()) {
                return "wired-headset";
            }
        } catch (Exception ignored) {
        }
        return "mic";
    }

    private static int dp(int value) {
        return Math.round(value * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    }

    private void hidePauseMenu() {
        if (mPauseScrim == null || mArtifactBusy) {
            return;
        }
        if (isVoiceRecording()) {
            stopVoiceNote();
        }
        mPauseVisible = false;
        mPauseHatLatch = 0;
        mPauseHatXLatch = 0;
        mHandler.removeCallbacks(mPadPoller);
        mPauseScrim.setVisibility(View.GONE);
        if (mRomScroller != null) {
            mRomScroller.setVisibility(View.GONE);
        }
        closeArtifactPanels();
        if (mNativeReady) {
            MesenNative.resumeEmu();
        }
        setSurfaceFocusable(true);
        updateFastForward();
        mHandler.postDelayed(this::hideHudBar, 2500);
        applyChipState();
    }

    private void showHudBar() {
        if (mHud == null) {
            return;
        }
        mHud.setVisibility(View.VISIBLE);
        mHud.animate().alpha(1f).setDuration(180).start();
    }

    private void hideHudBar() {
        if (mHud == null || mPauseVisible || mKeepHud) {
            return;
        }
        mHud.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (!mPauseVisible && !mKeepHud) {
                mHud.setVisibility(View.GONE);
            }
        }).start();
    }

    private void toggleRomList() {
        if (mRomScroller == null || mArtifactBusy) {
            return;
        }
        if (mRomScroller.getVisibility() == View.VISIBLE) {
            closeRomList();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_roms));
            return;
        }
        closeArtifactPanels();
        populateRoms();
        mRomScroller.setVisibility(View.VISIBLE);
        restoreContentFocus();
    }

    private void populateRoms() {
        mRomList.removeAllViews();
        List<File> roms = discoverRoms();
        if (roms.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.roms_empty);
            empty.setTextColor(TEXT_PRIMARY);
            empty.setTextSize(12);
            mRomList.addView(empty);
            return;
        }
        for (File rom : roms) {
            Button row = new Button(this, null, 0, R.style.OracleMenuButton);
            String folder = romSource(rom);
            row.setText(rom.getName() + (folder.isEmpty() ? "" : "  ·  " + folder)
                    + saveTag(rom));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, "");
            if (rom.getAbsolutePath().equals(last)) {
                row.setTextColor(ACCENT);
            }
            row.setOnClickListener(v -> {
                if (mArtifactBusy) {
                    return;
                }
                if (mNativeReady) {
                    BatterySaveSeed.Status seeded = seedBatterySave(rom.getAbsolutePath());
                    if (seeded == BatterySaveSeed.Status.SEEDED) {
                        Toast.makeText(this, "Imported " + BatterySaveSeed.sidecar(rom).getName(),
                                Toast.LENGTH_SHORT).show();
                    }
                }
                if (mNativeReady && MesenNative.loadRom(rom.getAbsolutePath())) {
                    rememberRom(rom.getAbsolutePath());
                    applyPerformanceMode();
                    hidePauseMenu();
                    refreshHud();
                }
            });
            mRomList.addView(row);
        }
    }

    private List<File> discoverRoms() {
        File[] roots = new File[] {
                getExternalFilesDir(null),
                new File("/storage/emulated/0/Android/data/ca.mesen.oos/files"),
                new File("/storage/emulated/0/OracleOfSecrets"),
                new File("/storage/0000-0000/snes")
        };
        return RomFiles.discover(roots);
    }

    private interface ArtifactTask<T> {
        T run() throws Exception;
    }

    private interface ArtifactResult<T> {
        void done(T value);
    }

    private <T> void runArtifactTask(int progressMessage, int errorMessage,
            int returnFocus, ArtifactTask<T> task, ArtifactResult<T> result) {
        if (mArtifactBusy || mDestroyed) {
            return;
        }
        if (!mNativeReady) {
            setArtifactStatus(getString(R.string.artifact_not_ready), true);
            return;
        }
        setArtifactStatus(getString(progressMessage), false);
        setArtifactBusy(true);
        mArtifactExecutor.execute(() -> {
            try {
                T value = task.run();
                mHandler.post(() -> {
                    if (mDestroyed) {
                        return;
                    }
                    setArtifactBusy(false);
                    focusPauseView(mPauseScrim.findViewById(returnFocus));
                    result.done(value);
                });
            } catch (Exception error) {
                Log.w(TAG, "Workshop artifact action failed", error);
                mHandler.post(() -> {
                    if (mDestroyed) {
                        return;
                    }
                    setArtifactBusy(false);
                    String detail = error.getMessage();
                    if (detail == null || detail.isEmpty()) {
                        detail = error.getClass().getSimpleName();
                    }
                    setArtifactStatus(getString(errorMessage, detail), true);
                    focusPauseView(mPauseScrim.findViewById(returnFocus));
                });
            }
        });
    }

    private void setArtifactBusy(boolean busy) {
        mArtifactBusy = busy;
        if (busy) {
            mBusyDisabledViews.clear();
            mBusyDisabledViews.addAll(pauseFocusables());
            addBusyView(mArtifactTitle);
            addBusyView(mArtifactNote);
            for (View view : mBusyDisabledViews) {
                view.setEnabled(false);
            }
        } else {
            for (View view : mBusyDisabledViews) {
                view.setEnabled(true);
            }
            mBusyDisabledViews.clear();
        }
    }

    private void addBusyView(View view) {
        if (view != null) {
            mBusyDisabledViews.add(view);
        }
    }

    private static String fieldText(EditText field) {
        if (field == null || field.getText() == null) {
            return null;
        }
        String value = field.getText().toString();
        return value.trim().isEmpty() ? null : value;
    }

    private boolean isArtifactEditor(View view) {
        return view != null && (view == mArtifactTitle || view == mArtifactNote);
    }

    private void setArtifactStatus(String message, boolean announce) {
        if (mArtifactStatus != null) {
            mArtifactStatus.setText(message);
            mArtifactStatus.setVisibility(View.VISIBLE);
        }
        if (announce) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void closeArtifactPanels() {
        if (mArtifactStatus != null) {
            mArtifactStatus.setVisibility(View.GONE);
        }
        if (mBugCategories != null) {
            mBugCategories.setVisibility(View.GONE);
        }
        if (mCheckpointScroller != null) {
            mCheckpointScroller.setVisibility(View.GONE);
        }
        if (mRestoreReview != null) {
            mRestoreReview.setVisibility(View.GONE);
        }
        if (mStateScroller != null) {
            mStateScroller.setVisibility(View.GONE);
        }
        if (mStateReview != null) {
            mStateReview.setVisibility(View.GONE);
        }
        mSelectedCheckpoint = null;
        mSelectedState = null;
        syncMenuScreens();
    }

    private void closeRomList() {
        if (mRomScroller != null) {
            mRomScroller.setVisibility(View.GONE);
        }
        syncMenuScreens();
    }

    private void showSaveStates() {
        mStateList.removeAllViews();
        boolean pathsAvailable = false;
        for (int slot = 1; slot <= SaveStateSlots.SLOT_COUNT; slot++) {
            SaveStateSlots.Entry entry = SaveStateSlots.inspect(
                    slot, MesenNative.saveStatePath(slot));
            pathsAvailable |= entry.file != null;
            Button row = artifactButton(entry.title());
            if (entry.occupied) {
                row.setTextColor(ACCENT);
            }
            row.setOnClickListener(v -> {
                focusPauseView(row);
                reviewSaveState(entry);
            });
            mStateList.addView(row);
        }
        if (!pathsAvailable) {
            setArtifactStatus(getString(R.string.states_empty), true);
            return;
        }
        mStateScroller.setVisibility(View.VISIBLE);
        setArtifactStatus(getString(R.string.states_choose), false);
        restoreContentFocus();
    }

    private void reviewSaveState(SaveStateSlots.Entry entry) {
        if (mArtifactBusy || entry.file == null) {
            return;
        }
        closeArtifactPanels();
        mSelectedState = entry;
        mStateDescription.setText(entry.occupied
                ? getString(R.string.state_review_occupied, entry.title())
                : getString(R.string.state_review_empty, entry.slot));
        mStateSave.setText(entry.occupied ? R.string.state_overwrite : R.string.state_save);
        mStateLoad.setEnabled(entry.occupied);
        mStateLoad.setFocusable(entry.occupied);
        mStateLoad.setFocusableInTouchMode(entry.occupied);
        mStateReview.setVisibility(View.VISIBLE);
        focusPauseView(mPauseScrim.findViewById(R.id.btn_cancel_state));
    }

    private void saveSelectedState() {
        SaveStateSlots.Entry entry = mSelectedState;
        if (mArtifactBusy || entry == null) {
            return;
        }
        int slot = entry.slot;
        closeArtifactPanels();
        runArtifactTask(R.string.state_saving, R.string.state_save_failed,
                R.id.tab_states, () -> {
                    if (!MesenNative.saveState(slot)) {
                        throw new IllegalStateException("native save failed");
                    }
                    return slot;
                }, savedSlot -> {
                    showSaveStates();
                    setArtifactStatus(getString(R.string.state_saved, savedSlot), true);
                });
    }

    private void loadSelectedState() {
        SaveStateSlots.Entry entry = mSelectedState;
        if (mArtifactBusy || entry == null || !entry.occupied) {
            return;
        }
        int slot = entry.slot;
        closeArtifactPanels();
        runArtifactTask(R.string.state_loading, R.string.state_load_failed,
                R.id.tab_states, () -> {
                    if (!MesenNative.loadState(slot)) {
                        throw new IllegalStateException("native load failed");
                    }
                    return slot;
                }, loadedSlot -> {
                    showSaveStates();
                    setArtifactStatus(getString(R.string.state_loaded, loadedSlot), true);
                    refreshHud();
                });
    }

    private void saveCheckpoint() {
        if (mArtifactBusy) {
            return;
        }
        closeArtifactPanels();
        closeRomList();
        runArtifactTask(R.string.artifact_saving, R.string.artifact_save_failed,
                R.id.btn_checkpoint_save, () -> mArtifacts.saveCheckpoint(
                        fieldText(mArtifactTitle), fieldText(mArtifactNote)), entry -> {
                    setArtifactStatus(getString(R.string.artifact_saved,
                            entry.title), true);
                });
    }

    private Button artifactButton(String title) {
        Button button = new Button(this, null, 0, R.style.OracleMenuButton);
        button.setText(title);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void populateBugCategories() {
        String[] categories = { "weather", "collision", "audio", "graphics", "other" };
        int[] labels = { R.string.bug_weather, R.string.bug_collision, R.string.bug_audio,
                R.string.bug_graphics, R.string.bug_other };
        for (int i = 0; i < categories.length; i++) {
            String category = categories[i];
            Button row = artifactButton(getString(labels[i]));
            row.setOnClickListener(v -> captureBug(category));
            mBugCategories.addView(row);
        }
        Button cancel = artifactButton(getString(R.string.artifact_cancel));
        cancel.setOnClickListener(v -> {
            closeArtifactPanels();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_bug_capture));
        });
        mBugCategories.addView(cancel);
    }

    private void toggleBugCategories() {
        if (mArtifactBusy) {
            return;
        }
        boolean wasVisible = mBugCategories.getVisibility() == View.VISIBLE;
        closeArtifactPanels();
        closeRomList();
        if (!wasVisible) {
            mBugCategories.setVisibility(View.VISIBLE);
            setArtifactStatus(getString(R.string.bug_choose_category), false);
            restoreContentFocus();
        }
    }

    private void captureBug(String category) {
        if (mArtifactBusy) {
            return;
        }
        closeArtifactPanels();
        runArtifactTask(R.string.artifact_capturing, R.string.artifact_capture_failed,
                R.id.btn_bug_capture, () -> mArtifacts.captureBug(
                        category, fieldText(mArtifactTitle), fieldText(mArtifactNote)), entry -> {
                    setArtifactStatus(getString(R.string.artifact_captured,
                            entry.title), true);
                });
    }

    private void toggleCheckpoints() {
        if (mArtifactBusy) {
            return;
        }
        boolean wasVisible = mCheckpointScroller.getVisibility() == View.VISIBLE
                || mRestoreReview.getVisibility() == View.VISIBLE;
        closeArtifactPanels();
        closeRomList();
        if (wasVisible) {
            return;
        }
        runArtifactTask(R.string.artifact_listing, R.string.artifact_list_failed,
                R.id.btn_checkpoints, () -> mArtifacts.listCheckpoints(), this::showCheckpoints);
    }

    private void showCheckpoints(List<WorkshopArtifacts.Entry> entries) {
        mCheckpointList.removeAllViews();
        if (entries.isEmpty()) {
            setArtifactStatus(getString(R.string.checkpoints_empty), true);
            return;
        }
        View firstCompatible = null;
        for (WorkshopArtifacts.Entry entry : entries) {
            boolean compatible = entry.compatibilityReason.isEmpty();
            Button row = artifactButton(entry.title + (compatible ? ""
                    : "\n" + getString(R.string.checkpoint_incompatible, entry.compatibilityReason)));
            row.setEnabled(compatible);
            row.setFocusable(compatible);
            row.setFocusableInTouchMode(compatible);
            if (compatible) {
                row.setOnClickListener(v -> {
                    focusPauseView(row);
                    reviewCheckpoint(entry);
                });
                mCheckpointList.addView(row);
            } else {
                // Keep unavailable reasons reachable by D-pad without offering
                // an actionable checkpoint or an enabled Restore control.
                LinearLayout readOnlyRow = new LinearLayout(this);
                readOnlyRow.setOrientation(LinearLayout.VERTICAL);
                readOnlyRow.setFocusable(true);
                readOnlyRow.setFocusableInTouchMode(true);
                readOnlyRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                readOnlyRow.setBackgroundResource(R.drawable.menu_row);
                readOnlyRow.setContentDescription(row.getText());
                row.setBackground(null);
                row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                readOnlyRow.addView(row);
                mCheckpointList.addView(readOnlyRow);
            }
            if (compatible && firstCompatible == null) {
                firstCompatible = row;
            }
        }
        mCheckpointScroller.setVisibility(View.VISIBLE);
        setArtifactStatus(getString(firstCompatible == null
                ? R.string.checkpoints_none_compatible : R.string.checkpoints_choose), false);
        if (mMenuSelection.containsKey("checkpoints")) {
            restoreContentFocus();
        } else {
            focusPauseView(firstCompatible != null ? firstCompatible : mCheckpointList.getChildAt(0));
        }
    }

    private void reviewCheckpoint(WorkshopArtifacts.Entry entry) {
        if (mArtifactBusy || !entry.compatibilityReason.isEmpty()) {
            return;
        }
        closeArtifactPanels();
        mSelectedCheckpoint = entry;
        mRestoreDescription.setText(getString(R.string.checkpoint_restore_review, entry.title));
        mRestoreReview.setVisibility(View.VISIBLE);
        // Selecting a checkpoint only opens review; a second action restores it.
        focusPauseView(mPauseScrim.findViewById(R.id.btn_cancel_restore));
    }

    private void restoreCheckpoint() {
        WorkshopArtifacts.Entry entry = mSelectedCheckpoint;
        if (mArtifactBusy || entry == null || !entry.compatibilityReason.isEmpty()) {
            return;
        }
        closeArtifactPanels();
        runArtifactTask(R.string.artifact_restoring, R.string.artifact_restore_failed,
                R.id.btn_checkpoints, () -> {
                    mArtifacts.restoreCheckpoint(entry);
                    return entry;
                }, restored -> {
                    setArtifactStatus(getString(R.string.artifact_restored,
                            restored.title), true);
                    refreshHud();
                });
    }

    private void focusPauseView(View view) {
        syncMenuScreens();
        if (view != null && view.isEnabled() && view.isShown()) {
            view.requestFocus();
            List<View> rows = contentFocusables();
            int index = rows.indexOf(view);
            if (index >= 0) mMenuSelection.put(menuScreenKey(), index);
            view.post(() -> {
                Rect bounds = new Rect();
                view.getDrawingRect(bounds);
                view.requestRectangleOnScreen(bounds, false);
            });
        }
    }

    private void setHomeStatus(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (mHomeStatus != null) {
            mHomeStatus.setText(message);
            mHomeStatus.setVisibility(View.VISIBLE);
        }
    }

    private void requestHomeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            setHomeStatus(R.string.home_unsupported);
            return;
        }
        try {
            ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
            if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
                setHomeStatus(R.string.home_unsupported);
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
            // Opening the app must preserve an existing session. A "rom" extra
            // would instead load that ROM again through onNewIntent().
            Intent open = new Intent(Intent.ACTION_MAIN, null, this, MesenActivity.class);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, HOME_SHORTCUT_ID)
                    .setShortLabel(getString(R.string.app_name))
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(open)
                    .build();
            // Older Android versions cannot mark dynamic receivers unexported.
            // Only the launcher holding this immutable callback knows the token.
            mHomeRequestToken = UUID.randomUUID().toString();
            Intent result = new Intent(HOME_SHORTCUT_PINNED).setPackage(getPackageName())
                    .putExtra(HOME_REQUEST_TOKEN, mHomeRequestToken);
            PendingIntent callback = PendingIntent.getBroadcast(this, 0, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_ONE_SHOT);
            // A true return means the launcher accepted the request. Only the
            // callback confirms that the user actually pinned the shortcut.
            if (shortcuts.requestPinShortcut(shortcut, callback.getIntentSender())) {
                setHomeStatus(R.string.home_requested);
            } else {
                mHomeRequestToken = null;
                setHomeStatus(R.string.home_unsupported);
            }
        } catch (RuntimeException e) {
            mHomeRequestToken = null;
            Log.w(TAG, "Home shortcut request failed", e);
            setHomeStatus(R.string.home_failed);
        }
    }

    private void refreshHud() {
        if (!mNativeReady || mHudRom == null) {
            return;
        }
        try {
            // Full pad/key scan only when the pause menu or Keep HUD needs it.
            // Workshop telemetry still gets frames/render counters via the lite path.
            JSONObject json = new JSONObject(MesenNative.statusJson(mPauseVisible || mKeepHud));
            String rom = json.optString("rom", "");
            mHudRom.setText(rom.isEmpty() ? getString(R.string.hud_waiting) : rom);
            String socket = json.optString("socket", "tcp:27015");
            boolean pad = json.optBoolean("pad", false);
            boolean mapped = json.optBoolean("mapped", false);
            String padName = json.optString("padName", "");
            String buttons = json.optString("buttons", "");
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
            json.put("observedFps",
                    observedFps == null ? JSONObject.NULL : observedFps);
            json.put("presentFps",
                    presentFps == null ? JSONObject.NULL : presentFps);
            json.put("observedFpsState",
                    paused ? (observedFps == null ? "paused" : "last_running")
                            : (observedFps == null ? "measuring" : "live"));
            if (mArtifactBuild != null) {
                String shortSha = sha1.length() > 12 ? sha1.substring(0, 12) : sha1;
                mArtifactBuild.setText(getString(R.string.artifact_current_build,
                        rom.isEmpty() ? getString(R.string.artifact_no_rom) : rom,
                        shortSha.isEmpty() ? getString(R.string.artifact_unknown_hash) : shortSha));
            }
            int agents = mWorkshop != null ? mWorkshop.agentCount() : 0;
            String endpoint = mWorkshop != null ? mWorkshop.adbEndpoint() : socket;
            String agentBit = agents > 0 ? (agents + " agents") : "ready";
            mHudSocket.setText(socket + (pad ? " · pad" : " · no pad"));
            if (mHudLink != null) {
                mHudLink.setText(endpoint + "  ·  " + modeLabel(json)
                        + "  ·  " + performance + "  ·  " + agentBit);
                mHudLink.setVisibility(mKeepHud || mPauseVisible ? View.VISIBLE : View.GONE);
            }
            if (mWorkshopLink != null) {
                mWorkshopLink.setText(endpoint);
            }
            if (mWorkshopMeta != null) {
                String shortSha = sha1.length() > 12 ? sha1.substring(0, 12) : sha1;
                mWorkshopMeta.setText(modeLabel(json)
                        + "  ·  " + performance + "  ·  " + agentBit
                        + (shortSha.isEmpty() ? "" : "  ·  " + shortSha));
            }
            if (mPauseStatus != null) {
                String label = padName.isEmpty() ? "waiting for pad" : padName;
                if (mapped) {
                    label += " mapped";
                }
                mPauseStatus.setText(socket + "  ·  " + label);
            }
            if (mPausePad != null) {
                mPausePad.setText(buttons.isEmpty() ? getString(R.string.pad_idle) : "pad: " + buttons);
            }
            if (mWorkshop != null) {
                mWorkshop.writeManifest(json);
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
        // NotificationShade / adb can clear focus on the RG353P. Force the
        // resume flags so SDL's normal gate can proceed — but never start the
        // native thread before SDLSurface reports ready. Creating the SDL
        // window without an ANativeWindow leaves renderDriver=none forever.
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
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(PREF_LAST_ROM, path).apply();
    }

    private void setKeepHud(boolean keep) {
        mKeepHud = keep;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HUD, keep).apply();
        applyChipState();
        if (keep) {
            showHudBar();
        } else if (!mPauseVisible) {
            hideHudBar();
        }
        refreshHud();
    }

    private void setFillScreen(boolean fill) {
        mFillScreen = fill;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_FIT, fill).apply();
        applyChipState();
        applyPresentMode();
    }

    private void setSmoothPlay(boolean enabled) {
        mSmoothPlay = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_PLAY, enabled).apply();
        applyChipState();
        applyPerformanceMode();
        refreshHud();
    }

    private void setPreferGpu(boolean enabled) {
        mPreferGpu = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_GPU, enabled).apply();
        applyChipState();
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
        setFastForwardHeld(!mUiVisible && !mPauseVisible
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
        if (enabled) {
            showHudBar();
        } else if (!mPauseVisible) {
            mHandler.postDelayed(this::hideHudBar, 800);
        }
        refreshHud();
    }

    private void applyChipState() {
        if (mChipHud != null) {
            mChipHud.setSelected(mKeepHud);
            mChipHud.setTextColor(mKeepHud ? ACCENT : TEXT_PRIMARY);
        }
        if (mChipFit != null) {
            mChipFit.setSelected(mFillScreen);
            mChipFit.setTextColor(mFillScreen ? ACCENT : TEXT_PRIMARY);
        }
        if (mChipPlay != null) {
            mChipPlay.setSelected(mSmoothPlay);
            mChipPlay.setTextColor(mSmoothPlay ? ACCENT : TEXT_PRIMARY);
        }
        if (mChipGpu != null) {
            mChipGpu.setSelected(mPreferGpu);
            mChipGpu.setTextColor(mPreferGpu ? ACCENT : TEXT_PRIMARY);
        }
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
    }

    // Playtest note: `am start ... --es note "Test: ..." --ei note_secs 20`.
    // A bottom banner over the game; an empty note hides it, note_secs <= 0 keeps it up.
    private TextView mNoteView;
    private final Runnable mHideNote = () -> {
        if (mNoteView != null) {
            mNoteView.setVisibility(View.GONE);
        }
    };

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

    private File savesDir() {
        return new File(getFilesDir(), "Saves");
    }

    /** Copies Name.srm beside the ROM into Saves/ once; never overwrites. */
    private BatterySaveSeed.Status seedBatterySave(String romPath) {
        BatterySaveSeed.Status status = BatterySaveSeed.seed(new File(romPath), savesDir());
        Log.v(TAG, "battery save seed " + status + " for " + romPath);
        return status;
    }

    private String saveTag(File rom) {
        if (BatterySaveSeed.target(rom, savesDir()).isFile()) {
            return "  ·  save";
        }
        File sidecar = BatterySaveSeed.sidecar(rom);
        return sidecar != null && sidecar.isFile() ? "  ·  test save" : "";
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
                    : String.format(Locale.US, "PAUSED · last %.1f fps",
                            observedFps);
        }
        return observedFps == null
                ? "measuring"
                : String.format(Locale.US, "%.1f fps", observedFps);
    }

    private List<View> railViews() {
        return java.util.Arrays.asList(mTabPlay, mTabStates, mTabCheats, mTabSetup, mTabCapture);
    }

    private View selectedRailView() {
        switch (mPauseTab) {
            case TAB_STATES: return mTabStates;
            case TAB_CHEATS: return mTabCheats;
            case TAB_SETUP: return mTabSetup;
            case TAB_CAPTURE: return mTabCapture;
            default: return mTabPlay;
        }
    }

    private void selectRailView(View view) {
        int tab = view == mTabStates ? TAB_STATES : view == mTabCheats ? TAB_CHEATS
                : view == mTabSetup ? TAB_SETUP : view == mTabCapture ? TAB_CAPTURE : TAB_PLAY;
        selectPauseTab(tab, false);
        focusPauseView(view);
    }

    private void movePauseFocus(boolean up) {
        boolean onRail = railViews().contains(getCurrentFocus());
        List<View> views = onRail ? railViews() : contentFocusables();
        if (views.isEmpty()) return;
        int index = views.indexOf(getCurrentFocus());
        // Endpoints stop instead of wrapping into another screen. Content
        // button rows (group tabs, dialog buttons) count as one vertical stop.
        int next = onRail ? MenuNavigation.next(index, views.size(), up)
                : MenuNavigation.nextAcrossRows(horizontalRowIds(views), index, up);
        if (onRail) selectRailView(views.get(next));
        else focusPauseView(views.get(next));
    }

    private void cyclePauseTab(boolean left) {
        List<View> rail = railViews();
        int next = MenuNavigation.next(rail.indexOf(selectedRailView()), rail.size(), left);
        selectRailView(rail.get(next));
    }

    private void cycleCheatGroup(boolean left) {
        List<String> groups = WorkshopCheats.groups();
        if (groups.isEmpty()) {
            return;
        }
        int idx = groups.indexOf(mCheatGroup);
        if (idx < 0) {
            idx = 0;
        }
        int next = idx + (left ? -1 : 1);
        if (next < 0) {
            next = groups.size() - 1;
        } else if (next >= groups.size()) {
            next = 0;
        }
        showCheatGroup(groups.get(next));
    }

    private void movePauseFocusHorizontal(boolean left) {
        View focus = getCurrentFocus();
        if (railViews().contains(focus)) {
            if (!left) restoreContentFocus();
            return;
        }
        // Button rows (e.g. the state slot dialog) move between their buttons first.
        View sibling = horizontalRowNeighbor(focus, left);
        if (sibling != null) {
            focusPauseView(sibling);
        } else if (left) {
            focusPauseView(selectedRailView());
        }
    }

    private int[] horizontalRowIds(List<View> views) {
        int[] ids = new int[views.size()];
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            Object parent = views.get(i).getParent();
            boolean row = parent instanceof android.widget.LinearLayout
                    && ((android.widget.LinearLayout) parent).getOrientation()
                    == android.widget.LinearLayout.HORIZONTAL;
            if (!row) { ids[i] = -1; continue; }
            int id = rows.indexOf(parent);
            if (id < 0) { rows.add(parent); id = rows.size() - 1; }
            ids[i] = id;
        }
        return ids;
    }

    private View horizontalRowNeighbor(View focus, boolean left) {
        if (focus == null || !(focus.getParent() instanceof android.widget.LinearLayout)) return null;
        android.widget.LinearLayout row = (android.widget.LinearLayout) focus.getParent();
        if (row.getOrientation() != android.widget.LinearLayout.HORIZONTAL) return null;
        int count = row.getChildCount();
        boolean[] usable = new boolean[count];
        int current = -1;
        for (int i = 0; i < count; i++) {
            View child = row.getChildAt(i);
            usable[i] = child.isShown() && child.isEnabled() && child.isFocusable();
            if (child == focus) current = i;
        }
        int next = MenuNavigation.sideways(usable, current, left);
        return next < 0 ? null : row.getChildAt(next);
    }

    private static boolean visible(View view) {
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    private void syncMenuScreens() {
        if (mPauseScrim == null) return;
        boolean bug = visible(mBugCategories);
        boolean workshopChild = bug || visible(mCheckpointScroller) || visible(mRestoreReview);
        mPauseScrim.findViewById(R.id.workshop_actions).setVisibility(workshopChild ? View.GONE : View.VISIBLE);
        mPauseScrim.findViewById(R.id.bug_scroller).setVisibility(bug ? View.VISIBLE : View.GONE);
        mPauseScrim.findViewById(R.id.game_actions).setVisibility(visible(mRomScroller) ? View.GONE : View.VISIBLE);
    }

    private String menuScreenKey() {
        if (mPauseTab == TAB_PLAY && visible(mRomScroller)) return "roms";
        if (mPauseTab == TAB_STATES) return visible(mStateReview) ? "state-review" : "states";
        if (mPauseTab == TAB_CHEATS) return "cheats-" + mCheatGroup;
        if (mPauseTab == TAB_CAPTURE) {
            if (visible(mRestoreReview)) return "restore-review";
            if (visible(mCheckpointScroller)) return "checkpoints";
            if (visible(mBugCategories)) return "bug-categories";
        }
        return "tab-" + mPauseTab;
    }

    private List<View> contentFocusables() {
        List<View> views = new ArrayList<>();
        if (mPauseScrim == null) return views;
        View root;
        switch (mPauseTab) {
            case TAB_STATES: root = visible(mStateReview) ? mStateReview : mStateScroller; break;
            case TAB_CHEATS: root = mPanelCheats; break;
            case TAB_SETUP: root = mPanelSetup; break;
            case TAB_CAPTURE:
                root = visible(mRestoreReview) ? mRestoreReview
                        : visible(mCheckpointScroller) ? mCheckpointScroller
                        : visible(mBugCategories) ? mBugCategories
                        : mPauseScrim.findViewById(R.id.workshop_actions);
                break;
            default: root = visible(mRomScroller) ? mRomScroller : mPauseScrim.findViewById(R.id.game_actions);
        }
        collectMenuRows(root, views);
        return views;
    }

    private void collectMenuRows(View view, List<View> views) {
        if (view == null || !view.isShown() || !view.isEnabled() || isArtifactEditor(view)) return;
        if (view.isFocusable() && !(view instanceof ScrollView)
                && !(view instanceof android.widget.HorizontalScrollView)) {
            views.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectMenuRows(group.getChildAt(i), views);
        }
    }

    private void restoreContentFocus() {
        syncMenuScreens();
        List<View> views = contentFocusables();
        int remembered = mMenuSelection.getOrDefault(menuScreenKey(), 0);
        focusPauseView(views.isEmpty() ? selectedRailView()
                : views.get(Math.max(0, Math.min(remembered, views.size() - 1))));
    }

    private List<View> pauseFocusables() {
        List<View> views = new ArrayList<>(railViews());
        views.addAll(contentFocusables());
        return views;
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
