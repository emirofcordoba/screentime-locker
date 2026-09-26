package com.vortex.timelock;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * BatteryGuard — the "a locked phone should be a quiet phone" power policy.
 *
 * <p>{@link PowerGovernor} keeps <em>this app</em> from burning power. This class
 * is the other half of the problem: while the kiosk lock owns the device, the
 * <em>rest of the phone</em> must stop burning power too. Concretely, the moment
 * the lock engages it makes three idempotent changes over the existing
 * privileged shell ({@link ShizukuBridge}, the same uid-2000 channel
 * {@link ShizukuHardener} already uses):
 *
 * <ul>
 *   <li>{@code am kill-all} — stop every background user app, so nothing left
 *       in the background keeps a wakelock, fires an alarm, runs a sync or holds
 *       the radio awake. The foreground kiosk (this app) is untouched.</li>
 *   <li>{@code svc data disable} — mobile data off. A locked screen cannot use
 *       the network, so every packet it would have woken up for is pure drain.</li>
 *   <li>{@code svc wifi disable} — Wi-Fi off as well, for the same reason.</li>
 * </ul>
 *
 * <p>The radio states are read <em>before</em> anything is flipped and stored in
 * {@link Prefs}, so unlocking restores exactly what the owner had: a device whose
 * owner had data/Wi-Fi off is left off, never silently turned on. If the shell
 * channel is unavailable the class degrades to a single recorded note and changes
 * nothing device-wide.
 *
 * <p>COST: one short-lived daemon thread per real transition (never per tick), no
 * timer, no alarm, no polling loop and no wakelock. The 1 Hz kiosk render path
 * calls {@link #sync}, which returns after one in-memory {@code SharedPreferences}
 * read whenever the policy already matches the lock state, so a steady screen
 * spawns nothing.
 *
 * <p>Every call is wrapped: a missing binder, a revoked permission or a
 * RemoteException degrades to a log line, never a crash or a wedged transition.
 */
final class BatteryGuard {

    static final String TAG = "TL.BatteryGuard";

    /** Serialises transitions so two overlapping sync() calls can never interleave. */
    private static final Object LOCK = new Object();

    private BatteryGuard() {}

    // =====================================================================
    // Entry point
    // =====================================================================

    /**
     * Bring the device-wide power policy in line with the kiosk lock state.
     * Non-blocking: the shell work rides on a daemon thread.
     *
     * @param c      any context
     * @param locked true when the kiosk lock is enforced, false once it lifts
     */
    static void sync(Context c, boolean locked) {
        final Context app = appOf(c);
        if (app == null) return;

        boolean engaged = Prefs.powerEngaged(app);
        if (locked) {
            // Re-engage only when the policy is off OR the engage predates the
            // current boot (a reboot resets the radios to their defaults, so the
            // previous pass no longer describes the live device).
            if (engaged && !staleSinceBoot(app)) return;
        } else if (!engaged) {
            return;
        }

        final boolean want = locked;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    try {
                        if (want) {
                            if (Prefs.powerEngaged(app) && !staleSinceBoot(app)) return;
                            engagePass(app);
                        } else {
                            if (!Prefs.powerEngaged(app)) return;
                            releasePass(app);
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "power pass failed", e);
                    }
                }
            }
        }, locked ? "tl-power-on" : "tl-power-off");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            Log.w(TAG, "start power thread", e);
        }
    }

    private static Context appOf(Context c) {
        if (c == null) return null;
        try {
            return c.getApplicationContext();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the stored engage predates the current boot. */
    private static boolean staleSinceBoot(Context c) {
        long at = Prefs.powerAt(c);
        if (at <= 0L) return true;
        long bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return at < bootMs;
    }

    // =====================================================================
    // The two passes
    // =====================================================================

    /** Lock engaged: remember the radio state, stop background apps, radios off. */
    private static void engagePass(Context c) {
        if (!channelUsable()) {
            Prefs.setPowerEngaged(c, true, false, false, false, false,
                    System.currentTimeMillis());
            Prefs.setLastEvent(c, "power:engaged channel=no");
            Log.i(TAG, "lock power policy skipped: Shizuku shell unavailable");
            return;
        }

        // 1. Read the radio state FIRST, so the unlock can put it back exactly.
        int dataPrior = readGlobal("mobile_data");
        int wifiPrior = readGlobal("wifi_on");

        // 2. Stop every background user app. The foreground kiosk is untouched.
        boolean apps = run("am kill-all");

        // 3. Mobile data off — only when it is actually on (idempotent either way,
        //    but this keeps the "off" flag an honest description of what we did).
        boolean dataOff = false;
        if (dataPrior == 1) dataOff = run("svc data disable");

        // 4. Wi-Fi off, same rule.
        boolean wifiOff = false;
        if (wifiPrior == 1) wifiOff = run("svc wifi disable");

        Prefs.setPowerPrior(c, dataPrior, wifiPrior);
        Prefs.setPowerEngaged(c, true, dataOff, wifiOff, apps, true,
                System.currentTimeMillis());
        Prefs.setLastEvent(c, "power:engaged apps=" + (apps ? "stopped" : "no")
                + " data=" + (dataOff ? "off" : "no")
                + " wifi=" + (wifiOff ? "off" : "no"));
        Log.i(TAG, "lock power policy engaged (apps=" + apps + " data=" + dataOff
                + " wifi=" + wifiOff + ")");
    }

    /** Lock lifted: restore exactly the radio state the owner had before. */
    private static void releasePass(Context c) {
        boolean dataOff = Prefs.powerDataOff(c);
        boolean wifiOff = Prefs.powerWifiOff(c);
        boolean channel = channelUsable();

        if (channel) {
            // Only re-enable what we actually disabled: a radio the owner had
            // turned off stays off.
            if (dataOff) run("svc data enable");
            if (wifiOff) run("svc wifi enable");
        }

        Prefs.setPowerEngaged(c, false, false, false, false, channel,
                System.currentTimeMillis());
        Prefs.setLastEvent(c, "power:released data=" + (dataOff ? "on" : "no")
                + " wifi=" + (wifiOff ? "on" : "no"));
        Log.i(TAG, "lock power policy released (data=" + dataOff + " wifi=" + wifiOff + ")");
    }

    // =====================================================================
    // Shell helpers
    // =====================================================================

    private static boolean channelUsable() {
        try {
            return ShizukuBridge.isServiceAlive() && ShizukuBridge.isPermissionGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One {@code settings get global <key>} read: 1, 0, or -1 when unknown. */
    private static int readGlobal(String key) {
        try {
            ShizukuBridge.Exec e = ShizukuBridge.run("settings get global " + key);
            if (e == null || !e.ran() || !e.ok || e.output == null) return -1;
            String v = e.output.trim();
            if ("1".equals(v)) return 1;
            if ("0".equals(v)) return 0;
            return -1;   // "null" (unset) or any other token
        } catch (Throwable t) {
            Log.w(TAG, "read global " + key, t);
            return -1;
        }
    }

    /** Run one shell command; true when it started and exited 0. */
    private static boolean run(String cmd) {
        try {
            ShizukuBridge.Exec e = ShizukuBridge.run(cmd);
            return e != null && e.ran() && e.ok;
        } catch (Throwable t) {
            Log.w(TAG, cmd, t);
            return false;
        }
    }
}
