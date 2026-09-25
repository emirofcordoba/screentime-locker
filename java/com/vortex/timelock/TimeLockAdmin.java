package com.vortex.timelock;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Device Owner / Device Admin receiver.
 *
 * Accepts ownership both ways:
 *   adb shell dpm set-device-owner com.vortex.timelock/.TimeLockAdmin
 * and an in-place hand-over from another DPC (for example Dhizuku):
 *   DevicePolicyManager.transferOwnership(dhizukuAdmin, THIS, extras)
 *
 * For transferOwnership() to be accepted, the platform checks that the target
 * receiver is declared with <support-transfer-ownership /> in its device-admin
 * XML (see res/xml/device_admin.xml) AND that this receiver implements
 * onTransferOwnershipComplete(). Both are in place, which removes the old
 * "Provided target does not support ownership transfer" exception.
 *
 * No Dhizuku/Shizuku APIs are used here: only real DevicePolicyManager semantics.
 */
public class TimeLockAdmin extends DeviceAdminReceiver {

    static final String TAG = "TL.Admin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "admin enabled");
        Engine.bootstrap(context);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(TAG, "admin disabled");
        // Be defensive: if ownership was removed externally, drop any policy that
        // could otherwise leave the device in a half-locked state.
        if (!Engine.isDeviceOwner(context)) {
            Engine.setLockActivityEnabled(context, false);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED.equals(action)
                || "android.app.action.PROFILE_PROVISIONING_COMPLETE".equals(action)
                || "android.app.action.DEVICE_OWNER_CHANGED".equals(action)
                // ACTION_TRANSFER_OWNERSHIP_COMPLETE is a real framework broadcast
                // action, but the symbol is stripped from the public SDK stub jar,
                // so reference the literal (same style as the two actions above).
                || "android.app.action.TRANSFER_OWNERSHIP_COMPLETE".equals(action)) {
            Log.i(TAG, "owner event: " + action);
            Engine.selfGrantNotifications(context);
            Engine.bootstrap(context);
            Engine.reevaluate(context, "owner-event:" + action);
        }
    }

    /**
     * Called on the NEW owner once ownership transfer from another DPC succeeds.
     * This is the callback that makes the Dhizuku hand-over usable.
     */
    @Override
    public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
        Log.i(TAG, "ownership transfer complete");
        Prefs.setTransferred(context, true);
        Engine.selfGrantNotifications(context);
        Engine.bootstrap(context);
        // Re-assert whichever state we are supposed to be in right now.
        Engine.reevaluate(context, "transfer-complete");
        Engine.applyLockState(context, Engine.shouldBeLocked(context, System.currentTimeMillis()));
    }

    static ComponentName component(Context ctx) {
        return new ComponentName(ctx.getApplicationContext(), TimeLockAdmin.class);
    }
}
