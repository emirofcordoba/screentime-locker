package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Hidden admin entry point. Dialing *#*#84635#*#* opens the setup screen.
 *
 * After activation the setup screen additionally requires a fingerprint match, so
 * this is only a doorbell, not a bypass.
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    static final String TAG = "TL.Secret";
    private static volatile long lastDialMs = 0L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Log.i(TAG, "secret code dialed");
        lastDialMs = System.currentTimeMillis();
        // After a full setup the console component is DISABLED (it is no longer a
        // launchable surface). This dial code is the one authorised door back in,
        // so wake the component for this single visit; the console re-hides
        // itself in onStop().
        Engine.setConsoleEnabled(context, true);
        Intent i = new Intent(context, SetupActivity.class);
        // Flags come from the confirmation-gated helper: the EXCLUDE_FROM_RECENTS
        // flag is only added once the two-step setup is confirmed, so a first-run
        // console launch does not disappear from recents before initialization.
        i.addFlags(Engine.adminEntryFlags(context));
        i.putExtra("via_secret_code", true);
        try {
            context.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
        }
    }

    static boolean isFreshDial() {
        return System.currentTimeMillis() - lastDialMs < 120_000L;
    }
}
