package com.vortex.timelock;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * ConfigHistory — the configuration audit trail.
 *
 * DESIGN
 * ------
 *  1. A configuration change is not a second logging system. Every change is
 *     written straight into the ONE global log ring that the kiosk already
 *     keeps ({@link Prefs#setLastEvent}), using a single canonical line:
 *
 *         config:&lt;change&gt; limit=… reset=… warn=… schedule=… flags=… root=… active=…
 *
 *     Because it uses the existing ring, a configuration history survives
 *     process death, costs exactly one commit, and shows up in the same log
 *     view as every other event — no parallel store, no parser to keep in sync.
 *
 *  2. The line is self-describing: the change label says WHAT was changed and
 *     the snapshot says WHAT THE CONFIGURATION BECAME. Reading two adjacent
 *     config lines therefore yields a diff without any extra bookkeeping.
 *
 *  3. The snapshot is deliberately flat and stable-ordered so the raw ring can
 *     be diffed with plain text tools and the UI parser can render it without
 *     guessing.
 */
final class ConfigHistory {

    /** Label used when a caller has no more precise classification to give. */
    static final String UNCLASSIFIED = "config-change";

    private ConfigHistory() {}

    // =====================================================================
    // Recording
    // =====================================================================

    /**
     * Append one configuration change to the global log ring.
     *
     * @param change short, stable label such as {@code limits-updated} or
     *               {@code restriction-factory-off}. Never null-safe-guessed:
     *               an empty label is normalised to {@link #UNCLASSIFIED}.
     */
    static void record(Context c, String change) {
        if (c == null) return;
        String label = (change == null) ? "" : change.trim();
        if (label.isEmpty()) label = UNCLASSIFIED;
        Prefs.setLastEvent(c, "config:" + label + " " + snapshot(c));
    }

    // =====================================================================
    // Snapshot
    // =====================================================================

    /** Canonical, single-line description of the live configuration. */
    static String snapshot(Context c) {
        if (c == null) {
            return "limit=? reset=? warn=? schedule=? flags=? root=? active=?";
        }
        StringBuilder b = new StringBuilder(112);
        b.append("limit=").append(limitLabel(Prefs.limitMs(c)));
        b.append(" reset=").append(clock(Prefs.anchorMin(c)));
        b.append(" warn=").append(warnLabel(Prefs.warnSeconds(c)));
        b.append(" schedule=").append(scheduleLabel(c));
        b.append(" flags=").append(flagLabel(c));
        b.append(" root=sb=").append(Prefs.optSafeBoot(c) ? 1 : 0)
                .append(",fr=").append(Prefs.optFactoryReset(c) ? 1 : 0);
        b.append(" active=").append(Prefs.activated(c) ? "yes" : "no");
        return b.toString();
    }

    // =====================================================================
    // Reading back
    // =====================================================================

    /**
     * Configuration-only view of the global ring, oldest first. Lets a caller
     * show "configuration history" without re-parsing unrelated events.
     */
    static List<String> recent(Context c) {
        List<String> out = new ArrayList<String>();
        if (c == null) return out;
        String ring = Prefs.logRing(c);
        if (ring == null || ring.isEmpty()) return out;
        String[] lines = ring.split("\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            int bar = line.indexOf('|');
            String raw = (bar < 0) ? line : line.substring(bar + 1);
            if (raw.startsWith("config:")) out.add(raw);
        }
        return out;
    }

    // =====================================================================
    // Field formatters
    // =====================================================================

    /** {@code 90000} ms → {@code 1m30s}; {@code 0}/negative → {@code unset}. */
    static String limitLabel(long ms) {
        if (ms <= 0L) return "unset";
        long totalSecs = ms / 1000L;
        long h = totalSecs / 3600L;
        long m = (totalSecs % 3600L) / 60L;
        long s = totalSecs % 60L;
        StringBuilder b = new StringBuilder(8);
        if (h > 0L) b.append(h).append('h');
        if (m > 0L) b.append(m).append('m');
        if (s > 0L || b.length() == 0) b.append(s).append('s');
        return b.toString();
    }

    /** Minutes past midnight → {@code HH:MM}. */
    static String clock(int minutesPastMidnight) {
        int m = minutesPastMidnight % (24 * 60);
        if (m < 0) m += 24 * 60;
        int hh = m / 60;
        int mm = m % 60;
        return two(hh) + ":" + two(mm);
    }

    /** {@code 10} → {@code 10s}; {@code 0}/negative → {@code off}. */
    static String warnLabel(int seconds) {
        return seconds <= 0 ? "off" : seconds + "s";
    }

    private static String scheduleLabel(Context c) {
        try {
            String s = ScheduleConfig.summary(c);
            return (s == null || s.trim().isEmpty()) ? "default" : s.trim().replace(' ', '_');
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String flagLabel(Context c) {
        StringBuilder b = new StringBuilder(32);
        appendFlag(b, "screenoff", Prefs.optScreenOff(c));
        appendFlag(b, "keyguard", Prefs.optKeyguard(c));
        appendFlag(b, "nodebug", Prefs.optDisableDebugging(c));
        appendFlag(b, "standby", Prefs.optStandby(c));
        appendFlag(b, "countdown", Prefs.optCountdown(c));
        return b.length() == 0 ? "none" : b.toString();
    }

    private static void appendFlag(StringBuilder b, String name, boolean on) {
        if (!on) return;
        if (b.length() > 0) b.append('.');
        b.append(name);
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }
}
