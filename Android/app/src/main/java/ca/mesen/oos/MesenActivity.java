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
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
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

public class MesenActivity extends SDLActivity {
    private static final String TAG = "MesenActivity";
    private static final String PREFS = "oracle";
    private static final String PREF_LAST_ROM = "last_rom";
    private static final String PREF_HUD = "keep_hud";
    private static final String PREF_FIT = "fill_screen";
    private static final String HOME_SHORTCUT_ID = "oracle-open";
    private static final String HOME_SHORTCUT_PINNED = "ca.mesen.oos.HOME_SHORTCUT_PINNED";
    private static final String HOME_REQUEST_TOKEN = "home_request_token";
    private static final int GOLD = Color.parseColor("#D4A017");
    private static final int PARCHMENT = Color.parseColor("#E6D7A2");
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
    private WorkshopArtifacts mArtifacts;
    private WorkshopArtifacts.Entry mSelectedCheckpoint;
    private boolean mArtifactBusy;
    private boolean mDestroyed;
    private boolean mPauseVisible;
    private boolean mNativeReady;
    private boolean mKeepHud;
    private boolean mFillScreen;
    private int mPauseHatLatch;
    private int mPauseHatXLatch;
    private final FrameRateTracker mFrameRateTracker = new FrameRateTracker();
    private WorkshopLink mWorkshop;
    private boolean mHomeReceiverRegistered;
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
                return new String[] { rom };
            }
            Uri data = intent.getData();
            if (data != null && data.getPath() != null && !data.getPath().isEmpty()) {
                rememberRom(data.getPath());
                return new String[] { data.getPath() };
            }
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, null);
        if (last != null && new File(last).isFile()) {
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
        attachOverlay();
        applyIntentPrefs(getIntent());
        mWorkshop.start();
        startNativeIfNeeded();
        mHandler.postDelayed(this::markNativeReady, 1200);
        mHandler.postDelayed(this::refreshHud, 1500);
        mHandler.postDelayed(this::hideHudBar, 4500);
        keepVisibleScreenOn();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        // Let an in-flight artifact finish writing; never interrupt a state save.
        mArtifactExecutor.shutdown();
        if (sInstance == this) {
            sInstance = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        if (mWorkshop != null) {
            mWorkshop.stop();
        }
        if (mHomeReceiverRegistered) {
            unregisterReceiver(mHomeReceiver);
            mHomeReceiverRegistered = false;
        }
        mHomeRequestToken = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
            startNativeIfNeeded();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepVisibleScreenOn();
        if (mWorkshop != null) {
            mWorkshop.refresh(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
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
                if (MesenNative.loadRom(rom)) {
                    rememberRom(rom);
                    refreshHud();
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        togglePauseMenu();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                togglePauseMenu();
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
                if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ESCAPE) {
                    hidePauseMenu();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BUTTON_B
                        || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    View focused = getCurrentFocus();
                    if (focused != null && event.getRepeatCount() == 0) {
                        focused.performClick();
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
        if (!mPauseVisible) {
            return super.dispatchGenericMotionEvent(event);
        }
        int source = event.getSource();
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
        activity.mHandler.post(activity::showPauseMenu);
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
        overlay.findViewById(R.id.btn_checkpoints).setOnClickListener(v -> toggleCheckpoints());
        overlay.findViewById(R.id.btn_restore_checkpoint).setOnClickListener(v -> restoreCheckpoint());
        overlay.findViewById(R.id.btn_cancel_restore).setOnClickListener(v -> {
            closeArtifactPanels();
            focusPauseView(mPauseScrim.findViewById(R.id.btn_checkpoints));
        });
        populateBugCategories();
        if (mChipHud != null) {
            mChipHud.setOnClickListener(v -> setKeepHud(!mKeepHud));
        }
        if (mChipFit != null) {
            mChipFit.setOnClickListener(v -> setFillScreen(!mFillScreen));
        }
        applyChipState();
        applyPresentMode();
    }

    private void markNativeReady() {
        mNativeReady = true;
        applyPresentMode();
        refreshHud();
        mHandler.removeCallbacks(mStatusPoller);
        mHandler.post(mStatusPoller);
        if (mKeepHud) {
            showHudBar();
        }
    }

    private void keepVisibleScreenOn() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // SDL may also update the window flag asynchronously. A visible-view
        // request remains part of Android's effective window attributes and
        // stops applying when this app's window is no longer visible.
        window.getDecorView().setKeepScreenOn(true);
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

    private void togglePauseMenu() {
        if (mPauseVisible) {
            hidePauseMenu();
        } else {
            showPauseMenu();
        }
    }

    private void showPauseMenu() {
        if (mPauseScrim == null) {
            return;
        }
        mPauseVisible = true;
        setSurfaceFocusable(false);
        mPauseScrim.setVisibility(View.VISIBLE);
        showHudBar();
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

    private void hidePauseMenu() {
        if (mPauseScrim == null || mArtifactBusy) {
            return;
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
            mRomScroller.setVisibility(View.GONE);
            return;
        }
        closeArtifactPanels();
        populateRoms();
        mRomScroller.setVisibility(View.VISIBLE);
    }

    private void populateRoms() {
        mRomList.removeAllViews();
        List<File> roms = discoverRoms();
        if (roms.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.roms_empty);
            empty.setTextColor(PARCHMENT);
            empty.setTextSize(12);
            mRomList.addView(empty);
            return;
        }
        for (File rom : roms) {
            Button row = new Button(this, null, 0, R.style.OracleMenuButton);
            String folder = romSource(rom);
            row.setText(rom.getName() + (folder.isEmpty() ? "" : "  ·  " + folder));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST_ROM, "");
            if (rom.getAbsolutePath().equals(last)) {
                row.setTextColor(GOLD);
            }
            row.setOnClickListener(v -> {
                if (mArtifactBusy) {
                    return;
                }
                if (mNativeReady && MesenNative.loadRom(rom.getAbsolutePath())) {
                    rememberRom(rom.getAbsolutePath());
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
        if (mBugCategories != null) {
            mBugCategories.setVisibility(View.GONE);
        }
        if (mCheckpointScroller != null) {
            mCheckpointScroller.setVisibility(View.GONE);
        }
        if (mRestoreReview != null) {
            mRestoreReview.setVisibility(View.GONE);
        }
        mSelectedCheckpoint = null;
    }

    private void closeRomList() {
        if (mRomScroller != null) {
            mRomScroller.setVisibility(View.GONE);
        }
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
            focusPauseView(mBugCategories.getChildAt(0));
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
                row.setOnClickListener(v -> reviewCheckpoint(entry));
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
        focusPauseView(firstCompatible != null ? firstCompatible
                : mCheckpointList.getChildAt(0));
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

    private static void focusPauseView(View view) {
        if (view != null && view.isEnabled() && view.isShown()) {
            view.requestFocus();
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
            JSONObject json = new JSONObject(MesenNative.statusJson());
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
            String performance = performanceLabel(paused, observedFps);
            json.put("observedFps",
                    observedFps == null ? JSONObject.NULL : observedFps);
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
            String lan = mWorkshop != null ? mWorkshop.lanEndpoint() : socket;
            String agentBit = agents > 0 ? (agents + " agents") : "ready";
            mHudSocket.setText(socket + (pad ? " · pad" : " · no pad"));
            if (mHudLink != null) {
                mHudLink.setText(lan + "  ·  " + modeLabel(json)
                        + "  ·  " + performance + "  ·  " + agentBit);
                mHudLink.setVisibility(mKeepHud || mPauseVisible ? View.VISIBLE : View.GONE);
            }
            if (mWorkshopLink != null) {
                mWorkshopLink.setText(lan);
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

    private void startNativeIfNeeded() {
        SDLActivity.mHasFocus = true;
        SDLActivity.mIsResumedCalled = true;
        resumeNativeThread();
        if (SDLActivity.mSDLThread != null) {
            return;
        }
        Log.v(TAG, "forcing SDL thread start");
        Thread thread = new Thread(() -> {
            Log.v(TAG, "starting native SDL_main");
            SDLActivity.nativeRunMain(getMainSharedObject(), getMainFunction(), getArguments());
            Log.v(TAG, "native SDL_main finished");
        }, "SDLThread");
        SDLActivity.mSDLThread = thread;
        thread.start();
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

    private void applyChipState() {
        if (mChipHud != null) {
            mChipHud.setSelected(mKeepHud);
            mChipHud.setTextColor(mKeepHud ? GOLD : PARCHMENT);
        }
        if (mChipFit != null) {
            mChipFit.setSelected(mFillScreen);
            mChipFit.setTextColor(mFillScreen ? GOLD : PARCHMENT);
        }
    }

    private void applyIntentPrefs(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra("keep_hud")) {
            setKeepHud(intent.getBooleanExtra("keep_hud", false));
        }
        if (intent.hasExtra("fill_screen")) {
            setFillScreen(intent.getBooleanExtra("fill_screen", false));
        }
    }

    private void applyPresentMode() {
        if (mNativeReady) {
            MesenNative.setPresentMode(mFillScreen ? 1 : 0);
        }
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

    private void movePauseFocus(boolean up) {
        List<View> views = pauseFocusables();
        if (views.isEmpty()) {
            return;
        }
        View focused = getCurrentFocus();
        int idx = views.indexOf(focused);
        if (idx < 0) {
            idx = 0;
        }
        int next = up ? idx - 1 : idx + 1;
        if (next < 0) {
            next = views.size() - 1;
        } else if (next >= views.size()) {
            next = 0;
        }
        focusPauseView(views.get(next));
    }

    private void movePauseFocusHorizontal(boolean left) {
        View focused = getCurrentFocus();
        if (mPauseScrim != null && focused == mPauseScrim.findViewById(R.id.btn_checkpoint_save)
                && !left) {
            focusPauseView(mPauseScrim.findViewById(R.id.btn_bug_capture));
        } else if (mPauseScrim != null && focused == mPauseScrim.findViewById(R.id.btn_bug_capture)
                && left) {
            focusPauseView(mPauseScrim.findViewById(R.id.btn_checkpoint_save));
        } else if (mRestoreReview != null && mRestoreReview.getVisibility() == View.VISIBLE
                && focused == mPauseScrim.findViewById(R.id.btn_restore_checkpoint) && !left) {
            focusPauseView(mPauseScrim.findViewById(R.id.btn_cancel_restore));
        } else if (mRestoreReview != null && mRestoreReview.getVisibility() == View.VISIBLE
                && focused == mPauseScrim.findViewById(R.id.btn_cancel_restore) && left) {
            focusPauseView(mPauseScrim.findViewById(R.id.btn_restore_checkpoint));
        } else if (focused == mChipHud && !left && mChipFit != null) {
            mChipFit.requestFocus();
        } else if (focused == mChipFit && left && mChipHud != null) {
            mChipHud.requestFocus();
        }
    }

    private List<View> pauseFocusables() {
        List<View> views = new ArrayList<>();
        if (mPauseScrim == null) {
            return views;
        }
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_continue));
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_checkpoint_save));
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_bug_capture));
        if (mBugCategories != null && mBugCategories.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < mBugCategories.getChildCount(); i++) {
                addFocusable(views, mBugCategories.getChildAt(i));
            }
        }
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_checkpoints));
        if (mCheckpointScroller != null && mCheckpointScroller.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < mCheckpointList.getChildCount(); i++) {
                addFocusable(views, mCheckpointList.getChildAt(i));
            }
        }
        if (mRestoreReview != null && mRestoreReview.getVisibility() == View.VISIBLE) {
            addFocusable(views, mPauseScrim.findViewById(R.id.btn_restore_checkpoint));
            addFocusable(views, mPauseScrim.findViewById(R.id.btn_cancel_restore));
        }
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_reset));
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_roms));
        if (mRomScroller != null && mRomScroller.getVisibility() == View.VISIBLE && mRomList != null) {
            for (int i = 0; i < mRomList.getChildCount(); i++) {
                addFocusable(views, mRomList.getChildAt(i));
            }
        }
        addFocusable(views, mChipHud);
        addFocusable(views, mChipFit);
        addFocusable(views, mPauseScrim.findViewById(R.id.btn_home));
        return views;
    }

    private static void addFocusable(List<View> views, View view) {
        if (view != null && view.getVisibility() == View.VISIBLE
                && view.isFocusable() && view.isEnabled()) {
            views.add(view);
        }
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
