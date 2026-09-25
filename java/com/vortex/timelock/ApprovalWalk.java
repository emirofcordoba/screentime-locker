package com.vortex.timelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * ApprovalWalk — the runner that presents a {@link ApprovalCheckpoint} chain one
 * isolated dialog at a time and records each pass in the {@link ApprovalLedger}.
 *
 * The catalogue it is handed is a strict two-step sequence, so a walk is two
 * dialogs long; the runner itself is agnostic to the count and presents whatever
 * chain it is given. Every checkpoint's pre-flight guard is evaluated against
 * live state before its dialog is shown, so a drifted condition halts the walk
 * without ever opening a confirmation dialogue.
 *
 * Every rung is a SEPARATE, uncancellable dialog: no touch-outside, no Back, no
 * stacked prompt. The only way on is the positive action that the rung's
 * {@link ApprovalCheckpoint.Kind} demands, and the only way out is the negative
 * button \u2014 which abandons the entire walk, resets the chain and leaves the
 * barrier armed.
 *
 * STATIC EVENTS ONLY
 * ------------------
 * There is no Handler, no postDelayed, no Timer, no Thread, no Executor, no
 * AlarmManager and no polling loop in this file. The runner advances only when
 * one of these arrives:
 *
 *    a tap on a positive button            (DIALOG_POSITIVE)
 *    a ticked acknowledgement             (ACK_TICKED)
 *    a keystroke submitted as a phrase    (PHRASE_TYPED)
 *    a genuine enrolled-fingerprint match (BIOMETRIC_MATCHED)
 *    a press-and-hold released after dwell(HOLD_RELEASED)
 *    a wall-clock comparison on a tap     (DEADLINE_COMPARED)
 *    a binding re-read on a tap           (BINDING_RECHECKED)
 *
 * A walk that is merely sitting on screen therefore costs nothing: no wakeups,
 * no scheduled work, no battery. Walking away from a half-finished ladder is
 * free, and coming back is free; the state simply rests in storage.
 */
final class ApprovalWalk {

    private ApprovalWalk() {}

    /**
     * Drive {@code chain} to completion for {@code scope}. Exactly one of the
     * two callbacks runs: {@code onGranted} once the warrant has been minted and
     * the chain re-verified, or {@code onHalted} the moment anything fails.
     */
    static void run(Activity a, String scope, List<ApprovalCheckpoint> chain,
                    Runnable onGranted, Runnable onHalted) {
        if (a == null || a.isFinishing()) {
            if (onHalted != null) onHalted.run();
            return;
        }
        if (chain == null || chain.isEmpty()) {
            if (onHalted != null) onHalted.run();
            return;
        }
        new Walker(a, scope, chain, onGranted, onHalted).start();
    }

    // =====================================================================
    // The walk itself
    // =====================================================================

    private static final class Walker {

        private final Activity a;
        private final String scope;
        private final List<ApprovalCheckpoint> chain;
        private final Runnable onGranted;
        private final Runnable onHalted;

        /** Global position in the chain; also the ledger link index. */
        private int index;
        private boolean halted;
        private AlertDialog current;
        /** uptimeMillis of the press currently being held, or 0. */
        private long holdDownAt;

        Walker(Activity a, String scope, List<ApprovalCheckpoint> chain,
               Runnable onGranted, Runnable onHalted) {
            this.a = a;
            this.scope = scope;
            this.chain = chain;
            this.onGranted = onGranted;
            this.onHalted = onHalted;
        }

        // ------------------------------------------------------- lifecycle

        void start() {
            // A fresh walk never inherits links, deadlines or tokens.
            ApprovalLedger.begin(a, scope);
            Prefs.setLastEvent(a, "approval-begin:" + scope + ":steps=" + chain.size());
            advance();
        }

        private int tierCount() {
            int max = 0;
            for (ApprovalCheckpoint cp : chain) if (cp.tier > max) max = cp.tier;
            return max;
        }

        private void advance() {
            if (halted) return;
            if (a.isFinishing()) { halt("console left before the walk finished"); return; }
            if (index >= chain.size()) { grant(); return; }
            present(chain.get(index));
        }

        // ----------------------------------------------------- presentation

