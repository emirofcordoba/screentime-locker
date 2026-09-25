package com.vortex.timelock;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserManager;
import android.util.Log;

/**
 * RestrictionGuard — the interceptor between the engine and the two privileged,
 * root-level restrictions (blocking Safe Mode access and Factory Reset).
 *
 * {@link Engine#applyHardening} never touches DISALLOW_SAFE_BOOT or
 * DISALLOW_FACTORY_RESET directly; it delegates here. This class is strictly
 * fail-safe:
 *
 *   - LIFTING a restriction is never gated (the user can always escape), so it
 *     revokes the standing warrant and clears both keys unconditionally.
 *   - ENABLING a restriction requires the live STANDING warrant minted by the
 *     8-tier restriction ladder in {@link ApprovalGate#gateRestrictions}. That
 *     warrant is MAC-bound to the exact requested action set, so it can only
 *     have been produced by walking the whole chain for THIS configuration, and
 *     it stops matching the instant the request changes.
 *   - The decision is ONE synchronous storage read ({@link ApprovalGate#restrictionsGranted})
 *     with no polling, no timer and no wake lock, so the barrier rests for free.
 *   - If the barrier is not satisfied, the restrictions are actively CLEARED
 *     rather than merely skipped, so a stale policy can never survive a failed
 *     check.
 */
final class RestrictionGuard {

    static final String TAG = "TL.Guard";

    private RestrictionGuard() {}

    static void applyRootRestrictions(Context c, DevicePolicyManager d,
                                      ComponentName admin, boolean enable) {
        if (d == null) return;

        if (!enable) {
            ApprovalGate.revoke(c, ApprovalGate.SCOPE_RESTRICTIONS);
            clear(d, admin, UserManager.DISALLOW_SAFE_BOOT);
            clear(d, admin, UserManager.DISALLOW_FACTORY_RESET);
            return;
        }

        if (!ApprovalGate.anyExtremeRequested(c)) {
            clear(d, admin, UserManager.DISALLOW_SAFE_BOOT);
            clear(d, admin, UserManager.DISALLOW_FACTORY_RESET);
            return;
        }

        if (!ApprovalGate.restrictionsGranted(c)) {
            // FAIL-SAFE: no live, matching standing warrant -> withhold activation.
            Log.w(TAG, "root restrictions withheld; the tiered approval barrier is not satisfied");
            clear(d, admin, UserManager.DISALLOW_SAFE_BOOT);
            clear(d, admin, UserManager.DISALLOW_FACTORY_RESET);
            return;
        }

        if (Prefs.optSafeBoot(c)) add(d, admin, UserManager.DISALLOW_SAFE_BOOT);
        else clear(d, admin, UserManager.DISALLOW_SAFE_BOOT);

        if (Prefs.optFactoryReset(c)) add(d, admin, UserManager.DISALLOW_FACTORY_RESET);
        else clear(d, admin, UserManager.DISALLOW_FACTORY_RESET);
    }

    private static void add(DevicePolicyManager d, ComponentName admin, String key) {
        try { d.addUserRestriction(admin, key); }
        catch (Throwable t) { Log.w(TAG, "add " + key, t); }
    }

    private static void clear(DevicePolicyManager d, ComponentName admin, String key) {
        try { d.clearUserRestriction(admin, key); }
        catch (Throwable t) { Log.w(TAG, "clear " + key, t); }
    }
}
