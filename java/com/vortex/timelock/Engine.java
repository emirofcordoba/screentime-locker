package com.vortex.timelock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Pure logic + DevicePolicyManager plumbing.
 *
 * Only genuine device-owner APIs are used (no Dhizuku/Shizuku calls).
 *
 * STABILITY RULE (this is what fixes the old "phone goes crazy" behaviour):
 * the app never hijacks the screen, never registers itself as a launcher and
 * never applies any policy unless it is the real Device Owner. Every call is
 * additionally wrapped so a missing permission can never crash or wedge the OS.
 */
final class Engine {

    static final String TAG = "TL.Engine";

    private Engine() {}

    // =====================================================================
    // DPM helpers
    // =====================================================================

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getApplicationContext()
                .getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isDeviceOwner(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isAdminActive(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isAdminActive(TimeLockAdmin.component(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** The hard kiosk lock is only ever possible as the real Device Owner. */
    static boolean canEnforce(Context c) {
        return isDeviceOwner(c);
    }

    static String ownerLabel(Context c) {
        if (isDeviceOwner(c)) return "Device owner";
        if (isAdminActive(c)) return "Device admin (not owner)";
        return "Not admin";
    }

    // =====================================================================
    // Window math
    // =====================================================================

    static final long DAY_MS = 24L * 3600_000L;

    /**
     * Start of the accounting window that contains [nowMs], anchored at the
     * user-chosen wall-clock time (minutes after local midnight). If the anchor
     * has not happened yet today we return yesterday's anchor, so the current
     * window is always <= 24h old.
     */
    static long windowStartFor(long nowMs, int anchorMin) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, anchorMin / 60);
        cal.set(Calendar.MINUTE, anchorMin % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        if (start > nowMs) start -= DAY_MS;
        return start;
    }

    static long windowEndFor(long nowMs, int anchorMin) {
        return windowStartFor(nowMs, anchorMin) + DAY_MS;
    }

    static long windowStart(Context c, long nowMs) {
        return windowStartFor(nowMs, Prefs.anchorMin(c));
    }

    static long windowEnd(Context c, long nowMs) {
        return windowStart(c, nowMs) + DAY_MS;
    }

    /**
     * Rolls the period forward when [nowMs] enters a new anchor window.
     *
     * The reset (window start, accumulator zero, re-anchored live session) is a
     * SINGLE atomic transaction in {@link UsageStore}, so a kill during rollover
     * can never leave the counter half-reset.
     */
    static boolean rollover(Context c, long nowMs) {
        if (!Prefs.activated(c)) return false;
        long start = windowStartFor(nowMs, Prefs.anchorMin(c));
        return UsageStore.rollover(c, start);
    }

    /**
     * Screen-on time consumed inside the current window, including the live
     * session. Served from the durable store's in-process cache, so the 1 Hz
     * dashboard path performs no database I/O.
     */
    static long effectiveUsed(Context c, long nowMs) {
        return UsageStore.usedNow(c, nowMs);
    }

    /** Weekday index for [nowMs], using Calendar.DAY_OF_WEEK (1=Sun .. 7=Sat). */
    static int dayOfWeek(long nowMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        return cal.get(Calendar.DAY_OF_WEEK);
    }

    /**
     * The screen-time limit that applies on the weekday containing [nowMs].
     *   - a per-day override of 0 disables the lock for that day entirely;
     *   - a positive per-day override wins over the default;
     *   - otherwise the default daily limit applies.
     */
    static long limitMsFor(Context c, long nowMs) {
        long day = Prefs.dayLimitMs(c, dayOfWeek(nowMs));
        if (day == 0L) return 0L;                  // explicitly disabled that day
        if (day > 0L) return day;                  // custom limit that day
        return Prefs.limitMs(c);                   // inherit the daily default
    }

    /** Today's effective limit (day-aware). */
    static long limitMs(Context c) { return limitMsFor(c, System.currentTimeMillis()); }

    static long remainingMs(Context c, long nowMs) {
        long lim = limitMsFor(c, nowMs);
        if (lim <= 0L) return 0L;
        long r = lim - effectiveUsed(c, nowMs);
        return r > 0 ? r : 0L;
    }

    static boolean shouldBeLocked(Context c, long nowMs) {
        if (!Prefs.activated(c)) return false;
        // Migration gate: while the one-time re-configuration window opened by the
        // legacy kiosk migration is in force, auto-locking is paused so the owner
        // can re-configure once. This is the SINGLE choke point every lock path
        // funnels through, so a paused gate suppresses all of them at once.
        if (DbMigration.isReconfigPauseActive(c, nowMs)) return false;
        long lim = limitMsFor(c, nowMs);
        if (lim <= 0L) return false;             // no limit that day => never lock
        return effectiveUsed(c, nowMs) >= lim;
    }

    static long lockUntilMs(Context c, long nowMs) {
        long stored = Prefs.lockUntil(c);
        if (stored > nowMs) return stored;
        return windowEnd(c, nowMs);
    }

    // =====================================================================
    // Service bootstrap / re-evaluation
    // =====================================================================

    static void bootstrap(Context c) {
        // Finish any pending durable-store migration (and, for a legacy kiosk
        // install, open the one-time re-config window) BEFORE selfHeal can
        // re-assert the lock. This is the universal wake entry point.
        DbMigration.run(c);
        // Recover a usage session that a reboot/SIGKILL interrupted, from the
        // durable heartbeat, BEFORE anything reads the counter for a lock decision.
        UsageStore.reconcileAfterRestart(c, System.currentTimeMillis());
        selfHeal(c);
        // Re-pin every runtime permission as a non-revocable GRANTED and put the
        // package under Device-Owner control protection. Owner-gated internally,
        // so this is a harmless no-op unless we really own the device.
        applyPermissionLockdown(c, true);
        if (!Prefs.activated(c) && !isDeviceOwner(c)) return;
        Intent i = new Intent(c, TimeLockService.class);
        i.setAction(TimeLockService.ACTION_RECHECK);
        startEngine(c, i);
    }

    static void reevaluate(Context c, String reason) {
        Prefs.setLastEvent(c, "reeval:" + reason);
        if (!Prefs.activated(c) && !isDeviceOwner(c)) {
            // Nothing to enforce and nothing to enforce it with: do not spin up a
            // foreground service just to have it stop itself again.
            selfHeal(c);
            return;
        }
        Intent i = new Intent(c, TimeLockService.class);
        i.setAction(TimeLockService.ACTION_RECHECK);
        i.putExtra("reason", reason);
        startEngine(c, i);
    }

    private static void startEngine(Context c, Intent i) {
        // If the engine is already alive, hand the work straight to it. Issuing
        // another startForegroundService() on every screen on/off and every alarm
        // was the "FGS storm" that burned battery and destabilised the device.
        if (TimeLockService.requestRecheck(c, i)) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startEngine failed", t);
        }
    }

    // =====================================================================
    // Lock / unlock policy application
    // =====================================================================

    static void applyLockState(Context c, boolean locked) {
        // Keep the device-protected mirror in step with the authoritative CE
        // state BEFORE the owner early-return, so the pre-unlock boot path always
        // observes the latest "activated / locked / lock_until".
        BootState.mirror(c);
        // Never touch the system unless we really own the device. This single
        // guard is the difference between a working kiosk and a bricked phone.
        if (!isDeviceOwner(c)) {
            if (!locked) LockActivity.dismiss(c);
            return;
        }

        DevicePolicyManager d = dpm(c);
        if (d == null) return;
        ComponentName admin = TimeLockAdmin.component(c);

        // ---- lock-task whitelist so we may enter kiosk ----
        try {
            d.setLockTaskPackages(admin, new String[]{c.getPackageName()});
        } catch (Throwable t) { Log.w(TAG, "setLockTaskPackages", t); }

        // ---- kill every escape hatch while locked ----
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                int feats = locked
                        ? DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                        : (DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                           | DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW);
                d.setLockTaskFeatures(admin, feats);
            } catch (Throwable t) { Log.w(TAG, "setLockTaskFeatures", t); }
        }

        try { d.setStatusBarDisabled(admin, locked); }
        catch (Throwable t) { Log.w(TAG, "setStatusBarDisabled", t); }

        if (Prefs.optKeyguard(c)) {
            try { d.setKeyguardDisabled(admin, locked); }
            catch (Throwable t) { Log.w(TAG, "setKeyguardDisabled", t); }
        }

        // The lock activity only exists as a HOME candidate while it is needed.
        setPersistentHome(c, locked);

        // Hardening follows the "activated" state, not the lock state.
        applyHardening(c, Prefs.activated(c));

        // ---- guaranteed screen takeover ----
        if (locked) LockActivity.launch(c);
        else LockActivity.dismiss(c);
    }

