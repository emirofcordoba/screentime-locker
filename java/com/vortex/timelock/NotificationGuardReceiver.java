package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Notification keep-alive receiver.
 *
 * <p>Referenced by {@link TimeLockService#keepAliveIntent()} and
 * {@link TimeLockService#requestKeepAlive(Context)} as the target of the
 * foreground-service notification's {@code deleteIntent}. Android 13+/14 lets the
 * user swipe a foreground-service notification away for some service types; when
 * that happens this receiver fires and re-asserts the foreground state, so
 * clearing the shade can never silence the guard or leave the process killable.
 *
 * <p>Declared in the manifest (exported=false) because the broadcast is delivered
 * to an explicit component. It performs no work of its own beyond handing off to
 * the service, so it is O(microseconds) and cannot be killed mid-transaction.
 */
public class NotificationGuardReceiver extends BroadcastReceiver {

    static final String TAG = "TL.NotifGuard";

    /** deleteIntent action: the persistent surface was cleared / swiped away. */
    static final String ACTION_NOTIF_DISMISSED = "com.vortex.timelock.NOTIF_DISMISSED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "receive: " + action);
        // Re-anchor the running instance, or bootstrap the engine if it is gone.
        TimeLockService.requestKeepAlive(context);
    }
}
