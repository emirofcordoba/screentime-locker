package com.vortex.timelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ApprovalSequence — a fail-safe, multi-step, fully isolated consent chain.
 *
 * The sequence is the ONLY way to activate a privileged action. It presents a
 * series of SEPARATE {@link AlertDialog} instances (one per {@link Step}), each
 * uncancellable by touch-outside or Back, each requiring an explicit positive
 * action (a tap, a ticked acknowledgement, or an exactly typed phrase). If the
 * user declines at ANY link, the whole chain collapses, the barrier is re-armed
 * and nothing is executed.
 *
 * There is no timer, no Handler and no polling anywhere in this class. Progress
 * is tracked purely through {@link ApprovalState} persistent reads/writes, which
 * are consulted only at the discrete moments a dialog is shown or a step is
 * passed. Between those moments the barrier simply rests in storage, costing no
 * CPU and no battery.
 */
final class ApprovalSequence {

    private ApprovalSequence() {}

    // =====================================================================
    // A single isolated dialog in the chain
    // =====================================================================

    static final class Step {
        final String title;
        final String message;
        final String confirmLabel;
        final String abortLabel;
        /** If non-null the user must type this phrase verbatim to pass. */
        final String requireTypedPhrase;
        /** If non-null the user must tick this acknowledgement to pass. */
        final String ackLabel;
        final boolean destructive;

        Step(String title, String message, String confirmLabel, String abortLabel,
             String requireTypedPhrase, String ackLabel, boolean destructive) {
            this.title = title;
            this.message = message;
            this.confirmLabel = confirmLabel;
            this.abortLabel = abortLabel;
            this.requireTypedPhrase = requireTypedPhrase;
            this.ackLabel = ackLabel;
            this.destructive = destructive;
        }
    }

    // =====================================================================
    // The two concrete chains
    // =====================================================================

    /** Chain that guards completing the setup / concealing the app. */
    static List<Step> setupSteps(Context c) {
        List<Step> out = new ArrayList<>();
        out.add(new Step(
                "Review what will be locked in",
                "Daily interval:  " + humanDuration(Prefs.limitMs(c) / 60_000L)
                        + "\nResets at:  " + hhmm(Prefs.anchorMin(c))
                        + "\nWeekly schedule:  " + ScheduleConfig.summary(c)
                        + "\n\nWhen the interval is used up a full-screen kiosk lock appears with "
                        + "the time left and the unlock time. There is no exit except waiting. "
                        + "The launcher icon is hidden and notification noise is silenced.",
                "Continue", "Cancel", null, null, false));
        out.add(new Step(
                "Acknowledge the consequences",
                "You will not be able to change this configuration without your fingerprint "
                        + "and the secret dial code. External removal of "
                        + "Device Owner drops the hard lock to a soft lock only.",
                "I understand", "Cancel", null,
                "I understand the device will be bound to this screen-time policy", false));
        out.add(new Step(
                "Type to confirm",
                "Type the phrase below exactly to authorise completing the setup.",
                "Confirm", "Cancel", "LOCK IN", null, false));
        out.add(new Step(
                "Final confirmation",
                "Completing setup hides the app and engages the screen-time policy immediately. "
                        + "It is reversible only through the admin console.",
                "COMPLETE SETUP", "Cancel", null, null, true));
        return out;
    }

    /**
     * Chain that guards the activation of the privileged root-level restrictions
     * (blocking Safe Mode access and Factory Reset commands). It is built to
     * match exactly what the user has requested, and it cannot be satisfied
     * without a distinct approval dialog per requested restriction.
     */
    static List<Step> restrictionSteps(Context c) {
        List<Step> out = new ArrayList<>();

        StringBuilder names = new StringBuilder();
        if (Prefs.optSafeBoot(c))    names.append("\u2022  Block Safe Mode access\n");
        if (Prefs.optFactoryReset(c)) names.append("\u2022  Block Factory Reset\n");

        out.add(new Step(
                "Root-level device restrictions",
                "You have requested privileged, device-owner restrictions:\n\n" + names
                        + "\nThese map to the system UserManager restrictions DISALLOW_SAFE_BOOT "
                        + "and DISALLOW_FACTORY_RESET. They are only honoured while this app is "
                        + "the real Device Owner.",
                "Continue", "Decline", null, null, false));

        if (Prefs.optSafeBoot(c)) {
            out.add(new Step(
                    "Approve blocking Safe Mode",
                    "Safe Mode boots Android with third-party apps disabled. Blocking it stops the "
                            + "screen-time lock from being bypassed at boot.",
                    "Approve", "Decline", null,
                    "I approve blocking Safe Mode access", false));
        }

        if (Prefs.optFactoryReset(c)) {
            out.add(new Step(
                    "Approve blocking Factory Reset",
                    "Blocking the factory reset removes the Settings \u2192 Reset escape route. "
                            + "Recovery from the bootloader is unaffected.",
                    "Approve", "Decline", null,
                    "I approve blocking Factory Reset", false));
        }

        out.add(new Step(
                "Final authorisation",
                "Type the word below exactly to authorise applying these root-level restrictions "
                        + "now. This authorisation is bound to the current configuration and will "
                        + "re-arm automatically if you change it.",
                "Authorise", "Decline", "APPROVE", null, true));
        return out;
    }

