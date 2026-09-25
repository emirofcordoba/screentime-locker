package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

/**
 * Reboot / update resilience, now included in DIRECT BOOT.
 *
 * The receiver intentionally runs on both sides of the user unlock:
 *
 *   - PRE-UNLOCK (LOCKED_BOOT_COMPLETED, or any boot action that arrives while
 *     the credential-encrypted store is still locked): only the tiny
 *     device-protected mirror {@link BootState} is read, and only Device-Owner
 *     policy calls are made via {@link Engine#reassertKioskSurface}. No CE read,
 *     no activity launch, no runtime permission, and no reliance on an OEM
 *     "autostart" whitelist -> the kiosk surface re-arms itself immediately at
 *     boot and the lock returns on its own once the user unlocks.
 *
 *   - POST-UNLOCK (BOOT_COMPLETED / QUICKBOOT_POWERON / MY_PACKAGE_REPLACED /
 *     USER_UNLOCKED once the store is available): the full engine runs, exactly
 *     as before, reconciling usage and re-asserting the complete policy.
 *
 * Nothing here starts an activity directly: the engine refuses to touch the
 * screen unless we are the real Device Owner, and activities are only reached
 * once the credential-encrypted store can be read safely.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String TAG = "TL.Boot";

    /**
     * android.intent.action.LOCKED_BOOT_COMPLETED is not exposed as a constant in
     * the public android.jar stub, so it is spelled out literally here.
     */
    private static final String ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "boot: " + action);
        if (action == null) return;

        // ---- PRE-UNLOCK FAST PATH -------------------------------------------
        // Runs whenever the credential-encrypted store is not readable yet:
        // the explicit locked-boot broadcast, or any boot action that raced
        // ahead of the unlock. Device-protected storage only.
        if (ACTION_LOCKED_BOOT_COMPLETED.equals(action) || !isUserUnlocked(context)) {
            reassertPreUnlock(context, action);
            return;
        }

        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action);
        if (!isBoot) return;

        try {
            // Run the durable-store migration FIRST, before anything re-asserts the
            // lock: this opens the one-time re-configuration window for a legacy
            // kiosk install so the very first boot after the upgrade does not
            // slam the device straight back into the permanent lock.
            DbMigration.run(context);
            // Restore the engine (foreground). It will re-evaluate on start.
            Engine.bootstrap(context);
            // Recompute window/usage and re-assert or release the lock as needed.
            Engine.reevaluate(context, "boot:" + action);

            // Re-assert concealment: a reboot must not resurrect the launcher
            // icon or the noisy monitor notification.
            if (Prefs.activated(context)) {
                Engine.setLauncherVisible(context, false);
                // Re-assert the recent-task visibility blockade on boot. This is
                // already gated on Prefs.activated, and enforceRecentsBlockade
                // re-checks + documents the state in the global log ring.
                Engine.enforceRecentsBlockade(context, "boot");
                Engine.silenceNotifications(context);
                Engine.selfGrantNotifications(context);
                // Pin the runtime permission set (non-revocable) again on boot.
                Engine.applyPermissionLockdown(context, true);
            }
        } catch (Throwable t) {
            Log.e(TAG, "boot handling failed", t);
        }
    }

    /**
     * Read only the device-protected mirror and re-arm the kiosk surface. Never
     * touches credential-encrypted storage and never launches an activity, so it
     * is safe to run the instant the framework is up (before the user unlocks).
     */
    private static void reassertPreUnlock(Context context, String action) {
        try {
            boolean activated = BootState.activated(context);
            boolean locked = BootState.locked(context);
            Log.i(TAG, "pre-unlock re-assert action=" + action
                    + " activated=" + activated + " locked=" + locked);
            if (activated) {
                // Owner-gated internally; a no-op when we are not the owner.
                Engine.reassertKioskSurface(context, locked);
            }
        } catch (Throwable t) {
            Log.e(TAG, "pre-unlock handling failed", t);
        }
    }

    /** True once the credential-encrypted store is available to this user. */
    private static boolean isUserUnlocked(Context c) {
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            // Unknown: prefer the full path (it is internally fail-safe) so a
            // weird ROM still gets the complete engine rather than nothing.
            return true;
        }
    }
}
