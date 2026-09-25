package com.vortex.timelock;

import android.content.Context;

import java.util.Calendar;

/**
 * ============================================================================
 *  Sentinel TimeLock — weekly schedule configuration module
 * ============================================================================
 *
 *  Lets the user carve individual weekdays out of the single daily interval so
 *  that, for example, Friday and Saturday can be treated differently from the
 *  rest of the week. Every weekday (keyed by {@link Calendar#DAY_OF_WEEK}:
 *  1 = Sunday .. 7 = Saturday) is in exactly one of three modes:
 *
 *      INHERIT  — use the normal daily interval            (storage sentinel -1)
 *      OFF      — completely disable the lock that day      (storage sentinel  0)
 *      CUSTOM   — a reduced (or otherwise different) limit  (value > 0 ms)
 *
 *  This class owns no UI: it is the single source of truth for reading and
 *  writing the per-weekday configuration and for rendering it as text. The
 *  matching control lives in {@link WeekdayScheduleCard}. Every read/write goes
 *  through {@link Prefs}, which fsyncs each mutating write, and the result is
 *  consumed by {@link Engine#limitMsFor(Context, long)} when the lock is
 *  evaluated.
 *
 *  Nothing is preset: a freshly installed app has every day in INHERIT mode.
 */
final class ScheduleConfig {

    private ScheduleConfig() {}

    /** Storage sentinel — inherit the daily interval. Mirrors the Prefs contract. */
    static final long INHERIT = -1L;
    /** Storage sentinel — the lock is disabled for the whole of that day. */
    static final long OFF = 0L;

    static final int MODE_INHERIT = 0;
    static final int MODE_OFF = 1;
    static final int MODE_CUSTOM = 2;

    private static final String[] LONG = {
            "", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] SHORT = {
            "", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    static String dayName(int dow, boolean shortForm) {
        if (dow < Calendar.SUNDAY || dow > Calendar.SATURDAY) return "?";
        return shortForm ? SHORT[dow] : LONG[dow];
    }

    /** The weekday index for "today". */
    static int today() { return Calendar.getInstance().get(Calendar.DAY_OF_WEEK); }

    // ------------------------------------------------------------------ reads

    /** The raw stored value for a weekday (-1 inherit, 0 off, >0 custom ms). */
    static long storedLimitMs(Context c, int dow) { return Prefs.dayLimitMs(c, dow); }

    static int mode(Context c, int dow) {
        long v = storedLimitMs(c, dow);
        if (v == OFF) return MODE_OFF;
        if (v == INHERIT) return MODE_INHERIT;
        return MODE_CUSTOM;
    }

    static boolean isOff(Context c, int dow) { return mode(c, dow) == MODE_OFF; }
    static boolean isCustom(Context c, int dow) { return mode(c, dow) == MODE_CUSTOM; }

    /**
     * The limit that actually applies on [dow], with INHERIT resolved against
     * the daily default. A result of 0 means "no lock that day".
     */
    static long effectiveLimitMs(Context c, int dow) {
        long v = storedLimitMs(c, dow);
        if (v == OFF) return 0L;
        if (v == INHERIT) return Prefs.limitMs(c);
        return v;
    }

    // ----------------------------------------------------------------- writes

    /** Put the day back on the daily interval. */
    static void inherit(Context c, int dow) { Prefs.setDayLimitMs(c, dow, INHERIT); }

    /** Disable the lock completely for that day. */
    static void disable(Context c, int dow) { Prefs.setDayLimitMs(c, dow, OFF); }

    /** Give the day an explicit limit. Rejects non-positive values. */
    static boolean setCustom(Context c, int dow, long ms) {
        if (ms <= 0L) return false;
        Prefs.setDayLimitMs(c, dow, ms);
        return true;
    }

    /** Convenience: reduce a day to [minutes] (must stay >= 1 min). */
    static boolean reduce(Context c, int dow, int minutes) {
        return setCustom(c, dow, minutes * 60_000L);
    }

    static boolean isCustomized(Context c, int dow) { return mode(c, dow) != MODE_INHERIT; }

    static boolean hasCustomSchedule(Context c) { return Prefs.hasCustomSchedule(c); }

    /** Wipe every per-day override (all days return to INHERIT). */
    static void reset(Context c) { Prefs.clearSchedule(c); }

    static int customizedDayCount(Context c) {
        int n = 0;
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            if (isCustomized(c, d)) n++;
        }
        return n;
    }

    // ----------------------------------------------------------- presentation

    /** "3 h 30 min" style duration for a minute count. */
    static String human(long minutes) {
        if (minutes <= 0) return "0 min";
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " min";
        if (m == 0) return h + (h == 1 ? " hour" : " hours");
        return h + " h " + m + " min";
    }

    /** One-line description of the day's effective setting. */
    static String describe(Context c, int dow) {
        switch (mode(c, dow)) {
            case MODE_OFF:
                return "No lock";
            case MODE_CUSTOM:
                return human(storedLimitMs(c, dow) / 60_000L);
            default:
                long base = Prefs.limitMs(c);
                return base > 0 ? "Daily \u00b7 " + human(base / 60_000L) : "Daily \u00b7 not set";
        }
    }

    /** A compact summary of every day that differs from the daily interval. */
    static String summary(Context c) {
        if (!hasCustomSchedule(c)) {
            return "Every day uses the daily interval \u2014 no exceptions set.";
        }
        StringBuilder off = new StringBuilder();
        StringBuilder reduced = new StringBuilder();
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            int m = mode(c, d);
            if (m == MODE_OFF) append(off, SHORT[d]);
            else if (m == MODE_CUSTOM) append(reduced, SHORT[d]);
        }
        StringBuilder sb = new StringBuilder();
        if (off.length() > 0) sb.append(off).append(" \u2014 lock disabled");
        if (reduced.length() > 0) {
            if (sb.length() > 0) sb.append("   \u00b7   ");
            sb.append(reduced).append(" \u2014 reduced interval");
        }
        return sb.toString();
    }

    private static void append(StringBuilder b, String s) {
        if (b.length() > 0) b.append(", ");
        b.append(s);
    }
}
