package com.vortex.timelock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ============================================================================
 *  Screen Time Locker — initial onboarding (first screen)
 * ============================================================================
 *
 *  Build-ready twin of {@code kotlin/.../OnboardingActivity.kt}. The project is
 *  compiled with the standalone aapt2 + javac + d8 pipeline (no Gradle, no
 *  Kotlin compiler), so THIS Java class is the one that ships in the APK. The
 *  Kotlin source is kept in the {@code kotlin/} tree for parity / future
 *  migration; a Kotlin-enabled build should keep only one of the two.
 *
 *  Layout: {@code res/layout/activity_onboarding.xml}.
 *
 *  <p><b>Warning banner.</b> Pinned to the very top and rendered verbatim from
 *  {@code R.string.onboarding_warning_banner}. The message is a hard requirement
 *  — the operator must factory reset (or strip every account), disable auto-sync
 *  and reboot — because Device Owner can only be provisioned on an account-free,
 *  freshly-booted device. The wording is bound straight from the resource and is
 *  never reworded or truncated.
 *
 *  <p><b>Sequential manual permission flow.</b> The
 *  {@code R.id.permissionStepsContainer} hosts three distinct permission items,
 *  one per upcoming permission, each with its OWN "Approve" button. They are
 *  walked strictly in order and the NEXT button at the bottom (the layout's
 *  {@code R.id.btnContinue}) stays DISABLED until the CURRENT step's live probe
 *  ({@link PermissionFlow#isGranted}) returns true. Only the current step's
 *  Approve button is actionable; later steps stay locked and earlier ones show
 *  GRANTED:
 *
 *      step 1  Autostart permission  -> OEM autostart settings
 *      step 2  Notification permission -> POST_NOTIFICATIONS runtime prompt
 *      step 3  Battery optimisation  -> ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *
 *  Every capability check and every system prompt lives in {@link PermissionFlow};
 *  this screen only owns presentation, the gating and the hand-off. CONTINUE on
 *  the last step hands over to {@link PermissionActivity}.
 */
public class OnboardingActivity extends Activity {

    static final String TAG = "TL.Onboarding";

    // ---- views ----
    private LinearLayout stepsContainer;
    private TextView stepsPlaceholder;
    /** The layout's bottom button: doubles as the gated NEXT button of the ladder. */
    private Button nextButton;

    // ---- presentation ----
    private Ui ui;

    // ---- sequential-flow state ----
    /** Index into {@link PermissionFlow}'s ordered steps: the only unlocked row. */
    private int currentStep = 0;
    /** True until the first refresh has auto-skipped already-satisfied steps. */
    private boolean initialised = false;
    /** Set when we send the operator to the OEM autostart screen; consumed on return. */
    private boolean pendingAutostart = false;

    // ---- per-step live widgets, indexed by PermissionFlow step id ----
    private final View[] stepRows = new View[PermissionFlow.count()];
    private final TextView[] stepStates = new TextView[PermissionFlow.count()];
    private final Button[] stepApproveButtons = new Button[PermissionFlow.count()];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        ui = new Ui(this).bind(this);

        stepsContainer = findViewById(R.id.permissionStepsContainer);
        stepsPlaceholder = findViewById(R.id.permissionStepsPlaceholder);
        nextButton = findViewById(R.id.btnContinue);

        // The banner text is a single resource string bound as-is: the required
        // wording must render exactly, with no local re-wording.
        TextView banner = findViewById(R.id.warningBannerText);
        if (banner != null) {
            banner.setText(R.string.onboarding_warning_banner);
        }

        renderPermissionSteps();

        if (nextButton != null) {
            nextButton.setOnClickListener(v -> onNext());
        }

        refreshFlow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The operator has just been out to a system surface (autostart / battery /
        // notification settings) and is back: fold that visit into the state and
        // re-evaluate the gate.
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
        if (requestCode == PermissionFlow.REQ_NOTIFICATIONS) {
            refreshFlow();
        }
    }

    // =====================================================================
    // Sequentially gated steps
    // =====================================================================

    /**
     * Builds one row per {@link PermissionFlow} step into the shared container,
     * removing the empty placeholder on first injection. The rows are presented
     * all at once but gated one-by-one: {@link #refreshFlow()} keeps only the
     * current step's Approve button actionable.
     */
    private void renderPermissionSteps() {
        if (stepsContainer == null) return;
        stepsContainer.removeAllViews();
        // Let addStep() do the placeholder bookkeeping so the container is clean.
        if (stepsPlaceholder != null && stepsPlaceholder.getParent() != stepsContainer) {
            stepsContainer.addView(stepsPlaceholder);
        }
        for (int step = 0; step < PermissionFlow.count(); step++) {
            addStep(buildStepRow(step));
        }
    }

    /**
     * Adds a single permission-step row to the shared container, removing the
     * empty placeholder on first use.
     */
    public void addStep(View view) {
        if (stepsContainer == null || view == null) return;
        if (stepsPlaceholder != null && stepsPlaceholder.getParent() == stepsContainer) {
            stepsContainer.removeView(stepsPlaceholder);
        }
        stepsContainer.addView(view);
    }

    /** One permission item: number + title, live state chip, description, Approve. */
    private View buildStepRow(final int step) {
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

    // =====================================================================
    // Flow control
    // =====================================================================

    /** The operator's single manual action for [step]. */
    private void onApprove(int step) {
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
     * The gated NEXT action. It refuses to advance unless the CURRENT step's live
     * probe returns true; on the last step it hands over to {@link PermissionActivity}.
     */
    private void onNext() {
        if (!PermissionFlow.isGranted(this, currentStep)) {
            refreshFlow();     // keep the button state honest
            return;
        }
        if (currentStep < PermissionFlow.count() - 1) {
            currentStep++;
            refreshFlow();
            return;
        }
        handOff();
    }

    /** Continue into the existing permission onboarding / setup console. */
    private void handOff() {
        try {
            startActivity(new Intent(this, PermissionActivity.class));
        } catch (Throwable t) {
            // Never let a missing hand-off trap the operator on a dead end.
            if (nextButton != null) nextButton.setEnabled(true);
        }
    }

    /**
     * Single refresh point: probes every step, paints its live state, enables ONLY
     * the current step's Approve button, and unlocks the NEXT button strictly when
     * the current permission returns true.
     */
    private void refreshFlow() {
        int count = PermissionFlow.count();

        // On first paint, silently skip past leading steps that are already
        // satisfied so the operator lands on the first thing that needs them.
        if (!initialised) {
            initialised = true;
            while (currentStep < count - 1 && PermissionFlow.isGranted(this, currentStep)) {
                currentStep++;
            }
        }

        for (int step = 0; step < count; step++) {
            boolean granted = PermissionFlow.isGranted(this, step);
            boolean isCurrent = (step == currentStep);

            setState(stepStates[step], granted, isCurrent);

            Button approve = stepApproveButtons[step];
            if (approve != null) {
                // Actionable only while it is the current step and still needed.
                approve.setEnabled(isCurrent && !granted);
                approve.setAlpha((isCurrent && !granted) ? 1f : 0.5f);
            }
            View row = stepRows[step];
            if (row != null) {
                // Locked (future) rows read a little dimmer; once you reach them
                // and they are still needed they come to full strength.
                boolean locked = (step > currentStep);
                row.setAlpha(locked ? 0.55f : 1f);
            }
        }

        if (nextButton != null) {
            boolean ready = PermissionFlow.isGranted(this, currentStep);
            nextButton.setEnabled(ready);
            nextButton.setAlpha(ready ? 1f : 0.5f);
            nextButton.setText(nextButtonLabel(ready));
        }
    }

    private void setState(TextView tv, boolean granted, boolean isCurrent) {
        if (tv == null) return;
        if (granted) {
            tv.setText(R.string.perm_state_granted);
            tv.setTextColor(ui.ok);
        } else if (isCurrent) {
            tv.setText(R.string.perm_state_needed);
            tv.setTextColor(ui.dangerText);
        } else {
            tv.setText(R.string.perm_state_locked);
            tv.setTextColor(ui.textFaint);
        }
    }

    private String nextButtonLabel(boolean ready) {
        int count = PermissionFlow.count();
        if (currentStep >= count - 1) {
            return getString(R.string.onboarding_continue);
        }
        String next = getString(R.string.onboarding_next)
                + " \u2192 " + PermissionFlow.shortTitle(this, currentStep + 1);
        return ready ? next : getString(R.string.perm_state_needed) + " \u00b7 " + next;
    }

    // =====================================================================
    // Small widget helpers
    // =====================================================================

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

    private int dp(int v) {
        return ui.dp(v);
    }
}
