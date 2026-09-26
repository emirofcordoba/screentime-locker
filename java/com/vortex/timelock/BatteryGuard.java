package com.vortex.timelock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BatteryGuard — the "a locked phone should be a quiet phone" power policy.
 *
 * <p>{@link PowerGovernor} keeps <em>this app</em> from burning power. This class
 * is the other half of the problem: while the kiosk lock owns the device, the
 * <em>rest of the phone</em> must stop burning power too. It does so with the
 * native Device-Owner {@link DevicePolicyManager} API only — no shell, no ADB,
 * no Shizuku:
 *
 * <ul>
 *   <li>{@link DevicePolicyManager#setPackagesSuspended} — suspend every
 *       third-party package. A suspended package is force-stopped by the
 *       framework and can never be started again (no broadcast, no alarm, no
 *       service restart) until it is un-suspended. That is strictly stronger
 *       than {@code am kill-all}: it not only stops the running background app,
 *       it keeps it from coming back, so nothing left in the background keeps a
 *       wakelock, fires an alarm, runs a sync or holds the radio awake. The
 *       foreground kiosk (this app) and every system package are left running.</li>
 *   <li>{@link WifiManager#setWifiEnabled} — Wi-Fi off. A locked screen cannot
 *       use the network, so every packet it would have woken up for is pure
 *       drain. The Device-Owner exemption is what makes this call legal on a
 *       {@code targetSdk >= 29} build; a plain app would be silently refused.</li>
 * </ul>
 *
 * <p>The Wi-Fi state is read <em>before</em> anything is flipped and stored in
 * {@link Prefs}, so unlocking restores exactly what the owner had: a device
 * whose owner had Wi-Fi off is left off, never silently turned on. The exact set
 * of packages this app suspended is likewise recorded, so the unlock un-suspends
 * only what the lock suspended.
 *
 * <p>Mobile data: stock Android exposes <b>no</b> Device-Owner API to toggle the
 * cellular data radio ({@code mobile_data} is not in the {@code setGlobalSetting}
 * allow-list, and {@code TelephonyManager.setDataEnabled} needs
 * {@code MODIFY_PHONE_STATE}). The policy therefore does not touch the radio —
 * but suspending every user app means no third-party app can use mobile data
 * either, which is the same practical outcome for everything the kiosk can
 * reach. The data radio itself is left exactly as the owner set it.
 *
 * <p>COST: one short-lived daemon thread per real transition (never per tick), no
 * timer, no alarm, no polling loop and no wakelock. The 1 Hz kiosk render path
 * calls {@link #sync}, which returns after one in-memory {@code SharedPreferences}
 * read whenever the policy already matches the lock state, so a steady screen
 * spawns nothing.
 *
 * <p>Every call is wrapped: a missing binder, a revoked permission or a
 * protected package degrades to a log line, never a crash or a wedged
 * transition.
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
     * Non-blocking: the DevicePolicyManager work rides on a daemon thread.
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
            // current boot (a reboot clears every suspension and resets Wi-Fi, so
            // the previous pass no longer describes the live device).
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

    /** Lock engaged: suspend every user app, remember Wi-Fi state, Wi-Fi off. */
    private static void engagePass(Context c) {
        DevicePolicyManager d = Engine.dpm(c);
        ComponentName admin = TimeLockAdmin.component(c);

        // Not the Device Owner: the native APIs are unavailable. Record one
        // honest note and change nothing device-wide (exactly as before).
        if (d == null || !Engine.isDeviceOwner(c) || admin == null) {
            Prefs.setPowerEngaged(c, true, false, false, false, false,
                    System.currentTimeMillis());
            Prefs.setLastEvent(c, "power:engaged channel=no");
            Log.i(TAG, "lock power policy skipped: not device owner");
            return;
        }

        // 1. Read the Wi-Fi state FIRST, so the unlock can put it back exactly.
        WifiManager wm = wifi(c);
        int wifiPrior = -1;
        if (wm != null) {
            try { wifiPrior = wm.isWifiEnabled() ? 1 : 0; } catch (Throwable t) {
                Log.w(TAG, "read wifi", t);
            }
        }

        // 2. Suspend every third-party package. The kiosk (this package) and all
        //    system packages are untouched. Only the ones the framework actually
        //    accepted are recorded for an exact un-suspend on release.
        List<String> suspended = suspendAll(c, d, admin);

        // 3. Wi-Fi off — only when it was actually on, so the stored "off" flag
        //    stays an honest description of what we did.
        boolean wifiOff = false;
        if (wifiPrior == 1 && wm != null) {
            try { wifiOff = wm.setWifiEnabled(false); } catch (Throwable t) {
                Log.w(TAG, "wifi off", t);
            }
        }

        Prefs.setPowerPrior(c, -1, wifiPrior);
        Prefs.setPowerSuspended(c, suspended);
        Prefs.setPowerEngaged(c, true, false, wifiOff, !suspended.isEmpty(), true,
                System.currentTimeMillis());
        Prefs.setLastEvent(c, "power:engaged apps=" + suspended.size()
                + " wifi=" + (wifiOff ? "off" : "no"));
        Log.i(TAG, "lock power policy engaged (suspended=" + suspended.size()
                + " wifi=" + wifiOff + ")");
    }

    /** Lock lifted: un-suspend exactly what we suspended, restore Wi-Fi. */
    private static void releasePass(Context c) {
        DevicePolicyManager d = Engine.dpm(c);
        ComponentName admin = TimeLockAdmin.component(c);
        boolean owner = d != null && Engine.isDeviceOwner(c) && admin != null;

        Set<String> suspended = Prefs.powerSuspended(c);
        if (owner && !suspended.isEmpty()) {
            unsuspend(d, admin, suspended);
        }

        // Only re-enable what we actually disabled: a radio the owner had turned
        // off stays off.
        boolean wifiOff = Prefs.powerWifiOff(c);
        if (owner && wifiOff) {
            WifiManager wm = wifi(c);
            if (wm != null) {
                try { wm.setWifiEnabled(true); } catch (Throwable t) {
                    Log.w(TAG, "wifi on", t);
                }
            }
        }

        Prefs.setPowerSuspended(c, java.util.Collections.<String>emptyList());
        Prefs.setPowerEngaged(c, false, false, false, false, owner,
                System.currentTimeMillis());
        Prefs.setLastEvent(c, "power:released apps=" + suspended.size()
                + " wifi=" + (wifiOff ? "on" : "no"));
        Log.i(TAG, "lock power policy released (unsuspended=" + suspended.size()
                + " wifi=" + wifiOff + ")");
    }

    // =====================================================================
    // DevicePolicyManager helpers
    // =====================================================================

    /**
     * Suspend every third-party package, returning the ones the framework
     * actually accepted. Requires API 24 (Android 7.0), where the bulk
     * {@link DevicePolicyManager#setPackagesSuspended} call was introduced: it
     * returns the package names that could NOT be suspended, so the accepted set
     * is the requested set minus that. Protected packages (the active launcher,
     * dialer, installer, other device admins) come back in that refused list and
     * are simply not recorded. The guard is 24 rather than a later level on
     * purpose: gating at API 30 would silently disable app-killing on Android
     * 8-10 even though the API is present there.
     */
    private static List<String> suspendAll(Context c, DevicePolicyManager d, ComponentName admin) {
        List<String> done = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return done;
        List<String> targets = thirdPartyPackages(c);
        if (targets.isEmpty()) return done;

        String[] names = targets.toArray(new String[0]);
        String[] refused = null;
        try {
            refused = d.setPackagesSuspended(admin, names, true);
        } catch (Throwable t) {
            Log.w(TAG, "bulk suspend", t);
            return done;
        }
        Set<String> fail = new HashSet<>();
        if (refused != null) {
            for (String f : refused) if (f != null) fail.add(f);
        }
        for (String n : names) if (!fail.contains(n)) done.add(n);
        return done;
    }

    /** Un-suspend exactly the recorded set (API 24+ bulk call). */
    private static void unsuspend(DevicePolicyManager d, ComponentName admin,
                                  Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            d.setPackagesSuspended(admin, pkgs.toArray(new String[0]), false);
        } catch (Throwable t) {
            Log.w(TAG, "bulk unsuspend", t);
        }
    }

    /** Every installed third-party (non-system) package, minus our own. */
    private static List<String> thirdPartyPackages(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            String self = c.getPackageName();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                if (self.equals(ai.packageName)) continue;
                out.add(ai.packageName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "list packages", t);
        }
        return out;
    }

    private static WifiManager wifi(Context c) {
        try {
            return (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }
}
