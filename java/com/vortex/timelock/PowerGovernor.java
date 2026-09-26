package com.vortex.timelock;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PowerGovernor -- the idle-power brain of the engine.
 *
 * Everything in this class exists to make one guarantee: while the device is
 * NOT interactive the engine performs exactly zero work and posts exactly zero
 * notifications, and while it IS interactive the engine never pays more than one
 * binder round-trip per real state change.
 *
 * The four mechanisms:
 *
 *   1. CACHED INTERACTIVITY. The screen state is read from PowerManager ONCE at
 *      attach() and then maintained purely from ACTION_SCREEN_ON/OFF broadcasts
 *      (via setInteractive). No code in the hot path ever calls
 *      PowerManager.isInteractive() again -- that call is a binder round-trip to
 *      system_server, and the old engine paid it on every recheck / alarm.
 *
 *   2. SUSPENDABLE REGISTRY. Any component that owns a timer (the 1 Hz live
 *      countdown) registers here. When the device goes non-interactive the
 *      governor flips its cached flag FIRST and then calls onIdle() on every
 *      subscriber, so a subscriber can never "miss" the transition and can never
 *      re-arm a timer from inside its own callback (each one re-checks the flag).
 *      On wake the idle session is frozen BEFORE onWake() is dispatched, so the
 *      session accounting is already closed when subscribers start posting again
 *      (which is why a wake-time post is classified as "settling", not "late").
 *
 *   3. ALARM RE-ARM COALESCING. armPassiveExact() keeps the last armed target
 *      per action and drops a re-arm that lands within REARM_EPSILON_MS of it.
 *      The engine re-evaluates on every screen event and every alarm, and each
 *      re-evaluation used to re-issue the same passive RTC trigger; an active
 *      screen therefore generated a stream of redundant setExactAndAllowWhileIdle
 *      calls. Now a re-arm only happens when the target actually moved.
 *
 *   4. IDLE INSTRUMENTATION. The governor counts what happened during every idle
 *      session. A correct build reports idleLatePosts == 0 and (with the ticker
 *      parked) idleTicks == 0 / idleLimitTicks <= 1. If a future change starts
 *      leaking work into the idle window this counter is what catches it.
 *
 * Zero wake locks are taken anywhere: the class only ever reads state and calls
 * AlarmManager with the passive RTC (non-wakeup) form owned by EnforcerReceiver.
 *
 * All statics are safe to call while detached (broadcast receivers may fire
 * after the service died); in that state they simply count and do nothing else.
 */
final class PowerGovernor {

    static final String TAG = "TL.Power";

    /** A component that owns timers and can park itself when the device sleeps. */
    interface Suspendable {
        /** Device became non-interactive: cancel every timer, arm nothing. */
        void onIdle();
        /** Device became interactive again: re-post/refresh and re-arm. */
        void onWake();
        /** Optional: memory pressure signal from the service. */
        default void onMemoryTrim(int level) { }
    }

    /** Re-arming the same trigger inside this window is a no-op (ms). */
    private static final long REARM_EPSILON_MS = 750L;
    /** Posts this soon after an idle session begins are "settling", not "late". */
    private static final long SETTLE_MS = 3000L;

    private static final CopyOnWriteArrayList<Suspendable> SUBS = new CopyOnWriteArrayList<>();
    private static final HashMap<String, Long> ARMED = new HashMap<>();

    private static volatile boolean sAttached = false;
    private static volatile boolean sInteractive = true;
    private static volatile boolean sIdle = false;

    // --- idle session accounting -------------------------------------------
    private static long sIdleSince = 0L;      // 0 while interactive
    private static long sIdleMsTotal = 0L;    // closed idle sessions
    private static int sIdleSessions = 0;

    // --- counters ----------------------------------------------------------
    private static long sRechecks = 0L;
    private static long sPosts = 0L;
    private static long sAlarms = 0L;
    private static long sArmsIssued = 0L;
    private static long sArmsSkipped = 0L;

    private static long sIdleRechecks = 0L;
    private static long sIdlePostsSettle = 0L;
    private static long sIdlePostsLate = 0L;
    private static long sIdleAlarms = 0L;
    private static long sIdleLimitTicks = 0L;
    private static long sIdleTicks = 0L;

    private static String sLastIdleAlarm = null;
    private static String sLastIdleEvent = null;

    private PowerGovernor() { }

    // ======================================================================
    // Lifecycle / interactivity
    // ======================================================================

