package com.vortex.timelock;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  Screen Time Locker — TRANSITION + MAIN SETUP SCREEN
 * ============================================================================
 *
 *  The screen the operator lands on once the earlier onboarding surfaces have
 *  collected every prerequisite. It is deliberately two surfaces in one:
 *
 *  <ol>
 *    <li><b>Transition.</b> On entry it runs a live verification pass over the
 *        two prerequisite groups — the System Permissions and the Shizuku /
 *        Device Admin grant (see {@link TransitionGate}). Each group member is
 *        rendered with a live VERIFIED / PENDING chip and the panel keeps
 *        re-checking once a second. The moment {@link TransitionGate#allVerified}
 *        flips to true the screen <b>automatically transitions</b> to the main
 *        surface with a fade — the operator never has to tap to move on.</li>
 *    <li><b>Main setup screen.</b> A calm hub: a live status summary, the two
 *        educational shortcut buttons required by the spec — "Learn Full App
 *        Usage" and "What is Screentime-Locker exactly about?" — each opening a
 *        clean bottom-sheet ({@link SheetDialog}) with scrollable documentation,
 *        and a primary action that hands over to the configuration console
 *        ({@link SetupActivity}).</li>
 *  </ol>
 *
 *  <p>Auto-entry: {@link PermissionActivity} calls straight into this screen the
 *  instant the prerequisites are verified, and also auto-advances here when the
 *  operator returns to it with everything already granted. All colour and
 *  geometry come from the shared {@link Ui} design tokens, so the screen follows
 *  the system light/dark theme exactly like the rest of the app.
 */
public class MainSetupActivity extends Activity {

    static final String TAG = "TL.MainSetup";

    /** How often the transition panel re-verifies while it waits. */
    private static final long POLL_MS = 900L;
    /** Short beat so the completed checklist is visible before the reveal. */
    private static final long REVEAL_DELAY_MS = 550L;

    private Ui ui;
    private boolean resumed = false;
    private boolean revealed = false;
    private boolean revealPosted = false;

    // ---- transition surface ----
    private LinearLayout transitionPanel;
    private LinearLayout checksBox;
    private TextView transitionDot, transitionTitle, transitionNote;

    // ---- main surface ----
    private LinearLayout mainPanel;
    private TextView sysValue, adminValue;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || revealed) return;
            verifyPass();
            if (!revealed) handler.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable reveal = new Runnable() {
        @Override public void run() {
            revealPosted = false;
            if (resumed && !revealed && TransitionGate.allVerified(MainSetupActivity.this)) {
                revealMain();
            }
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
        // Locked-in install: as soon as we hold Device Owner, keep the app's own
        // controls disabled on every resume (permissions pinned, user control off).
        if (Engine.isDeviceOwner(this)) Engine.lockDownSelf(this);
        verifyPass();
        // Keep polling only while we are still in the transition state.
        if (!revealed) {
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_MS);
        } else {
            refreshMainStatus();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(reveal);
        revealPosted = false;
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui = new Ui(this).bind(this);
        boolean wasRevealed = revealed;
        buildUi();
        revealed = wasRevealed;
        if (revealed) {
            transitionPanel.setVisibility(View.GONE);
            mainPanel.setVisibility(View.VISIBLE);
            refreshMainStatus();
        } else {
            verifyPass();
        }
    }

    // ==================================================================== UI shell

    private void buildUi() {
        applySystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);

        // [1] transition surface — centered, full width
        transitionPanel = buildTransitionPanel();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        root.addView(transitionPanel, tlp);

        // [2] main surface — scrollable, hidden until the transition completes
        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(ui.bg);

        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        mainPanel.setPadding(dp(18), dp(26), dp(18), dp(48));
        mainScroll.addView(mainPanel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainPanel.addView(buildHeader());
        mainPanel.addView(buildStatusCard());
        mainPanel.addView(buildLearnCard());
        mainPanel.addView(buildContinueCard());

        mainScroll.setVisibility(revealed ? View.VISIBLE : View.GONE);
        mainPanel.setVisibility(revealed ? View.VISIBLE : View.GONE);
        root.addView(mainScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    // ------------------------------------------------------------ transition

    private LinearLayout buildTransitionPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(24), dp(24), dp(24));

        transitionDot = new TextView(this);
        transitionDot.setText("\u25CF");
        transitionDot.setTextSize(30f);
        transitionDot.setTextColor(ui.accent);
        col.addView(transitionDot);

        TextView kicker = new TextView(this);
        kicker.setText("TRANSITION");
        kicker.setTextColor(ui.accent);
        kicker.setTextSize(12.5f);
        kicker.setTypeface(Ui.bold());
        kicker.setLetterSpacing(0.22f);
        kicker.setPadding(0, dp(10), 0, 0);
        col.addView(kicker);

        transitionTitle = new TextView(this);
        transitionTitle.setText("Verifying your setup\u2026");
        transitionTitle.setTextColor(ui.text);
        transitionTitle.setTextSize(23f);
        transitionTitle.setTypeface(Ui.bold());
        transitionTitle.setGravity(Gravity.CENTER);
        transitionTitle.setPadding(0, dp(6), 0, 0);
        col.addView(transitionTitle);

        // live checklist card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(ui.surface, 18, ui.border, 1.5f));
        card.setPadding(dp(18), dp(6), dp(18), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(20);
        card.setLayoutParams(clp);

        checksBox = new LinearLayout(this);
        checksBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(checksBox);
        col.addView(card);

        transitionNote = new TextView(this);
        transitionNote.setText("");
        transitionNote.setTextColor(ui.textFaint);
        transitionNote.setTextSize(12.5f);
        transitionNote.setGravity(Gravity.CENTER);
        transitionNote.setLineSpacing(dp(3), 1f);
        transitionNote.setPadding(dp(4), dp(14), dp(4), 0);
        col.addView(transitionNote);

        Button review = ghostButton("Review permissions");
        review.setOnClickListener(v -> openPermissions());
        col.addView(review);

        return col;
    }

    /** Runs one verification pass and repaints the transition checklist. */
    private void verifyPass() {
        if (transitionPanel == null) return;

        List<TransitionGate.Requirement> reqs = TransitionGate.evaluate(this);
        checksBox.removeAllViews();
        int pending = 0;
        for (TransitionGate.Requirement r : reqs) {
            if (!r.ok) pending++;
            checksBox.addView(checkRow(r));
        }

        boolean all = TransitionGate.allVerified(this);

        if (all) {
            transitionDot.setTextColor(ui.ok);
            transitionTitle.setText("All set \u2014 entering main screen\u2026");
            transitionNote.setText("System permissions and the Device Admin grant are verified.");
            scheduleReveal();
        } else {
            transitionDot.setTextColor(ui.warn);
            transitionTitle.setText("Verifying your setup\u2026");
            transitionNote.setText(pending + (pending == 1 ? " item still needs" : " items still need")
                    + " attention. This screen advances automatically once every prerequisite "
                    + "is verified \u2014 or tap Review permissions to finish them now.");
        }
    }

    private void scheduleReveal() {
        if (revealed || revealPosted) return;
        revealPosted = true;
        handler.postDelayed(reveal, REVEAL_DELAY_MS);
    }

    private View checkRow(TransitionGate.Requirement r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText(r.label);
        label.setTextColor(ui.text);
        label.setTextSize(14.5f);
        label.setTypeface(Ui.bold());
        col.addView(label);

        TextView detail = new TextView(this);
        detail.setText(r.detail);
        detail.setTextColor(ui.textFaint);
        detail.setTextSize(12f);
        detail.setLineSpacing(dp(2), 1f);
        detail.setPadding(0, dp(2), 0, 0);
        col.addView(detail);

        row.addView(col);

        TextView chip = new TextView(this);
        chip.setText(r.ok ? "VERIFIED" : "PENDING");
        chip.setTextSize(11f);
        chip.setTypeface(Ui.bold());
        chip.setLetterSpacing(0.08f);
        chip.setTextColor(r.ok ? ui.ok : ui.warn);
        chip.setBackground(ui.rounded(r.ok ? ui.okSoft : ui.warnSoft, 10));
        chip.setPadding(dp(9), dp(5), dp(9), dp(5));
        row.addView(chip);

        return row;
    }

    /** Fades the transition surface away and reveals the main screen. */
    private void revealMain() {
        if (revealed) return;
        revealed = true;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(reveal);
        revealPosted = false;

        transitionPanel.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            transitionPanel.setVisibility(View.GONE);
            View mainScroll = (View) mainPanel.getParent();
            mainScroll.setVisibility(View.VISIBLE);
            mainPanel.setVisibility(View.VISIBLE);
            mainPanel.setAlpha(0f);
            mainPanel.animate().alpha(1f).setDuration(260).start();
            refreshMainStatus();
        }).start();
    }

    // ------------------------------------------------------------ main header

    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);

        TextView kicker = new TextView(this);
        kicker.setText("MAIN SETUP");
        kicker.setTextColor(ui.accent);
        kicker.setTextSize(12.5f);
        kicker.setTypeface(Ui.bold());
        kicker.setLetterSpacing(0.18f);
        head.addView(kicker);

        TextView title = new TextView(this);
        title.setText("You're all set");
        title.setTextColor(ui.text);
        title.setTextSize(26f);
        title.setTypeface(Ui.bold());
        title.setPadding(0, dp(6), 0, 0);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Every prerequisite is verified. Familiarise yourself with the app below, "
                + "then continue to choose your daily focus limit and finish setup.");
        sub.setTextColor(ui.textDim);
        sub.setTextSize(14f);
        sub.setLineSpacing(dp(4), 1f);
        sub.setPadding(0, dp(8), 0, 0);
        head.addView(sub);

        return head;
    }

    // ------------------------------------------------------------ status card

    private View buildStatusCard() {
        LinearLayout c = card();

        TextView heading = cardTitle("Verification summary");
        c.addView(heading);

        LinearLayout sysRow = statusRow(c, "System permissions");
        sysValue = (TextView) sysRow.getTag();

        LinearLayout adminRow = statusRow(c, "Shizuku / Device Admin");
        adminValue = (TextView) adminRow.getTag();

        refreshMainStatus();
        return c;
    }

    private void refreshMainStatus() {
        if (sysValue == null || adminValue == null) return;

        List<TransitionGate.Requirement> reqs = TransitionGate.evaluate(this);
        int pending = 0;
        for (TransitionGate.Requirement r : reqs) if (!r.ok) pending++;

        boolean sysOk = TransitionGate.systemPermissionsOk(this);

        sysValue.setText(sysOk ? "VERIFIED" : (pending + " PENDING"));
        sysValue.setTextColor(sysOk ? ui.ok : ui.warn);

        if (Engine.isDeviceOwner(this)) {
            adminValue.setText("DEVICE OWNER");
            adminValue.setTextColor(ui.ok);
        } else if (Engine.isAdminActive(this)) {
            adminValue.setText("ADMIN ONLY");
            adminValue.setTextColor(ui.warn);
        } else {
            adminValue.setText("NOT GRANTED");
            adminValue.setTextColor(ui.dangerText);
        }
    }

    /** A label/value line; the value TextView is returned via the row's tag. */
    private LinearLayout statusRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ui.textDim);
        l.setTextSize(14f);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText("\u2014");
        v.setTextColor(ui.text);
        v.setTextSize(13f);
        v.setTypeface(Ui.bold());
        v.setLetterSpacing(0.06f);
        v.setGravity(Gravity.END);
        row.addView(v);

        row.setTag(v);
        parent.addView(row);
        return row;
    }

    // ------------------------------------------------------------ learn shortcuts

    private View buildLearnCard() {
        LinearLayout c = card();

        c.addView(cardTitle("Learn about the app"));
        c.addView(note("New here? These two shortcuts open plain-language documentation "
                + "about how everything works."));

        c.addView(shortcutRow(
                "Learn Full App Usage",
                "A step-by-step guide to every feature",
                () -> SheetDialog.show(this, ui, "FULL APP USAGE",
                        "Learn Full App Usage", fullUsageIntro(), fullUsageSections())));

        c.addView(shortcutRow(
                "What is Screentime-Locker exactly about?",
                "The idea, the guarantees and the privacy model",
                () -> SheetDialog.show(this, ui, "ABOUT THE APP",
                        "What is Screentime-Locker exactly about?", aboutIntro(), aboutSections())));

        return c;
    }

    /** A tappable shortcut row: bold title, subordinate line, trailing chevron. */
    private View shortcutRow(String title, String subtitle, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1.5f));
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(15.5f);
        t.setTypeface(Ui.bold());
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(ui.textFaint);
        s.setTextSize(12.5f);
        s.setPadding(0, dp(3), 0, 0);
        col.addView(s);

        row.addView(col);

        TextView chevron = new TextView(this);
        chevron.setText("\u203A");
        chevron.setTextColor(ui.accent);
        chevron.setTextSize(24f);
        chevron.setTypeface(Ui.bold());
        chevron.setPadding(dp(10), 0, 0, 0);
        row.addView(chevron);

        return row;
    }

    // ------------------------------------------------------------ continue

    private View buildContinueCard() {
        LinearLayout c = card();

        c.addView(cardTitle("Continue"));
        c.addView(note("When you are ready, continue to configure your daily focus limit, "
                + "reset time and lock behaviour."));

        Button go = actionButton("CONTINUE TO SETUP", ui.ok, ui.onAccent, 16f);
        go.setOnClickListener(v -> openSetup());
        c.addView(go);

        Button recheck = ghostButton("Re-check permissions");
        recheck.setOnClickListener(v -> {
            refreshMainStatus();
            toast(TransitionGate.allVerified(this)
                    ? "Everything is verified"
                    : "Some items still need attention");
        });
        c.addView(recheck);

        return c;
    }

    // ==================================================================== actions

    private void openSetup() {
        try {
            startActivity(new Intent(this, SetupActivity.class));
        } catch (Throwable t) {
            toast("Could not open setup");
        }
    }

    private void openPermissions() {
        try {
            startActivity(new Intent(this, PermissionActivity.class));
        } catch (Throwable t) {
            toast("Could not open permissions");
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { /* toast can race a finishing Activity */ }
    }

    // ==================================================================== docs content

    private String fullUsageIntro() {
        return "Everything the app does, in the order you will meet it. Nothing here "
                + "requires technical knowledge.";
    }

    private List<SheetDialog.Section> fullUsageSections() {
        List<SheetDialog.Section> s = new ArrayList<>();
        s.add(new SheetDialog.Section("1 \u00b7 Choose your limit",
                "In the setup console you pick how much screen-on time you allow yourself "
                        + "each day (for example 2 hours), the time of day the counter resets, "
                        + "and how many seconds of warning you get before the screen turns off."));
        s.add(new SheetDialog.Section("2 \u00b7 How time is counted",
                "The app counts time only while the screen is actually on. When the screen is "
                        + "off, the clock is paused \u2014 so your allowance reflects real usage, "
                        + "not idle hours."));
        s.add(new SheetDialog.Section("3 \u00b7 What happens at the limit",
                "When your allowance for the current window is used up, the app shows a "
                        + "full-screen lock and keeps you out until the next window begins. "
                        + "The lock re-arms itself automatically after a reboot."));
        s.add(new SheetDialog.Section("4 \u00b7 The live counter",
                "While the limit is active a persistent notification shows your remaining "
                        + "time, counting down in lock-step with the lock screen. It is a "
                        + "read-out, not a control."));
        s.add(new SheetDialog.Section("5 \u00b7 Finishing setup",
                "Tap CONTINUE TO SETUP from the main screen, choose your values, confirm "
                        + "them, and the configuration is locked in. From that moment the "
                        + "settings surfaces are sealed."));
        s.add(new SheetDialog.Section("6 \u00b7 Getting back in",
                "After setup is locked, the console is reachable only on purpose: dial "
                        + "*#*#84635#*#* from the phone app and pass the biometric check. "
                        + "There is deliberately no PIN and no emergency release."));
        s.add(new SheetDialog.Section("7 \u00b7 Reboots & persistence",
                "A direct-boot receiver re-arms the guard the instant the system is up, "
                        + "before you even unlock the phone, so the policy survives reboots "
                        + "without any manual step."));
        return s;
    }

    private String aboutIntro() {
        return "The short version: this is a self-imposed screen-time lock that you "
                + "genuinely cannot cheat past.";
    }

    private List<SheetDialog.Section> aboutSections() {
        List<SheetDialog.Section> s = new ArrayList<>();
        s.add(new SheetDialog.Section("The idea",
                "Screentime-Locker enforces a daily limit on how long the screen can be "
                        + "actively used. You set the allowance and the reset time; the app "
                        + "holds you to it."));
        s.add(new SheetDialog.Section("Why it needs Device Owner",
                "Ordinary apps can be uninstalled, force-stopped or have their permissions "
                        + "revoked the moment they get inconvenient. With Device Owner rights "
                        + "the lock cannot be removed before your limit is finished, and other "
                        + "launchers cannot be used to slip past the lock screen."));
        s.add(new SheetDialog.Section("What it is not",
                "It is not spyware and not parental-surveillance software. It does not read "
                        + "your messages, does not track your location, and does not phone home. "
                        + "There is no account to create and no cloud sync."));
        s.add(new SheetDialog.Section("Privacy",
                "Everything it needs to work \u2014 your limit, your reset time, your usage "
                        + "counters \u2014 stays on this device. Nothing is transmitted anywhere."));
        s.add(new SheetDialog.Section("Why there is no master key",
                "A lock that has an easy override is not a lock. That is why there is no "
                        + "emergency PIN and no recovery code: once the configuration is locked "
                        + "in, the policy itself is the way out. This is a deliberate design "
                        + "choice, not a missing feature."));
        s.add(new SheetDialog.Section("The building blocks",
                "Setup combines three things: the runtime permissions (so the guard can "
                        + "notify, wake and stay alive), a Device Admin / Device Owner grant "
                        + "(so the lock can be enforced), and optionally Shizuku, which lets "
                        + "you grant Device Owner entirely on the phone \u2014 no PC or cable "
                        + "required."));
        return s;
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

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ui.textFaint);
        t.setTextSize(12.5f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, 0);
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
        lp.topMargin = dp(14);
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

    private int dp(int v) {
        return ui.dp(v);
    }
}
