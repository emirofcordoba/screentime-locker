package com.vortex.timelock;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Digital Retreat — Shizuku transport.
 *
 * Thin, defensive wrapper over the bundled Shizuku 13.1.5 client (rikka.shizuku.*)
 * and its binder AIDL (moe.shizuku.server.*). It is the ONE place the app talks
 * to Shizuku, so the rest of the UI never touches a binder directly.
 *
 * <p>What it gives the onboarding screen:
 * <ul>
 *   <li>availability + permission probes (is Shizuku installed / running /
 *       authorised for this app);</li>
 *   <li>a shell runner ({@link #run}) that executes a command through the
 *       Shizuku service and returns its exit code and combined output;</li>
 *   <li>the Device-Owner task itself ({@link #provisionDeviceOwner}): run
 *       {@code pm list users}, remove every secondary user, then run
 *       {@code dpm set-device-owner}. That last step is exactly the ADB command
 *       the screen shows, only executed live over Shizuku instead of a PC.</li>
 * </ul>
 *
 * <p>Every call is wrapped: a missing binder, a revoked permission or any
 * RemoteException degrades to a readable message instead of a crash. Nothing is
 * assumed about the Shizuku version beyond the v13 handshake the client ships.
 */
final class ShizukuBridge {

    static final String TAG = "TL.Shizuku";

    /** The Shizuku manager package (the app the user installs and starts). */
    static final String MANAGER_PKG = "moe.shizuku.privileged.api";

    /** Request code for Shizuku's own per-app permission dialog. */
    static final int REQ_PERMISSION = 8465;

    private ShizukuBridge() {}

    // ================================================================= availability

    /** True when the Shizuku manager app is installed on the device. */
    static boolean isManagerInstalled(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(MANAGER_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the Shizuku service binder is reachable (service started + bound). */
    static boolean isServiceAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this app has been granted Shizuku's runtime permission. */
    static boolean isPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ask Shizuku's manager to show its per-app permission dialog. */
    static void requestPermission() {
        try {
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission", t);
        }
    }

    // ================================================================= listeners

    /** Register a per-app permission result listener (safe no-op on failure). */
    static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.addRequestPermissionResultListener(l); } catch (Throwable t) { /* ignore */ }
    }

    /** Remove a previously registered permission result listener. */
    static void removePermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try { Shizuku.removeRequestPermissionResultListener(l); } catch (Throwable t) { /* ignore */ }
    }

    /** Register a sticky binder-received listener (fires immediately when alive). */
    static void addBinderListener(Shizuku.OnBinderReceivedListener l) {
        try { Shizuku.addBinderReceivedListenerSticky(l); } catch (Throwable t) { /* ignore */ }
    }

    /** Remove a previously registered binder-received listener. */
    static void removeBinderListener(Shizuku.OnBinderReceivedListener l) {
        try { Shizuku.removeBinderReceivedListener(l); } catch (Throwable t) { /* ignore */ }
    }

    /** Human-readable Shizuku server version, or "" when unavailable. */
    static String version() {
        try {
            int v = Shizuku.getVersion();
            return v <= 0 ? "" : ("v" + v);
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================= result model

    /** Outcome of one shell invocation. */
    static final class Exec {
        boolean ok;          // started and exit code == 0
        int exitCode = -1;
        String output = "";  // combined stdout + stderr, trimmed
        String error;        // transport failure reason (null when it ran)

        boolean ran() { return error == null; }
    }

    /**
     * Run one command through the Shizuku shell and capture its output.
     * Never throws: transport problems come back as {@link Exec#error}.
     */
    static Exec run(String command) {
        Exec r = new Exec();
        try {
            if (!isServiceAlive()) {
                r.error = "Shizuku service is not running";
                return r;
            }
            if (!isPermissionGranted()) {
                r.error = "Shizuku permission not granted";
                return r;
            }
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                r.error = "Shizuku binder unavailable";
                return r;
            }
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            if (svc == null) {
                r.error = "Shizuku service interface unavailable";
                return r;
            }
            IRemoteProcess p = svc.newProcess(new String[]{"sh", "-c", command}, null, null);
            if (p == null) {
                r.error = "Shizuku refused to start the command";
                return r;
            }
            String out = readFd(p.getInputStream());
            String err = readFd(p.getErrorStream());
            int code = p.waitFor();
            r.exitCode = code;
            r.ok = (code == 0);
            StringBuilder sb = new StringBuilder();
            if (out != null && out.trim().length() > 0) sb.append(out.trim());
            if (err != null && err.trim().length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(err.trim());
            }
            r.output = sb.toString();
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "run: " + command, t);
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    private static String readFd(ParcelFileDescriptor pfd) {
        if (pfd == null) return null;
        try {
            InputStream is = new FileInputStream(pfd.getFileDescriptor());
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { pfd.close(); } catch (Throwable ignored) {}
        }
    }

    // ================================================================= user parsing

    /** Every user id reported by {@code pm list users}. */
    static List<Integer> parseUserIds(String pmListUsersOutput) {
        List<Integer> ids = new ArrayList<>();
        if (pmListUsersOutput == null) return ids;
        Matcher m = Pattern.compile("UserInfo\\{(\\d+):").matcher(pmListUsersOutput);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1));
                if (!ids.contains(id)) ids.add(id);
            } catch (Throwable ignored) {}
        }
        return ids;
    }

    // ================================================================= error surface

    /**
     * Format ANY execution failure as ONE line, in the compact {@code Type:
     * message} shape of a JavaScript exception, instead of exposing a bare
     * numeric error/exit code.
     *
     * <p>The gatekeeper's error surface shows exactly this: the whole reason is
     * folded onto a single line (newlines and whitespace runs collapsed to single
     * spaces) and prefixed {@code Error:} — the canonical single-line JavaScript
     * exception form — so a "standard error code" (e.g. {@code exit 1}) is never
     * shown on its own. The app ships no JavaScript runtime; this only reproduces
     * that exception STRING shape for display, while preserving the underlying
     * cause so the failure stays diagnosable.
     */
    static String formatExecutionError(String detail) {
        String one = (detail == null) ? "" : detail.replace('\r', ' ').replace('\n', ' ');
        one = one.replaceAll("\\s{2,}", " ").trim();
        if (one.isEmpty()) one = "unknown failure";
        return "Error: " + one;
    }

    // ================================================================= the task

    /** Everything the Shizuku approval produced, for display on the screen. */
    static final class ProvisionReport {
        boolean success;
        String adbCommand = "";
        String usersOutput = "";
        List<Integer> removedUsers = new ArrayList<>();
        String removeOutput = "";
        String ownerOutput = "";
        String failure;
    }

    /** The exact ADB command that grants Device Owner to this app. */
    static String deviceOwnerCommand(Context c) {
        return "dpm set-device-owner " + c.getPackageName() + "/.TimeLockAdmin";
    }

    /**
     * The full "approve by Shizuku" chain:
     *   1. {@code pm list users}
     *   2. {@code pm remove-user <id>} for every user that is NOT 0
     *   3. {@code dpm set-device-owner <pkg>/.TimeLockAdmin}
     *
     * Blocking; call off the main thread. Returns a populated report either way.
     */
    static ProvisionReport provisionDeviceOwner(Context c) {
        ProvisionReport rep = new ProvisionReport();
        rep.adbCommand = deviceOwnerCommand(c);

        if (!isServiceAlive()) {
            rep.failure = formatExecutionError(
                    "Shizuku service is not running - open Shizuku and start it first");
            return rep;
        }
        if (!isPermissionGranted()) {
            rep.failure = formatExecutionError(
                    "Shizuku permission is not granted for this app yet");
            return rep;
        }

        // 1. enumerate users
        Exec users = run("pm list users");
        rep.usersOutput = users.ran() ? users.output : ("(failed) " + users.error);
        List<Integer> ids = parseUserIds(users.output);

        // 2. remove every secondary user (anything other than 0)
        StringBuilder remLog = new StringBuilder();
        for (Integer id : ids) {
            if (id == null || id == 0) continue;
            Exec rem = run("pm remove-user " + id);
            rep.removedUsers.add(id);
            remLog.append("pm remove-user ").append(id).append("  ->  exit ").append(rem.exitCode);
            if (rem.output.length() > 0) remLog.append('\n').append(rem.output);
            remLog.append('\n');
        }
        if (remLog.length() == 0) remLog.append("No secondary users found — nothing to remove.\n");
        rep.removeOutput = remLog.toString().trim();

        // 3. grant Device Owner (the same command shown on screen, run live)
        Exec owner = run(rep.adbCommand);
        rep.ownerOutput = owner.ran()
                ? (owner.output.length() > 0 ? owner.output : "(no output)")
                : ("(failed) " + owner.error);

        rep.success = owner.ran() && owner.ok;
        if (!rep.success && owner.ran()) {
            String reason = owner.output.isEmpty() ? "no output" : owner.output;
            rep.failure = formatExecutionError("dpm set-device-owner "
                    + c.getPackageName() + "/.TimeLockAdmin failed - " + reason
                    + " - remove every account/user and retry");
        } else if (!owner.ran()) {
            rep.failure = formatExecutionError(owner.error);
        }
        return rep;
    }
}
