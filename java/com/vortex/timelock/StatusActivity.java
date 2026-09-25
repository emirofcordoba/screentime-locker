package com.vortex.timelock;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ============================================================================
 *  Sentinel TimeLock — the always-available status screen
 * ============================================================================
 *
 *  <p><b>What this is.</b> The single read-only status page the notifications
 *  open. Tapping the persistent monitor / countdown notification (or the lock
 *  notification) lands here. It answers, at a glance and in plain language:
 *  <ul>
 *    <li>am I locked right now, and if so, how long until it lifts;</li>
 *    <li>if not, how long until the lock fires and when that is;</li>
 *    <li>how much screen-on time is used, the limit, what is left, the reset
 *        time and the weekly schedule;</li>
 *    <li>the enforcement state: setup, device ownership, enforceability, the
 *        settings lock, the status-bar countdown and the screen-off guard;</li>
 *    <li>the most recent engine events from the durable log ring.</li>
 *  </ul>
 *
 *  <p><b>Always available.</b> Unlike {@link LauncherDashboard} — which is a
 *  MAIN+LAUNCHER entry and is therefore frozen by {@link Engine#freezeLaunchers}
 *  the moment a full setup is activated — this activity carries NO launcher and
 *  NO HOME intent-filter. It is not returned by the launcher-entry query, so it
 *  is <em>never</em> disabled, and it stays startable by explicit component from
 *  the notification's content intent on a fully locked-in install. It is the one
 *  surface that is guaranteed to open at any point in the app's life.
 *
 *  <p><b>Strictly read-only.</b> This activity never writes a preference, never
 *  applies a policy and never offers a route into any configuration surface: no
 *  console button, no secret-code hint, no toggle. It only projects the durable
 *  state. The only "action" anywhere on it is a plain refresh, and even that is
 *  automatic.
 *
 *  <p><b>Live.</b> While on screen it subscribes to the process's one
 *  authoritative 1 Hz cadence ({@link HardwareTick}), so its numbers move in
 *  lock-step with the kiosk and the status bar, and it unsubscribes in
 *  {@code onPause} so it costs nothing in the background.
 */
public class StatusActivity extends Activity implements HardwareTick.Sink {

    static final String TAG = "TL.Status";

    private Ui ui;
    private boolean resumed = false;

    // ---- hero ----
    private TextView statusPill, heroLabel, heroValue, heroNote;
    private View barFill, barTrack;
    private String pillBaked = null;
    private int lastBarColor = 0;

    // ---- tiles ----
    private TextView tileUsed, tileLimit, tileLeft, tileGuard;

    // ---- status card ----
    private TextView stLockState, stAutoLock, stTodayLimit, stUsed, stLeft, stReset, stAnchor, stSchedule;

    // ---- enforcement card ----
    private TextView stSetup, stOwner, stEnforce, stSettings, stCountdown, stGuard, stWarning;

    // ---- activity + footer ----
    private TextView actLast, actList;
    private TextView updatedNote;

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat stampFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ==================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this).bind(this);
        buildUi();
        // Render once from live arithmetic so the page is never blank on arrival.
        render(System.currentTimeMillis());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        HardwareTick.subscribe(this);
    }

    @Override
    protected void onPause() {
        resumed = false;
        HardwareTick.unsubscribe(this);
        super.onPause();
    }

    // ==================================================================== tick sink

    @Override
    public boolean active() {
        return resumed && !isFinishing();
    }

    @Override
    public void onTick(long nowMs) {
        render(nowMs);
    }

    // ==================================================================== render

    private void render(long now) {
        long limit = Engine.limitMsFor(this, now);           // day-aware effective limit (0 = none)
        long used = Engine.effectiveUsed(this, now);         // screen-on ms in the current window
        long remaining = limit > 0L ? Math.max(0L, limit - used) : 0L;

        boolean activated = Prefs.activated(this);
        boolean owner = Engine.isDeviceOwner(this);
        boolean settingsLocked = Prefs.settingsLocked(this);
        boolean reconfig = DbMigration.isReconfigPauseActive(this, now);
        boolean locked = Prefs.locked(this) || Engine.shouldBeLocked(this, now);

        // ---- status pill ----
        if (locked) {
            setPill("LOCKED", ui.dangerText, ui.dangerSoft);
        } else if (reconfig) {
            setPill("PAUSED", ui.warn, ui.warnSoft);
        } else if (limit > 0L) {
            setPill("ACTIVE", ui.accent, ui.accentSoft);
        } else {
            setPill("NO LIMIT", ui.warn, ui.warnSoft);
        }

        // ---- hero: what happens next and when ----
        if (locked) {
            long until = Math.max(0L, Engine.lockUntilMs(this, now) - now);
            setIfChanged(heroLabel, "TIME UNTIL UNLOCK");
            setIfChanged(heroValue, KioskSnapshot.hms(until));
            setIfChanged(heroNote, "Unlocks at " + clockFmt.format(new Date(Engine.lockUntilMs(this, now))));
        } else if (reconfig) {
            setIfChanged(heroLabel, "AUTO-LOCK PAUSED");
            setIfChanged(heroValue, limit > 0L ? KioskSnapshot.hms(remaining) : "\u2014");
            setIfChanged(heroNote, "One-time reconfiguration window is open");
        } else if (limit > 0L) {
            long lockAt = now + remaining;
            setIfChanged(heroLabel, "TIME UNTIL LOCK");
            setIfChanged(heroValue, KioskSnapshot.hms(remaining));
            setIfChanged(heroNote, remaining <= 0L
                    ? "Limit reached \u2014 locking now"
                    : "Locks at " + clockFmt.format(new Date(lockAt)) + " if the screen stays on");
        } else {
            setIfChanged(heroLabel, "TIME UNTIL LOCK");
            setIfChanged(heroValue, "\u2014");
            setIfChanged(heroNote, "No limit is configured for today");
        }

        // ---- usage bar ----
        int permille = limit > 0L
                ? (int) Math.max(0L, Math.min(1000L, (used * 1000L) / limit)) : 0;
        applyWeight(barFill, permille);
        applyWeight(barTrack, 1000 - permille);
        int barColor = permille >= 1000 ? ui.danger : (permille >= 800 ? ui.warn : ui.accent);
        if (barColor != lastBarColor) {
            lastBarColor = barColor;
            ((GradientDrawable) barFill.getBackground()).setColor(barColor);
        }

        // ---- tiles ----
        setIfChanged(tileUsed, KioskSnapshot.shortDuration(used));
        setIfChanged(tileLimit, limit > 0L ? KioskSnapshot.shortDuration(limit) : "Not set");
        setIfChanged(tileLeft, limit > 0L ? KioskSnapshot.shortDuration(remaining) : "\u2014");
        setIfChanged(tileGuard, Prefs.warnSeconds(this) + "s");

        // ---- current status card ----
        setIfChanged(stLockState, locked ? "Locked" : "Not locked");
        setIfChanged(stAutoLock, reconfig ? "Paused"
                : (limit > 0L ? "Armed" : "Off"));
        setIfChanged(stTodayLimit, limit > 0L
                ? ScheduleConfig.human(limit / 60_000L) : "No lock today");
        setIfChanged(stUsed, KioskSnapshot.shortDuration(used)
                + (limit > 0L ? "  (" + (permille / 10) + "%)" : ""));
        setIfChanged(stLeft, limit > 0L ? KioskSnapshot.shortDuration(remaining) : "\u2014");
        setIfChanged(stReset, clockFmt.format(new Date(Engine.windowEnd(this, now))));
        setIfChanged(stAnchor, clockFmt.format(new Date(Engine.windowStart(this, now))));
        setIfChanged(stSchedule, ScheduleConfig.summary(this));

        // ---- enforcement card ----
        setIfChanged(stSetup, activated ? "Complete" : "Not complete");
        setIfChanged(stOwner, Engine.ownerLabel(this));
        setIfChanged(stEnforce, Engine.canEnforce(this) ? "Yes" : "No");
        setIfChanged(stSettings, settingsLocked ? "Locked in" : "Open");
        setIfChanged(stCountdown, Prefs.optCountdown(this) ? "On" : "Off");
        setIfChanged(stGuard, Prefs.optScreenOff(this) ? "On" : "Off");
        setIfChanged(stWarning, Prefs.warnSeconds(this) + " seconds");

        // ---- recent activity ----
        renderActivity(now);

        setIfChanged(updatedNote, "Live \u00b7 updated " + stampFmt.format(new Date(now)));
    }

    /**
     * Render the durable event trail. Purely informational: the ring is read,
     * never written, from this screen.
     */
    private void renderActivity(long now) {
        String ring = Prefs.logRing(this);
        List<KioskLogEvent> events = KioskLogParser.parseRing(ring, now);
        if (events == null || events.isEmpty()) {
            setIfChanged(actLast, "No events recorded yet");
            setIfChanged(actList, "");
            return;
        }
        KioskLogEvent newest = events.get(0);
        setIfChanged(actLast, newest.title
                + (newest.detail.isEmpty() ? "" : " \u2014 " + newest.detail)
                + (newest.hasTime() ? "  \u00b7  " + KioskSnapshot.relative(newest.atMs, now) : ""));

        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int i = 1; i < events.size() && shown < 6; i++) {
            KioskLogEvent e = events.get(i);
            if (e == null) continue;
            if (b.length() > 0) b.append('\n');
            b.append("\u2022  ").append(e.title);
            if (!e.detail.isEmpty()) b.append(" \u2014 ").append(e.detail);
            if (e.hasTime()) b.append("  \u00b7  ").append(KioskSnapshot.relative(e.atMs, now));
            shown++;
        }
        setIfChanged(actList, b.toString());
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
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Lock status");
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
        subtitle.setText("Read-only \u00b7 live status");
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
        tileGuard = addTile(tilesRow2, "LOCK WARNING");
        root.addView(tilesRow2);

        // ---- current status card ----
        LinearLayout status = card();
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = ui.dp(20);
        status.setLayoutParams(stLp);
        TextView statusTitle = caption();
        statusTitle.setText("CURRENT STATUS");
        status.addView(statusTitle);

        stLockState = configValue();
        status.addView(configRow("Lock state", stLockState));
        stAutoLock = configValue();
        status.addView(configRow("Auto-lock", stAutoLock));
        stTodayLimit = configValue();
        status.addView(configRow("Today's limit", stTodayLimit));
        stUsed = configValue();
        status.addView(configRow("Screen-on used", stUsed));
        stLeft = configValue();
        status.addView(configRow("Time left", stLeft));
        stReset = configValue();
        status.addView(configRow("Window resets at", stReset));
        stAnchor = configValue();
        status.addView(configRow("Window started at", stAnchor));
        stSchedule = configValue();
        status.addView(configRow("Weekly schedule", stSchedule));
        root.addView(status);

        // ---- enforcement card ----
        LinearLayout enforce = card();
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enLp.topMargin = ui.dp(16);
        enforce.setLayoutParams(enLp);
        TextView enforceTitle = caption();
        enforceTitle.setText("ENFORCEMENT");
        enforce.addView(enforceTitle);

        stSetup = configValue();
        enforce.addView(configRow("Setup", stSetup));
        stOwner = configValue();
        enforce.addView(configRow("Device owner", stOwner));
        stEnforce = configValue();
        enforce.addView(configRow("Can enforce lock", stEnforce));
        stSettings = configValue();
        enforce.addView(configRow("Settings lock", stSettings));
        stCountdown = configValue();
        enforce.addView(configRow("Status-bar countdown", stCountdown));
        stGuard = configValue();
        enforce.addView(configRow("Screen-off guard", stGuard));
        stWarning = configValue();
        enforce.addView(configRow("Warning before lock", stWarning));
        root.addView(enforce);

        // ---- recent activity card ----
        LinearLayout activity = card();
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = ui.dp(16);
        activity.setLayoutParams(acLp);
        TextView activityTitle = caption();
        activityTitle.setText("RECENT ACTIVITY");
        activity.addView(activityTitle);

        actLast = new TextView(this);
        actLast.setTextColor(ui.text);
        actLast.setTextSize(14.5f);
        actLast.setTypeface(Ui.bold());
        actLast.setPadding(0, ui.dp(10), 0, 0);
        activity.addView(actLast);

        actList = new TextView(this);
        actList.setTextColor(ui.textDim);
        actList.setTextSize(13f);
        actList.setLineSpacing(ui.dp(4), 1f);
        actList.setPadding(0, ui.dp(10), 0, 0);
        activity.addView(actList);
        root.addView(activity);

        // ---- read-only notice + live stamp ----
        TextView readOnly = new TextView(this);
        readOnly.setText("Read-only \u2014 this screen never changes your configuration.");
        readOnly.setTextColor(ui.textFaint);
        readOnly.setTextSize(12.5f);
        readOnly.setGravity(Gravity.CENTER);
        readOnly.setPadding(ui.dp(4), ui.dp(18), ui.dp(4), 0);
        root.addView(readOnly);

        updatedNote = new TextView(this);
        updatedNote.setTextColor(ui.textFaint);
        updatedNote.setTextSize(12f);
        updatedNote.setGravity(Gravity.CENTER);
        updatedNote.setPadding(0, ui.dp(8), 0, 0);
        root.addView(updatedNote);

        setContentView(scroll);
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
