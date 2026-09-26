package com.vortex.timelock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The kiosk's in-call UI: a full-screen overlay that stands in for the system
 * dialer's call screen, which lock task will not let come to the front.
 *
 * <p>It is deliberately dumb. {@link PhoneLine} owns the truth ("the line is
 * ringing", "the line went off-hook", "the line is idle again") and this view
 * does nothing but paint it and route three taps back up:
 *
 * <ul>
 *   <li><b>RINGING</b> — avatar, caller name, the words "Incoming call", and two
 *       large pills: <b>Decline</b> and <b>Answer</b>.</li>
 *   <li><b>ACTIVE</b> — avatar, callee name, the words "On call", a live
 *       {@code m:ss} timer, and a single <b>End call</b> pill.</li>
 * </ul>
 *
 * <p>The overlay is clickable so it swallows touches: nothing underneath the lock
 * screen can be poked while a call is up. It appears with a short fade so it
 * reads as arriving rather than snapping in. The 1 Hz timer stops the instant the
 * overlay is hidden, so a call screen that is not on screen holds no timer.
 */
final class InCallScreen extends FrameLayout {

    /** Host callbacks for the three call actions this overlay can trigger. */
    interface Actions {
        void onAnswer();
        void onDecline();
        void onEnd();
    }

    private final Actions actions;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TextView avatar;
    private final TextView title;
    private final TextView subtitle;
    private final TextView timer;
    private final LinearLayout ringingRow;
    private final LinearLayout activeRow;

    private boolean ringingMode = false;
    private long connectedAtMs = 0L;

    InCallScreen(Context c, Actions a) {
        super(c);
        this.actions = a;

        // Near-opaque scrim: hides the tab shell completely without the small
        // extra cost of a true window, and reads as a focused call screen.
        setBackgroundColor(Color.argb(0xF2, 12, 20, 16));
        // Swallow every touch so the kiosk behind us is inert during a call.
        setClickable(true);
        setFocusable(true);
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { /* swallow */ }
        });

        LinearLayout col = KioskUi.column(c);
        col.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        addView(col, colLp);

        avatar = KioskUi.text(c, "#", 30, KioskUi.ACCENT, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(KioskUi.oval(c, KioskUi.ACCENT_SOFT));
        col.addView(avatar, new LinearLayout.LayoutParams(
                KioskUi.dp(c, 96), KioskUi.dp(c, 96)));

        title = KioskUi.text(c, "", 26, KioskUi.TXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = KioskUi.matchWrap();
        tLp.topMargin = KioskUi.dp(c, 22);
        col.addView(title, tLp);

        subtitle = KioskUi.text(c, "", 14, KioskUi.MUTED, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = KioskUi.matchWrap();
        sLp.topMargin = KioskUi.dp(c, 8);
        col.addView(subtitle, sLp);

        timer = KioskUi.mono(c, "0:00", 34, KioskUi.ACCENT, Typeface.BOLD);
        timer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mLp = KioskUi.matchWrap();
        mLp.topMargin = KioskUi.dp(c, 14);
        KioskUi.setVisible(timer, false);
        col.addView(timer, mLp);

        // ---- ringing actions ----
        ringingRow = KioskUi.row(c);
        ringingRow.setGravity(Gravity.CENTER);
        TextView decline = pill(c, "Decline", KioskUi.DANGER, KioskUi.DANGER_SOFT);
        decline.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { actions.onDecline(); }
        });
        TextView answer = pill(c, "Answer", KioskUi.GOOD, KioskUi.GOOD_SOFT);
        answer.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { actions.onAnswer(); }
        });
        ringingRow.addView(decline);
        LinearLayout.LayoutParams anLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        anLp.leftMargin = KioskUi.dp(c, 22);
        ringingRow.addView(answer, anLp);
        LinearLayout.LayoutParams rLp = KioskUi.matchWrap();
        rLp.topMargin = KioskUi.dp(c, 40);
        col.addView(ringingRow, rLp);

        // ---- active action ----
        activeRow = KioskUi.row(c);
        activeRow.setGravity(Gravity.CENTER);
        TextView end = pill(c, "End call", KioskUi.DANGER, KioskUi.DANGER_SOFT);
        end.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { actions.onEnd(); }
        });
        activeRow.addView(end);
        LinearLayout.LayoutParams aLp = KioskUi.matchWrap();
        aLp.topMargin = KioskUi.dp(c, 40);
        col.addView(activeRow, aLp);

        setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ states

    /** Show the "incoming call" face for {@code label}. */
    void showRinging(String label) {
        ringingMode = true;
        connectedAtMs = 0L;
        ui.removeCallbacks(tick);
        bind(label);
        subtitle.setText("Incoming call");
        KioskUi.setVisible(timer, false);
        KioskUi.setVisible(ringingRow, true);
        KioskUi.setVisible(activeRow, false);
        reveal();
    }

    /** Show the "on call" face for {@code label} with a fresh timer. */
    void showActive(String label) {
        // If we are already on a live call, just refresh the name (e.g. the number
        // resolved to a contact after the first paint); never reset the timer.
        if (!ringingMode && connectedAtMs > 0L) {
            bind(label);
            return;
        }
        ringingMode = false;
        bind(label);
        subtitle.setText("On call");
        connectedAtMs = System.currentTimeMillis();
        timer.setText("0:00");
        KioskUi.setVisible(timer, true);
        KioskUi.setVisible(ringingRow, false);
        KioskUi.setVisible(activeRow, true);
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000L);
        reveal();
    }

    /** Tear the overlay down and stop its timer. */
    void hide() {
        ui.removeCallbacks(tick);
        connectedAtMs = 0L;
        ringingMode = false;
        KioskUi.setVisible(this, false);
    }

    boolean isShowing() { return getVisibility() == View.VISIBLE; }

    // ------------------------------------------------------------------ internals

    private void bind(String label) {
        String name = TextUtils.isEmpty(label) ? "Unknown" : label;
        title.setText(name);
        avatar.setText(ContactNames.initials(name));
    }

    private void reveal() {
        if (getVisibility() == View.VISIBLE) return;
        setAlpha(0f);
        KioskUi.setVisible(this, true);
        animate().alpha(1f).setDuration(180L).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (connectedAtMs <= 0L || getVisibility() != View.VISIBLE) return;
            long sec = Math.max(0L, (System.currentTimeMillis() - connectedAtMs) / 1000L);
            timer.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
            ui.postDelayed(this, 500L);
        }
    };

    private TextView pill(Context c, String label, int color, int fill) {
        TextView t = KioskUi.text(c, label, 16, color, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(KioskUi.round(c, fill, 30, color, 1));
        int hp = KioskUi.dp(c, 34);
        int vp = KioskUi.dp(c, 15);
        t.setPadding(hp, vp, hp, vp);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }
}
