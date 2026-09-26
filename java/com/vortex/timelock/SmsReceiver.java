package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Incoming-SMS notifier for the kiosk's Messages screen and dock.
 *
 * <p>The retreat reads its conversations straight out of the system SMS provider,
 * so a freshly delivered message is already visible the next time the thread list
 * is rebuilt — this receiver exists purely so an <em>open</em> surface can react
 * the instant a text lands instead of waiting for the next tick.
 *
 * <p>It deliberately keeps no state and — as of this revision — supports
 * <em>several</em> listeners at once. Two surfaces now care about an incoming
 * text: the Messages screen (which refreshes the open thread or list) and the
 * lock screen itself (which floats a heads-up banner over whatever tab is
 * showing and keeps the dock badge honest). A {@link CopyOnWriteArraySet} lets
 * both register and unregister independently without one clobbering the other
 * (the old single-slot {@code setListener} meant the last registrant silently
 * won).
 *
 * <p>{@code SMS_RECEIVED} is an ordered broadcast that the platform also hands to
 * the default SMS app; we never {@code abortBroadcast()} and never touch the
 * notification, so this receiver can neither swallow a message nor interfere with
 * whatever app the user has set as their default messenger. All it does is parse
 * the PDUs and ping the listeners. Everything here is wrapped: a malformed PDU or
 * a dead listener must never take the guard process down.
 */
public class SmsReceiver extends BroadcastReceiver {

    static final String TAG = "TL.SmsRx";

    /** Implemented by any surface that wants to hear about an incoming text. */
    interface Listener {
        void onIncomingSms(String address, String body, long whenMs);
    }

    private static final CopyOnWriteArraySet<Listener> sListeners = new CopyOnWriteArraySet<>();

    /** Register a listener (idempotent). Invoked on the broadcast thread. */
    static void addListener(Listener l) {
        if (l != null) sListeners.add(l);
    }

    /** Unregister a listener; a no-op when it was never registered. */
    static void removeListener(Listener l) {
        if (l != null) sListeners.remove(l);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) return;

            SmsMessage[] messages = extract(intent);
            if (messages == null || messages.length == 0) return;

            String lastAddress = null;
            String lastBody = null;
            long lastWhen = System.currentTimeMillis();

            for (SmsMessage m : messages) {
                if (m == null) continue;
                String addr = m.getOriginatingAddress();
                String body = m.getMessageBody();
                long when = m.getTimestampMillis();
                if (when <= 0L) when = lastWhen;
                lastAddress = addr;
                lastBody = body;
                lastWhen = when;
            }

            if (lastBody == null || sListeners.isEmpty()) return;

            // Snapshot iteration: CopyOnWriteArraySet is safe to walk while a
            // listener (un)registers from the UI thread.
            for (Iterator<Listener> it = sListeners.iterator(); it.hasNext(); ) {
                Listener l = it.next();
                try {
                    l.onIncomingSms(lastAddress, lastBody, lastWhen);
                } catch (Throwable t) {
                    Log.w(TAG, "listener", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onReceive", t);
        }
    }

    /** PDU extraction across the API 4..33 signature churn, guarded as always. */
    private static SmsMessage[] extract(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 19) {
                return Telephony.Sms.Intents.getMessagesFromIntent(intent);
            }
            Object[] pdus = (Object[]) intent.getSerializableExtra("pdus");
            if (pdus == null) return null;
            SmsMessage[] out = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                out[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "extract", t);
            return null;
        }
    }
}