    /**
     * While locked, LockActivity becomes the persistent-preferred HOME activity.
     * The preference is stored by the system, so after a reboot the launcher slot
     * brings the lock screen straight back.
     *
     * While UNLOCKED the component is disabled again, so it disappears from the
     * launcher picker entirely and can never fight the real launcher.
     */
    static void setPersistentHome(Context c, boolean on) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isDeviceOwner(c)) return;
        ComponentName admin = TimeLockAdmin.component(c);
        ComponentName target = new ComponentName(c.getApplicationContext(), LockActivity.class);
        try {
            if (on) {
                setLockActivityEnabled(c, true);
                IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
                f.addCategory(Intent.CATEGORY_HOME);
                f.addCategory(Intent.CATEGORY_DEFAULT);
                d.addPersistentPreferredActivity(admin, f, target);
            } else {
                d.clearPackagePersistentPreferredActivities(admin, c.getPackageName());
                setLockActivityEnabled(c, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "persistentHome(" + on + ")", t);
        }
    }

    /**
     * Lightweight re-assertion of an ACTIVE lock: re-enable the kiosk component,
     * make sure the persistent HOME preference is in place and bring the lock
     * screen to the front. Called on every screen-wake while locked so the kiosk
     * always reappears after the panel was turned off (or after a reboot).
     */
    static void refreshKiosk(Context c) {
        if (!isDeviceOwner(c)) return;
        if (!shouldBeLocked(c, System.currentTimeMillis())) return;
        setPersistentHome(c, true);
        LockActivity.launch(c);
    }

    /**
     * The one piece of state that survives a reinstall/update: a persisted
     * COMPONENT_ENABLED_STATE override (kept by the system in package-restrictions.xml)
     * that can keep the kiosk HOME component alive even when the APK manifest says
     * enabled="false". That stale override is exactly what kept an old build
     * fighting the real launcher ("random apps launching / screen blinking /
     * unresponsive phone") even with no Device Owner at all.
     */
    static boolean isLockComponentEnabled(Context c) {
        try {
            ComponentName a = new ComponentName(c.getApplicationContext(), LockActivity.class);
            int state = c.getPackageManager().getComponentEnabledSetting(a);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            // DEFAULT -> fall back to the manifest value.
            return c.getPackageManager().getActivityInfo(a, 0).enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * IDEMPOTENT SELF-HEAL. Safe to call from any component, at any time, even
     * when we are NOT the device owner. It is the antidote to every stale state
     * that can outlive an ownership loss or a reinstall.
     */
    static void selfHeal(Context c) {
        try {
            boolean owner = isDeviceOwner(c);
            boolean locked = owner && Prefs.activated(c)
                    && shouldBeLocked(c, System.currentTimeMillis());

            if (!locked) {
                // 1. The kiosk must never be a live HOME candidate unless we are
                //    genuinely locked as the owner. Disabling our own component is
                //    allowed without ownership, so this always works.
                if (isLockComponentEnabled(c)) setLockActivityEnabled(c, false);
                // 2. Drop the persistent-HOME preference if we still own the device.
                if (owner) clearPersistentHome(c);
                // 3. If ownership vanished mid-lock, clear the lock so the stored
                //    state matches reality (and the notification stops lying).
                if (!owner && Prefs.locked(c)) {
                    Prefs.setLocked(c, false);
                    Prefs.setLockUntil(c, 0L);
                }
            }
            // 4. Never leave the launcher entry hidden for a non-activated app.
            if (!Prefs.activated(c)) setLauncherVisible(c, true);
            // 5. While activated as the real owner, re-freeze every launcher entry
            //    so a stale state (update, ownership churn, a manual un-hide) can
            //    never resurrect an icon for the user. Idempotent: a component that
            //    is already disabled is skipped.
            if (owner && Prefs.activated(c)) freezeLaunchers(c);
            // 6. Console concealment: once fully activated as the real owner the
            //    console COMPONENT itself is disabled, not just its launcher
            //    entries, so no launchable surface is left at all. Skipped when
            //    selfHeal runs from the console's own onCreate, so opening it can
            //    never disable itself mid-launch; the console re-hides in onStop.
            if (!(c instanceof SetupActivity)) applyConsoleVisibility(c);
            Prefs.setLastEvent(c, "selfheal:" + (owner ? "owner" : "noowner")
                    + (locked ? " locked" : ""));
            // Snapshot the freshly reconciled state into the device-protected
            // mirror so a pre-unlock boot can never read a stale lock.
            BootState.mirror(c);
        } catch (Throwable t) {
            Log.w(TAG, "selfHeal", t);
        }
    }

    /** Clear only the persistent-HOME override (owner only). */
    static void clearPersistentHome(Context c) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isDeviceOwner(c)) return;
        try {
            d.clearPackagePersistentPreferredActivities(
                    TimeLockAdmin.component(c), c.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "clearPersistentHome", t);
        }
    }

    /** Enable/disable the kiosk activity component so it is a launcher only when needed. */
    static void setLockActivityEnabled(Context c, boolean on) {
        try {
            ComponentName a = new ComponentName(c.getApplicationContext(), LockActivity.class);
            c.getPackageManager().setComponentEnabledSetting(a,
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.w(TAG, "setLockActivityEnabled", t);
        }
    }

    /** Device-owner hardening. Applied while activated (and reinforced while locked). */
    static void applyHardening(Context c, boolean enable) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isDeviceOwner(c)) return;
        ComponentName admin = TimeLockAdmin.component(c);

        restrict(d, admin, UserManager.DISALLOW_ADD_USER, enable);
        restrict(d, admin, UserManager.DISALLOW_USER_SWITCH, enable);
        // Root-level restrictions (blocking Safe Mode / Factory Reset) are routed
        // through the fail-safe approval barrier: they engage ONLY when a live
        // standing warrant minted by the tiered approval walk matches the live
        // configuration.
        RestrictionGuard.applyRootRestrictions(c, d, admin, enable);
        // Blocks changing the clock/date, which would let the user jump past the
        // window end and escape an active lock.
        restrict(d, admin, UserManager.DISALLOW_CONFIG_DATE_TIME, enable);
        // Once the settings are permanently locked we also cut ADB off, so the
        // configuration surface cannot be reached from a PC even as a privileged
        // shell user. (Also honours the explicit "Block USB debugging" toggle.)
        if (Prefs.optDisableDebugging(c) || Prefs.settingsLocked(c)) {
            restrict(d, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, enable);
        }

        // keep the engine immune to App-Standby / restricted battery buckets
        if (enable && Prefs.optStandby(c)) {
            try { d.setGlobalSetting(admin, "app_standby_enabled", "0"); }
            catch (Throwable t) { Log.w(TAG, "app_standby", t); }
        }

        // Permissions follow the same "activated" lifecycle: while active every
        // runtime permission we declare is pinned to a non-revocable GRANTED
        // state and the package is placed under user-control protection, so the
        // user can never take a permission away again. Released when disabled.
        applyPermissionLockdown(c, enable);
    }

    private static void restrict(DevicePolicyManager d, ComponentName admin, String key, boolean on) {
        if (d == null) return;
        try {
            if (on) d.addUserRestriction(admin, key);
            else d.clearUserRestriction(admin, key);
        } catch (Throwable t) {
            Log.w(TAG, "restrict " + key, t);
        }
    }

    static void selfGrantNotifications(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            DevicePolicyManager d = dpm(c);
            if (d != null && isDeviceOwner(c)) {
                try {
                    d.setPermissionGrantState(TimeLockAdmin.component(c), c.getPackageName(),
                            "android.permission.POST_NOTIFICATIONS",
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Throwable t) { Log.w(TAG, "grant notif", t); }
            }
        }
        // Belt-and-braces: pin the FULL declared runtime permission set, not just
        // notifications. Runs on every API level and is a no-op unless we are the
        // device owner.
        applyPermissionLockdown(c, true);
    }

    // =====================================================================
    // Concealment ("complete setup & hide")
    // =====================================================================

    /**
     * Enable / disable the visible launcher entries (.SplashActivity + .LauncherDashboard).
     *
     * The gatekeeper splash owns the MAIN+LAUNCHER intent-filter and hands over to
     * the dashboard, so BOTH components are toggled together: a hidden install must
     * show no icon at all, and a visible one must actually be openable.
     */
    static void setLauncherVisible(Context c, boolean visible) {
        for (Class<?> entry : new Class<?>[]{ SplashActivity.class, GatekeeperActivity.class, LauncherDashboard.class }) {
            try {
                ComponentName alias = new ComponentName(c.getApplicationContext(), entry);
                c.getPackageManager().setComponentEnabledSetting(alias,
                        visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "launcher " + entry.getSimpleName() + " visible=" + visible);
            } catch (Throwable t) {
                Log.w(TAG, "setLauncherVisible:" + entry.getSimpleName(), t);
            }
        }
        // Lock-step with the console component. The alias targets .LauncherDashboard,
        // whose "Open setup console" button starts .SetupActivity, so a visible icon
        // is dead weight (and the admin entry is unreachable from it) if the console
        // component itself is disabled. Showing the icon re-enables the console;
        // the normal conceal path leaves the console governed by
        // applyConsoleVisibility().
        setConsoleEnabled(c, visible || !consoleShouldBeHidden(c));
    }

    // =====================================================================
    // ADMIN CONSOLE CONCEALMENT
    // =====================================================================
    //
    // freezeLaunchers() disables every MAIN+LAUNCHER / MAIN+HOME entry, but
    // .SetupActivity itself is neither, so after a full setup the console was
    // still an exported, startable component: reachable from adb, a third-party
    // app or the recent-tasks surface, which let a "locked-in" install be walked
    // straight back into the configuration screen. The console component is now
    // disabled too, on the same gate (activated + real device owner). The secret
    // dial code is the one door back in: it re-enables the component for a single
    // visit, and the console re-hides itself the moment it leaves the screen.

    /** The admin console component (.SetupActivity) -- the class the alias targets. */
    static ComponentName consoleComponent(Context c) {
        return new ComponentName(c.getApplicationContext(),
                "com.vortex.timelock.SetupActivity");
    }

    /**
     * TRUE once a full, owner-confirmed setup is in force. Only then is the
     * console concealed along with every launcher entry. While the app is merely
     * activated (no device ownership yet) or not activated at all, the console
     * stays reachable so the operator can finish configuring / grant ownership
     * (that is also the state the "not owner" limit notification relies on).
     */
    static boolean consoleShouldBeHidden(Context c) {
        return c != null && Prefs.activated(c) && isDeviceOwner(c);
    }

    static boolean isConsoleEnabled(Context c) {
        try {
            PackageManager pm = c.getApplicationContext().getPackageManager();
            int state = pm.getComponentEnabledSetting(consoleComponent(c));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            // DEFAULT -> fall back to the manifest value.
            return pm.getActivityInfo(consoleComponent(c), 0).enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Enable / disable the admin console component itself (persistent override
     * that survives a reboot and an APK update, like the launcher freeze).
     */
    static void setConsoleEnabled(Context c, boolean on) {
        if (c == null) return;
        try {
            ComponentName cn = consoleComponent(c);
            PackageManager pm = c.getApplicationContext().getPackageManager();
            int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                          : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (pm.getComponentEnabledSetting(cn) == want) return; // already right
            pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
            Prefs.setLastEvent(c, "config:console " + (on ? "enabled" : "disabled"));
            Log.i(TAG, "console enabled=" + on);
        } catch (Throwable t) {
            Log.w(TAG, "setConsoleEnabled", t);
        }
    }

    /** Re-assert the console component state for the current activation/owner state. */
    static void applyConsoleVisibility(Context c) {
        setConsoleEnabled(c, !consoleShouldBeHidden(c));
    }

    /**
     * Every activity this app exposes as a launcher entry: the MAIN+LAUNCHER
     * aliases/activities and the MAIN+HOME kiosk candidates. The query is bounded
     * to this package, so nothing outside the app is ever enumerated.
     */
    static List<ResolveInfo> launcherEntries(Context c) {
        List<ResolveInfo> out = new ArrayList<>();
        try {
            PackageManager pm = c.getApplicationContext().getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(c.getPackageName());
            List<ResolveInfo> l = pm.queryIntentActivities(launcher, 0);
            if (l != null) out.addAll(l);
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(c.getPackageName());
            List<ResolveInfo> h = pm.queryIntentActivities(home, 0);
            if (h != null) out.addAll(h);
        } catch (Throwable t) {
            Log.w(TAG, "launcherEntries", t);
        }
        return out;
    }

    /**
     * FREEZE every activity-launcher entry this app declares.
     *
     * A "launcher entry" is any component the system surfaces in the launcher /
     * app drawer: the MAIN+LAUNCHER alias, plus any MAIN+LAUNCHER or MAIN+HOME
     * activity. Each one is disabled with COMPONENT_ENABLED_STATE_DISABLED — a
     * persistent override that survives a reboot and an APK update — so a fully
     * set-up install has ZERO launchable presence for the user.
     *
     * The kiosk ({@link LockActivity}) is deliberately NOT frozen while a lock is
     * actually in force: it is the HOME target the locked device needs. While no
     * lock is active it carries no launcher entry of its own and the normal
     * disable path ({@link #setLockActivityEnabled} / {@link #selfHeal}) governs
     * it, so this method leaves it alone either way.
     *
     * @return the number of components disabled by this call.
     */
    static int freezeLaunchers(Context c) {
        if (c == null) return 0;
        int disabled = 0;
        try {
            PackageManager pm = c.getApplicationContext().getPackageManager();
            List<ResolveInfo> entries = launcherEntries(c);
            boolean lockActive = Prefs.activated(c)
                    && shouldBeLocked(c, System.currentTimeMillis());
            for (ResolveInfo ri : entries) {
                if (ri == null || ri.activityInfo == null) continue;
                try {
                    // Never freeze the kiosk HOME while it is the active lock target.
                    if (lockActive && LockActivity.class.getName()
                            .equals(ri.activityInfo.name)) {
                        continue;
                    }
                    ComponentName cn = new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name);
                    if (pm.getComponentEnabledSetting(cn)
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        continue; // already frozen
                    }
                    pm.setComponentEnabledSetting(cn,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    disabled++;
                } catch (Throwable t) {
                    Log.w(TAG, "freezeLaunchers: " + ri.activityInfo.name, t);
                }
            }
            Prefs.setLastEvent(c, "config:launcher-freeze entries=" + entries.size()
                    + " disabled=" + disabled);
            Log.i(TAG, "launcher freeze: entries=" + entries.size() + " disabled=" + disabled);
        } catch (Throwable t) {
            Log.w(TAG, "freezeLaunchers", t);
        }
        return disabled;
    }

    // ---------------------------------------------------------------------
    // RECENT-TASK VISIBILITY BLOCKADE (runtime, confirmation-gated)
    // ---------------------------------------------------------------------
    // Background glitch being fixed: .SetupActivity used to carry a STATIC
    // android:excludeFromRecents="true" in AndroidManifest.xml. A static
    // attribute applies the moment the activity is created, so the console
    // vanished from the recent-apps / overview list as soon as it launched --
    // i.e. BEFORE the operator had walked the two-step setup confirmation
    // (ACK + PHRASE). That is the "premature hide" glitch.
    //
    // excludeFromRecents cannot be toggled at runtime per activity, so the
    // static attribute has been removed and the blockade is now enforced at
    // RUNTIME, strictly gated on the persisted two-step confirmation state
    // (Prefs.activated(c) == true, written only by SetupActivity.doCompleteSetup
    // after the approval chain passes). Until that gate flips, the console is
    // deliberately left discoverable in recents.
    //
    // Every transition is documented into the GLOBAL LOG RING through
    // Prefs.setLastEvent(...) with the "config:" prefix, so it is picked up by
    // ConfigHistory / KioskLogParser.parseConfig like every other configuration
    // event.
    // ---------------------------------------------------------------------

    /** The single source of truth for "the two-step setup is fully confirmed". */
    static boolean recentsBlockadeConfirmed(Context c) {
        return c != null && Prefs.activated(c);
    }

    /**
     * Human-readable gate state written to the global log. Kept as key=value
     * tokens (spaces, not '='), because KioskLogParser.parseConfig collapses
     * '=' into spaces when it renders a config line.
     */
    static String recentsGateState(Context c) {
        boolean confirmed = recentsBlockadeConfirmed(c);
        return "setup=" + (confirmed ? "confirmed" : "pending")
                + " owner=" + (isDeviceOwner(c) ? "yes" : "no");
    }

    /**
     * Enforce the recent-task visibility blockade ONLY after the two-step setup
     * confirmation has been persisted.
     *
     * @return true when the blockade is now engaged (confirmed), false when it
     *         is deferred because setup is not confirmed yet.
     */
    static boolean enforceRecentsBlockade(Context c, String reason) {
        String gate = recentsGateState(c);
        String snap = ConfigHistory.snapshot(c);
        if (!recentsBlockadeConfirmed(c)) {
            // Deferred: the console stays discoverable in recents on purpose.
            Prefs.setLastEvent(c, "config:recents-blockade-deferred reason=" + reason
                    + " " + gate + " " + snap);
            Log.i(TAG, "recents blockade deferred (" + reason + "): " + gate);
            return false;
        }
        Prefs.setLastEvent(c, "config:recents-blockade-engaged reason=" + reason
                + " " + gate + " " + snap);
        Log.i(TAG, "recents blockade engaged (" + reason + "): " + gate);
        return true;
    }

    /**
     * Launch flags for the admin console entry points. The
     * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS flag is added ONLY once the two-step
     * setup confirmation is persisted, so a first-run launch stays visible in
     * recents until setup completes.
     */
    static int adminEntryFlags(Context c) {
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        if (recentsBlockadeConfirmed(c)) {
            flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        }
        return flags;
    }

    /** Drop the ongoing monitor notification to the quietest possible state. */
    static void silenceNotifications(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 26) return;
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            for (String id : new String[]{"tl_monitor", "tl_lock"}) {
                android.app.NotificationChannel ch = nm.getNotificationChannel(id);
                if (ch == null) continue;
                if ("tl_lock".equals(id)) continue; // lock channel must stay high-priority
                ch.setImportance(android.app.NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Log.i(TAG, "notifications silenced");
        } catch (Throwable t) {
            Log.w(TAG, "silenceNotifications", t);
        }
    }

    // =====================================================================
    // PERMISSION LOCKDOWN + PRE-UNLOCK KIOSK RE-ASSERT
    // =====================================================================

    /**
     * Every DANGEROUS (runtime) permission this package declares. Read straight
     * from the installed package so the list can never drift from the manifest.
     * Returns an empty list on any failure (never throws).
     */
    static List<String> declaredRuntimePermissions(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(
                    c.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi == null || pi.requestedPermissions == null) return out;
            for (String p : pi.requestedPermissions) {
                if (p == null) continue;
                try {
                    PermissionInfo info =
                            c.getPackageManager().getPermissionInfo(p, 0);
                    int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                    if (base == PermissionInfo.PROTECTION_DANGEROUS) out.add(p);
                } catch (Throwable ignore) { /* unknown perm: skip */ }
            }
        } catch (Throwable t) {
            Log.w(TAG, "declaredRuntimePermissions", t);
        }
        return out;
    }

    /**
     * Make every permission this app declares permanently granted-and-pinned so
     * the kiosk works from a totally un-permissioned install, and so that any
     * permission (auto-)granted to it afterwards can never be disabled by the
     * user.
     *
     * Everything here is PACKAGE-SCOPED. We deliberately do NOT touch
     * DevicePolicyManager.setPermissionPolicy(): that call is DEVICE-WIDE - for a
     * Device Owner it would silently auto-grant the runtime permissions of every
     * other app on the phone. Instead we pin only our own declared runtime
     * permissions:
     *
     *   - setPermissionGrantState(GRANTED) : grants and freezes each declared
     *     dangerous permission. A permission granted by the Device Owner is fixed
     *     policy: Settings shows it greyed-out and the user cannot revoke it (the
     *     "once granted, never revocable" guarantee).
     *   - setUserControlDisabledPackages (API 30+): blocks the user from
     *     force-stopping / clearing data / uninstalling the protected package, so
     *     neither the app nor its pinned permissions can be torn down by hand.
     *
     * Owner-gated, idempotent and fully fail-safe. When {@code enable} is false the
     * pin is released (PERMISSION_GRANT_STATE_DEFAULT) so the permissions return to
     * normal user control while the app is not activated.
     */
    static void applyPermissionLockdown(Context c, boolean enable) {
        if (!isDeviceOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        if (d == null) return;
        ComponentName admin = TimeLockAdmin.component(c);

        // NOTE: no setPermissionPolicy() on purpose - it is DEVICE-WIDE and would
        // affect every other app on the phone. Pinning our own declared runtime
        // permissions below is enough and is strictly package-scoped.
        for (String p : declaredRuntimePermissions(c)) {
            try {
                d.setPermissionGrantState(admin, c.getPackageName(), p, enable
                        ? DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        : DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);
            } catch (Throwable t) { Log.w(TAG, "pin perm " + p, t); }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                d.setUserControlDisabledPackages(admin, enable
                        ? java.util.Collections.singletonList(c.getPackageName())
                        : java.util.Collections.<String>emptyList());
            } catch (Throwable t) { Log.w(TAG, "setUserControlDisabledPackages", t); }
        }
        Log.i(TAG, "permission lockdown " + (enable ? "on" : "off"));
    }

    /**
     * PRE-UNLOCK SAFE kiosk re-assert.
     *
     * Runs from the direct-boot receiver before the credential-encrypted store is
     * readable, so it MUST NOT touch Prefs / UsageStore / any CE-backed surface and
     * MUST NOT launch an activity (LockActivity reads CE state in onCreate and
     * would crash-loop). It therefore only uses Device-Owner policy calls, which
     * the framework serves from device-protected storage and which require no
     * runtime permission and no OEM "autostart" whitelist:
     *   - setLockTaskPackages : keep ourselves whitelisted for lock task.
     *   - setPersistentHome   : (re)install the system-persisted preferred-HOME
     *     override pointing HOME at LockActivity while locked. That override lives
     *     in the system, so once the user finishes unlocking the kiosk comes back
     *     on its own - with zero permissions.
     *
     * {@code locked} comes from the device-protected mirror (BootState), never CE.
     */
    static void reassertKioskSurface(Context c, boolean locked) {
        if (!isDeviceOwner(c)) return;
        try {
            DevicePolicyManager d = dpm(c);
            if (d == null) return;
            ComponentName admin = TimeLockAdmin.component(c);
            try {
                d.setLockTaskPackages(admin, new String[]{c.getPackageName()});
            } catch (Throwable t) { Log.w(TAG, "reassert setLockTaskPackages", t); }
            // setPersistentHome(true) also enables the LockActivity component and
            // installs the preferred-HOME override; (false) clears + disables it.
            setPersistentHome(c, locked);
            // Pin our declared runtime permissions pre-unlock too. DPM policy is
            // served from device-protected storage, so this needs no CE access and
            // no runtime permission, and it is already in force the instant the
            // user finishes unlocking - no delay waiting for app UI to come up.
            applyPermissionLockdown(c, true);
            Log.i(TAG, "pre-unlock kiosk re-assert locked=" + locked);
        } catch (Throwable t) {
            Log.w(TAG, "reassertKioskSurface", t);
        }
    }

}