    /**
     * Bind the governor to a live service instance. Seeds the cached
     * interactivity flag with ONE PowerManager read, after which the flag is
     * maintained entirely from screen broadcasts.
     */
    static void attach(Context c) {
        // Conservative default: an unavailable/unreadable PowerManager is treated
        // as NON-interactive, so the governor never counts/keeps an on-session
        // alive on an assumption (matching TimeLockService's session gate).
        boolean on = false;
        try {
            PowerManager pm = c == null ? null
                    : (PowerManager) c.getApplicationContext()
                            .getSystemService(Context.POWER_SERVICE);
            on = pm != null && pm.isInteractive();
        } catch (Throwable t) {
            Log.w(TAG, "attach: assuming not interactive", t);
        }
        synchronized (PowerGovernor.class) {
            sInteractive = on;
            sIdle = !on;
            sIdleSince = sIdle ? System.currentTimeMillis() : 0L;
            sAttached = true;
        }
        Log.i(TAG, "attach interactive=" + on);
    }

    /** Detach: drop subscribers and cached alarm targets. Counters are kept. */
    static void detach() {
        synchronized (PowerGovernor.class) {
            sAttached = false;
            sIdle = false;
            sIdleSince = 0L;
            SUBS.clear();
            ARMED.clear();
        }
        Log.i(TAG, "detach");
    }

    /** The CACHED interactivity flag. Never hits PowerManager. */
    static boolean interactive() { return sInteractive; }

    static boolean isIdle() { return sIdle; }

    /**
     * Update the cached interactivity flag from a screen broadcast.
     *
     * The flag is flipped BEFORE any subscriber callback runs, and the idle
     * session is frozen BEFORE onWake() is dispatched. Both orderings are
     * deliberate: they make the delegation deterministic and make the idle
     * instrumentation trustworthy.
     */
    static void setInteractive(boolean on) {
        boolean changed;
        synchronized (PowerGovernor.class) {
            changed = (on != sInteractive);
            if (!changed) return;
            sInteractive = on;
            if (!on) {
                sIdle = true;
                sIdleSince = System.currentTimeMillis();
                sIdleSessions++;
                sLastIdleEvent = "idle";
            }
        }
        if (on) {
            freezeIdle("wake");            // close the session before anyone posts
            sLastIdleEvent = "wake";
            dispatchWake();
        } else {
            dispatchIdle();
        }
        Log.i(TAG, "interactive=" + on);
    }

    /** Close the open idle session and bank its duration. */
    private static void freezeIdle(String reason) {
        synchronized (PowerGovernor.class) {
            if (!sIdle) return;
            long now = System.currentTimeMillis();
            if (sIdleSince > 0L && now > sIdleSince) sIdleMsTotal += (now - sIdleSince);
            sIdleSince = 0L;
            sIdle = false;
        }
    }

    // ======================================================================
    // Subscriber registry
    // ======================================================================

    static void register(Suspendable s) {
        if (s == null || !sAttached) return;
        if (!SUBS.contains(s)) {
            SUBS.add(s);
            Log.i(TAG, "register " + s.getClass().getSimpleName() + " subs=" + SUBS.size());
        }
    }

    static void unregister(Suspendable s) {
        if (s == null) return;
        if (SUBS.remove(s)) Log.i(TAG, "unregister subs=" + SUBS.size());
    }

    private static void dispatchIdle() {
        for (Suspendable s : SUBS) {
            try { s.onIdle(); } catch (Throwable t) { Log.w(TAG, "onIdle", t); }
        }
    }

    private static void dispatchWake() {
        for (Suspendable s : SUBS) {
            try { s.onWake(); } catch (Throwable t) { Log.w(TAG, "onWake", t); }
        }
    }

    // ======================================================================
    // Passive alarm coalescing
    // ======================================================================

    /**
     * Arm a PASSIVE (non-wakeup) exact RTC trigger through the governor's
     * coalescing layer. Identical/adjacent targets for the same action are
     * skipped: the engine re-evaluates on every screen event, and without this
     * an active screen produced a continuous stream of redundant
     * setExactAndAllowWhileIdle() binder calls for a target that had not moved.
     */
    static void armPassiveExact(Context c, long atMs, String action, int req) {
        if (c == null || action == null) return;
        long now = System.currentTimeMillis();
        synchronized (PowerGovernor.class) {
            sArmsIssued++;
            Long prev = ARMED.get(action);
            if (prev != null && prev > now && Math.abs(prev - atMs) <= REARM_EPSILON_MS) {
                sArmsSkipped++;
                return;
            }
            ARMED.put(action, atMs);
        }
        EnforcerReceiver.armExact(c, atMs, action, req);
    }

