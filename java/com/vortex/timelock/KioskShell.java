package com.vortex.timelock;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The kiosk's tab shell: one fixed content well with a small tab dock pinned to
 * the bottom of the panel.
 *
 * <p>Why a shell at all. Until this revision the lock screen was a single,
 * read-only projection. A retreat member locked out of their phone still needs to
 * reach a human — a parent, a partner, emergency services — so the lock screen now
 * carries three interactive surfaces: <b>Retreat</b> (the original dashboard),
 * <b>Phone</b> (dialer + recents + contacts) and <b>Messages</b> (SMS threads and
 * conversations). Rather than stack three full screens and hope the user never
 * needs the second one, this shell keeps exactly one screen in the content well
 * and swaps between them from a dock that never moves.
 *
 * <p>Presentation + routing only. It owns no telephony state: it reports a tab
 * change to the {@link Screen} it is showing and lets the host
 * ({@link LockActivity}) decide what a "call" or "open this conversation" means.
 *
 * <p>Screens are added in order; the first added is shown by default. A page may
 * have a {@code null} {@link Screen} (the dashboard does — it is bound by the
 * host's own 1 Hz cadence, not by show/hide callbacks).
 */
final class KioskShell extends LinearLayout {

    private static final String TAG = "TL.Shell";

    /** Show/hide lifecycle for a tab's page. Both calls happen on the UI thread. */
    interface Screen {
        void onShown();
        void onHidden();
    }

    /** Everything a screen may ask the host to do. */
    interface Host {
        /** Originate a call to {@code number} (raw, un-normalised input is fine). */
        void placeCall(String number);
        /** Switch to Messages and open the thread for {@code address} (may be empty = new). */
        void openMessages(String address);
        /** Set the dock's Messages badge; {@code 0} hides it. */
        void setMessagesBadge(int count);
    }

    private static final class Tab {
        final FrameLayout iconBox;
        final TextView glyph;
        final TextView label;
        final TextView badge;
        Tab(FrameLayout iconBox, TextView glyph, TextView label, TextView badge) {
            this.iconBox = iconBox;
            this.glyph = glyph;
            this.label = label;
            this.badge = badge;
        }
    }

    private final FrameLayout content;
    private final LinearLayout dock;
    private final List<View> pages = new ArrayList<>();
    private final List<Screen> screens = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    private int index = -1;

    KioskShell(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(KioskUi.BG);

        // content well: weight 1, so the dock always sits at the very bottom
        content = new FrameLayout(c);
        addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // hairline separator above the dock
        View line = new View(c);
        line.setBackgroundColor(KioskUi.LINE);
        addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, KioskUi.dp(c, 1))));

        dock = KioskUi.row(c);
        dock.setBackgroundColor(KioskUi.CARD);
        addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, KioskUi.dp(c, 62)));
    }

    // ------------------------------------------------------------------ build

    /**
     * Add a tab. {@code glyph} is a single character (the dock is icon-led), and
     * {@code screen} may be {@code null} when the page is driven from elsewhere.
     */
    void addScreen(String glyph, String label, View page, Screen screen) {
        if (page == null) return;
        page.setVisibility(View.GONE);
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(page);
        screens.add(screen);
        tabs.add(buildTab(glyph, label, pages.size() - 1));
    }

    private Tab buildTab(String glyphText, String labelText, final int tabIndex) {
        final Context c = getContext();

        FrameLayout iconBox = new FrameLayout(c);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(
                KioskUi.dp(c, 30), KioskUi.dp(c, 22)));

        TextView glyph = KioskUi.text(c, glyphText, 19, KioskUi.FAINT, Typeface.NORMAL);
        glyph.setGravity(Gravity.CENTER);
        iconBox.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        TextView badge = KioskUi.text(c, "", 9, KioskUi.BG, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(KioskUi.oval(c, KioskUi.DANGER));
        badge.setMinWidth(KioskUi.dp(c, 15));
        badge.setPadding(KioskUi.dp(c, 4), 0, KioskUi.dp(c, 4), 0);
        badge.setVisibility(View.GONE);
        iconBox.addView(badge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, KioskUi.dp(c, 15),
                Gravity.TOP | Gravity.END));

        TextView label = KioskUi.text(c, labelText, 10, KioskUi.FAINT, Typeface.BOLD);
        label.setLetterSpacing(0.06f);
        label.setGravity(Gravity.CENTER);

        LinearLayout col = KioskUi.column(c);
        col.setGravity(Gravity.CENTER);
        col.addView(iconBox);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = KioskUi.dp(c, 3);
        col.addView(label, llp);

        FrameLayout holder = new FrameLayout(c);
        holder.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        holder.setClickable(true);
        holder.setFocusable(true);
        holder.setContentDescription(labelText);
        holder.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { select(tabIndex); }
        });
        dock.addView(holder, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        return new Tab(iconBox, glyph, label, badge);
    }

    // ------------------------------------------------------------------ routing

    /** Index of the screen currently shown, or {@code -1} before the first select. */
    int current() { return index; }

    /** Show tab {@code i}, firing onHidden/onShown exactly on a real transition. */
    void select(int i) {
        if (i < 0 || i >= pages.size() || i == index) return;
        if (index >= 0) {
            pages.get(index).setVisibility(View.GONE);
            Screen prev = screens.get(index);
            if (prev != null) {
                try { prev.onHidden(); } catch (Throwable t) { Log.w(TAG, "onHidden", t); }
            }
            styleTab(index, false);
        }
        index = i;
        pages.get(i).setVisibility(View.VISIBLE);
        styleTab(i, true);
        Screen next = screens.get(i);
        if (next != null) {
            try { next.onShown(); } catch (Throwable t) { Log.w(TAG, "onShown", t); }
        }
        try { pages.get(i).requestFocus(); } catch (Throwable ignored) { }
    }

    /** Re-run the current screen's onShown() (e.g. after returning from a call). */
    void refreshCurrent() {
        if (index < 0) return;
        Screen s = screens.get(index);
        if (s == null) return;
        try { s.onShown(); } catch (Throwable t) { Log.w(TAG, "refresh", t); }
    }

    /** Unread (or other) badge on a tab; {@code count <= 0} hides it. */
    void setBadge(int tab, int count) {
        if (tab < 0 || tab >= tabs.size()) return;
        TextView b = tabs.get(tab).badge;
        if (count <= 0) { KioskUi.setVisible(b, false); return; }
        b.setText(count > 99 ? "99+" : String.valueOf(count));
        KioskUi.setVisible(b, true);
    }

    private void styleTab(int i, boolean selected) {
        Tab t = tabs.get(i);
        int c = selected ? KioskUi.ACCENT : KioskUi.FAINT;
        t.glyph.setTextColor(c);
        t.label.setTextColor(c);
        t.iconBox.setBackground(selected
                ? KioskUi.round(getContext(), KioskUi.ACCENT_SOFT, 11)
                : null);
    }
}
