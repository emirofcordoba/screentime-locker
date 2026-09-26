package com.vortex.timelock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * ============================================================================
 *  Screen Time Locker — STRICT single-page GATEKEEPER (the one launcher entry)
 * ============================================================================
 *
 *  A single, self-contained page that gates the whole app. Nothing here is
 *  requested behind the operator's back and nothing can be skipped: the four
 *  prerequisites are walked strictly in order on ONE screen, and progression is
 *  refused until every one of them is confirmed by a live probe.
 *
 *  <p><b>1 · Warning banner (verbatim).</b> Pinned to the very top and rendered
 *  verbatim from {@link R.string#onboarding_warning_banner}: the operator must
 *  factory reset the phone OR completely remove all accounts, turn off auto-sync
 *  and reboot. This is a hard pre-requisite — Device Owner can only be
 *  provisioned on an account-free, freshly-booted device — so the wording is
 *  bound straight from the resource and never reworded, split or truncated.</p>
 *
 *  <p><b>2 · What this app is.</b> A short plain-language explanation of the
 *  screen-time lock so the operator never approves something they do not
 *  understand.</p>
 *
 *  <p><b>3 · Four sequential buttons.</b> Presented as four rows on this same
 *  page, walked strictly in order:</p>
 *  <ol>
 *    <li><b>Autostart</b> — opens the OEM autostart / startup screen (via
 *        {@link PermissionFlow#openAutostart}); the visit is attested on return
 *        because no vendor whitelist is readable by an app.</li>
 *    <li><b>Notifications</b> — the {@code POST_NOTIFICATIONS} prompt.</li>
 *    <li><b>Battery</b> — the ignore-battery-optimisation exemption dialog.</li>
 *    <li><b>Shizuku Device Admin</b> — silently provisions Device Owner through
 *        the running Shizuku service: {@code pm list users} → {@code pm
 *        remove-user <id>} for every non-zero user → {@code dpm set-device-owner
 *        <pkg>/.TimeLockAdmin}, all on a background worker.</li>
 *  </ol>
 *
 *  <p><b>Dynamic onResume gate.</b> {@link #onResume()} re-reads every live
 *  probe (including the Device-Owner state) each time the operator returns from
 *  a system surface or the Shizuku dialog, recomputes the first unfinished step
 *  and unlocks EXACTLY one row. Later rows stay LOCKED, and the final CONTINUE
 *  button stays disabled until all four steps are approved. The back button is a
 *  no-op: this gate cannot be dismissed.</p>
 *
 *  <p>All colour and geometry come from the shared {@link Ui} design tokens so
 *  the page follows the system light/dark theme exactly like the rest of the app.
 */
public class GatekeeperActivity extends Activity {

    static final String TAG = "TL.Gatekeeper";

    // ---- the four ordered steps -------------------------------------------
    private static final int STEP_AUTOSTART     = 0;
    private static final int STEP_NOTIFICATIONS = 1;
    private static final int STEP_BATTERY       = 2;
    private static final int STEP_DEVICE_OWNER  = 3;
    private static final int STEP_COUNT         = 4;

    private static final int[] STEP_TITLES = {
            R.string.gk_step_autostart_title,
            R.string.gk_step_notifications_title,
            R.string.gk_step_battery_title,
            R.string.gk_step_owner_title,
    };
    private static final int[] STEP_DESCS = {
            R.string.gk_step_autostart_desc,
            R.string.gk_step_notifications_desc,
            R.string.gk_step_battery_desc,
            R.string.gk_step_owner_desc,
    };
    /** One-line, plain-language helper shown under each step title. */
    private static final int[] STEP_SHORT = {
            R.string.gk_step_autostart_short,
            R.string.gk_step_notifications_short,
            R.string.gk_step_battery_short,
            R.string.gk_step_owner_short,
    };
    /** Short, action-first label for each step's button (what the tap will do). */
    private static final int[] STEP_ACTIONS = {
            R.string.gk_btn_autostart,
            R.string.gk_btn_notifications,
            R.string.gk_btn_battery,
            R.string.gk_btn_owner,
    };

    // ---- presentation ----
    private Ui ui;

    // ---- per-step live widgets, indexed by step id ----
    private final LinearLayout[] stepRows  = new LinearLayout[STEP_COUNT];
    private final TextView[]     stepStates = new TextView[STEP_COUNT];
    private final TextView[]     stepIcons  = new TextView[STEP_COUNT];
    private final Button[]       stepButtons = new Button[STEP_COUNT];

    private Button continueButton;
    private TextView continueHint;
    private TextView provisionStatus;
    private TextView progressLabel;

    // ---- gate state ----
    /** Set when we send the operator to the OEM autostart screen; consumed on return. */
    private boolean pendingAutostart = false;
    /** True while the Shizuku Device-Owner chain is executing. */
    private final AtomicBoolean provisioning = new AtomicBoolean(false);

    /** Single worker thread so the blocking shell chain never touches the UI thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gatekeeper-provision");
        t.setDaemon(true);
        return t;
    });

    // ---- Shizuku listeners (registered only while on screen) ----
    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == ShizukuBridge.REQ_PERMISSION) {
                        runOnUiThread(GatekeeperActivity.this::refreshGate);
                    }
                }
            };

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    runOnUiThread(GatekeeperActivity.this::refreshGate);
                }
            };

    // ==================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this).bind(this);
        buildUi();
        refreshGate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The operator has just come back from a system surface (OEM autostart /
        // notification prompt / battery exemption) or the Shizuku grant dialog:
        // fold that visit into the state and re-evaluate the WHOLE gate live.
        if (pendingAutostart) {
            pendingAutostart = false;
            PermissionFlow.markAutostartAttested(this);
        }
        // PERMISSION PERMANENCE: the one capability Android gives NO Device-Owner
        // pin for is the battery-optimization exemption. If it was dropped while we
        // were away, re-request it here - this is a foreground surface, which is the
        // only context Android 29+ allows to start that dialog. Owner-gated, gated on
        // a completed setup and rate-limited, so it never fights the onboarding
        // STEP_BATTERY row and never stacks dialogs.
        Engine.reassertBatteryIfLost(this);
        // PERMISSION PERMANENCE: likewise re-pin every declared runtime permission
        // (notification included) to its non-revocable GRANTED state on every
        // resume. DPM-policy only: no storage, no thread, no wake lock, and a free
        // no-op unless we are the Device Owner.
        Engine.reassertRuntimePins(this);
        ShizukuBridge.addPermissionListener(permListener);
        ShizukuBridge.addBinderListener(binderListener);
        refreshGate();
    }

    @Override
    protected void onPause() {
        ShizukuBridge.removePermissionListener(permListener);
        ShizukuBridge.removeBinderListener(binderListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui = new Ui(this).bind(this);
        buildUi();
        refreshGate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionFlow.REQ_NOTIFICATIONS) {
            refreshGate();
        }
    }

    /**
     * STRICT: the gatekeeper cannot be dismissed with the back button or the back
     * gesture. Swallow the event, explain why, and stay put.
     */
    @Override
    public void onBackPressed() {
        // Deliberately NOT calling super: the gate must be completed.
        toast(getString(R.string.gk_back_blocked));
    }

    // ==================================================================== UI shell

    private void buildUi() {
        applySystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(34));

        root.addView(buildBanner());
        root.addView(buildHeader());
        root.addView(buildStepsCard());
        root.addView(buildContinueCard());

        scroll.addView(root);
        setContentView(scroll);
    }

    /** The mandatory factory-reset / remove-accounts warning, bound verbatim. */
    private View buildBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.rounded(ui.dangerSoft, 16, ui.danger, 2f));
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(18);
        b.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(R.string.onboarding_warning_banner);
        t.setTextColor(ui.dangerText);
        t.setTextSize(14.5f);
        t.setTypeface(Ui.bold());
        t.setLineSpacing(dp(4), 1f);
        b.addView(t);
        return b;
    }

    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);

        TextView kicker = new TextView(this);
        kicker.setText(R.string.gk_kicker);
        kicker.setTextColor(ui.accent);
        kicker.setTextSize(12.5f);
        kicker.setTypeface(Ui.bold());
        kicker.setLetterSpacing(0.18f);
        head.addView(kicker);

        TextView title = new TextView(this);
        title.setText(R.string.gk_title);
        title.setTextColor(ui.text);
        title.setTextSize(26f);
        title.setTypeface(Ui.bold());
        title.setPadding(0, dp(6), 0, 0);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.gk_intro);
        sub.setTextColor(ui.textDim);
        sub.setTextSize(14.5f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(8), 0, dp(4));
        head.addView(sub);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        head.setLayoutParams(lp);
        return head;
    }

    private View buildStepsCard() {
        LinearLayout c = card();

        // ---- header line: short title on the left, progress read-out on the right ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = cardTitle(getString(R.string.gk_steps_header));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        progressLabel = new TextView(this);
        progressLabel.setText(R.string.gk_progress_empty);
        progressLabel.setTextColor(ui.accent);
        progressLabel.setTextSize(12.5f);
        progressLabel.setTypeface(Ui.bold());
        head.addView(progressLabel);
        c.addView(head);

        TextView intro = new TextView(this);
        intro.setText(R.string.gk_steps_intro);
        intro.setTextColor(ui.textFaint);
        intro.setTextSize(13f);
        intro.setLineSpacing(dp(3), 1f);
        intro.setPadding(0, dp(8), 0, dp(2));
        c.addView(intro);

        for (int step = 0; step < STEP_COUNT; step++) {
            c.addView(buildStepRow(step));
        }
        return c;
    }

    /**
     * One clean step card: a coloured status badge (check / warning / pending),
     * a short title with a one-line helper, the short action button, and the
     * deeper explanation tucked behind a "What does this do?" toggle so the page
     * stays short for non-technical readers.
     */
    private View buildStepRow(int step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ui.rounded(ui.surfaceAlt, 16, ui.border, 1f));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);

        // ---- top line: status badge + short title/helper + state word ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText("\u2013");
        icon.setTextSize(17f);
        icon.setTypeface(Ui.bold());
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(ui.textFaint);
        int badge = dp(32);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(badge, badge);
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        top.addView(icon);

        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(getString(STEP_TITLES[step]));
        t.setTextColor(ui.text);
        t.setTextSize(15f);
        t.setTypeface(Ui.bold());
        textWrap.addView(t);

        TextView help = new TextView(this);
        help.setText(getString(STEP_SHORT[step]));
        help.setTextColor(ui.textDim);
        help.setTextSize(12.5f);
        help.setLineSpacing(dp(2), 1f);
        help.setPadding(0, dp(2), 0, 0);
        textWrap.addView(help);
        top.addView(textWrap);

        TextView state = new TextView(this);
        state.setText("\u2014");
        state.setTextSize(11.5f);
        state.setTypeface(Ui.bold());
        state.setTextColor(ui.textFaint);
        state.setLetterSpacing(0.06f);
        state.setPadding(dp(8), 0, 0, 0);
        top.addView(state);
        row.addView(top);

        // ---- short, action-first button (what this tap will do) ----
        Button b = actionButton(getString(STEP_ACTIONS[step]), ui.accent, ui.onAccent, 14.5f);
        b.setOnClickListener(v -> act(step));
        row.addView(b);

        // ---- optional deeper explanation, hidden until the user asks for it ----
        TextView toggle = new TextView(this);
        toggle.setText(R.string.gk_details_show);
        toggle.setTextColor(ui.accent);
        toggle.setTextSize(12.5f);
        toggle.setTypeface(Ui.bold());
        toggle.setPadding(0, dp(10), 0, 0);
        row.addView(toggle);

        TextView details = new TextView(this);
        details.setText(getString(STEP_DESCS[step]));
        details.setTextColor(ui.textFaint);
        details.setTextSize(12.5f);
        details.setLineSpacing(dp(3), 1f);
        details.setPadding(0, dp(4), 0, 0);
        details.setVisibility(View.GONE);
        row.addView(details);

        toggle.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? R.string.gk_details_hide : R.string.gk_details_show);
        });

        stepRows[step] = row;
        stepIcons[step] = icon;
        stepStates[step] = state;
        stepButtons[step] = b;
        return row;
    }

    private View buildContinueCard() {
        LinearLayout c = card();
        c.addView(cardTitle(getString(R.string.gk_continue_header)));

        continueButton = actionButton(getString(R.string.gk_continue), ui.ok, ui.onAccent, 16f);
        continueButton.setOnClickListener(v -> onContinue());
        c.addView(continueButton);

        continueHint = new TextView(this);
        continueHint.setText(R.string.gk_continue_hint);
        continueHint.setTextColor(ui.textFaint);
        continueHint.setTextSize(12.5f);
        continueHint.setLineSpacing(dp(3), 1f);
        continueHint.setPadding(0, dp(10), 0, 0);
        c.addView(continueHint);

        return c;
    }

    // ==================================================================== the gate

    /** Live probe for one step. Every probe is defensive: failure reads as not-done. */
    private boolean stepDone(int step) {
        switch (step) {
            case STEP_AUTOSTART:     return PermissionFlow.isAutostartOk(this);
            case STEP_NOTIFICATIONS: return PermissionFlow.isNotificationsOk(this);
            case STEP_BATTERY:       return PermissionFlow.isBatteryExempt(this);
            case STEP_DEVICE_OWNER:  return Engine.isDeviceOwner(this);
            default:                 return false;
        }
    }

    /** The first step that is not yet approved, or STEP_COUNT when all are done. */
    private int currentStep() {
        for (int step = 0; step < STEP_COUNT; step++) {
            if (!stepDone(step)) return step;
        }
        return STEP_COUNT;
    }

    private boolean allApproved() {
        return currentStep() == STEP_COUNT;
    }

    /**
     * The dynamic gate. Re-evaluates every probe, unlocks EXACTLY the first
     * unfinished step, locks the rest, and enables CONTINUE only when all four
     * steps are approved. Called from {@link #onResume()} and after every action.
     */
    private void refreshGate() {
        int current = allApproved() ? STEP_COUNT : currentStep();

        // ---- one-line progress read-out for the whole ladder ----
        int doneCount = 0;
        for (int s = 0; s < STEP_COUNT; s++) {
            if (stepDone(s)) doneCount++;
        }
        if (progressLabel != null) {
            progressLabel.setText(getString(R.string.gk_progress, doneCount, STEP_COUNT));
        }

        for (int step = 0; step < STEP_COUNT; step++) {
            if (stepButtons[step] == null) continue;
            boolean done = stepDone(step);
            boolean isCurrent = !done && step == current;

            // ---- status badge: green check (done) / amber warning (needed) / pending ----
            TextView icon = stepIcons[step];
            if (icon != null) {
                if (done) {
                    icon.setText("\u2713");
                    icon.setTextColor(ui.onOk);
                    icon.setBackground(ui.oval(ui.ok));
                } else if (isCurrent) {
                    icon.setText("\u26A0\uFE0E");
                    icon.setTextColor(ui.onWarn);
                    icon.setBackground(ui.oval(ui.warn));
                } else {
                    icon.setText("\u2013");
                    icon.setTextColor(ui.textFaint);
                    icon.setBackground(ui.oval(ui.surfaceAlt, ui.border, 1f));
                }
            }

            // ---- short, plain-language state word ----
            TextView state = stepStates[step];
            if (state != null) {
                if (done) {
                    state.setText(R.string.gk_state_done);
                    state.setTextColor(ui.ok);
                } else if (isCurrent) {
                    state.setText(R.string.gk_state_current);
                    state.setTextColor(ui.warn);
                } else {
                    state.setText(R.string.gk_state_locked);
                    state.setTextColor(ui.textFaint);
                }
            }

            // ---- card tint: green when done, accent ring around the current step ----
            if (stepRows[step] != null) {
                if (done) {
                    stepRows[step].setBackground(ui.rounded(ui.okSoft, 16, ui.ok, 1f));
                } else if (isCurrent) {
                    stepRows[step].setBackground(ui.rounded(ui.accentSoft, 16, ui.accent, 2f));
                } else {
                    stepRows[step].setBackground(ui.rounded(ui.surfaceAlt, 16, ui.border, 1f));
                }
                stepRows[step].setAlpha(done || isCurrent ? 1f : 0.6f);
            }

            // ---- the button: short, action-first label for the active step ----
            Button b = stepButtons[step];
            boolean working = (step == STEP_DEVICE_OWNER) && provisioning.get();
            if (working) {
                b.setText(R.string.gk_working);
                b.setEnabled(false);
                b.setBackground(ui.rounded(ui.accent, 14));
                b.setAlpha(1f);
                continue;
            }
            if (done) {
                b.setText(R.string.gk_btn_approved);
                b.setEnabled(false);
                b.setBackground(ui.rounded(ui.ok, 14));
                b.setAlpha(1f);
            } else if (isCurrent) {
                b.setText(STEP_ACTIONS[step]);
                b.setEnabled(true);
                b.setBackground(ui.rounded(ui.accent, 14));
                b.setAlpha(1f);
            } else {
                b.setText(R.string.gk_btn_locked);
                b.setEnabled(false);
                b.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1.5f));
                b.setAlpha(0.6f);
            }
        }

        boolean ready = allApproved();
        if (continueButton != null) {
            continueButton.setEnabled(ready);
            continueButton.setAlpha(ready ? 1f : 0.5f);
        }
        if (continueHint != null) {
            continueHint.setVisibility(ready ? View.GONE : View.VISIBLE);
        }

        // Reflect Device-Owner provisioning progress in the status line.
        if (provisionStatus != null) {
            provisionStatus.setVisibility(
                    provisioning.get() ? View.VISIBLE : provisionStatus.getVisibility());
        }
    }

    // ==================================================================== actions

    /** Runs the single action behind one step's "Approve" tap. */
    private void act(int step) {
        switch (step) {
            case STEP_AUTOSTART:
                // Record the return so the unreadable vendor toggle can be attested.
                pendingAutostart = true;
                PermissionFlow.approve(this, PermissionFlow.STEP_AUTOSTART);
                break;
            case STEP_NOTIFICATIONS:
                PermissionFlow.approve(this, PermissionFlow.STEP_NOTIFICATION);
                break;
            case STEP_BATTERY:
                PermissionFlow.approve(this, PermissionFlow.STEP_BATTERY);
                break;
            case STEP_DEVICE_OWNER:
                actDeviceOwner();
                break;
            default:
                break;
        }
    }

    /**
     * Device Owner step. Grants ownership SILENTLY through the Shizuku binder —
     * no system Settings screen is opened, nothing is shown to the user but a
     * short status line.
     */
    private void actDeviceOwner() {
        if (Engine.isDeviceOwner(this)) {
            refreshGate();
            return;
        }
        if (!ShizukuBridge.isManagerInstalled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dap_dlg_no_app_title)
                    .setMessage(R.string.dap_dlg_no_app_body)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!ShizukuBridge.isServiceAlive()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dap_dlg_not_running_title)
                    .setMessage(R.string.dap_dlg_not_running_body)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!ShizukuBridge.isPermissionGranted()) {
            toast(getString(R.string.gk_toast_allow));
            ShizukuBridge.requestPermission();
            return;
        }
        runOwnerProvision();
    }

    /**
     * Runs the full chain on the background worker: {@code pm list users} →
     * {@code pm remove-user <id>} for every non-zero user → {@code dpm
     * set-device-owner}. The result is republished to the main thread; the raw
     * shell output is deliberately NOT shown (the grant is silent).
     */
    private void runOwnerProvision() {
        if (!provisioning.compareAndSet(false, true)) {
            return;
        }
        refreshGate();
        if (provisionStatus != null) {
            provisionStatus.setText(R.string.gk_status_started);
            provisionStatus.setVisibility(View.VISIBLE);
        }

        final Context app = getApplicationContext();
        worker.execute(() -> {
            final ShizukuBridge.ProvisionReport rep = ShizukuBridge.provisionDeviceOwner(app);
            runOnUiThread(() -> {
                provisioning.set(false);
                if (isFinishing() || isDestroyed()) return;

                if (rep.success) {
                    if (provisionStatus != null) {
                        provisionStatus.setText(R.string.gk_status_owner_ok);
                        provisionStatus.setVisibility(View.VISIBLE);
                    }
                    toast(getString(R.string.dap_toast_granted));
                } else {
                    // SINGLE LINE only: any execution failure is rendered as one
                    // exception-style line (ShizukuBridge.formatExecutionError),
                    // never a multi-line block and never a bare error code.
                    final String line = (rep.failure != null)
                            ? rep.failure
                            : ShizukuBridge.formatExecutionError(
                                    getString(R.string.dap_dlg_failed_body));
                    if (provisionStatus != null) {
                        provisionStatus.setText(line);
                        provisionStatus.setVisibility(View.VISIBLE);
                    }
                    new AlertDialog.Builder(GatekeeperActivity.this)
                            .setTitle(R.string.dap_dlg_failed_title)
                            .setMessage(line)
                            .setPositiveButton("OK", null)
                            .show();
                }
                refreshGate();
            });
        });
    }

    /** The operator's single way forward, and only after all four steps pass. */
    private void onContinue() {
        if (!allApproved()) {
            refreshGate();
            toast(getString(R.string.gk_continue_hint));
            return;
        }
        handOff();
    }

    /**
     * Hands over to the existing flow, preserving it exactly: the dashboard owns
     * the rest. Defensive fallbacks keep the operator off a dead end.
     */
    private void handOff() {
        try {
            startActivity(new Intent(this, LauncherDashboard.class));
            return;
        } catch (Throwable ignored) { /* fall through */ }
        try {
            startActivity(new Intent(this, MainSetupActivity.class));
            return;
        } catch (Throwable ignored) { /* fall through */ }
        try {
            startActivity(new Intent(this, PermissionActivity.class));
        } catch (Throwable t) {
            toast("Could not open setup");
        }
    }

    // ==================================================================== widgets

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(ui.bg);
        w.setNavigationBarColor(ui.bg);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (ui.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ui.rounded(ui.surface, 18, ui.border, 1.5f));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ui.text);
        t.setTextSize(17f);
        t.setTypeface(Ui.bold());
        return t;
    }

    private Button actionButton(String label, int bg, int fg, float sizeSp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(sizeSp);
        b.setTextColor(fg);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(bg, 14));
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
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
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return ui.dp(v);
    }
}
