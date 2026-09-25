package com.vortex.timelock;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A frozen, ready-to-paint picture of the kiosk at one instant.
 *
 * This is the single contract between the enforcement engine and the dashboard
 * layout module. The engine produces it; {@link KioskDashboard} consumes it and
 * never reaches back into {@link Prefs} or {@link Engine} itself. That keeps the
 * layout module a pure function of its input, which is what makes it cheap: no
 * observers, no listeners, no per-frame polling — one immutable value in, one
 * static frame out.
 *
 * Plain Java, no {@code android.*}: unit-testable on a bare JVM.
 */
final class KioskSnapshot {

    /** Wall clock at capture time. */
    final long nowMs;
    /** Milliseconds left before the lock lifts; 0 when not locked. */
    final long remainingMs;
    /** Screen time already spent inside the current accounting window. */
    final long usedMs;
    /** Budget for the window; 0 means "no limit configured". */
    final long limitMs;
    /** Instant the lock lifts; 0 when unknown. */
    final long unlockAtMs;
    /** Display-sleep watchdog: seconds until the panel blanks, or -1 if disabled. */
    final int guardSeconds;
    /** True while the lock screen is enforced. */
    final boolean locked;
    /** Newest-first history, already humanised by {@link KioskLogParser}. */
    final List<KioskLogEvent> events;

    KioskSnapshot(long nowMs, long remainingMs, long usedMs, long limitMs, long unlockAtMs,
                  int guardSeconds, boolean locked, List<KioskLogEvent> events) {
        this.nowMs = nowMs;
        this.remainingMs = Math.max(0L, remainingMs);
        this.usedMs = Math.max(0L, usedMs);
        this.limitMs = Math.max(0L, limitMs);
        this.unlockAtMs = Math.max(0L, unlockAtMs);
        this.guardSeconds = guardSeconds;
        this.locked = locked;
        this.events = events == null ? Collections.<KioskLogEvent>emptyList() : events;
    }

    /**
     * Fraction of the budget consumed, in tenths of a percent (0..1000).
     *
     * Returns 0 when no limit is configured — the dashboard renders an explicit
     * "not set" state for that case rather than a bar that looks like real data.
     */
    int permille() {
        if (limitMs <= 0L) return 0;
        long p = (usedMs * 1000L) / limitMs;
        return (int) Math.max(0L, Math.min(1000L, p));
    }

    /** Budget left in the window (never negative). */
    long budgetLeftMs() {
        long left = limitMs - usedMs;
        return left < 0L ? 0L : left;
    }

    /** "HH:MM:SS" — the hero read-out and every duration in the tiles. */
    static String hms(long ms) {
        if (ms < 0L) ms = 0L;
        long s = ms / 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L);
    }

    /** Compact human duration: "4h 52m", "52m 10s", "10s". */
    static String shortDuration(long ms) {
        if (ms < 0L) ms = 0L;
        long s = ms / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        if (h > 0L) return h + "h " + String.format(Locale.US, "%02d", m) + "m";
        if (m > 0L) return m + "m " + String.format(Locale.US, "%02d", s % 60L) + "s";
        return s + "s";
    }

    /** "2m ago" / "just now" for a past instant. */
    static String relative(long atMs, long nowMs) {
        if (atMs <= 0L) return "";
        long d = nowMs - atMs;
        if (d < 45_000L) return "just now";
        long s = d / 1000L;
        if (s < 3600L) return (s / 60L) + "m ago";
        if (s < 86_400L) return (s / 3600L) + "h ago";
        return (s / 86_400L) + "d ago";
    }
}
