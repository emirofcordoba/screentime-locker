package com.vortex.timelock;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Native kiosk container.
 *
 * A self-contained, programmatic ViewGroup (no WebView, no layout XML) that owns
 * the entire lockdown surface of the lock screen. It blocks both classes of
 * navigation signal:
 *
 *   HARD navigation signals
 *       Physical / hardware keys are consumed here, at the top of the view tree,
 *       before the framework can act on them: BACK, HOME, APP_SWITCH (overview),
 *       MENU, the volume rocker, CAMERA, SEARCH and ESCAPE. System-wide HOME /
 *       OVERVIEW escapes are additionally denied out of process by device-owner
 *       lock task (LOCK_TASK_FEATURE_NONE) applied in {@link Engine}.
 *
 *   SOFT navigation signals
 *       The system bars (status bar + the navigation / gesture pill) are hidden
 *       and kept hidden. Immersive-sticky is re-applied on every window-focus
 *       change and on every system-UI visibility change, so swiping the
 *       navigation bar cannot summon it back.
 *
 * Every key-down and touch-down is also forwarded to a {@link WakeSink}, so the
 * display-sleep watchdog can treat any interaction as a user wake signal.
 */
public class KioskContainer extends FrameLayout {

    /** Receiver of "the user just did something" events. */
    public interface WakeSink {
        void onUserWakeSignal();
    }

    /** Hardware keys swallowed by the container (hard nav signals). */
    private static final int[] BLOCKED_KEYS = {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_ESCAPE,
    };

    private WakeSink wakeSink;
    private Activity activity;
    private long lastKeyLogMs = 0L;

    public KioskContainer(Context c) { super(c); init(); }
    public KioskContainer(Context c, AttributeSet a) { super(c, a); init(); }
    public KioskContainer(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        setOnSystemUiVisibilityChangeListener(v -> applyImmersive());
    }

    public void attachActivity(Activity a) { this.activity = a; }

    public void setWakeSink(WakeSink s) { this.wakeSink = s; }

    // ------------------------------------------------------------- soft lockdown

    /** Hide and keep hidden the soft navigation surface (immersive-sticky). */
    public void applyImmersive() {
        try {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) {}
    }

    /**
     * Window-level hardening: kiosk window flags (show over keyguard, turn the
     * panel on, keep the layout stable) plus the capture block so the lock
     * screen can never be screenshotted or exposed as a recents thumbnail.
     */
    public void hardenWindow(Activity a, boolean blockCapture) {
        this.activity = a;
        try {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (blockCapture) flags |= WindowManager.LayoutParams.FLAG_SECURE;
            a.getWindow().addFlags(flags);
            if (Build.VERSION.SDK_INT >= 27) {
                a.setShowWhenLocked(true);
                a.setTurnScreenOn(true);
            }
        } catch (Throwable ignored) {}
        applyImmersive();
    }

    // ------------------------------------------------------------- hard lockdown

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(null);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && wakeSink != null) wakeSink.onUserWakeSignal();

        if (isBlocked(event.getKeyCode())) {
            long now = System.currentTimeMillis();
            if (down && now - lastKeyLogMs > 1000L) {
                lastKeyLogMs = now;
                try {
                    Prefs.setLastEvent(getContext(), "kiosk-blocked-key:" + event.getKeyCode());
                } catch (Throwable ignored) {
                    // Pre-unlock (direct boot) the credential-encrypted store is not
                    // readable yet; a blocked key must never crash the kiosk panel.
                }
            }
            return true; // consumed before the framework sees it
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && wakeSink != null) {
            wakeSink.onUserWakeSignal();
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean isBlocked(int keyCode) {
        for (int k : BLOCKED_KEYS) if (k == keyCode) return true;
        return false;
    }
}