        private void present(final ApprovalCheckpoint cp) {
            if (halted) return;

            // Fail fast: every checkpoint's pre-flight guard is evaluated against
            // LIVE state before its dialog is even shown. A two-step chain carries
            // its guards on the review/authorise steps, so this is what keeps a
            // drifted device-owner / input / credential state from ever opening a
            // confirmation dialogue.
            String guard = guardFailure(cp.guard);
            if (guard != null) { halt("pre-flight failed: " + guard); return; }

            holdDownAt = 0L;

            Prefs.setLastEvent(a, "approval-step:" + scope + ":step" + (index + 1)
                    + "/" + chain.size() + ":" + cp.id);

            final EditText input = wantsInput(cp.kind) ? new EditText(a) : null;
            final CheckBox ack = cp.kind == ApprovalCheckpoint.Kind.ACK ? new CheckBox(a) : null;
            final TextView error = new TextView(a);

            // A deliberative checkpoint arms its wall-clock target the first time
            // it is presented. Arming is one storage write; nothing is scheduled.
            if (cp.kind == ApprovalCheckpoint.Kind.DEADLINE
                    && ApprovalLedger.deadline(a, scope, index) == 0L) {
                ApprovalLedger.armDeadline(a, scope, index, cp.dwellMs);
            }

            LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 4));

            TextView meta = new TextView(a);
            meta.setText("Checkpoint " + (index + 1) + " of " + chain.size()
                    + "   \u00b7   tier " + cp.tier + "   \u00b7   trigger: " + cp.trigger.label());
            meta.setTextSize(11.5f);
            meta.setTextColor(0xFF8892A4);
            body.addView(meta);

