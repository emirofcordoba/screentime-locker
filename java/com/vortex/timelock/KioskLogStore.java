package com.vortex.timelock;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The glue between the enforcement engine and the dashboard layout module.
 *
 * <p>Exactly one method matters: {@link #capture}. It reads the durable state
 * once, converts the developer-style diagnostic trail into human-readable
 * {@link KioskLogEvent}s, and freezes the result into a {@link KioskSnapshot}.
 * The dashboard then does nothing but paint that value.
 *
 * <p>This keeps the layout module completely free of {@code Prefs}/{@code Engine}
 * knowledge, so it can be previewed and reasoned about without a device, and it
 * gives every surface (this lock screen today, any future widget tomorrow) the
 * same numbers from the same call.
 *
 * <p>Read-only by design: the render path runs at 1 Hz on the UI thread, so it
 * must never write. The write side lives at the single point where the app
 * already records a diagnostic ({@link Prefs#setLastEvent}).
 */
final class KioskLogStore {

    /** How many raw events the durable ring keeps. */
    static final int RING_MAX = 40;

    /** How many rows the dashboard is willing to show. */
    static final int VISIBLE_MAX = 6;

    private KioskLogStore() {}

    /**
     * One consistent picture of the kiosk at {@code nowMs}.
     *
     * @param guardSeconds seconds until the display-sleep watchdog blanks the
     *                     panel, or -1 when that watchdog is off/not armed.
     */
    static KioskSnapshot capture(Context c, long nowMs, int guardSeconds) {
        long unlockAt = Engine.lockUntilMs(c, nowMs);
        long limit = Engine.limitMs(c);
        long used = Engine.effectiveUsed(c, nowMs);

        long left = unlockAt > nowMs ? unlockAt - nowMs : 0L;
        boolean locked = left > 0L || Engine.shouldBeLocked(c, nowMs) || Prefs.locked(c);

        List<KioskLogEvent> events = KioskLogParser.parseRing(Prefs.logRing(c), nowMs);

        if (events.isEmpty()) {
            // Install that predates the ring: fall back to the single-slot
            // diagnostic the previous revisions kept, so the card is never empty
            // on an upgraded device.
            String last = Prefs.lastEvent(c);
            if (last != null && !last.isEmpty() && !"-".equals(last)) {
                events = new ArrayList<>(1);
                events.add(KioskLogParser.parseLine(last, Prefs.lastEventAt(c)));
            }
        }

        if (events.isEmpty() && locked) {
            // A fresh install has no history at all. State the live condition
            // once, in the same vocabulary, so the dashboard shows the current
            // situation instead of a blank box. The "state:" prefix marks it as
            // derived from state rather than captured from the log.
            events = Collections.singletonList(new KioskLogEvent(
                    KioskLogEvent.Kind.LOCK_ENGAGED, KioskLogEvent.Tone.DANGER,
                    "state:locked", "Lock engaged right now",
                    "Today's screen time is used up - the unlock timer is running above.", 0L));
        }

        if (events.size() > VISIBLE_MAX) {
            events = events.subList(0, VISIBLE_MAX);
        }

        // Battery: read the STICKY ACTION_BATTERY_CHANGED broadcast once per
        // capture. registerReceiver(null, ...) does not register a live receiver,
        // so this costs one cached-Intent read and no wake-up, which preserves the
        // render path's zero-scheduling contract. -1 means the level is unknown;
        // the dashboard then omits the read-out rather than printing a fake 0%.
        Intent battery = stickyBattery(c);

        return new KioskSnapshot(nowMs, locked ? left : 0L, used, limit,
                locked ? unlockAt : 0L, guardSeconds,
                batteryPercent(battery), batteryCharging(battery), locked, events);
    }

    /**
     * The last {@code ACTION_BATTERY_CHANGED} broadcast, or {@code null}. Passing a
     * {@code null} receiver to {@link Context#registerReceiver} returns the sticky
     * Intent without subscribing, so no receiver, no filter and no listener lives
     * after this call returns.
     */
    private static Intent stickyBattery(Context c) {
        try {
            return c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Charge percentage (0..100) parsed from a battery sticky Intent, or -1. */
    private static int batteryPercent(Intent b) {
        if (b == null) return -1;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.round(level * 100f / scale);
    }

    /** True while the battery reports CHARGING or FULL. */
    private static boolean batteryCharging(Intent b) {
        if (b == null) return false;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }
}
