package com.vortex.timelock;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The kiosk's Phone tab: a full dialer that never leaves the kiosk.
 *
 * <p>Layout, top to bottom: a caption, the number display, a 3x4 keypad, a
 * control row (backspace / Call / Message), then a scrollable list that is either
 * the recent call log or — once the search field has text — typeahead results
 * from the address book.
 *
 * <p><b>Deliberately not a telephony client.</b> This screen never places a call
 * itself: it hands the number to {@link com.vortex.timelock.KioskShell.Host}, so
 * the host can drop lock-task, let the platform's own in-call UI take over, and
 * re-assert the kiosk when the call ends. Tapping a recent row dials it; the ✉
 * chip on a row opens that address in the Messages tab.
 *
 * <p>Every provider read (recents, contacts) is best-effort and wrapped upstream,
 * so an unreadable log or address book degrades to an empty list rather than a
 * blank or broken screen.
 */
final class DialerScreen extends LinearLayout implements KioskShell.Screen {

    private final KioskShell.Host host;
    private final TextView display;
    private final TextView section;
    private final EditText search;
    private final LinearLayout listContainer;
    private final StringBuilder typed = new StringBuilder();

    DialerScreen(Context c, KioskShell.Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(KioskUi.BG);

        // ---- caption ---------------------------------------------------------
        TextView cap = KioskUi.caption(c, "PHONE");
        cap.setPadding(KioskUi.dp(c, 18), KioskUi.dp(c, 14), KioskUi.dp(c, 18), 0);
        addView(cap, KioskUi.matchWrap());

        // ---- number display ---------------------------------------------------
        display = KioskUi.mono(c, "", 30, KioskUi.FAINT, Typeface.BOLD);
        display.setGravity(Gravity.END);
        display.setSingleLine(true);
        display.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout card = KioskUi.row(c);
        card.setBackground(KioskUi.round(c, KioskUi.CARD, 18, KioskUi.BORDER, 1));
        card.setPadding(KioskUi.dp(c, 16), KioskUi.dp(c, 14), KioskUi.dp(c, 16), KioskUi.dp(c, 14));
        card.setMinimumHeight(KioskUi.dp(c, 44));
        card.addView(display, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cardLp = KioskUi.matchWrap();
        cardLp.leftMargin = KioskUi.dp(c, 18);
        cardLp.rightMargin = KioskUi.dp(c, 18);
        cardLp.topMargin = KioskUi.dp(c, 10);
        addView(card, cardLp);
        setDisplay();

        // ---- keypad -----------------------------------------------------------
        LinearLayout keypad = KioskUi.column(c);
        LinearLayout.LayoutParams kpLp = KioskUi.matchWrap();
        kpLp.leftMargin = KioskUi.dp(c, 12);
        kpLp.rightMargin = KioskUi.dp(c, 12);
        kpLp.topMargin = KioskUi.dp(c, 12);
        addView(keypad, kpLp);

        String[][] rows = {
                {"1", ""}, {"2", "ABC"}, {"3", "DEF"},
                {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
                {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
                {"*", ""}, {"0", "+"}, {"#", ""}
        };
        for (int r = 0; r < 4; r++) {
            LinearLayout row = KioskUi.row(c);
            if (r > 0) {
                LinearLayout.LayoutParams gap = KioskUi.matchWrap();
                gap.topMargin = KioskUi.dp(c, 8);
                keypad.addView(row, gap);
            } else {
                keypad.addView(row, KioskUi.matchWrap());
            }
            for (int k = 0; k < 3; k++) {
                String[] cell = rows[r * 3 + k];
                View key = key(c, cell[0], cell[1]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, KioskUi.dp(c, 48), 1f);
                if (k > 0) lp.leftMargin = KioskUi.dp(c, 8);
                row.addView(key, lp);
            }
        }

        // ---- control row: backspace / Call / Message --------------------------
        LinearLayout controls = KioskUi.row(c);
        LinearLayout.LayoutParams ctlLp = KioskUi.matchWrap();
        ctlLp.leftMargin = KioskUi.dp(c, 12);
        ctlLp.rightMargin = KioskUi.dp(c, 12);
        ctlLp.topMargin = KioskUi.dp(c, 10);
        addView(controls, ctlLp);

        controls.addView(ctrl(c, "\u232B", KioskUi.CARD2, KioskUi.MUTED, 0.8f,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { backspace(); }
                }));

        LinearLayout call = KioskUi.row(c);
        call.setBackground(KioskUi.round(c, KioskUi.GOOD, 14));
        call.setGravity(Gravity.CENTER);
        call.setClickable(true);
        call.setFocusable(true);
        TextView callGlyph = KioskUi.text(c, "\u260E  Call", 16, KioskUi.BG, Typeface.BOLD);
        call.addView(callGlyph);
        call.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { placeCall(); }
        });
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(0, KioskUi.dp(c, 50), 1.6f);
        callLp.leftMargin = KioskUi.dp(c, 8);
        callLp.rightMargin = KioskUi.dp(c, 8);
        controls.addView(call, callLp);

        controls.addView(ctrl(c, "\u2709", KioskUi.CARD2, KioskUi.ACCENT, 0.8f,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openMessages(); }
                }));

        // ---- divider ---------------------------------------------------------
        View divider = new View(c);
        divider.setBackgroundColor(KioskUi.LINE);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, KioskUi.dp(c, 1)));
        dLp.leftMargin = KioskUi.dp(c, 18);
        dLp.rightMargin = KioskUi.dp(c, 18);
        dLp.topMargin = KioskUi.dp(c, 12);
        addView(divider, dLp);

        // ---- section label + search ------------------------------------------
        section = KioskUi.caption(c, "RECENT");
        section.setPadding(KioskUi.dp(c, 18), KioskUi.dp(c, 12), KioskUi.dp(c, 18), 0);
        addView(section, KioskUi.matchWrap());

        search = new EditText(c);
        search.setHint("Search contacts");
        search.setHintTextColor(KioskUi.FAINT);
        search.setTextColor(KioskUi.TXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        search.setBackground(KioskUi.round(c, KioskUi.CARD2, 12));
        search.setPadding(KioskUi.dp(c, 14), KioskUi.dp(c, 10),
                KioskUi.dp(c, 14), KioskUi.dp(c, 10));
        LinearLayout.LayoutParams sLp = KioskUi.matchWrap();
        sLp.leftMargin = KioskUi.dp(c, 18);
        sLp.rightMargin = KioskUi.dp(c, 18);
        sLp.topMargin = KioskUi.dp(c, 8);
        addView(search, sLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void afterTextChanged(Editable e) { showList(); }
        });

        // ---- list (weight 1: the only part that scrolls) ----------------------
        listContainer = KioskUi.column(c);
        ScrollView scroller = new ScrollView(c);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = KioskUi.dp(c, 6);
        listLp.bottomMargin = KioskUi.dp(c, 10);
        addView(scroller, listLp);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onShown() { showList(); }

    @Override public void onHidden() {
        try { search.clearFocus(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ dialing

    private void append(String s) {
        if (typed.length() >= 32) return;
        typed.append(s);
        setDisplay();
    }

    private void backspace() {
        if (typed.length() == 0) return;
        typed.deleteCharAt(typed.length() - 1);
        setDisplay();
    }

    private void setNumber(String digits) {
        typed.setLength(0);
        typed.append(digits);
        setDisplay();
    }

    private void setDisplay() {
        if (typed.length() == 0) {
            display.setText("Enter a number");
            display.setTextColor(KioskUi.FAINT);
        } else {
            display.setText(typed.toString());
            display.setTextColor(KioskUi.TXT);
        }
    }

    private void placeCall() {
        if (typed.length() == 0) return;
        host.placeCall(typed.toString());
    }

    private void openMessages() {
        if (typed.length() == 0) return;
        host.openMessages(typed.toString());
    }

    // ------------------------------------------------------------------ keypad

    private View key(Context c, final String digit, String letters) {
        LinearLayout b = KioskUi.column(c);
        b.setGravity(Gravity.CENTER);
        b.setBackground(KioskUi.round(c, KioskUi.CARD2, 16));
        b.setClickable(true);
        b.setFocusable(true);

        TextView d = KioskUi.text(c, digit, 22, KioskUi.TXT, Typeface.NORMAL);
        d.setGravity(Gravity.CENTER);
        b.addView(d);

        if (!letters.isEmpty()) {
            TextView l = KioskUi.text(c, letters, 9, KioskUi.FAINT, Typeface.NORMAL);
            l.setLetterSpacing(0.14f);
            l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = KioskUi.dp(c, 1);
            b.addView(l, lp);
        }

        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { append(digit); }
        });
        return b;
    }

    private View ctrl(Context c, String glyph, int fill, int tint, float weight,
                      View.OnClickListener click) {
        LinearLayout b = KioskUi.row(c);
        b.setGravity(Gravity.CENTER);
        b.setBackground(KioskUi.round(c, fill, 14));
        b.setClickable(true);
        b.setFocusable(true);
        TextView t = KioskUi.text(c, glyph, 19, tint, Typeface.NORMAL);
        b.addView(t);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, KioskUi.dp(c, 50), weight));
        return b;
    }

    // ------------------------------------------------------------------ list

    private void showList() {
        listContainer.removeAllViews();
        String q = search.getText() == null ? "" : search.getText().toString().trim();
        if (q.isEmpty()) {
            section.setText("RECENT");
            List<CallLogStore.Call> recent = CallLogStore.recent(getContext(), 30);
            if (recent.isEmpty()) {
                listContainer.addView(KioskUi.emptyState(getContext(), "No recent calls"));
                return;
            }
            for (CallLogStore.Call call : recent) listContainer.addView(recentRow(call));
        } else {
            section.setText("RESULTS");
            List<ContactNames.Match> matches = ContactNames.search(getContext(), q, 20);
            if (matches.isEmpty()) {
                listContainer.addView(KioskUi.emptyState(getContext(), "No contacts match \u201C" + q + "\u201D"));
                return;
            }
            for (ContactNames.Match m : matches) listContainer.addView(matchRow(m));
        }
    }

    private View recentRow(final CallLogStore.Call call) {
        Context c = getContext();
        LinearLayout row = KioskUi.row(c);
        row.setBackground(KioskUi.round(c, KioskUi.CARD, 14));
        row.setPadding(KioskUi.dp(c, 12), KioskUi.dp(c, 10), KioskUi.dp(c, 12), KioskUi.dp(c, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.placeCall(call.number); }
        });

        String label = call.label(c);
        row.addView(avatar(c, ContactNames.initials(label)));

        LinearLayout col = KioskUi.column(c);
        TextView name = KioskUi.text(c, label, 15, KioskUi.TXT, Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sub = KioskUi.text(c,
                dirGlyph(call.dir) + "  " + TimeFmt.clock(c, call.dateMs),
                12, dirColor(call.dir), Typeface.NORMAL);
        LinearLayout.LayoutParams subLp = KioskUi.matchWrap();
        subLp.topMargin = KioskUi.dp(c, 2);
        col.addView(sub, subLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = KioskUi.dp(c, 12);
        row.addView(col, colLp);

        LinearLayout chip = KioskUi.row(c);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(KioskUi.oval(c, KioskUi.CARD2));
        chip.setClickable(true);
        chip.setFocusable(true);
        TextView chipGlyph = KioskUi.text(c, "\u2709", 16, KioskUi.ACCENT, Typeface.NORMAL);
        chip.addView(chipGlyph);
        chip.setContentDescription("Message " + label);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.openMessages(call.number); }
        });
        row.addView(chip, new LinearLayout.LayoutParams(KioskUi.dp(c, 40), KioskUi.dp(c, 40)));

        LinearLayout.LayoutParams rowLp = KioskUi.matchWrap();
        rowLp.leftMargin = KioskUi.dp(c, 18);
        rowLp.rightMargin = KioskUi.dp(c, 18);
        rowLp.topMargin = KioskUi.dp(c, 6);
        row.setLayoutParams(rowLp);
        return row;
    }

    private View matchRow(final ContactNames.Match m) {
        Context c = getContext();
        LinearLayout row = KioskUi.row(c);
        row.setBackground(KioskUi.round(c, KioskUi.CARD, 14));
        row.setPadding(KioskUi.dp(c, 12), KioskUi.dp(c, 10), KioskUi.dp(c, 12), KioskUi.dp(c, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("Use " + m.name);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setNumber(ContactNames.digits(m.number));
                search.setText("");
                try { search.clearFocus(); } catch (Throwable ignored) { }
            }
        });

        row.addView(avatar(c, ContactNames.initials(m.name)));

        LinearLayout col = KioskUi.column(c);
        TextView name = KioskUi.text(c, m.name, 15, KioskUi.TXT, Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView num = KioskUi.text(c, ContactNames.prettyNumber(m.number), 12,
                KioskUi.FAINT, Typeface.NORMAL);
        LinearLayout.LayoutParams numLp = KioskUi.matchWrap();
        numLp.topMargin = KioskUi.dp(c, 2);
        col.addView(num, numLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = KioskUi.dp(c, 12);
        row.addView(col, colLp);

        LinearLayout.LayoutParams rowLp = KioskUi.matchWrap();
        rowLp.leftMargin = KioskUi.dp(c, 18);
        rowLp.rightMargin = KioskUi.dp(c, 18);
        rowLp.topMargin = KioskUi.dp(c, 6);
        row.setLayoutParams(rowLp);
        return row;
    }

    private View avatar(Context c, String initials) {
        TextView t = KioskUi.text(c, initials, 14, KioskUi.ACCENT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(KioskUi.oval(c, KioskUi.ACCENT_SOFT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                KioskUi.dp(c, 38), KioskUi.dp(c, 38));
        t.setLayoutParams(lp);
        return t;
    }

    private static String dirGlyph(CallLogStore.Dir d) {
        switch (d) {
            case INCOMING:   return "\u2199";
            case OUTGOING:   return "\u2197";
            case MISSED:     return "\u2715";
            case REJECTED:   return "\u2298";
            case BLOCKED:    return "\u2298";
            case VOICEMAIL:  return "\u260F";
            default:         return "\u2022";
        }
    }

    private static int dirColor(CallLogStore.Dir d) {
        switch (d) {
            case INCOMING:  return KioskUi.GOOD;
            case OUTGOING:  return KioskUi.ACCENT;
            case MISSED:
            case REJECTED:
            case BLOCKED:   return KioskUi.DANGER;
            default:        return KioskUi.FAINT;
        }
    }
}
