package com.vortex.timelock;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Sentinel TimeLock — admin console / setup.
 *
 * Everything is chosen by the USER: the daily restriction interval, the time of
 * day the counter resets, how the lock behaves, and which extra protections are
 * enabled. Nothing is preset.
 *
 * Reached from the launcher while the app is visible, or via the secret dial
 * code  *#*#84635#*#*  once the icon has been hidden.
 *
 * This revision is an interface modernization sweep:
 *   - the previous dense, single-tone layout is replaced with a calmer card
 *     rhythm, generous spacing and a clear type scale;
 *   - the palette is now theme-aware (balanced dark AND light) and follows the
 *     system setting live;
 *   - the daily limit and the lock-screen warning are chosen with an adjustable
 *     {@link IntervalSelector} (slider + steppers + exact manual entry);
 *   - every control keeps its previous behaviour, storage keys and side effects.
 */
public class SetupActivity extends Activity implements HardwareTick.Sink {

    static final String TAG = "TL.Setup";

    private Ui ui;

    private boolean verified = false;

    // ---- form fields ----
    private IntervalSelector limitSel, warnSel;
    private EditText ahIn, amIn;
    /** True when the DEVICE clock preference is 24-hour; the reset picker follows it. */
    private boolean use24 = true;
    /** Meridiem for the reset hour when {@link #use24} is false (12-hour devices). */
    private boolean pm = false;
    private Button amBtn, pmBtn;
    private TextView limitPreview, resetPreview, warnPreview;
    private WeekdayScheduleCard scheduleCard;
    private TextView schedulePreview;

    // ---- identity gate ----
    private LinearLayout gateBox, formBox;
    private TextView gateState;         // live biometric status line
    private boolean biometricReady = false;
    private boolean gateAutoPrompt = false;

    // ---- live status ----
    private TextView statusBadge;
    private TextView tvAccess, tvBattery, tvLimit, tvReset, tvWarn, tvGate, tvUsed, tvState;
    private TextView tvSchedule;

