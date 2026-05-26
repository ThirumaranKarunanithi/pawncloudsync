package com.pawnbroking.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MIS Report — exact replica of desktop MISReportController.
 *
 * 10 columns (as in desktop AllDetailsBean):
 *  Month | Open# | Open Amt | Redeem# | Redeem Amt | Profit |
 *  Stock# (cumulative) | Stock Amt (cumulative) | Earned# | Earned Amt
 *
 * Tap a row to toggle its selection. Mode buttons (All / Selected / Deselected)
 * control which rows feed the Summary card at the top.
 */
public class MonthlyReportActivity extends AppCompatActivity {

    // ── Views ─────────────────────────────────────────────────────────────────

    private ProgressBar progressBar;
    private TableLayout tableMonthly;
    private TextView tvCompanyName, tvRowCount, tvSummaryMonths;
    private TextView tvSumPawnBills, tvSumPawnAmt;
    private TextView tvSumRedeemBills, tvSumRedeemAmt;
    private TextView tvSumProfit;
    private TextView tvSumStockBills, tvSumStockAmt;
    private TextView tvSumEarnedBills, tvSumEarnedAmt;
    private View layoutControls, layoutSummary;
    private Button btnAll, btnSelected, btnDeselected;
    private Button btnSelectAll, btnDeselectAll;

    // ── Data ──────────────────────────────────────────────────────────────────

    private static class MisRow {
        String month;
        long   pawnBills, redeemBills, stockBills, earnedBills;
        double pawnAmt, redeemAmt, profit, stockAmt, earnedAmt;
        boolean selected = true;
        TableRow tableRow; // reference so we can repaint on tap
    }

    private final List<MisRow> rows = new ArrayList<>();
    private String mode = "ALL"; // ALL | SELECTED | DESELECTED
    private String companyId, companyName;

    // ── Column config ─────────────────────────────────────────────────────────

