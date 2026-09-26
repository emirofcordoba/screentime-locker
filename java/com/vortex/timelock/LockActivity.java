package com.vortex.timelock;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The kiosk lock screen. Pure-code UI (no layout XML) so the build stays a
 * single fast pass.
 *
 * Escape hatches killed:
 *   - lock-task kiosk (LOCK_TASK_FEATURE_NONE blocks home/overview/notifications)
 *   - back button consumed, immersive sticky mode hides the navigation bar
 *   - persistent-preferred HOME points here while locked, so pressing Home
 *     re-enters it
 *   - the window-end alarm is the only way out
 *
 * Safety: if we are NOT the real Device Owner we finish immediately and never
 * take over the screen. That is what stops this activity from ever fighting the
 * launcher (the old blinking / random-app chaos).
 */
public class LockActivity extends Activity implements HardwareTick.Sink {

    static final String TAG = "TL.Lock";

    /**
     * Boot-time PRE-UNLOCK launch marker. When set, this activity runs in
     * DIRECT-BOOT mode: it renders the kiosk from the device-protected mirror
     * ({@link BootState}) ONLY, because no credential-encrypted storage
     * ({@link Prefs}, {@link UsageStore}, {@link KioskLogStore}) is readable
     * before the user unlocks. It never runs a 1 Hz cadence and never touches a
     * wakelock, so an ongoing lock is on screen from the instant the framework
     * is up with literally zero idle cost. On USER_UNLOCKED the full engine takes
     * over (BootReceiver -> Engine.reevaluate -> LockActivity.launch), which
     * re-delivers this activity as the normal, live lock screen.
     */
    static final String EXTRA_PRE_UNLOCK = "tl_pre_unlock";

    // Only two colour tokens survive in the activity itself: the window
    // background and the panel fill. Every other colour, and every string the
    // lock screen used to lay out by hand, now belongs to the dashboard module.
    private static final int BG   = Color.parseColor("#070C14");
    private static final int CARD = Color.parseColor("#131C2B");

    private static WeakReference<LockActivity> sInstance = new WeakReference<>(null);

    private boolean lockTaskEntered = false;

    /** True while running the credential-encrypted-storage-free direct-boot path. */
    private boolean preUnlockMode = false;

    // ---- hardened kiosk container + dashboard projection (this revision) ----
    private KioskContainer container;
    private KioskDashboard dashboard;
    private WakeGuard wakeGuard;

    // ---------------------------------------------------------------- lifecycle

