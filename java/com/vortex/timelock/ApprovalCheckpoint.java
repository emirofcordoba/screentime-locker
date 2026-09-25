package com.vortex.timelock;

/**
 * ApprovalCheckpoint — one immutable link in a tiered approval chain.
 *
 * A checkpoint knows three things: where it sits (tier + index), what the user
 * must physically do to pass it ({@link Kind}), and which static event is
 * allowed to record the pass ({@link ApprovalTrigger}). It carries no logic and
 * no state of its own: the runner ({@link ApprovalWalk}) decides how each kind
 * is presented, and the ledger ({@link ApprovalLedger}) records the outcome.
 *
 * Splitting the chain into "notice / acknowledgement / typed consent / identity
 * / deliberation / re-verification / final acceptance" tiers means no single
 * dialog can ever carry the whole decision. A user who is in a hurry, or an
 * attacker driving the UI, has to stop at every tier independently, and every
 * stop is a fresh, separately worded, separately triggered consent.
 *
 * STATIC-EVENT INVARIANT: nothing in this class can fire on its own. It is a
 * data structure. Presenting it costs nothing while it rests in memory, and a
 * chain assembled here has zero power draw until a user touches it.
 */
final class ApprovalCheckpoint {

    /**
     * What the checkpoint demands. Each constant maps to exactly one class of
     * genuinely static event; none of them can be satisfied by waiting.
     */
    enum Kind {
        /** Re-reads live device/platform state and refuses to continue on drift. */
        PRECONDITION,
        /** Pure disclosure: read it, tap continue. */
        NOTICE,
        /** A ticked acknowledgement checkbox. */
        ACK,
        /** An exactly typed consent phrase (case sensitive). */
        PHRASE,
        /** A typed challenge token minted from the ledger key (case insensitive). */
        CHALLENGE,
        /** A genuine enrolled-fingerprint match from the platform stack. */
        BIOMETRIC,
        /** An unavoidable wall-clock deliberation window, compared on each tap. */
        DEADLINE,
        /** A press-and-hold gesture released only after the required dwell. */
        HOLD,
        /** Re-reads the configuration binding and compares it to link zero. */
        BINDING_RECHECK,
        /** The point of no return: an explicitly labelled final acceptance. */
        FINAL;

        /** True for the kinds that require the user to actively type or tick. */
        boolean isActiveConsent() {
            return this == ACK || this == PHRASE || this == CHALLENGE
                    || this == BIOMETRIC;
        }

        /** True for the kinds that consume wall-clock time deliberately. */
        boolean isDeliberative() {
            return this == DEADLINE || this == HOLD;
        }
    }

    /**
     * A pre-flight condition that must still hold at the moment the checkpoint
     * is passed. Guards are evaluated by the runner against live state, never
     * against a cached copy, so a policy change made half-way through the chain
     * collapses the whole walk.
     */
    enum Guard {
        NONE,
        /** The app must still be the real Device Owner with an active admin. */
        DEVICE_OWNER,
        /** A usable daily interval must already be configured. */
        ACTIVATION_INPUTS,
        /** A usable way back in (an enrolled fingerprint) must exist. */
        RE_ENTRY_CREDENTIAL,
        /** At least one root-level restriction must actually be requested. */
        EXTREME_REQUESTED
    }

    /** Which tier of the ladder this link belongs to (1-based). */
    final int tier;
    /** Position within the tier (1-based); purely descriptive. */
    final int index;
    /** Stable identifier; recorded verbatim in the ledger entry. */
    final String id;
    final Kind kind;
    final ApprovalTrigger trigger;
    final Guard guard;
    final String title;
    final String message;
    final String positiveLabel;
    final String negativeLabel;
    /** Exact case-sensitive phrase for {@link Kind#PHRASE}. */
    final String phrase;
    /** Checkbox caption for {@link Kind#ACK}. */
    final String ackLabel;
    /** Millisecond dwell for {@link Kind#DEADLINE} and {@link Kind#HOLD}. */
    final long dwellMs;
    /** True for the terminal acceptance of an irreversible, device-wide action. */
    final boolean destructive;

    private ApprovalCheckpoint(Builder b) {
        this.tier = b.tier;
        this.index = b.index;
        this.id = b.id;
        this.kind = b.kind;
        this.trigger = b.trigger;
        this.guard = b.guard;
        this.title = b.title;
        this.message = b.message;
        this.positiveLabel = b.positiveLabel;
        this.negativeLabel = b.negativeLabel;
        this.phrase = b.phrase;
        this.ackLabel = b.ackLabel;
        this.dwellMs = b.dwellMs;
        this.destructive = b.destructive;
    }

    /** Start a builder for a checkpoint at {@code tier}/{@code index}. */
    static Builder at(int tier, int index, String id, Kind kind) {
        return new Builder(tier, index, id, kind);
    }

    /** One-line diagnostic; written to the tiny last-event slot, never parsed. */
    String describe() {
        return "t" + tier + "." + index + " " + kind + " [" + id + "]"
                + (destructive ? " !" : "");
    }

    /**
     * Text a user must reproduce for {@link Kind#PHRASE}, or the token for
     * {@link Kind#CHALLENGE}. Null for every other kind.
     */
    String expected() {
        if (kind == Kind.PHRASE) return phrase;
        return null;
    }

    static final class Builder {
        private final int tier;
        private final int index;
        private final String id;
        private final Kind kind;
        private ApprovalTrigger trigger = ApprovalTrigger.DIALOG_POSITIVE;
        private Guard guard = Guard.NONE;
        private String title = "";
        private String message = "";
        private String positiveLabel = "Continue";
        private String negativeLabel = "Abandon";
        private String phrase;
        private String ackLabel;
        private long dwellMs;
        private boolean destructive;

        Builder(int tier, int index, String id, Kind kind) {
            this.tier = tier;
            this.index = index;
            this.id = id;
            this.kind = kind;
        }

        Builder trigger(ApprovalTrigger t) { this.trigger = t; return this; }
        Builder guard(Guard g) { this.guard = g; return this; }
        Builder title(String s) { this.title = s; return this; }
        Builder message(String s) { this.message = s; return this; }
        Builder buttons(String positive, String negative) {
            if (positive != null) this.positiveLabel = positive;
            if (negative != null) this.negativeLabel = negative;
            return this;
        }
        Builder phrase(String s) { this.phrase = s; return this; }
        Builder ack(String s) { this.ackLabel = s; return this; }
        Builder dwell(long ms) { this.dwellMs = ms; return this; }
        Builder destructive(boolean v) { this.destructive = v; return this; }

        ApprovalCheckpoint build() {
            if (kind == Kind.PHRASE && (phrase == null || phrase.isEmpty())) {
                throw new IllegalStateException("PHRASE checkpoint needs a phrase: " + id);
            }
            if (kind == Kind.ACK && (ackLabel == null || ackLabel.isEmpty())) {
                throw new IllegalStateException("ACK checkpoint needs a label: " + id);
            }
            if (kind.isDeliberative() && dwellMs <= 0L) {
                throw new IllegalStateException("deliberative checkpoint needs a dwell: " + id);
            }
            return new ApprovalCheckpoint(this);
        }
    }
}