    // Header labels (10 columns matching desktop)
    private static final String[] HEADERS = {
        "Month", "Open\n#", "Open\nAmt", "Redeem\n#", "Redeem\nAmt",
        "Profit", "Stock\n#", "Stock\nAmt", "Earned\n#", "Earned\nAmt"
    };
    // Minimum column widths in dp
    private static final int[] COL_WIDTHS_DP = {
        72, 44, 72, 44, 72, 72, 44, 72, 44, 72
    };

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_BLUE   = Color.parseColor("#64B5F6");
    private static final int COL_GREEN  = Color.parseColor("#A5D6A7");
    private static final int COL_WHITE  = Color.WHITE;
    private static final int COL_GOLD   = Color.parseColor("#E6B800");
    private static final int COL_GREY   = Color.parseColor("#AAAAAA");
    private static final int BG_HEADER  = Color.parseColor("#1E2A4A");
    private static final int BG_EVEN    = Color.parseColor("#16213E");
    private static final int BG_ODD     = Color.parseColor("#1A2744");
    private static final int BG_SELECTED= Color.parseColor("#1B3A5C"); // highlight
    private static final int BG_TOTALS  = Color.parseColor("#1E2A4A");

    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monthly_report);

        fmt.setMinimumFractionDigits(0);
        fmt.setMaximumFractionDigits(0);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("MIS Report");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) { load(); return true; }
            return false;
        });

        tvCompanyName   = findViewById(R.id.tvCompanyName);
        tvRowCount      = findViewById(R.id.tvRowCount);
        progressBar     = findViewById(R.id.progressBar);
        tableMonthly    = findViewById(R.id.tableMonthly);
        layoutControls  = findViewById(R.id.layoutControls);
        layoutSummary   = findViewById(R.id.layoutSummary);

        tvSummaryMonths = findViewById(R.id.tvSummaryMonths);
        tvSumPawnBills  = findViewById(R.id.tvSumPawnBills);
        tvSumPawnAmt    = findViewById(R.id.tvSumPawnAmt);
        tvSumRedeemBills= findViewById(R.id.tvSumRedeemBills);
        tvSumRedeemAmt  = findViewById(R.id.tvSumRedeemAmt);
        tvSumProfit     = findViewById(R.id.tvSumProfit);
        tvSumStockBills = findViewById(R.id.tvSumStockBills);
        tvSumStockAmt   = findViewById(R.id.tvSumStockAmt);
        tvSumEarnedBills= findViewById(R.id.tvSumEarnedBills);
        tvSumEarnedAmt  = findViewById(R.id.tvSumEarnedAmt);

        btnAll        = findViewById(R.id.btnAll);
        btnSelected   = findViewById(R.id.btnSelected);
        btnDeselected = findViewById(R.id.btnDeselected);
        btnSelectAll   = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);

        tvCompanyName.setText(companyName);

        btnAll.setOnClickListener(v        -> setMode("ALL"));
        btnSelected.setOnClickListener(v   -> setMode("SELECTED"));
        btnDeselected.setOnClickListener(v -> setMode("DESELECTED"));
        btnSelectAll.setOnClickListener(v   -> selectAll(true));
        btnDeselectAll.setOnClickListener(v -> selectAll(false));

        load();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tableMonthly.removeAllViews();
        rows.clear();
        layoutControls.setVisibility(View.GONE);
        layoutSummary.setVisibility(View.GONE);

        ApiService.getMonthlyReport(companyId, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    bind(data);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MonthlyReportActivity.this,
                        "Error: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ── Bind data ─────────────────────────────────────────────────────────────

    private void bind(JSONObject data) {
        JSONArray months = data.optJSONArray("months");
        int total        = data.optInt("total", 0);
        tvRowCount.setText(total + " months");

        if (months == null || months.length() == 0) return;

        // Build MisRow list
        for (int i = 0; i < months.length(); i++) {
            JSONObject m = months.optJSONObject(i);
            if (m == null) continue;
            MisRow r = new MisRow();
            r.month       = m.optString("month", "");
            r.pawnBills   = m.optLong("pawnBills",    0);
            r.pawnAmt     = m.optDouble("pawnAmount",  0);
            r.redeemBills = m.optLong("redeemBills",  0);
            r.redeemAmt   = m.optDouble("redeemAmount",0);
            r.profit      = m.optDouble("profit",      0);
            r.stockBills  = m.optLong("stockBills",   0);
            r.stockAmt    = m.optDouble("stockAmount", 0);
            r.earnedBills = m.optLong("earnedBills",  0);
            r.earnedAmt   = m.optDouble("earnedAmount",0);
            r.selected    = true;
            rows.add(r);
        }

        // Column header row
        tableMonthly.addView(buildHeaderRow());

        // Data rows
        for (int i = 0; i < rows.size(); i++) {
            MisRow r = rows.get(i);
            TableRow tr = buildDataRow(r, i);
            r.tableRow = tr;
            final int idx = i;
            tr.setOnClickListener(v -> onRowTap(idx));
            tableMonthly.addView(tr);
        }

        // Divider + totals row
        tableMonthly.addView(buildDivider());
        tableMonthly.addView(buildTotalsRow());

        // Show UI
        layoutControls.setVisibility(View.VISIBLE);
        layoutSummary.setVisibility(View.VISIBLE);
        refreshSummary();
        refreshModeButtons();
    }

    // ── Row tap (toggle selection) ─────────────────────────────────────────────

    private void onRowTap(int idx) {
        MisRow r = rows.get(idx);
        r.selected = !r.selected;
        int bg = r.selected ? BG_SELECTED : (idx % 2 == 0 ? BG_EVEN : BG_ODD);
        r.tableRow.setBackgroundColor(bg);
        refreshSummary();
    }

    // ── Select All / Deselect All ─────────────────────────────────────────────

    private void selectAll(boolean select) {
        for (int i = 0; i < rows.size(); i++) {
            MisRow r = rows.get(i);
            r.selected = select;
            int bg = select ? BG_SELECTED : (i % 2 == 0 ? BG_EVEN : BG_ODD);
            r.tableRow.setBackgroundColor(bg);
        }
        refreshSummary();
    }

    // ── Mode switch ───────────────────────────────────────────────────────────

    private void setMode(String newMode) {
        mode = newMode;
        refreshModeButtons();
        refreshSummary();
    }

    private void refreshModeButtons() {
        btnAll.setTextColor(       "ALL".equals(mode)        ? COL_GOLD : COL_GREY);
        btnSelected.setTextColor(  "SELECTED".equals(mode)   ? COL_GOLD : COL_GREY);
        btnDeselected.setTextColor("DESELECTED".equals(mode) ? COL_GOLD : COL_GREY);
    }

    // ── Summary recalculation (mirrors desktop setCompanyHeaderValues) ────────

    private void refreshSummary() {
        long months = 0;
        long pawnBills = 0, redeemBills = 0, stockBills = 0, earnedBills = 0;
        double pawnAmt = 0, redeemAmt = 0, profit = 0, stockAmt = 0, earnedAmt = 0;

        for (MisRow r : rows) {
            boolean include = "ALL".equals(mode)
                    || ("SELECTED".equals(mode)   &&  r.selected)
                    || ("DESELECTED".equals(mode) && !r.selected);
            if (include) {
                months++;
                pawnBills   += r.pawnBills;   pawnAmt   += r.pawnAmt;
                redeemBills += r.redeemBills; redeemAmt += r.redeemAmt;
                profit      += r.profit;
                stockBills  += r.stockBills;  stockAmt  += r.stockAmt;
                earnedBills += r.earnedBills; earnedAmt += r.earnedAmt;
            }
        }

        tvSummaryMonths.setText("(" + months + " month" + (months != 1 ? "s" : "") + ")");
        tvSumPawnBills.setText(String.valueOf(pawnBills));
        tvSumPawnAmt.setText("₹" + shortFmt(pawnAmt));
        tvSumRedeemBills.setText(String.valueOf(redeemBills));
        tvSumRedeemAmt.setText("₹" + shortFmt(redeemAmt));
        tvSumProfit.setText("₹" + shortFmt(profit));
        tvSumStockBills.setText(String.valueOf(stockBills));
        tvSumStockAmt.setText("₹" + shortFmt(stockAmt));
        tvSumEarnedBills.setText(String.valueOf(earnedBills));
        tvSumEarnedAmt.setText("₹" + shortFmt(earnedAmt));
    }

    // ── Table row builders ────────────────────────────────────────────────────

    private TableRow buildHeaderRow() {
        TableRow tr = new TableRow(this);
        tr.setBackgroundColor(BG_HEADER);
        for (int j = 0; j < HEADERS.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(HEADERS[j]);
            tv.setTextColor(COL_GOLD);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(j == 0 ? Gravity.START : Gravity.CENTER);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));
            tr.addView(tv);
        }
        return tr;
    }

    private TableRow buildDataRow(MisRow r, int idx) {
        TableRow tr = new TableRow(this);
        tr.setBackgroundColor(r.selected ? BG_SELECTED : (idx % 2 == 0 ? BG_EVEN : BG_ODD));

        String[] vals = {
            r.month,
            String.valueOf(r.pawnBills),  shortFmt(r.pawnAmt),
            String.valueOf(r.redeemBills),shortFmt(r.redeemAmt),
            shortFmt(r.profit),
            String.valueOf(r.stockBills), shortFmt(r.stockAmt),
            String.valueOf(r.earnedBills),shortFmt(r.earnedAmt)
        };

        for (int j = 0; j < vals.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(vals[j]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setPadding(dp(8), dp(5), dp(8), dp(5));
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));

            if (j == 0) {
                tv.setTextColor(COL_GOLD);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setGravity(Gravity.START);
            } else if (j == 5) {
                // Profit — green
                tv.setTextColor(COL_GREEN);
                tv.setGravity(Gravity.END);
            } else if (j == 1 || j == 3 || j == 6 || j == 8) {
                // Count columns — blue
                tv.setTextColor(COL_BLUE);
                tv.setGravity(Gravity.END);
            } else {
                tv.setTextColor(COL_WHITE);
                tv.setGravity(Gravity.END);
            }
            tr.addView(tv);
        }
        return tr;
    }

    private TableRow buildTotalsRow() {
        TableRow tr = new TableRow(this);
        tr.setBackgroundColor(BG_TOTALS);

        // Compute totals across ALL rows
        long   pawnBills = 0, redeemBills = 0, earnedBills = 0;
        double pawnAmt = 0, redeemAmt = 0, profit = 0, earnedAmt = 0;
        // stock: use last row's cumulative value (that's what makes sense for cumulative stock)
        long   lastStockBills = rows.isEmpty() ? 0 : rows.get(rows.size()-1).stockBills;
        double lastStockAmt   = rows.isEmpty() ? 0 : rows.get(rows.size()-1).stockAmt;
        // Actually for totals row we sum the individual monthly earned (= total opened - total closed)
        for (MisRow r : rows) {
            pawnBills   += r.pawnBills;   pawnAmt   += r.pawnAmt;
            redeemBills += r.redeemBills; redeemAmt += r.redeemAmt;
            profit      += r.profit;
            earnedBills += r.earnedBills; earnedAmt += r.earnedAmt;
        }

        String[] vals = {
            "TOTAL",
            String.valueOf(pawnBills),   shortFmt(pawnAmt),
            String.valueOf(redeemBills), shortFmt(redeemAmt),
            shortFmt(profit),
            String.valueOf(lastStockBills), shortFmt(lastStockAmt),
            String.valueOf(earnedBills), shortFmt(earnedAmt)
        };

        for (int j = 0; j < vals.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(vals[j]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));
            tv.setTextColor(j == 0 ? COL_GOLD : j == 5 ? COL_GREEN : COL_WHITE);
            tv.setGravity(j == 0 ? Gravity.START : Gravity.END);
            tr.addView(tv);
        }
        return tr;
    }

    private View buildDivider() {
        View v = new View(this);
        v.setLayoutParams(new TableLayout.LayoutParams(
            TableLayout.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        return v;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String shortFmt(double v) {
        return fmt.format(v);
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            getResources().getDisplayMetrics());
    }
}
