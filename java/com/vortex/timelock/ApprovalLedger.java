package com.vortex.timelock;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ApprovalLedger — the tamper-evident, device-bound record of an approval walk,
 * and the only place that may mint the warrant which unlocks a privileged
 * action.
 *
 * WHY A CHAIN RATHER THAN A FLAG
 * ------------------------------
 * A boolean "approved" flag can be flipped by anything that can write a
 * preference. Here every passed checkpoint appends a link whose HMAC is
 * computed over (index, id, tier, kind, trigger, live binding, timestamp,
 * previous link's MAC) using a 256-bit key generated on this device and stored
 * in a separate preferences file. Forging a walk therefore requires the key,
 * and skipping a link breaks every subsequent MAC. {@link #verified} re-walks
 * the whole chain and also insists that every link was bound to the SAME
 * configuration that is live right now: change which restrictions you are
 * asking for after the fact and the chain stops verifying on its own.
 *
 * WHY IT COSTS NOTHING
 * --------------------
 * Every method here is a synchronous storage read or write performed at the
 * instant a static event is delivered (a tap, a checkbox tick, a keystroke, a
 * wall-clock comparison). There is no Handler, no Timer, no Thread, no
 * AlarmManager and no service anywhere in this class — a barrier that is only
 * consulted on demand cannot wake the CPU, and one that stores three short
 * strings cannot draw measurable current.
 *
 * All writes use commit() so a crash or power loss can never leave a
 * half-written consent behind.
 */
final class ApprovalLedger {

    /** A warrant that survives until explicitly revoked (restriction deployment). */
    static final String KIND_STANDING = "standing";
    /** A single-use warrant, valid briefly, consumed on first redemption. */
    static final String KIND_SHOT = "one_shot";

    /** A one-shot warrant expires this long after it is minted. */
    static final long SHOT_MS = 10L * 60L * 1000L;

    /** Hard ceiling on chain length; bounds storage and any verification walk. */
    static final int MAX_LINKS = 48;

    private static final String FILE = "tl_approval_ledger";
    private static final String KEY_FILE = "tl_approval_key";
    private static final String GENESIS = "GENESIS";
    private static final String ALPHABET = "23456789ABCDEF";
    private static final String SEP = "\\|";
    private static final int FIELDS = 9;
    private static final int WARRANT_FIELDS = 6;

    private ApprovalLedger() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences kp(Context c) {
        return c.getApplicationContext().getSharedPreferences(KEY_FILE, Context.MODE_PRIVATE);
    }

    private static void commit(SharedPreferences.Editor e) {
        e.commit();
    }

    // ===================================================================== key

    /**
     * The device-bound ledger key: 256 random bits, generated once, stored in a
     * file of its own so that clearing the ledger does not clear the key (a
     * missing key would otherwise let an old chain be re-validated for free).
     */
    private static String key(Context c) {
        SharedPreferences p = kp(c);
        String k = p.getString("k", null);
        if (k != null && k.length() == 64) return k;
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        k = hex(raw);
        SharedPreferences.Editor e = p.edit();
        e.putString("k", k);
        commit(e);
        return k;
    }

    private static String hmac(Context c, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(unhex(key(c)), "HmacSHA256"));
            return hex(mac.doFinal(msg.getBytes("UTF-8")));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes("UTF-8")));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String nonce() {
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        return hex(r);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ================================================================= binding

    /**
     * Fresh, canonical description of exactly what is being approved. Read live
     * at every use, never cached: this is what makes an approval self-revoking
     * when the requested action set changes underneath it.
     */
    static String binding(Context c, String scope) {
        if (ApprovalState.SCOPE_RESTRICTIONS.equals(scope)) {
            return sanitize("sb=" + (Prefs.optSafeBoot(c) ? 1 : 0)
                    + ";fr=" + (Prefs.optFactoryReset(c) ? 1 : 0));
        }
        if (ApprovalState.SCOPE_SETUP.equals(scope)) {
            return sanitize("lim=" + Prefs.limitMs(c)
                    + ";anc=" + Prefs.anchorMin(c)
                    + ";sch=" + ScheduleConfig.summary(c)
                    + ";fl=" + flagBits(c));
        }
        return sanitize("scope=" + scope);
    }

    private static String flagBits(Context c) {
        StringBuilder b = new StringBuilder(8);
        b.append(Prefs.optScreenOff(c) ? '1' : '0');
        b.append(Prefs.optKeyguard(c) ? '1' : '0');
        b.append(Prefs.optSafeBoot(c) ? '1' : '0');
        b.append(Prefs.optFactoryReset(c) ? '1' : '0');
        b.append(Prefs.optDisableDebugging(c) ? '1' : '0');
        b.append(Prefs.optStandby(c) ? '1' : '0');
        b.append(Prefs.optCountdown(c) ? '1' : '0');
        return b.toString();
    }

    /**
     * Short human-checkable digest of the live binding. Displayed at the
     * re-verification checkpoint so the user can see that the thing they are
     * about to approve is the thing they started approving.
     */
    static String fingerprint(Context c, String scope) {
        String h = sha256(binding(c, scope));
        if (h.length() < 12) return "000000000000";
        return h.substring(0, 12).toUpperCase(Locale.US);
    }

    /**
     * The challenge token for a checkpoint. Derived from the device key, the
     * scope, the index and the live binding, so it cannot be pre-computed
     * off-device and it changes the moment the binding does. Formatted
     * XXX-XXX from an alphabet with no look-alike characters.
     */
    static String challengeToken(Context c, String scope, int index) {
        String seed = hmac(c, "token|" + scope + "|" + index + "|" + binding(c, scope));
        if (seed.length() < 12) return "AAAA-AA";
        StringBuilder out = new StringBuilder(7);
        for (int i = 0; i < 6; i++) {
            if (i == 3) out.append('-');
            int v = Integer.parseInt(seed.substring(i * 2, i * 2 + 2), 16);
            out.append(ALPHABET.charAt(v % ALPHABET.length()));
        }
        return out.toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '|' || ch == '\n' || ch == '\r' || ch == '\t' || ch < 0x20) b.append('/');
            else b.append(ch);
        }
        if (b.length() > 240) return b.substring(0, 240);
        return b.toString();
    }

    // ================================================================== chain

    private static String linkKey(String scope, int index) {
        return "cp_" + scope + "_" + index;
    }

    /** Arm a fresh walk: no links, no deadlines, no stale chain to inherit. */
    static void begin(Context c, String scope) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 0; i < MAX_LINKS; i++) e.remove(linkKey(scope, i));
        for (int i = 0; i < MAX_LINKS; i++) e.remove(dlKey(scope, i));
        e.putBoolean("walk_" + scope, true);
        e.putLong("walk_at_" + scope, System.currentTimeMillis());
        commit(e);
    }

    /**
     * Append one link. Refuses unless the previous link is present and intact,
     * which is what makes the chain gapless: a link can never be written over a
     * hole, and a link can never be re-ordered.
     */
    static boolean append(Context c, String scope, int index, ApprovalCheckpoint cp,
                          ApprovalTrigger trigger) {
        if (index < 0 || index >= MAX_LINKS) return false;

        String prevMac = GENESIS;
        if (index > 0) {
            String raw = sp(c).getString(linkKey(scope, index - 1), null);
            if (raw == null) return false;
            String[] f = raw.split(SEP, -1);
            if (f.length != FIELDS) return false;
            prevMac = f[8];
        }

        String payload = index
                + "|" + sanitize(cp.id)
                + "|" + cp.tier
                + "|" + cp.kind.name()
                + "|" + (trigger == null ? "NONE" : trigger.name())
                + "|" + binding(c, scope)
                + "|" + System.currentTimeMillis()
                + "|" + prevMac;

        String mac = hmac(c, payload);
        if (mac.isEmpty()) return false;

        SharedPreferences.Editor e = sp(c).edit();
        e.putString(linkKey(scope, index), payload + "|" + mac);
        commit(e);
        return true;
    }

    /**
     * Re-walk the chain end to end. True only when every link's MAC recomputes,
     * the indices are contiguous and in order, every link carries the identical
     * binding, and that binding still matches the live configuration.
     */
    static boolean verified(Context c, String scope) {
        String live = binding(c, scope);
        String prevMac = GENESIS;
        String root = null;
        int count = 0;

        for (int i = 0; i < MAX_LINKS; i++) {
            String raw = sp(c).getString(linkKey(scope, i), null);
            if (raw == null) break;
            String[] f = raw.split(SEP, -1);
            if (f.length != FIELDS) return false;
            if (!String.valueOf(i).equals(f[0])) return false;
            if (!prevMac.equals(f[7])) return false;

            String expected = hmac(c, f[0] + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|"
                    + f[4] + "|" + f[5] + "|" + f[6] + "|" + f[7]);
            if (expected.isEmpty() || !expected.equals(f[8])) return false;

            if (i == 0) root = f[5];
            else if (root == null || !root.equals(f[5])) return false;

            prevMac = f[8];
            count++;
        }

        return count > 0 && root != null && root.equals(live);
    }

    /** Number of links currently recorded (diagnostics only). */
    static int length(Context c, String scope) {
        int n = 0;
        while (n < MAX_LINKS && sp(c).getString(linkKey(scope, n), null) != null) n++;
        return n;
    }

    // ============================================================== deadlines

    private static String dlKey(String scope, int index) {
        return "dl_" + scope + "_" + index;
    }

    /**
     * Arm the wall-clock deadline for a deliberative checkpoint. The target is
     * an absolute instant: if the user walks away, the wait is still real when
     * they come back, and no timer had to run in the meantime.
     */
    static void armDeadline(Context c, String scope, int index, long dwellMs) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putLong(dlKey(scope, index), System.currentTimeMillis() + Math.max(0L, dwellMs));
        commit(e);
    }

    /** Absolute instant at which the deadline elapses, or 0 when unarmed. */
    static long deadline(Context c, String scope, int index) {
        return sp(c).getLong(dlKey(scope, index), 0L);
    }

    /** Drop every link and every deadline, but keep any minted warrant. */
    static void resetChain(Context c, String scope) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 0; i < MAX_LINKS; i++) e.remove(linkKey(scope, i));
        for (int i = 0; i < MAX_LINKS; i++) e.remove(dlKey(scope, i));
        e.putBoolean("walk_" + scope, false);
        commit(e);
    }

    /** Full teardown for one scope: links, deadlines and the warrant itself. */
    static void revoke(Context c, String scope) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 0; i < MAX_LINKS; i++) e.remove(linkKey(scope, i));
        for (int i = 0; i < MAX_LINKS; i++) e.remove(dlKey(scope, i));
        e.putBoolean("walk_" + scope, false);
        e.remove("w_" + scope);
        commit(e);
    }

    // ================================================================ warrant

    /**
     * Mint the warrant that authorises execution. It is MAC-bound to the live
     * configuration, so a warrant minted for "block factory reset only" is
     * worthless the moment "block safe mode" is switched on as well.
     */
    static boolean mint(Context c, String scope, String kind) {
        String payload = scope
                + "|" + kind
                + "|" + nonce()
                + "|" + binding(c, scope)
                + "|" + System.currentTimeMillis();
        String mac = hmac(c, payload);
        if (mac.isEmpty()) return false;
        SharedPreferences.Editor e = sp(c).edit();
        e.putString("w_" + scope, payload + "|" + mac);
        commit(e);
        return true;
    }

    /**
     * The kind of the currently valid warrant for {@code scope}, or null when
     * there is none, when its MAC or binding no longer checks out, or when a
     * one-shot has aged past its short validity window.
     */
    static String warrantKind(Context c, String scope) {
        String raw = sp(c).getString("w_" + scope, null);
        if (raw == null) return null;
        String[] f = raw.split(SEP, -1);
        if (f.length != WARRANT_FIELDS) return null;
        if (!scope.equals(f[0])) return null;

        String expected = hmac(c, f[0] + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|" + f[4]);
        if (expected.isEmpty() || !expected.equals(f[5])) return null;
        if (!binding(c, scope).equals(f[3])) return null;

        long issued;
        try { issued = Long.parseLong(f[4]); } catch (Throwable t) { return null; }
        if (KIND_SHOT.equals(f[1])) {
            long age = System.currentTimeMillis() - issued;
            if (age < 0L || age > SHOT_MS) return null;
        }
        return f[1];
    }

    /** True only while a live STANDING warrant covers the current binding. */
    static boolean standingWarranted(Context c, String scope) {
        return KIND_STANDING.equals(warrantKind(c, scope));
    }

    /** Consume a one-shot warrant. Single use: the second call returns false. */
    static boolean redeemShot(Context c, String scope) {
        if (!KIND_SHOT.equals(warrantKind(c, scope))) return false;
        SharedPreferences.Editor e = sp(c).edit();
        e.remove("w_" + scope);
        commit(e);
        return true;
    }

    /** One-line status for the diagnostics slot. */
    static String describe(Context c, String scope) {
        String kind = warrantKind(c, scope);
        return "scope=" + scope
                + " links=" + length(c, scope)
                + " chain=" + (verified(c, scope) ? "verified" : "broken")
                + " binding=" + fingerprint(c, scope)
                + " warrant=" + (kind == null ? "none" : kind);
    }
}
