package com.vortex.timelock;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * CountdownTicker -- the status-bar countdown's seatbelt, now a thin adapter on
 * the process's single authoritative tick handler.
 *
 * WHAT CHANGED AND WHY. This class used to own a private 1 Hz schedule of its
 * own (a {@link VsyncFrameClock} job) while the activities ran their own
 * {@code postDelayed(1000)} loops. Four independent clocks render the same
 * "remaining time" quantity, and independent clocks do not merely drift: they
 * read different instants, so the status bar and the on-screen telemetry could
 * disagree by a second while both were "healthy", and the activity loops could
 * freeze outright if one tick threw before it re-armed itself.
 *
 * The cadence now lives in exactly one place, {@link HardwareTick}: one timer,
 * one boundary-aligned VSYNC-scheduled fire, one shared {@code nowMs} handed to
 * every surface, re-armed BEFORE the sinks run so no sink can stop it. This
 * class keeps its original job -- it is the gate and the adapter between the
 * service's {@link Sink} and that shared cadence -- and nothing else.
 *
 * ARMED exactly while a live countdown is on screen: every tick re-derives the
 * remaining budget from persisted state so the surface can never be left showing
 * a stale value. In steady state a tick changes no pixels of its own: the
 * per-second pixels are still drawn natively by SystemUI's chronometer and
 * {@link LiveCountdown#update} drops a render whose deadline has not moved, so
 * the cadence is a correctness guard, not a repaint storm.
 *
 * ZERO BACKGROUND COST. This class owns no timer at all any more. It subscribes
 * to {@link HardwareTick} on start() and unsubscribes on stop(); HardwareTick
 * cancels the whole cadence the moment it has no live sink, and
 * {@link PowerGovernor} cancels it on the idle transition. While the screen is
 * off there is no queued message and no frame callback anywhere in the process.
 *
 * DELIBERATELY STATELESS ABOUT CONTEXT. The ticker holds no Context (it cannot
 * leak the service) and no system-service reference: everything that needs a
 * Context lives behind {@link Sink}.
 */
final class CountdownTicker implements HardwareTick.Sink {

    static final String TAG = "TL.Ticker";

    /** The owner of the countdown surface (the foreground service). */
    interface Sink {
        /**
         * Render one tick. Called on the main thread at most once per second,
         * and only while {@link #tickAllowed()} is true.
         *
         * @param nowMs the authoritative instant shared with every other surface
         *              rendered on this same tick
         */
        void onTick(long nowMs);

        /**
         * Cheap gate: live countdown permitted right now (interactive, unlocked,
         * a real limit configured, notifications granted and channel visible).
         * Implementations MUST answer from cached state -- this is evaluated on
         * every single tick and must never perform binder traffic.
         */
        boolean tickAllowed();
    }

    private final Sink sink;
    private boolean running = false;

    CountdownTicker(Sink sink) {
        this.sink = sink;
    }

    // ------------------------------------------------------------------ control

    /** Begin counting: join the shared cadence. Idempotent. */
    void start() {
        if (running) { refresh(); return; }
        running = true;
        HardwareTick.subscribe(this);
    }

    /** Leave the shared cadence. Idempotent. */
    void stop() {
        if (!running) { HardwareTick.unsubscribe(this); return; }
        running = false;
        HardwareTick.unsubscribe(this);
    }

    /**
     * Re-evaluate the gate immediately: realign the shared cadence when the
     * countdown is allowed, park it when it is not. Called on every screen event,
     * lock transition, alarm, recheck and configuration change, so the handler
     * never lingers in a state where the engine promised to be idle.
     */
    void refresh() {
        if (!running) return;
        HardwareTick.refresh();
    }

    boolean isRunning() { return running; }

    // ------------------------------------------------------------------- sink

    /**
     * The shared cadence asks whether this surface is live. Parked whenever the
     * owner's gate is closed or the device is not interactive, which is what lets
     * HardwareTick cancel the cadence entirely instead of ticking into nothing.
     */
    @Override public boolean active() {
        if (!running) return false;
        if (PowerGovernor.isIdle()) return false;
        try {
            return sink.tickAllowed();
        } catch (Throwable t) {
            Log.w(TAG, "tickAllowed", t);
            return false;
        }
    }

    /** Fan the authoritative instant out through the owner's gate. */
    @Override public void onTick(long nowMs) {
        if (!running) return;
        if (!sink.tickAllowed()) return;   // gate closed: park until refresh()
        sink.onTick(nowMs);
    }

    // ------------------------------------------------------------------ probes

    /** Kept for the owner's lifecycle hooks; the shared handler owns parking. */
    void onIdle() { }

    /** Re-align the shared cadence after a wake. */
    void onWake() { refresh(); }

    /** Forward memory pressure so the cadence keeps at most one queued tick. */
    void onMemoryTrim(int level) { HardwareTick.get().onMemoryTrim(level); }

    /**
     * TRUE when the app may post notifications AND the countdown channel has not
     * been blocked by the user.
     *
     * This is the "notification permission is granted" gate the whole countdown
     * architecture hangs off. It performs ONE binder lookup against
     * NotificationManager, so callers must cache it -- the ticker's per-second
     * gate goes through the service's 10 s probe, never through here directly.
     */
    static boolean notificationsEnabled(Context c) {
        if (c == null) return false;
        try {
            NotificationManager nm = (NotificationManager) c.getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
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
            Log.w(TAG, "notificationsEnabled", t);
            return false;
        }
    }
}
