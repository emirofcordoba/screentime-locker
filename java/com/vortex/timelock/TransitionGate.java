package com.vortex.timelock;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  Digital Retreat — readiness gate for the transition to the Main Screen
 * ============================================================================
 *
 *  This is the single source of truth for the two prerequisite groups the
 *  operator must have completed on the earlier onboarding surfaces before the
 *  app is allowed to move on to {@link MainSetupActivity}:
 *
 *  <ol>
 *    <li><b>System permissions</b> — the runtime capabilities the enforcement
 *        engine depends on: notifications, exact alarms, full-screen intent,
 *        battery-optimisation exemption and (best-effort) OEM autostart.</li>
 *    <li><b>Shizuku / Device Admin</b> — the elevated grant that lets the app
 *        actually enforce the lock. It is satisfied the moment this app is the
 *        real Device Owner OR an active Device Admin; the Shizuku service state
 *        is reported alongside so the operator can see how it was (or can be)
 *        provisioned.</li>
 *  </ol>
 *
 *  Every probe here is defensive: any failure reads as "not ready" instead of
 *  crashing or falsely reporting success. The three autostart-aware lines mirror
 *  {@link PermissionFlow#isAutostartOk(Context)} exactly — OEM autostart has no
 *  readable API, so a Device Owner / active admin (which is required anyway) is
 *  exempt, and the operator's own attestation is the honest fallback.
 */
final class TransitionGate {

    private TransitionGate() {}

    /** A single verified/pending line rendered by the transition surface. */
    static final class Requirement {
        final String label;
        final String detail;
        final boolean ok;

        Requirement(String label, String detail, boolean ok) {
            this.label = label;
            this.detail = detail;
            this.ok = ok;
        }
    }

    // =====================================================================
    // The full checklist, in the order the operator walked it
    // =====================================================================

    /** Evaluates every requirement and returns them for live rendering. */
    static List<Requirement> evaluate(Context c) {
        List<Requirement> out = new ArrayList<>();
        if (c == null) return out;

        out.add(new Requirement("Notifications",
                "Lock reminder and the live remaining-time counter", notificationsOk(c)));
        out.add(new Requirement("Exact alarms",
                "Re-arms the daily schedule on time", exactAlarmsOk(c)));
        out.add(new Requirement("Full-screen lock",
                "Allows the lock screen to take over at the limit", fullScreenOk(c)));
        out.add(new Requirement("Battery exemption",
                "Keeps the background guard from being frozen", batteryOk(c)));
        out.add(new Requirement("Autostart",
                "Lets the guard restart itself after a reboot", autostartOk(c)));

        // ---- Shizuku / Device Admin group ----
        boolean admin = deviceAdminOk(c);
        out.add(new Requirement("Device Admin / Owner",
                "The lock cannot be removed before the limit is finished", admin));
        out.add(new Requirement("Shizuku provisioning",
                "One-tap Device Owner path (optional if granted via ADB)", shizukuOk(c)));
        return out;
    }

    // =====================================================================
    // Group verdicts
    // =====================================================================

    /**
     * True when every system-permission probe passes. This is the first half of
     * the transition condition.
     */
    static boolean systemPermissionsOk(Context c) {
        if (c == null) return false;
        return notificationsOk(c)
                && exactAlarmsOk(c)
                && fullScreenOk(c)
                && batteryOk(c)
                && autostartOk(c);
    }

    /**
     * True when the elevated grant exists at all. Satisfied by a real Device
     * Owner grant OR an active Device Admin — both let the lock be enforced and
     * both are accepted by the onboarding CONTINUE action.
     */
    static boolean deviceAdminOk(Context c) {
        if (c == null) return false;
        try {
            return Engine.isDeviceOwner(c) || Engine.isAdminActive(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The single boolean the automatic transition is gated on: System
     * Permissions <b>and</b> the Shizuku/Device Admin grant are all verified as
     * active.
     */
    static boolean allVerified(Context c) {
        return systemPermissionsOk(c) && deviceAdminOk(c);
    }

    // =====================================================================
    // Individual live probes (all defensive: any failure reads as "not ready")
    // =====================================================================

    static boolean notificationsOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean exactAlarmsOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 31) return true;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean fullScreenOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 34) return true; // granted at install below 34
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean batteryOk(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mirrors {@link PermissionFlow#isAutostartOk(Context)}. */
    static boolean autostartOk(Context c) {
        try {
            if (Engine.isDeviceOwner(c) || Engine.isAdminActive(c)) return true;
        } catch (Throwable ignored) { /* fall through to attestation */ }
        try {
            return Prefs.autostartAttested(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Live Shizuku client state — informational, used for the group detail. */
    static boolean shizukuOk(Context c) {
        try {
            return ShizukuBridge.isManagerInstalled(c)
                    && ShizukuBridge.isServiceAlive()
                    && ShizukuBridge.isPermissionGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
