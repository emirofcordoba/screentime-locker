package com.vortex.timelock;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Contact-name resolution for the in-kiosk dialer and SMS client.
 *
 * <p>Both surfaces show a phone number; a name is strictly better when one is
 * known. This module is the single place that talks to the Contacts provider, so
 * the dialer and the messages screen never each grow their own query.
 *
 * <p><b>Never fatal.</b> Every method is wrapped: a missing permission, an
 * OEM contacts provider that answers with a {@code null} cursor, or a plain
 * device with no contacts at all all resolve to "no name", and the caller falls
 * back to the raw number. The kiosk must keep rendering even when the contacts
 * store is unreadable.
 *
 * <p>{@code READ_CONTACTS} is a DANGEROUS permission and is pinned to GRANTED by
 * {@link Engine#applyPermissionLockdown} once the app is Device Owner, so by the
 * time this runs inside the kiosk the read normally succeeds.
 */
final class ContactNames {

    private static final String TAG = "TL.Contacts";

    private ContactNames() { }

    /**
     * Display name for a raw phone number, or {@code null} when the number is not
     * in the address book (or contacts are unreadable). Uses
     * {@link ContactsContract.PhoneLookup}, which matches on the normalised number
     * so "+1 (555) 010-1234" and "5550101234" resolve to the same contact.
     */
    static String name(Context c, String number) {
        if (c == null || TextUtils.isEmpty(number)) return null;
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            cur = c.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (!TextUtils.isEmpty(n)) return n;
            }
        } catch (Throwable t) {
            Log.w(TAG, "name", t);
        } finally {
            close(cur);
        }
        return null;
    }

    /** One row of the name-search result. */
    static final class Match {
        final String name;
        final String number;
        Match(String name, String number) { this.name = name; this.number = number; }
    }

    /**
     * Typeahead search across the address book: contacts whose display name or
     * normalised number starts with / contains the query. Newest sort first is
     * not meaningful here; results are returned in provider order and capped by
     * {@code limit}. Returns an empty list on any failure.
     */
    static List<Match> search(Context c, String query, int limit) {
        List<Match> out = new ArrayList<>();
        if (c == null || TextUtils.isEmpty(query)) return out;
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    Uri.encode(query));
            cur = c.getContentResolver().query(uri,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null);
            if (cur != null) {
                int nameIdx = cur.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = cur.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (cur.moveToNext() && out.size() < limit) {
                    String n = nameIdx >= 0 ? cur.getString(nameIdx) : null;
                    String num = numIdx >= 0 ? cur.getString(numIdx) : null;
                    if (TextUtils.isEmpty(num)) continue;
                    // Skip duplicate numbers (a contact with several numbers).
                    boolean dupe = false;
                    String key = digits(num);
                    for (Match m : out) if (digits(m.number).equals(key)) { dupe = true; break; }
                    if (!dupe) out.add(new Match(TextUtils.isEmpty(n) ? num : n, num));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "search", t);
        } finally {
            close(cur);
        }
        return out;
    }

    /**
     * Pretty label for a conversation / call row: the contact name when known,
     * otherwise a lightly formatted version of the number itself. Never returns
     * {@code null} (falls back to the raw input, then to "Unknown").
     */
    static String label(Context c, String number) {
        if (TextUtils.isEmpty(number)) return "Unknown";
        String n = name(c, number);
        if (!TextUtils.isEmpty(n)) return n;
        return prettyNumber(number);
    }

    /** "+15550101234" -> "+1 555 010 1234" where the platform can; else raw. */
    static String prettyNumber(String number) {
        if (TextUtils.isEmpty(number)) return "Unknown";
        try {
            String f = PhoneNumberUtils.formatNumber(number, "US");
            if (!TextUtils.isEmpty(f)) return f;
        } catch (Throwable ignored) { }
        return number;
    }

    /** Digits only, for normalised comparison. */
    static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') b.append(ch);
        }
        return b.toString();
    }

    /** Best-effort initial(s) for an avatar bubble: "Ada Lovelace" -> "AL". */
    static String initials(String label) {
        if (TextUtils.isEmpty(label)) return "#";
        String[] parts = label.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            char ch = p.charAt(0);
            if (Character.isLetterOrDigit(ch)) b.append(Character.toUpperCase(ch));
            if (b.length() == 2) break;
        }
        if (b.length() == 0) return "#";
        return b.toString();
    }

    private static void close(Cursor c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) { }
    }
}