            TextView msg = new TextView(a);
            msg.setText(cp.message);
            msg.setTextSize(14.5f);
            msg.setLineSpacing(dp(a, 4), 1f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(a, 10);
            msg.setLayoutParams(mlp);
            body.addView(msg);

            if (cp.kind == ApprovalCheckpoint.Kind.CHALLENGE) {
                TextView token = new TextView(a);
                token.setText(ApprovalLedger.challengeToken(a, scope, index));
                token.setTextSize(24f);
                token.setTypeface(android.graphics.Typeface.MONOSPACE);
                token.setLetterSpacing(0.22f);
                token.setGravity(android.view.Gravity.CENTER);
                token.setTextColor(0xFF2E7D32);
                token.setPadding(0, dp(a, 18), 0, dp(a, 18));
                body.addView(token);
            }

            if (cp.kind == ApprovalCheckpoint.Kind.DEADLINE) {
                long target = ApprovalLedger.deadline(a, scope, index);
                TextView clock = new TextView(a);
                clock.setText("Deadline: " + TimeFmt.clockSeconds(a, target)
                        + "   (" + cp.dwellMs / 1000L + "s from the moment this first appeared)");
                clock.setTextSize(13f);
                clock.setTypeface(android.graphics.Typeface.MONOSPACE);
                clock.setTextColor(0xFF8892A4);
                clock.setPadding(0, dp(a, 10), 0, 0);
                body.addView(clock);
            }

            if (cp.kind == ApprovalCheckpoint.Kind.BINDING_RECHECK) {
                TextView fp = new TextView(a);
                fp.setText("Binding fingerprint: " + ApprovalLedger.fingerprint(a, scope)
                        + "\nChain links so far: " + ApprovalLedger.length(a, scope)
                        + "\nLedger state: " + ApprovalLedger.describe(a, scope));
                fp.setTextSize(12.5f);
                fp.setTypeface(android.graphics.Typeface.MONOSPACE);
                fp.setTextColor(0xFF4B5563);
                fp.setPadding(0, dp(a, 12), 0, 0);
                body.addView(fp);
            }

            if (ack != null) {
                ack.setText(cp.ackLabel);
                ack.setTextSize(14f);
                ack.setPadding(0, dp(a, 14), 0, 0);
                body.addView(ack);
            }

            if (input != null) {
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setSingleLine(true);
                input.setHint(hintFor(cp));
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.topMargin = dp(a, 14);
                input.setLayoutParams(ilp);
                body.addView(input);
            }

            error.setTextSize(13f);
            error.setTextColor(0xFFFF5252);
            error.setVisibility(View.GONE);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(a, 10);
            error.setLayoutParams(elp);
            body.addView(error);

            ScrollView scroller = new ScrollView(a);
            scroller.addView(body);

            AlertDialog.Builder b = new AlertDialog.Builder(a);
            b.setTitle(cp.title);
            b.setView(scroller);
            b.setCancelable(false);
            b.setPositiveButton(cp.positiveLabel, null);      // wired in onShow
            b.setNegativeButton(cp.negativeLabel,
                    (d, w) -> halt("declined at checkpoint " + cp.id));

            final AlertDialog dialog = b.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnShowListener(x -> {
                if (input != null) {
                    input.requestFocus();
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                    }
                }
                Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (cp.kind == ApprovalCheckpoint.Kind.HOLD) {
                    wireHold(positive, cp, dialog, error);
                } else {
                    positive.setOnClickListener(v -> onPositive(cp, dialog, input, ack, error));
                }
                if (cp.kind == ApprovalCheckpoint.Kind.BIOMETRIC) {
                    requestBiometric(cp, dialog, error);
                }
            });
            current = dialog;
            dialog.show();
        }

        // --------------------------------------------------- positive paths

        private void onPositive(ApprovalCheckpoint cp, AlertDialog dialog, EditText input,
                                CheckBox ack, TextView error) {
            if (halted) return;

            switch (cp.kind) {
                case PRECONDITION: {
                    String bad = guardFailure(cp.guard);
                    if (bad != null) { halt("pre-flight failed: " + bad); return; }
                    pass(cp, dialog);
                    return;
                }
                case ACK: {
                    if (ack == null || !ack.isChecked()) {
                        error.setText("Tick the acknowledgement above to continue.");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    pass(cp, dialog);
                    return;
                }
                case PHRASE: {
                    String typed = input == null ? "" : input.getText().toString().trim();
                    if (!cp.phrase.contentEquals(typed)) {
                        error.setText("Phrase does not match. Type \"" + cp.phrase
                                + "\" exactly, in capitals.");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    pass(cp, dialog);
                    return;
                }
                case CHALLENGE: {
                    String typed = normalize(input == null ? "" : input.getText().toString());
                    String wanted = normalize(ApprovalLedger.challengeToken(a, scope, index));
                    if (typed.isEmpty() || !wanted.equals(typed)) {
                        error.setText("Token does not match. Read it again and type all six characters.");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    pass(cp, dialog);
                    return;
                }
                case DEADLINE: {
                    long target = ApprovalLedger.deadline(a, scope, index);
                    if (target == 0L) {
                        ApprovalLedger.armDeadline(a, scope, index, cp.dwellMs);
                        error.setText("Deadline armed. Tap Continue again once the clock has passed.");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    long left = target - System.currentTimeMillis();
                    if (left > 0L) {
                        error.setText("Still deliberating: " + ((left + 999L) / 1000L)
                                + "s remain on the clock.");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    pass(cp, dialog);
                    return;
                }
                case BINDING_RECHECK: {
                    if (!ApprovalLedger.verified(a, scope)) {
                        halt("configuration changed, or the chain no longer verifies");
                        return;
                    }
                    pass(cp, dialog);
                    return;
                }
                case BIOMETRIC:
                    requestBiometric(cp, dialog, error);
                    return;
                case NOTICE:
                case FINAL:
                default:
                    pass(cp, dialog);
            }
        }

        /** The press-and-hold rung: a tap cannot satisfy it, only a real hold can. */
        private void wireHold(final Button positive, final ApprovalCheckpoint cp,
                              final AlertDialog dialog, final TextView error) {
            final String rest = "\u25CF HOLD \u2014 " + (cp.dwellMs / 1000L) + "s";
            positive.setText(rest);
            positive.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        holdDownAt = SystemClock.uptimeMillis();
                        positive.setText("\u25CF HOLDING\u2026 KEEP PRESSING");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        long held = holdDownAt == 0L ? 0L
                                : SystemClock.uptimeMillis() - holdDownAt;
                        holdDownAt = 0L;
                        positive.setText(rest);
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && held >= cp.dwellMs) {
                            pass(cp, dialog);
                        } else {
                            error.setText("Held for " + String.format(Locale.US, "%.1f", held / 1000.0)
                                    + "s of " + (cp.dwellMs / 1000L)
                                    + "s. Keep pressing without lifting your finger.");
                            error.setVisibility(View.VISIBLE);
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            });
        }

        private void requestBiometric(final ApprovalCheckpoint cp, final AlertDialog dialog,
                                      final TextView error) {
            if (halted) return;
            error.setVisibility(View.GONE);
            BiometricAuth.authenticate(a, cp.title,
                    "Match to pass tier " + cp.tier,
                    new BiometricAuth.Callback() {
                        @Override
                        public void onSuccess() {
                            pass(cp, dialog);
                        }

                        @Override
                        public void onFailure(CharSequence message, boolean recoverable) {
                            if (halted) return;
                            if (recoverable) {
                                error.setVisibility(View.VISIBLE);
                                error.setText("No match"
                                        + (message == null ? "" : " \u2014 " + message)
                                        + ". Tap again to retry.");
                            } else {
                                halt("biometric unavailable: " + message);
                            }
                        }
                    });
        }

        // --------------------------------------------------------- outcomes

        /**
         * Record one passed rung. The link is written and fsync'd BEFORE the
         * dialog is dismissed and the next rung presented, so the persisted
         * chain can never lag behind what the user was shown.
         */
        private void pass(ApprovalCheckpoint cp, AlertDialog dialog) {
            if (halted) return;
            if (!ApprovalLedger.append(a, scope, index, cp, cp.trigger)) {
                halt("ledger refused the link at " + cp.id);
                return;
            }
            Prefs.setLastEvent(a, "approval-pass:" + scope + ":" + cp.id + ":"
                    + cp.trigger.name());
            if (dialog != null) {
                try { dialog.dismiss(); } catch (Throwable ignored) { /* already gone */ }
            }
            if (current == dialog) current = null;
            index++;
            advance();
        }

        private void grant() {
            if (halted) return;
            if (!ApprovalLedger.verified(a, scope)) {
                halt("chain integrity check failed at the end of the walk");
                return;
            }
            String kind = ApprovalState.SCOPE_SETUP.equals(scope)
                    ? ApprovalLedger.KIND_SHOT
                    : ApprovalLedger.KIND_STANDING;
            if (!ApprovalLedger.mint(a, scope, kind)) {
                halt("could not mint the warrant");
                return;
            }
            Prefs.setLastEvent(a, "approval-granted:" + scope + ":" + kind
                    + ":links=" + ApprovalLedger.length(a, scope));
            if (onGranted != null) onGranted.run();
        }

        /**
         * Abandon the whole walk. The chain is dropped \u2014 but NOT the minted
         * warrant, because reaching here means no warrant was minted \u2014 and the
         * barrier stays armed.
         */
        private void halt(String why) {
            if (halted) return;
            halted = true;
            ApprovalLedger.resetChain(a, scope);
            BiometricAuth.cancel();
            if (current != null) {
                try { current.dismiss(); } catch (Throwable ignored) { /* already gone */ }
                current = null;
            }
            Prefs.setLastEvent(a, "approval-halt:" + scope + ":" + why);
            if (onHalted != null) onHalted.run();
        }

        // ------------------------------------------------- pre-flight guards

        private String guardFailure(ApprovalCheckpoint.Guard g) {
            switch (g) {
                case DEVICE_OWNER:
                    if (!Engine.isDeviceOwner(a)) return "this app is not the Device Owner";
                    if (!Engine.isAdminActive(a)) return "the device admin is not active";
                    return null;
                case ACTIVATION_INPUTS:
                    if (Prefs.limitMs(a) <= 0L) return "no daily interval has been set";
                    return null;
                case RE_ENTRY_CREDENTIAL:
                    if (!BiometricAuth.isAvailable(a)) {
                        return "no enrolled fingerprint on this device";
                    }
                    return null;
                case EXTREME_REQUESTED:
                    if (!ApprovalState.dangerousConfigured(a)) {
                        return "no root-level restriction is requested";
                    }
                    return null;
                case NONE:
                default:
                    return null;
            }
        }
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private static boolean wantsInput(ApprovalCheckpoint.Kind kind) {
        return kind == ApprovalCheckpoint.Kind.PHRASE
                || kind == ApprovalCheckpoint.Kind.CHALLENGE;
    }

    private static String hintFor(ApprovalCheckpoint cp) {
        if (cp.kind == ApprovalCheckpoint.Kind.PHRASE) return "Type \"" + cp.phrase + "\"";
        if (cp.kind == ApprovalCheckpoint.Kind.CHALLENGE) return "Type the six-character token";
        return "";
    }

    /** Strip separators and case so a token can be typed naturally. */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toUpperCase(s.charAt(i));
            if (Character.isLetterOrDigit(ch)) b.append(ch);
        }
        return b.toString();
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
}
