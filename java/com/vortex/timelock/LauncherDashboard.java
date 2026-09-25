package com.vortex.timelock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ============================================================================
 *  Sentinel TimeLock — launcher activity dashboard
 * ============================================================================
 *
 *  This is the single visible launcher entry for the app (it is what the
 *  manifest declares directly). It is a READ-ONLY projection
 *  of the saved configuration: it never mutates a preference and never applies
 *  any policy.
 *
 *  <p><b>Read-only after setup.</b> Before setup is finished the dashboard offers
 *  one edit entrance ({@link SetupActivity}, via the console button). The moment
 *  the configuration is activated that entrance is withdrawn and replaced by a
 *  "configuration is locked in" notice: from an activated install there is no way
 *  to reach any configuration surface from the dashboard, only to read it. The
 *  one sanctioned exception is {@link DbMigration#isReconfigPauseActive}, the
 *  one-time post-upgrade re-configuration window, during which editing is briefly
 *  re-opened. See {@link #applyEditability(boolean)}.
 *
 *  <p><b>Dynamically reads the saved configuration.</b> Nothing here is cached
 *  at build time and nothing is hard-coded. Every field is resolved, on each
 *  render pass, straight from the durable stores the rest of the app writes to:
 *  <ul>
 *    <li>{@link Prefs} — the {@code tl_state} SharedPreferences file: daily
 *        limit, accounting anchor, warning seconds, activation / settings-lock
 *        flags and the hardening toggles;</li>
 *    <li>{@link ScheduleConfig} — the per-weekday overrides;</li>
 *    <li>{@link Engine} — the day-aware effective limit
 *        ({@link Engine#limitMsFor}), the live consumption
 *        ({@link Engine#effectiveUsed}) and the derived
 *        {@link Engine#remainingMs} / lock state.</li>
 *  </ul>
 *  Because the whole table is rebuilt from those sources on every tick, a change
 *  made in the console (or by the engine) is reflected here within a second,
 *  with no observer, listener or broadcast wiring.
 *
 *  <p><b>The live read-out.</b> The dashboard subscribes to the process's one
 *  authoritative 1 Hz cadence ({@link HardwareTick}) while it is on screen, so
 *  the remaining screen-on time counts down in lock-step with the kiosk and the
 *  status-bar counter — one clock, one instant, no drift. It unsubscribes in
 *  {@code onPause}, so it costs nothing while backgrounded.
 *
 *  <p>All colour and geometry come from the shared {@link Ui} design tokens, so
 *  the dashboard follows the system light/dark theme exactly like the console.
 */
public class LauncherDashboard extends Activity implements HardwareTick.Sink {

    static final String TAG = "TL.Dashboard";

    private Ui ui;

    /** Cheap cached gate for {@link HardwareTick}; true only while on screen. */
    private boolean resumed = false;

    // ---- hero: the remaining-time read-out ----
    private TextView statusPill, heroLabel, heroValue, heroNote;
    private View barFill, barTrack;
    private String pillBaked = null;

    // ---- stat tiles ----
    private TextView tileUsed, tileLimit, tileLeft, tileGuard;

    // ---- saved-configuration table ----
    private TextView cfgDaily, cfgToday, cfgReset, cfgWarn, cfgSchedule, cfgState;

    private TextView updatedNote;

    // ---- edit entrance (withdrawn once the configuration is locked in) ----
    private Button consoleBtn;
    private TextView readOnlyNote;

    private final SimpleDateFormat resetFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat stampFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

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
        // subscribe() renders once immediately from live arithmetic, then keeps
        // the read-out ticking once per second off the shared cadence.
        HardwareTick.subscribe(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        HardwareTick.unsubscribe(this);
        super.onPause();
    }

    // ==================================================================== tick sink

    /** Cached visibility gate; never performs binder traffic. */
    @Override
    public boolean active() {
        return resumed && !isFinishing();
    }

    /** Paint one frame from the shared authoritative instant. */
    @Override
    public void onTick(long nowMs) {
        render(nowMs);
    }

    // ==================================================================== render

    private void render(long now) {
        // ---- resolve everything fresh from the saved configuration ----
        long limit = Engine.limitMsFor(this, now);          // day-aware effective limit (0 = none)
        long used = Engine.effectiveUsed(this, now);        // screen-on ms in the current window
        long remaining = limit > 0L ? Math.max(0L, limit - used) : 0L;

        boolean activated = Prefs.activated(this);
        boolean owner = Engine.isDeviceOwner(this);
        boolean settingsLocked = Prefs.settingsLocked(this);
        boolean locked = Prefs.locked(this) || Engine.shouldBeLocked(this, now);

        // ---- read-only enforcement ----
        // Once setup is activated this dashboard is a pure read-out: the edit
        // entrance is withdrawn and no configuration surface can be reached from
        // here. The sanctioned one-time re-configuration window re-opens editing
        // for its duration, so the post-upgrade path still works.
        applyEditability(!activated || DbMigration.isReconfigPauseActive(this, now));

        // ---- status pill ----
        if (locked) {
            setPill("LOCKED", ui.danger, ui.dangerSoft);
        } else if (limit > 0L) {
            setPill("ACTIVE", ui.accent, ui.accentSoft);
        } else {
            setPill("NO LIMIT", ui.warn, ui.warnSoft);
        }

        // ---- hero: remaining screen-on time ----
        if (locked) {
            long until = Math.max(0L, Engine.lockUntilMs(this, now) - now);
            setIfChanged(heroLabel, "TIME UNTIL UNLOCK");
            setIfChanged(heroValue, KioskSnapshot.hms(until));
            setIfChanged(heroNote, unlockNote(Engine.lockUntilMs(this, now), now));
        } else if (limit > 0L) {
            setIfChanged(heroLabel, "SCREEN-ON TIME REMAINING");
            setIfChanged(heroValue, KioskSnapshot.hms(remaining));
            setIfChanged(heroNote, "of a " + ScheduleConfig.human(limit / 60_000L)
                    + " limit this window");
        } else {
            setIfChanged(heroLabel, "SCREEN-ON TIME REMAINING");
            setIfChanged(heroValue, "\u2014");
            setIfChanged(heroNote, "No daily limit is configured yet");
        }

        // ---- usage bar ----
        int permille = limit > 0L
                ? (int) Math.max(0L, Math.min(1000L, (used * 1000L) / limit)) : 0;
        applyWeight(barFill, permille);
        applyWeight(barTrack, 1000 - permille);
        int barColor = permille >= 1000 ? ui.danger : (permille >= 800 ? ui.warn : ui.accent);
        ((GradientDrawable) barFill.getBackground()).setColor(barColor);

        // ---- tiles ----
        setIfChanged(tileUsed, KioskSnapshot.shortDuration(used));
        setIfChanged(tileLimit, limit > 0L ? KioskSnapshot.shortDuration(limit) : "Not set");
        setIfChanged(tileLeft, limit > 0L ? KioskSnapshot.shortDuration(remaining) : "\u2014");
        int warn = Prefs.warnSeconds(this);
        setIfChanged(tileGuard, warn + "s");

        // ---- saved-configuration table ----
        long base = Prefs.limitMs(this);
        setIfChanged(cfgDaily, base > 0L ? ScheduleConfig.human(base / 60_000L) : "Not set");
        setIfChanged(cfgToday, limit > 0L
                ? ScheduleConfig.human(limit / 60_000L) : "No lock today");
        setIfChanged(cfgReset, resetFmt.format(new Date(Engine.windowEnd(this, now))));
        setIfChanged(cfgWarn, warn + " seconds");
        setIfChanged(cfgSchedule, ScheduleConfig.summary(this));
        setIfChanged(cfgState, stateLine(activated, owner, settingsLocked));

        setIfChanged(updatedNote, "Live \u00b7 updated " + stampFmt.format(new Date(now)));
    }

    /**
     * Show or withdraw the edit entrance. Read-only is the resting state for a
     * completed (activated) setup; {@code editable} is true only before setup is
     * finished or while the sanctioned re-configuration window is open.
     */
    private void applyEditability(boolean editable) {
        if (consoleBtn == null || readOnlyNote == null) return;
        int btn = editable ? View.VISIBLE : View.GONE;
        int note = editable ? View.GONE : View.VISIBLE;
        if (consoleBtn.getVisibility() != btn) consoleBtn.setVisibility(btn);
        if (readOnlyNote.getVisibility() != note) readOnlyNote.setVisibility(note);
        if (!editable) setIfChanged(readOnlyNote, "Read-only \u2014 configuration is locked in");
    }

    private String unlockNote(long atMs, long nowMs) {
        if (atMs <= 0L) return "Unlocks once the timer finishes";
        return "Unlocks at " + resetFmt.format(new Date(atMs));
    }

    private static String stateLine(boolean activated, boolean owner, boolean settingsLocked) {
        StringBuilder b = new StringBuilder();
        b.append(activated ? "Activated" : "Setup not complete");
        b.append("  \u00b7  ").append(owner ? "Device owner" : "No ownership");
        if (settingsLocked) b.append("  \u00b7  Settings locked");
        return b.toString();
    }

    // ==================================================================== UI build

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(28));
        scroll.addView(root);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        header.setLayoutParams(hlp);

        TextView title = new TextView(this);
        title.setText("Screen Time");
        title.setTextColor(ui.text);
        title.setTextSize(24f);
        title.setTypeface(Ui.bold());
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        statusPill = new TextView(this);
        statusPill.setTextSize(12f);
        statusPill.setTypeface(Ui.bold());
        statusPill.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        header.addView(statusPill);
        root.addView(header);

        TextView subtitle = new TextView(this);
        subtitle.setText("Launcher dashboard \u00b7 live configuration");
        subtitle.setTextColor(ui.textFaint);
        subtitle.setTextSize(13f);
        subtitle.setPadding(0, ui.dp(4), 0, 0);
        root.addView(subtitle);

        // ---- hero card ----
        LinearLayout hero = card();
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = ui.dp(18);
        hero.setLayoutParams(heroLp);

        heroLabel = caption();
        hero.addView(heroLabel);

        heroValue = new TextView(this);
        heroValue.setTextColor(ui.text);
        heroValue.setTextSize(46f);
        heroValue.setTypeface(Ui.bold());
        heroValue.setIncludeFontPadding(false);
        heroValue.setPadding(0, ui.dp(6), 0, ui.dp(2));
        hero.addView(heroValue);

        heroNote = new TextView(this);
        heroNote.setTextColor(ui.textDim);
        heroNote.setTextSize(13.5f);
        hero.addView(heroNote);

        // usage bar (two weighted views: fill + remainder)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipToPadding(false);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8));
        barLp.topMargin = ui.dp(16);
        bar.setLayoutParams(barLp);

        barFill = new View(this);
        barFill.setBackground(ui.rounded(ui.accent, 4));
        barFill.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(barFill);

        barTrack = new View(this);
        barTrack.setBackground(ui.rounded(ui.track, 4));
        barTrack.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(barTrack);
        hero.addView(bar);
        root.addView(hero);

        // ---- stat tiles (2 x 2) ----
        LinearLayout tilesRow1 = tileRow();
        tileUsed = addTile(tilesRow1, "TIME USED");
        tileLimit = addTile(tilesRow1, "DAILY LIMIT");
        root.addView(tilesRow1);

        LinearLayout tilesRow2 = tileRow();
        tileLeft = addTile(tilesRow2, "TIME LEFT");
        tileGuard = addTile(tilesRow2, "SCREEN-OFF WARNING");
        root.addView(tilesRow2);

        // ---- saved-configuration card ----
        LinearLayout config = card();
        LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cfgLp.topMargin = ui.dp(20);
        config.setLayoutParams(cfgLp);

        TextView cfgTitle = caption();
        cfgTitle.setText("SAVED CONFIGURATION");
        config.addView(cfgTitle);

        cfgDaily = configValue();
        config.addView(configRow("Daily limit", cfgDaily));
        cfgToday = configValue();
        config.addView(configRow("Today's limit", cfgToday));
        cfgReset = configValue();
        config.addView(configRow("Window resets at", cfgReset));
        cfgWarn = configValue();
        config.addView(configRow("Lock warning", cfgWarn));
        cfgSchedule = configValue();
        config.addView(configRow("Weekly schedule", cfgSchedule));
        cfgState = configValue();
        config.addView(configRow("State", cfgState));
        root.addView(config);

        // ---- edit entrance (hidden once the configuration is locked in) ----
        consoleBtn = new Button(this);
        consoleBtn.setText("Open setup console");
        consoleBtn.setAllCaps(false);
        consoleBtn.setTextSize(16f);
        consoleBtn.setTextColor(ui.onAccent);
        consoleBtn.setTypeface(Ui.bold());
        consoleBtn.setBackground(ui.rounded(ui.accent, 14));
        consoleBtn.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = ui.dp(20);
        consoleBtn.setLayoutParams(cLp);
        consoleBtn.setMinWidth(0);
        consoleBtn.setMinimumWidth(0);
        consoleBtn.setMinHeight(0);
        consoleBtn.setMinimumHeight(0);
        consoleBtn.setStateListAnimator(null);
        consoleBtn.setOnClickListener(v -> openConsole());
        root.addView(consoleBtn);

        // Stands in for the button once setup is locked in: the dashboard then
        // offers read-only information and no way to reach the configuration.
        readOnlyNote = new TextView(this);
        readOnlyNote.setTextColor(ui.textFaint);
        readOnlyNote.setTextSize(12.5f);
        readOnlyNote.setGravity(Gravity.CENTER);
        readOnlyNote.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), 0);
        readOnlyNote.setVisibility(View.GONE);
        root.addView(readOnlyNote);

        updatedNote = new TextView(this);
        updatedNote.setTextColor(ui.textFaint);
        updatedNote.setTextSize(12f);
        updatedNote.setGravity(Gravity.CENTER);
        updatedNote.setPadding(0, ui.dp(14), 0, 0);
        root.addView(updatedNote);

        setContentView(scroll);
    }

    private void openConsole() {
        try {
            Intent i = new Intent(this, SetupActivity.class);
            i.addFlags(Engine.adminEntryFlags(this));
            startActivity(i);
        } catch (Throwable t) {
            // The console may be concealed (activated owner): the secret dial
            // code ( *#*#84635#*#* ) is the sanctioned door back in.
            setIfChanged(updatedNote, "Console concealed \u2014 dial *#*#84635#*#*");
        }
    }

    // ==================================================================== helpers

    /** A rounded surface card, matching the console's rhythm. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ui.rounded(ui.surface, 16, ui.border, 1f));
        c.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        return c;
    }

    private TextView caption() {
        TextView t = new TextView(this);
        t.setTextColor(ui.textFaint);
        t.setTextSize(12f);
        t.setTypeface(Ui.bold());
        t.setLetterSpacing(0.06f);
        return t;
    }

    private TextView configValue() {
        TextView v = new TextView(this);
        v.setTextColor(ui.text);
        v.setTextSize(14.5f);
        v.setTypeface(Ui.bold());
        v.setGravity(Gravity.END);
        return v;
    }

    private View configRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        row.setLayoutParams(lp);

        TextView cap = new TextView(this);
        cap.setText(label);
        cap.setTextColor(ui.textDim);
        cap.setTextSize(14f);
        cap.setTypeface(Ui.medium());
        cap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cap);

        value.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(value);
        return row;
    }

    private LinearLayout tileRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(14);
        row.setLayoutParams(lp);
        return row;
    }

    /** Adds one tile to a tile row and returns its value {@link TextView}. */
    private TextView addTile(LinearLayout row, String label) {
        if (row.getChildCount() > 0) {
            View spacer = new View(this);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(12), 1);
            spacer.setLayoutParams(slp);
            row.addView(spacer);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(ui.rounded(ui.surface, 14, ui.border, 1f));
        col.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cap = new TextView(this);
        cap.setText(label);
        cap.setTextColor(ui.textFaint);
        cap.setTextSize(11f);
        cap.setTypeface(Ui.bold());
        cap.setLetterSpacing(0.05f);
        col.addView(cap);

        TextView value = new TextView(this);
        value.setTextColor(ui.text);
        value.setTextSize(20f);
        value.setTypeface(Ui.bold());
        value.setPadding(0, ui.dp(6), 0, 0);
        col.addView(value);

        row.addView(col);
        return value;
    }

    private void setPill(String label, int fg, int bg) {
        if (label.equals(pillBaked)) return;
        pillBaked = label;
        statusPill.setText(label);
        statusPill.setTextColor(fg);
        statusPill.setBackground(ui.rounded(bg, 20));
    }

    private static void applyWeight(View v, float weight) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            float w = Math.max(0f, weight);
            if (((LinearLayout.LayoutParams) lp).weight != w) {
                ((LinearLayout.LayoutParams) lp).weight = w;
                v.setLayoutParams(lp);
            }
        }
    }

    private static void setIfChanged(TextView t, String value) {
        CharSequence current = t.getText();
        if (current == null || !current.toString().equals(value)) t.setText(value);
    }
}
