package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Power-state watchdog for PERMISSION PERMANENCE.
 *
 * <p>The one permission-adjacent state Android lets the user (or the system)
 * silently flip back is the battery-optimization / Doze posture. There is no
 * Device-Owner API that pins that exemption, so this receiver watches the two
 * platform broadcasts that fire exactly when the posture changes and re-asserts
 * the whole permanence layer immediately:
 *
 *   - {@link PowerManager#ACTION_DEVICE_IDLE_MODE_CHANGED} : entering/leaving Doze.
 *   - {@link PowerManager#ACTION_POWER_SAVE_MODE_CHANGED}  : Battery Saver toggled.
 *
 * <p>{@link Engine#enforcePermissionPermanence(Context)} is owner-gated, idempotent
 * and cheap: it re-pins the runtime permission set (notification included), keeps
 * the uninstall block in place and forces the App-Standby flag off. If the battery
 * exemption itself was dropped, {@link Engine#reassertBatteryIfLost} restores it the
 * next time the app is on screen (a background receiver cannot start that dialog on
 * Android 29+).
 *
 * <p>No work is done unless the app is the real Device Owner, so this is free on an
 * un-provisioned install. Both broadcasts are protected system broadcasts, so the
 * receiver is declared exported="false" and cannot be spoofed.
 */
public class PowerStateReceiver extends BroadcastReceiver {

    static final String TAG = "TL.Power";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "power-state change: " + action);
        try {
            if (Engine.isDeviceOwner(context)) {
                Engine.enforcePermissionPermanence(context);
            }
        } catch (Throwable t) {
            Log.w(TAG, "power-state re-assert failed", t);
        }
    }
}
