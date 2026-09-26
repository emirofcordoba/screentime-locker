package com.vortex.timelock;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
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
 * The kiosk's Messages tab: SMS threads and conversations, entirely in-kiosk.
 *
 * <p>Two pages share one screen. The <b>thread list</b> shows a newest-first row
 * per conversation (avatar, name, snippet, unread badge) plus a "New" affordance;
 * the <b>conversation</b> page shows the message history as bubbles, with a
 * compose row pinned to the bottom. Tapping a thread opens it; the back glyph
 * returns to the list.
 *
 * <p>Reading/writing goes through {@link SmsStore}. This screen adds only the
 * kiosk-specific glue: it re-reads the provider whenever it is shown, and it
 * installs a {@link SmsReceiver.Listener} while visible so an incoming text
 * refreshes the list (or the open conversation) the instant it lands instead of
 * waiting for the next tick. The listener is cleared the moment the tab is hidden
 * so the broadcast receiver never holds the UI alive.
 *
 * <p><b>Honesty about state.</b> A non-default SMS app cannot write the "sent"
 * row into the provider, so {@link SmsStore} keeps a private outbox and merges it
 * back into the thread. Sends that the radio refuses surface as a short status
 * line rather than a silent failure.
 */
final class MessagesScreen extends LinearLayout
        implements KioskShell.Screen, SmsReceiver.Listener {

    private final KioskShell.Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // list page
    private final LinearLayout listPage;
    private final LinearLayout threadContainer;

    // conversation page
    private final LinearLayout convPage;
    private final TextView convTitle;
    private final EditText addressField;
    private final LinearLayout bubbleContainer;
    private final EditText compose;
    private final TextView status;
    private final ScrollView bubbleScroller;

    private String convAddress = "";
    private long convThreadId = 0L;

    MessagesScreen(Context c, KioskShell.Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(KioskUi.BG);

        // ================================================== thread list page
        listPage = KioskUi.column(c);

        LinearLayout header = KioskUi.row(c);
        header.setPadding(KioskUi.dp(c, 18), KioskUi.dp(c, 14), KioskUi.dp(c, 18), 0);
        TextView cap = KioskUi.caption(c, "MESSAGES");
        header.addView(cap, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout newChip = KioskUi.row(c);
        newChip.setBackground(KioskUi.round(c, KioskUi.ACCENT_SOFT, 12));
        newChip.setPadding(KioskUi.dp(c, 12), KioskUi.dp(c, 6), KioskUi.dp(c, 12), KioskUi.dp(c, 6));
        newChip.setClickable(true);
        newChip.setFocusable(true);
        TextView newTxt = KioskUi.text(c, "+  New", 12, KioskUi.ACCENT, Typeface.BOLD);
        newChip.addView(newTxt);
        newChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openConversation(""); }
        });
        header.addView(newChip);
        listPage.addView(header, KioskUi.matchWrap());

        threadContainer = KioskUi.column(c);
        ScrollView listScroller = new ScrollView(c);
        listScroller.setVerticalScrollBarEnabled(false);
        listScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroller.addView(threadContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = KioskUi.dp(c, 10);
        listLp.bottomMargin = KioskUi.dp(c, 10);
        listPage.addView(listScroller, listLp);

        // ================================================== conversation page
        convPage = KioskUi.column(c);
        convPage.setVisibility(View.GONE);

        LinearLayout convHeader = KioskUi.row(c);
        convHeader.setPadding(KioskUi.dp(c, 10), KioskUi.dp(c, 10), KioskUi.dp(c, 14), 0);

        TextView back = KioskUi.text(c, "\u2190", 22, KioskUi.MUTED, Typeface.NORMAL);
        back.setGravity(Gravity.CENTER);
        back.setBackground(KioskUi.oval(c, KioskUi.CARD2));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("Back to conversations");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showList(); }
        });
        convHeader.addView(back, new LinearLayout.LayoutParams(
                KioskUi.dp(c, 42), KioskUi.dp(c, 42)));

        convTitle = KioskUi.text(c, "New message", 17, KioskUi.TXT, Typeface.BOLD);
        convTitle.setSingleLine(true);
        convTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = KioskUi.dp(c, 12);
        convHeader.addView(convTitle, tLp);

        LinearLayout callChip = KioskUi.row(c);
        callChip.setGravity(Gravity.CENTER);
        callChip.setBackground(KioskUi.oval(c, KioskUi.GOOD_SOFT));
        callChip.setClickable(true);
        callChip.setFocusable(true);
        callChip.setContentDescription("Call this contact");
        callChip.addView(KioskUi.text(c, "\u260E", 17, KioskUi.GOOD, Typeface.NORMAL));
        callChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callContact(); }
        });
        convHeader.addView(callChip, new LinearLayout.LayoutParams(
                KioskUi.dp(c, 42), KioskUi.dp(c, 42)));
        convPage.addView(convHeader, KioskUi.matchWrap());

        // address field: only shown for a brand-new conversation
        addressField = new EditText(c);
        addressField.setHint("To: phone number");
        addressField.setHintTextColor(KioskUi.FAINT);
        addressField.setTextColor(KioskUi.TXT);
        addressField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addressField.setInputType(InputType.TYPE_CLASS_PHONE);
        addressField.setSingleLine(true);
        addressField.setBackground(KioskUi.round(c, KioskUi.CARD2, 12));
        addressField.setPadding(KioskUi.dp(c, 14), KioskUi.dp(c, 10),
                KioskUi.dp(c, 14), KioskUi.dp(c, 10));
        addressField.setVisibility(View.GONE);
        LinearLayout.LayoutParams aLp = KioskUi.matchWrap();
        aLp.leftMargin = KioskUi.dp(c, 14);
        aLp.rightMargin = KioskUi.dp(c, 14);
        aLp.topMargin = KioskUi.dp(c, 10);
        convPage.addView(addressField, aLp);

        bubbleContainer = KioskUi.column(c);
        bubbleContainer.setPadding(0, KioskUi.dp(c, 8), 0, KioskUi.dp(c, 8));
        bubbleScroller = new ScrollView(c);
        bubbleScroller.setVerticalScrollBarEnabled(false);
        bubbleScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bubbleScroller.addView(bubbleContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bLp.topMargin = KioskUi.dp(c, 6);
        convPage.addView(bubbleScroller, bLp);

        status = KioskUi.text(c, "", 12, KioskUi.WARN, Typeface.NORMAL);
        status.setPadding(KioskUi.dp(c, 18), KioskUi.dp(c, 2), KioskUi.dp(c, 18), 0);
        status.setVisibility(View.GONE);
        convPage.addView(status, KioskUi.matchWrap());

        LinearLayout composeRow = KioskUi.row(c);
        composeRow.setPadding(KioskUi.dp(c, 14), KioskUi.dp(c, 8),
                KioskUi.dp(c, 14), KioskUi.dp(c, 12));

        compose = new EditText(c);
        compose.setHint("Message");
        compose.setHintTextColor(KioskUi.FAINT);
        compose.setTextColor(KioskUi.TXT);
        compose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        compose.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        compose.setMaxLines(3);
        compose.setBackground(KioskUi.round(c, KioskUi.CARD2, 16));
        compose.setPadding(KioskUi.dp(c, 14), KioskUi.dp(c, 10),
                KioskUi.dp(c, 14), KioskUi.dp(c, 10));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        composeRow.addView(compose, cLp);

        LinearLayout send = KioskUi.row(c);
        send.setGravity(Gravity.CENTER);
        send.setBackground(KioskUi.oval(c, KioskUi.ACCENT));
        send.setClickable(true);
        send.setFocusable(true);
        send.setContentDescription("Send message");
        send.addView(KioskUi.text(c, "\u27A4", 18, KioskUi.BG, Typeface.BOLD));
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                KioskUi.dp(c, 44), KioskUi.dp(c, 44));
        sendLp.leftMargin = KioskUi.dp(c, 10);
        composeRow.addView(send, sendLp);
        convPage.addView(composeRow, KioskUi.matchWrap());

        // live "send" when the keyboard's action key is used
        compose.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c2) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c2) {
                if (status.getVisibility() == View.VISIBLE) status.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable e) { }
        });

        // ================================================== assemble
        addView(listPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(convPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onShown() {
        if (convPage.getVisibility() == View.VISIBLE) {
            reloadConversation();
        } else {
            refreshThreads();
        }
        SmsReceiver.addListener(this);
    }

    @Override public void onHidden() {
        SmsReceiver.removeListener(this);
        try { compose.clearFocus(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ pages

    private void showList() {
        convPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        convAddress = "";
        convThreadId = 0L;
        try { compose.setText(""); } catch (Throwable ignored) { }
        try { compose.clearFocus(); } catch (Throwable ignored) { }
        refreshThreads();
    }

    /** Open a conversation; {@code address} may be empty for a brand-new message. */
    void openConversation(String address) {
        String digits = Calls.normalize(address);
        convAddress = digits;
        convThreadId = resolveThreadId(digits);
        KioskUi.setVisible(addressField, digits.isEmpty());
        if (digits.isEmpty()) {
            addressField.setText("");
            convTitle.setText("New message");
        } else {
            addressField.setText(digits);
            convTitle.setText(ContactNames.label(getContext(), digits));
        }
        status.setVisibility(View.GONE);
        listPage.setVisibility(View.GONE);
        convPage.setVisibility(View.VISIBLE);
        reloadConversation();
        try {
            if (digits.isEmpty()) addressField.requestFocus();
            else compose.requestFocus();
        } catch (Throwable ignored) { }
    }

    private long resolveThreadId(String digits) {
        if (digits.isEmpty()) return 0L;
        try {
            List<SmsStore.Thread> threads = SmsStore.threads(getContext(), 60);
            for (SmsStore.Thread t : threads) {
                if (digits.equals(ContactNames.digits(t.address))) return t.threadId;
            }
        } catch (Throwable ignored) { }
        return 0L;
    }

    private void callContact() {
        String addr = currentAddress();
        if (!addr.isEmpty()) host.placeCall(addr);
    }

    private String currentAddress() {
        if (!convAddress.isEmpty()) return convAddress;
        return Calls.normalize(addressField.getText() == null
                ? "" : addressField.getText().toString());
    }

    // ------------------------------------------------------------------ list

    private void refreshThreads() {
        Context c = getContext();
        threadContainer.removeAllViews();
        List<SmsStore.Thread> threads = SmsStore.threads(c, 40);

        if (threads.isEmpty()) {
            threadContainer.addView(KioskUi.emptyState(c,
                    SmsStore.hasTelephony(c)
                            ? "No conversations yet"
                            : "This device has no messaging radio"));
        } else {
            for (SmsStore.Thread t : threads) threadContainer.addView(threadRow(t));
        }
        host.setMessagesBadge(SmsStore.unreadTotal(c));
    }

    private View threadRow(final SmsStore.Thread t) {
        Context c = getContext();
        LinearLayout row = KioskUi.row(c);
        row.setBackground(KioskUi.round(c, KioskUi.CARD, 14));
        row.setPadding(KioskUi.dp(c, 12), KioskUi.dp(c, 12), KioskUi.dp(c, 12), KioskUi.dp(c, 12));
        row.setClickable(true);
        row.setFocusable(true);

        String label = ContactNames.label(c, t.address);
        row.addView(avatar(c, ContactNames.initials(label)));

        LinearLayout col = KioskUi.column(c);
        TextView name = KioskUi.text(c, label, 15, KioskUi.TXT,
                t.unread > 0 ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView snippet = KioskUi.text(c, t.snippet, 13, KioskUi.FAINT, Typeface.NORMAL);
        snippet.setSingleLine(true);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams snLp = KioskUi.matchWrap();
        snLp.topMargin = KioskUi.dp(c, 2);
        col.addView(snippet, snLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = KioskUi.dp(c, 12);
        row.addView(col, colLp);

        LinearLayout tail = KioskUi.column(c);
        tail.setGravity(Gravity.END);
        TextView time = KioskUi.text(c, TimeFmt.clock(c, t.dateMs), 11, KioskUi.FAINT,
                Typeface.NORMAL);
        tail.addView(time);
        if (t.unread > 0) {
            TextView badge = KioskUi.text(c, t.unread > 99 ? "99+" : String.valueOf(t.unread),
                    10, KioskUi.BG, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(KioskUi.oval(c, KioskUi.ACCENT));
            badge.setMinWidth(KioskUi.dp(c, 18));
            badge.setPadding(KioskUi.dp(c, 5), KioskUi.dp(c, 1), KioskUi.dp(c, 5), KioskUi.dp(c, 1));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = KioskUi.dp(c, 5);
            tail.addView(badge, bLp);
        }
        LinearLayout.LayoutParams tailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tailLp.leftMargin = KioskUi.dp(c, 8);
        row.addView(tail, tailLp);

        final String address = t.address;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openConversation(address); }
        });

        LinearLayout.LayoutParams rowLp = KioskUi.matchWrap();
        rowLp.leftMargin = KioskUi.dp(c, 18);
        rowLp.rightMargin = KioskUi.dp(c, 18);
        rowLp.topMargin = KioskUi.dp(c, 6);
        row.setLayoutParams(rowLp);
        return row;
    }

    // ------------------------------------------------------------------ conversation

    private void reloadConversation() {
        Context c = getContext();
        bubbleContainer.removeAllViews();
        String addr = currentAddress();

        if (addr.isEmpty()) {
            convTitle.setText("New message");
            KioskUi.setVisible(addressField, true);
            bubbleContainer.addView(KioskUi.emptyState(c, "Type a number, then a message"));
            return;
        }

        convAddress = addr;
        if (convThreadId == 0L) convThreadId = resolveThreadId(addr);
        convTitle.setText(ContactNames.label(c, addr));

        List<SmsStore.Msg> msgs = SmsStore.conversation(c, convThreadId, addr, 300);
        if (msgs.isEmpty()) {
            bubbleContainer.addView(KioskUi.emptyState(c, "No messages yet"));
        } else {
            for (SmsStore.Msg m : msgs) bubbleContainer.addView(bubble(m));
        }

        // Opening a thread is the read action: remember it locally so the unread
        // dot clears even though a non-default SMS app cannot write the provider.
        long newest = System.currentTimeMillis();
        if (!msgs.isEmpty()) newest = msgs.get(msgs.size() - 1).dateMs;
        SmsStore.markSeen(c, addr, newest);
        host.setMessagesBadge(SmsStore.unreadTotal(c));

        bubbleScroller.post(new Runnable() {
            @Override public void run() {
                try { bubbleScroller.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignored) { }
            }
        });
    }

    private View bubble(SmsStore.Msg m) {
        Context c = getContext();
        LinearLayout holder = KioskUi.column(c);
        holder.setGravity(m.outgoing ? Gravity.END : Gravity.START);
        holder.setPadding(KioskUi.dp(c, 18), KioskUi.dp(c, 3), KioskUi.dp(c, 18), KioskUi.dp(c, 3));

        LinearLayout inner = KioskUi.column(c);
        inner.setGravity(m.outgoing ? Gravity.END : Gravity.START);

        TextView body = KioskUi.text(c, m.body, 14, KioskUi.TXT, Typeface.NORMAL);
        body.setBackground(KioskUi.round(c,
                m.outgoing ? KioskUi.BUBBLE_OUT : KioskUi.BUBBLE_IN, 16));
        body.setPadding(KioskUi.dp(c, 13), KioskUi.dp(c, 9),
                KioskUi.dp(c, 13), KioskUi.dp(c, 9));
        try {
            body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.76f));
        } catch (Throwable ignored) { }
        inner.addView(body);

        TextView time = KioskUi.text(c, TimeFmt.clock(c, m.dateMs), 10, KioskUi.FAINT,
                Typeface.NORMAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = KioskUi.dp(c, 3);
        inner.addView(time, tLp);

        holder.addView(inner);
        return holder;
    }

    // ------------------------------------------------------------------ send

    private void sendMessage() {
        Context c = getContext();
        String addr = currentAddress();
        if (addr.isEmpty()) {
            showStatus("Enter a phone number first");
            return;
        }
        String body = compose.getText() == null ? "" : compose.getText().toString().trim();
        if (body.isEmpty()) return;

        boolean ok = SmsStore.send(c, addr, body);

        convAddress = addr;
        if (convThreadId == 0L) convThreadId = resolveThreadId(addr);
        KioskUi.setVisible(addressField, false);
        convTitle.setText(ContactNames.label(c, addr));
        compose.setText("");
        if (!ok) showStatus("Could not send \u2014 check signal and try again");
        else status.setVisibility(View.GONE);

        reloadConversation();
        host.setMessagesBadge(SmsStore.unreadTotal(c));
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ receiver

    /**
     * Called on the broadcast thread; hop to the UI thread and refresh what is
     * actually on screen. A message for the open conversation reloads the thread;
     * anything else refreshes the list.
     */
    @Override public void onIncomingSms(final String address, final String body, final long whenMs) {
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    if (convPage.getVisibility() == View.VISIBLE
                            && !convAddress.isEmpty()
                            && ContactNames.digits(convAddress)
                                    .equals(ContactNames.digits(address))) {
                        reloadConversation();
                    } else if (listPage.getVisibility() == View.VISIBLE) {
                        refreshThreads();
                    } else {
                        // hidden tab: still keep the dock badge honest
                        host.setMessagesBadge(SmsStore.unreadTotal(getContext()));
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private View avatar(Context c, String initials) {
        TextView t = KioskUi.text(c, initials, 14, KioskUi.ACCENT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(KioskUi.oval(c, KioskUi.ACCENT_SOFT));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                KioskUi.dp(c, 42), KioskUi.dp(c, 42)));
        return t;
    }
}
