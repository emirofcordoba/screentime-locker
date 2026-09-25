package com.vortex.timelock;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * The weekly-schedule control.
 *
 * One row per weekday, each offering three explicit modes — Daily (inherit),
 * No lock, and Custom (a reduced interval revealed inline with a full
 * {@link IntervalSelector}). Sunday is listed first to match
 * {@link Calendar#DAY_OF_WEEK}.
 *
 * This is a pure view layer over {@link ScheduleConfig}: it never keeps
 * authoritative state of its own, it always reads back from Prefs, so a
 * configuration change (or a theme rebuild) can never desynchronise the UI from
 * what the engine actually enforces.
 */
class WeekdayScheduleCard extends LinearLayout {

    private final Ui ui;
    private final DayRow[] rows = new DayRow[8];
    private Runnable onChanged;

    WeekdayScheduleCard(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        setOrientation(VERTICAL);
        build();
    }

    void setOnChanged(Runnable r) { this.onChanged = r; }

    private void fireChanged() { if (onChanged != null) onChanged.run(); }

    /** Reset every day back to the daily interval. */
    void resetAll() {
        ScheduleConfig.reset(getContext());
        refreshValues();
        fireChanged();
    }

    /** Re-read every row from Prefs without firing a change callback. */
    void refreshValues() {
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            if (rows[d] != null) rows[d].refresh();
        }
    }

    private void build() {
        for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) {
            DayRow row = new DayRow(getContext(), ui, d);
            row.setOnChanged(this::fireChanged);
            rows[d] = row;
            addView(row);
            if (d != Calendar.SATURDAY) addView(divider());
        }
    }

    private View divider() {
        View v = new View(getContext());
        v.setBackgroundColor(ui.border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1)));
        lp.topMargin = ui.dp(6);
        lp.bottomMargin = ui.dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- one day

    private static final class DayRow extends LinearLayout {

        private final Ui ui;
        private final int dow;
        private final TextView value;
        private final Button bDaily, bOff, bCustom;
        private final LinearLayout customBox;
        private final IntervalSelector customSel;
        private Runnable onChanged;

        DayRow(Context c, Ui ui, int dow) {
            super(c);
            this.ui = ui;
            this.dow = dow;
            setOrientation(VERTICAL);
            setPadding(0, ui.dp(10), 0, ui.dp(10));

            // ---- header: weekday name + current effective value ----
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(c);
            name.setText(ScheduleConfig.dayName(dow, false));
            name.setTextColor(ui.text);
            name.setTextSize(15f);
            name.setTypeface(Ui.bold());
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(name);

            value = new TextView(c);
            value.setTextSize(13f);
            value.setTypeface(Ui.bold());
            value.setGravity(Gravity.END);
            head.addView(value);
            addView(head);

            // ---- mode selector ----
            LinearLayout modes = new LinearLayout(c);
            modes.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = ui.dp(8);
            modes.setLayoutParams(mlp);

            bDaily = modeButton("Daily");
            bOff = modeButton("No lock");
            bCustom = modeButton("Custom");

            bDaily.setOnClickListener(v -> {
                ScheduleConfig.inherit(getContext(), dow);
                refresh();
                notifyChanged();
            });
            bOff.setOnClickListener(v -> {
                ScheduleConfig.disable(getContext(), dow);
                refresh();
                notifyChanged();
            });
            bCustom.setOnClickListener(v -> enableCustom());

            modes.addView(bDaily, weight(0, ui.dp(8)));
            modes.addView(bOff, weight(ui.dp(8), ui.dp(8)));
            modes.addView(bCustom, weight(ui.dp(8), 0));
            addView(modes);

            // ---- inline custom interval (only visible in Custom mode) ----
            customBox = new LinearLayout(c);
            customBox.setOrientation(VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = ui.dp(10);
            customBox.setLayoutParams(clp);

            int initialMin = (int) Math.max(1L,
                    Math.min(1440L, ScheduleConfig.storedLimitMs(c, dow) / 60_000L));
            customSel = new IntervalSelector(c, ui, "Reduced interval",
                    1, 1440, 15, "min", initialMin);
            customSel.setFormatter(ScheduleConfig::human);
            customSel.addPresetRow(new int[]{15, 30, 45, 60, 90, 120}, 3);
            customSel.setOnChanged(v -> {
                ScheduleConfig.reduce(getContext(), dow, v);
                refresh();
                notifyChanged();
            });
            customBox.addView(customSel);
            addView(customBox);

            refresh();
        }

        void setOnChanged(Runnable r) { this.onChanged = r; }

        private void notifyChanged() { if (onChanged != null) onChanged.run(); }

        /**
         * Switch the day to Custom. When it was not already custom, seed the
         * reduced interval with half of the daily allowance (a "lighter day"),
         * floor 15 min, but never above the daily interval itself.
         */
        private void enableCustom() {
            if (ScheduleConfig.mode(getContext(), dow) != ScheduleConfig.MODE_CUSTOM) {
                long base = Prefs.limitMs(getContext());
                int mins = base > 0 ? (int) (base / 60_000L) : 120;
                int seed = Math.max(15, Math.min(1440, mins / 2));
                customSel.setValue(seed);
                ScheduleConfig.reduce(getContext(), dow, seed);
            }
            refresh();
            notifyChanged();
        }

        /** Push the current Prefs configuration into every widget. */
        void refresh() {
            int mode = ScheduleConfig.mode(getContext(), dow);

            value.setText(ScheduleConfig.describe(getContext(), dow));
            switch (mode) {
                case ScheduleConfig.MODE_OFF:
                    value.setTextColor(ui.warn);
                    break;
                case ScheduleConfig.MODE_CUSTOM:
                    value.setTextColor(ui.accent);
                    break;
                default:
                    value.setTextColor(ui.textDim);
                    break;
            }

            style(bDaily, mode == ScheduleConfig.MODE_INHERIT, ui.accent);
            style(bOff, mode == ScheduleConfig.MODE_OFF, ui.warn);
            style(bCustom, mode == ScheduleConfig.MODE_CUSTOM, ui.accent);

            if (mode == ScheduleConfig.MODE_CUSTOM) {
                long stored = ScheduleConfig.storedLimitMs(getContext(), dow);
                if (stored > 0) {
                    customSel.setValue((int) Math.min(1440L, Math.max(1L, stored / 60_000L)));
                }
                customBox.setVisibility(VISIBLE);
            } else {
                customBox.setVisibility(GONE);
            }
        }

        private void style(Button b, boolean selected, int tint) {
            b.setTextColor(selected ? ui.onAccent : ui.text);
            b.setBackground(ui.rounded(selected ? tint : ui.surfaceAlt, 12, ui.border, 1.5f));
        }

        private LinearLayout.LayoutParams weight(int left, int right) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = left;
            lp.rightMargin = right;
            return lp;
        }

        private Button modeButton(String s) {
            Button b = new Button(getContext());
            b.setText(s);
            b.setAllCaps(false);
            b.setTextSize(13f);
            b.setTextColor(ui.text);
            b.setTypeface(Ui.bold());
            b.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setStateListAnimator(null);
            return b;
        }
    }
}
