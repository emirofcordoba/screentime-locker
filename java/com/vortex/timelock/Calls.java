package com.vortex.timelock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;

/**
 * Place a phone call from inside the kiosk, without ever growing a dialer app.
 *
 * <p>The retreat is a <em>caller</em>, not a telephony client: it asks the
 * platform's own telecom stack to originate the call and lets the system's
 * in-call UI own the screen afterwards. Three routes are tried, best first, so a
 * device whose OEM has locked one of them down still ends up on a live call:
 *
 * <ol>
 *   <li>{@link TelecomManager#placeCall} — the modern, UI-less originate path.
 *       Requires {@code CALL_PHONE}, which the Device-Owner permission lockdown
 *       pins GRANTED, so no prompt is ever shown.</li>
 *   <li>{@code Intent.ACTION_CALL} — the classic originate path; still needs
 *       {@code CALL_PHONE}.</li>
 *   <li>{@code Intent.ACTION_DIAL} — the dial-only path, which needs no
 *       permission at all. It is a last resort: it opens the system dialer
 *       pre-filled but does <em>not</em> place the call, so the caller still has
 *       to press the green button. It is only reached when both originate routes
 *       are refused, which means the caller at least keeps a working escape hatch
 *       for an emergency number.</li>
 * </ol>
 *
 * <p><b>Never fatal, never lying.</b> Every route is wrapped; the return value
 * says whether a route was actually accepted by the platform, so the UI can tell
 * the truth ("Calling…" vs "Calling isn't available on this device") instead of
 * pretending a call went out.
 */
final class Calls {

    private static final String TAG = "TL.Calls";

    private Calls() { }

    /** True when the device has a telephony radio at all (a call can be placed). */
    static boolean available(Context c) {
        if (c == null) return false;
        try {
            return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
                    || c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        } catch (Throwable t) {
            return true; // err on the side of showing the dialer
        }
    }

    /**
     * Reduce a user-typed string to the characters a {@code tel:} URI accepts:
     * digits and the four dial modifiers {@code + * #}. Everything else (spaces,
     * dashes, parentheses, letters from a smart-dial) is dropped. Returns an empty
     * string when nothing callable remains, so the caller can refuse the request.
     */
    static String normalize(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '+' || ch == '*' || ch == '#') {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * Originate a call to {@code raw}. Returns {@code true} when a route was
     * accepted by the platform (the call is being set up, or the dialer was
     * opened), {@code false} when nothing could handle it.
     */
    static boolean place(Context c, String raw) {
        String number = normalize(raw);
        if (c == null || number.isEmpty()) return false;
        Uri uri = Uri.fromParts("tel", number, null);

        // 1) TelecomManager.placeCall -- originate with no dialer UI.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.placeCall(uri, null);
                    Log.i(TAG, "placeCall " + number);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "placeCall refused, falling back", t);
        }

        // 2) ACTION_CALL -- classic originate.
        try {
            Intent call = new Intent(Intent.ACTION_CALL, uri);
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(call);
            Log.i(TAG, "ACTION_CALL " + number);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_CALL refused, falling back", t);
        }

        // 3) ACTION_DIAL -- no permission needed; fills the dialer and stops.
        try {
            Intent dial = new Intent(Intent.ACTION_DIAL, uri);
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(dial);
            Log.i(TAG, "ACTION_DIAL " + number);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_DIAL failed", t);
        }
        return false;
    }
}
