package com.vortex.timelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kiosk's SMS client: read + send, entirely self-contained.
 *
 * <p>Reading uses the system SMS provider ({@code content://sms}), guarded by
 * {@code READ_SMS}. Sending uses {@link SmsManager}, guarded by
 * {@code SEND_SMS}. Both permissions are DANGEROUS and are pinned GRANTED by the
 * Device-Owner permission lockdown, so the kiosk never has to ask.
 *
 * <p><b>Why there is a local outbox.</b> Only the device's <em>default</em> SMS
 * app is allowed to insert into the SMS provider. The retreat is not (and will
 * not silently become) the default SMS app, so a message we send through
 * {@link SmsManager} goes out on the radio but Android does not write it into the
 * provider's "sent" table. Without help the conversation would look like the
 * user's own messages vanished. The outbox therefore keeps a small, private,
 * per-address copy of everything sent from the kiosk and merges it back into the
 * thread on read. It is best-effort and fully contained in this app.
 *
 * <p><b>Never fatal.</b> Every provider call is wrapped; an unreadable provider
 * yields an empty list, and a refused send yields {@code false} so the UI can say
 * so instead of pretending.
 */
final class SmsStore {

    private static final String TAG = "TL.Sms";
    private static final Uri SMS_URI = Uri.parse("content://sms");

    /** Hard cap on rows scanned when building the thread list (keeps it cheap). */
    private static final int MAX_SCAN = 4000;

    private static final String OUTBOX = "tl_sms_outbox";
    private static final String OUTBOX_KEY = "sent";

    /**
     * Local per-address "seen" watermarks: the newest message timestamp the user
     * has actually looked at for a conversation. The retreat is not the default
     * SMS app, so it cannot write the provider's {@code read} column; it keeps
     * its own watermark instead so the unread dot clears once a thread is opened.
     */
    private static final String SEEN = "tl_sms_seen";

    private SmsStore() { }

    // ------------------------------------------------------------------ models

    /** One conversation summary. */
    static final class Thread {
        final long threadId;
        final String address;
        final String snippet;
        final long dateMs;
        final int unread;
        Thread(long threadId, String address, String snippet, long dateMs, int unread) {
            this.threadId = threadId;
            this.address = address;
            this.snippet = snippet == null ? "" : snippet;
            this.dateMs = dateMs;
            this.unread = unread;
        }
    }

    /** One message inside a conversation. */
    static final class Msg {
        final long id;
        final String address;
        final String body;
        final long dateMs;
        final boolean outgoing;
        Msg(long id, String address, String body, long dateMs, boolean outgoing) {
            this.id = id;
            this.address = address;
            this.body = body == null ? "" : body;
            this.dateMs = dateMs;
            this.outgoing = outgoing;
        }
    }

    // ------------------------------------------------------------------ read

    /** True when the device can actually do SMS (has a telephony radio). */
    static boolean hasTelephony(Context c) {
        try {
            return c.getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_TELEPHONY);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Newest-first conversation list. Provider rows are grouped by {@code
     * thread_id}; addresses that only exist in the local outbox are appended so a
     * just-sent first message still creates a visible conversation.
     */
    static List<Thread> threads(Context c, int limit) {
        List<Thread> out = new ArrayList<>();
        if (c == null) return out;

        Map<String, Thread> byKey = new LinkedHashMap<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(SMS_URI,
                    new String[]{"_id", "thread_id", "address", "body", "date", "type", "read"},
                    null, null, "date DESC");
            if (cur != null) {
                int tidIdx = cur.getColumnIndex("thread_id");
                int addrIdx = cur.getColumnIndex("address");
                int bodyIdx = cur.getColumnIndex("body");
                int dateIdx = cur.getColumnIndex("date");
                int typeIdx = cur.getColumnIndex("type");
                int readIdx = cur.getColumnIndex("read");
                int scanned = 0;
                while (cur.moveToNext() && scanned++ < MAX_SCAN && byKey.size() <= limit) {
                    String address = addrIdx >= 0 ? cur.getString(addrIdx) : null;
                    if (TextUtils.isEmpty(address)) continue;
                    long tid = tidIdx >= 0 ? cur.getLong(tidIdx) : 0L;
                    String key = tid > 0 ? ("t" + tid) : ("a" + ContactNames.digits(address));
                    if (byKey.containsKey(key)) {
                        Thread existing = byKey.get(key);
                        int read = readIdx >= 0 ? cur.getInt(readIdx) : 1;
                        int type = typeIdx >= 0 ? cur.getInt(typeIdx) : 0;
                        if (read == 0 && type == 1) {
                            byKey.put(key, new Thread(existing.threadId, existing.address,
                                    existing.snippet, existing.dateMs, existing.unread + 1));
                        }
                        continue;
                    }
                    String body = bodyIdx >= 0 ? cur.getString(bodyIdx) : "";
                    long date = dateIdx >= 0 ? cur.getLong(dateIdx) : 0L;
                    int read = readIdx >= 0 ? cur.getInt(readIdx) : 1;
                    int type = typeIdx >= 0 ? cur.getInt(typeIdx) : 0;
                    int unread = (read == 0 && type == 1) ? 1 : 0;
                    byKey.put(key, new Thread(tid, address, body, date, unread));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "threads", t);
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) { }
        }
        out.addAll(byKey.values());

        // Outbox-only conversations (a message we sent that the provider has no
        // thread for yet). Keyed by normalised number.
        for (Map.Entry<String, Long> e : outboxAddresses(c).entrySet()) {
            boolean present = false;
            String key = e.getKey();
            for (Thread t : out) {
                if (ContactNames.digits(t.address).equals(key)) { present = true; break; }
            }
            if (!present) {
                String snippet = lastOutboxBody(c, e.getKey());
                out.add(new Thread(0L, key, snippet, e.getValue(), 0));
            }
        }

        Collections.sort(out, new Comparator<Thread>() {
            @Override public int compare(Thread a, Thread b) {
                return Long.compare(b.dateMs, a.dateMs);
            }
        });
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    /**
     * The conversation with one address, oldest-first so it reads top-to-bottom.
     * Provider rows and local-outbox rows are merged and sorted by time.
     */
    static List<Msg> conversation(Context c, long threadId, String address, int limit) {
        List<Msg> out = new ArrayList<>();
        if (c == null) return out;

        Cursor cur = null;
        try {
            String sel;
            String[] args;
            if (threadId > 0L) {
                sel = "thread_id = ?"; args = new String[]{String.valueOf(threadId)};
            } else {
                sel = "address = ?"; args = new String[]{address};
            }
            cur = c.getContentResolver().query(SMS_URI,
                    new String[]{"_id", "address", "body", "date", "type"},
                    sel, args, "date ASC");
            if (cur != null) {
                int idIdx = cur.getColumnIndex("_id");
                int addrIdx = cur.getColumnIndex("address");
                int bodyIdx = cur.getColumnIndex("body");
                int dateIdx = cur.getColumnIndex("date");
                int typeIdx = cur.getColumnIndex("type");
                while (cur.moveToNext()) {
                    long id = idIdx >= 0 ? cur.getLong(idIdx) : 0L;
                    String a = addrIdx >= 0 ? cur.getString(addrIdx) : address;
                    String body = bodyIdx >= 0 ? cur.getString(bodyIdx) : "";
                    long date = dateIdx >= 0 ? cur.getLong(dateIdx) : 0L;
                    int type = typeIdx >= 0 ? cur.getInt(typeIdx) : 0;
                    out.add(new Msg(id, a, body, date, type == 2 /* MESSAGE_TYPE_SENT */));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "conversation", t);
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) { }
        }

        // Merge local outbox rows for this address.
        String key = ContactNames.digits(address);
        for (JSONObject o : outbox(c)) {
            try {
                if (!key.equals(ContactNames.digits(o.optString("a")))) continue;
                out.add(new Msg(0L, o.optString("a"), o.optString("b"),
                        o.optLong("t"), true));
            } catch (Throwable ignored) { }
        }

        Collections.sort(out, new Comparator<Msg>() {
            @Override public int compare(Msg a, Msg b) { return Long.compare(a.dateMs, b.dateMs); }
        });
        if (out.size() > limit) return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        return out;
    }

    /** Best-effort "mark this thread read" so the unread dots clear. */
    static void markRead(Context c, long threadId, String address) {
        if (c == null) return;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("read", 1);
            if (threadId > 0L) {
                c.getContentResolver().update(SMS_URI, v, "thread_id = ?",
                        new String[]{String.valueOf(threadId)});
            } else {
                c.getContentResolver().update(SMS_URI, v, "address = ?",
                        new String[]{address});
            }
        } catch (Throwable t) {
            Log.w(TAG, "markRead", t);
        }
    }

    /**
     * Remember that the user has seen everything in {@code address} up to
     * {@code whenMs}. Stored as a monotonic per-number watermark (keyed by the
     * digits of the address) so re-opening an older thread can never "un-clear" a
     * newer message. Best-effort: a failed write just leaves the dot on.
     */
    static void markSeen(Context c, String address, long whenMs) {
        if (c == null) return;
        try {
            String key = ContactNames.digits(address);
            if (key.isEmpty()) return;
            SharedPreferences p = seen(c);
            if (whenMs > p.getLong(key, 0L)) {
                p.edit().putLong(key, whenMs).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "markSeen", t);
        }
    }

    /**
     * How many incoming messages are genuinely unread across every conversation
     * -- i.e. provider rows still flagged {@code read = 0} whose timestamp is
     * newer than the local "seen" watermark for that address. This is what drives
     * the kiosk dock badge and the incoming-SMS banner. Never fatal: an
     * unreadable provider reads as zero.
     */
    static int unreadTotal(Context c) {
        if (c == null) return 0;
        SharedPreferences p;
        try {
            p = seen(c);
        } catch (Throwable t) {
            return 0;
        }
        int total = 0;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(SMS_URI,
                    new String[]{"address", "date"},
                    "read = 0 AND type = 1", null, "date DESC");
            if (cur != null) {
                int addrIdx = cur.getColumnIndex("address");
                int dateIdx = cur.getColumnIndex("date");
                int scanned = 0;
                while (cur.moveToNext() && scanned < MAX_SCAN) {
                    scanned++;
                    String a = addrIdx >= 0 ? cur.getString(addrIdx) : "";
                    long d = dateIdx >= 0 ? cur.getLong(dateIdx) : 0L;
                    String key = ContactNames.digits(a);
                    long watermark = key.isEmpty() ? 0L : p.getLong(key, 0L);
                    if (d > watermark) total++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "unreadTotal", t);
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) { }
        }
        return total;
    }

    private static SharedPreferences seen(Context c) {
        return c.getApplicationContext().getSharedPreferences(SEEN, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ send

    /**
     * Send an SMS and record it in the local outbox so the conversation shows it.
     * Returns {@code true} when the message was handed to the SMS stack.
     */
    static boolean send(Context c, String address, String body) {
        if (c == null || TextUtils.isEmpty(address) || TextUtils.isEmpty(body)) return false;
        boolean ok = false;
        try {
            SmsManager sm = smsManager(c);
            if (sm != null) {
                ArrayList<String> parts = sm.divideMessage(body);
                if (parts == null || parts.size() <= 1) {
                    sm.sendTextMessage(address, null, body, null, null);
                } else {
                    sm.sendMultipartTextMessage(address, null, parts, null, null);
                }
                ok = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "send", t);
        }
        // Record regardless of the radio result: the user typed it and must see
        // it in the thread, with the honest "sending" state owned by the UI.
        Outbox.add(c, address, body, System.currentTimeMillis());
        return ok;
    }

    private static SmsManager smsManager(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                return c.getSystemService(SmsManager.class);
            }
            return SmsManager.getDefault();
        } catch (Throwable t) {
            Log.w(TAG, "smsManager", t);
            return null;
        }
    }

    /**
     * Subscription id for the device's default SMS subscription (multi-SIM), or
     * {@code -1} when it cannot be resolved. Uses the public static
     * {@link SubscriptionManager#getDefaultSmsSubscriptionId()} (API 24+), which
     * needs {@code READ_PHONE_STATE} -- pinned GRANTED by the Device-Owner
     * permission lockdown. Never fatal: any failure reads as "no preference",
     * which the SMS stack treats as the default subscription anyway.
     */
    static int defaultSubscriptionId(Context c) {
        try {
            return android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        } catch (Throwable ignored) { }
        return -1;
    }

    // ------------------------------------------------------------------ outbox

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(OUTBOX, Context.MODE_PRIVATE);
    }

    private static List<JSONObject> outbox(Context c) {
        List<JSONObject> out = new ArrayList<>();
        if (c == null) return out;
        try {
            String raw = sp(c).getString(OUTBOX_KEY, null);
            if (TextUtils.isEmpty(raw)) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "outbox read", t);
        }
        return out;
    }

    /** address(digits) -> newest outbox timestamp; used to surface new threads. */
    private static Map<String, Long> outboxAddresses(Context c) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JSONObject o : outbox(c)) {
            String a = ContactNames.digits(o.optString("a"));
            if (a.isEmpty()) continue;
            long t = o.optLong("t");
            Long prev = out.get(a);
            if (prev == null || t > prev) out.put(a, t);
        }
        return out;
    }

    private static String lastOutboxBody(Context c, String digitsKey) {
        String body = "";
        long best = -1;
        for (JSONObject o : outbox(c)) {
            if (!digitsKey.equals(ContactNames.digits(o.optString("a")))) continue;
            long t = o.optLong("t");
            if (t > best) { best = t; body = o.optString("b"); }
        }
        return body;
    }

    /** Tiny private store of messages sent from the kiosk; capped so it stays small. */
    static final class Outbox {
        private static final int MAX = 300;

        static synchronized void add(Context c, String address, String body, long whenMs) {
            try {
                List<JSONObject> all = outbox(c);
                JSONObject o = new JSONObject();
                o.put("a", address);
                o.put("b", body);
                o.put("t", whenMs);
                all.add(0, o);
                JSONArray arr = new JSONArray();
                for (int i = 0; i < all.size() && i < MAX; i++) arr.put(all.get(i));
                sp(c).edit().putString(OUTBOX_KEY, arr.toString()).apply();
            } catch (Throwable t) {
                Log.w(TAG, "outbox add", t);
            }
        }

        /** Forget everything (used when the retreat is torn down / reset). */
        static synchronized void clear(Context c) {
            try { sp(c).edit().remove(OUTBOX_KEY).apply(); } catch (Throwable ignored) { }
        }
    }
}
