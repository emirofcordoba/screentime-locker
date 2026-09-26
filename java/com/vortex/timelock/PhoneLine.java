package com.vortex.timelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * The kiosk's telephony line state, entirely in-process.
 *
 * <p>The retreat already knows how to <em>originate</em> a call ({@link Calls})
 * and how to read the SMS provider ({@link SmsStore}). What was missing is the
 * piece that turns a phone call into something the lock screen can render: a
 * single authority for "is there a call, who is it, and is it ringing or live".
 *
 * <p>This class listens to {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED}
 * and collapses Android's three states into one small enum
 * ({@link State#IDLE}, {@link State#RINGING}, {@link State#OFFHOOK}) plus the
 * remote number. It also owns the three call actions the kiosk needs:
 * {@link #place} (originate), {@link #answer} (pick up a ringing call) and
 * {@link #end} (hang up / decline) — all through the platform's own
 * {@link TelecomManager}, never a second dialer app.
 *
 * <p><b>Why this is the right primitive for a kiosk.</b> Because we are the
 * Device Owner in lock task, the system's own in-call UI cannot come to the
 * front: lock task blocks any activity that is not whitelisted, and we do not
 * whitelist the dialer. So the call is real and alive on the radio, but the only
 * thing the user can see is our dedicated in-call overlay — which is exactly what
 * {@link InCallScreen} paints, driven by this class's callbacks. Nothing here
 * ever calls {@code stopLockTask()}: the kiosk never yields the screen, not even
 * for a call.
 *
 * <p><b>Honesty and safety.</b> Every platform call is wrapped. {@code end()} on
 * API 26/27 would need {@code MODIFY_PHONE_STATE} (system-only) and so may be
 * refused — in that case the method returns {@code false} and the overlay keeps
 * showing the line until the far end hangs up, rather than pretending it ended.
 * The receiver is seeded from {@link TelephonyManager#getCallState()} on
 * {@link #start()} so a call that was already ringing when the kiosk came up is
 * not missed.
 */
final class PhoneLine {

    private static final String TAG = "TL.Phone";

    /** The only three states the kiosk cares about. */
    enum State { IDLE, RINGING, OFFHOOK }

    /** Implemented by the host; invoked on the broadcast thread. */
    interface Listener {
        void onCallState(State state, String number);
    }

    private final Context app;
    private final Listener listener;

    private boolean registered = false;
    private State state = State.IDLE;
    private String number = "";

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null) return;
                if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
                String st = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (TelephonyManager.EXTRA_STATE_RINGING.equals(st)) {
                    String inc = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    if (!TextUtils.isEmpty(inc)) number = inc;
                    update(State.RINGING);
                } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)) {
                    update(State.OFFHOOK);
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
                    update(State.IDLE);
                }
            } catch (Throwable t) {
                Log.w(TAG, "onReceive", t);
            }
        }
    };

    PhoneLine(Context c, Listener l) {
        this.app = c.getApplicationContext();
        this.listener = l;
    }

    State state() { return state; }
    String number() { return number; }

    /** Begin watching the line; idempotent. Seeds the current state immediately. */
    void start() {
        if (registered) return;
        try {
            app.registerReceiver(rx,
                    new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
            registered = true;
        } catch (Throwable t) {
            Log.w(TAG, "register", t);
        }
        seed();
    }

    /** Stop watching the line; idempotent. */
    void stop() {
        if (!registered) return;
        try { app.unregisterReceiver(rx); } catch (Throwable ignored) { }
        registered = false;
    }

    private void seed() {
        try {
            TelephonyManager tm =
                    (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            int s = tm.getCallState();
            State cur = s == TelephonyManager.CALL_STATE_RINGING ? State.RINGING
                    : s == TelephonyManager.CALL_STATE_OFFHOOK ? State.OFFHOOK
                    : State.IDLE;
            update(cur);
        } catch (Throwable t) {
            Log.w(TAG, "seed", t);
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * Originate a call. On success the state is moved to {@link State#OFFHOOK}
     * optimistically so the overlay appears on the same frame the user tapped,
     * rather than waiting for the first {@code OFFHOOK} broadcast.
     */
    boolean place(String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        boolean ok = Calls.place(app, raw);
        if (ok) {
            number = Calls.normalize(raw);
            update(State.OFFHOOK);
        }
        return ok;
    }

    /** Answer a ringing call. Returns {@code true} when the platform accepted it. */
    boolean answer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                TelecomManager tm =
                        (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.acceptRingingCall();
                    update(State.OFFHOOK);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "answer", t);
        }
        return false;
    }

    /**
     * Hang up the live call, or decline a ringing one. Needs {@code ANSWER_PHONE_CALLS}
     * on API 28+ (we declare it); on API 26/27 the platform requires the
     * system-only {@code MODIFY_PHONE_STATE} and will refuse — we then report
     * {@code false} and leave the state alone instead of lying.
     */
    boolean end() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                TelecomManager tm =
                        (TelecomManager) app.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.endCall();
                    update(State.IDLE);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "end", t);
        }
        return false;
    }

    private void update(State s) {
        if (s == state) return;
        state = s;
        if (s == State.IDLE) number = "";
        final Listener l = listener;
        if (l != null) {
            final String n = number;
            try { l.onCallState(s, n); } catch (Throwable t) { Log.w(TAG, "listener", t); }
        }
    }
}
