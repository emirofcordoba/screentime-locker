package com.vortex.timelock;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * LiveCountdown -- the low-level background optimisation model for the live
 * status-bar countdown.
 *
 * THE GOAL. Keep a genuinely live, second-accurate countdown of the remaining
 * screen-time budget in the status bar while the engine's own background cost
 * stays at its floor: no wake locks, no scheduled wakeups, no per-second binder
 * traffic, and provably zero timer work while the device is not interactive.
 *
 * THE THREE MECHANISMS.
 *
 *   1. THE PER-SECOND PIXELS COME FROM THE OS, NOT FROM US.
 *      The notification is posted with setUsesChronometer(true) +
 *      setChronometerCountDown(true) + setWhen(deadline). SystemUI renders that
 *      with a native Chronometer inside the system UI process, which recomputes
 *      the remaining time on its own second-aligned redraw clock. The app never
 *      posts a per-second notification, is never woken FROM SLEEP for a repaint,
 *      and holds no wake lock, so "the status bar updates every single second"
 *      costs zero binder traffic and zero wakeups. This is the platform's
 *      replacement for a 1 Hz wakelock/Handler loop, and it is accurate by
 *      construction -- the displayed value is derived from the same wall-clock
 *      deadline the engine enforces against, every second, so there is no
 *      app-side cadence that could accumulate drift. The separate app-side 1 Hz
 *      tick (point 3) re-derives that same deadline from persisted state and runs
 *      on a VSYNC frame; in steady state it changes no pixels of its own.
 *
 *   2. REDUNDANT WAKE CYCLES ARE STRIPPED, NOT MERELY THROTTLED.
 *      Every render request funnels through update(). If the surface the native
 *      chronometer is already driving cannot change (same countdown, same
 *      deadline second) the request is dropped BEFORE it becomes a binder call
 *      to NotificationManager. That is the whole optimisation: the engine
 *      re-evaluates on every screen event, every alarm and every recheck, and
 *      each of those used to produce a fresh notification post for pixels that
 *      were already correct. Suppressed renders are counted, so the effect is
 *      measurable rather than asserted.
 *
 *   3. THE APP-SIDE 1 Hz LOOP IS VSYNC-SYNCHRONIZED AND SELF-HEALING.
 *      {@link CountdownTicker} re-derives the remaining budget once per wall-clock
 *      second and hands each tick to {@link VsyncFrameClock}, so the re-derivation
 *      runs on a real display VSYNC frame (Choreographer) instead of landing at an
 *      arbitrary software instant -- the genuine hardware-synchronized frame. It is
 *      armed exactly while a live countdown is on screen and parked in every other
 *      state (not interactive, locked, opt-out, no limit configured, gate closed).
 *      Because the deadline is structurally constant while the screen-on anchor is
 *      unchanged (deadline = limit - accumulator + anchor, independent of `now`),
 *      a steady tick is suppressed BEFORE it can become a binder call, so the loop
 *      adds zero notification traffic while still guaranteeing the surface can
 *      never drift or be left showing a stale/zero value. The whole model is
 *      registered with {@link PowerGovernor} as a Suspendable, so the moment the
 *      device stops being interactive the ticker is cancelled and update() refuses
 *      to post; the governor's instrumentation (idleLatePosts == 0, clean verdict)
 *      is the proof that nothing leaks into the idle window.
 *
 * PERMISSION GATE ("status bar permitted"). Nothing is posted while the OS has
 * revoked our right to show it: POST_NOTIFICATIONS /
 * NotificationManager.areNotificationsEnabled() and the tl_countdown channel's
 * importance are probed ONCE and cached for 10 s, so the hot path never performs
 * a binder call, and if the user blocks the countdown channel the engine
 * degrades to the static monitor text instead of posting invisible
 * notifications forever.
 *
 * COST MODEL (steady state, screen on, permitted): one binder call per real
 * state change (budget moved, lock/unlock, screen on/off) -- O(events), not
 * O(seconds) -- and zero CPU between them. While the screen is off: zero renders,
 * zero ticks, zero alarms held by this class. That is the floor the engine runs
 * at. "Absolute zero current draw" is not physically reachable: a powered device
 * awake enough to draw a live countdown is by definition drawing current. What
 * IS reachable, and what this class implements, is that the countdown adds no
 * measurable drain of its own on top of a panel that is already on.
 */
final class LiveCountdown implements CountdownTicker.Sink, PowerGovernor.Suspendable {

    static final String TAG = "TL.Countdown";

    /** The owner of the status-bar surface (the foreground service). */
    interface Sink {
        /**
         * Cheap, cached state check: a live countdown is meaningful right now
         * (configured, countdown enabled, not locked). MUST NOT do binder work.
         */
        boolean countdownWanted();

