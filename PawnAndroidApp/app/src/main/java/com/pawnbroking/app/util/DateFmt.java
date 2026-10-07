package com.pawnbroking.app.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One place that turns a stored timestamp into something a person reads.
 *
 * <p>The cloud hands back whatever PostgreSQL stored, which on a bill looks
 * like {@code 2026-08-25T19:49:09.586387}. That was being shown to shop staff
 * as-is on the Billing screen, and trimmed three different ways elsewhere —
 * one screen cut it to 16 characters, another parsed and reformatted it, a
 * third printed it raw. Same value, three appearances.
 *
 * <p>The house format is {@code 25/08/2026 - 7:49 pm}: day first, because that
 * is how a date is written here, and a 12-hour clock because that is how the
 * counter reads one. It matches what the desktop apps now print, so a bill
 * looks the same on a phone as on the till.
 */
public final class DateFmt {

    private DateFmt() { }

    /** The shapes the API actually returns, most specific first. */
    private static final String[] PATTERNS = {
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
    };

    /**
     * A timestamp as {@code dd/MM/yyyy - h:mm am/pm}.
     *
     * <p>Anything unrecognised is handed back untouched rather than blanked: a
     * value nobody can parse is still worth more on screen than nothing, and it
     * shows up as something to fix instead of disappearing silently.
     */
    public static String stamp(String raw) {
        Date d = parse(raw);
        if (d == null) return raw == null || "null".equals(raw) ? "" : raw;
        // A value that carried no time gets no time. Printing "12:00 am" for a
        // plain date invents a fact — it reads as midnight rather than unknown.
        if (raw.indexOf(':') < 0) {
            return new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d);
        }
        // Locale.US so the month and am/pm never come back localised — this is
        // a fixed house format, not something that should follow the handset.
        return new SimpleDateFormat("dd/MM/yyyy - h:mm", Locale.US).format(d)
                + new SimpleDateFormat(" a", Locale.US).format(d).toLowerCase(Locale.US);
    }

    /** Just the day, as {@code dd/MM/yyyy}, for fields that carry no time. */
    public static String day(String raw) {
        Date d = parse(raw);
        if (d == null) return raw == null || "null".equals(raw) ? "" : raw;
        return new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d);
    }

    private static Date parse(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        // Postgres microseconds (.586387) are more precision than SimpleDateFormat
        // handles cleanly, and nobody reads them — cut at the seconds.
        int dot = v.indexOf('.');
        if (dot > 0) v = v.substring(0, dot);
        for (String p : PATTERNS) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setLenient(false);
                return f.parse(v);
            } catch (Exception ignored) { /* try the next shape */ }
        }
        return null;
    }
}
