package com.vortex.timelock;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * ============================================================================
 *  Digital Retreat — sequential MANUAL permission ladder (onboarding)
 * ============================================================================
 *
 *  This is the single source of truth for the three permission items the
 *  operator must approve BY HAND, one after another, on the onboarding screen
 *  ({@link OnboardingActivity}):
 *
 *      step 0   Autostart permission   -> OEM autostart / battery settings
 *      step 1   Notification permission -> POST_NOTIFICATIONS runtime prompt
 *      step 2   Battery optimization   -> ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *
 *  Nothing here requests anything on its own: {@link #approve(Activity, int)}
 *  is only ever called from the operator's own "Approve" tap, and it opens the
 *  exact system surface for THAT one permission and nothing else. A single
 *  blanket prompt is deliberately NOT used.
 *
 *  {@link #isGranted(Context, int)} is the live probe the UI consults to decide
 *  whether the current step is satisfied and the NEXT button may be unlocked.
 *  Every probe is defensive: any failure reads as "not granted" rather than
 *  crashing or lying.
 *
 *  WHY THE AUTOSTART PROBE IS SPECIAL
 *  ----------------------------------
 *  OEM autostart whitelists (MIUI, EMUI, ColorOS, Funtouch, ...) are vendor
 *  extensions with NO public read API and NO framework permission. An app
 *  therefore cannot truthfully answer "am I whitelisted?". What it CAN observe
 *  is whether that restriction is even in play: a Device Owner / active admin
 *  is exempt from OEM autostart limits, and this app ships a directBootAware
 *  boot receiver that re-arms the guard with no whitelist at all. When neither
 *  of those holds we fall back to the operator's own attestation, captured the
 *  moment they return from the OEM autostart screen they were just sent to.
 *  See {@link #isAutostartOk(Context)}.
 */
final class PermissionFlow {

    // ---- step ids (stable order the operator walks) ----
    static final int STEP_AUTOSTART    = 0;
    static final int STEP_NOTIFICATION = 1;
    static final int STEP_BATTERY      = 2;
    static final int STEP_COUNT        = 3;

    /** Request code for the POST_NOTIFICATIONS runtime prompt. */
    static final int REQ_NOTIFICATIONS = 9101;

    /**
     * Known OEM autostart screens, tried most-specific first. Every entry is
     * resolved before it is used, and any miss simply falls through to the next
     * candidate, so an unsupported/OEM-less device degrades to the app details
     * page instead of crashing.
     */
    private static final ComponentName[] AUTOSTART_COMPONENTS = {
            // Xiaomi / MIUI
            new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            new ComponentName("com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"),
            // Huawei / EMUI / Honor
            new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            // Oppo / ColorOS (incl. Realme)
            new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            new ComponentName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Vivo / Funtouch (incl. iQOO)
            new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            // Letv
            new ComponentName("com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"),
            // Asus
            new ComponentName("com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"),
            // Samsung (Device care -> Battery -> background usage limits)
            new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"),
            new ComponentName("com.samsung.android.sm_cn",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"),
    };

    private PermissionFlow() {}

    // =====================================================================
    // Step metadata (bound to string resources so the ladder is localisable)
    // =====================================================================

    static int count() {
        return STEP_COUNT;
    }

    static String title(Context c, int step) {
        switch (step) {
            case STEP_AUTOSTART:    return c.getString(R.string.perm_autostart_title);
            case STEP_NOTIFICATION: return c.getString(R.string.perm_notification_title);
            case STEP_BATTERY:      return c.getString(R.string.perm_battery_title);
            default:                return "";
        }
    }

    /** Short form used inside the NEXT button ("Next -> Notifications"). */
    static String shortTitle(Context c, int step) {
        switch (step) {
            case STEP_AUTOSTART:    return c.getString(R.string.perm_autostart_short);
            case STEP_NOTIFICATION: return c.getString(R.string.perm_notification_short);
            case STEP_BATTERY:      return c.getString(R.string.perm_battery_short);
            default:                return "";
        }
    }

    static String description(Context c, int step) {
        switch (step) {
            case STEP_AUTOSTART:    return c.getString(R.string.perm_autostart_desc);
            case STEP_NOTIFICATION: return c.getString(R.string.perm_notification_desc);
            case STEP_BATTERY:      return c.getString(R.string.perm_battery_desc);
            default:                return "";
        }
    }

    // =====================================================================
    // Live probes — the boolean the NEXT button is gated on
    // =====================================================================

    /**
     * Dynamic state of one step. The UI calls this on every resume and after
     * every prompt result; the NEXT button is enabled only when
     * {@code isGranted(this, currentStep)} returns true.
     */
    static boolean isGranted(Context c, int step) {
        switch (step) {
            case STEP_AUTOSTART:    return isAutostartOk(c);
            case STEP_NOTIFICATION: return isNotificationsOk(c);
            case STEP_BATTERY:      return isBatteryExempt(c);
            default:                return false;
        }
    }

    /**
     * Autostart state, best-effort and honest about the platform's limits.
     *
     * <p>Returns true when:
     * <ol>
     *   <li>we are the real Device Owner or an active admin (OEM autostart limits
     *       do not apply, and our directBootAware boot receiver re-arms the guard
     *       pre-unlock with no whitelist), or</li>
     *   <li>the operator has just been through the OEM autostart screen and the
     *       attestation was recorded on return (there is no read API to verify
     *       the vendor toggle itself).</li>
     * </ol>
     */
    static boolean isAutostartOk(Context c) {
        try {
            if (Engine.isDeviceOwner(c) || Engine.isAdminActive(c)) return true;
        } catch (Throwable ignored) { /* fall through to attestation */ }
        try {
            return Prefs.autostartAttested(c);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isNotificationsOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isBatteryExempt(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================
    // The one manual action per step — each opens EXACTLY one system surface
    // =====================================================================

    /** Launch the system surface behind this step's single "Approve" tap. */
    static void approve(Activity a, int step) {
        if (a == null) return;
        switch (step) {
            case STEP_AUTOSTART:    openAutostart(a);            break;
            case STEP_NOTIFICATION: requestNotifications(a);     break;
            case STEP_BATTERY:      requestBatteryExemption(a);  break;
            default: break;
        }
    }

    /**
     * Autostart: open the OEM's autostart / startup management screen. No
     * standard intent exists, so we probe the known vendor components and fall
     * back to the app details page every OEM keeps.
     */
    static void openAutostart(Activity a) {
        for (ComponentName cn : AUTOSTART_COMPONENTS) {
            try {
                Intent i = new Intent();
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (a.getPackageManager().resolveActivity(i, 0) != null) {
                    a.startActivity(i);
                    return;
                }
            } catch (Throwable ignored) { /* try the next candidate */ }
        }
        openAppDetails(a);
    }

    /**
     * Notification: the standard POST_NOTIFICATIONS runtime prompt on Android
     * 13+; otherwise (already decided / pre-33) the per-app notification
     * settings so the state can still be inspected.
     */
    static void requestNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
            return;
        }
        openAppNotificationSettings(a);
    }

    /**
     * Battery: the ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog for this
     * package, with a defensive fall back to the global exemption list.
     */
    static void requestBatteryExemption(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        } catch (Throwable ignored) { /* fall through */ }
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            openAppDetails(a);
        }
    }

    // =====================================================================
    // Attestation for the (unreadable) autostart step
    // =====================================================================

    /** Record that the operator returned from the OEM autostart screen. */
    static void markAutostartAttested(Context c) {
        try {
            Prefs.setAutostartAttested(c, true);
        } catch (Throwable ignored) { /* advisory only */ }
    }

    // =====================================================================
    // Settings launchers
    // =====================================================================

    private static void openAppDetails(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { /* nothing else we can do */ }
    }

    private static void openAppNotificationSettings(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            a.startActivity(i);
        } catch (Throwable t) {
            openAppDetails(a);
        }
    }
}