    /** An action was delivered / cancelled: drop its coalescing target. */
    static void forget(String action) {
        if (action == null) return;
        synchronized (PowerGovernor.class) {
            if (ARMED.remove(action) != null) sLastIdleEvent = null == sLastIdleEvent ? sLastIdleEvent : sLastIdleEvent;
        }
    }

    static void forgetAll() {
        synchronized (PowerGovernor.class) { ARMED.clear(); }
    }

    /**
     * Drop coalescing targets whose time has already passed. Conservative: only
     * entries that are unambiguously stale are removed, so a still-pending alarm
     * can never be forgotten and then not re-armed.
     */
    static void pruneStaleArms() {
        long now = System.currentTimeMillis();
        int removed = 0;
        synchronized (PowerGovernor.class) {
            Iterator<Map.Entry<String, Long>> it = ARMED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                Long v = e.getValue();
                if (v == null || v <= now) { it.remove(); removed++; }
            }
        }
        if (removed > 0) Log.i(TAG, "prune stale arms removed=" + removed);
    }

    // ======================================================================
    // Instrumentation
    // ======================================================================

    static void noteRecheck(String reason) {
        synchronized (PowerGovernor.class) {
            sRechecks++;
            if (sIdle) {
                sIdleRechecks++;
                sLastIdleEvent = "recheck:" + reason;
            }
        }
    }

    /** One notification post reached the NotificationManager. */
    static void notePost(String what) {
        synchronized (PowerGovernor.class) {
            sPosts++;
            if (sIdle) {
                if (sIdleSince > 0L && System.currentTimeMillis() - sIdleSince <= SETTLE_MS) {
                    sIdlePostsSettle++;
                } else {
                    sIdlePostsLate++;
                    sLastIdleEvent = "late-post:" + what;
                }
            }
        }
    }

    /** One alarm was delivered to a receiver. */
    static void noteAlarm(String action) {
        synchronized (PowerGovernor.class) {
            sAlarms++;
            if (sIdle) {
                sIdleAlarms++;
                sLastIdleAlarm = action;
                sLastIdleEvent = "alarm:" + action;
                if (EnforcerReceiver.ACTION_TICK.equals(action)) sIdleLimitTicks++;
            }
        }
    }

    /**
     * One live-countdown tick fired. Called BEFORE the ticker's idle check, so a
     * tick that raced with the screen going off is still recorded.
     */
    static void noteTick() {
        synchronized (PowerGovernor.class) {
            if (sIdle) {
                sIdleTicks++;
                sLastIdleEvent = "tick-while-idle";
            }
        }
    }

    static void onTrimMemory(int level) {
        pruneStaleArms();
        for (Suspendable s : SUBS) {
            try { s.onMemoryTrim(level); } catch (Throwable t) { Log.w(TAG, "onMemoryTrim", t); }
        }
        Log.i(TAG, "trim level=" + level + " " + report());
    }

    /**
     * True when the idle window was clean: no alarm-triggered limit evaluation
     * during idle (one is tolerated, for the tick armed at the exact limit
     * instant racing a screen-off) and zero late posts.
     */
    static boolean idleVerdictOk() { return sIdleLimitTicks <= 1L && sIdlePostsLate == 0L; }

    static boolean batteryExempt(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) c.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static String report() {
        synchronized (PowerGovernor.class) {
            long idleMs = sIdleMsTotal;
            if (sIdle && sIdleSince > 0L) idleMs += (System.currentTimeMillis() - sIdleSince);
            return "gov[attached=" + sAttached
                    + " interactive=" + sInteractive
                    + " idle=" + sIdle
                    + " subs=" + SUBS.size()
                    + " arms(issued=" + sArmsIssued + ",skipped=" + sArmsSkipped
                    + ",pending=" + ARMED.size() + ")"
                    + " rechecks=" + sRechecks
                    + " posts=" + sPosts
                    + " alarms=" + sAlarms
                    + " idleSessions=" + sIdleSessions
                    + " idleMs=" + idleMs
                    + " idle(rechecks=" + sIdleRechecks
                    + ",settle=" + sIdlePostsSettle
                    + ",late=" + sIdlePostsLate
                    + ",alarms=" + sIdleAlarms
                    + ",limitTicks=" + sIdleLimitTicks
                    + ",ticks=" + sIdleTicks + ")"
                    + " lastIdleAlarm=" + sLastIdleAlarm
                    + " lastIdleEvent=" + sLastIdleEvent
                    + " verdict=" + (idleVerdictOk() ? "clean" : "LEAK") + "]";
        }
    }
}
