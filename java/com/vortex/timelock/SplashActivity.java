package com.vortex.timelock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  Digital Retreat — strict GATEKEEPER splash (first surface)
 * ============================================================================
 *
 *  The very first screen of the app, and a hard gate: the operator cannot get
 *  into the setup chain, and cannot leave this screen, without first reading the
 *  mandatory pre-requisite, explicitly acknowledging it, AND approving the three
 *  runtime capabilities the engine depends on — one at a time.
 *
 *  Layout: {@code res/layout/activity_splash.xml}.
 *
 *  <p><b>1 · Top warning banner (verbatim).</b> Pinned to the very top and
 *  rendered verbatim from {@link R.string#onboarding_warning_banner}: the
 *  operator must factory reset the phone OR completely remove all accounts,
 *  turn off auto-sync and reboot. This is a hard requirement, not advice —
 *  Device Owner can only be provisioned on an account-free, freshly-booted
 *  device — so the wording is bound straight from the resource and is never
 *  reworded, split or truncated.</p>
 *
 *  <p><b>2 · Full usage of the screen lock app.</b> Beneath the banner the
 *  screen explains, step by step, exactly what the app does and in the order the
 *  operator will meet it (choose a limit · how time is counted · what happens at
 *  the limit · the live counter · finishing setup · getting back in · reboots
 *  and persistence). The rows are injected at runtime into
 *  {@code R.id.usageContainer}; a "Learn Full App Usage" shortcut opens the same
 *  documentation as a scrollable bottom sheet via {@link SheetDialog}.</p>
 *
 *  <p><b>3 · The strict gate.</b> The gate has two independent locks that must
 *  BOTH be satisfied before CONTINUE unlocks:</p>
 *
 *  <ol>
 *    <li><b>Acknowledgement.</b> The mandatory CheckBox must be ticked.</li>
 *    <li><b>Sequential manual permission ladder.</b> Three capabilities are
 *        presented as three rows in {@code R.id.gateStepsContainer}, walked
 *        strictly in order — Autostart (OEM settings), Notifications
 *        ({@code POST_NOTIFICATIONS}) and Battery optimisation (ignore list).
 *        Each row has its OWN "Approve" button that opens the exact system
 *        surface for THAT one permission (via {@link PermissionFlow}); only the
 *        CURRENT step's button is actionable and every later row stays LOCKED
 *        until the previous row's live probe returns true. There is no blanket
 *        prompt and nothing is requested behind the operator's back.</li>
 *  </ol>
 *
 *  <p>{@link #onBackPressed()} is a no-op: the back button / back gesture cannot
 *  dismiss the gatekeeper. The only way forward is to acknowledge, approve all
 *  three permissions, and continue.</p>
 *
 *  <p><b>Single source of truth.</b> Every capability probe, every system
 *  surface and the autostart attestation live in {@link PermissionFlow}; this
 *  screen only owns presentation, the sequential gating and the hand-off. This
 *  is the same ladder {@link OnboardingActivity} drives, so the two screens can
 *  never disagree about what "granted" means.</p>
 *
 *  <p><b>Entry point.</b> This is the manifest's launcher activity. Once the
 *  gate is cleared it hands over to {@link LauncherDashboard}, preserving the
 *  existing dashboard → onboarding → permissions → setup flow exactly as it was.
 *  {@link Engine#freezeLaunchers} treats this activity like every other
 *  MAIN+LAUNCHER entry, so a fully provisioned install still shows no icon.</p>
 */
public class SplashActivity extends Activity {

    static final String TAG = "TL.Splash";

    // ---- usage-documentation content, in reading order --------------------
    private static final int[] USAGE_TITLES = {
            R.string.splash_usage_1_title,
            R.string.splash_usage_2_title,
            R.string.splash_usage_3_title,
            R.string.splash_usage_4_title,
            R.string.splash_usage_5_title,
            R.string.splash_usage_6_title,
            R.string.splash_usage_7_title,
    };
    private static final int[] USAGE_BODIES = {
            R.string.splash_usage_1_body,
            R.string.splash_usage_2_body,
            R.string.splash_usage_3_body,
            R.string.splash_usage_4_body,
            R.string.splash_usage_5_body,
            R.string.splash_usage_6_body,
            R.string.splash_usage_7_body,
    };

    // ---- views ----
    private LinearLayout usageContainer;
    private TextView usagePlaceholder;
    private LinearLayout gateStepsContainer;
    private TextView gateStepsPlaceholder;
    private CheckBox ackCheck;
    private Button enterButton;
    private TextView enterHint;

    // ---- presentation ----
    private Ui ui;

    // ---- sequential permission-ladder state ----
    /** Index into {@link PermissionFlow}'s ordered steps: the only unlocked row. */
    private int currentStep = 0;
    /** Set when we send the operator to the OEM autostart screen; consumed on return. */
    private boolean pendingAutostart = false;

    // ---- per-step live widgets, indexed by PermissionFlow step id ----
    private final View[] stepRows = new View[PermissionFlow.count()];
    private final TextView[] stepStates = new TextView[PermissionFlow.count()];
    private final Button[] stepApproveButtons = new Button[PermissionFlow.count()];

    // ==================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        ui = new Ui(this).bind(this);

        usageContainer = findViewById(R.id.usageContainer);
        usagePlaceholder = findViewById(R.id.usagePlaceholder);
        gateStepsContainer = findViewById(R.id.gateStepsContainer);
        gateStepsPlaceholder = findViewById(R.id.gateStepsPlaceholder);
        ackCheck = findViewById(R.id.ackCheck);
        enterButton = findViewById(R.id.btnEnter);
        enterHint = findViewById(R.id.enterHint);

        // Bind the banner text straight from the resource: the required wording
        // is a single string and must render exactly as specified.
        TextView banner = findViewById(R.id.splashBannerText);
        if (banner != null) banner.setText(R.string.onboarding_warning_banner);

        renderUsage();
        renderGateSteps();

        Button learn = findViewById(R.id.btnLearn);
        if (learn != null) learn.setOnClickListener(v -> openFullUsageSheet());

        // The gate: CONTINUE only unlocks once the acknowledgement is ticked AND
        // all three permission steps pass their live checks.
        ackCheck.setOnCheckedChangeListener((b, checked) -> refreshFlow());

        enterButton.setOnClickListener(v -> onEnter());

        refreshFlow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The operator has just been out to a system surface (OEM autostart /
        // notification settings / battery exemption) and is back: fold that visit
        // into the state and re-evaluate the whole gate.
        if (pendingAutostart) {
            pendingAutostart = false;
            PermissionFlow.markAutostartAttested(this);
        }
        refreshFlow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The POST_NOTIFICATIONS prompt returned: re-probe and re-evaluate the gate.
        if (requestCode == PermissionFlow.REQ_NOTIFICATIONS) {
            refreshFlow();
        }
    }

    // ==================================================================== the gate

    /**
     * STRICT: the gatekeeper cannot be dismissed with the back button or the
     * back gesture. Swallow the event, tell the operator why, and stay put.
     */
    @Override
    public void onBackPressed() {
        // Deliberately NOT calling super: the screen must be read and confirmed.
        toast(getString(R.string.splash_back_blocked));
    }

    /**
     * True only when EVERY {@link PermissionFlow} step reports granted. The
     * CONTINUE button is gated on this AND on the acknowledgement.
     */
    private boolean allGranted() {
        for (int step = 0; step < PermissionFlow.count(); step++) {
            if (!PermissionFlow.isGranted(this, step)) return false;
        }
        return true;
    }

    /**
     * Keeps the CONTINUE button honest: enabled iff the acknowledgement is
     * ticked AND every permission step has passed. The hint line spells out
     * exactly which of the two locks is still closed.
     */
    private void applyGate(boolean acknowledged) {
        boolean ready = acknowledged && allGranted();
        if (enterButton != null) {
            enterButton.setEnabled(ready);
            enterButton.setAlpha(ready ? 1f : 0.5f);
        }
        if (enterHint != null) {
            if (ready) {
                enterHint.setVisibility(View.GONE);
            } else {
                enterHint.setVisibility(View.VISIBLE);
                enterHint.setText(!acknowledged
                        ? R.string.splash_enter_locked
                        : R.string.splash_gate_locked_hint);
            }
        }
    }

    /** The operator's single way forward, and only after both gate locks pass. */
    private void onEnter() {
        boolean acknowledged = ackCheck != null && ackCheck.isChecked();
        if (!acknowledged) {
            applyGate(false);
            return;
        }
        if (!allGranted()) {
            refreshFlow();     // keep the buttons honest
            toast(getString(R.string.splash_gate_locked_hint));
            return;
        }
        handOff();
    }

    /**
     * Hands over to the existing dashboard, which owns the rest of the flow.
     * Defensive fallbacks keep the operator off a dead end if a component is
     * unavailable.
     */
    private void handOff() {
        try {
            startActivity(new Intent(this, LauncherDashboard.class));
            return;
        } catch (Throwable t) {
            // fall through
        }
        try {
            startActivity(new Intent(this, MainSetupActivity.class));
            return;
        } catch (Throwable t) {
            // fall through
        }
        try {
            startActivity(new Intent(this, PermissionActivity.class));
        } catch (Throwable t) {
            // Both hand-offs unavailable; the CONTINUE button remains live.
        }
    }

    // ==================================================================== permission ladder

    /**
     * Builds one row per {@link PermissionFlow} step into the shared container,
     * removing the empty placeholder on first injection. The rows are presented
     * all at once but gated one-by-one: {@link #refreshFlow()} keeps only the
     * current step's Approve button actionable and keeps every later row LOCKED.
     */
    private void renderGateSteps() {
        if (gateStepsContainer == null) return;
        gateStepsContainer.removeAllViews();
        // Let addGateStep() do the placeholder bookkeeping so the container is clean.
        if (gateStepsPlaceholder != null
                && gateStepsPlaceholder.getParent() != gateStepsContainer) {
            gateStepsContainer.addView(gateStepsPlaceholder);
        }
        for (int step = 0; step < PermissionFlow.count(); step++) {
            addGateStep(buildGateRow(step));
        }
    }

    /** Adds a single permission-step row, removing the placeholder on first use. */
    private void addGateStep(View view) {
        if (gateStepsContainer == null || view == null) return;
        if (gateStepsPlaceholder != null
                && gateStepsPlaceholder.getParent() == gateStepsContainer) {
            gateStepsContainer.removeView(gateStepsPlaceholder);
        }
        gateStepsContainer.addView(view);
    }

    /** One permission item: number + title, live state chip, description, Approve. */
    private View buildGateRow(final int step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1f));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        stepRows[step] = row;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText((step + 1) + " \u00b7 " + PermissionFlow.title(this, step));
        title.setTextColor(ui.text);
        title.setTextSize(15f);
        title.setTypeface(Ui.bold());
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);

        TextView state = new TextView(this);
        state.setText("");
        state.setTextSize(11.5f);
        state.setTypeface(Ui.bold());
        state.setTextColor(ui.textFaint);
        state.setLetterSpacing(0.08f);
        top.addView(state);
        stepStates[step] = state;
        row.addView(top);

        TextView desc = new TextView(this);
        desc.setText(PermissionFlow.description(this, step));
        desc.setTextColor(ui.textFaint);
        desc.setTextSize(12.5f);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(0, dp(6), 0, 0);
        row.addView(desc);

        Button approve = actionButton(getString(R.string.perm_approve),
                ui.accent, ui.onAccent, 13f);
        approve.setOnClickListener(v -> onApprove(step));
        stepApproveButtons[step] = approve;
        row.addView(approve);

        return row;
    }

    /** The operator's single manual action for [step]. */
    private void onApprove(int step) {
        // The ladder is behind the acknowledgement: nothing is actionable until
        // the warning above has been read and ticked.
        boolean acknowledged = ackCheck != null && ackCheck.isChecked();
        if (!acknowledged) {
            toast(getString(R.string.splash_enter_locked));
            return;
        }
        // Only the current, not-yet-granted step is actionable: never let a later
        // item be opened before the previous one is satisfied.
        if (step != currentStep) return;
        if (PermissionFlow.isGranted(this, step)) {
            refreshFlow();
            return;
        }
        if (step == PermissionFlow.STEP_AUTOSTART) {
            // There is no read API for the OEM whitelist: remember that we sent the
            // operator out, and fold the visit in when they come back.
            pendingAutostart = true;
        }
        PermissionFlow.approve(this, step);
    }

    /**
     * Single refresh point: probes every step, paints its live state, enables ONLY
     * the current step's Approve button (and only once the acknowledgement is
     * ticked), advances past already-satisfied steps, and re-evaluates CONTINUE.
     */
    private void refreshFlow() {
        boolean acknowledged = ackCheck != null && ackCheck.isChecked();
        int count = PermissionFlow.count();

        // Silently skip past leading steps that are already satisfied so the
        // operator lands on the first thing that still needs them. Runs again on
        // every resume, so a grant made in system Settings is picked up on return.
        while (currentStep < count - 1 && PermissionFlow.isGranted(this, currentStep)) {
            currentStep++;
        }

        for (int step = 0; step < count; step++) {
            boolean granted = PermissionFlow.isGranted(this, step);
            boolean isCurrent = (step == currentStep);
            boolean unlocked = acknowledged && isCurrent && !granted;

            setState(stepStates[step], granted, isCurrent, acknowledged);

            Button approve = stepApproveButtons[step];
            if (approve != null) {
                // Actionable only while it is the current step, still needed, and
                // the acknowledgement has been ticked.
                approve.setEnabled(unlocked);
                approve.setAlpha(unlocked ? 1f : 0.5f);
            }
            View row = stepRows[step];
            if (row != null) {
                // Locked (future / pre-acknowledgement) rows read a little dimmer;
                // the current one comes to full strength once it is actionable.
                boolean locked = !acknowledged || step > currentStep;
                row.setAlpha(locked ? 0.55f : 1f);
            }
        }

        applyGate(acknowledged);
    }

    private void setState(TextView tv, boolean granted, boolean isCurrent, boolean acknowledged) {
        if (tv == null) return;
        if (granted) {
            tv.setText(R.string.perm_state_granted);
            tv.setTextColor(ui.ok);
        } else if (acknowledged && isCurrent) {
            tv.setText(R.string.perm_state_needed);
            tv.setTextColor(ui.dangerText);
        } else {
            tv.setText(R.string.perm_state_locked);
            tv.setTextColor(ui.textFaint);
        }
    }

    // ==================================================================== usage UI

    /** Builds one numbered usage row per feature into the shared container. */
    private void renderUsage() {
        if (usageContainer == null) return;
        usageContainer.removeAllViews();
        if (usagePlaceholder != null) {
            usageContainer.addView(usagePlaceholder);
        }
        for (int i = 0; i < USAGE_TITLES.length; i++) {
            addUsageRow(buildUsageRow(i));
        }
    }

    /** Adds a row, removing the empty placeholder on first use. */
    private void addUsageRow(View row) {
        if (usageContainer == null || row == null) return;
        if (usagePlaceholder != null && usagePlaceholder.getParent() == usageContainer) {
            usageContainer.removeView(usagePlaceholder);
        }
        usageContainer.addView(row);
    }

    /** One usage item: numbered bold title + subordinate body line. */
    private View buildUsageRow(int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(10), dp(2), dp(10));

        TextView title = new TextView(this);
        title.setText(USAGE_TITLES[index]);
        title.setTextColor(ui.text);
        title.setTextSize(15.5f);
        title.setTypeface(Ui.bold());
        row.addView(title);

        TextView body = new TextView(this);
        body.setText(USAGE_BODIES[index]);
        body.setTextColor(ui.textFaint);
        body.setTextSize(13.5f);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(4), 0, 0);
        row.addView(body);

        return row;
    }

    /** Opens the same documentation as a scrollable bottom sheet. */
    private void openFullUsageSheet() {
        List<SheetDialog.Section> sections = new ArrayList<>();
        for (int i = 0; i < USAGE_TITLES.length; i++) {
            sections.add(new SheetDialog.Section(
                    getString(USAGE_TITLES[i]), getString(USAGE_BODIES[i])));
        }
        try {
            SheetDialog.show(this, ui,
                    getString(R.string.splash_sheet_kicker),
                    getString(R.string.splash_learn_button),
                    getString(R.string.splash_sheet_intro),
                    sections);
        } catch (Throwable t) {
            // A toast is enough if the sheet cannot be shown; never crash the gate.
            toast(getString(R.string.splash_learn_button));
        }
    }

    // ==================================================================== helpers

    /** Full-width accent action button used by each permission row. */
    private Button actionButton(String label, int bg, int fg, float sizeSp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(sizeSp);
        b.setTextColor(fg);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(bg, 14));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { /* toast can race a finishing Activity */ }
    }

    private int dp(int v) {
        return ui.dp(v);
    }
}
