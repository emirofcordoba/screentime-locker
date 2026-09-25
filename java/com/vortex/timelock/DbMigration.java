package com.vortex.timelock;

import android.content.Context;
import android.util.Log;

/**
 * Durable-store ("database") migration + first-boot re-configuration window.
 *
 * <p>This build introduces an explicit, schema-versioned migration for the one
 * durable store the app actually has: the {@code tl_state} SharedPreferences
 * file (see {@link Prefs}). There is no SQLite database in this project, so the
 * "database migration" here means: read the legacy keys an older build may have
 * written, translate them into this build's canonical keys, record the schema
 * version, and — if a legacy install had kiosk / auto-lock enabled — open a
 * single, one-shot window during which auto-locking is paused so the owner can
 * re-configure the options exactly once before the permanent lock resumes.
 *
 * <h3>Zero-battery contract</h3>
 * Everything in this class is a pure, synchronous, in-process operation over a
 * small SharedPreferences file:
 * <ul>
 *   <li>no {@code AlarmManager}, no {@code WakeLock}, no timers, no polling;</li>
 *   <li>no service / activity / job is ever started from here;</li>
 *   <li>read-only when there is nothing to migrate;</li>
 *   <li>the whole thing is a handful of key lookups, so it costs no measurable
 *       CPU and never wakes the device.</li>
 * </ul>
 * The re-configuration window is <em>not</em> a background timer: it is a stored
 * deadline that is consulted lazily by a pure predicate
 * ({@link #isReconfigPauseActive(Context, long)}) at the single auto-lock choke
 * point ({@link Engine#shouldBeLocked(Context, long)}). When the deadline passes,
 * the very next evaluation seals the window. While no one is asking, nothing runs.
 *
 * <h3>Safety</h3>
 * Every entry point is fully wrapped in {@code try/catch(Throwable)} — a corrupt
 * or partially-written store must never crash a boot receiver or a foreground
 * service. The module only ever touches this app's own preferences and its own
 * launcher alias; it applies no device policy and requires no Device Owner.
 */
final class DbMigration {

    static final String TAG = "TL.DbMigration";

    /** Schema version this build writes into the durable store. */
    static final int SCHEMA_VERSION = 1;
    /** Schema version attributed to pre-migration (legacy) installs. */
    static final int LEGACY_SCHEMA_VERSION = 0;

    /** How long the one-time re-configuration window stays open. */
    static final long RECONFIG_WINDOW_MS = 24L * 3600_000L;
    /** Upper bound applied to any legacy daily limit carried forward. */
    static final long MAX_LIMIT_MS = 24L * 3600_000L;

    // ---- canonical (this-build) migration state keys ----
    private static final String K_SCHEMA    = "schema_version";
    private static final String K_DONE      = "mig_done";
    private static final String K_FROM      = "mig_from";
    private static final String K_AT        = "mig_at";
    private static final String K_LEGACY_K  = "legacy_kiosk";
    private static final String K_ARMED     = "reconfig_armed";
    private static final String K_CONSUMED  = "reconfig_consumed";
    private static final String K_DEADLINE  = "reconfig_deadline";

    /**
     * Historic key names an older build may have used to persist "kiosk mode is
     * enabled". An explicit value at any of these keys always wins over inference.
     */
    private static final String[] LEGACY_KIOSK_KEYS = {
            "kiosk_enabled", "kiosk_mode", "kiosk_on", "kiosk", "auto_lock", "lock_enabled"
    };

    /** Legacy keys that stored the daily limit directly in milliseconds. */
    private static final String[] LEGACY_LIMIT_MS_KEYS = {
            "daily_limit_ms", "limit_msv1"
    };

    /** Legacy keys that stored the daily limit in minutes. */
    private static final String[] LEGACY_LIMIT_MIN_KEYS = {
            "limit_minutes", "daily_limit_minutes"
    };

    /**
     * Keys whose presence proves the store belongs to a configured install
     * (as opposed to a freshly installed, never-set-up app). A brand-new install
     * must never open a re-configuration window.
     */
    private static final String[] PRIOR_STATE_KEYS = {
            "activated", "settings_locked", "limit_ms", "window_start",
            "acc", "anchor_min", "transferred"
    };

    private DbMigration() {}

    // =====================================================================
    // Entry point
    // =====================================================================

