package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Wake-triggered display-sleep watchdog.
 *
 * ANY user wake signal restarts a single, strict deadline exactly
 * {@link #SLEEP_AFTER_WAKE_MS} in the future; when that deadline elapses the
 * panel is put back to sleep. Wake signals are:
 *
 *   - the screen lighting up          (ACTION_SCREEN_ON)
 *   - the user actually reaching it   (ACTION_USER_PRESENT)
 *   - a raw key-down or touch-down    (routed from {@link KioskContainer})
 *
 * PLATFORM TIMER, NOT A HANDLER LOOP (build recipe 5): the deadline is now a
 * single, NON-wakeup AlarmManager timer owned by {@link EnforcerReceiver}. The
 * alarm is RTC (not RTC_WAKEUP), so it can never hold the SoC awake and never
 * costs a wake lock — it fires only while the device is already interactive,
 * which is exactly when we need to turn the panel back off. N wake signals cost
 * O(1): each simply re-arms the same alarm. No Handler, no Runnable loop, no
 * polling, and nothing at all to freeze while the screen is already off.
 *
 * The receiver delivers the fired alarm into this class through the static
 * {@link #fireFromAlarm(Context)} bridge, so the same live instance that owns
 * the {@link Sink} services the callback without the framework having to rebind
 * or restart the hosting activity.
 */
final class WakeGuard {

    static final String TAG = "TL.WakeGuard";

    /** The enforced grace window: the panel sleeps this long after the last wake. */
    static final long SLEEP_AFTER_WAKE_MS = 10_000L;

    interface Sink {
        /** Called when the grace window elapses after a wake. */
        void onSleepRequested();
    }

    /**
     * The single live guard. EnforcerReceiver fires alarms in the app process,
     * so this bridge routes the platform callback to the instance that armed it.
     */
    private static volatile WakeGuard sLive;

    private final Context ctx;
    private final Sink sink;

    private BroadcastReceiver rx;
    private boolean started = false;
    private long deadlineMs = 0L;

    WakeGuard(Context c, Sink sink) {
        this.ctx = c.getApplicationContext();
        this.sink = sink;
    }

    long deadlineMs() { return deadlineMs; }

    long remainingMs() {
        long r = deadlineMs - System.currentTimeMillis();
        return r > 0 ? r : 0L;
    }

    boolean isArmed() { return started && deadlineMs > 0L; }

    /** Begin watching for wake signals (idempotent). Arms the deadline immediately. */
    void start() {
        if (started) return;
        started = true;
        sLive = this;
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String a = intent == null ? "?" : intent.getAction();
                Log.i(TAG, "wake signal: " + a);
                signal("screen:" + a);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { ctx.registerReceiver(rx, f); }
        catch (Throwable t) { Log.w(TAG, "registerReceiver", t); }
        signal("start");
    }

    /** Stop watching and cancel the platform deadline. */
    void stop() {
        started = false;
        if (sLive == this) sLive = null;
        if (rx != null) {
            try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) {}
            rx = null;
        }
        EnforcerReceiver.cancelSleepGuard(ctx);
        deadlineMs = 0L;
    }

    /**
     * Register a user wake/activity signal and (re)arm the strict 10-second
     * platform deadline. Cheap and idempotent: safe to call on every key/touch
     * down. No Handler is created or posted.
     */
    void signal(String why) {
        if (!started) return;
        deadlineMs = System.currentTimeMillis() + SLEEP_AFTER_WAKE_MS;
        EnforcerReceiver.armSleepGuard(ctx, deadlineMs);
    }

    /**
     * Platform entry point: {@link EnforcerReceiver} calls this when the
     * non-wakeup sleep-guard alarm fires. Routes to the live guard.
     */
    static void fireFromAlarm(Context c) {
        WakeGuard w = sLive;
        if (w == null) return;
        w.fire();
    }

    private void fire() {
        deadlineMs = 0L;
        Log.i(TAG, "wake grace elapsed -> display sleep (" + (SLEEP_AFTER_WAKE_MS / 1000) + "s)");
        Prefs.setLastEvent(ctx, "wake-sleep:" + (SLEEP_AFTER_WAKE_MS / 1000) + "s");
        try { sink.onSleepRequested(); }
        catch (Throwable t) { Log.w(TAG, "sleep sink", t); }
    }
}