    // =====================================================================
    // Runner
    // =====================================================================

    /**
     * Walk [steps] one isolated dialog at a time. Runs [onApproved] only after the
     * final step is passed (the barrier state is persisted as "done" first), or
     * [onAborted] the moment any step is declined.
     */
    static void run(final Activity a, final String scope, final List<Step> steps,
                    final Runnable onApproved, final Runnable onAborted) {
        if (a == null || a.isFinishing()) {
            if (onAborted != null) onAborted.run();
            return;
        }
        if (steps == null || steps.isEmpty()) {
            ApprovalState.markChainDone(a, scope);
            if (onApproved != null) onApproved.run();
            return;
        }
        ApprovalState.markChainRunning(a, scope);
        showStep(a, scope, steps, 0, onApproved, onAborted);
    }

    private static void showStep(final Activity a, final String scope, final List<Step> steps,
                                 final int index, final Runnable onApproved, final Runnable onAborted) {
        if (a.isFinishing()) {
            abort(a, scope, onAborted);
            return;
        }
        if (index >= steps.size()) {
            // Persist "done" BEFORE handing control back, so authorisation can be
            // minted only from a storage state that proves the chain was walked.
            ApprovalState.markChainDone(a, scope);
            if (onApproved != null) onApproved.run();
            return;
        }

        final Step s = steps.get(index);
        ApprovalState.setProgress(a, scope, index);

        final EditText phrase = s.requireTypedPhrase == null ? null : new EditText(a);
        final CheckBox ack = s.ackLabel == null ? null : new CheckBox(a);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 22), dp(a, 16), dp(a, 22), dp(a, 6));

        TextView msg = new TextView(a);
        msg.setText(s.message);
        msg.setTextSize(14.5f);
        msg.setLineSpacing(dp(a, 4), 1f);
        body.addView(msg);

        if (ack != null) {
            ack.setText(s.ackLabel);
            ack.setTextSize(14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 16);
            ack.setLayoutParams(lp);
            body.addView(ack);
        }

        final TextView error = new TextView(a);
        error.setTextSize(13f);
        error.setTextColor(0xFFFF5252);
        error.setVisibility(View.GONE);

        if (phrase != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(a, 16);
            phrase.setLayoutParams(plp);
            phrase.setHint("Type \"" + s.requireTypedPhrase + "\"");
            phrase.setInputType(InputType.TYPE_CLASS_TEXT);
            phrase.setSingleLine(true);
            body.addView(phrase);

            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(a, 8);
            error.setLayoutParams(elp);
            body.addView(error);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle(s.title);
        b.setView(body);
        b.setCancelable(false);
        b.setPositiveButton(s.confirmLabel, null);           // wired in onShow
        b.setNegativeButton(s.abortLabel, (d, w) -> abort(a, scope, onAborted));

        final AlertDialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (phrase != null
                            && !s.requireTypedPhrase.contentEquals(
                                    phrase.getText().toString().trim())) {
                        error.setVisibility(View.VISIBLE);
                        error.setText("Phrase does not match. Type \"" + s.requireTypedPhrase
                                + "\" to continue.");
                        return;
                    }
                    if (ack != null && !ack.isChecked()) {
                        error.setVisibility(View.VISIBLE);
                        error.setText("Tick the acknowledgement to continue.");
                        return;
                    }
                    dialog.dismiss();
                    showStep(a, scope, steps, index + 1, onApproved, onAborted);
                }));
        dialog.show();
    }

    private static void abort(Activity a, String scope, Runnable onAborted) {
        ApprovalState.markChainIdle(a, scope);
        ApprovalState.revoke(a, scope);
        if (onAborted != null) onAborted.run();
    }

    // =====================================================================
    // Small formatters (kept local so the sequence stays self-contained)
    // =====================================================================

    /**
     * "11:59 PM" from a minute-of-day. Same clock convention as the lock screen:
     * the operator approving a change reads the reset instant exactly as the
     * locked-out user will see it. (Durations keep their own HH:MM:SS form.)
     */
    private static String hhmm(int anchorMin) {
        int m = ((anchorMin % 1440) + 1440) % 1440;
        int h = m / 60;
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m % 60, h < 12 ? "AM" : "PM");
    }

    private static String humanDuration(long minutes) {
        if (minutes <= 0) return "not set";
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " min";
        if (m == 0) return h + (h == 1 ? " hour" : " hours");
        return h + " h " + m + " min";
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
}
