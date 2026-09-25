package com.vortex.timelock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Adjustable interval selector.
 *
 * A self-contained, theme-aware control that lets the user dial in an interval
 * (or any bounded integer) three ways at once:
 *
 *   1. a slider for fast coarse movement,
 *   2. + / − steppers for single-step nudges, and
 *   3. a numeric field for exact manual entry of any value in range.
 *
 * The three inputs stay in sync; the slider and steppers snap to the configured
 * step, while the manual field accepts any in-range integer so the operator can
 * set a precise restriction value. It is used for the daily screen-time interval
 * and for the screen-off warning interval.
 */
class IntervalSelector extends LinearLayout {

    /** Callback for a user-driven change. */
    interface OnChanged {
        void onChanged(int value);
    }

    /** Optional formatter so the header badge can show a friendly label. */
    interface ValueFormat {
        String format(int value);
    }

    private final Ui ui;
    private final int min, max, step;
    private final String caption, unit;

    private SeekBar bar;
    private EditText manual;
    private TextView badge, scaleLo, scaleHi, fieldUnit;
    private Button minus, plus;

    private int value;
    private boolean syncing;
    private OnChanged cb;
    private ValueFormat fmt;

    IntervalSelector(Context c, Ui ui, String caption,
                     int min, int max, int step, String unit, int initial) {
        super(c);
        this.ui = ui;
        this.caption = caption;
        this.min = min;
        this.max = Math.max(max, min);
        this.step = Math.max(1, step);
        this.unit = unit;
        this.value = snap(initial);

        setOrientation(VERTICAL);
        build();
    }

    // ------------------------------------------------------------- public API

    int getValue() { return value; }

    void setOnChanged(OnChanged l) { this.cb = l; }

    void setFormatter(ValueFormat f) { this.fmt = f; }

    /** Programmatic (silent) set. */
    void setValue(int v) {
        int nv = clamp(v, min, max);
        if (nv == value) { refresh(); return; }
        value = nv;
        refresh();
    }

