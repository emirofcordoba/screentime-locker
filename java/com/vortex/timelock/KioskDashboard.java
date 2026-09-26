package com.vortex.timelock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * KioskDashboard — the elegant, human-readable projection of the kiosk log.
 *
 * <p><b>What it replaces.</b> The lock screen used to show a monospaced ASCII
 * terminal table ({@code +------+}, {@code TIME TO UNLOCK MATRIX},
 * {@code USED 04:52:00 82% of limit}). That is a developer's view: it is accurate
 * but unreadable, it reflows badly, and it redraws a wall of text every second.
 * This module takes the same underlying data — the raw event strings decoded by
 * {@link KioskLogParser} — and paints a dashboard a non-technical person
 * understands at a glance. There is no ASCII, no machine timestamp and no
 * {@code key:value} anywhere in the output; even the unlock time is spoken in
 * plain language:
 *
 * <pre>
 *   [mark] Screen Time                                   [ LOCKED ]
 *   -----------------------------------------------------
 *   TIME UNTIL UNLOCK
 *   01:23:45
 *   Unlocks today at 11:59 PM
 *   [========--------------------------]  82% of the daily limit used
 *
 *   [ SCREEN TIME USED ] [ DAILY LIMIT ]
 *   [ BUDGET LEFT     ] [ DISPLAY OFF ]
 *
 *   RECENT ACTIVITY                          4 events · 22:35
 *   * Lock engaged        Daily limit reached — the lock is enforced   2m ago
 *   * Navigation blocked  The kiosk consumed the Back key              6m ago
 * </pre>
 *
 * <p><b>Static by construction (the zero-battery contract).</b> Battery on this
 * device is spent by waking the panel, waking the CPU and redrawing pixels, in
 * that order. This view is built so it can never be the reason any of the three
 * happens:
 * <ul>
 *   <li>The whole view tree is created <em>once</em> in the constructor. There is
 *       no inflation, no adapter and no layout work at update time.</li>
 *   <li>The activity list is a <em>fixed pool</em> of pre-built rows that are
 *       shown/hidden. Rendering N events never allocates a View.</li>
 *   <li>{@link #bind} writes a {@link TextView} only when the string actually
 *       changed, so a redundant bind costs zero invalidations. No
 *       {@code Animation}, {@code ObjectAnimator}, {@code Choreographer} or
 *       {@link android.os.Handler} timer exists anywhere here: the host decides
 *       when to call {@code bind}, and the module never schedules its own wake-up.</li>
 *   <li>The usage bar is two weighted {@code View}s, not a {@code ProgressBar},
 *       because {@code ProgressBar} can animate {@code setProgress} on modern
 *       API levels. Two weights are a pure measure pass — no interpolator, no
 *       frame callbacks.</li>
 * </ul>
 * The module also draws nothing it does not need: the kiosk is always dark, so
 * there is no theme lookup and no configuration listener.
 */
final class KioskDashboard extends LinearLayout {

    // ---- kiosk palette (always dark, matches LockTheme) ----
    private static final int BG         = Color.parseColor("#070C14");
    private static final int CARD       = Color.parseColor("#131C2B");
    private static final int CARD2      = Color.parseColor("#1F2C3E");
    private static final int TXT        = Color.parseColor("#F5F9FF");
    private static final int MUTED      = Color.parseColor("#C2CEDE");
    private static final int FAINT      = Color.parseColor("#7C8AA0");
    private static final int ACCENT     = Color.parseColor("#5AC8FA");
    private static final int GOOD       = Color.parseColor("#34D399");
    private static final int WARN       = Color.parseColor("#FBBF24");
    private static final int DANGER     = Color.parseColor("#FB7185");
    private static final int BORDER     = Color.parseColor("#2C3B52");

    private static final int ACCENT_SOFT = Color.parseColor("#0A2A3A");
    private static final int GOOD_SOFT   = Color.parseColor("#08281C");
    private static final int WARN_SOFT   = Color.parseColor("#2E2200");
    private static final int DANGER_SOFT = Color.parseColor("#2C1315");

    /** Sentinel for "no tone baked into an activity dot yet". */
    private static final int TONE_NONE = 0;

    /**
     * THE NO-DATA SENTINEL, and the only legal seed for a numeric read-out.
     *
     * A countdown may be painted only from an instant the clock actually
     * produced. A literal {@code "00:00:00"} is not "no data" -- it is an
     * assertion that the budget is exhausted, and no reader (or host) can tell
     * it apart from a genuine expiry. So every surface without a value renders
     * this instead: it is what the tiles built by {@link #addTile} have always
     * seeded with, what the no-limit branch of {@link #bind} renders, and what
     * strings.xml encodes as policy (srv_no_limit / srv_limit_reached) rather
     * than as a zero clock.
     */
    private static final String SENTINEL = "\u2014";

    /** How many activity rows exist. Fixed: the pool is allocated once. */
    private static final int ACTIVITY_ROWS = 4;

    /**
     * Every wall-clock time the dashboard shows (the top lock clock, the
     * activity stamp, the "Unlocks ..." label) routes through {@link #wallFmt()},
     * which reads the user's own 12/24-hour system setting, so the kiosk never
     * contradicts the clock in the settings app.
     */
    /** "Wed" — only consulted when the unlock lands past tomorrow. */
    private final SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.US);

    // ---- lock clock (very top, phone-lock-screen style) ----
    private TextView lockClock;
    private TextView lockDate;
    /** Minute bucket already painted; the clock repaints at most once a minute. */
    private long clockMinute = -1L;
    /** Whether the last clock run rendered in 24-hour form (user's system setting). */
    private boolean clockIs24;
    private SimpleDateFormat clockFmt;
    private SimpleDateFormat dateFmt;

    // ---- battery (top-right corner, lock-screen style) ----
    private TextView batteryText;

    // ---- header ----
    private TextView statusPill;
    /** The pill state whose drawable is already installed (see {@link #setPill}). */
    private String pillBaked;

    // ---- hero ----
    private TextView heroLabel;
    private TextView heroValue;
    private TextView heroUnlockAt;
    private TextView heroBarNote;
    private View barFill;
    private View barTrack;

    // ---- tiles ----
    private TextView tileUsedValue;
    private TextView tileLimitValue;
    private TextView tileLeftValue;
    private TextView tileGuardValue;

    // ---- activity ----
    private TextView activityCount;
    private TextView activityEmpty;
    private final ActivityRow[] rows = new ActivityRow[ACTIVITY_ROWS];

    KioskDashboard(Context c) {
        this(c, null, 0);
    }

    KioskDashboard(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    KioskDashboard(Context c, AttributeSet a, int defStyleAttr) {
        super(c, a, defStyleAttr);
        buildStaticTree();
    }

    // =====================================================================
    //  Static construction — everything below runs once, at most
    // =====================================================================

    private void buildStaticTree() {
        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setBackgroundColor(BG);

        addView(buildLockClock());
        addView(buildHeader());
        addView(buildHeroCard());
        addView(buildTileGrid());
        addView(buildActivityCard());
        addView(buildFooter());
    }

    /**
     * The phone-lock-screen clock, pinned to the very top of the kiosk page: a
     * large wall-clock time over the weekday + date. It is the first thing a
     * person sees when the limit bites, exactly like the system lock screen.
     *
     * <p>Both lines are seeded empty and painted by {@link #renderClock} on the
     * first bind, so nothing flashes a stale or zero value. The 12/24-hour form
     * follows the user's own system choice ({@code Settings.System.TIME_12_24},
     * read through {@link DateFormat#is24HourFormat}), so the kiosk never
     * contradicts the clock in the settings app.
     */
    private View buildLockClock() {
        // A FrameLayout lets the centred clock and the top-right battery read-out
        // share the very top of the page without either disturbing the other's
        // layout: the clock column fills the width and centres itself, the battery
        // pill is pinned to the top-right corner on top of it.
        FrameLayout frame = new FrameLayout(getContext());
        frame.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), dp(10), dp(4), dp(20));
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lockClock = text("", 54, TXT, Typeface.NORMAL);
        lockClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        lockClock.setGravity(Gravity.CENTER);
        lockClock.setIncludeFontPadding(false);
        col.addView(lockClock);

        lockDate = text("", 15, MUTED, Typeface.NORMAL);
        lockDate.setGravity(Gravity.CENTER);
        lockDate.setPadding(0, dp(8), 0, 0);
        col.addView(lockDate);

        frame.addView(col);

        // Battery: seeded empty and hidden, then painted by renderBattery() on the
        // first bind that actually carries a level. Nothing ever flashes a "0%".
        batteryText = text("", 13, MUTED, Typeface.BOLD);
        batteryText.setTypeface(Ui.mono(), Typeface.BOLD);
        batteryText.setGravity(Gravity.CENTER);
        batteryText.setIncludeFontPadding(false);
        batteryText.setPadding(dp(11), dp(6), dp(11), dp(6));
        batteryText.setBackground(rounded(CARD2, 14, BORDER, 1));
        batteryText.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.topMargin = dp(12);
        blp.rightMargin = dp(6);
        frame.addView(batteryText, blp);

        return frame;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(16));

        TextView mark = text("\u23F1", 20, ACCENT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(ACCENT_SOFT, 14, BORDER, 1));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        mlp.rightMargin = dp(14);
        mark.setLayoutParams(mlp);
        row.addView(mark);

        LinearLayout titles = new LinearLayout(getContext());
        titles.setOrientation(VERTICAL);
        titles.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        titles.addView(text("Screen Time", 20, TXT, Typeface.BOLD));
        TextView sub = text("Daily limit enforced by this device", 12.5f, FAINT, Typeface.NORMAL);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        row.addView(titles);

        statusPill = text("LOCKED", 11, DANGER, Typeface.BOLD);
        statusPill.setLetterSpacing(0.10f);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusPill.setBackground(rounded(DANGER_SOFT, 20, 0, 0));
        row.addView(statusPill);

        return row;
    }

    private View buildHeroCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(rounded(CARD, 24, BORDER, 1));
        card.setPadding(dp(22), dp(20), dp(22), dp(20));

        heroLabel = text("TIME UNTIL UNLOCK", 11, ACCENT, Typeface.BOLD);
        heroLabel.setLetterSpacing(0.14f);
        card.addView(heroLabel);

        // NO-ZERO SEED. The hero read-out must never be born holding a clock.
        // A literal "00:00:00" here is indistinguishable from a real, expired
        // budget, so any frame drawn before the first bind() -- and any host that
        // renders the dashboard without binding it -- would flash a false
        // "time is up" state. The seed is therefore the same no-data sentinel the
        // unbound tiles use (addTile) and the no-limit branch of bind() renders
        // ("\u2014", see strings.xml: srv_no_limit / srv_limit_reached policy).
        // bind() overwrites it on the very first tick with the authoritative value.
        heroValue = text(SENTINEL, 42, TXT, Typeface.BOLD);
        heroValue.setTypeface(Ui.mono(), Typeface.BOLD);
        heroValue.setIncludeFontPadding(false);
        heroValue.setPadding(0, dp(8), 0, dp(6));
        card.addView(heroValue);

        // Neutral subtitle: seeding it with an assertion ("Not locked") can
        // contradict the LOCKED pill above until the first bind() lands. Empty is
        // the same not-yet-bound idiom buildHeroCard()'s bar note uses.
        heroUnlockAt = text("", 14, GOOD, Typeface.BOLD);
        card.addView(heroUnlockAt);

        // ---- usage bar: two weighted Views (never animates) ----
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setBackground(rounded(CARD2, 6, 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        blp.topMargin = dp(18);
        bar.setLayoutParams(blp);

        barTrack = new View(getContext());
        barTrack.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1000f));
        bar.addView(barTrack);

        barFill = new View(getContext());
        barFill.setBackground(rounded(DANGER, 6, 0, 0));
        barFill.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        bar.addView(barFill);
        card.addView(bar);

        heroBarNote = text("", 12.5f, MUTED, Typeface.NORMAL);
        heroBarNote.setPadding(0, dp(10), 0, 0);
        card.addView(heroBarNote);

        return card;
    }

    private View buildTileGrid() {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(12);
        grid.setLayoutParams(glp);

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(HORIZONTAL);
        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams btm = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btm.topMargin = dp(10);
        bottom.setLayoutParams(btm);

        tileUsedValue  = addTile(top, "SCREEN TIME USED", 0);
        tileLimitValue = addTile(top, "DAILY LIMIT", 1);
        tileLeftValue  = addTile(bottom, "BUDGET LEFT", 0);
        tileGuardValue = addTile(bottom, "DISPLAY OFF", 1);

        grid.addView(top);
        grid.addView(bottom);
        return grid;
    }

    /** One stat tile: a caption above a value. Returns the value TextView. */
    private TextView addTile(LinearLayout row, String caption, int half) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setBackground(rounded(CARD2, 18, BORDER, 1));
        tile.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (half == 0) tlp.rightMargin = dp(5); else tlp.leftMargin = dp(5);
        tile.setLayoutParams(tlp);

        TextView cap = text(caption, 10.5f, FAINT, Typeface.BOLD);
        cap.setLetterSpacing(0.08f);
        tile.addView(cap);

        TextView value = text("—", 17, TXT, Typeface.BOLD);
        value.setTypeface(Ui.mono(), Typeface.BOLD);
        value.setPadding(0, dp(6), 0, 0);
        tile.addView(value);

        row.addView(tile);
        return value;
    }

    private View buildActivityCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(rounded(CARD, 20, BORDER, 1));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        card.setLayoutParams(clp);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("RECENT ACTIVITY", 11, ACCENT, Typeface.BOLD);
        title.setLetterSpacing(0.14f);
        title.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        activityCount = text("", 11, FAINT, Typeface.NORMAL);
        activityCount.setGravity(Gravity.RIGHT);
        head.addView(activityCount);
        card.addView(head);

        activityEmpty = text("No kiosk activity recorded yet.", 12.5f, FAINT, Typeface.NORMAL);
        activityEmpty.setPadding(0, dp(14), 0, dp(6));
        card.addView(activityEmpty);

        for (int i = 0; i < ACTIVITY_ROWS; i++) {
            rows[i] = new ActivityRow();
            card.addView(rows[i].container);
        }
        return card;
    }

    private View buildFooter() {
        TextView foot = text("Nothing to tap — the device unlocks on its own.",
                12, FAINT, Typeface.NORMAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(14), 0, dp(4));
        foot.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return foot;
    }

    /**
     * One pre-built activity row. Created once, then only ever re-texted and
     * shown/hidden, so updating the list costs no allocation and no inflation.
     */
    private final class ActivityRow {
        final LinearLayout container;
        final View dot;
        final TextView title;
        final TextView detail;
        final TextView time;
        /** Tone currently painted on the dot; {@link #TONE_NONE} until first show. */
        int dotBaked = TONE_NONE;

        ActivityRow() {
            container = new LinearLayout(getContext());
            container.setOrientation(VERTICAL);
            container.setVisibility(View.GONE);

            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setPadding(0, dp(11), 0, dp(11));

            dot = new View(getContext());
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(FAINT);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dlp.rightMargin = dp(12);
            dlp.topMargin = dp(5);
            dot.setLayoutParams(dlp);
            line.addView(dot);

            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(VERTICAL);
            body.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            title = text("—", 14, TXT, Typeface.BOLD);
            title.setMaxLines(1);
            body.addView(title);

            detail = text("", 12, MUTED, Typeface.NORMAL);
            detail.setMaxLines(2);
            detail.setPadding(0, dp(2), 0, 0);
            body.addView(detail);

            line.addView(body);

            time = text("", 11, FAINT, Typeface.NORMAL);
            time.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(10);
            time.setLayoutParams(tlp);
            line.addView(time);

            container.addView(line);

            View divider = new View(getContext());
            divider.setBackgroundColor(BORDER);
            divider.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            container.addView(divider);
        }

        void show(KioskLogEvent e, long nowMs) {
            setIfChanged(title, e.title);
            setIfChanged(detail, e.detail);
            setIfChanged(time, KioskSnapshot.relative(e.atMs, nowMs));
            int tone = toneColor(e.tone);
            if (dotBaked != tone) {
                dotBaked = tone;
                ((GradientDrawable) dot.getBackground()).setColor(tone);
            }
            if (container.getVisibility() != View.VISIBLE) {
                container.setVisibility(View.VISIBLE);
            }
        }

        void hide() {
            container.setVisibility(View.GONE);
        }
    }

    // =====================================================================
    //  Update path
    // =====================================================================

    /**
     * Paint one snapshot. Cheap and idempotent: every write is diffed first, so
     * calling this with unchanged data performs no invalidation at all. The host
     * chooses the cadence; this module never posts a delayed message.
     */
    void bind(KioskSnapshot s) {
        if (s == null) return;

        // ---- lock clock (very top) ----
        renderClock(s.nowMs);

        // ---- battery (top-right corner) ----
        renderBattery(s);

        // ---- status pill ----
        if (s.locked) {
            setPill("LOCKED", DANGER, DANGER_SOFT);
        } else if (s.limitMs > 0L) {
            setPill("ACTIVE", ACCENT, ACCENT_SOFT);
        } else {
            setPill("NO LIMIT", WARN, WARN_SOFT);
        }

        // ---- hero: the same big number, worded for whichever state we are in --
        if (s.locked) {
            setIfChanged(heroLabel, "TIME UNTIL UNLOCK");
            setIfChanged(heroValue, KioskSnapshot.hms(s.remainingMs));
            setIfChanged(heroUnlockAt, unlockLabel(s.unlockAtMs, s.nowMs));
            setColorIfChanged(heroUnlockAt, GOOD);
        } else if (s.limitMs > 0L) {
            setIfChanged(heroLabel, "TIME LEFT TODAY");
            setIfChanged(heroValue, KioskSnapshot.hms(s.budgetLeftMs()));
            setIfChanged(heroUnlockAt, "Screen time is available right now");
            setColorIfChanged(heroUnlockAt, ACCENT);
        } else {
            setIfChanged(heroLabel, "TIME REMAINING");
            setIfChanged(heroValue, SENTINEL);
            setIfChanged(heroUnlockAt, "No daily limit is configured");
            setColorIfChanged(heroUnlockAt, ACCENT);
        }

        // ---- usage bar (weights: track = remainder, fill = consumed) ----
        int permille = s.permille();
        applyWeight(barFill, permille);
        applyWeight(barTrack, 1000 - permille);
        int barColor = permille >= 1000 ? DANGER : (permille >= 800 ? WARN : ACCENT);
        ((GradientDrawable) barFill.getBackground()).setColor(barColor);

        if (s.limitMs <= 0L) {
            setIfChanged(heroBarNote, "No daily limit is set for today");
        } else {
            setIfChanged(heroBarNote, (permille / 10) + "% of the daily limit used  ·  "
                    + KioskSnapshot.shortDuration(s.usedMs) + " of "
                    + KioskSnapshot.shortDuration(s.limitMs));
        }

        // ---- tiles ----
        setIfChanged(tileUsedValue, KioskSnapshot.shortDuration(s.usedMs));
        setIfChanged(tileLimitValue, s.limitMs > 0L
                ? KioskSnapshot.shortDuration(s.limitMs) : "Not set");
        setIfChanged(tileLeftValue, s.limitMs > 0L
                ? KioskSnapshot.shortDuration(s.budgetLeftMs()) : SENTINEL);
        setIfChanged(tileGuardValue, guardLabel(s.guardSeconds));

        // ---- activity ----
        List<KioskLogEvent> events = s.events;
        int shown = 0;
        for (int i = 0; i < ACTIVITY_ROWS; i++) {
            if (i < events.size() && events.get(i) != null) {
                rows[i].show(events.get(i), s.nowMs);
                shown++;
            } else {
                rows[i].hide();
            }
        }
        activityEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        setIfChanged(activityCount, shown == 0
                ? ""
                : shown + (shown == 1 ? " event" : " events")
                        + " \u00b7 " + wallFmt().format(new Date(s.nowMs)));
    }

    // =====================================================================
    //  Lock clock
    // =====================================================================

    /**
     * Wall-clock formatter honouring the user's 12/24-hour setting, rebuilt only
     * when the hour form actually flips. Shared by the top clock, the activity
     * stamp and the unlock label, so every time the kiosk prints agrees. Callable
     * from any render path, not just {@link #renderClock}, so a change made in the
     * settings app is picked up on the very next bind without waiting a minute.
     */
    private SimpleDateFormat wallFmt() {
        boolean h24 = TimeFmt.is24(getContext());
        if (clockFmt == null || h24 != clockIs24) {
            clockIs24 = h24;
            clockFmt = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.US);
            dateFmt  = new SimpleDateFormat("EEEE, d MMMM", Locale.US);
        }
        return clockFmt;
    }

    /**
     * Paint the top clock, but at most once per wall-clock minute.
     *
     * <p>{@code bind} runs at 1 Hz; the clock only changes when the minute rolls
     * over, so anything more frequent would repaint identical pixels for nothing.
     * The diff guard also means a 12/24-hour change made in the settings app is
     * picked up within a minute of the next tick without polling the provider
     * every second. Formats are rebuilt only when the hour form actually flips.
     */
    private void renderClock(long nowMs) {
        long minute = nowMs / 60_000L;
        if (minute == clockMinute) return;
        clockMinute = minute;

        SimpleDateFormat fmt = wallFmt();
        Date now = new Date(nowMs);
        setIfChanged(lockClock, fmt.format(now));
        setIfChanged(lockDate, dateFmt.format(now));
    }

    // =====================================================================
    //  Battery (top-right corner)
    // =====================================================================

    /**
     * Paint the top-right battery read-out. The pill shows "82%" (prefixed with a
     * charging bolt while plugged in), tinted by charge level. Like every other
     * write in {@link #bind}, the string and the colour are diffed first, so a tick
     * that changes no charge performs no invalidation.
     *
     * <p>A level of -1 means "unknown" (for example a device whose battery HAL does
     * not answer the sticky broadcast). In that case the pill is hidden rather than
     * printing a false "0%", matching the no-data sentinel policy used elsewhere.
     */
    private void renderBattery(KioskSnapshot s) {
        if (batteryText == null) return;

        if (s.batteryPct < 0) {
            if (batteryText.getVisibility() != View.GONE) {
                batteryText.setVisibility(View.GONE);
            }
            return;
        }
        if (batteryText.getVisibility() != View.VISIBLE) {
            batteryText.setVisibility(View.VISIBLE);
        }

        setIfChanged(batteryText, (s.charging ? "\u26A1 " : "") + s.batteryPct + "%");
        int color = s.charging ? GOOD
                : s.batteryPct <= 15 ? DANGER
                : s.batteryPct <= 30 ? WARN
                : MUTED;
        setColorIfChanged(batteryText, color);
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /**
     * Repaint the status pill only when its state actually changes. The rounded
     * background is a fresh {@link GradientDrawable}, so building it on every
     * bind would allocate and invalidate once per second for nothing; the state
     * label is the cache key instead.
     */
    private void setPill(String label, int fg, int bg) {
        if (label.equals(pillBaked)) return;
        pillBaked = label;
        statusPill.setText(label);
        statusPill.setTextColor(fg);
        statusPill.setBackground(rounded(bg, 20, 0, 0));
    }

    private static void applyWeight(View v, int weight) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LayoutParams) {
            float w = Math.max(0, weight);
            if (((LayoutParams) lp).weight != w) {
                ((LayoutParams) lp).weight = w;
                v.setLayoutParams(lp);
            }
        }
    }

    private static void setIfChanged(TextView t, String value) {
        String current = t.getText() == null ? null : t.getText().toString();
        if (current == null || !current.equals(value)) t.setText(value);
    }

    /** Writes a text colour only when it actually differs, so a steady state
     *  performs no invalidate-on-set work at all. */
    private static void setColorIfChanged(TextView t, int color) {
        if (t.getCurrentTextColor() != color) t.setTextColor(color);
    }

    /**
     * "Unlocks today at 11:59 PM" / "Unlocks Wed at 11:59 PM", or "Unlocks today
     * at 23:59" on a 24-hour device — never an ISO stamp. The hour form follows
     * the user's own system 12/24-hour setting (via {@link #wallFmt()}), so the
     * unlock instant reads exactly as the phone's own clock would print it.
     */
    private String unlockLabel(long atMs, long nowMs) {
        if (atMs <= 0L) return "Unlocks once the timer finishes";
        String time = wallFmt().format(new Date(atMs));
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(atMs);
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(nowMs);
        if (sameDay(a, n)) return "Unlocks today at " + time;
        n.add(Calendar.DAY_OF_YEAR, 1);
        if (sameDay(a, n)) return "Unlocks tomorrow at " + time;
        return "Unlocks " + weekday.format(new Date(atMs)) + " at " + time;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String guardLabel(int guardSeconds) {
        if (guardSeconds < 0) return "Off";
        if (guardSeconds <= 0) return "Now";
        return guardSeconds + "s";
    }

    private static int toneColor(KioskLogEvent.Tone tone) {
        switch (tone) {
            case GOOD:   return GOOD;
            case WARN:   return WARN;
            case DANGER: return DANGER;
            case ACCENT: return ACCENT;
            default:     return FAINT;
        }
    }

    private TextView text(String value, float spSize, int color, int style) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        t.setTypeface(Typeface.create("sans-serif-medium", style));
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable rounded(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0 && strokeDp > 0f) g.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return g;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
