package com.vortex.timelock;

import android.content.Context;
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

/**
 * The kiosk's heads-up for a new text: a small card that slides down from the top
 * of the lock screen over whatever tab is showing, whatever the user was doing,
 * and steps back out of the way on its own after a few seconds.
 *
 * <p>The retreat is not the default SMS app, so the platform does not draw a
 * notification heads-up for it — and even if it did, notifications are suppressed
 * inside lock task. This overlay is therefore the retreat's own heads-up: it
 * makes sure a text that lands while the person is staring at the lock screen is
 * never silent. Tapping it opens that conversation in the Messages tab; ignoring
 * it leaves the dock badge to carry the unread count.
 *
 * <p>It is a thin top strip (not a full-screen scrim) on purpose: while visible it
 * blocks touches only across the card itself, so the surface underneath stays
 * usable. The auto-hide timer and the slide animation mean it never lingers.
 */
final class SmsNotice extends FrameLayout {

    /** Invoked when the user taps the banner. */
    interface Tap {
        void onTap(String address, String body);
    }

    private static final long AUTO_HIDE_MS = 4500L;

    private final Tap onTap;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView avatar;
    private final TextView name;
    private final TextView snippet;

    private String address = "";
    private String message = "";
    private boolean showing = false;

    private final Runnable autoHide = new Runnable() {
        @Override public void run() { hideSmooth(); }
    };

    SmsNotice(Context c, Tap t) {
        super(c);
        this.onTap = t;
        setVisibility(View.GONE);
        setPadding(KioskUi.dp(c, 14), KioskUi.dp(c, 12), KioskUi.dp(c, 14), 0);

        LinearLayout card = KioskUi.row(c);
        card.setBackground(KioskUi.round(c, KioskUi.CARD2, 18, KioskUi.BORDER, 1));
        int p = KioskUi.dp(c, 12);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("New message");
        card.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                String a = address;
                String b = message;
                hideSmooth();
                if (onTap != null) onTap.onTap(a, b);
            }
        });

        avatar = KioskUi.text(c, "#", 14, KioskUi.ACCENT, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(KioskUi.oval(c, KioskUi.ACCENT_SOFT));
        card.addView(avatar, new LinearLayout.LayoutParams(
                KioskUi.dp(c, 40), KioskUi.dp(c, 40)));

        LinearLayout col = KioskUi.column(c);
        name = KioskUi.text(c, "", 15, KioskUi.TXT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name, KioskUi.matchWrap());
        snippet = KioskUi.text(c, "", 13, KioskUi.FAINT, Typeface.NORMAL);
        snippet.setSingleLine(true);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams snLp = KioskUi.matchWrap();
        snLp.topMargin = KioskUi.dp(c, 2);
        col.addView(snippet, snLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = KioskUi.dp(c, 12);
        card.addView(col, colLp);

        TextView hint = KioskUi.caption(c, "MESSAGES");
        card.addView(hint);

        addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Slide the banner in (or refresh it if already up) for a new message. */
    void show(String addr, String body) {
        String a = addr == null ? "" : addr;
        String b = body == null ? "" : body;
        this.address = a;
        this.message = b;

        String label = ContactNames.label(getContext(), a);
        name.setText(TextUtils.isEmpty(label) ? "Unknown" : label);
        snippet.setText(b);
        avatar.setText(ContactNames.initials(label));

        ui.removeCallbacks(autoHide);
        if (!showing) {
            showing = true;
            KioskUi.setVisible(this, true);
            setTranslationY(-KioskUi.dp(getContext(), 150));
            setAlpha(0f);
            animate().translationY(0f).alpha(1f).setDuration(220L).start();
        }
        ui.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    /** Slide the banner out and take it off screen. */
    void hideSmooth() {
        ui.removeCallbacks(autoHide);
        if (!showing) return;
        showing = false;
        animate().translationY(-KioskUi.dp(getContext(), 150)).alpha(0f)
                .setDuration(200L)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        KioskUi.setVisible(SmsNotice.this, false);
                    }
                })
                .start();
    }
}
