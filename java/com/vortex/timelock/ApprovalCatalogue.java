package com.vortex.timelock;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ApprovalCatalogue — the two confirmation dialogues a user must answer.
 *
 * The catalogue was deliberately flattened. Where the previous revision walked
 * the user through a 7-to-8 tier / 14-to-18 checkpoint loop for every critical
 * action, both chains below now present a strict TWO-STEP confirmation dialogue
 * sequence and nothing else:
 *
 *     step 1 of 2   review + acknowledge   (one isolated dialog, one tick)
 *     step 2 of 2   authorise             (one isolated dialog, one typed phrase)
 *
 * The two critical actions keep their own, fully isolated chain:
 *
 *   RESTRICTIONS   guards pinning DISALLOW_SAFE_BOOT / DISALLOW_FACTORY_RESET
 *                  onto the device as Device Owner.
 *   ACTIVATION     guards the final activation of the screen-time policy and
 *                  the concealing of the app.
 *
 * WHAT WAS NOT STRIPPED
 * ---------------------
 * Only the *user-facing step count* was reduced. The integrity machinery behind
 * the dialogs is untouched: every step is still an uncancellable, separately
 * presented dialog; each pass is still written as an HMAC-bound ledger link;
 * the pre-flight guards (device ownership, usable inputs, a way back in) are
 * still evaluated against live state; and a warrant is still minted only after
 * the whole chain re-verifies. A two-step walk is therefore exactly as
 * tamper-evident and self-revoking as the old ladder — it is simply not
 * exhausting to a legitimate owner.
 *
 * Both chains are pure data. They are assembled on demand, presented one dialog
 * at a time, and otherwise cost nothing at all.
 */
final class ApprovalCatalogue {

    /** Verbatim, case-sensitive consent phrases for the second (authorise) step. */
    static final String PHRASE_RESTRICT = "CONFIRM";
    static final String PHRASE_ACTIVATE = "LOCK IN";

    private ApprovalCatalogue() {}

    // =====================================================================
    // Chain 1 — extreme system restrictions (two steps)
    // =====================================================================

    static List<ApprovalCheckpoint> restrictionsChain(Context c) {
        List<ApprovalCheckpoint> out = new ArrayList<>();

        // ---- step 1 of 2: review + acknowledge ----------------------------
        out.add(ApprovalCheckpoint.at(1, 1, "restrictions-review", ApprovalCheckpoint.Kind.ACK)
                .guard(ApprovalCheckpoint.Guard.DEVICE_OWNER)
                .trigger(ApprovalTrigger.ACK_TICKED)
                .title("Step 1 of 2 \\u2014 Review the restrictions")
                .message("These root-level restrictions are honoured only while this app is the "
                        + "real Device Owner, and the ownership is re-read the moment you continue.\n\n"
                        + requestedList(c)
                        + "\nOnce applied they are enforced by the platform itself. Undoing them "
                        + "happens later, deliberately, from this console.")
                .ack("I understand these root-level restrictions will be pinned to this device")
                .buttons("Continue", "Cancel")
                .build());

        // ---- step 2 of 2: authorise ---------------------------------------
        out.add(ApprovalCheckpoint.at(2, 1, "restrictions-authorise", ApprovalCheckpoint.Kind.PHRASE)
                .guard(ApprovalCheckpoint.Guard.EXTREME_REQUESTED)
                .phrase(PHRASE_RESTRICT)
                .trigger(ApprovalTrigger.PHRASE_TYPED)
                .destructive(true)
                .title("Step 2 of 2 \\u2014 Authorise")
                .message("Type the word below EXACTLY, in capitals, to authorise deploying:\n\n"
                        + requestedList(c)
                        + "\nThere is no undo from inside this dialog.")
                .buttons("APPLY", "Cancel")
                .build());

        return out;
    }

    // =====================================================================
    // Chain 2 — activation of the screen-time policy (two steps)
    // =====================================================================

    static List<ApprovalCheckpoint> activationChain(Context c) {
        List<ApprovalCheckpoint> out = new ArrayList<>();

        // ---- step 1 of 2: review + acknowledge ----------------------------
        out.add(ApprovalCheckpoint.at(1, 1, "activation-review", ApprovalCheckpoint.Kind.ACK)
                .guard(ApprovalCheckpoint.Guard.ACTIVATION_INPUTS)
                .trigger(ApprovalTrigger.ACK_TICKED)
                .title("Step 1 of 2 \\u2014 Review the policy")
                .message("Daily interval:  " + human(Prefs.limitMs(c) / 60_000L)
                        + "\nResets at:  " + hhmm(c, Prefs.anchorMin(c))
                        + "\nWeekly schedule:  " + ScheduleConfig.summary(c)
                        + "\n\nWhen the interval is used up a full-screen kiosk appears with the "
                        + "time left and the moment it lifts. There is no exit but waiting, no "
                        + "snooze and no grace period. The launcher icon is hidden and notification "
                        + "noise is silenced.")
                .ack("I understand this device will be bound to this screen-time policy")
                .buttons("Continue", "Cancel")
                .build());

        // ---- step 2 of 2: authorise ---------------------------------------
        out.add(ApprovalCheckpoint.at(2, 1, "activation-authorise", ApprovalCheckpoint.Kind.PHRASE)
                .guard(ApprovalCheckpoint.Guard.RE_ENTRY_CREDENTIAL)
                .phrase(PHRASE_ACTIVATE)
                .trigger(ApprovalTrigger.PHRASE_TYPED)
                .destructive(true)
                .title("Step 2 of 2 \\u2014 Authorise activation")
                .message("Type the phrase below EXACTLY, in capitals. On acceptance the policy is "
                        + "activated, the app is hidden and the engine starts. Changing your mind "
                        + "later goes through the secret dial code and the same identity check.")
                .buttons("ACTIVATE", "Cancel")
                .build());

        return out;
    }

    // =====================================================================
    // Copy helpers
    // =====================================================================

    private static String requestedList(Context c) {
        StringBuilder b = new StringBuilder();
        if (Prefs.optSafeBoot(c)) b.append("\\u2022  Block Safe Mode access  (DISALLOW_SAFE_BOOT)\\n");
        if (Prefs.optFactoryReset(c)) b.append("\\u2022  Block Factory Reset  (DISALLOW_FACTORY_RESET)\\n");
        if (b.length() == 0) b.append("\\u2022  nothing \\u2014 no root-level restriction is requested\\n");
        return b.toString();
    }

    /**
     * The reset instant from a minute-of-day, rendered in the user's own 12/24-hour
     * form (see {@link TimeFmt}), so every surface that names a wall-clock instant
     * agrees.
     */
    private static String hhmm(Context c, int anchorMin) {
        return TimeFmt.clockFromMinuteOfDay(c, anchorMin);
    }

    private static String human(long minutes) {
        if (minutes <= 0L) return "not set";
        long h = minutes / 60L, m = minutes % 60L;
        if (h == 0L) return m + " min";
        if (m == 0L) return h + (h == 1L ? " hour" : " hours");
        return h + " h " + m + " min";
    }
}
