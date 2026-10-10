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
import com.pawnbroking.app.util.BottomNav;
import com.pawnbroking.app.util.Royal;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MIS Report — full 16-column desktop layout, one row per (month, jewel
 * type). Mirrors the user's desktop MIS SQL exactly.
 *
 * Columns:
 *  Month | Type |
 *  Pawn # | Pawn Amt | Redeem # | Redeem Amt | Interest |
 *  Repl # | Repl Amt | Repl Rdm # | Repl Rdm Amt | Repl Intr |
 *  Repl Stk # | Repl Stk Amt | Stock # | Stock Amt
 *
 * Tap a row to toggle its selection; the Summary card and mode buttons
 * (All / Selected / Deselected) total the chosen rows.
 */
public class MonthlyReportActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TableLayout tableMonthly;
    private TextView tvCompanyName, tvRowCount, tvSummaryMonths;
    private TextView tvSumPawnBills, tvSumPawnAmt;
    private TextView tvSumRedeemBills, tvSumRedeemAmt;
    private TextView tvSumProfit;
    private TextView tvSumStockBills, tvSumStockAmt;
    private TextView tvSumEarnedBills, tvSumEarnedAmt;
    // The repledge leg and the locker/lender split: in MisRow all along,
    // but the summary only ever showed five of the sixteen columns.
    private TextView tvSumReplBills, tvSumReplAmt;
    private TextView tvSumReplRdmBills, tvSumReplRdmAmt;
    private TextView tvSumReplIntr;
    private TextView tvSumReplStockBills, tvSumReplStockAmt;
    private TextView tvSumTotalStockBills, tvSumTotalStockAmt;
    private TextView tvSumGross;
    private View layoutControls, layoutSummary;
    private Button btnAll, btnSelected, btnDeselected;
    private Button btnSelectAll, btnDeselectAll;

    // View toggle (Table / Bar / Line / Pie) + chart views
    private Button btnViewTable, btnViewBar, btnViewLine, btnViewPie;
    private View   layoutChart, layoutTableScroll;
    private TextView tvChartTitle;
    private BarChart  misBarChart;
    private LineChart misLineChart;
    private PieChart  misPieChart;
    private String viewMode = "TABLE"; // TABLE | BAR | LINE | PIE

    // ── Data ──────────────────────────────────────────────────────────────────

    private static class MisRow {
        String month, jwlType;
        long   pawnBills, redeemBills, repledgeBills, repledgeRedeemBills,
               repledgeStockBills, stockBills;
        double pawnAmt, redeemAmt, interest,
               repledgeAmt, repledgeRedeemAmt, repledgeInterest,
               repledgeStockAmt, stockAmt;
        boolean selected = true;
        TableRow tableRow;
    }

    private final List<MisRow> rows = new ArrayList<>();
    private String mode = "ALL"; // ALL | SELECTED | DESELECTED
    private String companyId, companyName;

    // ── Column config (16 columns) ─────────────────────────────────────────────

    private static final String[] HEADERS = {
        "Month", "Type",
        "Pawn\n#", "Pawn\nAmt", "Redeem\n#", "Redeem\nAmt", "Interest",
        "Repl\n#", "Repl\nAmt", "ReplRdm\n#", "ReplRdm\nAmt", "Repl\nIntr",
        "ReplStk\n#", "ReplStk\nAmt", "Stock\n#", "Stock\nAmt"
    };
    private static final int[] COL_WIDTHS_DP = {
        70, 50,
        46, 74, 50, 74, 74,
        46, 74, 56, 74, 70,
        56, 76, 50, 78
    };

    // ── Colours ───────────────────────────────────────────────────────────────
    // Resolved in onCreate, not held as static hex. These were eleven
    // Color.parseColor constants carrying the old navy scheme, which is
    // why this table went on rendering dark after the app went light.
    // Row backgrounds are drawables now (bg_table_row), so each row
    // carries its own hairline instead of a zebra stripe.
    private Royal royal;
    private int colHead;      // a header cell, sitting on wine
    private int colMonth;     // the Month cell, and the TOTAL label
    private int colCount;     // a count of bills
    private int colAmount;    // a plain amount
    private int colEarned;    // interest earned
    private int colRepledge;  // a repledge amount
    private int colMute;      // an inactive mode button

    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monthly_report);

        fmt.setMinimumFractionDigits(0);
        fmt.setMaximumFractionDigits(0);

        royal       = new Royal(this);
        colHead     = royal.onWineTitle;
        colMonth    = royal.wineInk;
        colCount    = royal.inkSoft;
        colAmount   = royal.ink;
        colEarned   = royal.emerald;
        colRepledge = royal.violet;
        colMute     = royal.inkMute;

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
        tvSumReplBills       = findViewById(R.id.tvSumReplBills);
        tvSumReplAmt         = findViewById(R.id.tvSumReplAmt);
        tvSumReplRdmBills    = findViewById(R.id.tvSumReplRdmBills);
        tvSumReplRdmAmt      = findViewById(R.id.tvSumReplRdmAmt);
        tvSumReplIntr        = findViewById(R.id.tvSumReplIntr);
        tvSumReplStockBills  = findViewById(R.id.tvSumReplStockBills);
        tvSumReplStockAmt    = findViewById(R.id.tvSumReplStockAmt);
        tvSumTotalStockBills = findViewById(R.id.tvSumTotalStockBills);
        tvSumTotalStockAmt   = findViewById(R.id.tvSumTotalStockAmt);
        tvSumGross           = findViewById(R.id.tvSumGross);

        btnAll        = findViewById(R.id.btnAll);
        btnSelected   = findViewById(R.id.btnSelected);
        btnDeselected = findViewById(R.id.btnDeselected);
        btnSelectAll   = findViewById(R.id.btnSelectAll);
        btnDeselectAll = findViewById(R.id.btnDeselectAll);

        tvCompanyName.setText(companyName);

        BottomNav.attach(this, BottomNav.Tab.REPORTS, companyId, companyName);

        btnAll.setOnClickListener(v        -> setMode("ALL"));
        btnSelected.setOnClickListener(v   -> setMode("SELECTED"));
        btnDeselected.setOnClickListener(v -> setMode("DESELECTED"));
        btnSelectAll.setOnClickListener(v   -> selectAll(true));
        btnDeselectAll.setOnClickListener(v -> selectAll(false));

        // View toggle
        btnViewTable = findViewById(R.id.btnViewTable);
        btnViewBar   = findViewById(R.id.btnViewBar);
        btnViewLine  = findViewById(R.id.btnViewLine);
        btnViewPie   = findViewById(R.id.btnViewPie);
        layoutChart        = findViewById(R.id.layoutChart);
        layoutTableScroll  = findViewById(R.id.layoutTableScroll);
        tvChartTitle = findViewById(R.id.tvChartTitle);
        misBarChart  = findViewById(R.id.misBarChart);
        misLineChart = findViewById(R.id.misLineChart);
        misPieChart  = findViewById(R.id.misPieChart);

        btnViewTable.setOnClickListener(v -> setViewMode("TABLE"));
        btnViewBar.setOnClickListener(v   -> setViewMode("BAR"));
        btnViewLine.setOnClickListener(v  -> setViewMode("LINE"));
        btnViewPie.setOnClickListener(v   -> setViewMode("PIE"));

        load();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tableMonthly.removeAllViews();
        rows.clear();
        layoutControls.setVisibility(View.GONE);
        layoutSummary.setVisibility(View.GONE);

        ApiService.getMisReport(companyId, new ApiService.Callback<JSONObject>() {
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

    // ── Bind ───────────────────────────────────────────────────────────────────

    private void bind(JSONObject data) {
        JSONArray arr = data.optJSONArray("rows");
        int total     = data.optInt("total", 0);
        tvRowCount.setText(total + " rows");

        if (arr == null || arr.length() == 0) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            MisRow r = new MisRow();
            r.month               = m.optString("month", "");
            r.jwlType             = m.optString("jwlType", "");
            r.pawnBills           = m.optLong  ("pawnBills",            0);
            r.pawnAmt             = m.optDouble("pawnAmount",           0);
            r.redeemBills         = m.optLong  ("redeemBills",          0);
            r.redeemAmt           = m.optDouble("redeemAmount",         0);
            r.interest            = m.optDouble("interest",             0);
            r.repledgeBills       = m.optLong  ("repledgeBills",        0);
            r.repledgeAmt         = m.optDouble("repledgeAmount",       0);
            r.repledgeRedeemBills = m.optLong  ("repledgeRedeemBills",  0);
            r.repledgeRedeemAmt   = m.optDouble("repledgeRedeemAmount", 0);
            r.repledgeInterest    = m.optDouble("repledgeInterest",     0);
            r.repledgeStockBills  = m.optLong  ("repledgeStockBills",   0);
            r.repledgeStockAmt    = m.optDouble("repledgeStockAmount",  0);
            r.stockBills          = m.optLong  ("stockBills",           0);
            r.stockAmt            = m.optDouble("stockAmount",          0);
            r.selected            = true;
            rows.add(r);
        }

        tableMonthly.addView(buildHeaderRow());
        for (int i = 0; i < rows.size(); i++) {
            MisRow r = rows.get(i);
            TableRow tr = buildDataRow(r, i);
            r.tableRow = tr;
            final int idx = i;
            tr.setOnClickListener(v -> onRowTap(idx));
            tableMonthly.addView(tr);
        }
        tableMonthly.addView(buildDivider());
        tableMonthly.addView(buildTotalsRow());

        layoutControls.setVisibility(View.VISIBLE);
        layoutSummary.setVisibility(View.VISIBLE);
        refreshSummary();
        refreshModeButtons();
    }

    // ── Row tap ─────────────────────────────────────────────────────────────────

    private void onRowTap(int idx) {
        MisRow r = rows.get(idx);
        r.selected = !r.selected;
        paintRow(r.tableRow, r.selected);
        refreshSummary();
    }

    private void selectAll(boolean select) {
        for (int i = 0; i < rows.size(); i++) {
            MisRow r = rows.get(i);
            r.selected = select;
            paintRow(r.tableRow, select);
        }
        refreshSummary();
    }

    private void setMode(String newMode) { mode = newMode; refreshModeButtons(); refreshSummary(); }

    private void refreshModeButtons() {
        btnAll.setTextColor(       "ALL".equals(mode)        ? colMonth : colMute);
        btnSelected.setTextColor(  "SELECTED".equals(mode)   ? colMonth : colMute);
        btnDeselected.setTextColor("DESELECTED".equals(mode) ? colMonth : colMute);
    }

    // ── Summary (reuses the existing 10 summary fields; maps the most useful
    //     of the 16 columns onto them) ─────────────────────────────────────────

    private void refreshSummary() {
        long months = 0;
        long pawnBills = 0, redeemBills = 0, earnedBills = 0;
        double pawnAmt = 0, redeemAmt = 0, interest = 0, earnedAmt = 0;
        long lastStockBills = 0; double lastStockAmt = 0;
        long replBills = 0, replRdmBills = 0;
        double replAmt = 0, replRdmAmt = 0, replIntr = 0;
        // Stock is a position, not a flow, so it is carried not summed -
        // same convention as the company stock above it.
        long lastReplStockBills = 0; double lastReplStockAmt = 0;

        for (MisRow r : rows) {
            boolean include = "ALL".equals(mode)
                    || ("SELECTED".equals(mode)   &&  r.selected)
                    || ("DESELECTED".equals(mode) && !r.selected);
            if (include) {
                months++;
                pawnBills   += r.pawnBills;   pawnAmt   += r.pawnAmt;
                redeemBills += r.redeemBills; redeemAmt += r.redeemAmt;
                interest    += r.interest;
                earnedBills += (r.pawnBills - r.redeemBills);
                earnedAmt   += (r.pawnAmt   - r.redeemAmt);
                lastStockBills = r.stockBills;   // cumulative — last wins
                lastStockAmt   = r.stockAmt;

                replBills    += r.repledgeBills;       replAmt    += r.repledgeAmt;
                replRdmBills += r.repledgeRedeemBills; replRdmAmt += r.repledgeRedeemAmt;
                replIntr     += r.repledgeInterest;
                lastReplStockBills = r.repledgeStockBills;
                lastReplStockAmt   = r.repledgeStockAmt;
            }
        }

        tvSummaryMonths.setText("(" + months + " row" + (months != 1 ? "s" : "") + ")");
        tvSumPawnBills.setText(String.valueOf(pawnBills));
        tvSumPawnAmt.setText("₹" + fmt.format(pawnAmt));
        tvSumRedeemBills.setText(String.valueOf(redeemBills));
        tvSumRedeemAmt.setText("₹" + fmt.format(redeemAmt));
        tvSumProfit.setText("₹" + fmt.format(interest));
        tvSumStockBills.setText(String.valueOf(lastStockBills));
        tvSumStockAmt.setText("₹" + fmt.format(lastStockAmt));
        tvSumEarnedBills.setText(String.valueOf(earnedBills));
        tvSumEarnedAmt.setText("₹" + fmt.format(earnedAmt));

        tvSumReplBills.setText(String.valueOf(replBills));
        tvSumReplAmt.setText("₹" + fmt.format(replAmt));
        tvSumReplRdmBills.setText(String.valueOf(replRdmBills));
        tvSumReplRdmAmt.setText("₹" + fmt.format(replRdmAmt));
        tvSumReplIntr.setText("₹" + fmt.format(replIntr));

        tvSumReplStockBills.setText(String.valueOf(lastReplStockBills));
        tvSumReplStockAmt.setText("₹" + fmt.format(lastReplStockAmt));
        // Total stock = what is in the locker plus what is at the lender.
        // A jewel is in one place or the other, never both.
        tvSumTotalStockBills.setText(String.valueOf(lastStockBills + lastReplStockBills));
        tvSumTotalStockAmt.setText("₹" + fmt.format(lastStockAmt + lastReplStockAmt));

        // Gross is what the shop earned less what it paid the lender,
        // which is how the desktop MIS reads it.
        tvSumGross.setText("₹" + fmt.format(interest - replIntr));
    }

    // ── Builders ─────────────────────────────────────────────────────────────────

    /**
     * A row is parchment with a hairline under it; the picked one takes a
     * gold wash. No zebra stripe — the rule does the separating.
     */
    private void paintRow(View row, boolean selected) {
        row.setBackgroundResource(selected
                ? R.drawable.bg_table_row_selected : R.drawable.bg_table_row);
    }

    private TableRow buildHeaderRow() {
        TableRow tr = new TableRow(this);
        tr.setBackgroundColor(royal.wine);
        for (int j = 0; j < HEADERS.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(HEADERS[j]);
            tv.setTextColor(colHead);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(j == 0 ? Gravity.START : Gravity.CENTER);
            tv.setPadding(dp(6), dp(6), dp(6), dp(6));
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));
            tr.addView(tv);
        }
        return tr;
    }

    private TableRow buildDataRow(MisRow r, int idx) {
        TableRow tr = new TableRow(this);
        paintRow(tr, r.selected);

        String[] vals = {
            r.month, r.jwlType,
            n(r.pawnBills),           a(r.pawnAmt),
            n(r.redeemBills),         a(r.redeemAmt),
            a(r.interest),
            n(r.repledgeBills),       a(r.repledgeAmt),
            n(r.repledgeRedeemBills), a(r.repledgeRedeemAmt),
            a(r.repledgeInterest),
            n(r.repledgeStockBills),  a(r.repledgeStockAmt),
            n(r.stockBills),          a(r.stockAmt)
        };

        for (int j = 0; j < vals.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(vals[j]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv.setPadding(dp(6), dp(5), dp(6), dp(5));
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));
            tv.setTextColor(colorFor(j));
            if (j == 0) { tv.setTypeface(null, Typeface.BOLD); tv.setGravity(Gravity.START); }
            else if (j == 1) { tv.setGravity(Gravity.CENTER); }
            else { tv.setGravity(Gravity.END); }
            tr.addView(tv);
        }
        return tr;
    }

    private TableRow buildTotalsRow() {
        TableRow tr = new TableRow(this);
        tr.setBackgroundResource(R.drawable.bg_table_total);

        long pawnBills=0, redeemBills=0, replBills=0, replRdmBills=0;
        double pawnAmt=0, redeemAmt=0, interest=0, replAmt=0, replRdmAmt=0, replIntr=0;
        long lastStockBills=0, lastReplStockBills=0;
        double lastStockAmt=0, lastReplStockAmt=0;
        for (MisRow r : rows) {
            pawnBills+=r.pawnBills;   pawnAmt+=r.pawnAmt;
            redeemBills+=r.redeemBills; redeemAmt+=r.redeemAmt;
            interest+=r.interest;
            replBills+=r.repledgeBills; replAmt+=r.repledgeAmt;
            replRdmBills+=r.repledgeRedeemBills; replRdmAmt+=r.repledgeRedeemAmt;
            replIntr+=r.repledgeInterest;
            lastStockBills=r.stockBills; lastStockAmt=r.stockAmt;
            lastReplStockBills=r.repledgeStockBills; lastReplStockAmt=r.repledgeStockAmt;
        }

        String[] vals = {
            "TOTAL", "",
            n(pawnBills),    a(pawnAmt),
            n(redeemBills),  a(redeemAmt),
            a(interest),
            n(replBills),    a(replAmt),
            n(replRdmBills), a(replRdmAmt),
            a(replIntr),
            n(lastReplStockBills), a(lastReplStockAmt),
            n(lastStockBills),     a(lastStockAmt)
        };

        for (int j = 0; j < vals.length; j++) {
            TextView tv = new TextView(this);
            tv.setText(vals[j]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tv.setPadding(dp(6), dp(6), dp(6), dp(6));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setMinWidth(dp(COL_WIDTHS_DP[j]));
            tv.setTextColor(j == 0 ? colMonth : colorFor(j));
            tv.setGravity(j == 0 ? Gravity.START : j == 1 ? Gravity.CENTER : Gravity.END);
            tr.addView(tv);
        }
        return tr;
    }

    /** Counts read quiet, money reads ink, interest green, repledge violet. */
    private int colorFor(int j) {
        switch (j) {
            case 0: return colMonth;                          // Month
            case 1: return colAmount;                         // Type
            case 2: case 4: return colCount;                  // pawn#, redeem#
            case 6: return colEarned;                         // interest
            case 7: case 9: case 12: case 14: return colCount; // repl#, replRdm#, replStk#, stock#
            case 11: return colEarned;                        // repl interest
            case 8: case 10: case 13: return colRepledge;     // repledge amounts
            default: return colAmount;                        // plain amounts
        }
    }

    private View buildDivider() {
        View v = new View(this);
        v.setLayoutParams(new TableLayout.LayoutParams(
            TableLayout.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(royal.line);
        return v;
    }

    // ── View mode (Table / Bar / Line / Pie) ───────────────────────────────────

    private void setViewMode(String m) {
        viewMode = m;
        // A segmented control: the chosen one is wine, the rest are the
        // parchment strip it sits on.
        Button[] btns = { btnViewTable, btnViewBar, btnViewLine, btnViewPie };
        String[] keys = { "TABLE", "BAR", "LINE", "PIE" };
        for (int i = 0; i < btns.length; i++) {
            boolean on = keys[i].equals(m);
            btns[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    on ? royal.wine : royal.strip));
            btns[i].setTextColor(on ? royal.onWine : royal.inkBody);
        }

        boolean isTable = "TABLE".equals(m);
        layoutTableScroll.setVisibility(isTable ? View.VISIBLE : View.GONE);
        layoutChart.setVisibility(isTable ? View.GONE : View.VISIBLE);
        misBarChart.setVisibility("BAR".equals(m)  ? View.VISIBLE : View.GONE);
        misLineChart.setVisibility("LINE".equals(m) ? View.VISIBLE : View.GONE);
        misPieChart.setVisibility("PIE".equals(m)  ? View.VISIBLE : View.GONE);

        if (rows.isEmpty()) return;
        switch (m) {
            case "BAR":  buildBarChart();  break;
            case "LINE": buildLineChart(); break;
            case "PIE":  buildPieChart();  break;
            default: /* table already populated */ break;
        }
    }

    /** One bar group per month: Pawn vs Redeem amount (gold+silver merged). */
    private void buildBarChart() {
        tvChartTitle.setText("Pawn vs Redeem Amount (by month)");
        // Merge the GOLD+SILVER rows for the same month into one bucket.
        Map<String,double[]> byMonth = new LinkedHashMap<>(); // month → {pawn, redeem}
        // rows are newest-first; reverse for left-to-right time order.
        for (int i = rows.size() - 1; i >= 0; i--) {
            MisRow r = rows.get(i);
            double[] v = byMonth.computeIfAbsent(r.month, k -> new double[2]);
            v[0] += r.pawnAmt; v[1] += r.redeemAmt;
        }
        List<String> labels = new ArrayList<>();
        List<BarEntry> pawn = new ArrayList<>(), redeem = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String,double[]> e : byMonth.entrySet()) {
            labels.add(e.getKey());
            pawn.add(new BarEntry(idx, (float) e.getValue()[0]));
            redeem.add(new BarEntry(idx, (float) e.getValue()[1]));
            idx++;
        }
        BarDataSet dsP = new BarDataSet(pawn,   "Pawn");
        dsP.setColor(royal.goldRule); dsP.setValueTextColor(royal.inkSoft); dsP.setValueTextSize(8);
        BarDataSet dsR = new BarDataSet(redeem, "Redeem");
        dsR.setColor(royal.navy); dsR.setValueTextColor(royal.inkSoft); dsR.setValueTextSize(8);
        BarData bd = new BarData(dsP, dsR);
        float groupSpace = 0.3f, barSpace = 0.05f, barWidth = 0.3f;
        bd.setBarWidth(barWidth);
        styleChart(misBarChart);
        misBarChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        misBarChart.getXAxis().setCenterAxisLabels(true);
        misBarChart.getXAxis().setAxisMinimum(0f);
        misBarChart.getXAxis().setAxisMaximum(labels.size());
        misBarChart.getXAxis().setLabelCount(labels.size());
        misBarChart.setData(bd);
        if (labels.size() > 0) misBarChart.groupBars(0f, groupSpace, barSpace);
        misBarChart.animateY(400);
        misBarChart.invalidate();
    }

    /** Two lines over months: cumulative Stock Amount + cumulative Repledge Stock. */
    private void buildLineChart() {
        tvChartTitle.setText("Stock Amount Trend (cumulative)");
        Map<String,double[]> byMonth = new LinkedHashMap<>(); // month → {stock, replStock}
        for (int i = rows.size() - 1; i >= 0; i--) {
            MisRow r = rows.get(i);
            // cumulative columns: take the max seen for the month (gold row carries repl)
            double[] v = byMonth.computeIfAbsent(r.month, k -> new double[2]);
            v[0] = Math.max(v[0], r.stockAmt);
            v[1] = Math.max(v[1], r.repledgeStockAmt);
        }
        List<String> labels = new ArrayList<>();
        List<Entry> stock = new ArrayList<>(), repl = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String,double[]> e : byMonth.entrySet()) {
            labels.add(e.getKey());
            stock.add(new Entry(idx, (float) e.getValue()[0]));
            repl.add(new Entry(idx, (float) e.getValue()[1]));
            idx++;
        }
        LineDataSet dsS = new LineDataSet(stock, "Stock Amount");
        dsS.setColor(royal.violet); dsS.setCircleColor(royal.violet); dsS.setLineWidth(2f);
        dsS.setValueTextColor(royal.inkSoft); dsS.setValueTextSize(8);
        LineDataSet dsR = new LineDataSet(repl, "Repledge Stock");
        dsR.setColor(royal.goldRule); dsR.setCircleColor(royal.goldRule); dsR.setLineWidth(2f);
        dsR.setValueTextColor(royal.inkSoft); dsR.setValueTextSize(8);
        styleChart(misLineChart);
        misLineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        misLineChart.getXAxis().setLabelCount(labels.size(), false);
        misLineChart.setData(new LineData(dsS, dsR));
        misLineChart.animateY(400);
        misLineChart.invalidate();
    }

    /** Pie of total Pawn Amount: Gold vs Silver share across all months. */
    private void buildPieChart() {
        tvChartTitle.setText("Pawn Amount Share — Gold vs Silver");
        double gold = 0, silver = 0;
        for (MisRow r : rows) {
            if ("GOLD".equalsIgnoreCase(r.jwlType))   gold   += r.pawnAmt;
            if ("SILVER".equalsIgnoreCase(r.jwlType)) silver += r.pawnAmt;
        }
        List<PieEntry> entries = new ArrayList<>();
        if (gold   > 0) entries.add(new PieEntry((float) gold,   "Gold"));
        if (silver > 0) entries.add(new PieEntry((float) silver, "Silver"));
        PieDataSet ds = new PieDataSet(entries, "");
        ds.setColors(royal.goldRule, royal.navy);
        ds.setValueTextColor(royal.onWine); ds.setValueTextSize(12f);
        ds.setSliceSpace(2f);
        PieData pd = new PieData(ds);
        pd.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return "₹" + fmt.format(v); }
        });
        misPieChart.setData(pd);
        misPieChart.getDescription().setEnabled(false);
        misPieChart.setEntryLabelColor(royal.onWine);
        misPieChart.setHoleColor(0x00000000);
        misPieChart.setHoleRadius(45f);
        misPieChart.setTransparentCircleRadius(48f);
        misPieChart.getLegend().setTextColor(royal.ink);
        misPieChart.setCenterText("Pawn\nGold vs Silver");
        misPieChart.setCenterTextColor(royal.inkMute);
        misPieChart.animateY(400);
        misPieChart.invalidate();
    }

    /** Common dark-theme styling for the bar/line charts. */
    private void styleChart(com.github.mikephil.charting.charts.BarLineChartBase<?> chart) {
        Description d = new Description(); d.setText("");
        chart.setDescription(d);
        chart.setNoDataText("No data");
        chart.setDrawGridBackground(false);
        chart.setScaleEnabled(false);
        chart.getLegend().setTextColor(royal.ink);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setTextColor(royal.inkMute);
        x.setLabelRotationAngle(-40f);
        x.setGranularity(1f);
        x.setDrawGridLines(false);
        chart.getAxisLeft().setTextColor(royal.inkMute);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return fmt.format(v); }
        });
        chart.getAxisRight().setEnabled(false);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String n(long v) { return fmt.format(v); }
    private String a(double v) { return fmt.format(v); }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            getResources().getDisplayMetrics());
    }
}
