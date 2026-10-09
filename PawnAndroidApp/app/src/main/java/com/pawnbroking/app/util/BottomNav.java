package com.pawnbroking.app.util;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.pawnbroking.app.BackupFilesActivity;
import com.pawnbroking.app.BillsActivity;
import com.pawnbroking.app.CustomersActivity;
import com.pawnbroking.app.EmployeeActivityActivity;
import com.pawnbroking.app.HomeActivity;
import com.pawnbroking.app.MonthlyReportActivity;
import com.pawnbroking.app.NotificationsActivity;
import com.pawnbroking.app.R;
import com.pawnbroking.app.SettingsActivity;
import com.pawnbroking.app.StockDetailsActivity;
import com.pawnbroking.app.TodaysAccountActivity;
import com.pawnbroking.app.TrialBalanceActivity;

/**
 * The bottom bar, and the More sheet behind its fourth tab.
 *
 * <p>Only the three top-level screens carry the bar. Everything else is
 * pushed on top of one of them with a back arrow, which is how Android
 * expects to be navigated and keeps the bar from appearing halfway down
 * a drill-down.
 *
 * <p>Switching tabs does not stack: each destination is launched
 * CLEAR_TOP | SINGLE_TOP, so tapping Home from Bills returns to the
 * Home already on the stack rather than building a second one. Without
 * that, Back after a few taps walks the user through every tab they
 * touched.
 */
public final class BottomNav {

    public enum Tab { HOME, BILLS, REPORTS }

    private BottomNav() { }

    public static void attach(final Activity a, Tab current,
                              final String companyId, final String companyName) {

        View bar = a.findViewById(R.id.bottomNav);
        if (bar == null) return;   // screen does not carry the bar

        light(a, current == Tab.HOME,    R.id.navHomeIcon,    R.id.navHomeLabel);
        light(a, current == Tab.BILLS,   R.id.navBillsIcon,   R.id.navBillsLabel);
        light(a, current == Tab.REPORTS, R.id.navReportsIcon, R.id.navReportsLabel);
        light(a, false,                  R.id.navMoreIcon,    R.id.navMoreLabel);

        tap(a, R.id.navHome,    current == Tab.HOME,
                () -> go(a, HomeActivity.class, companyId, companyName));
        tap(a, R.id.navBills,   current == Tab.BILLS,
                () -> go(a, BillsActivity.class, companyId, companyName));
        tap(a, R.id.navReports, current == Tab.REPORTS,
                () -> go(a, MonthlyReportActivity.class, companyId, companyName));
        tap(a, R.id.navMore,    false,
                () -> showMore(a, companyId, companyName));
    }

    /** The chosen tab goes gold; the rest stay the quiet wine-pink. */
    private static void light(Activity a, boolean on, @IdRes int icon, @IdRes int label) {
        int c = ContextCompat.getColor(a, on ? R.color.on_wine_title : R.color.nav_inactive);
        ImageView iv = a.findViewById(icon);
        TextView  tv = a.findViewById(label);
        if (iv != null) iv.setColorFilter(c);
        if (tv != null) {
            tv.setTextColor(c);
            tv.setTypeface(tv.getTypeface(), on ? android.graphics.Typeface.BOLD
                                                : android.graphics.Typeface.NORMAL);
        }
    }

    private static void tap(Activity a, @IdRes int id, boolean isCurrent, Runnable r) {
        View v = a.findViewById(id);
        if (v == null) return;
        // Tapping the tab you are already on should do nothing, not
        // reload the screen under you.
        v.setOnClickListener(isCurrent ? null : x -> r.run());
    }

    private static void go(Activity from, Class<?> to, String companyId, String companyName) {
        Intent i = new Intent(from, to);
        i.putExtra("companyId", companyId);
        i.putExtra("companyName", companyName);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
    }

    private static void showMore(final Activity a,
                                 final String companyId, final String companyName) {
        final BottomSheetDialog sheet = new BottomSheetDialog(a);
        View body = a.getLayoutInflater().inflate(R.layout.sheet_more, null);
        sheet.setContentView(body);

        row(body, R.id.moreCustomers,     sheet, a, CustomersActivity.class,       companyId, companyName);
        row(body, R.id.moreStock,         sheet, a, StockDetailsActivity.class,    companyId, companyName);
        row(body, R.id.moreTodays,        sheet, a, TodaysAccountActivity.class,   companyId, companyName);
        row(body, R.id.moreTrial,         sheet, a, TrialBalanceActivity.class,    companyId, companyName);
        row(body, R.id.moreActivity,      sheet, a, EmployeeActivityActivity.class, companyId, companyName);
        // These three are company-agnostic, but passing the extras does
        // no harm and keeps one code path.
        row(body, R.id.moreNotifications, sheet, a, NotificationsActivity.class,   companyId, companyName);
        row(body, R.id.moreBackups,       sheet, a, BackupFilesActivity.class,     companyId, companyName);
        row(body, R.id.moreSettings,      sheet, a, SettingsActivity.class,        companyId, companyName);

        sheet.show();
    }

    private static void row(View body, @IdRes int id, final BottomSheetDialog sheet,
                            final Activity a, final Class<?> to,
                            final String companyId, final String companyName) {
        View v = body.findViewById(id);
        if (v == null) return;
        v.setOnClickListener(x -> {
            sheet.dismiss();
            Intent i = new Intent(a, to);
            i.putExtra("companyId", companyId);
            i.putExtra("companyName", companyName);
            a.startActivity(i);
        });
    }
}