    static void launch(Context c) {
        // Never hijack the screen unless we are genuinely enforcing as owner.
        if (!Engine.isDeviceOwner(c)) {
            Log.i(TAG, "launch skipped: not device owner");
            return;
        }
        try {
            Intent i = new Intent(c, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
        }
    }

    /**
     * DIRECT-BOOT entry point: called by {@link BootReceiver} before the user
     * unlocks, when the device-protected mirror says an activation is in force
     * and a lock is ongoing. Owner-gated; makes no CE read and needs no runtime
     * permission.
     */
    static void launchPreUnlock(Context c) {
        if (!Engine.isDeviceOwner(c)) {
            Log.i(TAG, "pre-unlock launch skipped: not device owner");
            return;
        }
        try {
            Intent i = new Intent(c, LockActivity.class);
            i.putExtra(EXTRA_PRE_UNLOCK, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(i);
            Log.i(TAG, "pre-unlock kiosk launched");
        } catch (Throwable t) {
            Log.e(TAG, "pre-unlock launch failed", t);
        }
    }

    static void dismiss(Context c) {
        // Only close a live instance. Starting the activity just to have it
        // finish would flash the dark lock screen for a frame.
        LockActivity a = sInstance.get();
        if (a != null) a.runOnUiThread(a::finishSafely);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new WeakReference<>(this);

        preUnlockMode = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_PRE_UNLOCK, false);

        long now = System.currentTimeMillis();
        if (!Engine.isDeviceOwner(this)) {
            Log.i(TAG, "not owner -> finishing");
            super.finish();
            return;
        }

        if (preUnlockMode) {
            // DEVICE-PROTECTED ONLY. Never call Engine.shouldBeLocked / Prefs /
            // UsageStore / KioskLogStore below the unlock: those live in CE
            // storage and would throw (the historical boot crash-loop).
            if (!BootState.activated(this) || !BootState.locked(this)) {
                super.finish();
                return;
            }
            buildPreUnlockUi();
            container.attachActivity(this);
            container.hardenWindow(this, true);
            container.requestFocus();
            try { getWindow().setStatusBarColor(Color.BLACK); } catch (Throwable ignored) {}
            // No WakeGuard (needs Prefs.optScreenOff), no HardwareTick: a static
            // surface with zero timers is the whole point of the direct-boot path.
            enforceImmersive();
            enterLockTaskIfPossible();
            return;
        }

        if (!Engine.shouldBeLocked(this, now)) {
            super.finish();
            return;
        }

        // Wake-triggered display sleep: any wake signal restarts a strict
        // 10-second deadline after which the panel is put back to sleep.
        wakeGuard = new WakeGuard(this, this::turnScreenOffGracefully);

        buildUi();

        // The container hardens the window (over lockscreen, captures blocked at
        // the native layer). No FLAG_KEEP_SCREEN_ON: the lock can last hours and
        // holding the panel awake would itself drain battery.
        container.hardenWindow(this, true);
        container.requestFocus();
        if (Prefs.optScreenOff(this)) wakeGuard.start();
        try { getWindow().setStatusBarColor(Color.BLACK); } catch (Throwable ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) setIntent(intent);
        if (!Engine.isDeviceOwner(this)) { finishSafely(); return; }

        boolean pre = intent != null && intent.getBooleanExtra(EXTRA_PRE_UNLOCK, false);
        if (pre) {
            preUnlockMode = true;
            if (!BootState.activated(this) || !BootState.locked(this)) finishSafely();
            return;
        }

        // FULL-ENGINE HANDOVER: a plain (post-unlock) launch has arrived while we
        // were showing the direct-boot panel, so CE storage is now readable and
        // the live lock screen must replace the static projection.
        if (preUnlockMode) {
            preUnlockMode = false;
            if (wakeGuard == null) wakeGuard = new WakeGuard(this, this::turnScreenOffGracefully);
            buildUi();
            container.hardenWindow(this, true);
            container.requestFocus();
            enforceImmersive();
            enterLockTaskIfPossible();
            HardwareTick.subscribe(this);
            Log.i(TAG, "pre-unlock -> full engine handover");
        }

        if (!Engine.shouldBeLocked(this, System.currentTimeMillis())) {
            finishSafely();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sInstance = new WeakReference<>(this);
        if (!Engine.isDeviceOwner(this)) { finishSafely(); return; }

        if (preUnlockMode) {
            // Direct-boot path: device-protected only, no CE, no cadence.
            if (!BootState.activated(this) || !BootState.locked(this)) { finishSafely(); return; }
            enforceImmersive();
            enterLockTaskIfPossible();
            if (container != null) {
                container.applyImmersive();
                container.requestFocus();
            }
            return;
        }

        enforceImmersive();
        enterLockTaskIfPossible();
        Engine.rollover(this, System.currentTimeMillis());

        if (!Engine.shouldBeLocked(this, System.currentTimeMillis())) {
            exitLockTaskIfNeeded();
            finishSafely();
            return;
        }

        // Re-arm the display-sleep watchdog on every (re)entry and register the
        // resume itself as a wake signal.
        if (wakeGuard != null) {
            if (Prefs.optScreenOff(this)) {
                wakeGuard.start();
                wakeGuard.signal("resume");
            } else {
                wakeGuard.stop();
            }
        }
        if (container != null) {
            container.applyImmersive();
            container.requestFocus();
        }

        // Join the process's SINGLE authoritative 1 Hz cadence. subscribe()
        // renders immediately, so the projection is populated from the real lock
        // arithmetic on this frame rather than left showing a placeholder until
        // the first second elapses.
        HardwareTick.subscribe(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leave the shared cadence while we are not visible: with no other sink
        // attached the handler cancels outright, so a paused lock screen holds no
        // timer at all. The WakeGuard receiver stays registered on purpose: a wake
        // signal while paused still (re)arms the 10-second sleep deadline.
        HardwareTick.unsubscribe(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Re-assert the kiosk if we are still supposed to be locked. We do NOT
        // relaunch the activity (that used to fight the launcher); lock-task plus
        // the persistent HOME preference bring us back naturally.
        if (preUnlockMode) {
            // Device-protected decision only.
            if (BootState.activated(this) && BootState.locked(this)) enforceImmersive();
            return;
        }
        if (Engine.isDeviceOwner(this)
                && Engine.shouldBeLocked(this, System.currentTimeMillis())) {
            enforceImmersive();
        }
    }

    @Override
    public void onBackPressed() {
        // swallowed on purpose
    }

    @Override
    public void onUserLeaveHint() {
        // swallowed on purpose
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Any time the kiosk regains focus (wake, notification pull attempt,
        // task switch) re-hide the system bars immediately.
        if (hasFocus) enforceImmersive();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Any key press is a user wake signal: restart the 10-second sleep clock.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && wakeGuard != null) {
            wakeGuard.signal("key:" + event.getKeyCode());
        }
        // Swallow the navigation keys we can intercept. HOME / APP_SWITCH are
        // additionally blocked system-wide by lock-task (LOCK_TASK_FEATURE_NONE),
        // which is the authoritative kiosk control.
        switch (event.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_BACK:
            case android.view.KeyEvent.KEYCODE_HOME:
            case android.view.KeyEvent.KEYCODE_APP_SWITCH:
            case android.view.KeyEvent.KEYCODE_MENU:
                return true; // consumed
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    protected void onDestroy() {
        if (sInstance.get() == this) sInstance = new WeakReference<>(null);
        HardwareTick.unsubscribe(this);
        if (wakeGuard != null) wakeGuard.stop();
        exitLockTaskIfNeeded();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- kiosk

    private void enterLockTaskIfPossible() {
        if (lockTaskEntered) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                lockTaskEntered = true;
                return;
            }
            if (Engine.isDeviceOwner(this)) {
                startLockTask();
                lockTaskEntered = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "startLockTask", t);
        }
    }

    private void exitLockTaskIfNeeded() {
        if (!lockTaskEntered) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopLockTask", t);
        }
        lockTaskEntered = false;
    }

    private void enforceImmersive() {
        if (container != null) { container.applyImmersive(); return; }
        try {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) {}
    }

    /** Bubbled up from the container when the user touches or wakes the panel. */
    private void onUserWakeSignal() {
        if (wakeGuard != null && Prefs.optScreenOff(this)) wakeGuard.signal("user-signal");
    }

    /** Turn the panel off the graceful way: lockNow() as owner (screen off + lock). */
    private void turnScreenOffGracefully() {
        try {
            DevicePolicyManager d = Engine.dpm(this);
            if (d != null && Engine.isDeviceOwner(this)) {
                d.lockNow();
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "lockNow", t);
        }
        // Fallback for the non-owner case (should not normally be reached).
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                java.lang.reflect.Method m =
                        android.os.PowerManager.class.getMethod("goToSleep", long.class);
                m.invoke(pm, System.currentTimeMillis());
            }
        } catch (Throwable ignored) {}
    }

    private void finishSafely() {
        try { finishAndRemoveTask(); }
        catch (Throwable t) { finish(); }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        // Native kiosk container: consumes hard/soft navigation signals and
        // re-asserts immersive mode from the view hierarchy itself.
        container = new KioskContainer(this);
        container.setBackgroundColor(BG);
        container.setWakeSink(this::onUserWakeSignal);

        // The dashboard replaces the old stack of hand-placed TextViews (icon,
        // title, monospaced terminal matrix, progress bar, percent line, used
        // line, divider, unlock line, footer). All of that is now one module
        // with one entry point, so this method builds a frame and stops.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), dp(18), dp(16), dp(18));
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The dashboard is taller than the old card. A ScrollView keeps a short
        // screen from clipping the activity card; it is configured to look and
        // behave static (no bar, no overscroll glow, no fill animation), so a
        // full-height panel renders exactly like the design mock-up.
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 26, Color.parseColor("#2C3B52")));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(card);

        // ---- the entire projection: one static view, one bind() per tick ----
        dashboard = new KioskDashboard(this);
        card.addView(dashboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(container);
    }

    // ======================================================================
    // DIRECT-BOOT (PRE-UNLOCK) PROJECTION
    // ======================================================================

    /**
     * Minimal, timer-free kiosk projection for the direct-boot path. Built
     * purely from device-protected state: the lock deadline comes from
     * {@link BootState#lockUntil}, and the wall clock from {@link TimeFmt} (system
     * settings, readable pre-unlock). No CE read, no tick, no wakelock, no
     * polling loop -- so the lock costs literally nothing while it waits.
     */
    private void buildPreUnlockUi() {
        container = new KioskContainer(this);
        container.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Screen time lock active");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        long until = BootState.lockUntil(this);
        android.widget.TextView sub = new android.widget.TextView(this);
        sub.setText(until > 0L ? "Unlocks " + formatClock(this, until) : "Locked");
        sub.setTextColor(Color.parseColor("#9FB0C8"));
        sub.setTextSize(15f);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(sub, lp);

        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(container);
    }

    // ======================================================================
    // HardwareTick.Sink -- the single authoritative 1 Hz cadence
    // ======================================================================

    /** Live while this lock screen is actually on screen. */
    @Override public boolean active() {
        return !isFinishing() && !isDestroyed();
    }

    /**
     * One tick, from the SHARED instant. {@code nowMs} is the same value the
     * status-bar countdown re-derived from on this tick and the same value every
     * other surface rendered with, so the projection, the usage bar and the
     * notification cannot disagree or slide out of step.
     */
    @Override public void onTick(long nowMs) {
        render(nowMs);
    }

    /**
     * Project the current lock arithmetic.
     *
     * Replaces the old {@code tickUi()}, which re-armed itself with
     * {@code handler.postDelayed(..., 1000L)} as its LAST statement -- so it
     * drifted by the duration of its own work, and any throwable earlier in the
     * pass killed the loop for good and froze the read-out. The cadence now lives
     * in {@link HardwareTick}, which re-arms BEFORE calling this method and
     * isolates every sink, so this surface can glitch at most one frame instead
     * of freezing forever.
     */
    private void render(long now) {
        // The direct-boot panel is deliberately static and never subscribes to the
        // cadence, but guard anyway so no caller can knock it into a CE read.
        if (preUnlockMode) return;
        long until = Engine.lockUntilMs(this, now);
        long left = until - now;

        if (left <= 0) {
            // window rolled: unlock
            Prefs.setLocked(this, false);
            Prefs.setLockUntil(this, 0L);
            Engine.applyLockState(this, false);
            Engine.bootstrap(this);
            exitLockTaskIfNeeded();
            finishSafely();
            return;
        }

        // ---- display-sleep watchdog, in seconds (-1 = off) ----
        int guard = -1;
        if (wakeGuard != null && Prefs.optScreenOff(this) && wakeGuard.isArmed()) {
            guard = (int) ((wakeGuard.remainingMs() + 999L) / 1000L);
        }

        // ---- the whole projection, one call ----
        // KioskLogStore reads the durable state once and freezes it into a
        // snapshot; the dashboard does nothing but paint that value. bind()
        // diffs every string before writing it, so a tick that changes nothing
        // invalidates nothing -- the previous revision set nine TextViews and a
        // ProgressBar unconditionally, every second, forever.
        if (dashboard != null) {
            dashboard.bind(KioskLogStore.capture(this, now, guard));
        }
        // No self-rescheduling here on purpose: the next tick already exists.
    }

    /**
     * "Wed 10:35 PM" / "Wed 22:35" — the status-bar countdown formats through
     * here too, so the lock screen and the shade can never disagree about the
     * unlock instant or its hour form. The 12/24-hour choice follows the user's
     * own system setting (see {@link TimeFmt}).
     */
    static String formatClock(Context c, long ms) {
        String day = new SimpleDateFormat("EEE", Locale.US).format(new Date(ms));
        return day + " " + TimeFmt.clock(c, ms);
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
