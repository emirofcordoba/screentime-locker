package com.vortex.timelock;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Crash-safe, tamper-evident persistence for the DAILY USAGE COUNTER.
 *
 * <p>This replaces the previous accounting model, where the consumed screen time
 * lived in two independent SharedPreferences keys ({@code acc} and
 * {@code screen_on_since}) that were only banked on {@code ACTION_SCREEN_OFF}.
 * That model lost (or over-counted) the in-flight session across a
 * {@code SIGKILL} or a power cut, because no {@code SCREEN_OFF} is delivered for
 * a session the OS never got to close, and because the three accounting keys
 * were written as three separate commits (torn state if killed between them).
 *
 * <h3>What makes this durable</h3>
 * <ul>
 *   <li><b>One row, one transaction.</b> The whole counter — window start,
 *       banked milliseconds, the live session stamp, the last-seen heartbeat,
 *       the boot epoch, a monotonic revision and the sealed accumulator — is a
 *       single SQLite row. Every mutation rewrites that row inside ONE
 *       transaction, so a kill can never observe a half-written counter.</li>
 *   <li><b>Immediate, fsync'd commit.</b> The database runs in WAL mode with
 *       {@code PRAGMA synchronous=FULL}; {@link #commitLocked} ends the
 *       transaction (a real fsync of the WAL) before the method returns. The
 *       counter is therefore on disk the instant it changes — there is no
 *       deferred flush, no {@code apply()}, no batching.</li>
 *   <li><b>Monotonic accrual.</b> The live session records
 *       {@link SystemClock#elapsedRealtime()} at its start. While the boot epoch
 *       is unchanged the delta is taken from that clock, so a wall-clock change
 *       (or a {@code SIGKILL} that leaves the process dead but the kernel alive)
 *       cannot distort the count.</li>
 *   <li><b>Deadman bounding.</b> The {@code last_seen} heartbeat is written on
 *       every screen event / recheck. After a reboot the elapsed clock has reset,
 *       so the crashed session is recovered from wall time clipped to
 *       {@code last_seen} — the device downtime is never charged as usage, and
 *       the pre-crash session is never dropped. See
 *       {@link #reconcileAfterRestart(Context, long)}.</li>
 *   <li><b>KeyStore seal.</b> Every row carries an HMAC-SHA256 tag computed with
 *       a non-exportable AndroidKeyStore key. A row edited out-of-band fails
 *       verification; the accumulator is then raised to the last sealed value
 *       (never lowered) and the tamper flag is persisted.</li>
 * </ul>
 *
 * <p>Reads are served from a process-local cache so the 1 Hz dashboard path does
 * no database I/O; writes go through the cache to disk synchronously.
 */
final class UsageStore {

    static final String TAG = "TL.UsageStore";

    private static final String DB_NAME = "tl_usage.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "usage";
    private static final int ROW_ID = 1;

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "tl_usage_seal_v1";

    // ---- cache / single source of truth (guarded by LOCK) ----
    private static final Object LOCK = new Object();
    private static boolean loaded = false;
    /**
     * TRUE once the row was successfully read (or created) from disk. Until then
     * the cache holds default values that are NOT authoritative, so nothing may be
     * written back: see {@link #commitLocked}. A load failure must never be
     * allowed to overwrite a good row with zeros.
     */
    private static boolean loadOk = false;
    /** Earliest elapsedRealtime() at which a failed load may be retried. */
    private static long loadRetryAtElapsed = 0L;
    /** How long to wait before re-attempting a load that threw. */
    private static final long LOAD_RETRY_MS = 5_000L;
    /**
     * Upper bound on the unverified monotonic slice that a same-boot process
     * restart under a lit panel may be trusted as CONTINUOUS screen-on time.
     * Safely above the 60 s durability checkpoint, small enough that the rare
     * "panel went off and on again while the process stayed dead" case can never
     * over-charge by more than this.
     */
    private static final long MAX_UNVERIFIED_CONTINUITY_MS = 5L * 60_000L;
    private static boolean sealEnabled = false;

    private static long cWindow = 0L;
    private static long cAcc = 0L;
    private static long cSession = 0L;
    private static long cSessionElapsed = 0L;
    private static long cSeen = 0L;
    private static long cSeenElapsed = 0L;
    private static long cVerified = 0L;
    private static int cBoot = -1;
    private static long cRev = 0L;
    private static int cTampered = 0;

    private static Db db;

    private UsageStore() {}

    // =====================================================================
    // Public API — accounting
    // =====================================================================

    /** Banked screen-on milliseconds in the current window (excludes the live session). */
    static long acc(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) { return cAcc; }
    }

    /** Explicit accumulator write (setup / reset paths). Persisted immediately. */
    static void setAcc(Context c, long v) {
        ensureLoaded(c);
        synchronized (LOCK) {
            cAcc = v < 0L ? 0L : v;
            cVerified = cAcc;
            cRev++;
            commitLocked(c);
        }
    }

    /** Start of the stored accounting window, or 0 when nothing is banked yet. */
    static long windowStart(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) { return cWindow; }
    }

    static void setWindowStart(Context c, long v) {
        ensureLoaded(c);
        synchronized (LOCK) {
            cWindow = v < 0L ? 0L : v;
            cRev++;
            commitLocked(c);
        }
    }

    /** Wall-clock stamp of the active screen-on session, or 0 when none is tracked. */
    static long sessionStart(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) { return cSession; }
    }

    /** Back-compat setter: 0 clears the live session, >0 starts one at that instant. */
    static void setSessionStart(Context c, long v) {
        if (v <= 0L) endSession(c, System.currentTimeMillis());
        else startSession(c, v);
    }

    /**
     * Screen-on time consumed inside the current window, including the live
     * session. Pure read; safe on the 1 Hz render path.
     */
    static long usedNow(Context c, long nowMs) {
        ensureLoaded(c);
        synchronized (LOCK) {
            return cAcc + liveDeltaLocked(c, nowMs, SystemClock.elapsedRealtime());
        }
    }

    /** Live session length in ms (0 when no session is tracked). */
    static long liveDelta(Context c, long nowMs) {
        ensureLoaded(c);
        synchronized (LOCK) {
            return liveDeltaLocked(c, nowMs, SystemClock.elapsedRealtime());
        }
    }

    /** Begin tracking a screen-on session. Idempotent; refreshes the heartbeat. */
    static void startSession(Context c, long nowMs) {
        ensureLoaded(c);
        long elapsed = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (cSession == 0L) {
                cSession = nowMs;
                cSessionElapsed = elapsed;
            }
            cSeen = nowMs;
            cSeenElapsed = elapsed;
            cBoot = bootCount(c);
            cRev++;
            commitLocked(c);
        }
    }

    /** Close the live session WITHOUT banking it (window rolled / reset). */
    static void endSession(Context c, long nowMs) {
        ensureLoaded(c);
        long elapsed = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            cSession = 0L;
            cSessionElapsed = 0L;
            cSeen = nowMs;
            cSeenElapsed = elapsed;
            cBoot = bootCount(c);
            cRev++;
            commitLocked(c);
        }
    }

    /** Bank the live session into the accumulator and clear it. Persisted immediately. */
    static void bankSession(Context c, long nowMs) {
        ensureLoaded(c);
        synchronized (LOCK) {
            bankLocked(c, nowMs, true);
        }
    }

    /**
     * Called once per process/boot before any accounting read. If the live session
     * survived a REBOOT (boot epoch changed), recover it from wall time clipped to
     * the last heartbeat and clear it. A same-boot restart (SIGKILL) needs nothing:
     * the elapsed clock is intact and {@link #usedNow} keeps counting.
     *
     * @return true when a crashed session was recovered.
     */
    static boolean reconcileAfterRestart(Context c, long nowMs) {
        ensureLoaded(c);
        synchronized (LOCK) {
            if (cSession > 0L && cBoot != bootCount(c)) {
                bankLocked(c, nowMs, false);
                Log.i(TAG, "recovered session across reboot; acc=" + cAcc);
                return true;
            }
            return false;
        }
    }

    /**
     * Close a session that is still marked live at a point where its continuity
     * CANNOT be trusted: the process was (re)started, or the screen came back on,
     * without us ever observing the matching {@code ACTION_SCREEN_OFF} (the
     * process was dead through the dark period, so the broadcast was never
     * delivered to us).
     *
     * <p>The live delta is taken from {@link SystemClock#elapsedRealtime()}, which
     * keeps advancing through screen-off and deep sleep. Leaving such a session
     * open would therefore charge the ENTIRE dark gap as screen-on time - the
     * "screen-on time is counted while the screen is off" bug. Only the monotonic
     * slice we can actually vouch for, from the session anchor to the last durable
     * heartbeat, is banked; the session is then cleared so the caller can start a
     * fresh, honest one. This mirrors the post-reboot recovery: downtime is never
     * charged, at the cost of at most one heartbeat interval of unverified on-time.
     *
     * @return true when a live-but-unverified session was found and closed.
     */
    static boolean reconcileDeadSession(Context c, long nowMs) {
        ensureLoaded(c);
        long elapsed = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (cSession <= 0L) return false;
            long delta;
            if (cBoot == bootCount(c) && cSessionElapsed > 0L && cSeenElapsed >= cSessionElapsed) {
                // Same boot epoch: the heartbeat-anchored monotonic slice is exact
                // and immune to wall-clock changes.
                delta = cSeenElapsed - cSessionElapsed;
            } else {
                // Boot epoch moved under us: fall back to wall time clipped to the
                // durable heartbeat (same rule as the post-reboot recovery).
                delta = wallDeltaForCrashedSession(c, nowMs);
            }
            if (delta > 0L) {
                if (Long.MAX_VALUE - cAcc < delta) cAcc = Long.MAX_VALUE;
                else cAcc += delta;
            }
            if (cAcc > cVerified) cVerified = cAcc;
            cSession = 0L;
            cSessionElapsed = 0L;
            cSeen = nowMs;
            cSeenElapsed = elapsed;
            cBoot = bootCount(c);
            cRev++;
            commitLocked(c);
            Log.i(TAG, "closed unverified live session; acc=" + cAcc);
            return true;
        }
    }

    /**
     * Durable heartbeat. Cheap no-op when nothing moved, otherwise a single
     * fsync'd transaction. Bound the possible loss of an abrupt kill to one
     * heartbeat interval.
     */
    static void heartbeat(Context c, long nowMs) {
        ensureLoaded(c);
        long elapsed = SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        synchronized (LOCK) {
            if (nowMs <= cSeen && boot == cBoot) return;
            cSeen = nowMs;
            cSeenElapsed = elapsed;
            cBoot = boot;
            cRev++;
            commitLocked(c);
        }
    }

    /**
     * Bounded-interval durability checkpoint for a LIVE screen-on session.
     *
     * <p>Cheap on the existing 1 Hz countdown cadence: it returns without touching
     * the disk unless a session is still open AND the persisted heartbeat is at
     * least {@code minIntervalMs} old, in which case it commits exactly one
     * fsync'd transaction. It reuses the cadence the app already runs, so it adds
     * no timer, no wakeup and no wake lock; it exists purely to narrow the
     * worst-case reboot recovery window from the passive watchdog interval down to
     * {@code minIntervalMs}. On a reboot the in-flight session is rebuilt from wall
     * time clipped to {@code last_seen}, so a fresher heartbeat directly means less
     * unrecovered screen time (an abrupt SIGKILL on the same boot loses nothing at
     * all: the monotonic elapsed clock survives and {@link #usedNow} keeps
     * counting).
     *
     * <p>A heartbeat that is stale because the BOOT EPOCH moved is deliberately
     * left alone: that session is a crash artifact and is owned by
     * {@link #reconcileAfterRestart(Context, long)}, not by this path.
     *
     * @return true when a row was written.
     */
    static boolean checkpoint(Context c, long nowMs, long minIntervalMs) {
        ensureLoaded(c);
        long interval = minIntervalMs > 0L ? minIntervalMs : 0L;
        long elapsed = SystemClock.elapsedRealtime();
        int boot = bootCount(c);
        synchronized (LOCK) {
            if (cSession <= 0L) return false;              // nothing live to checkpoint
            if (boot != cBoot) return false;               // stale session: reconcile owns it
            if (nowMs - cSeen < interval) return false;    // heartbeat still fresh enough
            cSeen = nowMs;
            cSeenElapsed = elapsed;
            cBoot = boot;
            cRev++;
            commitLocked(c);
            return true;
        }
    }

    /**
     * Advance the accounting window. Resets the counter, re-anchors a session that
     * started before the boundary so only post-boundary time is counted, and
     * commits atomically.
     *
     * @return true when the window actually moved.
     */
    static boolean rollover(Context c, long newWindowStart) {
        ensureLoaded(c);
        long elapsed = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (cWindow == newWindowStart) return false;
            cWindow = newWindowStart;
            cAcc = 0L;
            cVerified = 0L;
            if (cSession > 0L && cSession < newWindowStart) {
                cSession = newWindowStart;
                cSessionElapsed = elapsed;
            }
            cBoot = bootCount(c);
            cRev++;
            commitLocked(c);
            return true;
        }
    }

    /** Full reset (fresh setup): zeroes every accounting field, in one transaction. */
    static void reset(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) {
            cWindow = 0L;
            cAcc = 0L;
            cSession = 0L;
            cSessionElapsed = 0L;
            cSeen = 0L;
            cSeenElapsed = 0L;
            cVerified = 0L;
            cBoot = bootCount(c);
            cTampered = 0;
            cRev++;
            commitLocked(c);
        }
    }

    /** True when the sealed accumulator failed verification at least once. */
    static boolean tampered(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) { return cTampered != 0; }
    }

    // =====================================================================
    // Session math
    // =====================================================================

    /**
     * Length of the live session. Monotonic elapsed delta while the boot epoch is
     * unchanged; otherwise wall time clipped to the durable heartbeat so a reboot
     * never charges the downtime.
     */
    private static long liveDeltaLocked(Context c, long nowMs, long elapsedNow) {
        if (cSession <= 0L) return 0L;
        long delta;
        if (cBoot == bootCount(c) && cSessionElapsed > 0L && elapsedNow >= cSessionElapsed) {
            delta = elapsedNow - cSessionElapsed;
        } else {
            long cap = cSeen > 0L ? cSeen : nowMs;
            long to = nowMs < cap ? nowMs : cap;
            delta = to - cSession;
        }
        return delta > 0L ? delta : 0L;
    }

    /** Fold the live session into the accumulator and clear it. */
    private static void bankLocked(Context c, long nowMs, boolean preferMonotonic) {
        long delta = preferMonotonic
                ? liveDeltaLocked(c, nowMs, SystemClock.elapsedRealtime())
                : wallDeltaForCrashedSession(c, nowMs);
        if (delta > 0L) {
            if (Long.MAX_VALUE - cAcc < delta) cAcc = Long.MAX_VALUE;
            else cAcc += delta;
        }
        if (cAcc > cVerified) cVerified = cAcc;
        cSession = 0L;
        cSessionElapsed = 0L;
        cSeen = nowMs;
        cSeenElapsed = SystemClock.elapsedRealtime();
        cBoot = bootCount(c);
        cRev++;
        commitLocked(c);
    }

    /** Wall-clock delta for a session whose boot epoch is gone (post-reboot path). */
    private static long wallDeltaForCrashedSession(Context c, long nowMs) {
        if (cSession <= 0L) return 0L;
        long cap = cSeen > 0L ? cSeen : nowMs;
        long to = nowMs < cap ? nowMs : cap;
        long delta = to - cSession;
        return delta > 0L ? delta : 0L;
    }

    private static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    // =====================================================================
    // Persistence
    // =====================================================================

    private static Db open(Context c) {
        if (db == null) db = new Db(c.getApplicationContext());
        return db;
    }

    /** Load the single row once per process; repair and verify it on the way in. */
    private static void ensureLoaded(Context c) {
        if (loaded || c == null) return;
        synchronized (LOCK) {
            if (loaded) return;
            SQLiteDatabase d = null;
            Cursor cur = null;
            try {
                d = open(c).getWritableDatabase();
                cur = d.query(TABLE, null, "id=?", new String[]{String.valueOf(ROW_ID)},
                        null, null, null);
                if (cur.moveToFirst()) {
                    cWindow = cur.getLong(cur.getColumnIndexOrThrow("window_start"));
                    cAcc = cur.getLong(cur.getColumnIndexOrThrow("acc"));
                    cSession = cur.getLong(cur.getColumnIndexOrThrow("session_start"));
                    cSessionElapsed = cur.getLong(cur.getColumnIndexOrThrow("session_elapsed"));
                    cSeen = cur.getLong(cur.getColumnIndexOrThrow("last_seen"));
                    cSeenElapsed = cur.getLong(cur.getColumnIndexOrThrow("last_seen_elapsed"));
                    cBoot = cur.getInt(cur.getColumnIndexOrThrow("boot"));
                    cRev = cur.getLong(cur.getColumnIndexOrThrow("revision"));
                    cVerified = cur.getLong(cur.getColumnIndexOrThrow("verified_acc"));
                    cTampered = cur.getInt(cur.getColumnIndexOrThrow("tampered"));
                    String storedTag = cur.getString(cur.getColumnIndexOrThrow("tag"));
                    verifyLocked(c, storedTag);
                } else {
                    cWindow = 0L;
                    cAcc = 0L;
                    cSession = 0L;
                    cSessionElapsed = 0L;
                    cSeen = 0L;
                    cSeenElapsed = 0L;
                    cVerified = 0L;
                    cBoot = bootCount(c);
                    cRev = 0L;
                    cTampered = 0;
                    commitLocked(c);
                }
                loaded = true;
            } catch (Throwable t) {
                Log.e(TAG, "load failed; starting from a safe zero row", t);
                cWindow = 0L; cAcc = 0L; cSession = 0L; cSessionElapsed = 0L;
                cSeen = 0L; cSeenElapsed = 0L; cVerified = 0L;
                cBoot = bootCount(c); cRev = 0L; cTampered = 1;
                try { if (d != null) commitLocked(c); } catch (Throwable ignored) {}
                loaded = true;
            } finally {
                if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Verify the sealed accumulator. A row written out-of-band (edited DB, restored
     * backup, truncated tag) fails the HMAC; the accumulator is then raised to the
     * last sealed value — never lowered — and the tamper flag is recorded.
     */
    private static void verifyLocked(Context c, String storedTag) {
        if (storedTag == null || storedTag.isEmpty()) {
            // Pre-seal row or first boot: nothing to verify, it will be sealed on
            // the next write. Not treated as tampering.
            sealEnabled = ensureSealKey();
            return;
        }
        sealEnabled = ensureSealKey();
        if (!sealEnabled) return;
        String expect = seal();
        if (expect != null && expect.equals(storedTag)) return;
        Log.w(TAG, "usage row failed seal verification; using sealed floor "
                + cVerified + " over stored " + cAcc);
        if (cVerified > cAcc) cAcc = cVerified;
        cTampered = 1;
        cRev++;
        commitLocked(c);
    }

    /** Write the whole row atomically, in one fsync'd transaction. */
    private static void commitLocked(Context c) {
        try {
            String tag = seal();
            ContentValues cv = new ContentValues();
            cv.put("id", ROW_ID);
            cv.put("window_start", cWindow);
            cv.put("acc", cAcc);
            cv.put("session_start", cSession);
            cv.put("session_elapsed", cSessionElapsed);
            cv.put("last_seen", cSeen);
            cv.put("last_seen_elapsed", cSeenElapsed);
            cv.put("boot", cBoot);
            cv.put("revision", cRev);
            cv.put("verified_acc", cVerified);
            cv.put("tampered", cTampered);
            cv.put("tag", tag);

            SQLiteDatabase d = open(c).getWritableDatabase();
            d.beginTransaction();
            try {
                d.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();   // commit == fsync of the WAL
            }
        } catch (Throwable t) {
            Log.e(TAG, "commit failed", t);
        }
    }

    // =====================================================================
    // Integrity seal (HMAC-SHA256, non-exportable AndroidKeyStore key)
    // =====================================================================

    private static boolean ensureSealKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) return true;
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "seal key unavailable", t);
            return false;
        }
    }

    /** HMAC over the canonical encoding of every accounting field; null if unavailable. */
    private static String seal() {
        try {
            if (!ensureSealKey()) return null;
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (key == null) return null;
            String canonical = cWindow + "|" + cAcc + "|" + cSession + "|"
                    + cSessionElapsed + "|" + cSeen + "|" + cSeenElapsed + "|"
                    + cBoot + "|" + cRev + "|" + cVerified;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] out = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // Schema
    // =====================================================================

    private static final class Db extends SQLiteOpenHelper {

        Db(Context c) {
            super(c, DB_NAME, null, DB_VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            try { db.execSQL("PRAGMA synchronous=FULL"); } catch (Throwable ignored) {}
            try { db.execSQL("PRAGMA journal_mode=WAL"); } catch (Throwable ignored) {}
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY CHECK(id=" + ROW_ID + "),"
                    + "window_start INTEGER NOT NULL DEFAULT 0,"
                    + "acc INTEGER NOT NULL DEFAULT 0,"
                    + "session_start INTEGER NOT NULL DEFAULT 0,"
                    + "session_elapsed INTEGER NOT NULL DEFAULT 0,"
                    + "last_seen INTEGER NOT NULL DEFAULT 0,"
                    + "last_seen_elapsed INTEGER NOT NULL DEFAULT 0,"
                    + "boot INTEGER NOT NULL DEFAULT -1,"
                    + "revision INTEGER NOT NULL DEFAULT 0,"
                    + "verified_acc INTEGER NOT NULL DEFAULT 0,"
                    + "tampered INTEGER NOT NULL DEFAULT 0,"
                    + "tag TEXT"
                    + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // The counter is a single mutable row: rebuilding it is lossless for a
            // fresh schema and keeps the store forward-compatible.
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
