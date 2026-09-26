package com.vortex.timelock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared design tokens and view factories for the kiosk's interactive surfaces
 * (the dialer, the messages client and the tab shell). {@link KioskDashboard}
 * keeps its own private copies of these values so the read-only dashboard stays
 * a closed, allocation-free module; this class exists so the three
 * <em>interactive</em> screens added alongside it share one palette and one set
 * of rounded-rectangle / label factories instead of each growing a copy.
 *
 * <p>Presentation only: no state, no listeners, no Android services.
 */
final class KioskUi {

    // ---- kiosk palette (always dark, matches LockTheme / KioskDashboard) ----
    static final int BG         = Color.parseColor("#0C1410");
    static final int CARD       = Color.parseColor("#172019");
    static final int CARD2      = Color.parseColor("#24322A");
    static final int TXT        = Color.parseColor("#F3F7F1");
    static final int MUTED      = Color.parseColor("#B4C2B7");
    static final int FAINT      = Color.parseColor("#86948A");
    static final int ACCENT     = Color.parseColor("#7FC8A9");
    static final int GOOD       = Color.parseColor("#6FBF9B");
    static final int WARN       = Color.parseColor("#E0A552");
    static final int DANGER     = Color.parseColor("#E08573");
    static final int BORDER     = Color.parseColor("#33453B");
    static final int LINE       = Color.parseColor("#22302A");

    static final int ACCENT_SOFT = Color.parseColor("#123029");
    static final int GOOD_SOFT   = Color.parseColor("#12281F");
    static final int WARN_SOFT   = Color.parseColor("#2E2412");
    static final int DANGER_SOFT = Color.parseColor("#2A1613");
    static final int BUBBLE_IN   = Color.parseColor("#24322A");
    static final int BUBBLE_OUT  = Color.parseColor("#2C5C4B");

    private KioskUi() { }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Rounded rectangle, optionally stroked (radius / stroke given in dp). */
    static GradientDrawable round(Context c, int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0 && strokeDp > 0f) g.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return g;
    }

    static GradientDrawable round(Context c, int fill, float radiusDp) {
        return round(c, fill, radiusDp, 0, 0);
    }

    static GradientDrawable oval(Context c, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    /** A styled text view: sans-serif-medium at the requested weight. */
    static TextView text(Context c, String value, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif-medium", style));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView mono(Context c, String value, float sp, int color, int style) {
        TextView t = text(c, value, sp, color, style);
        t.setTypeface(Typeface.create("monospace", style));
        return t;
    }

    /** A small section caption in the kiosk's uppercase, letter-spaced idiom. */
    static TextView caption(Context c, String value) {
        TextView t = text(c, value, 11, ACCENT, Typeface.BOLD);
        t.setLetterSpacing(0.14f);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Set the first character's case without pulling in a full text transform. */
    static String upperFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A one-line "nothing here" placeholder used by both list surfaces. */
    static TextView emptyState(Context c, String message) {
        TextView t = text(c, message, 13, FAINT, Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 24);
        t.setPadding(p, p, p, p);
        t.setLayoutParams(matchWrap());
        return t;
    }

    static void setVisible(View v, boolean visible) {
        if (v == null) return;
        int want = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != want) v.setVisibility(want);
    }
}
