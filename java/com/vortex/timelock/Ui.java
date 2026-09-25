package com.vortex.timelock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * Sentinel TimeLock — single source of design tokens.
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
            bg         = Color.parseColor("#0E1420");
            surface    = Color.parseColor("#171F2E");
            surfaceAlt = Color.parseColor("#202B3D");
            field      = Color.parseColor("#0B111B");
            border     = Color.parseColor("#2C3B52");

            text       = Color.parseColor("#F2F6FC");
            textDim    = Color.parseColor("#A9B6C8");
            textFaint  = Color.parseColor("#7C8AA0");

            accent     = Color.parseColor("#5AC8FA");
            accentSoft = Color.parseColor("#0A2A3A");
            onAccent   = Color.parseColor("#04222E");

            ok         = Color.parseColor("#34D399");
            okSoft     = Color.parseColor("#08281C");
            onOk       = Color.parseColor("#04241A");
            warn       = Color.parseColor("#F5B301");
            warnSoft   = Color.parseColor("#2E2200");
            onWarn     = Color.parseColor("#2E2200");
            danger     = Color.parseColor("#F87171");
            dangerText = Color.parseColor("#FCA5A5");
            dangerSoft = Color.parseColor("#2C1315");
            onDanger   = Color.parseColor("#2A0A0C");

            track      = Color.parseColor("#26344A");
        } else {
            bg         = Color.parseColor("#F2F5FA");
            surface    = Color.parseColor("#FFFFFF");
            surfaceAlt = Color.parseColor("#EFF3F9");
            field      = Color.parseColor("#FFFFFF");
            border     = Color.parseColor("#D3DCE8");

            text       = Color.parseColor("#10151F");
            textDim    = Color.parseColor("#51607A");
            textFaint  = Color.parseColor("#7C8AA0");

            accent     = Color.parseColor("#0B7BD6");
            accentSoft = Color.parseColor("#E3F0FF");
            onAccent   = Color.parseColor("#FFFFFF");

            ok         = Color.parseColor("#0F8A6A");
            okSoft     = Color.parseColor("#E1F5EE");
            onOk       = Color.parseColor("#FFFFFF");
            warn       = Color.parseColor("#B7791F");
            warnSoft   = Color.parseColor("#FDF3DC");
            onWarn     = Color.parseColor("#FFFFFF");
            danger     = Color.parseColor("#D64550");
            dangerText = Color.parseColor("#C0323D");
            dangerSoft = Color.parseColor("#FCE8EA");
            onDanger   = Color.parseColor("#FFFFFF");

            track      = Color.parseColor("#DCE4EE");
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
