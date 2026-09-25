package com.vortex.timelock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * ============================================================================
 *  Screen Time Locker — clean, framework-only bottom sheet
 * ============================================================================
 *
 *  A small, dependency-free bottom sheet used by {@link MainSetupActivity} to
 *  present the educational documentation behind its shortcut buttons. The
 *  project is built WITHOUT Gradle/AGP and ships no AndroidX or Material
 *  classes, so the Material {@code BottomSheetDialog} is not available. This
 *  class reproduces the same look-and-feel using only platform widgets:
 *
 *  <ul>
 *    <li>a bottom-anchored, full-width rounded panel floated over a dim
 *        backdrop, held by a small drag handle;</li>
 *    <li>a scrollable body capped to a fraction of the screen so long docs never
 *        run off the top;</li>
 *    <li>a slide-up / slide-down reveal animation driven by {@code View}
 *        properties (no animation XML resources needed).</li>
 *  </ul>
 *
 *  All colour and geometry come from the shared {@link Ui} design tokens, so the
 *  sheet follows the system light/dark theme exactly like the rest of the app.
 *  Everything is defensive: if any step fails the sheet simply dismisses instead
 *  of crashing a live Activity.
 */
final class SheetDialog {

    private SheetDialog() {}

    /** One titled block of documentation rendered inside the sheet body. */
    static final class Section {
        final String heading;
        final String body;

        Section(String heading, String body) {
            this.heading = heading;
            this.body = body;
        }
    }

    /**
     * Shows the sheet. Blocks are rendered in order; long bodies scroll.
     *
     * @param activity host Activity (its Ui density must already be bound)
     * @param ui       shared design tokens
     * @param kicker   small uppercase label above the title (may be null)
     * @param title    sheet title
     * @param intro    short lead paragraph under the title (may be null)
     * @param sections ordered documentation blocks
     */
    static void show(final Activity activity, final Ui ui, String kicker, String title,
                     String intro, List<Section> sections) {
        if (activity == null || ui == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(topRounded(ui.surface, 26f, ui, ui.border, 1.5f));
        panel.setPadding(ui.dp(20f), ui.dp(10f), ui.dp(20f), ui.dp(20f));

        // ---- grab handle ----
        View handle = new View(activity);
        handle.setBackground(ui.rounded(ui.border, 3f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(44f), ui.dp(5f));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = ui.dp(6f);
        hlp.bottomMargin = ui.dp(14f);
        handle.setLayoutParams(hlp);
        panel.addView(handle);

        // ---- kicker ----
        if (kicker != null && kicker.length() > 0) {
            TextView k = new TextView(activity);
            k.setText(kicker);
            k.setTextColor(ui.accent);
            k.setTextSize(12f);
            k.setTypeface(Ui.bold());
            k.setLetterSpacing(0.16f);
            panel.addView(k);
        }

        // ---- title ----
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(ui.text);
        t.setTextSize(21f);
        t.setTypeface(Ui.bold());
        t.setPadding(0, ui.dp(6f), 0, 0);
        panel.addView(t);

        // ---- intro ----
        if (intro != null && intro.length() > 0) {
            TextView i = new TextView(activity);
            i.setText(intro);
            i.setTextColor(ui.textDim);
            i.setTextSize(14.5f);
            i.setLineSpacing(ui.dp(4f), 1f);
            i.setPadding(0, ui.dp(8f), 0, ui.dp(4f));
            panel.addView(i);
        }

        // ---- scrollable body ----
        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, ui.dp(12f), 0, ui.dp(4f));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (sections != null) {
            for (Section s : sections) {
                if (s == null) continue;
                if (s.heading != null && s.heading.length() > 0) {
                    TextView h = new TextView(activity);
                    h.setText(s.heading);
                    h.setTextColor(ui.text);
                    h.setTextSize(15.5f);
                    h.setTypeface(Ui.bold());
                    h.setPadding(0, ui.dp(14f), 0, 0);
                    body.addView(h);
                }
                if (s.body != null && s.body.length() > 0) {
                    TextView b = new TextView(activity);
                    b.setText(s.body);
                    b.setTextColor(ui.textDim);
                    b.setTextSize(14f);
                    b.setLineSpacing(ui.dp(4f), 1f);
                    b.setPadding(0, ui.dp(6f), 0, 0);
                    body.addView(b);
                }
            }
        }

        // Cap the scroll area so a long document never pushes the title off-screen.
        int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.58f);
        scroll.setMaxHeightPx(maxH);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = ui.dp(4f);
        scroll.setLayoutParams(slp);
        panel.addView(scroll);

        // ---- close action ----
        Button close = new Button(activity);
        close.setText("Got it");
        close.setAllCaps(false);
        close.setTextSize(16f);
        close.setTextColor(ui.onAccent);
        close.setTypeface(Ui.bold());
        close.setBackground(ui.rounded(ui.accent, 14f));
        close.setPadding(ui.dp(16f), ui.dp(15f), ui.dp(16f), ui.dp(15f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ui.dp(16f);
        close.setLayoutParams(clp);
        close.setMinWidth(0); close.setMinimumWidth(0);
        close.setMinHeight(0); close.setMinimumHeight(0);
        close.setStateListAnimator(null);
        close.setOnClickListener(v -> dismissAnimated(dialog, panel));
        panel.addView(close);

        dialog.setContentView(panel);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.55f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        // Slide the panel up from just below the window.
        float start = activity.getResources().getDisplayMetrics().heightPixels * 0.35f;
        panel.setTranslationY(start);
        panel.setAlpha(0f);
        dialog.show();
        panel.animate().translationY(0f).alpha(1f).setDuration(220).start();
    }

    /** Convenience for a single-section informational sheet. */
    static void show(Activity activity, Ui ui, String title, String body) {
        java.util.List<Section> one = new java.util.ArrayList<>();
        one.add(new Section(null, body));
        show(activity, ui, "DOCUMENTATION", title, null, one);
    }

    private static void dismissAnimated(final Dialog dialog, final View panel) {
        try {
            float end = panel.getHeight() > 0
                    ? panel.getHeight()
                    : panel.getResources().getDisplayMetrics().heightPixels * 0.35f;
            panel.animate().translationY(end).alpha(0f).setDuration(180)
                    .withEndAction(() -> {
                        try {
                            if (dialog.isShowing()) dialog.dismiss();
                        } catch (Throwable ignored) { /* activity may be gone */ }
                    }).start();
        } catch (Throwable t) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) { /* nothing else we can do */ }
        }
    }

    /**
     * A {@link ScrollView} that refuses to grow taller than a fixed pixel cap,
     * so a long document scrolls inside the sheet instead of pushing the title
     * and close button off the top of the screen. Platform {@code ScrollView}
     * has no {@code setMaxHeight}, so the cap is enforced in {@code onMeasure}.
     */
    private static final class MaxHeightScrollView extends ScrollView {
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxHeightScrollView(Context context) { super(context); }
        MaxHeightScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

        void setMaxHeightPx(int px) {
            if (px > 0) maxHeightPx = px;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int spec = heightMeasureSpec;
            int size = MeasureSpec.getSize(heightMeasureSpec);
            if (size > maxHeightPx) {
                spec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, spec);
        }
    }

    /** Rounded rectangle with only the top corners curved (bottom sheet look). */
    private static GradientDrawable topRounded(int fill, float radiusDp,
                                               Ui ui, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        float r = ui.dp(radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        if (strokeColor != 0 && strokeDp > 0) {
            g.setStroke(ui.dp(strokeDp), strokeColor);
        }
        return g;
    }
}
