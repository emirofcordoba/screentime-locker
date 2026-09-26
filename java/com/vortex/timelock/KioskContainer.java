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
 *       MENU, CAMERA, SEARCH and ESCAPE. System-wide HOME / OVERVIEW escapes are
 *       additionally denied out of process by device-owner lock task
 *       (LOCK_TASK_FEATURE_NONE) applied in {@link Engine}.
 *
 *       The POWER button is never touched: it is not (and cannot be) listed in
 *       {@link #BLOCKED_KEYS}, so the platform's own power handling always wins
 *       and pressing it turns the panel off for good. The window is hardened
 *       WITHOUT a forced screen-on (see {@link #hardenWindow}), so the kiosk
 *       never turns the panel back on behind the user's back.
 *
 *       The volume rocker is also not a navigation key: it is consumed only to
 *       be re-purposed as the brightness shortcut (Volume Up = brighter,
 *       Volume Down = dimmer) via {@link VolumeSink}.
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

    /**
     * Hardware keys swallowed by the container (hard nav signals).
     *
     * <p>KEYCODE_POWER is deliberately absent: Android does not deliver it to
     * apps and the kiosk must never sit between the user and the power button.
     * KEYCODE_VOLUME_UP/DOWN are absent too -- they are handled separately below
     * as the brightness shortcut rather than being blocked.
     */
    private static final int[] BLOCKED_KEYS = {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_ESCAPE,
    };

    /**
     * Receiver of hardware volume-rocker steps. Inside the kiosk the rocker never
     * changes media volume: the container consumes it and hands the direction to
     * the host, which maps it to screen brightness -- the first kiosk shortcut
     * (Volume Up = brighter, Volume Down = dimmer).
     */
    public interface VolumeSink {
        /** @param direction +1 for Volume Up, -1 for Volume Down. */
        void onVolumeStep(int direction);
    }

    private WakeSink wakeSink;
    private VolumeSink volumeSink;
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

    public void setVolumeSink(VolumeSink s) { this.volumeSink = s; }

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
     * Window-level hardening: kiosk window flags (show over keyguard, dismiss the
     * keyguard) plus the capture block so the lock screen can never be
     * screenshotted or exposed as a recents thumbnail. It intentionally does NOT
     * force the panel on: the power button is left entirely to the platform.
     */
    public void hardenWindow(Activity a, boolean blockCapture) {
        this.activity = a;
        try {
            // The power button is NOT intercepted. FLAG_TURN_SCREEN_ON /
            // setTurnScreenOn(true) are deliberately omitted: forcing the panel
            // back on every time this activity is shown or resumed is exactly what
            // made the phone appear to "turn itself back on" a moment after the
            // user switched it off with the power button (and then fall asleep
            // again). With them gone, a power-button press turns the screen off
            // and it stays off. Enforcement is unaffected: lock-task plus the
            // persistent HOME preference keep this activity on top, so the moment
            // the screen is switched back on the kiosk is already there.
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
            if (blockCapture) flags |= WindowManager.LayoutParams.FLAG_SECURE;
            a.getWindow().addFlags(flags);
            if (Build.VERSION.SDK_INT >= 27) {
                a.setShowWhenLocked(true);
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

        int code = event.getKeyCode();

        // Volume rocker -> brightness shortcut. Consumed on BOTH edges so the
        // platform's own media-volume handling never sees the key and never
        // fights (or doubles) our change. POWER is never in this path: the
        // container must not -- and cannot -- intercept it.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (down && volumeSink != null) {
                volumeSink.onVolumeStep(code == KeyEvent.KEYCODE_VOLUME_UP ? +1 : -1);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true; // muted kiosk: swallow, never change media volume
        }

        if (isBlocked(code)) {
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
