package com.vortex.timelock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * Sentinel TimeLock — PRE-SETUP PERMISSION ONBOARDING (the very first screen).
 *
 * <p>This Activity is shown automatically in place of the dashboard until the
 * configuration has been activated (see {@link LauncherDashboard#onCreate}). It
 * exists to remove every reason the app could later fail silently: each runtime
 * capability the enforcement engine depends on is presented as its OWN row with
 * its OWN manual "APPROVE" button, so nothing is requested or granted behind the
 * operator's back. A single blanket prompt is deliberately NOT used.
 *
 * <p>The screen is organised top-to-bottom in the order the operator must work:
 * <ol>
 *   <li><b>Pre-requisites</b> — a plain warning that the phone should be factory
 *       reset (or, at minimum, have every account removed and auto-sync turned
 *       off) and then rebooted BEFORE continuing, because Device Owner can only
 *       be provisioned on an account-free, freshly-booted device. An
 *       acknowledgement switch gates the CONTINUE button.</li>
 *   <li><b>Permissions</b> — one row per capability (notifications, exact alarms,
 *       full-screen intent, battery-optimisation exemption, OEM autostart). Each
 *       row shows its live state and has a dedicated approve button that opens the
 *       exact system surface for THAT permission.</li>
 *   <li><b>Device Admin</b> — shows the ADB command that grants Device Owner, a
 *       manual "ENABLE DEVICE ADMIN" button (the standard OS admin dialog), and
 *       "APPROVE BY SHIZUKU": one tap runs {@code pm list users}, removes every
 *       user whose id is not 0, then runs that same {@code dpm set-device-owner}
 *       command live through the Shizuku binder — no PC and no cable — and prints
 *       the raw output.</li>
 *   <li><b>Learn</b> — shortcuts that explain, in plain language, exactly what
 *       this app is and how the whole flow works, so the operator is never asked
 *       to approve something they do not understand.</li>
 *   <li><b>Continue</b> — hands over to {@link SetupActivity} once the warning is
 *       acknowledged.</li>
 * </ol>
 *
 * <p>All colour and geometry come from the shared {@link Ui} design tokens so the
 * screen follows the system light/dark theme exactly like the rest of the app.
 */
public class PermissionActivity extends Activity {

    static final String TAG = "TL.Permission";

    /** Runtime request code for POST_NOTIFICATIONS (API 33+). */
    private static final int REQ_NOTIF = 9101;

    private Ui ui;
    private boolean resumed = false;

    /**
     * Guards the one-shot automatic hand-off to {@link MainSetupActivity} so a
     * verified operator is advanced exactly once per visit, never in a loop.
     */
    private boolean autoAdvanced = false;

    // ---- views we must refresh live ----
    private Switch ackSwitch;
    private TextView notifState, alarmState, fsiState, battState, autoState;
    private TextView adminState, shizukuState, shizukuOutput;
    private Button shizukuBtn;

    // ---- Shizuku callbacks (registered only while on screen) ----
    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == ShizukuBridge.REQ_PERMISSION) {
                        runOnUiThread(() -> refreshShizuku());
                    }
                }
            };

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    runOnUiThread(() -> refreshShizuku());
                }
            };

    // ==================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this).bind(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Attach to Shizuku only while visible, and never let a listener leak.
        ShizukuBridge.addPermissionListener(permListener);
        ShizukuBridge.addBinderListener(binderListener);
        refreshAll();
        // Self lock-down re-assert on every visit: once we hold Device Owner, make
        // sure the app's own controls stay disabled (permissions pinned, user
        // control off) even if the operator returned from a Settings detour.
        Engine.lockDownSelf(this);
        // AUTO-TRANSITION: if every prerequisite from the previous steps (System
        // Permissions + the Shizuku / Device Admin grant) is already verified,
        // move straight to the Main Setup Screen without another tap. When they
        // are not yet all active this is a harmless no-op and the operator keeps
        // working through the rows on this screen.
        maybeAutoAdvance();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ShizukuBridge.removePermissionListener(permListener);
        ShizukuBridge.removeBinderListener(binderListener);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui = new Ui(this).bind(this);
        buildUi();
        refreshAll();
    }

    // ==================================================================== UI shell

    private void buildUi() {
        applySystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(48));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader());
        root.addView(buildPrereqCard());
        root.addView(buildPermissionsCard());
        root.addView(buildAdminCard());
        root.addView(buildLearnCard());
        root.addView(buildContinueCard());

        setContentView(scroll);
    }

    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);

        TextView kicker = new TextView(this);
        kicker.setText("BEFORE SETUP");
        kicker.setTextColor(ui.accent);
        kicker.setTextSize(12.5f);
        kicker.setTypeface(Ui.bold());
        kicker.setLetterSpacing(0.18f);
        head.addView(kicker);

        TextView title = new TextView(this);
        title.setText("Grant each permission");
        title.setTextColor(ui.text);
        title.setTextSize(26f);
        title.setTypeface(Ui.bold());
        title.setPadding(0, dp(6), 0, 0);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Approve every item below one at a time. Nothing is requested "
                + "automatically — you tap the button for each permission yourself, "
                + "so you always know exactly what is being granted.");
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

    // ------------------------------------------------------------ pre-requisites

    private View buildPrereqCard() {
        LinearLayout c = card();

        TextView title = cardTitle("1 \u00b7 Read this first");
        c.addView(title);

        TextView warn = new TextView(this);
        warn.setText("Device Owner can only be granted on a device with NO accounts "
                + "and NO secondary users.");
        warn.setTextColor(ui.dangerText);
        warn.setTextSize(15f);
        warn.setTypeface(Ui.bold());
        warn.setLineSpacing(dp(3), 1f);
        warn.setPadding(0, dp(10), 0, dp(6));
        c.addView(warn);

        c.addView(body("Before you continue, do the following on this phone:"));

        c.addView(bullet("Factory-reset the phone (the cleanest option), or at the very "
                + "least remove every account and every added user from "
                + "Settings \u2192 Passwords & accounts / Users."));
        c.addView(bullet("Turn OFF auto-sync for all accounts "
                + "(Settings \u2192 Passwords & accounts \u2192 auto-sync data)."));
        c.addView(bullet("Reboot the phone and come straight back to this screen — "
                + "do not sign into any account after the reboot."));

        c.addView(note("Why: while a Google / work / any account is present, or a second "
                + "user profile exists, the system refuses dpm set-device-owner with "
                + "\"Not allowed to set the device owner because there are already some "
                + "accounts on the device\". A fresh reboot clears any leftover session "
                + "so the owner grant succeeds on the first attempt."));

        ackSwitch = new Switch(this);
        ackSwitch.setText("I have done the above (or accept the risk)");
        ackSwitch.setTextColor(ui.text);
        ackSwitch.setTextSize(14.5f);
        ackSwitch.setTypeface(Ui.medium());
        ackSwitch.setPadding(0, dp(16), 0, 0);
        ackSwitch.setChecked(Prefs.prereqAck(this));
        ackSwitch.setOnCheckedChangeListener((b, checked) -> Prefs.setPrereqAck(this, checked));
        c.addView(ackSwitch);

        return c;
    }

    /** A single "\u2022 text" paragraph with a hanging bullet. */
    private TextView bullet(String s) {
        TextView t = new TextView(this);
        t.setText("\u2022  " + s);
        t.setTextColor(ui.textDim);
        t.setTextSize(14f);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    // ------------------------------------------------------------ permissions

    private View buildPermissionsCard() {
        LinearLayout c = card();
        c.addView(cardTitle("2 \u00b7 Permissions"));

        notifState = permRow(c,
                "Notifications",
                "Lets the app show the lock reminder and the live remaining-time "
                        + "notification. Without it the lock screen still works, but "
                        + "you lose the countdown and warnings.",
                "APPROVE NOTIFICATIONS", v -> actNotifications());

        alarmState = permRow(c,
                "Exact alarms",
                "Required so the lock engages exactly at the chosen time instead of "
                        + "being delayed by battery-saving.",
                "APPROVE EXACT ALARMS", v -> actExactAlarms());

        fsiState = permRow(c,
                "Full-screen intent",
                "On Android 14+ this lets the lock screen take over the display when "
                        + "the limit is reached.",
                "APPROVE FULL-SCREEN", v -> actFullScreenIntent());

        battState = permRow(c,
                "Battery optimisation",
                "Exempts the app from doze so the background enforcement service is "
                        + "never frozen while the lock is due.",
                "APPROVE BATTERY", v -> actBattery());

        autoState = permRow(c,
                "Autostart (OEM)",
                "Some manufacturers keep apps from restarting after a reboot. Open your "
                        + "phone's autostart / battery settings and allow this app. "
                        + "There is no reliable way for an app to read or set this, so "
                        + "this one is opened for you manually.",
                "OPEN AUTOSTART", v -> actAutostart());

        return c;
    }

    /**
     * One permission row: title + description + live state on the left, and a
     * dedicated manual approve button on the right. Returns the state TextView so
     * the caller can refresh it.
     */
    private TextView permRow(LinearLayout parent, String title, String desc,
                             String buttonLabel, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1f));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(15f);
        t.setTypeface(Ui.bold());
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(t);

        TextView state = new TextView(this);
        state.setText("\u2014");
        state.setTextSize(11.5f);
        state.setTypeface(Ui.bold());
        state.setTextColor(ui.textFaint);
        state.setLetterSpacing(0.08f);
        top.addView(state);
        row.addView(top);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(ui.textFaint);
        d.setTextSize(12.5f);
        d.setLineSpacing(dp(3), 1f);
        d.setPadding(0, dp(6), 0, 0);
        row.addView(d);

        Button b = actionButton(buttonLabel, ui.accent, ui.onAccent, 13f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        b.setLayoutParams(blp);
        b.setOnClickListener(onClick);
        row.addView(b);

        parent.addView(row);
        return state;
    }

    // ------------------------------------------------------------ device admin

    private View buildAdminCard() {
        LinearLayout c = card();
        c.addView(cardTitle("3 \u00b7 Device Admin / Device Owner"));

        c.addView(body("This app needs Device Owner rights to enforce the lock. You can "
                + "grant it two ways — pick whichever suits you. Both run the SAME "
                + "command."));

        // The exact command, shown verbatim.
        TextView cmdLabel = new TextView(this);
        cmdLabel.setText("ADB COMMAND");
        cmdLabel.setTextColor(ui.textFaint);
        cmdLabel.setTextSize(11.5f);
        cmdLabel.setTypeface(Ui.bold());
        cmdLabel.setLetterSpacing(0.12f);
        cmdLabel.setPadding(0, dp(14), 0, dp(6));
        c.addView(cmdLabel);

        c.addView(monoBlock("adb shell " + ShizukuBridge.deviceOwnerCommand(this)));

        c.addView(note("On a computer with the phone plugged in and USB debugging on, run "
                + "the line above. Device Owner must be set while no account or second "
                + "user exists (see step 1)."));

        // Shizuku approval.
        c.addView(divider());

        LinearLayout shRow = new LinearLayout(this);
        shRow.setOrientation(LinearLayout.HORIZONTAL);
        shRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView shTitle = new TextView(this);
        shTitle.setText("Approve by Shizuku");
        shTitle.setTextColor(ui.text);
        shTitle.setTextSize(15f);
        shTitle.setTypeface(Ui.bold());
        shTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        shRow.addView(shTitle);
        shizukuState = new TextView(this);
        shizukuState.setText("\u2014");
        shizukuState.setTextSize(11.5f);
        shizukuState.setTypeface(Ui.bold());
        shizukuState.setTextColor(ui.textFaint);
        shizukuState.setLetterSpacing(0.08f);
        shRow.addView(shizukuState);
        c.addView(shRow);

        c.addView(note("With Shizuku running you can do it entirely on the phone: tapping "
                + "the button below runs  pm list users , removes every user whose id is "
                + "not 0, then runs the dpm set-device-owner command — no PC, no cable."));

        shizukuBtn = actionButton("APPROVE BY SHIZUKU", ui.accent, ui.onAccent, 14f);
        shizukuBtn.setOnClickListener(v -> actShizukuApprove());
        c.addView(shizukuBtn);

        shizukuOutput = new TextView(this);
        shizukuOutput.setTextColor(ui.textDim);
        shizukuOutput.setTextSize(12f);
        shizukuOutput.setTypeface(Ui.mono());
        shizukuOutput.setLineSpacing(dp(3), 1f);
        shizukuOutput.setBackground(ui.rounded(ui.field, 12, ui.border, 1f));
        shizukuOutput.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(12);
        shizukuOutput.setLayoutParams(olp);
        shizukuOutput.setVisibility(View.GONE);
        c.addView(shizukuOutput);

        // Live Device Admin status (read-only). There is deliberately NO button
        // here that opens the system Device Admin screen: the grant above is the
        // only path, and it is force-provisioned through Shizuku.
        c.addView(divider());

        LinearLayout adRow = new LinearLayout(this);
        adRow.setOrientation(LinearLayout.HORIZONTAL);
        adRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView adTitle = new TextView(this);
        adTitle.setText("Device Admin status");
        adTitle.setTextColor(ui.text);
        adTitle.setTextSize(15f);
        adTitle.setTypeface(Ui.bold());
        adTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        adRow.addView(adTitle);
        adminState = new TextView(this);
        adminState.setText("\u2014");
        adminState.setTextSize(11.5f);
        adminState.setTypeface(Ui.bold());
        adminState.setTextColor(ui.textFaint);
        adminState.setLetterSpacing(0.08f);
        adRow.addView(adminState);
        c.addView(adRow);

        c.addView(note("Granted entirely through Shizuku \u2014 the app never opens the "
                + "system Device Admin screen. Use the APPROVE BY SHIZUKU button above, "
                + "or the ADB command if no Shizuku is available."));

        return c;
    }

    // ------------------------------------------------------------ learn

    private View buildLearnCard() {
        LinearLayout c = card();
        c.addView(cardTitle("4 \u00b7 Learn how this works"));

        c.addView(body("Not sure what you are approving? Read these first — each one "
                + "explains a part of the app in plain language."));

        c.addView(shortcut("What is this app, exactly?",
                "A screen-time lock that enforces a daily limit on how long the screen "
                        + "can be actively used.\n\n"
                        + "\u2022 You choose a daily allowance (for example 2 hours) and "
                        + "when the day's counter resets.\n"
                        + "\u2022 The app counts time while the screen is actually on.\n"
                        + "\u2022 When the allowance is used up, it shows a lock screen "
                        + "and blocks you from getting back in until the next window."));

        c.addView(shortcut("How the lock actually works",
                "Once Device Owner is granted the app can, on its own:\n\n"
                        + "\u2022 take over the screen with a lock when the limit is hit;\n"
                        + "\u2022 hide the launcher icon and freeze other launchers so the "
                        + "lock screen cannot be bypassed;\n"
                        + "\u2022 re-apply the policy automatically after a reboot.\n\n"
                        + "This is why the permissions above matter: each one lets a "
                        + "piece of that enforcement work reliably."));

        c.addView(shortcut("The full setup playbook",
                "1.  Read step 1 and do the factory-reset / remove-accounts / no-sync / "
                        + "reboot routine.\n"
                        + "2.  Approve each permission in step 2, one at a time.\n"
                        + "3.  Grant Device Admin in step 3 — easiest via Shizuku, or with "
                        + "the ADB command.\n"
                        + "4.  Tap CONTINUE and choose your daily limit and reset time.\n"
                        + "5.  Complete setup. That locks the configuration in."));

        c.addView(shortcut("How to get back in afterwards",
                "After setup is locked, the settings are reachable only on purpose: "
                        + "dial  *#*#84635#*#*  from the phone app and pass the biometric "
                        + "check. There is deliberately no PIN and no emergency release — "
                        + "the policy is the way out."));

        return c;
    }

    private Button shortcut(String title, String dialogBody) {
        Button b = ghostButton(title);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(dialogBody)
                .setPositiveButton("Got it", null)
                .show());
        return b;
    }

    // ------------------------------------------------------------ continue

    private View buildContinueCard() {
        LinearLayout c = card();
        c.addView(cardTitle("5 \u00b7 Continue"));

        TextView s = new TextView(this);
        s.setText("When the permissions are approved, continue to choose your focus limit "
                + "and finish setup.");
        s.setTextColor(ui.textDim);
        s.setTextSize(14f);
        s.setLineSpacing(dp(4), 1f);
        s.setPadding(0, dp(8), 0, 0);
        c.addView(s);

        Button go = actionButton("CONTINUE TO SETUP", ui.ok, ui.onAccent, 16f);
        go.setOnClickListener(v -> actContinue());
        c.addView(go);

        return c;
    }

    // ==================================================================== actions

    private void actNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        // Already granted, or pre-33 where it is granted at install: send them to the
        // per-app notification settings so they can confirm / re-enable.
        openAppNotificationSettings();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            refreshPermissions();
        }
    }

    private void actExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable ignored) { /* fall through to app details */ }
        }
        openAppDetails();
    }

    private void actFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable ignored) { /* fall through */ }
        }
        openAppNotificationSettings();
    }

    private void actBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) { /* fall through */ }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            openAppDetails();
        }
    }

    private void actAutostart() {
        // OEM autostart has no standard check or setter: open the app's details page,
        // which every OEM keeps and where their autostart toggle lives nearby.
        openAppDetails();
    }

    private void actShizukuApprove() {
        if (!ShizukuBridge.isManagerInstalled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku is not installed")
                    .setMessage("This one-tap method needs the Shizuku app.\n\n"
                            + "Install Shizuku (moe.shizuku.privileged.api), start it, "
                            + "grant it permission, then come back and tap APPROVE BY "
                            + "SHIZUKU again.\n\n"
                            + "Alternatively use the ADB command shown above from a "
                            + "computer.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!ShizukuBridge.isServiceAlive()) {
            new AlertDialog.Builder(this)
                    .setTitle("Start Shizuku first")
                    .setMessage("Shizuku is installed but its service is not running.\n\n"
                            + "Open the Shizuku app and start the service (via wireless "
                            + "debugging or ADB), then come back and tap APPROVE BY "
                            + "SHIZUKU again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!ShizukuBridge.isPermissionGranted()) {
            toast("Allow this app in the Shizuku dialog\u2026");
            ShizukuBridge.requestPermission();
            return;
        }
        runProvision();
    }

    /** Run the full pm list users / remove-user / set-device-owner chain off the UI thread. */
    private void runProvision() {
        shizukuBtn.setEnabled(false);
        shizukuBtn.setText("WORKING\u2026");
        showOutput("Running pm list users \u2026");

        final Context app = getApplicationContext();
        new Thread(() -> {
            final ShizukuBridge.ProvisionReport rep = ShizukuBridge.provisionDeviceOwner(app);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                shizukuBtn.setEnabled(true);
                shizukuBtn.setText("APPROVE BY SHIZUKU");

                StringBuilder sb = new StringBuilder();
                sb.append("$ pm list users\n").append(rep.usersOutput).append('\n');
                sb.append("\n").append(rep.removeOutput).append('\n');
                sb.append("\n$ ").append(rep.adbCommand).append('\n').append(rep.ownerOutput);
                showOutput(sb.toString());

                refreshAdmin();
                refreshShizuku();

                if (rep.success) {
                    // INSTANT self lock-down: the moment ownership lands, make
                    // THIS app unmodifiable by the user -- every declared
                    // permission pinned to a fixed, non-user-manageable GRANTED
                    // state and user control of our own package disabled
                    // (no force-stop / clear-data / uninstall). This is the
                    // "app control off for the device-owner app" guarantee, and
                    // it is applied here so it does not wait for the full setup
                    // to finish.
                    Engine.lockDownSelf(app);
                    // Success is asserted ONLY when the shell returned exit code 0
                    // for dpm set-device-owner. Toast, then move straight to the
                    // setup screen. No system Settings screen is involved.
                    toast("Device Owner granted \u2014 app locked, continuing to setup\u2026");
                    openSetup();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Could not set Device Owner")
                            .setMessage((rep.failure != null ? rep.failure + "\n\n" : "")
                                    + "Make sure the phone was factory reset (or has no "
                                    + "accounts and no second user) and was rebooted, then "
                                    + "try again — or use the ADB command above.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }, "shizuku-provision").start();
    }

    private void actContinue() {
        if (!Prefs.prereqAck(this)) {
            toast("Please tick the box in step 1 first");
            return;
        }
        // Locked-in install: pin the app's own controls before leaving this page.
        if (Engine.isDeviceOwner(this)) Engine.lockDownSelf(this);
        if (!Engine.isDeviceOwner(this) && !Engine.isAdminActive(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("No Device Admin yet")
                    .setMessage("You have not granted Device Admin. Without it the lock "
                            + "can be removed before your limit is finished.\n\n"
                            + "Continue to setup anyway?")
                    .setPositiveButton("Continue anyway", (d, w) -> openSetup())
                    .setNegativeButton("Go back", null)
                    .show();
            return;
        }
        openSetup();
    }

    /**
     * Automatically transitions to {@link MainSetupActivity} once the permission
     * steps are verified as active. Fired from {@link #onResume()} so it also
     * catches the operator returning from a system prompt or the Shizuku grant
     * dialog; a one-shot flag ensures it never re-opens on top of itself.
     */
    private void maybeAutoAdvance() {
        if (autoAdvanced) return;
        if (!TransitionGate.allVerified(this)) return;
        autoAdvanced = true;
        openSetup();
    }

    /**
     * Hands over to the Main Setup Screen (transition + main hub). If that screen
     * is somehow unavailable, falls back to the configuration console directly so
     * the operator is never left on a dead end.
     */
    private void openSetup() {
        try {
            startActivity(new Intent(this, MainSetupActivity.class));
            return;
        } catch (Throwable ignored) { /* fall through to the console */ }
        try {
            startActivity(new Intent(this, SetupActivity.class));
        } catch (Throwable t) {
            toast("Could not open setup");
        }
    }

    // ==================================================================== status

    private void refreshAll() {
        refreshPermissions();
        refreshAdmin();
        refreshShizuku();
    }

    private void refreshPermissions() {
        setState(notifState, isNotificationsOk());
        setState(alarmState, isExactAlarmsOk());
        setState(fsiState, isFullScreenOk());
        setState(battState, isBatteryExempt());
        // Autostart: nothing can be read; show it as a manual step rather than lie.
        if (autoState != null) {
            autoState.setText("MANUAL");
            autoState.setTextColor(ui.warn);
        }
    }

    private void refreshAdmin() {
        if (adminState == null) return;
        boolean owner = Engine.isDeviceOwner(this);
        boolean admin = Engine.isAdminActive(this);
        if (owner) {
            adminState.setText("DEVICE OWNER");
            adminState.setTextColor(ui.ok);
        } else if (admin) {
            adminState.setText("ADMIN ONLY");
            adminState.setTextColor(ui.warn);
        } else {
            adminState.setText("NOT GRANTED");
            adminState.setTextColor(ui.dangerText);
        }
    }

    private void refreshShizuku() {
        if (shizukuState == null) return;
        String label;
        int colour;
        if (!ShizukuBridge.isManagerInstalled(this)) {
            label = "NOT INSTALLED"; colour = ui.textFaint;
        } else if (!ShizukuBridge.isServiceAlive()) {
            label = "NOT RUNNING"; colour = ui.warn;
        } else if (!ShizukuBridge.isPermissionGranted()) {
            label = "PERMISSION?"; colour = ui.warn;
        } else {
            label = "READY"; colour = ui.ok;
        }
        shizukuState.setText(label);
        shizukuState.setTextColor(colour);
    }

    private void setState(TextView tv, boolean ok) {
        if (tv == null) return;
        tv.setText(ok ? "GRANTED" : "NEEDED");
        tv.setTextColor(ok ? ui.ok : ui.dangerText);
    }

    // ---- live probes (all defensive: any failure reads as "not granted") ----

    private boolean isNotificationsOk() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isExactAlarmsOk() {
        try {
            if (Build.VERSION.SDK_INT < 31) return true;
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isFullScreenOk() {
        try {
            if (Build.VERSION.SDK_INT < 34) return true;   // granted at install below 34
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isBatteryExempt() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================== settings launchers

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("Open Settings \u2192 Apps \u2192 this app manually");
        }
    }

    private void openAppNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) {
            openAppDetails();
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

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ui.textDim);
        t.setTextSize(14f);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ui.textFaint);
        t.setTextSize(12.5f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    /** A monospaced, selectable code block used for the ADB command. */
    private TextView monoBlock(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ui.text);
        t.setTextSize(13f);
        t.setTypeface(Ui.mono());
        t.setTextIsSelectable(true);
        t.setBackground(ui.rounded(ui.field, 12, ui.border, 1f));
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private Buffer showOutput(String s) {
        if (shizukuOutput == null) return null;
        shizukuOutput.setText(s);
        shizukuOutput.setVisibility(View.VISIBLE);
        return null;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
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

    private Button ghostButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(ui.text);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1.5f));
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

    /** Unused-but-harmless marker type referenced nowhere; kept out. */
    private interface Buffer {}
}