    /**
     * Idempotent, side-effect-bounded entry point. Safe to call from any
     * component, at any time, on any thread, before or after configuration.
     *
     * <p>Order matters: we first finish any pending migration, then reconcile a
     * lapsed window (sealing it), then apply the window's current effect. This
     * guarantees that a window which lapsed while the device was off cannot be
     * "resurrected" by a later step in the same call.
     */
    static void run(Context c) {
        if (c == null) return;
        try {
            migrateIfNeeded(c);
            reconcileWindow(c);
            applyOpenWindow(c);
        } catch (Throwable t) {
            Log.e(TAG, "migration run failed", t);
        }
    }

    // =====================================================================
    // Queries / one-shot consumption
    // =====================================================================

    /**
     * PURE predicate: is the one-time re-configuration window currently in force?
     * No side effects, cheap, safe to call from the hottest path (it is invoked
     * from {@link Engine#shouldBeLocked(Context, long)}, which runs on every
     * screen event and every recheck).
     *
     * @return {@code true} only when a window was armed, has not yet been
     *         consumed, and (if it carries a deadline) has not yet lapsed.
     */
    static boolean isReconfigPauseActive(Context c, long nowMs) {
        try {
            if (c == null) return false;
            if (!Prefs.reconfigArmed(c)) return false;
            if (Prefs.reconfigConsumed(c)) return false;
            long deadline = Prefs.reconfigDeadlineMs(c);
            if (deadline <= 0L) return true;      // open-ended (defensive default)
            return nowMs < deadline;
        } catch (Throwable t) {
            return false;                          // fail closed: never block locking
        }
    }

