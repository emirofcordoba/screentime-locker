package com.vortex.timelock;

/**
 * ApprovalTrigger — the closed set of STATIC events that may advance an approval
 * checkpoint.
 *
 * "Static" means the event has already happened before the barrier consults it:
 * a touch that was delivered, a lifecycle callback the framework raised, a
 * broadcast that arrived, or a wall-clock comparison performed while the user is
 * already staring at the gate. Nothing in this enumeration can fire by itself.
 *
 * HARD INVARIANTS — no exceptions, no additions that violate them:
 *   - no Handler, Looper.postDelayed or postAtTime;
 *   - no Timer, ScheduledExecutorService or Thread;
 *   - no AlarmManager, JobScheduler or foreground service;
 *   - no wake lock, no ContentObserver, no polling loop.
 *
 * An armed barrier therefore costs ZERO CPU and ZERO battery while it merely
 * rests: its entire state lives in SharedPreferences and is only touched at the
 * instant one of these events is delivered. {@link #needsBackgroundWorker()} is
 * asserted false for every constant so that a later edit which smuggles a timer
 * into the layer fails loudly instead of silently draining the battery.
 */
enum ApprovalTrigger {

    /** Activity.onResume()/onStart() — the user brought the console forward. */
    ACTIVITY_RESUMED("console brought forward"),

    /** Activity.onWindowFocusChanged(true) — the console owns the foreground. */
    WINDOW_FOCUS_GAINED("console focused"),

    /** A checkpoint dialog was actually presented to the user. */
    DIALOG_SHOWN("checkpoint displayed"),

    /** The positive button of a checkpoint dialog was tapped. */
    DIALOG_POSITIVE("explicit tap"),

    /** An acknowledgement checkbox changed to checked. */
    ACK_TICKED("acknowledgement ticked"),

    /** A keystroke landed in a typed-consent field. */
    PHRASE_TYPED("consent phrase typed"),

    /** A restriction switch was flipped by the user. */
    SWITCH_FLIPPED("toggle flipped"),

    /** The platform biometric stack reported a genuine enrolled match. */
    BIOMETRIC_MATCHED("fingerprint matched"),

    /** A press-and-hold gesture was released after the required dwell. */
    HOLD_RELEASED("press-and-hold released"),

    /** A wall-clock deadline was compared and found to have elapsed. */
    DEADLINE_COMPARED("deliberation deadline compared"),

    /** The live configuration binding was re-read and compared. */
    BINDING_RECHECKED("configuration binding re-checked"),

    /** The device-owner state was re-read from DevicePolicyManager. */
    OWNER_STATE_RECHECKED("device-owner state re-read"),

    /** The persisted checkpoint chain was re-verified end to end. */
    LEDGER_VERIFIED("checkpoint chain re-verified");

    private final String label;

    ApprovalTrigger(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** Always false: no trigger in this project needs a background worker. */
    boolean needsBackgroundWorker() {
        return false;
    }
}
