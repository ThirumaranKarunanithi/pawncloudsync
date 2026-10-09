package com.pawnbroking.app.util;

import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.pawnbroking.app.R;

/**
 * Paints a bill's status as a pill.
 *
 * <p>One place, so the bill list, the bill detail and the search result
 * cannot drift apart — they each used to carry their own switch over the
 * same four strings.
 *
 * <p>The pills differ in lightness as well as hue, so they still read
 * apart on a photocopied slip or to an eye that does not separate red
 * from green.
 */
public final class StatusPill {

    private StatusPill() { }

    public static void paint(TextView tv, String status) {
        int bg;
        int fg;
        switch (status == null ? "" : status.trim().toUpperCase()) {
            case "OPENED":
            case "OPEN":
                bg = R.drawable.pill_open;
                fg = R.color.emerald_ink;
                break;
            case "REPLEDGED":
            case "GIVEN":
                bg = R.drawable.pill_repledged;
                fg = R.color.violet;
                break;
            case "SUSPENSE":
                bg = R.drawable.pill_due;
                fg = R.color.on_wine;
                break;
            case "CLOSED":
            case "DELIVERED":
            case "REBILLED":
            default:
                bg = R.drawable.pill_closed;
                fg = R.color.ink_body;
                break;
        }
        // A pill needs its padding back: setBackgroundResource drops
        // whatever the layout set.
        int px = tv.getPaddingStart();
        int py = tv.getPaddingTop();
        tv.setBackgroundResource(bg);
        tv.setPadding(px, py, px, py);
        tv.setTextColor(ContextCompat.getColor(tv.getContext(), fg));
    }
}
