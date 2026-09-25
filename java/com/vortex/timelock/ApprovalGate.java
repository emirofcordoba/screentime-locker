package com.vortex.timelock;

import android.app.Activity;
import android.content.Context;

/**
 * ApprovalGate — the single façade in front of the two tiered approval ladders,
 * and the only sanctioned way to reach a privileged action.
 *
 * TWO INTERCEPT POINTS, TWO ISOLATED LOOPS
 * ----------------------------------------
 * This class exists to make the two dangerous operations of the app reachable
 * ONLY through a fully-walked {@link ApprovalCatalogue} chain:
 *
 *   1. {@link #gateSetup       } wraps FINAL SETUP EXECUTION. It drives
 *      {@link ApprovalCatalogue#activationChain} — 2 steps, 2 separately
 *      presented, separately triggered checkpoints — before the policy may be
 *      activated and the app concealed. Completing it mints a single-use
 *      warrant ({@link ApprovalLedger#KIND_SHOT}).
 *
 *   2. {@link #gateRestrictions} wraps ACTIVATION OF THE HARD SECURITY LOCKS,
 *      i.e. pinning DISALLOW_SAFE_BOOT and DISALLOW_FACTORY_RESET onto the
 *      device as Device Owner. It drives
 *      {@link ApprovalCatalogue#restrictionsChain} — 2 steps —
 *      and completing it mints a standing, action-bound warrant
 *      ({@link ApprovalLedger#KIND_STANDING}).
 *
 * Each loop is completely isolated: its own scope, its own chain, its own
 * ledger namespace. Passing one says nothing about the other.
 *
 * STATIC-EVENT, ZERO-DRAW INVARIANT
 * ---------------------------------
 * Nothing in this class (or the ladder behind it) schedules, polls or wakes
 * the CPU. A gate is armed by resting its state in storage and is consulted
 * ONLY at the instant the user delivers a static event — a tap, a tick, a
 * keystroke, a fingerprint match, a press-and-hold release, a wall-clock
 * comparison performed while the user is already looking at the dialog. There
 * is no Handler, no Timer, no Thread, no AlarmManager, no JobScheduler and no
 * foreground service anywhere on this path, so an armed barrier that is simply
 * waiting for the user consumes ZERO CPU and ZERO battery, indefinitely.
 *
 * FAIL-SAFE
 * ---------
 * Every query ({@link #setupGranted}, {@link #restrictionsGranted}) reads the
 * live, MAC-bound warrant on demand and answers "no" the moment anything has
 * drifted: a missing link, a broken chain, a changed configuration, or an
 * expired one-shot. Callers are expected to withhold the privileged action on
 * "no" — and {@link RestrictionGuard}, the only consumer of the standing
 * warrant, additionally CLEARS the restrictions rather than merely skipping
 * them, so a stale policy can never survive a failed check.
 */
final class ApprovalGate {

    /** Scope of the final-setup-execution loop. Shared with the ledger's binding. */
    static final String SCOPE_SETUP = ApprovalState.SCOPE_SETUP;
    /** Scope of the hard-security-lock (Safe Mode / Factory Reset) loop. */
    static final String SCOPE_RESTRICTIONS = ApprovalState.SCOPE_RESTRICTIONS;

    private ApprovalGate() {}

    // =====================================================================
    // What the user is asking for
    // =====================================================================

    /**
     * True when at least one privileged root-level restriction (blocking Safe
     * Mode access or Factory Reset) is currently requested. The restriction
     * ladder refuses to run — and the guard clears rather than applies — when
     * this is false.
     */
    static boolean anyExtremeRequested(Context c) {
        return Prefs.optSafeBoot(c) || Prefs.optFactoryReset(c);
    }

    // =====================================================================
    // The two isolated loops
    // =====================================================================

    /**
     * Walk the two-step activation confirmation before final setup execution.
     * Exactly one callback runs: {@code onGranted} once the single-use warrant has been
     * minted and the whole chain re-verified, or {@code onHalted} the instant
     * any rung is declined, a guard drifts, or the chain fails to verify.
     */
    static void gateSetup(Activity a, Runnable onGranted, Runnable onHalted) {
        ApprovalWalk.run(a, SCOPE_SETUP, ApprovalCatalogue.activationChain(a),
                onGranted, onHalted);
    }

    /**
     * Walk the two-step restriction confirmation before the hard security locks
     * may be applied as Device Owner. On success a standing warrant bound to the exact
     * requested action set is minted; changing which restrictions are requested
     * afterwards makes that warrant stop matching and re-arms this gate.
     */
    static void gateRestrictions(Activity a, Runnable onGranted, Runnable onHalted) {
        ApprovalWalk.run(a, SCOPE_RESTRICTIONS, ApprovalCatalogue.restrictionsChain(a),
                onGranted, onHalted);
    }

    // =====================================================================
    // On-demand warrant queries (one synchronous storage read each)
    // =====================================================================

    /** True while a live single-use setup warrant exists (unconsumed, unexpired). */
    static boolean setupGranted(Context c) {
        return ApprovalLedger.warrantKind(c, SCOPE_SETUP) != null;
    }

    /** True while a live STANDING warrant covers the current restriction binding. */
    static boolean restrictionsGranted(Context c) {
        return ApprovalLedger.standingWarranted(c, SCOPE_RESTRICTIONS);
    }

    /**
     * Consume the single-use setup warrant. Returns true the first time and
     * false on every later call (or once it has aged past its validity window).
     * Best-effort: a caller that has already walked the ladder must not be
     * blocked by a warrant that merely expired while a second chain was walked.
     */
    static boolean consumeSetup(Context c) {
        return ApprovalLedger.redeemShot(c, SCOPE_SETUP);
    }

    // =====================================================================
    // Teardown / diagnostics
    // =====================================================================

    /**
     * Drop one scope's chain AND its warrant, re-arming the barrier. Clears the
     * legacy bookkeeping for the same scope too, so the two layers can never
     * disagree about whether a gate is open.
     */
    static void revoke(Context c, String scope) {
        ApprovalLedger.revoke(c, scope);
        ApprovalState.revoke(c, scope);
    }

    /** Re-arm BOTH loops, re-arming the barriers after a policy teardown. */
    static void revokeAll(Context c) {
        revoke(c, SCOPE_SETUP);
        revoke(c, SCOPE_RESTRICTIONS);
    }

    /** One-line state of one scope's chain/warrant (diagnostics only). */
    static String status(Context c, String scope) {
        return ApprovalLedger.describe(c, scope);
    }
}
