package com.vortex.timelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The translation layer: developer-style kiosk log → human-facing dashboard data.
 *
 * The single raw dialect the app still produces is decoded here.
 *
 * The event trail. Every engine decision is stamped into
 * {@link Prefs#setLastEvent} as a terse machine string — {@code "reeval:limit"},
 * {@code "kiosk-blocked-key:4"}, {@code "approval-halt:lock:no-sensor"}. Those
 * land in a bounded ring buffer (see {@link Prefs#logRing}). {@link #parseRing}
 * turns each entry into one {@link KioskLogEvent} with a readable title, a
 * one-line explanation and a semantic tone.
 *
 * The old monospaced ASCII dialect ({@code +----+} frames, a
 * {@code TIME TO UNLOCK} matrix, {@code USED hh:mm:ss} and {@code SLEEP GUARD}
 * lines) has been retired along with the terminal view that drew it, so there is
 * exactly one dialect left to decode. Nothing here emits framed text any more.
 *
 * No {@code android.*} imports anywhere in this file: the entire transform is
 * plain Java and is verified by a bare-JVM unit test.
 */
final class KioskLogParser {

    private KioskLogParser() {}

    /** Ring depth: enough history to explain the lock, small enough to never grow. */
    static final int RING_MAX = 40;

    /** Separator between the timestamp and the raw string inside one ring entry. */
    private static final char ENTRY_SEP = '|';

    // =====================================================================
    //  Ring buffer: bounded, append-only, newest last on disk
    // =====================================================================

    /**
     * Append one raw string to the bounded ring, dropping the oldest entries past
     * {@code max}. Pure string arithmetic on a value that is already in memory, so
     * it is safe to call from the write path without any I/O of its own.
     */
    static String ringPush(String ring, long atMs, String raw, int max) {
        if (raw == null) return ring == null ? "" : ring;
        String probe = raw.trim();
        // "" / "-" are the app's "nothing happened" placeholders, not events.
        if (probe.isEmpty() || "-".equals(probe)) return ring == null ? "" : ring;
        String cleaned = raw.replace('\n', ' ').replace(ENTRY_SEP, '/').trim();

        List<String> entries = new ArrayList<>();
        if (ring != null && !ring.isEmpty()) {
            for (String line : ring.split("\n")) {
                if (!line.trim().isEmpty()) entries.add(line);
            }
        }
        entries.add(atMs + String.valueOf(ENTRY_SEP) + cleaned);

        while (entries.size() > Math.max(1, max)) entries.remove(0);

        StringBuilder b = new StringBuilder(entries.size() * 40);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(entries.get(i));
        }
        return b.toString();
    }

    /**
     * Decode the ring into newest-first events. Entries with no timestamp fall
     * back to {@code fallbackAtMs} so a relative-time label is always available.
     */
    static List<KioskLogEvent> parseRing(String ring, long fallbackAtMs) {
        if (ring == null || ring.isEmpty()) return Collections.emptyList();
        String[] lines = ring.split("\n");
        List<KioskLogEvent> out = new ArrayList<>(lines.length);
        for (int i = lines.length - 1; i >= 0; i--) {   // newest first
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            long at = 0L;
            String raw = line;
            int sep = line.indexOf(ENTRY_SEP);
            if (sep > 0 && sep < 20) {
                at = parseLong(line.substring(0, sep), 0L);
                raw = line.substring(sep + 1);
            }
            KioskLogEvent e = parseLine(raw, at > 0L ? at : fallbackAtMs);
            if (e != null) out.add(e);
            if (out.size() >= RING_MAX) break;
        }
        return out;
    }

    // =====================================================================
    //  Event trail → structured events
    // =====================================================================

    /**
     * Map one raw engine string to a human event. Returns {@code null} for the
     * placeholder values ("", "-") so callers can skip them.
     */
    static KioskLogEvent parseLine(String raw, long atMs) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "-".equals(s)) return null;

        // ---- navigation hardening ----
        if (s.startsWith("kiosk-blocked-key:")) {
            int code = (int) parseLong(s.substring("kiosk-blocked-key:".length()), -1L);
            String key = keyName(code);
            return new KioskLogEvent(KioskLogEvent.Kind.KEY_BLOCKED, KioskLogEvent.Tone.WARN, s,
                    "Navigation blocked",
                    "The kiosk consumed the " + key + " key before the system saw it", atMs);
        }

        // ---- display sleep (kiosk activity + WakeGuard both write this) ----
        if (s.startsWith("kiosk-wake-sleep:") || s.startsWith("wake-sleep:")) {
            String tail = s.substring(s.indexOf(':') + 1);
            long secs = parseLeadingNumber(tail, -1L);
            String detail = secs >= 0L
                    ? "Panel blanked " + secs + "s after the last wake signal"
                    : "Panel blanked after the wake grace window elapsed";
            return new KioskLogEvent(KioskLogEvent.Kind.SCREEN_SLEEP, KioskLogEvent.Tone.NEUTRAL,
                    s, "Screen turned off", detail, atMs);
        }

        // ---- self-heal ----
        if (s.startsWith("selfheal:")) {
            String body = s.substring("selfheal:".length());
            boolean owner = body.contains("owner") && !body.startsWith("noowner");
            KioskLogEvent.Tone tone = owner ? KioskLogEvent.Tone.GOOD : KioskLogEvent.Tone.WARN;
            String detail = owner
                    ? "Device owner confirmed — enforcement policy re-applied"
                    : "Device owner missing — enforcement is standing down";
            return new KioskLogEvent(KioskLogEvent.Kind.SELF_HEAL, tone, s,
                    "Self-heal check", detail, atMs);
        }

        // ---- admin approval chain ----
        if (s.startsWith("approval-")) {
            return parseApproval(s, atMs);
        }

        // ---- limit re-evaluation ----
        if (s.startsWith("reeval:")) {
            String reason = s.substring("reeval:".length());
            return new KioskLogEvent(KioskLogEvent.Kind.REEVALUATION, KioskLogEvent.Tone.NEUTRAL, s,
                    "Limit re-evaluated",
                    "Triggered by " + humanise(reason), atMs);
        }

        // ---- periodic usage re-check ----
        if (s.startsWith("recheck:")) {
            String body = s.substring("recheck:".length());
            boolean idle = body.endsWith("idle");
            boolean rolled = body.endsWith("roll");
            String reason = body.replace(" idle", "").replace(" roll", "").trim();
            KioskLogEvent.Tone tone = rolled ? KioskLogEvent.Tone.ACCENT : KioskLogEvent.Tone.NEUTRAL;
            String detail;
            if (rolled) {
                detail = "The 24h accounting window rolled over — the budget is reset";
            } else if (idle) {
                detail = "Not activated yet — the check reported idle and stopped";
            } else {
                detail = "Scheduled usage check from " + humanise(reason);
            }
            return new KioskLogEvent(KioskLogEvent.Kind.RECHECK, tone, s,
                    "Usage re-check", detail, atMs);
        }

        // ---- explicit lock states, in case a future build writes them ----
        if (s.startsWith("limit-reached")) {
            return new KioskLogEvent(KioskLogEvent.Kind.LIMIT_REACHED, KioskLogEvent.Tone.DANGER, s,
                    "Daily limit reached", "Screen time is used up — the lock is now enforced", atMs);
        }
        if (s.startsWith("lock-engaged")) {
            return new KioskLogEvent(KioskLogEvent.Kind.LOCK_ENGAGED, KioskLogEvent.Tone.DANGER, s,
                    "Lock engaged", "The kiosk lock screen took over the display", atMs);
        }

        // ---- configuration history (see ConfigHistory) ----
        if (s.startsWith("config:")) {
            return parseConfig(s, atMs);
        }

        // ---- anything this build does not know yet: still show it, tidied up ----
        return new KioskLogEvent(KioskLogEvent.Kind.UNKNOWN, KioskLogEvent.Tone.NEUTRAL, s,
                "System event", humanise(s), atMs);
    }

    /** {@code approval-begin|step|pass|granted|halt:scope:…} → one readable step. */
    private static KioskLogEvent parseApproval(String s, long atMs) {
        String body = s.substring("approval-".length());
        int colon = body.indexOf(':');
        String phase = colon < 0 ? body : body.substring(0, colon);
        String rest = colon < 0 ? "" : body.substring(colon + 1);

        String scope = rest;
        int scopeEnd = rest.indexOf(':');
        if (scopeEnd > 0) scope = rest.substring(0, scopeEnd);
        String scopeLabel = humanise(scope);

        if ("begin".equals(phase)) {
            long tiers = parseAfter(rest, "tiers=", 0L);
            String detail = tiers > 0L
                    ? "The " + scopeLabel + " unlock needs " + tiers + " approval tiers"
                    : "The " + scopeLabel + " unlock requires approval";
            return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, KioskLogEvent.Tone.ACCENT, s,
                    "Approval started", detail, atMs);
        }
        if ("step".equals(phase)) {
            return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, KioskLogEvent.Tone.ACCENT, s,
                    "Approval step", "Working through step " + humanise(rest) + " of "
                    + scopeLabel, atMs);
        }
        if ("pass".equals(phase)) {
            return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, KioskLogEvent.Tone.GOOD, s,
                    "Approval checkpoint passed", "Checkpoint " + humanise(rest) + " accepted",
                    atMs);
        }
        if ("granted".equals(phase)) {
            return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, KioskLogEvent.Tone.GOOD, s,
                    "Approval granted", "The " + scopeLabel + " unlock was authorised", atMs);
        }
        if ("halt".equals(phase)) {
            String why = humanise(rest);
            KioskLogEvent.Tone tone = (why.contains("deny") || why.contains("fail") || why.contains("no"))
                    ? KioskLogEvent.Tone.DANGER : KioskLogEvent.Tone.WARN;
            return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, tone, s,
                    "Approval stopped", "Halted on " + scopeLabel + " — " + why, atMs);
        }
        return new KioskLogEvent(KioskLogEvent.Kind.APPROVAL, KioskLogEvent.Tone.NEUTRAL, s,
                "Admin approval", humanise(s), atMs);
    }

    /**
     * {@code config:limits-updated limit=2h reset=00:00 warn=10s …} → one readable
     * configuration change. The label names what changed, the trailing fields are
     * the configuration that resulted.
     */
    private static KioskLogEvent parseConfig(String s, long atMs) {
        String body = s.substring("config:".length()).trim();
        int sep = body.indexOf(' ');
        String change = sep < 0 ? body : body.substring(0, sep);
        String state = sep < 0 ? "" : body.substring(sep + 1).trim();

        String title = humanise(change);
        if (title.isEmpty()) title = "Configuration recorded";
        String detail = state.isEmpty() ? "A configuration change was recorded" : fieldList(state);
        return new KioskLogEvent(KioskLogEvent.Kind.CONFIG, KioskLogEvent.Tone.ACCENT, s,
                title, detail, atMs);
    }

    /** {@code limit=2h reset=00:00 warn=10s} → {@code limit 2h · reset 00:00 · warn 10s}. */
    private static String fieldList(String s) {
        String[] parts = s.replace(';', ' ').replace('=', ' ').split(" ");
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (String p : parts) {
            String q = p.trim();
            if (q.isEmpty()) continue;
            if (b.length() > 0) b.append(" \u00b7 ");
            b.append(q);
        }
        return b.length() == 0 ? "Configuration recorded" : b.toString();
    }

    // =====================================================================
    //  Small scanners (no regex: keeps the parse allocation-free per line)
    // =====================================================================

    /** First run of digits found in {@code s}, or {@code def}. */
    private static long parseLeadingNumber(String s, long def) {
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        if (i >= s.length()) return def;
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        return parseLong(s.substring(i, j), def);
    }

    /** Digits immediately following {@code tag}, or {@code def}. */
    private static long parseAfter(String s, String tag, long def) {
        int i = s.indexOf(tag);
        if (i < 0) return def;
        return parseLeadingNumber(s.substring(i + tag.length()), def);
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // =====================================================================
    //  Wording helpers
    // =====================================================================

    /** "no-sensor" / "win_roll" → "No sensor" / "Win roll". */
    static String humanise(String s) {
        if (s == null) return "";
        String t = s.trim().replace('_', ' ').replace('-', ' ').replace('/', ' ').replace("  ", " ");
        if (t.isEmpty()) return "";
        if (t.length() > 60) t = t.substring(0, 57) + "…";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Android key codes the kiosk swallows → the words a person recognises. */
    static String keyName(int code) {
        switch (code) {
            case 3:   return "Home";
            case 4:   return "Back";
            case 82:  return "Menu";
            case 84:  return "Search";
            case 111: return "Escape";
            case 24:  return "Volume up";
            case 25:  return "Volume down";
            case 164: return "Mute";
            case 27:  return "Camera";
            case 187: return "Recents";
            default:  return "hardware (" + code + ")";
        }
    }
}
