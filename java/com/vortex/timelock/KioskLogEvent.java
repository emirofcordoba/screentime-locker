package com.vortex.timelock;

/**
 * One <em>structured</em> kiosk event.
 *
 * The app's durable trail is a stream of terse, developer-style strings such as
 * {@code "kiosk-blocked-key:4"} or {@code "reeval:limit"}
 * (see every {@link Prefs#setLastEvent} call site). Those strings are perfect for
 * a logcat tail and useless on a lock screen a human is staring at.
 *
 * A {@code KioskLogEvent} is the human-facing projection of exactly one of those
 * strings: a title a non-developer can read, a one-line detail, and a tone the
 * dashboard can paint. The original string is kept verbatim in {@link #raw} so
 * nothing is lost in the translation.
 *
 * Deliberately free of any {@code android.*} import: the whole parse/format path
 * is plain Java and can be exercised on a bare JVM by a unit test, which is how
 * the transformation is verified before it ever reaches a device.
 */
final class KioskLogEvent {

    /** What actually happened, reduced to the handful of cases the kiosk emits. */
    enum Kind {
        LOCK_ENGAGED   ("Lock engaged"),
        LIMIT_REACHED  ("Daily limit reached"),
        KEY_BLOCKED    ("Navigation blocked"),
        SCREEN_SLEEP   ("Screen turned off"),
        SELF_HEAL      ("Self-heal check"),
        REEVALUATION   ("Limit re-evaluated"),
        APPROVAL       ("Admin approval"),
        RECHECK        ("Usage re-check"),
        CONFIG         ("Configuration recorded"),
        UNKNOWN        ("System event");

        /** Fallback title used when the raw string carries no better detail. */
        final String defaultTitle;

        Kind(String defaultTitle) { this.defaultTitle = defaultTitle; }
    }

    /** Semantic colour role; the view maps this to a palette token. */
    enum Tone {
        NEUTRAL, ACCENT, GOOD, WARN, DANGER
    }

    final Kind kind;
    final Tone tone;
    /** The untouched developer string this event was derived from. */
    final String raw;
    final String title;
    final String detail;
    /** Wall-clock instant the event was recorded (0 when unknown). */
    final long atMs;

    KioskLogEvent(Kind kind, Tone tone, String raw, String title, String detail, long atMs) {
        this.kind = kind;
        this.tone = tone;
        this.raw = raw == null ? "" : raw;
        this.title = title == null ? kind.defaultTitle : title;
        this.detail = detail == null ? "" : detail;
        this.atMs = atMs;
    }

    /** True when this event has a usable timestamp for a relative-time label. */
    boolean hasTime() { return atMs > 0L; }

    @Override
    public String toString() {
        return kind + "[" + tone + "] " + title
                + (detail.isEmpty() ? "" : " — " + detail)
                + " (raw=" + raw + ")";
    }
}