    /** Add a row of quick-pick chips beneath the controls. */
    void addPresetRow(final int[] presets, int perRow) {
        if (presets == null || presets.length == 0) return;
        int cols = Math.max(1, perRow);

        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = ui.dp(10);
        wrap.setLayoutParams(wlp);

        for (int i = 0; i < presets.length; i += cols) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                if (idx >= presets.length) {
                    View sp = new View(getContext());
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    slp.rightMargin = ui.dp(8);
                    sp.setLayoutParams(slp);
                    row.addView(sp);
                    continue;
                }
                final int pv = presets[idx];
                Button chip = chip(labelFor(pv));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.rightMargin = ui.dp(8);
                clp.topMargin = ui.dp(8);
                chip.setLayoutParams(clp);
                chip.setOnClickListener(v -> {
                    setValue(pv);
                    notifyChanged();
                });
                row.addView(chip);
            }
            wrap.addView(row);
        }
        addView(wrap);
    }

    // ---------------------------------------------------------------- building

    private void build() {
        // caption + live badge
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView cap = new TextView(getContext());
        cap.setText(caption);
        cap.setTextColor(ui.text);
        cap.setTextSize(14.5f);
        cap.setTypeface(Ui.bold());
        cap.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(cap);

        badge = new TextView(getContext());
        badge.setTextColor(ui.onAccent);
        badge.setTextSize(15f);
        badge.setTypeface(Ui.bold());
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
        badge.setBackground(ui.rounded(ui.accent, 10));
        head.addView(badge);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = ui.dp(14);
        hlp.bottomMargin = ui.dp(2);
        head.setLayoutParams(hlp);
        addView(head);

        // slider
        bar = new SeekBar(getContext());
        int steps = Math.max(1, (max - min) / step);
        bar.setMax(steps);
        bar.setSplitTrack(false);
        bar.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(2));
        bar.setProgressTintList(ColorStateList.valueOf(ui.accent));
        bar.setThumbTintList(ColorStateList.valueOf(ui.accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(ui.track));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || syncing) return;
                value = clamp(min + progress * step, min, max);
                syncFromModel(true, false);
                notifyChanged();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        addView(bar);

        // scale endpoints
        LinearLayout scale = new LinearLayout(getContext());
        scale.setOrientation(HORIZONTAL);
        scaleLo = faint(labelFor(min), Gravity.START);
        scaleHi = faint(labelFor(max), Gravity.END);
        scaleLo.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scaleHi.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scaleHi.setGravity(Gravity.END);
        scale.addView(scaleLo);
        scale.addView(scaleHi);
        addView(scale);

        // steppers + manual entry
        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ui.dp(12);
        controls.setLayoutParams(clp);

        minus = stepButton("\u2212");
        minus.setOnClickListener(v -> nudge(-step));
        controls.addView(minus);

        LinearLayout fieldWrap = new LinearLayout(getContext());
        fieldWrap.setOrientation(HORIZONTAL);
        fieldWrap.setGravity(Gravity.CENTER_VERTICAL);
        fieldWrap.setBackground(ui.rounded(ui.field, 14, ui.border, 1.5f));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ui.dp(56), 1f);
        flp.leftMargin = ui.dp(10);
        flp.rightMargin = ui.dp(10);
        fieldWrap.setLayoutParams(flp);

        manual = new EditText(getContext());
        manual.setInputType(InputType.TYPE_CLASS_NUMBER);
        manual.setTextColor(ui.text);
        manual.setTextSize(19f);
        manual.setTypeface(Ui.bold());
        manual.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        manual.setBackgroundColor(0x00000000);
        manual.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        manual.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6));
        manual.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        manual.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (syncing) return;
                int v = parse(s.toString());
                if (v < 0) return;
                value = clamp(v, min, max);
                syncing = true;
                try {
                    bar.setProgress((value - min) / step);
                    badge.setText(labelFor(value));
                } finally { syncing = false; }
                notifyChanged();
            }
            public void afterTextChanged(Editable s) { }
        });
        manual.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) syncFromModel(true, true);
        });
        fieldWrap.addView(manual);

        fieldUnit = new TextView(getContext());
        fieldUnit.setText(unit);
        fieldUnit.setTextColor(ui.textDim);
        fieldUnit.setTextSize(13f);
        fieldUnit.setPadding(0, 0, ui.dp(14), 0);
        fieldWrap.addView(fieldUnit);
        controls.addView(fieldWrap);

        plus = stepButton("+");
        plus.setOnClickListener(v -> nudge(+step));
        controls.addView(plus);

        addView(controls);

        syncFromModel(true, true);
    }

    // ---------------------------------------------------------------- internals

    private void nudge(int delta) {
        setValue(clamp(value + delta, min, max));
        notifyChanged();
    }

    private void notifyChanged() {
        if (cb != null) cb.onChanged(value);
    }

    /** Push model -> widgets. */
    private void syncFromModel(boolean updateManual, boolean updateBar) {
        syncing = true;
        try {
            if (updateBar) bar.setProgress((value - min) / step);
            if (updateManual) manual.setText(String.format(Locale.US, "%d", value));
            badge.setText(labelFor(value));
            boolean atMin = value <= min, atMax = value >= max;
            if (minus != null) minus.setEnabled(!atMin);
            if (plus != null) plus.setEnabled(!atMax);
        } finally {
            syncing = false;
        }
    }

    private void refresh() { syncFromModel(true, true); }

    private int snap(int v) {
        int c = clamp(v, min, max);
        return clamp(min + Math.round((c - min) / (float) step) * step, min, max);
    }

    private String labelFor(int v) {
        return fmt != null ? fmt.format(v) : (v + " " + unit);
    }

    private static int parse(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (Throwable t) { return -1; }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // -------------------------------------------------------------- widgets

    private TextView faint(String s, int gravity) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(ui.textFaint);
        t.setTextSize(12f);
        t.setGravity(gravity);
        return t;
    }

    private Button stepButton(String s) {
        Button b = new Button(getContext());
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(24f);
        b.setTextColor(ui.text);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(ui.surfaceAlt, 14, ui.border, 1.5f));
        b.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }

    private Button chip(String s) {
        Button b = new Button(getContext());
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(ui.text);
        b.setTypeface(Ui.bold());
        b.setBackground(ui.rounded(ui.surfaceAlt, 12, ui.border, 1.5f));
        b.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }
}
