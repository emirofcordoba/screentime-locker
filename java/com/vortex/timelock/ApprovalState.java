package com.vortex.timelock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent approval bookkeeping for the fail-safe verification sequence.
 *
 * DESIGN INVARIANTS
 * -----------------
 *  1. Every question answered here is answered by reading SharedPreferences
 *     synchronously at the instant it is asked. There is no Handler, no
 *     AlarmManager, no Service and no polling loop anywhere in this class: an
 *     approval barrier costs exactly one storage read and zero wakeups, so it
 *     stays active forever while burning no power.
 *  2. An authorisation is bound to the exact action set it approves
 *     ("sb=<0|1>;fr=<0|1>"). If the user later changes which root-level
 *     restrictions are requested, the stored authorisation no longer matches
 *     and the barrier automatically re-arms until a fresh sequence is run.
 *  3. An authorisation can only be minted while the chain state is "done",
 *     which itself lives in persistent storage. A process death, a dismissed
 *     dialog or a rotation therefore cannot forge consent.
 *
 * All writes use commit() so a crash or power loss can never leave a half
 * recorded consent behind.
 */
final class ApprovalState {

    /** Setup completion barrier. */
    static final String SCOPE_SETUP = "setup_complete";
    /** Root-level restriction activation barrier (Safe Mode / Factory Reset). */
    static final String SCOPE_RESTRICTIONS = "root_restrictions";

    private static final String FILE = "tl_approvals";

    private ApprovalState() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void commit(SharedPreferences.Editor e) {
        e.commit();
    }

    // =====================================================================
    // What the user asked for
    // =====================================================================

    /** True when at least one privileged root-level restriction is requested. */
    static boolean dangerousConfigured(Context c) {
        return Prefs.optSafeBoot(c) || Prefs.optFactoryReset(c);
    }

    /** Canonical description of the currently requested root-level action set. */
    private static String actionSet(Context c) {
        return "sb=" + (Prefs.optSafeBoot(c) ? 1 : 0)
                + ";fr=" + (Prefs.optFactoryReset(c) ? 1 : 0);
    }

    // =====================================================================
    // Chain state machine (persistent; no timers involved)
    // =====================================================================

    static void markChainRunning(Context c, String scope) { setChain(c, scope, "running"); }
    static void markChainDone(Context c, String scope)    { setChain(c, scope, "done"); }
    static void markChainIdle(Context c, String scope)    { setChain(c, scope, "idle"); }

    static String chainState(Context c, String scope) {
        return sp(c).getString("chain_" + scope, "idle");
    }

    private static void setChain(Context c, String scope, String v) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putString("chain_" + scope, v);
        commit(e);
    }

    /** Index of the step the sequence is currently showing (diagnostics). */
    static void setProgress(Context c, String scope, int index) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putInt("prog_" + scope, index);
        commit(e);
    }

    static int progress(Context c, String scope) {
        return sp(c).getInt("prog_" + scope, 0);
    }

    // =====================================================================
    // Standing authorisation
    // =====================================================================

    /**
     * Mint a standing authorisation for [scope], bound to the action set that is
     * live at this exact moment. Refuses (silently, by doing nothing) unless the
     * chain state proves the user actually walked the whole sequence.
     */
    static void authorize(Context c, String scope) {
        if (!"done".equals(chainState(c, scope))) return;
        SharedPreferences.Editor e = sp(c).edit();
        e.putString("auth_" + scope, actionSet(c));
        e.putLong("auth_at_" + scope, System.currentTimeMillis());
        e.putString("chain_" + scope, "idle");
        e.putInt("prog_" + scope, 0);
        commit(e);
    }

    /**
     * The single read every executor calls before touching a privileged policy.
     * True only while a persisted authorisation exists AND it still matches the
     * live action set.
     */
    static boolean isAuthorized(Context c, String scope) {
        String stored = sp(c).getString("auth_" + scope, null);
        return stored != null && stored.equals(actionSet(c));
    }

    static long authorizedAt(Context c, String scope) {
        return sp(c).getLong("auth_at_" + scope, 0L);
    }

    /** Drop the authorisation and re-arm the barrier. */
    static void revoke(Context c, String scope) {
        SharedPreferences.Editor e = sp(c).edit();
        e.remove("auth_" + scope);
        e.remove("auth_at_" + scope);
        e.putString("chain_" + scope, "idle");
        e.putInt("prog_" + scope, 0);
        commit(e);
    }
}