    // ==================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this).bind(this);
        // Repair stale kiosk/launcher state the moment the user opens the app --
        // BEFORE any configuration. selfHeal is idempotent and can only ever
        // DISABLE our own component here.
        Engine.selfHeal(this);
        buildUi();
        if (Prefs.activated(this) && !verified) showGate();
        else showForm();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Prime the read-out from the same authoritative instant the tick uses,
        // then join the process's SINGLE 1 Hz cadence. subscribe() also renders
        // immediately, so the console is never blank and never stale.
        refreshStatus(HardwareTick.lastTickMs());
        HardwareTick.subscribe(this);
    }

    @Override
    protected void onPause() {
        HardwareTick.unsubscribe(this);
        super.onPause();
    }

    /**
     * ONE-SHOT CONCEALMENT. The secret dial code re-enables this component for a
     * single admin visit (see {@link SecretCodeReceiver}); the moment the console
     * leaves the screen it is disabled again whenever a full owner-confirmed
     * setup is in force, so it never lingers as a launchable surface the way the
     * launcher freeze alone used to leave it.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (Prefs.activated(this) && Engine.isDeviceOwner(this)) {
            Engine.setConsoleEnabled(this, false);
        }
    }

    /**
     * The manifest keeps this Activity alive across uiMode changes, so we must
     * re-theme ourselves here rather than rely on a recreate.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Capture in-flight edits as a 24-hour minute-of-day so the anchor survives a
        // 12/24-hour flip unchanged: the picker is rebuilt below in the new clock form.
        int lim = limitSel != null ? limitSel.getValue() : 0;
        int warn = warnSel != null ? warnSel.getValue() : Prefs.warnSeconds(this);
        int anchorNow = resetMinuteOfDay();

        ui = new Ui(this).bind(this);
        buildUi();
        if (Prefs.activated(this) && !verified) showGate();
        else showForm();

        if (limitSel != null) limitSel.setValue(lim);
        if (warnSel != null) warnSel.setValue(warn);
        setResetFields(anchorNow);
        updatePreview();
    }

    /**
     * Live only while this console is actually on screen and built.
     *
     * Deliberately cheap: no binder calls, no {@code System.currentTimeMillis()},
     * just view-tree state. The expensive refresh stays in {@link #onTick(long)}.
     */
    @Override public boolean active() {
        return !isFinishing() && !isDestroyed() && statusBadge != null;
    }

    /**
     * One tick, from the SHARED authoritative instant.
     *
     * Replaces the private {@code Handler}/{@code Runnable live} self-rescheduling
     * pair, whose re-arm in its own tail made the period 1000 ms PLUS the cost of
     * the whole refresh (every binder round-trip above the drift), and whose
     * position as the last statement meant a throwable left this console frozen
     * until the next lifecycle event. Cadence and anti-freeze ordering now live in
     * {@link HardwareTick}.
     */
    @Override public void onTick(long nowMs) {
        refreshStatus(nowMs);
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
        root.addView(buildHero());

        // ---- identity gate (hidden until needed) ----
        gateBox = new LinearLayout(this);
        gateBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gateBox);

        // ---- main form ----
        formBox = new LinearLayout(this);
        formBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(formBox);

        buildGate(gateBox);
        buildForm(formBox);
        setContentView(scroll);
    }

    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);

        LinearLayout kickerRow = new LinearLayout(this);
        kickerRow.setOrientation(LinearLayout.HORIZONTAL);
        kickerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView kicker = new TextView(this);
        kicker.setText("SENTINEL TIMELOCK");
        kicker.setTextColor(ui.accent);
        kicker.setTextSize(12f);
        kicker.setLetterSpacing(0.18f);
        kicker.setTypeface(Ui.bold());
        kicker.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        kickerRow.addView(kicker);

        TextView themeBadge = new TextView(this);
        themeBadge.setText(ui.dark ? "DARK" : "LIGHT");
        themeBadge.setTextColor(ui.dark ? ui.textDim : ui.accent);
        themeBadge.setTextSize(10.5f);
        themeBadge.setLetterSpacing(0.12f);
        themeBadge.setTypeface(Ui.bold());
        themeBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        themeBadge.setBackground(ui.rounded(ui.dark ? ui.surfaceAlt : ui.accentSoft, 20,
                ui.dark ? ui.border : 0, 1f));
        kickerRow.addView(themeBadge);
        head.addView(kickerRow);

        TextView title = new TextView(this);
        title.setText("Screen time, on your terms");
        title.setTextColor(ui.text);
        title.setTextSize(26f);
        title.setTypeface(Ui.bold());
        title.setPadding(0, dp(8), 0, 0);
        title.setLineSpacing(dp(2), 1f);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Choose your own daily interval, fine-tune how the lock behaves, then hide the "
                + "app completely. Nothing here is preset \u2014 it is all your choice.");
        sub.setTextColor(ui.textDim);
        sub.setTextSize(14.5f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(8), 0, dp(20));
        head.addView(sub);

        return head;
    }

    private View buildHero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(ui.surface, 20, ui.border, 1.5f));
        card.setPadding(dp(18), dp(18), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(18);
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("LIVE STATUS");
        label.setTextColor(ui.accent);
        label.setTextSize(11.5f);
        label.setLetterSpacing(0.16f);
        label.setTypeface(Ui.bold());
        card.addView(label);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(16.5f);
        statusBadge.setTypeface(Ui.bold());
        statusBadge.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        statusBadge.setLayoutParams(blp);
        card.addView(statusBadge);

        card.addView(divider());

        tvAccess  = valueRow(card, "Access level");
        tvBattery = valueRow(card, "Battery optimisation");
        tvLimit   = valueRow(card, "Daily interval");
        tvReset   = valueRow(card, "Resets at");
        tvWarn    = valueRow(card, "Screen-off warning");
        tvGate    = valueRow(card, "Unlock gate");
        tvUsed    = valueRow(card, "Used today");
        tvSchedule = valueRow(card, "Weekly schedule");
        tvState   = valueRow(card, "State");
        return card;
    }

    // ============================================================== identity gate

    /**
     * Identity gate for the setup flow. The console is unlocked ONLY with the
     * platform biometric stack: a genuine enrolled-fingerprint match authorises the
     * session outright (verified = true) with nothing to type and no secret stored.
     * There is no typed fallback of any kind -- a device with no enrolled
     * fingerprint cannot open the console.
     */
    private void buildGate(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);

        biometricReady = BiometricAuth.isAvailable(this);

        c.addView(cardTitle(biometricReady ? "Fingerprint required" : "Fingerprint unavailable"));
        c.addView(body(biometricReady
                ? "This app is already set up. Confirm your identity with your enrolled "
                  + "fingerprint \u2014 the settings open the moment the sensor recognises you."
                : "This app is already set up, but no enrolled fingerprint is available on this "
                  + "device. Enrol a fingerprint in system settings, then reopen this panel."));

        gateState = new TextView(this);
        gateState.setTextSize(13f);
        gateState.setLineSpacing(dp(4), 1f);
        gateState.setPadding(0, dp(8), 0, 0);
        gateState.setVisibility(View.GONE);
        c.addView(gateState);

        if (biometricReady) {
            Button scan = actionButton("AUTHENTICATE WITH FINGERPRINT", ui.accent, ui.onAccent, 16);
            scan.setOnClickListener(v -> startBiometric());
            c.addView(scan);

            // Auto-authorise the moment the gate is shown: no button press needed.
            gateAutoPrompt = true;
        }

        c.addView(note("Dial  *#*#84635#*#*  to reopen this panel."));
    }

    /** Launch the native biometric prompt and auto-authorise on a genuine match. */
    private void startBiometric() {
        if (!biometricReady) return;
        if (gateState != null) {
            gateState.setVisibility(View.VISIBLE);
            gateState.setText("Waiting for fingerprint\u2026");
            gateState.setTextColor(ui.textDim);
        }
        BiometricAuth.authenticate(this,
                "Unlock Sentinel TimeLock",
                "Confirm your identity to authorise access to the settings",
                new BiometricAuth.Callback() {
                    @Override
                    public void onSuccess() {
                        verified = true;
                        if (gateState != null) {
                            gateState.setText("Identity verified");
                            gateState.setTextColor(ui.ok);
                        }
                        toast("Identity verified");
                        showForm();
                    }

                    @Override
                    public void onFailure(CharSequence message, boolean recoverable) {
                        if (gateState != null) {
                            gateState.setVisibility(View.VISIBLE);
                            gateState.setText(message == null
                                    ? "Authentication failed \u2014 tap to try again"
                                    : ("Authentication failed: " + message));
                            gateState.setTextColor(ui.warn);
                        }
                    }
                });
    }

    // ==================================================================== the form

    private void buildForm(LinearLayout box) {
        seedFields();

        buildIntervalCard(box);
        buildScheduleCard(box);
        buildResetCard(box);
        buildLockBehaviourCard(box);
        buildProtectionCard(box);
        buildOwnerCard(box);
        buildFinishCard(box);

        refreshStatus();
    }

    private void buildIntervalCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("1  \u00b7  Daily restriction interval"));
        c.addView(body("How much screen-on time you allow yourself each day. Drag the slider, nudge "
                + "with + / \u2212, or type an exact figure \u2014 nothing is preset."));

        int minutes = (int) (Prefs.limitMs(this) / 60_000L);
        limitSel = new IntervalSelector(this, ui, "Allowed per day", 0, 1440, 15, "min", minutes);
        limitSel.setFormatter(v -> v <= 0 ? "Not set" : humanDuration(v));
        limitSel.setOnChanged(v -> updatePreview());
        limitSel.addPresetRow(new int[]{30, 60, 120, 180, 240, 360, 480, 720}, 4);
        c.addView(limitSel);

        limitPreview = previewLine();
        c.addView(limitPreview);
    }

    /**
     * Weekly schedule module: per-weekday overrides of the daily interval.
     * The control writes straight through {@link ScheduleConfig} into Prefs, so
     * the engine picks the new value up on its next evaluation.
     */
    private void buildScheduleCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("2  \u00b7  Weekly schedule"));
        c.addView(body("Override the daily interval on individual days. Reduce a day (a shorter "
                + "interval) or switch the lock off completely \u2014 e.g. a lighter Friday and "
                + "Saturday. Every day left on \"Daily\" follows the interval above. Your choice "
                + "entirely."));

        c.addView(divider());
        scheduleCard = new WeekdayScheduleCard(this, ui);
        scheduleCard.setOnChanged(this::updatePreview);
        c.addView(scheduleCard);

        schedulePreview = previewLine();
        c.addView(schedulePreview);

        Button resetSchedule = ghostButton("Reset every day to \"Daily\"");
        resetSchedule.setOnClickListener(v -> {
            scheduleCard.resetAll();
            updatePreview();
        });
        c.addView(resetSchedule);
    }

    private void buildResetCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        use24 = TimeFmt.is24(this);
        c.addView(cardTitle("3  \u00b7  Reset time"));
        c.addView(body(use24
                ? "The counter returns to zero at this time every day. Enter the hour on the "
                  + "24-hour clock; the preview matches your device's time format."
                : "The counter returns to zero at this time every day. Enter the hour, then "
                  + "choose AM or PM \u2014 the whole app follows your device's 12-hour clock."));

        c.addView(divider());
        timeRow(c, "Hour", ahIn, use24 ? 0 : 1, use24 ? 23 : 12);
        if (!use24) c.addView(meridiemRow());
        timeRow(c, "Minute", amIn, 0, 59);
        resetPreview = previewLine();
        c.addView(resetPreview);
        ahIn.addTextChangedListener(watcher());
        amIn.addTextChangedListener(watcher());
    }

    private void buildLockBehaviourCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("4  \u00b7  Lock screen behaviour"));
        c.addView(body("How the kiosk behaves when your interval runs out. It always shows the time "
                + "left and the exact unlock time, and it has no exit."));

        c.addView(divider());
        warnSel = new IntervalSelector(this, ui, "Warning before the screen sleeps",
                1, 120, 1, "s", Prefs.warnSeconds(this));
        warnSel.setFormatter(v -> v + "s");
        warnSel.setOnChanged(v -> updatePreview());
        c.addView(warnSel);

        warnPreview = previewLine();
        c.addView(warnPreview);

        c.addView(divider());
        toggleRow(c, "Turn the screen off while locked",
                "Shows a countdown warning, then the panel sleeps gracefully. Wakes straight back "
                        + "into the lock.",
                "o_screenoff", Prefs.optScreenOff(this));
        toggleRow(c, "Disable the keyguard while locked",
                "Prevents the system PIN/pattern from covering the kiosk.",
                "o_keyguard", Prefs.optKeyguard(this));
    }

    private void buildProtectionCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("5  \u00b7  Extra protection"));
        c.addView(body("Optional device-owner restrictions. These only apply while the app is the "
                + "Device Owner. Leave them off if you are unsure."));

        c.addView(divider());
        dangerousToggleRow(c, "Block Safe Mode",
                "Privileged root-level restriction. Requires an explicit approval chain.",
                "o_safeboot", Prefs.optSafeBoot(this));
        dangerousToggleRow(c, "Block factory reset",
                "Privileged root-level restriction. Requires an explicit approval chain.",
                "o_factory", Prefs.optFactoryReset(this));
        toggleRow(c, "Block USB debugging", null,
                "o_nodebug", Prefs.optDisableDebugging(this));
        toggleRow(c, "Disable system App-Standby (device-wide)",
                "Not recommended: changing this affects the whole device and can cause glitches.",
                "o_standby", Prefs.optStandby(this));
    }

    private void buildOwnerCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("6  \u00b7  Device Owner (needed for the hard lock)"));
        c.addView(body("The unbreakable kiosk lock screen only works while this app is the real "
                + "Device Owner. Grant it once from a PC, or transfer it from another app such as "
                + "Dhizuku."));

        TextView cmd = new TextView(this);
        cmd.setText("adb shell dpm set-device-owner\ncom.vortex.timelock/.TimeLockAdmin");
        cmd.setTextColor(ui.accent);
        cmd.setTextSize(12.5f);
        cmd.setTypeface(Ui.mono());
        cmd.setTextIsSelectable(true);
        cmd.setBackground(ui.rounded(ui.field, 12, ui.border, 1.5f));
        cmd.setPadding(dp(14), dp(14), dp(14), dp(14));
        cmd.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        clp.bottomMargin = dp(4);
        cmd.setLayoutParams(clp);
        c.addView(cmd);

        c.addView(divider());
        TextView steps = new TextView(this);
        steps.setText("Transferring from Dhizuku:\n"
                + "1.  Activate Sentinel TimeLock as a device admin (button below).\n"
                + "2.  In Dhizuku, open the device-owner / transfer option.\n"
                + "3.  Pick Sentinel TimeLock as the target and confirm.\n\n"
                + "This build advertises ownership-transfer support, so the old "
                + "\"Provided target does not support ownership transfer\" error no longer occurs. "
                + "The target must be an ACTIVE device admin before the transfer.");
        steps.setTextColor(ui.textDim);
        steps.setTextSize(13f);
        steps.setLineSpacing(dp(4), 1f);
        steps.setPadding(0, dp(10), 0, dp(4));
        c.addView(steps);

        Button admin = ghostButton("Activate device admin");
        admin.setOnClickListener(v -> openAdminSettings());
        c.addView(admin);

        Button batt = ghostButton("Allow unrestricted battery (recommended)");
        batt.setOnClickListener(v -> requestBatteryExemption());
        c.addView(batt);
    }

    private void buildFinishCard(LinearLayout box) {
        LinearLayout c = card();
        box.addView(c);
        c.addView(cardTitle("7  \u00b7  Finish"));

        Button save = ghostButton("Save settings");
        save.setOnClickListener(v -> {
            if (Prefs.activated(this) && !verified) { toast("Verify your identity first"); return; }
            if (applyInputs(false)) { toast("Saved"); refreshStatus(); }
        });
        c.addView(save);

        Button complete = actionButton("COMPLETE SETUP & HIDE", ui.danger, ui.onDanger, 17);
        complete.setPadding(dp(16), dp(22), dp(16), dp(22));
        complete.setOnClickListener(v -> confirmComplete());
        c.addView(complete);

        // ===================== BUILD RECIPE 6 — manual conceal control =====================
        // A dedicated view element whose click listener talks to the system
        // PackageManager API directly: it disables the manifest launcher entries
        // (.SplashActivity, which owns MAIN+LAUNCHER, and .LauncherDashboard, which
        // it hands over to) with COMPONENT_ENABLED_STATE_DISABLED + DONT_KILL_APP.
        // Those two are the ONLY launcher-declaring components; SetupActivity, the
        // FGS engine (TimeLockService), BootReceiver, EnforcerReceiver and the
        // secret-code entry are separate components and stay enabled, so the
        // background enforcement daemon keeps running in the same process.
        Button hideIcon = ghostButton("HIDE LAUNCHER ICON NOW");
        hideIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    for (Class<?> entry : new Class<?>[]{
                            SplashActivity.class, GatekeeperActivity.class,
                            LauncherDashboard.class }) {
                        ComponentName launcherAlias = new ComponentName(
                                SetupActivity.this.getApplicationContext(), entry);
                        SetupActivity.this.getPackageManager().setComponentEnabledSetting(
                                launcherAlias,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP);
                    }
                    toast("Launcher icon hidden \u2014 reopen with  *#*#84635#*#*");
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "hideLauncherAlias", t);
                    toast("Could not hide the icon: " + t.getClass().getSimpleName());
                }
            }
        });
        c.addView(hideIcon);

        c.addView(note("One tap saves your interval and behaviour, freezes every launcher entry "
                + "of the app, removes it from recents and notification noise, and locks the device "
                + "into the screen-time policy."));
    }

    // ==================================================================== form state

    private void seedFields() {
        use24 = TimeFmt.is24(this);
        ahIn = numField(2);
        amIn = numField(2);
        setResetFields(Prefs.anchorMin(this));
    }

    private void showGate() {
        gateBox.setVisibility(View.VISIBLE);
        formBox.setVisibility(View.GONE);
        if (biometricReady && gateAutoPrompt) {
            gateAutoPrompt = false;
            gateBox.post(this::startBiometric);
        }
    }

    private void showForm() {
        gateBox.setVisibility(View.GONE);
        formBox.setVisibility(View.VISIBLE);
        updatePreview();
        refreshStatus();
    }

    /**
     * The reset instant from a minute-of-day, rendered in the user's own 12/24-hour
     * form (see {@link TimeFmt}). The console and the lock screen speak the same
     * clock: an operator who configures the reset time reads exactly the string the
     * locked-out user will see on the kiosk.
     */
    static String clockAt(Context c, int minuteOfDay) {
        return TimeFmt.clockFromMinuteOfDay(c, minuteOfDay);
    }

    private void updatePreview() {
        if (limitPreview != null && limitSel != null) {
            int mins = limitSel.getValue();
            if (mins <= 0) {
                limitPreview.setText("No interval set yet \u2014 pick one above to continue");
                limitPreview.setTextColor(ui.danger);
            } else {
                limitPreview.setText("Allowed per day:  " + humanDuration(mins));
                limitPreview.setTextColor(ui.accent);
            }
        }
        if (resetPreview != null) {
            resetPreview.setText("Resets every day at  " + clockAt(this, resetMinuteOfDay()));
            resetPreview.setTextColor(ui.accent);
        }
        if (warnPreview != null && warnSel != null) {
            warnPreview.setText("Warning shows for  " + warnSel.getValue()
                    + "s  before the screen sleeps");
            warnPreview.setTextColor(ui.accent);
        }
        if (schedulePreview != null) {
            if (scheduleCard != null) scheduleCard.refreshValues();
            boolean custom = ScheduleConfig.hasCustomSchedule(this);
            schedulePreview.setText(ScheduleConfig.summary(this));
            schedulePreview.setTextColor(custom ? ui.accent : ui.textDim);
        }
    }

    private boolean applyInputs(boolean activate) {
        if (limitSel == null) return false;
        int mins = clamp(limitSel.getValue(), 0, 1440);
        long limit = mins * 60_000L;
        if (limit < 60_000L) { toast("Set a limit of at least 1 minute"); return false; }

        Prefs.setLimitMs(this, limit);
        // resetMinuteOfDay() clamps the hour/minute against the device's clock form,
        // so a half-typed field can never persist an out-of-range anchor.
        Prefs.setAnchorMin(this, resetMinuteOfDay());
        Prefs.setWarnSeconds(this, clamp(warnSel == null ? 10 : warnSel.getValue(), 1, 120));
        return true;
    }

    /**
     * Point-in-time refresh for the many imperative call sites (a switch flipped,
     * settings saved, the identity gate opened). Samples the clock exactly once
     * and delegates, so the on-demand path and the 1 Hz path run identical code.
     */
    private void refreshStatus() {
        refreshStatus(System.currentTimeMillis());
    }

    /**
     * The real render, projected from a caller-supplied instant.
     *
     * @param now the shared authoritative instant -- either the value carried by
     *            the 1 Hz tick, or one fresh sample for an out-of-band refresh.
     *            Taking it as a parameter is what keeps this console showing the
     *            SAME second as the notification's chronometer.
     */
    private void refreshStatus(long now) {
        if (statusBadge == null) return;

        boolean activated = Prefs.activated(this);
        boolean owner = Engine.isDeviceOwner(this);
        boolean admin = Engine.isAdminActive(this);
        boolean batt = isBatteryExempt();
        long lim = Prefs.limitMs(this);

        if (activated) {
            statusBadge.setText(owner ? "ACTIVE \u2014 hidden and enforcing" : "ACTIVE \u2014 waiting for Device Owner");
            statusBadge.setTextColor(owner ? ui.onAccent : ui.onDanger);
            statusBadge.setBackground(ui.rounded(owner ? ui.ok : ui.warn, 12));
        } else {
            statusBadge.setText("NOT SET UP YET");
            statusBadge.setTextColor(ui.onDanger);
            statusBadge.setBackground(ui.rounded(ui.warn, 12));
        }

        tvAccess.setText(owner ? "Device owner" : (admin ? "Device admin (not owner)" : "Not admin"));
        tvAccess.setTextColor(owner ? ui.ok : ui.warn);

        tvBattery.setText(batt ? "Unrestricted" : "Restricted (may delay lock)");
        tvBattery.setTextColor(batt ? ui.ok : ui.warn);

        tvLimit.setText(lim > 0 ? humanDuration(lim / 60_000L) : "not set");
        tvLimit.setTextColor(lim > 0 ? ui.text : ui.warn);

        tvReset.setText(clockAt(this, Prefs.anchorMin(this)));

        tvWarn.setText(Prefs.optScreenOff(this) ? (Prefs.warnSeconds(this) + "s warning") : "off");
        tvWarn.setTextColor(Prefs.optScreenOff(this) ? ui.text : ui.textDim);

        boolean bioReady = BiometricAuth.isAvailable(this);
        tvGate.setText(bioReady ? "fingerprint" : "not enrolled");
        tvGate.setTextColor(bioReady ? ui.text : ui.warn);

        if (tvSchedule != null) {
            int n = ScheduleConfig.customizedDayCount(this);
            tvSchedule.setText(n == 0 ? "all days on daily interval" : (n + " day(s) overridden"));
            tvSchedule.setTextColor(n == 0 ? ui.textDim : ui.accent);
        }

        if (activated) {
            tvUsed.setText(humanDuration(Engine.effectiveUsed(this, now) / 60_000L));
            tvUsed.setTextColor(ui.text);
            boolean paused = DbMigration.isReconfigPauseActive(this, now);
            boolean locked = Engine.shouldBeLocked(this, now);
            tvState.setText(paused ? "PAUSED (re-configure once)"
                                   : (locked ? "LOCKED" : "unlocked"));
            tvState.setTextColor(paused ? ui.warn : (locked ? ui.danger : ui.ok));
        } else {
            tvUsed.setText("\u2014");
            tvUsed.setTextColor(ui.textDim);
            tvState.setText("\u2014");
            tvState.setTextColor(ui.textDim);
        }
    }

    // ==================================================================== activation

    /**
     * SETUP-COMPLETION INTERCEPT.
     *
     * Replaces the old single dialog with the fail-safe approval chain: an
     * isolated review, acknowledgement, typed-phrase and final confirmation.
     * Nothing is executed unless every link is passed (the chain state is
     * persisted as "done" before we continue), and if the user has requested
     * root-level restrictions a second, separate chain gates their activation.
     */
    private void confirmComplete() {
        if (Prefs.activated(this) && !verified) { toast("Verify your identity first"); return; }
        if (!applyInputs(true)) return;

        // INTERCEPT #1 — the 7-tier activation ladder gates final setup execution.
        ApprovalGate.gateSetup(this,
                this::afterSetupApproval,
                () -> toast("Setup cancelled \u2014 nothing was applied"));
    }

    /** Runs the root-level restriction chain when (and only when) one was requested. */
    private void afterSetupApproval() {
        if (!ApprovalGate.anyExtremeRequested(this)) {
            ApprovalGate.revoke(this, ApprovalGate.SCOPE_RESTRICTIONS);
            doCompleteSetup();
            return;
        }
        // INTERCEPT #2 — the 8-tier restriction ladder gates the hard locks.
        // The walk itself mints the standing, action-bound warrant on success.
        ApprovalGate.gateRestrictions(this,
                this::doCompleteSetup,
                () -> {
                    // Declining the privileged path is NOT a dead end: strip the
                    // root-level requests, re-arm the barrier and finish setup
                    // WITHOUT them. The fail-safe default is the weaker policy.
                    ApprovalGate.revoke(this, ApprovalGate.SCOPE_RESTRICTIONS);
                    Prefs.setOpt(this, "o_safeboot", false);
                    Prefs.setOpt(this, "o_factory", false);
                    toast("Root restrictions declined \u2014 completing without them");
                    doCompleteSetup();
                });
    }

    /** Full one-tap finish: activate + conceal everything. */
    private void doCompleteSetup() {
        long now = System.currentTimeMillis();

        // Burn the single-use setup warrant. Best-effort: the setup loop may have
        // been completed before a long restriction chain, so a merely-expired
        // one-shot must not block the finish we already authorised.
        ApprovalGate.consumeSetup(this);

        // Re-configuration just happened: seal the one-time migration window and
        // re-conceal the launcher, so the permanent lock resumes from here on.
        // Do this BEFORE resetting the accounting state below, so nothing can
        // observe an "activated but still paused" moment on the way out.
        DbMigration.consumeReconfigPause(this);

        Prefs.setActivated(this, true);
        Prefs.setWindowStart(this, Engine.windowStartFor(now, Prefs.anchorMin(this)));
        Prefs.setAcc(this, 0L);
        Prefs.setScreenOnSince(this, 0L);
        Prefs.setLocked(this, false);
        Prefs.setLockUntil(this, 0L);

        // 1. hide everything -- but ONLY once the two-step setup confirmation
        //    has actually been persisted (Prefs.setActivated above). The
        //    recent-task visibility blockade is runtime- and state-gated here so
        //    the console can never be pulled out of recents prematurely; the
        //    decision (and the reason) is written to the global log ring either
        //    way, via Engine.enforceRecentsBlockade -> Prefs.setLastEvent("config:...").
        boolean setupConfirmed = Engine.recentsBlockadeConfirmed(this);
        if (setupConfirmed) {
            // Freeze EVERY activity-launcher entry this app declares, so a locked-in
            // install has zero presence in the launcher / app drawer.
            Engine.freezeLaunchers(this);
            // ...and conceal the console COMPONENT itself. The console carries no
            // launcher entry of its own, so freezeLaunchers() never touched it:
            // it stayed exported and startable (adb / third-party app / recents)
            // even after a full setup. Disabling it on the same gate leaves NO
            // launchable surface at all; the secret dial code is the only way back.
            Engine.applyConsoleVisibility(this);
            Engine.enforceRecentsBlockade(this, "complete-setup");
            Engine.silenceNotifications(this);
        } else {
            // Defensive: the approval chain should always have flipped the gate
            // before we get here, but never hide on an unconfirmed state.
            Engine.enforceRecentsBlockade(this, "complete-setup-unconfirmed");
        }

        // 2. device-owner hardening
        Engine.selfGrantNotifications(this);
        if (Engine.isDeviceOwner(this)) {
            Engine.applyLockState(this, false);
            Engine.applyHardening(this, true);
        }

        // 3. battery exemption + engine bootstrap
        requestBatteryExemption();
        Engine.bootstrap(this);
        Engine.reevaluate(this, "complete-setup");

        verified = true;
        refreshStatus();
        toast("Setup complete \u2014 app hidden and locked in");
        finishAndRemoveTask();
    }

    private void openAdminSettings() {
        try {
            startActivity(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            TimeLockAdmin.component(this)));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
            catch (Throwable ignored) {}
        }
    }

    private void requestBatteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("Battery optimization already disabled");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Throwable ignored) {}
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

    /** A label/value row inside the live status card. Returns the value TextView. */
    private TextView valueRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ui.textDim);
        l.setTextSize(14f);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText("\u2014");
        v.setTextColor(ui.text);
        v.setTextSize(14f);
        v.setTypeface(Ui.bold());
        v.setGravity(Gravity.END);
        row.addView(v);

        parent.addView(row);
        return v;
    }

    /** A titled row with a Switch that writes straight to Prefs. */
    private void toggleRow(LinearLayout parent, String title, String desc,
                           final String key, boolean value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(col);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(14.5f);
        t.setTypeface(Ui.bold());
        col.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(ui.textFaint);
            d.setTextSize(12.5f);
            d.setLineSpacing(dp(2), 1f);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }

        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setPadding(dp(10), 0, dp(4), 0);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setOpt(SetupActivity.this, key, isChecked);
            if (Engine.isDeviceOwner(SetupActivity.this) && Prefs.activated(SetupActivity.this)) {
                Engine.applyHardening(SetupActivity.this, true);
            }
            refreshStatus();
        });
        row.addView(sw);

        parent.addView(row);
    }

    /**
     * A toggle for a ROOT-LEVEL restriction. Switching it ON does NOT persist the
     * request immediately: it opens the isolated restriction chain first, and only
     * a fully approved chain writes the option, mints the standing authorisation
     * and (if already activated) re-applies hardening. Declining reverts the switch
     * and leaves the barrier armed. Switching OFF is never gated.
     */
    private void dangerousToggleRow(LinearLayout parent, String title, String desc,
                                    final String key, boolean value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(col);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(14.5f);
        t.setTypeface(Ui.bold());
        col.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(ui.textFaint);
            d.setTextSize(12.5f);
            d.setLineSpacing(dp(2), 1f);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }

        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setPadding(dp(10), 0, dp(4), 0);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                // Lifting is instantaneous and never requires approval.
                Prefs.setOpt(SetupActivity.this, key, false);
                ApprovalGate.revoke(SetupActivity.this, ApprovalGate.SCOPE_RESTRICTIONS);
                if (Engine.isDeviceOwner(SetupActivity.this) && Prefs.activated(SetupActivity.this)) {
                    Engine.applyHardening(SetupActivity.this, true);
                }
                refreshStatus();
                return;
            }
            // INTERCEPT #2 (live toggle) \u2014 the 8-tier restriction ladder gates
            // the hard locks; the walk mints the standing warrant on success.
            ApprovalGate.gateRestrictions(SetupActivity.this,
                    () -> {
                        Prefs.setOpt(SetupActivity.this, key, true);
                        if (Engine.isDeviceOwner(SetupActivity.this) && Prefs.activated(SetupActivity.this)) {
                            Engine.applyHardening(SetupActivity.this, true);
                        }
                        refreshStatus();
                    },
                    () -> {
                        buttonView.setChecked(false);
                        toast("Restriction not enabled \u2014 approval declined");
                    });
        });
        row.addView(sw);

        parent.addView(row);
    }

    private TextView previewLine() {
        TextView t = new TextView(this);
        t.setTextSize(17f);
        t.setTypeface(Ui.bold());
        t.setTextColor(ui.accent);
        t.setBackground(ui.rounded(ui.accentSoft, 12));
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
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

    /** Compact caption + [−][field][+] row used for the reset hour/minute. */
    private void timeRow(LinearLayout parent, String caption, EditText field, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextColor(ui.textDim);
        cap.setTextSize(15f);
        cap.setTypeface(Ui.medium());
        cap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cap);

        Button minus = miniStep("\u2212");
        minus.setOnClickListener(v -> bump(field, -1, min, max));
        row.addView(minus);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(62), dp(50));
        flp.leftMargin = dp(10);
        flp.rightMargin = dp(10);
        field.setLayoutParams(flp);
        row.addView(field);

        Button plus = miniStep("+");
        plus.setOnClickListener(v -> bump(field, +1, min, max));
        row.addView(plus);

        parent.addView(row);
    }

    private Button miniStep(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(22f);
        b.setTextColor(ui.text);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(ui.surfaceAlt, 12, ui.border, 1.5f));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        b.setPadding(0, 0, 0, 0);
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
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }

    private EditText numField(int maxLen) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(ui.text);
        e.setTextSize(20f);
        e.setTypeface(Ui.bold());
        e.setGravity(Gravity.CENTER);
        e.setBackground(ui.rounded(ui.field, 12, ui.border, 1.5f));
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        e.setPadding(dp(4), dp(4), dp(4), dp(4));
        return e;
    }

    private TextWatcher watcher() {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { updatePreview(); }
            public void afterTextChanged(Editable s) {}
        };
    }

    private void bump(EditText f, int delta, int min, int max) {
        int v = clamp(parseInt(f, min) + delta, min, max);
        f.setText(String.format(Locale.US, "%d", v));
        updatePreview();
    }

    // --------------------------------------------------- reset-time picker

    /**
     * The reset instant currently dialled into the picker, as a 24-hour
     * minute-of-day. The two fields are read through the DEVICE's clock form: on a
     * 12-hour phone {@link #ahIn} holds 1..12 and {@link #pm} supplies the
     * meridiem; on a 24-hour phone it holds 0..23. Every value is clamped, so a
     * half-typed field can never yield an out-of-range anchor.
     */
    private int resetMinuteOfDay() {
        if (ahIn == null || amIn == null) return Prefs.anchorMin(this);
        int h = clamp(parseInt(ahIn, use24 ? 0 : 12), use24 ? 0 : 1, use24 ? 23 : 12);
        int m = clamp(parseInt(amIn, 0), 0, 59);
        int h24 = use24 ? (h % 24) : ((h % 12) + (pm ? 12 : 0));
        return h24 * 60 + m;
    }

    /**
     * Seed the hour/minute fields + AM/PM state from a 24-hour minute-of-day,
     * rendered in whatever clock form the device currently uses. Called at build
     * time and after a configuration change so the anchor is never lost.
     */
    private void setResetFields(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        int h24 = m / 60;
        use24 = TimeFmt.is24(this);
        pm = h24 >= 12;
        int shown = use24 ? h24 : (h24 % 12 == 0 ? 12 : h24 % 12);
        if (ahIn != null) ahIn.setText(String.format(Locale.US, "%d", shown));
        if (amIn != null) amIn.setText(String.format(Locale.US, "%d", m % 60));
        paintMeridiem();
    }

    /** A caption + [AM][PM] segmented toggle, shown only on 12-hour devices. */
    private View meridiemRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView cap = new TextView(this);
        cap.setText("AM / PM");
        cap.setTextColor(ui.textDim);
        cap.setTextSize(15f);
        cap.setTypeface(Ui.medium());
        cap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cap);

        amBtn = meridiemButton("AM");
        pmBtn = meridiemButton("PM");
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) pmBtn.getLayoutParams();
        plp.leftMargin = dp(10);
        pmBtn.setLayoutParams(plp);
        amBtn.setOnClickListener(v -> { pm = false; paintMeridiem(); updatePreview(); });
        pmBtn.setOnClickListener(v -> { pm = true; paintMeridiem(); updatePreview(); });
        row.addView(amBtn);
        row.addView(pmBtn);
        paintMeridiem();
        return row;
    }

    private Button meridiemButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17f);
        b.setTypeface(Ui.bold());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(50)));
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }

    private void paintMeridiem() {
        if (amBtn == null || pmBtn == null) return;
        styleMeridiem(amBtn, !pm);
        styleMeridiem(pmBtn, pm);
    }

    private void styleMeridiem(Button b, boolean on) {
        b.setTextColor(on ? ui.onAccent : ui.text);
        b.setBackground(ui.rounded(on ? ui.accent : ui.surfaceAlt, 12,
                on ? 0 : ui.border, 1.5f));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** "3 h 30 min" style duration for minute counts. */
    private static String humanDuration(long minutes) {
        if (minutes <= 0) return "0 min";
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " min";
        if (m == 0) return h + (h == 1 ? " hour" : " hours");
        return h + " h " + m + " min";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Throwable t) { return def; }
    }

    private int dp(int v) {
        return ui.dp(v);
    }
}