    /** True when a legacy install was detected to have had kiosk / auto-lock on. */
    static boolean legacyKioskEnabled(Context c) {
        try {
            return c != null && Prefs.legacyKiosk(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Seal the window after the owner has re-configured. Idempotent. Also
     * re-conceals the launcher alias so the device returns to its permanent,
     * hidden, locked-in posture the moment re-configuration is finished.
     */
    static void consumeReconfigPause(Context c) {
        if (c == null) return;
        try {
            if (!Prefs.reconfigArmed(c) || Prefs.reconfigConsumed(c)) return;
            Prefs.setReconfigConsumed(c, true);
            Prefs.setReconfigWindow(c, false, 0L);
            Engine.setLauncherVisible(c, false);
            Log.i(TAG, "reconfiguration window consumed");
        } catch (Throwable t) {
            Log.e(TAG, "consumeReconfigPause failed", t);
        }
    }

    /** Compact, human-readable diagnostics string (for the admin console). */
    static String summary(Context c) {
        if (c == null) return "migration: n/a";
        try {
            return new StringBuilder(96)
                    .append("schema=").append(Prefs.schemaVersion(c))
                    .append(" from=").append(Prefs.migrationFrom(c))
                    .append(" done=").append(Prefs.migrationDone(c))
                    .append(" at=").append(Prefs.migratedAtMs(c))
                    .append(" legacyKiosk=").append(Prefs.legacyKiosk(c))
                    .append(" armed=").append(Prefs.reconfigArmed(c))
                    .append(" consumed=").append(Prefs.reconfigConsumed(c))
                    .append(" deadline=").append(Prefs.reconfigDeadlineMs(c))
                    .toString();
        } catch (Throwable t) {
            return "migration: err";
        }
    }

    // =====================================================================
    // Migration internals
    // =====================================================================

    /** Runs the store migration exactly once per install (until the schema advances). */
    private static void migrateIfNeeded(Context c) {
        if (Prefs.migrationDone(c) && Prefs.schemaVersion(c) >= SCHEMA_VERSION) return;

        long now = System.currentTimeMillis();
        boolean priorState = storeHasPriorState(c);
        boolean legacyKiosk = detectLegacyKiosk(c, priorState);

        // Carry a legacy limit forward so an upgrading user is not silently reset
        // to "no limit" (which would disable locking for them entirely). Never
        // clobber a limit this build already understands.
        if (!Prefs.rawHas(c, "limit_ms")) {
            carryLegacyLimit(c);
        }

        Prefs.setLegacyKiosk(c, legacyKiosk);
        Prefs.setMigrationRecord(c, LEGACY_SCHEMA_VERSION, SCHEMA_VERSION, now);
        // NOTE: we deliberately do NOT flip "activated" here. The pause gate is
        // independent of it, and a migration must never turn a dormant install
        // into an enforcing one.
        Log.i(TAG, "migrated schema 0->" + SCHEMA_VERSION
                + " priorState=" + priorState + " legacyKiosk=" + legacyKiosk);

        if (legacyKiosk) {
            openWindow(c, now);
        }
    }

    /** True when the store shows any evidence of a previously configured install. */
    private static boolean storeHasPriorState(Context c) {
        for (String k : PRIOR_STATE_KEYS)      if (Prefs.rawHas(c, k)) return true;
        for (String k : LEGACY_KIOSK_KEYS)     if (Prefs.rawHas(c, k)) return true;
        for (String k : LEGACY_LIMIT_MS_KEYS)  if (Prefs.rawHas(c, k)) return true;
        for (String k : LEGACY_LIMIT_MIN_KEYS) if (Prefs.rawHas(c, k)) return true;
        return false;
    }

    /**
     * Decide whether the pre-upgrade install had kiosk / auto-lock enabled.
     * An explicit legacy flag always wins; otherwise we infer from the two keys
     * that a legacy build set as a side effect of "locking the device in".
     */
    private static boolean detectLegacyKiosk(Context c, boolean priorState) {
        if (!priorState) return false;              // fresh install: never pause
        for (String k : LEGACY_KIOSK_KEYS) {
            if (Prefs.rawHas(c, k)) {
                return Prefs.rawBoolean(c, k, false);
            }
        }
        // No explicit flag: infer from other evidence of a locked-in install.
        if (Prefs.settingsLocked(c)) return true;
        if (Prefs.activated(c)) return true;
        return false;
    }

    /**
     * Translate a legacy daily-limit value into this build's canonical
     * {@code limit_ms} key. Millisecond keys are preferred over minute keys.
     */
    private static void carryLegacyLimit(Context c) {
        for (String k : LEGACY_LIMIT_MS_KEYS) {
            if (Prefs.rawHas(c, k)) {
                long v = clampLimit(Prefs.rawLong(c, k, 0L));
                if (v > 0L) { Prefs.setLimitMs(c, v); Log.i(TAG, "carried limit_ms from " + k); return; }
            }
        }
        for (String k : LEGACY_LIMIT_MIN_KEYS) {
            if (Prefs.rawHas(c, k)) {
                long mins = Prefs.rawLong(c, k, 0L);
                if (mins > 0L) {
                    long v = clampLimit(mins * 60_000L);
                    if (v > 0L) { Prefs.setLimitMs(c, v); Log.i(TAG, "carried limit from " + k + " (" + mins + " min)"); return; }
                }
            }
        }
    }

    private static long clampLimit(long ms) {
        if (ms < 0L) return 0L;
        if (ms > MAX_LIMIT_MS) return MAX_LIMIT_MS;
        return ms;
    }

    /** Arm the one-shot window. Never extends or re-arms an existing/consumed one. */
    private static void openWindow(Context c, long now) {
        if (Prefs.reconfigConsumed(c)) return;     // one chance only, already used
        if (Prefs.reconfigArmed(c)) return;        // already open: leave the deadline alone
        long deadline = now + RECONFIG_WINDOW_MS;
        Prefs.setReconfigWindow(c, true, deadline);
        Log.i(TAG, "legacy kiosk detected: auto-lock paused until " + deadline);
    }

    /** Seal a window that lapsed unused, so the permanent lock can resume. */
    private static void reconcileWindow(Context c) {
        if (!Prefs.reconfigArmed(c)) return;
        if (Prefs.reconfigConsumed(c)) return;
        long deadline = Prefs.reconfigDeadlineMs(c);
        if (deadline > 0L && System.currentTimeMillis() >= deadline) {
            Prefs.setReconfigConsumed(c, true);
            Prefs.setReconfigWindow(c, false, 0L);
            Engine.setLauncherVisible(c, false);
            Log.i(TAG, "reconfiguration window lapsed");
        }
    }

    /**
     * While the window is in force, make sure the owner can actually reach the
     * configuration surface by re-showing this app's own launcher alias. This
     * applies no device policy, starts nothing, and is a no-op once the window
     * is closed.
     */
    private static void applyOpenWindow(Context c) {
        if (!isReconfigPauseActive(c, System.currentTimeMillis())) return;
        Engine.setLauncherVisible(c, true);
    }
}
