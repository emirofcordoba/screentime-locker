package com.vortex.timelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * DEVICE-PROTECTED MIRROR of the tiny slice of state that must be readable
 * BEFORE the user unlocks the device.
 *
 * Why this exists: every real piece of state in this app lives in
 * credential-encrypted (CE) storage ({@link Prefs}, {@link UsageStore}). A
 * direct-boot receiver runs before the CE keys are available, so the first CE
 * read there throws and -- historically -- turned into a boot crash-loop. That
 * is exactly why {@link BootReceiver} was deliberately kept out of direct boot.
 *
 * The price of that decision was the "autostart" gap the operator reported:
 * after a restart, until the OEM hands over BOOT_COMPLETED (which many ROMs gate
 * behind a per-app "autostart" whitelist the user has to toggle by hand), the
 * engine could not re-assert the kiosk, so the inmate lock did not come back on
 * its own.
 *
 * The fix is this mirror: a handful of booleans/longs written into the
 * DEVICE-PROTECTED store (encrypted with the device key, available the instant
 * the framework is up, long before unlock). It carries no secrets -- just
 * "was a setup activated" and "was a lock in force, until when" -- so the
 * direct-boot receiver can re-arm the kiosk surface with ZERO permission and
 * ZERO delay, without ever touching CE storage.
 *
 * The mirror is updated from {@link Prefs} at the exact moment a transition is
 * persisted, so it can never drift from the real state.
 */
final class BootState {

    private static final String TAG = "TL.BootState";
    private static final String FILE = "tl_boot";

    private static final String K_ACTIVATED = "activated";
    private static final String K_LOCKED = "locked";
    private static final String K_LOCK_UNTIL = "lock_until";

    private BootState() {}

    /**
     * The device-protected SharedPreferences handle. Falls back to the normal
     * context if the platform refuses to hand one over (e.g. a very old ROM),
     * so a call here can never crash a boot path.
     */
    private static SharedPreferences sp(Context c) {
        Context base = c.getApplicationContext();
        try {
            Context de = base.createDeviceProtectedStorageContext();
            if (de != null) return de.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.w(TAG, "device-protected store unavailable, using default", t);
        }
        return base.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Low-level writer. Never throws. */
    static void write(Context c, boolean activated, boolean locked, long lockUntil) {
        try {
            SharedPreferences.Editor e = sp(c).edit();
            e.putBoolean(K_ACTIVATED, activated);
            e.putBoolean(K_LOCKED, locked);
            e.putLong(K_LOCK_UNTIL, lockUntil);
            e.commit();
        } catch (Throwable t) {
            Log.w(TAG, "write", t);
        }
    }

    /**
     * Snapshot the current CE state into the device-protected mirror. Called
     * from the single choke points where activation / lock state changes, so the
     * mirror always matches what the engine would decide if it could read CE.
     */
    static void mirror(Context c) {
        if (c == null) return;
        try {
            write(c, Prefs.activated(c), Prefs.locked(c), Prefs.lockUntil(c));
        } catch (Throwable t) {
            Log.w(TAG, "mirror", t);
        }
    }

    // ---- pre-unlock readers (device-protected only; never touch CE) ----

    static boolean activated(Context c) {
        try { return sp(c).getBoolean(K_ACTIVATED, false); }
        catch (Throwable t) { return false; }
    }

    static boolean locked(Context c) {
        try { return sp(c).getBoolean(K_LOCKED, false); }
        catch (Throwable t) { return false; }
    }

    static long lockUntil(Context c) {
        try { return sp(c).getLong(K_LOCK_UNTIL, 0L); }
        catch (Throwable t) { return 0L; }
    }
}
