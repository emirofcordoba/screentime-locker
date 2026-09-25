package com.vortex.timelock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Durable state store. Every mutating write uses commit() (synchronous, fsync'd)
 * so a reboot / power loss can never lose accounting data.
 */
final class Prefs {

    private static final String FILE = "tl_state";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void put(SharedPreferences.Editor e) {
        e.commit();
    }

    // ---------- configuration ----------
    /** No preset: the daily limit is chosen by the user in the setup UI (0 = unset). */
    static long limitMs(Context c) { return sp(c).getLong("limit_ms", 0L); }
    static void setLimitMs(Context c, long v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putLong("limit_ms", v); put(e);
    }

    /** minutes after midnight at which the 24h accounting window starts */
    static int anchorMin(Context c) { return sp(c).getInt("anchor_min", 0); }
    static void setAnchorMin(Context c, int v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putInt("anchor_min", v); put(e);
    }

    static boolean activated(Context c) { return sp(c).getBoolean("activated", false); }
    static void setActivated(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("activated", v); put(e);
        // Keep the device-protected mirror in lock-step so a direct-boot receiver
        // (pre-unlock, autostart-permission-free) can read this state without CE.
        BootState.mirror(c);
    }

    /** Seconds the kiosk shows its "screen turning off" warning before it does so. */
    static int warnSeconds(Context c) { return sp(c).getInt("warn_seconds", 10); }
    static void setWarnSeconds(Context c, int v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putInt("warn_seconds", v); put(e);
    }

    /** True once ownership has been received via transferOwnership (informational). */
    static boolean transferred(Context c) { return sp(c).getBoolean("transferred", false); }
    static void setTransferred(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("transferred", v); put(e);
    }

    // ---------- admin credential ----------
    //
    // The console is unlocked ONLY with an enrolled fingerprint (see
    // BiometricAuth). There is no stored secret of any kind: no PIN, salt,
    // digest or escrow key is ever written, so a locked device carries no
    // recoverable local credential to attack, dump or social-engineer. This
    // build intentionally ships with NO emergency release / recovery key: once
    // the policy is locked in, the policy itself is the only way out.

    // ---------- per-weekday schedule ----------
    //
    // Each weekday (keyed by Calendar.DAY_OF_WEEK: 1=Sun .. 7=Sat) can carry its
    // own screen-time limit, be disabled outright, or inherit the daily default:
    //   -1L  -> inherit the default daily limit ("limit_ms")
    //    0L  -> the lock is DISABLED for that day (never locks)
    //   >0L  -> an explicit per-day limit in milliseconds
    static long dayLimitMs(Context c, int dow) {
        return sp(c).getLong("day_limit_" + dow, -1L);
    }

    static void setDayLimitMs(Context c, int dow, long v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putLong("day_limit_" + dow, v); put(e);
    }

    static boolean hasCustomSchedule(Context c) {
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            if (dayLimitMs(c, d) != -1L) return true;
        }
        return false;
    }

    static void clearSchedule(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            e.remove("day_limit_" + d);
        }
        put(e);
    }

    // ---------- settings lock ----------
    // Set the moment setup is completed. While set, the configuration surface is
    // reachable ONLY through the secret dial code plus a biometric identity check:
    // the manifest stops external (ADB / activity-launcher) starts, and, as
    // Device Owner, USB debugging is disabled so ADB cannot reach the settings at
    // all. There is no PIN fallback and no emergency release.
    static boolean settingsLocked(Context c) { return sp(c).getBoolean("settings_locked", false); }
    static void setSettingsLocked(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("settings_locked", v); put(e);
    }

    // ---------- live accounting state ----------
    //
    // The daily usage counter now lives in UsageStore: a single-row SQLite table
    // written in ONE fsync'd transaction per change, sealed with an AndroidKeyStore
    // HMAC and recovered across SIGKILL/reboot from a durable heartbeat. These
    // accessors delegate to it so there is exactly one source of truth and no
    // stale duplicate of the counter can exist in this preferences file.
    static long windowStart(Context c) { return UsageStore.windowStart(c); }
    static void setWindowStart(Context c, long v) { UsageStore.setWindowStart(c, v); }

    static long acc(Context c) { return UsageStore.acc(c); }
    static void setAcc(Context c, long v) { UsageStore.setAcc(c, v); }

    static long screenOnSince(Context c) { return UsageStore.sessionStart(c); }
    static void setScreenOnSince(Context c, long v) { UsageStore.setSessionStart(c, v); }

    static boolean locked(Context c) { return sp(c).getBoolean("locked", false); }
    static void setLocked(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("locked", v); put(e);
        BootState.mirror(c);
    }

    static long lockUntil(Context c) { return sp(c).getLong("lock_until", 0L); }
    static void setLockUntil(Context c, long v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putLong("lock_until", v); put(e);
        BootState.mirror(c);
    }

    // ---------- diagnostics ----------
    //
    // Two views of the same trail:
    //   last_event -> a single string, overwritten in place (logcat-style tail)
    //   log_ring   -> the last LOG_RING_MAX events, "ts|raw" per line, oldest first
    //
    // The ring exists so the kiosk dashboard has actual history to render instead
    // of one line of developer text. Both are written at the one point the app
    // already records a diagnostic, so no call site had to change.
    static final int LOG_RING_MAX = 40;

    static String lastEvent(Context c) { return sp(c).getString("last_event", "-"); }
    static long lastEventAt(Context c) { return sp(c).getLong("last_event_at", 0L); }

    /** Raw bounded event trail as stored (oldest first); "" when nothing recorded yet. */
    static String logRing(Context c) { return sp(c).getString("log_ring", ""); }

    /**
     * Diagnostics only, written very frequently (every recheck / screen event) from
     * the main thread. Uses apply() so it never fsyncs the UI thread; losing the
     * last diagnostic string on a crash is acceptable, unlike accounting data.
     *
     * The ring is appended here on purpose: this is the single funnel every
     * surface already writes through, so history is captured without touching a
     * single call site. Non-events (null, blank, the "-" placeholder) are ignored
     * by {@link KioskLogParser#ringPush}. The added cost is one in-memory
     * SharedPreferences read plus one bounded string rebuild per event.
     */
    static void setLastEvent(Context c, String s) {
        SharedPreferences.Editor e = sp(c).edit();
        long at = System.currentTimeMillis();
        e.putString("last_event", s); e.putLong("last_event_at", at);
        if (s != null) {
            String ring = KioskLogParser.ringPush(logRing(c), at, s, LOG_RING_MAX);
            if (ring != null) e.putString("log_ring", ring);
        }
        e.apply();
    }

    // ---------- hardening toggles ----------
    static boolean optScreenOff(Context c)   { return sp(c).getBoolean("o_screenoff", true); }
    static boolean optKeyguard(Context c)    { return sp(c).getBoolean("o_keyguard", true); }
    // Conservative defaults: the user opts IN to the invasive locks. Leaving
    // these off avoids surprising device-wide policy when the owner is set.
    static boolean optSafeBoot(Context c)    { return sp(c).getBoolean("o_safeboot", false); }
    static boolean optFactoryReset(Context c){ return sp(c).getBoolean("o_factory", false); }
    static boolean optDisableDebugging(Context c){ return sp(c).getBoolean("o_nodebug", false); }
    // OFF by default: disabling app-standby device-wide is a battery/glitch risk.
    static boolean optStandby(Context c)     { return sp(c).getBoolean("o_standby", false); }
    // ON by default: the live per-second remaining-time countdown in the status
    // bar. Costs nothing while the screen is off and nothing per second while it
    // is on (the redraw is done natively by SystemUI off the chronometer fields).
    static boolean optCountdown(Context c)   { return sp(c).getBoolean("o_countdown", true); }

    static void setOpt(Context c, String key, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean(key, v); put(e);
    }

    // ---------- onboarding pre-requisite acknowledgement ----------
    //
    // Set once the operator confirms they have read (and, where needed, performed)
    // the factory-reset / remove-all-accounts / disable-auto-sync / reboot warning
    // shown on the very first screen. Purely informational: it gates the CONTINUE
    // button so the warning can never be dismissed by accident, and it is stored
    // as an ordinary hardening-style boolean key so no schema change is needed.
    static boolean prereqAck(Context c) { return sp(c).getBoolean("o_prereq_ack", false); }

    // ---------- onboarding autostart attestation ----------
    // OEM autostart whitelists expose no public read API, so the onboarding
    // ladder records the operator's own confirmation once they return from the
    // vendor autostart screen it opened. See PermissionFlow.isAutostartOk().
    static boolean autostartAttested(Context c) {
        return sp(c).getBoolean("o_autostart_attested", false);
    }
    static void setAutostartAttested(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("o_autostart_attested", v); put(e);
    }

    static void setPrereqAck(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("o_prereq_ack", v); put(e);
    }

    // ---------- durable-store schema / migration ----------
    //
    // The "database" for this app is this SharedPreferences file. These accessors
    // give the migration module (DbMigration) a schema version and an audit record,
    // plus a place to remember whether the upgraded install had kiosk mode on.
    static int schemaVersion(Context c) { return sp(c).getInt("schema_version", 0); }

    static boolean migrationDone(Context c) { return sp(c).getBoolean("mig_done", false); }

    static int migrationFrom(Context c) { return sp(c).getInt("mig_from", -1); }

    static long migratedAtMs(Context c) { return sp(c).getLong("mig_at", 0L); }

    /** Records the completed migration atomically (from-version, schema, timestamp, done). */
    static void setMigrationRecord(Context c, int from, int to, long atMs) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putInt("mig_from", from);
        e.putInt("schema_version", to);
        e.putLong("mig_at", atMs);
        e.putBoolean("mig_done", true);
        put(e);
    }

    /** Whether the pre-upgrade install was detected to have had kiosk/auto-lock enabled. */
    static boolean legacyKiosk(Context c) { return sp(c).getBoolean("legacy_kiosk", false); }
    static void setLegacyKiosk(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("legacy_kiosk", v); put(e);
    }

    // ---------- first-boot re-configuration window ----------
    static boolean reconfigArmed(Context c) { return sp(c).getBoolean("reconfig_armed", false); }
    static boolean reconfigConsumed(Context c) { return sp(c).getBoolean("reconfig_consumed", false); }
    static long reconfigDeadlineMs(Context c) { return sp(c).getLong("reconfig_deadline", 0L); }

    static void setReconfigWindow(Context c, boolean armed, long deadlineMs) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putBoolean("reconfig_armed", armed);
        e.putLong("reconfig_deadline", deadlineMs);
        put(e);
    }

    static void setReconfigConsumed(Context c, boolean v) {
        SharedPreferences.Editor e = sp(c).edit(); e.putBoolean("reconfig_consumed", v); put(e);
    }

    // ---------- crash-safe raw readers (used by the migration only) ----------
    //
    // A legacy build may have written any key with an unexpected type. These read
    // straight from the raw map and coerce defensively so a single odd value can
    // never throw and wedge a boot receiver / foreground service.
    static boolean rawHas(Context c, String key) { return sp(c).contains(key); }

    static Object rawGet(Context c, String key) { return sp(c).getAll().get(key); }

    static boolean rawBoolean(Context c, String key, boolean def) {
        Object o = rawGet(c, key);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean(((String) o).trim());
        if (o instanceof Number) return ((Number) o).longValue() != 0L;
        return def;
    }

    static long rawLong(Context c, String key, long def) {
        Object o = rawGet(c, key);
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong(((String) o).trim()); } catch (Throwable t) { return def; }
        }
        if (o instanceof Boolean) return ((Boolean) o) ? 1L : 0L;
        return def;
    }

    static int rawInt(Context c, String key, int def) {
        long v = rawLong(c, key, def);
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    static void rawRemove(Context c, String key) {
        SharedPreferences.Editor e = sp(c).edit(); e.remove(key); put(e);
    }
}
