package com.vortex.timelock;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ============================================================================
 *  TimeFmt — the one place every wall-clock time in the app is formatted
 * ============================================================================
 *
 *  <p><b>Why.</b> Screen Time Locker used to hard-code a clock convention per
 *  surface: the kiosk lock clock honoured the user's 12/24-hour setting, but the
 *  launcher + status dashboards always printed {@code HH:mm}, the lock-screen
 *  unlock label always printed {@code h:mm a}, and the setup console always
 *  printed {@code 11:59 PM}. A user who runs a 24-hour clock still saw AM/PM in
 *  half the app.
 *
 *  <p>Now every wall-clock time routes through here and follows a single source
 *  of truth: the user's own system preference, read through
 *  {@link DateFormat#is24HourFormat}. Flip the phone's clock and the whole app
 *  agrees on the next render — no per-screen setting, no stale cached string.
 *
 *  <p><b>Durations are not wall-clock times.</b> Countdowns ({@code 02:14:59}),
 *  "USED hh:mm:ss" and remaining-time read-outs keep their fixed zero-padded
 *  {@code HH:MM:SS} shape and deliberately do NOT come through here: a duration
 *  has no meridiem and must never gain one.
 */
final class TimeFmt {
    private TimeFmt() {}

    /** {@code "h:mm a"} on a 12-hour device, {@code "HH:mm"} on a 24-hour one. */
    private static final String H12_MIN = "h:mm a";
    private static final String H24_MIN = "HH:mm";
    /** Same choice, but carrying seconds (live "updated at" stamps, deadlines). */
    private static final String H12_SEC = "h:mm:ss a";
    private static final String H24_SEC = "HH:mm:ss";

    /**
     * True when the user's system preference is 24-hour. Never throws: a bad or
     * detached context simply falls back to the 12-hour form, matching the app's
     * historical default.
     */
    static boolean is24(Context c) {
        try {
            return c != null && DateFormat.is24HourFormat(c);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- wall-clock time, minutes only (e.g. "11:59 PM" / "23:59") ----------

    static String clock(boolean h24, long ms) {
        return new SimpleDateFormat(h24 ? H24_MIN : H12_MIN, Locale.US).format(new Date(ms));
    }

    static String clock(Context c, long ms) {
        return clock(is24(c), ms);
    }

    // ---- wall-clock time with seconds (e.g. "11:59:07 PM" / "23:59:07") -----

    static String clockSeconds(boolean h24, long ms) {
        return new SimpleDateFormat(h24 ? H24_SEC : H12_SEC, Locale.US).format(new Date(ms));
    }

    static String clockSeconds(Context c, long ms) {
        return clockSeconds(is24(c), ms);
    }

    // ---- wall-clock time from a static minute-of-day (0..1439) --------------

    /**
     * Formats a minute-of-day (the schedule anchor stored in {@link Prefs}) as a
     * wall-clock time in the user's chosen 12/24-hour form. The stored value is
     * always in 24-hour minutes; only the *presentation* follows the setting.
     */
    static String clockFromMinuteOfDay(boolean h24, int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        int h = m / 60;
        int mm = m % 60;
        if (h24) return String.format(Locale.US, "%02d:%02d", h, mm);
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.US, "%d:%02d %s", h12, mm, h < 12 ? "AM" : "PM");
    }

    static String clockFromMinuteOfDay(Context c, int minuteOfDay) {
        return clockFromMinuteOfDay(is24(c), minuteOfDay);
    }
}
