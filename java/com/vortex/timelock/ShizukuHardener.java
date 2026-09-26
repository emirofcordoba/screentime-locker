package com.vortex.timelock;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * ============================================================================
 *  PERMANENT permission / autostart / battery hardener
 * ============================================================================
 *
 * The last mile of the "once granted, never ungrantable" guarantee. The
 * Device-Owner policy layer in {@link Engine#enforcePermissionPermanence} pins
 * every runtime permission (notification included), blocks uninstall /
 * force-stop / clear-data and disables App Standby. It cannot, however, install
 * the two states Android exposes NO DevicePolicyManager setter for:
 *
 *   - the Doze battery-optimization EXEMPTION (deviceidle whitelist), and
 *   - the OEM "autostart" / background-execution allow-list.
 *
 * Both of those ARE written by the shell, and this app already owns a
 * privileged shell through the bundled Shizuku client. So this class performs
 * ONE idempotent shell pass over that channel:
 *
 *   cmd deviceidle whitelist +&lt;pkg&gt;         battery-optimization exemption,
 *                                            persisted by the framework in
 *                                            deviceidle.xml and therefore
 *                                            permanent across reboots
 *   am set-standby-bucket &lt;pkg&gt; active       never bucketed into App Standby
 *   cmd appops set &lt;pkg&gt; RUN_IN_BACKGROUND allow
 *   cmd appops set &lt;pkg&gt; RUN_ANY_IN_BACKGROUND allow
 *                                            the AOSP background-run allow-list
 *                                            that OEM "autostart" switches drive
 *   cmd appops set &lt;pkg&gt; USE_FULL_SCREEN_INTENT allow
 *   cmd appops set &lt;pkg&gt; SCHEDULE_EXACT_ALARM allow
 *   cmd appops set &lt;pkg&gt; POST_NOTIFICATION allow
 *                                            the SPECIAL-ACCESS appops a user can
 *                                            otherwise flip back from Settings ->
 *                                            Special app access (full-screen lock
 *                                            intent, the exact-alarm timer behind
 *                                            the countdown, the notification
 *                                            channel). DPM has no setter for them,
 *                                            the shell does, and an appop set by
 *                                            the shell is fixed policy.
 *   pm grant &lt;pkg&gt; &lt;perm&gt;                  belt-and-braces grant of every
 *                                            declared runtime permission
 *
 * ----------------------------------------------------------------------------
 *  BATTERY-FREE BY CONSTRUCTION
 * ----------------------------------------------------------------------------
 *  - Runs ONLY from the existing, already-rare event hooks (boot, the idle /
 *    charging JobScheduler reconciliation, a Doze / Battery-Saver posture
 *    change, an owner event, a lock-state change). There is NO new timer, NO
 *    alarm, NO polling loop and NO wakelock anywhere in here.
 *  - A single short-lived daemon thread carries the pass, so no caller (least
 *    of all a BroadcastReceiver's main thread) is ever blocked, and no worker
 *    thread is kept alive when idle.
 *  - A cooldown collapses bursts of events into at most one pass, and the pass
 *    bails out instantly when Shizuku is not running / not authorised, so on a
 *    device without Shizuku the whole class costs a couple of boolean reads.
 *
 * Every call is owner/admin gated and individually wrapped: a missing binder,
 * a revoked permission or any RemoteException degrades to a log line, never a
 * crash or a wedged boot.
 */
final class ShizukuHardener {

    static final String TAG = "TL.PermaHarden";

    /** Collapse a burst of events into at most one shell pass in this window. */
    private static final long COOLDOWN_MS = 90_000L;

    /** elapsedRealtime() of the last started pass; 0 = none yet. */
    private static volatile long sLastPassMs = 0L;

    private ShizukuHardener() {}

    /**
     * Kick off a (throttled) permanence pass. Non-blocking, owner/admin-gated
     * and a free no-op when the app holds neither device admin nor Device Owner.
     */
    static void hardenAsync(Context c) {
        if (c == null) return;
        final Context app;
        try {
            app = c.getApplicationContext();
        } catch (Throwable t) {
            return;
        }
        if (app == null) return;
        // The user's condition: the app must actually hold admin / ownership.
        if (!Engine.isDeviceOwner(app) && !Engine.isAdminActive(app)) return;

        long now = SystemClock.elapsedRealtime();
        long last = sLastPassMs;
        if (last != 0L && now - last < COOLDOWN_MS) return;
        sLastPassMs = now;

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    pass(app);
                } catch (Throwable e) {
                    Log.w(TAG, "harden pass", e);
                }
            }
        }, "tl-permaharden");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            Log.w(TAG, "start harden thread", e);
        }
    }

    /**
     * The single idempotent shell pass. Cheap to repeat: every command sets an
     * absolute state, so re-running is harmless. Silently returns when the
     * Shizuku channel is unusable (the DPM layer already did its part).
     */
    private static void pass(Context c) {
        if (!ShizukuBridge.isServiceAlive() || !ShizukuBridge.isPermissionGranted()) {
            // No privileged shell available: the Device-Owner policy layer is the
            // guarantee of record. Nothing to do here, and nothing to log loudly.
            return;
        }

        final String pkg = c.getPackageName();
        StringBuilder log = new StringBuilder("perma:harden");

        // 1. Battery: the Doze / battery-optimization exemption, written straight
        //    into the framework's deviceidle.xml whitelist -> permanent.
        run(log, "battery", "cmd deviceidle whitelist +" + pkg);

        // 2. Never bucketed into App Standby.
        run(log, "bkt", "am set-standby-bucket " + pkg + " active");

        // 3. Background / "autostart" allow-list (the AOSP half of what an OEM
        //    autostart switch controls).
        run(log, "bg", "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
        run(log, "bgA", "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");

        // 3b. SPECIAL-ACCESS appops the user CAN revoke from Settings -> Special app
        //     access, and which DevicePolicyManager exposes no setter for. Pinning
        //     them here is what extends "once granted, never ungrantable" beyond the
        //     DPM-pinned runtime permissions to the full-screen lock intent, the
        //     exact-alarm timer that drives the countdown, and the notification
        //     channel itself. An appop written by the shell is fixed policy: the
        //     Settings toggle turns grey and the state survives a reboot, an app
        //     update and a Settings visit.
        run(log, "fsi", "cmd appops set " + pkg + " USE_FULL_SCREEN_INTENT allow");
        run(log, "alarm", "cmd appops set " + pkg + " SCHEDULE_EXACT_ALARM allow");
        run(log, "notif", "cmd appops set " + pkg + " POST_NOTIFICATION allow");

        // 4. Belt-and-braces: grant every declared runtime permission (notification
        //    included) at the shell level as well, so the pin survives even if the
        //    Device-Owner grant state is ever reset.
        List<String> perms = Engine.declaredRuntimePermissions(c);
        for (String p : perms) {
            if (p == null) continue;
            run(log, "grant", "pm grant " + pkg + " " + p);
        }

        try {
            Prefs.setLastEvent(c, log.toString());
        } catch (Throwable t) {
            Log.w(TAG, "log", t);
        }
        Log.i(TAG, log.toString());
    }

    /** Run one command, recording a compact ok/no token into [log]. */
    private static void run(StringBuilder log, String label, String cmd) {
        boolean ok = false;
        try {
            ShizukuBridge.Exec e = ShizukuBridge.run(cmd);
            ok = e != null && e.ran() && e.ok;
        } catch (Throwable t) {
            Log.w(TAG, cmd, t);
        }
        log.append(' ').append(label).append(ok ? "=ok" : "=no");
    }
}