        /** Wall-clock instant the remaining budget hits zero; 0 when there is none. */
        long deadlineMs(long now);

        /**
         * Post / refresh the status-bar notification.
         *
         * @return true only when a post was actually handed to the
         *         NotificationManager, so the governor's accounting stays honest.
         */
        boolean render(long now, boolean nativeChronometer, long deadlineMs);
    }

    /** The notification permission is re-probed at most once per 10 s. */
    private static final long PROBE_TTL_MS = 10_000L;

    /**
     * How often a LIVE screen-on session is checkpointed to the durable store on
     * the cadence this surface already runs. It adds no timer and no wakeup -- it
     * piggybacks the tick / event path -- and it bounds the worst-case reboot
     * recovery window (the passive watchdog recheck is up to 15 minutes apart) to
     * one minute. A same-boot force-close loses nothing regardless of this value,
     * because the monotonic elapsed clock survives it.
     */
    private static final long CHECKPOINT_INTERVAL_MS = 60_000L;

    private final Context ctx;
    private final Sink sink;
    private final CountdownTicker ticker = new CountdownTicker(this);

    // --- permission probe cache: never a binder call in the hot path --------
    private boolean permitted = false;
    private long probedAtElapsed = -2L * PROBE_TTL_MS;

    // --- render dedupe -----------------------------------------------------
    private boolean live = false;           // countdown surface currently valid
    private boolean nativeDriven = false;   // SystemUI is drawing the seconds
    private boolean renderedNative = false; // ...and the LAST render was that surface
    private long renderedDeadlineSec = 0L;
    private boolean rendered = false;

    // --- instrumentation ---------------------------------------------------
    private long updates = 0L;
    private long posts = 0L;
    private long suppressed = 0L;
    private long ticksServiced = 0L;

