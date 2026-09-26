package com.vortex.timelock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * Digital Retreat — single source of design tokens.
 *
 * The admin console draws its own surfaces programmatically, so every colour it
 * uses is resolved here. Two balanced palettes are defined and picked from the
 * system night-mode flag: a soft, low-glare dark set and a bright, high-contrast
 * light set. Both share the same accent family and the same contrast ratios, so
 * the screen reads the same in either theme.
 *
 * Nothing here is preset by the app: these are presentation tokens only.
 */
final class Ui {

    /** True when the OS is in night mode. */
    static boolean isNight(Context c) {
        if (c == null) return true;
        int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    final boolean dark;

    // ---- surfaces ----
    final int bg;         // window background
    final int surface;    // card
    final int surfaceAlt; // raised chip / button
    final int field;      // editable field
    final int border;     // hairline stroke

    // ---- text ----
    final int text;
    final int textDim;
    final int textFaint;

    // ---- accent (brand) ----
    final int accent;
    final int accentSoft;  // tinted fill behind accent text
    final int onAccent;    // text on an accent-filled chip

    // ---- semantics ----
    final int ok, okSoft, onOk;
    final int warn, warnSoft, onWarn;
    final int danger, dangerText, dangerSoft, onDanger;

    // ---- slider ----
    final int track;

    Ui(Context c) {
        this(isNight(c));
    }

    Ui(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg         = Color.parseColor("#121A16");
            surface    = Color.parseColor("#1B2620");
            surfaceAlt = Color.parseColor("#24322A");
            field      = Color.parseColor("#0D1411");
            border     = Color.parseColor("#33453B");

            text       = Color.parseColor("#EEF3EC");
            textDim    = Color.parseColor("#B4C2B7");
            textFaint  = Color.parseColor("#86948A");

            accent     = Color.parseColor("#7FC8A9");
            accentSoft = Color.parseColor("#123029");
            onAccent   = Color.parseColor("#0E241C");

            ok         = Color.parseColor("#6FBF9B");
            okSoft     = Color.parseColor("#12281F");
            onOk       = Color.parseColor("#0E231A");
            warn       = Color.parseColor("#E0A552");
            warnSoft   = Color.parseColor("#2E2412");
            onWarn     = Color.parseColor("#2E2412");
            danger     = Color.parseColor("#E08573");
            dangerText = Color.parseColor("#F0B4A2");
            dangerSoft = Color.parseColor("#2A1613");
            onDanger   = Color.parseColor("#2A1410");

            track      = Color.parseColor("#2B3A31");
        } else {
            bg         = Color.parseColor("#F4F1EA");
            surface    = Color.parseColor("#FFFFFF");
            surfaceAlt = Color.parseColor("#EDE8DE");
            field      = Color.parseColor("#FFFFFF");
            border     = Color.parseColor("#DAD3C4");

            text       = Color.parseColor("#1E2A24");
            textDim    = Color.parseColor("#4C5B52");
            textFaint  = Color.parseColor("#86948A");

            accent     = Color.parseColor("#2F6F5E");
            accentSoft = Color.parseColor("#E2EFE9");
            onAccent   = Color.parseColor("#FFFFFF");

            ok         = Color.parseColor("#2F7D63");
            okSoft     = Color.parseColor("#E4F0E9");
            onOk       = Color.parseColor("#FFFFFF");
            warn       = Color.parseColor("#A9762E");
            warnSoft   = Color.parseColor("#F6EDDB");
            onWarn     = Color.parseColor("#FFFFFF");
            danger     = Color.parseColor("#C0563A");
            dangerText = Color.parseColor("#A6442C");
            dangerSoft = Color.parseColor("#F7E3DB");
            onDanger   = Color.parseColor("#FFFFFF");

            track      = Color.parseColor("#E0DACD");
        }
    }

    // ---------------------------------------------------------------- helpers

    private float density = 1f;

    /** Bind the real display density once the Activity is attached. */
    Ui bind(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        return this;
    }

    int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    /** Rounded rectangle, optionally stroked. radius/stroke given in dp. */
    GradientDrawable rounded(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0 && strokeDp > 0) {
            g.setStroke(dp(strokeDp), strokeColor);
        }
        return g;
    }

    GradientDrawable rounded(int fill, float radiusDp) {
        return rounded(fill, radiusDp, 0, 0);
    }

    /** Circular badge, optionally stroked (used for the gatekeeper status icons). */
    GradientDrawable oval(int fill, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokeColor != 0 && strokeDp > 0) {
            g.setStroke(dp(strokeDp), strokeColor);
        }
        return g;
    }

    GradientDrawable oval(int fill) {
        return oval(fill, 0, 0);
    }

    static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }
    static Typeface bold()   { return Typeface.create("sans-serif-medium", Typeface.BOLD); }
    static Typeface mono()   { return Typeface.MONOSPACE; }

    int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }
}
