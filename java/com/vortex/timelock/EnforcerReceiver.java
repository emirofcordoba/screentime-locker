package com.vortex.timelock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Alarm / watchdog receiver.
 *
 * AlarmManager is the ONLY timer we use — there are no Handler loops left
 * anywhere in the engine (build recipe 5). Every alarm is a PASSIVE, NON-WAKEUP
 * RTC trigger: it never holds a wake lock and can never keep the SoC awake. RTC
 * alarms whose time passed while the device slept are delivered the moment the
 * device next becomes interactive, so enforcement converges on wake without any
 * battery cost while idle. The alarms are:
 *   - EXACT at the moment the daily window ends  (guaranteed unlock)
 *   - EXACT at the moment the limit is exhausted (guaranteed lock)
 *   - EXACT at the wake-sleep deadline           (panel back off 10s after a wake)
 *   - INEXACT 15-minute watchdog                 (self-heal after crashes, TZ/clock change)
 *
 * Every fire simply tells the engine to re-evaluate (or hands the sleep-guard
 * deadline to {@link WakeGuard}). No work is done here so the receiver is
 * O(microseconds) and cannot be killed mid-transaction.
 */
public class EnforcerReceiver extends BroadcastReceiver {

    static final String TAG = "TL.Enforcer";

    static final String ACTION_TICK = "com.vortex.timelock.TICK";
    static final String ACTION_WINDOW_END = "com.vortex.timelock.WINDOW_END";
    static final String ACTION_WATCHDOG = "com.vortex.timelock.WATCHDOG";
    static final String ACTION_SLEEP_GUARD = "com.vortex.timelock.SLEEP_GUARD";

    static final int REQ_TICK = 41001;
    static final int REQ_END = 41002;
    static final int REQ_WD = 41003;
    static final int REQ_SLEEP = 41004;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "alarm: " + action);
        if (action == null) return;

        // A delivered alarm closes its coalescing window and lets the governor
        // drop targets whose time has passed. Without this the next re-arm for
        // the same action would be skipped as "redundant" against a target that
        // no longer exists in AlarmManager.
        PowerGovernor.noteAlarm(action);
        PowerGovernor.pruneStaleArms();

        if (ACTION_SLEEP_GUARD.equals(action)) {
            // Hand the elapsed wake-grace deadline back to the live guard.
            WakeGuard.fireFromAlarm(context);
            return;
        }

        // Acknowledge and hand off. If the service is dead the framework will
        // start it again (device owner + foreground service = allowed from here).
        if (ACTION_TICK.equals(action) || ACTION_WINDOW_END.equals(action) || ACTION_WATCHDOG.equals(action)) {
            Engine.reevaluate(context, "alarm:" + action);
        }
    }

    // ------------------------------------------------------------------ arming

    private static PendingIntent pi(Context c, int req, String action) {
        Intent i = new Intent(c, EnforcerReceiver.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    /**
     * Exact PASSIVE alarm at [atMs]. Uses AlarmManager.RTC (NOT RTC_WAKEUP) so it
     * never acquires a wake lock: while the device is asleep the alarm costs
     * nothing, and it is delivered as soon as the device is next interactive — at
     * which point allow-while-idle makes sure Doze/standby cannot push it further.
     */
    static void armExact(Context c, long atMs, String action, int req) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        if (atMs < now) atMs = now + 1000L;
        PendingIntent p = pi(c, req, action);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC, atMs, p);
            } else {
                am.setExact(AlarmManager.RTC, atMs, p);
            }
            Log.i(TAG, "armed " + action + " in " + ((atMs - now) / 1000) + "s");
        } catch (Throwable t) {
            Log.w(TAG, "armExact failed -> inexact fallback", t);
            try { am.setAndAllowWhileIdle(AlarmManager.RTC, atMs, p); }
            catch (Throwable t2) { Log.e(TAG, "arm fallback failed", t2); }
        }
    }

    static void armWindowEnd(Context c, long atMs) {
        armExact(c, atMs, ACTION_WINDOW_END, REQ_END);
    }

    static void armLimit(Context c, long atMs) {
        armExact(c, atMs, ACTION_TICK, REQ_TICK);
    }

    /** Arm the passive wake-sleep deadline (10s after the last wake signal). */
    static void armSleepGuard(Context c, long atMs) {
        armExact(c, atMs, ACTION_SLEEP_GUARD, REQ_SLEEP);
    }

    /** 15-minute self-heal watchdog. Inexact + passive = kiosk-grade but zero wake locks. */
    static void armWatchdog(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + 15L * 60_000L;
        PendingIntent p = pi(c, REQ_WD, ACTION_WATCHDOG);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, p);
            } else {
                am.set(AlarmManager.RTC, at, p);
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchdog arm failed", t);
        }
    }

    // ---------------------------------------------------------------- cancelling

    static void cancelLimit(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c, REQ_TICK, ACTION_TICK));
    }

    static void cancelSleepGuard(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c, REQ_SLEEP, ACTION_SLEEP_GUARD));
    }

    static void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c, REQ_TICK, ACTION_TICK));
        am.cancel(pi(c, REQ_END, ACTION_WINDOW_END));
        am.cancel(pi(c, REQ_WD, ACTION_WATCHDOG));
        am.cancel(pi(c, REQ_SLEEP, ACTION_SLEEP_GUARD));
    }
}