    LiveCountdown(Context c, Sink sink) {
        this.ctx = c.getApplicationContext();
        this.sink = sink;
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    /** Attach to the governor and publish the first surface. Idempotent. */
    void start() {
        PowerGovernor.register(this);
        ticker.start();
        update(System.currentTimeMillis());
    }

    /** Detach and take the ticker fully off the main-thread queue. */
    void stop() {
        ticker.stop();
        PowerGovernor.unregister(this);
        rendered = false;
        renderedNative = false;
        live = false;
        nativeDriven = false;
    }

    boolean isLive() { return live; }

    /**
     * Drop the render memory so the next update() re-pushes the surface
     * unconditionally. Used after the notification is dismissed (or otherwise
     * needs to be forced back), so the same-second dedupe cannot swallow the
     * re-post. Costs nothing while idle: it only clears state.
     */
    void invalidate() {
        rendered = false;
        renderedNative = false;
    }

    /**
     * The single entry point for "the countdown might need to change".
     *
     * Everything else in the engine calls this on screen events, alarms, lock
     * transitions, rechecks and config changes. It is deliberately coarse: the
     * cheap decisions (gate, dedupe, idle check) happen here, and only a genuinely
     * new surface ever reaches the NotificationManager.
     */
    void update(long now) {
        updates++;

        // Durability checkpoint for the live usage session. Cheap: a no-op until
        // the persisted heartbeat is CHECKPOINT_INTERVAL_MS old, then ONE fsync'd
        // row write. It never influences the render decision below and consumes no
        // extra alarm, wakeup or wake lock -- this method is already executing on
        // the shared 1 Hz cadence / screen-event path. It exists so an abrupt power
        // cut strands at most one minute of the in-flight session instead of a
        // whole watchdog interval.
        if (PowerGovernor.interactive()) {
            UsageStore.checkpoint(ctx, now, CHECKPOINT_INTERVAL_MS);
        }

        boolean wanted = sink.countdownWanted();
        boolean allowed = wanted && statusBarPermitted();
        long deadline = allowed ? sink.deadlineMs(now) : 0L;
        boolean liveNow = allowed && deadline > now;

        live = liveNow;
        nativeDriven = liveNow && nativeChronometerAvailable();

        if (PowerGovernor.isIdle()) {
            // Never post into a closed idle window. Force one refresh on wake.
            rendered = false;
            ticker.refresh();
            return;
        }

        long deadlineSec = liveNow ? (deadline / 1000L) : 0L;
        if (nativeDriven && renderedNative && rendered && deadlineSec == renderedDeadlineSec) {
            // THE OPTIMISATION: SystemUI is already drawing exactly these pixels
            // for exactly this deadline -- the seconds advance natively, with no
            // app involvement. This is not a throttle: nothing is scheduled and
            // nothing is posted; the request ceases to exist before it can cost a
            // binder call. renderedNative is part of the guard so a static->native
            // transition always posts (different surface, same second), which the
            // deadline-second check alone would swallow.
            //
            // Consequence: while the screen is on and the deadline is unchanged,
            // the 1 Hz ticker can fire as often as it likes and costs ZERO binder
            // traffic. The app is never the thing that redraws the countdown.
            suppressed++;
            ticker.refresh();
            return;
        }

        renderedDeadlineSec = deadlineSec;
        boolean posted = false;
        try {
            posted = sink.render(now, nativeDriven, deadline);
        } catch (Throwable t) {
            Log.w(TAG, "render", t);
        }
        rendered = true;
        renderedNative = nativeDriven;
        if (posted) {
            posts++;
            PowerGovernor.notePost("countdown");
        }
        // Re-evaluates the ticker gate. A steady tick is suppressed above, so
        // arming the 1 Hz loop costs no binder traffic in this state.
        ticker.refresh();
    }

    // ======================================================================
    // CountdownTicker.Sink -- the VSYNC-synchronized 1 Hz loop
    // ======================================================================

    /**
     * Render the status-bar surface from the SHARED authoritative instant.
     *
     * {@code nowMs} is the same value handed to every other surface on this
     * tick, so the status-bar countdown and the in-app telemetry are reading one
     * clock rather than two. It is deliberately NOT re-sampled here.
     */
    @Override public void onTick(long nowMs) {
        ticksServiced++;
        update(nowMs);
    }

    @Override public boolean tickAllowed() {
        // ARMED whenever a live countdown is actually on screen. Every tick
        // re-derives the remaining budget from persisted state, and the tick
        // itself executes on a real display VSYNC frame (VsyncFrameClock), so the
        // app re-evaluates the status-bar value exactly once per wall-clock second
        // in lock-step with the panel.
        //
        // This is a correctness guard, not a repaint storm: because the deadline
        // is structurally constant while the screen-on anchor is unchanged
        // (deadline = limit - accumulator + anchor, independent of `now`),
        // update() suppresses a steady tick BEFORE it can become a binder call.
        // The visible per-second pixels are still drawn natively by SystemUI; what
        // the tick guarantees is that the surface can never drift, freeze or be
        // left showing a stale value -- none of which a purely native chronometer
        // could correct on its own. It arms nothing the moment the device stops
        // being interactive (PowerGovernor.onIdle -> onIdle()).
        return live && PowerGovernor.interactive();
    }

    // ======================================================================
    // PowerGovernor.Suspendable
    // ======================================================================

    @Override public void onIdle() {
        // Screen off: cancel the seatbelt and forget the surface, so the first
        // render after the next wake re-derives everything from the wall clock.
        ticker.onIdle();
        rendered = false;
        renderedNative = false;
    }

    @Override public void onWake() {
        update(System.currentTimeMillis());
    }

    @Override public void onMemoryTrim(int level) {
        ticker.onMemoryTrim(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Drop the cached surface and the permission probe: both are cheap to
            // rebuild and this keeps the process footprint small under pressure.
            live = false;
            rendered = false;
            renderedNative = false;
            probedAtElapsed = -2L * PROBE_TTL_MS;
        }
    }

    // ======================================================================
    // Permission gate
    // ======================================================================

    /**
     * TRUE when the OS currently permits the status-bar countdown.
     *
     * Cached for PROBE_TTL_MS because areNotificationsEnabled() /
     * getNotificationChannel() are binder calls and this gate is evaluated on
     * every render request. A revoked permission (or a blocked tl_countdown
     * channel) therefore takes up to 10 s to be noticed -- and the moment it is,
     * the countdown is dropped and only the static monitor text remains.
     */
    private boolean statusBarPermitted() {
        long el = SystemClock.elapsedRealtime();
        if (el - probedAtElapsed < PROBE_TTL_MS) return permitted;
        probedAtElapsed = el;
        permitted = probePermission();
        return permitted;
    }

    private boolean probePermission() {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel(TimeLockService.CH_COUNTDOWN);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "permission probe", t);
            return false;
        }
    }

    /**
     * True when the platform can draw the second clock itself. API 24 added
     * setChronometerCountDown(); below that the only option would be an app-side
     * 1 Hz repaint, which the ticker covers.
     */
    static boolean nativeChronometerAvailable() { return Build.VERSION.SDK_INT >= 24; }

    // ======================================================================
    // Instrumentation
    // ======================================================================

    String report() {
        return "countdown[live=" + live
                + " native=" + nativeDriven
                + " rendered=" + rendered
                + " renderedNative=" + renderedNative
                + " permitted=" + permitted
                + " updates=" + updates
                + " posts=" + posts
                + " suppressed=" + suppressed
                + " ticks=" + ticksServiced
                + " tickerRunning=" + ticker.isRunning() + "]";
    }
}
