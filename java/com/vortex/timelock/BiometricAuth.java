package com.vortex.timelock;

import android.app.Activity;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Digital Retreat — native biometric identity module.
 *
 * Identity is never re-proven by recovering a stored secret; it is proven by
 * the platform's own biometric stack. There is no alternate secret and no
 * fallback credential of any kind.
 *
 * It deliberately introduces no third-party dependency — neither AndroidX
 * {@code androidx.biometric} nor Google Play Services — so the standalone
 * aapt2/javac/d8 build pipeline stays intact:
 *
 *   - API 28+  : framework {@link android.hardware.biometrics.BiometricPrompt},
 *                which the OEM may back with fingerprint or a stronger sensor;
 *   - API 26-27: framework {@link FingerprintManager}.
 *
 * In both paths the success callback fires ONLY after the secure hardware
 * reports a genuine match against an enrolled fingerprint. The callback is
 * therefore the authorisation itself: on success the caller is unlocked with
 * no secret left to type and no secret left to retrieve.
 */
final class BiometricAuth {

    static final String TAG = "TL.Biometric";

    /** Result of an authentication attempt; always delivered on the main thread. */
    interface Callback {
        /** A real, enrolled fingerprint matched. This authorises the caller. */
        void onSuccess();

        /** No match, cancel or error. {@code recoverable} = the user may retry. */
        void onFailure(CharSequence message, boolean recoverable);
    }

    /** The prompt currently on screen, if any, so a second one can never stack. */
    private static CancellationSignal inFlight;

    private BiometricAuth() {}

    // ================================================================ capability

    /** True when the device exposes fingerprint hardware at all. */
    static boolean hasHardware(Context c) {
        try {
            FingerprintManager fm = fp(c);
            return fm != null && fm.isHardwareDetected();
        } catch (Throwable t) {
            Log.w(TAG, "hasHardware", t);
            return false;
        }
    }

    /** True when hardware exists AND at least one fingerprint is enrolled. */
    static boolean isEnrolled(Context c) {
        try {
            FingerprintManager fm = fp(c);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Throwable t) {
            Log.w(TAG, "isEnrolled", t);
            return false;
        }
    }

    /**
     * True when the biometric gate can actually be used right now. This is the
     * ONLY credential: when it is false there is no alternate secret to fall
     * back to, by design.
     */
    static boolean isAvailable(Context c) {
        return hasHardware(c) && isEnrolled(c);
    }

    // ================================================================= prompting

    /** Drop any prompt that is still on screen (e.g. the Activity is leaving). */
    static void cancel() {
        CancellationSignal cs = inFlight;
        inFlight = null;
        if (cs != null) {
            try { cs.cancel(); } catch (Throwable ignored) { /* already gone */ }
        }
    }

    /**
     * Show the native biometric prompt and report the outcome through {@code cb}.
     * {@link Callback#onSuccess()} runs only on a genuine fingerprint match.
     */
    static void authenticate(final Activity activity, CharSequence title,
                             CharSequence subtitle, final Callback cb) {
        cancel();
        if (!isAvailable(activity)) {
            cb.onFailure("No enrolled fingerprint on this device", false);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            promptFramework(activity, title, subtitle, cb);
        } else {
            promptLegacy(activity, cb);
        }
    }

    /** API 28+ — the framework BiometricPrompt (no AndroidX required). */
    private static void promptFramework(Activity a, CharSequence title,
                                        CharSequence subtitle, final Callback cb) {
        android.hardware.biometrics.BiometricPrompt prompt =
                new android.hardware.biometrics.BiometricPrompt.Builder(a)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setConfirmationRequired(false)
                        .setNegativeButton("Cancel", a.getMainExecutor(),
                                (dialog, which) -> cb.onFailure("Authentication cancelled", true))
                        .build();

        CancellationSignal cs = new CancellationSignal();
        inFlight = cs;

        prompt.authenticate(cs, a.getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                        inFlight = null;
                        cb.onSuccess();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // A read that did not match. The prompt stays up so the
                        // user can simply try again; this is NOT a hard failure.
                        Log.i(TAG, "fingerprint not recognised");
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        inFlight = null;
                        cb.onFailure(msg, true);
                    }
                });
    }

    /** API 26-27 — legacy framework fingerprint manager. */
    @SuppressWarnings("deprecation")
    private static void promptLegacy(final Activity a, final Callback cb) {
        FingerprintManager fm = fp(a);
        if (fm == null) {
            cb.onFailure("Fingerprint service unavailable", false);
            return;
        }
        CancellationSignal cs = new CancellationSignal();
        inFlight = cs;

        fm.authenticate(null, cs, 0, new FingerprintManager.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                inFlight = null;
                cb.onSuccess();
            }

            @Override
            public void onAuthenticationFailed() {
                Log.i(TAG, "fingerprint not recognised");
            }

            @Override
            public void onAuthenticationError(int code, CharSequence msg) {
                inFlight = null;
                cb.onFailure(msg, true);
            }
        }, new Handler(Looper.getMainLooper()));
    }

    // ================================================================== plumbing

    @SuppressWarnings("deprecation")
    private static FingerprintManager fp(Context c) {
        return (FingerprintManager) c.getSystemService(Context.FINGERPRINT_SERVICE);
    }
}
