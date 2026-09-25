package com.vortex.timelock;

import android.content.ComponentCallbacks2;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HardwareTick -- THE single authoritative 1 Hz tick handler for the process.
 *
 * THE DEFECT THIS REPLACES. The same "remaining time" quantity used to be
 * rendered by four INDEPENDENT 1 Hz clocks:
 *
 *   1. the status-bar countdown (CountdownTicker -> VsyncFrameClock), which was
 *      boundary-aligned and self-healing;
 *   2. the lock screen's raw-text projection and usage bar, a free-running
 *      {@code handler.postDelayed(tickUi, 1000L)} loop that re-armed itself as
 *      the LAST statement of the tick -- so any throwable thrown earlier on the
 *      same pass killed the loop permanently and the view froze on whatever
 *      digits it happened to hold;
 *   3. a second in-app raw-text kiosk loop ("Remaining: N s");
 *   4. SetupActivity's status refresher.
 *
 * Two of those loops drifted (their period was 1000 ms PLUS the time the work
 * itself took, re-measured from the end of the work) and two could freeze
 * forever. Independent clocks also mean independent instants: the counters were
 * never reading the same "now", so they could disagree by a second even while
 * both were healthy. That disagreement, plus the frozen/zero surface, is the
 * "tracking glitch".
 *
 * THE FIX. Exactly ONE timer exists in this process, and every countdown
 * surface -- the status-bar notification path and the in-app telemetry alike --
 * is a {@link Sink} on it:
 *
 *   - ONE CADENCE. All sinks are driven from a single {@link VsyncFrameClock}
 *     job, scheduled to the next wall-clock second boundary, so a tick always
 *     lands on a real hardware-synchronized VSYNC frame. The delay is recomputed
 *     from the wall clock on every fire, so drift is structurally impossible:
 *     a late process lands on the NEXT boundary, never one full period later.
 *   - ONE INSTANT. Each tick computes {@code System.currentTimeMillis()} ONCE
 *     and hands that identical value to every sink. The counters are no longer
 *     two clocks that agree by luck; they read the same number.
 *   - CANNOT FREEZE. The next tick is RE-ARMED BEFORE any sink is called, and
 *     every sink call is individually wrapped, so a sink that throws, blocks or
 *     is slow cannot stop the cadence. A frozen counter is no longer reachable.
 *   - NEVER A ZERO CLOCK. Because {@code subscribe()} performs one immediate
 *     render pass, a surface that comes to the foreground is populated from the
 *     real arithmetic at once instead of showing a placeholder until the first
 *     second elapses.
 *   - ZERO BACKGROUND COST. The cadence exists only while at least one sink is
 *     both registered and {@link Sink#active()}. When the last sink detaches,
 *     when the screen goes off, or when every sink gates itself off, the timer
 *     is cancelled outright -- nothing is queued, so there is no Handler message
 *     and no frame callback left behind to cost anything. The clock is also
 *     registered with {@link PowerGovernor}, so the governor's idle transition
 *     cancels it even if a sink forgets to detach.
 *
 * A plain Handler message cannot fire while the SoC is asleep, so this handler
 * is structurally incapable of being a wakeup source. It holds no Context, no
 * Service and no wake lock.
 */
final class HardwareTick implements PowerGovernor.Suspendable {

    static final String TAG = "TL.HardwareTick";

    /** The one and only tick period. Every counter in the app derives from it. */
    static final long PERIOD_MS = 1000L;

    /**
     * A consumer of the authoritative tick.
     *
     * Implementations are owned by their surfaces (an Activity, or the
     * foreground service behind {@link CountdownTicker}) and MUST both register
     * and unregister from the main thread, mirroring their own visibility.
     */
    interface Sink {
        /**
         * Cheap, cached gate: is this surface actually on screen and meaningful
         * right now? MUST answer from already-cached state -- it is evaluated on
         * every tick and every re-arm, so it must never perform binder traffic.
         */
        boolean active();

        /**
         * Render one frame from the shared authoritative instant.
         *
         * @param nowMs the SAME wall-clock instant that was handed to every other
         *              sink on this tick
         */
        void onTick(long nowMs);
    }

    /** Process-wide singleton: one queue, one pending job, one cadence. */
    private static final HardwareTick INSTANCE = new HardwareTick();

    static HardwareTick get() { return INSTANCE; }

    /** Register a sink and render it once immediately. Idempotent. */
    static void subscribe(Sink s) { INSTANCE.attach(s); }

    /** Unregister a sink. Idempotent; the cadence dies with the last sink. */
    static void unsubscribe(Sink s) { INSTANCE.detach(s); }

    /**
     * Re-evaluate the cadence now. Called on screen events, lock transitions,
     * gate changes and configuration changes: it can only arm the single timer
     * when a live sink needs it and cancel it otherwise, and it is a no-op while
     * a tick is being delivered (that tick already re-armed the next one).
     */
    static void refresh() { INSTANCE.reEvaluate(); }

    /** Wall-clock instant of the most recent authoritative tick, 0 if none yet. */
    static long lastTickMs() { return INSTANCE.lastTick; }

    /** One-line instrumentation, for the governor/engine diagnostics surface. */
    static String report() { return INSTANCE.describe(); }

    // ------------------------------------------------------------------ state

    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    private final VsyncFrameClock clock = VsyncFrameClock.get();

    private boolean running = false;      // a sink has ever attached and none is left
    private boolean armed = false;        // a tick is scheduled with the frame clock
    private boolean dispatching = false;  // re-entrancy guard around sink calls

    private long lastTick = 0L;
    private long ticks = 0L;
    private long parks = 0L;
    private long skipped = 0L;
    private long faults = 0L;

    private final Runnable fire = new Runnable() {
        @Override public void run() { fireTick(); }
    };

    private HardwareTick() { }

    // ======================================================================
    // Sink registry
    // ======================================================================

    private void attach(Sink s) {
        if (s == null) return;
        if (!sinks.contains(s)) {
            sinks.add(s);
            // Idempotent and harmless before the service attaches the governor
            // (PowerGovernor.register refuses duplicates and no-ops until then).
            PowerGovernor.register(this);
            Log.i(TAG, "subscribe " + s.getClass().getSimpleName()
                    + " sinks=" + sinks.size());
        }
        running = true;
        // A surface that has just become visible must not wait a full period for
        // its first frame: that is how a placeholder stays on screen for a
        // second. Render it from the real arithmetic immediately.
        deliver(System.currentTimeMillis(), s);
        reEvaluate();
    }

    private void detach(Sink s) {
        if (s == null) return;
        if (sinks.remove(s)) {
            Log.i(TAG, "unsubscribe " + s.getClass().getSimpleName()
                    + " sinks=" + sinks.size());
        }
        if (sinks.isEmpty()) {
            // No consumer => no reason for a timer to exist. Zero queued work.
            park();
        } else {
            reEvaluate();
        }
    }

    /** Retire the cadence completely: nothing queued, nothing scheduled. */
    private void park() {
        running = false;
        armed = false;
        clock.cancel();   // drops the phase-1 message AND any frame callback
        parks++;
    }

    // ======================================================================
    // Cadence
    // ======================================================================

    /**
     * Re-derive the whole schedule: keep it armed exactly while a live sink
     * needs it, cancel it in every other state. Safe to call from anywhere on
     * the main thread; a no-op while a tick is in flight (see {@link #deliver}).
     */
    private void reEvaluate() {
        if (dispatching) return;      // the in-flight tick already re-armed
        PowerGovernor.register(this); // idempotent retry, in case the governor
                                      // attached after our first subscribe
        armed = false;
        clock.cancel();
        if (!running) return;
        if (sinks.isEmpty()) { park(); return; }
        if (PowerGovernor.isIdle()) return;   // parked: nothing queued, no wakeups
        if (!anyActive()) return;
        arm();
    }

    /**
     * Schedule the next tick on the NEXT wall-clock second boundary, executed on
     * a hardware-synchronized VSYNC frame.
     *
     * The delay is derived from the wall clock but applied to the uptime timebase
     * (which is what the frame clock's phase-1 sleep consumes), and it is
     * recomputed on every fire. It is therefore always in [1, PERIOD_MS] and the
     * cadence can neither drift nor degenerate into a busy loop.
     */
    private void arm() {
        if (!running || armed) return;
        if (PowerGovernor.isIdle() || !anyActive()) return;
        armed = true;
        long delay = PERIOD_MS - (System.currentTimeMillis() % PERIOD_MS);
        if (delay < 1L) delay = 1L;
        else if (delay > PERIOD_MS) delay = PERIOD_MS;
        clock.scheduleAt(SystemClock.uptimeMillis() + delay, fire);
    }

    /** The tick. Dispatches the ONE authoritative instant to every live sink. */
    private void fireTick() {
        armed = false;
        if (!running) return;

        // THE authoritative instant. Every sink is handed this exact value, so
        // the status-bar countdown and the on-screen telemetry are reading the
        // same clock rather than two clocks that happen to agree.
        final long now = System.currentTimeMillis();
        lastTick = now;
        ticks++;

        // Record the tick first -- a tick that raced a screen-off is still
        // visible to the governor's verdict -- then RE-ARM BEFORE DISPATCH.
        // That ordering is the anti-freeze guarantee: a sink that throws or is
        // slow cannot stop the cadence, because the next tick already exists.
        PowerGovernor.noteTick();
        if (!PowerGovernor.isIdle()) arm();

        boolean live = false;
        for (Sink s : sinks) {
            boolean on;
            try {
                on = s.active();
            } catch (Throwable t) {
                faults++;
                Log.w(TAG, "active " + s.getClass().getSimpleName(), t);
                continue;
            }
            if (!on) { skipped++; continue; }
            live = true;
            deliver(now, s);
        }

        // Nobody is looking: retire the cadence so the idle cost is exactly zero.
        if (!live) park();
    }

    /**
     * Hand {@code now} to one sink, isolated.
     *
     * A sink that throws is logged and counted but never allowed to disturb the
     * cadence or its peers; this is the difference between a surface that can
     * glitch for one frame and one that freezes forever. While the call is in
     * flight {@link #dispatching} suppresses re-entrant re-scheduling, so a sink
     * that asks for a refresh from inside its own tick cannot churn the queue.
     */
    private void deliver(long now, Sink s) {
        if (s == null) return;
        boolean outer = dispatching;
        dispatching = true;
        try {
            s.onTick(now);
        } catch (Throwable t) {
            faults++;
            Log.w(TAG, "tick " + s.getClass().getSimpleName(), t);
        } finally {
            dispatching = outer;
        }
    }

    private boolean anyActive() {
        for (Sink s : sinks) {
            try {
                if (s.active()) return true;
            } catch (Throwable t) {
                faults++;
                Log.w(TAG, "active", t);
            }
        }
        return false;
    }

    // ======================================================================
    // PowerGovernor.Suspendable -- the zero-background guarantee
    // ======================================================================

    @Override public void onIdle() {
        // Screen off. Drop the scheduled job and arm NOTHING: while the device is
        // idle this handler holds zero timers, so it cannot be a wakeup source
        // and adds nothing measurable to the idle window.
        armed = false;
        clock.cancel();
        Log.i(TAG, "idle -> cadence parked");
    }

    @Override public void onWake() {
        // Interactive again: re-align the single cadence, then render every live
        // sink once from the current wall clock so no surface is left showing a
        // value from before the idle window.
        reEvaluate();
        if (dispatching || PowerGovernor.isIdle()) return;
        long now = System.currentTimeMillis();
        for (Sink s : sinks) {
            boolean on;
            try {
                on = s.active();
            } catch (Throwable t) {
                continue;
            }
            if (on) deliver(now, s);
        }
    }

    @Override public void onMemoryTrim(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Keep the cadence but guarantee at most ONE queued tick. The next
            // fire re-derives everything from the wall clock, so nothing is lost
            // by dropping a pending one.
            reEvaluate();
        }
    }

    // ======================================================================
    // Instrumentation
    // ======================================================================

    private String describe() {
        StringBuilder b = new StringBuilder(160);
        b.append("hardwareTick[sinks=").append(sinks.size())
         .append(" running=").append(running)
         .append(" armed=").append(armed)
         .append(" period=").append(PERIOD_MS).append("ms")
         .append(" ticks=").append(ticks)
         .append(" skipped=").append(skipped)
         .append(" parks=").append(parks)
         .append(" faults=").append(faults);
        if (lastTick > 0L) {
            b.append(" lastTick=").append(Math.max(0L, System.currentTimeMillis() - lastTick))
             .append("ms ago");
        } else {
            b.append(" lastTick=never");
        }
        for (Sink s : sinks) {
            boolean on = false;
            try { on = s.active(); } catch (Throwable ignored) { }
            b.append(" | ").append(s.getClass().getSimpleName())
             .append(on ? ":active" : ":parked");
        }
        b.append(']');
        return b.toString();
    }
}
