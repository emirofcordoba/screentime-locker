package com.vortex.timelock;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view over the system call log, surfaced as the kiosk dialer's
 * "Recent" list.
 *
 * <p>This is the one place the kiosk reads {@link CallLog}. It never writes to
 * the log and never dials; it hands back a frozen, newest-first list of
 * {@link Call} rows that {@link DialerScreen} paints. Reading the log needs
 * {@code READ_CALL_LOG}, which is pinned GRANTED by the Device-Owner permission
 * lockdown, so the query normally succeeds inside the kiosk.
 *
 * <p><b>Never fatal.</b> A missing permission or an OEM provider that refuses
 * the cursor resolves to an empty list -- the dialer still works, it simply has
 * no history to show.
 */
final class CallLogStore {

    private static final String TAG = "TL.CallLog";

    private CallLogStore() { }

    /** Call directions, mapped to a stable enum the UI can switch on. */
    enum Dir { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, UNKNOWN }

    /** One immutable recent-call row. */
    static final class Call {
        final String number;
        final String cachedName;   // provider-supplied cached contact name, may be null
        final Dir dir;
        final long dateMs;
        final long durationSec;

        Call(String number, String cachedName, Dir dir, long dateMs, long durationSec) {
            this.number = number;
            this.cachedName = cachedName;
            this.dir = dir;
            this.dateMs = dateMs;
            this.durationSec = durationSec;
        }

        /** Display name: cached contact name, else live lookup, else the number. */
        String label(Context c) {
            if (!TextUtils.isEmpty(cachedName)) return cachedName;
            return ContactNames.label(c, number);
        }
    }

    /**
     * Newest-first recent calls, de-duplicated so the list does not read as a
     * wall of repeats. {@code limit} caps how many distinct numbers are returned.
     */
    static List<Call> recent(Context c, int limit) {
        List<Call> out = new ArrayList<>();
        if (c == null) return out;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION
                    },
                    null, null,
                    CallLog.Calls.DATE + " DESC");
            if (cur != null) {
                int numIdx  = cur.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIdx = cur.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int typeIdx = cur.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cur.getColumnIndex(CallLog.Calls.DATE);
                int durIdx  = cur.getColumnIndex(CallLog.Calls.DURATION);

                List<String> seen = new ArrayList<>();
                while (cur.moveToNext() && out.size() < limit) {
                    String number = numIdx >= 0 ? cur.getString(numIdx) : null;
                    if (TextUtils.isEmpty(number)) continue;
                    // Collapse consecutive duplicates of the same number so the
                    // recent list stays scannable instead of showing "Mom" 20x.
                    String key = ContactNames.digits(number);
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    String name = nameIdx >= 0 ? cur.getString(nameIdx) : null;
                    int type = typeIdx >= 0 ? cur.getInt(typeIdx) : -1;
                    long date = dateIdx >= 0 ? cur.getLong(dateIdx) : 0L;
                    long dur  = durIdx >= 0 ? cur.getLong(durIdx) : 0L;
                    out.add(new Call(number, name, mapType(type), date, dur));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "recent", t);
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static Dir mapType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return Dir.INCOMING;
            case CallLog.Calls.OUTGOING_TYPE: return Dir.OUTGOING;
            case CallLog.Calls.MISSED_TYPE:   return Dir.MISSED;
            case CallLog.Calls.REJECTED_TYPE: return Dir.REJECTED;
            case CallLog.Calls.BLOCKED_TYPE:  return Dir.BLOCKED;
            case CallLog.Calls.VOICEMAIL_TYPE:return Dir.VOICEMAIL;
            default: return Dir.UNKNOWN;
        }
    }
}
