package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.webkit.WebView;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.BottomNav;
import com.pawnbroking.app.util.GridRowView;
import com.pawnbroking.app.util.Royal;
import com.pawnbroking.app.util.TablePrint;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Description;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MIS Report: the desktop's MISReportController on a phone. One row per month
 * and material, each month's TOTAL row under them, the section picked (Stock
 * Details, Profit, Assets &amp; Liabilities), Split view or Totals only, ticks
 * and the three Calculates, the total row, sort by a column's heading, Print.
 *
 * <p>The sums are the desktop's, read from /mis-report and /mis-extras. A
 * month's expenses, assets and liabilities sit on its GOLD row, so they count
 * once. Each metal's stock runs on its own row, and a month's TOTAL row adds
 * them. The total row leaves the stock columns blank, because a total of
 * running balances answers nothing; the "stock now" tile is the phone's own,
 * each metal's newest running figure added.
 */
public class MonthlyReportActivity extends AppCompatActivity {

    private static final String STOCK = "STOCK", PROFIT = "PROFIT", AL = "AL";
    private static final String[] MONTH_NAMES =
            {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};

    private ProgressBar progressBar;
    private LinearLayout tableHead, tableMonthly, tableTotal, layoutTiles;
    private TextView tvCompanyName, tvRowCount, tvCloudNote, tvTotalsOf;
    private TextView chipStock, chipProfit, chipAL, chipSplit, chipTotals;
    private TextView btnAll, btnSelected, btnDeselected, btnOptions;
    private View layoutOptions, layoutTilesScroll;

    // Table / Bar / Line / Pie
    private TextView btnViewTable, btnViewBar, btnViewLine, btnViewPie;
    private View layoutChart, layoutTableScroll;
    private TextView tvChartTitle;
    private BarChart misBarChart;
    private LineChart misLineChart;
    private PieChart misPieChart;
    private String viewMode = "TABLE";

    // ── Data ──────────────────────────────────────────────────────────────────

    private static class MisRow {
        String month, material;
        long pawnBills, redeemBills, repledgeBills, repledgeRedeemBills, repledgeStockBills, stockBills;
        double pawnAmt, redeemAmt, interest, repledgeAmt, repledgeRedeemAmt, repledgeInterest,
               repledgeStockAmt, stockAmt, expenses, assets, liabilities;
        String expenseParts = "", profitParts = "", repledgeParts = "";
        boolean checked = true;
        List<MisRow> members;          // a month's TOTAL row: the rows it adds
        boolean isTotal() { return members != null; }
        double gross() { return interest - repledgeInterest; }
        double net()   { return gross() - expenses; }
        long   earnedBills() { return pawnBills - redeemBills; }
        double earnedAmt()   { return pawnAmt - redeemAmt; }
    }

    /** One column: how wide, what it reads, which list it opens. */
    private interface Read { double of(MisRow r); }
    private static final class Col {
        final String head, group, kind; final int width; final boolean count; final Read read; final int tone;
        Col(String group, String head, int width, boolean count, String kind, int tone, Read read) {
            this.group = group; this.head = head; this.width = width; this.count = count;
            this.kind = kind; this.tone = tone; this.read = read;
        }
    }
    private static final int TONE_PLAIN = 0, TONE_COUNT = 1, TONE_REPLEDGE = 2, TONE_EARNED = 3, TONE_SPENT = 4;

    private final List<MisRow> master = new ArrayList<>();   // with the month TOTAL rows
    private final List<MisRow> base = new ArrayList<>();     // without them
    private final List<MisRow> shown = new ArrayList<>();
    private final Map<MisRow, GridRowView> rowViews = new HashMap<>();
    private String totalsOf = "ALL";     // ALL | SELECTED | DESELECTED
    /** A tile's list reads each month from the cloud in turn, so it is asked for a sensible number. */
    private static final int MAX_LIST_MONTHS = 24;
    private String section = STOCK;
    private boolean totalsOnly = false;
    private int sortColumn = -1;         // index into columns(); -1 = month order
    private boolean sortDown = false;
    private boolean extrasLoaded = false;
    private boolean stockByMetal = false;    // the cloud's rows carry each metal's own running stock
    private String companyId, companyName;
    private WebView printing;

    private Royal royal;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monthly_report);

        fmt.setMinimumFractionDigits(0);
        fmt.setMaximumFractionDigits(0);
        royal = new Royal(this);

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

        tvCompanyName = findViewById(R.id.tvCompanyName);
        tvRowCount    = findViewById(R.id.tvRowCount);
        tvCloudNote   = findViewById(R.id.tvCloudNote);
        tvTotalsOf    = findViewById(R.id.tvTotalsOf);
        progressBar   = findViewById(R.id.progressBar);
        tableHead     = findViewById(R.id.tableHead);
        tableMonthly  = findViewById(R.id.tableMonthly);
        tableTotal    = findViewById(R.id.tableTotal);
        layoutTiles   = findViewById(R.id.layoutTiles);
        layoutTilesScroll = findViewById(R.id.layoutTilesScroll);
        layoutOptions = findViewById(R.id.layoutOptions);

        tvCompanyName.setText(companyName);
        BottomNav.attach(this, BottomNav.Tab.REPORTS, companyId, companyName);

        chipStock  = findViewById(R.id.chipStock);
        chipProfit = findViewById(R.id.chipProfit);
        chipAL     = findViewById(R.id.chipAL);
        chipSplit  = findViewById(R.id.chipSplit);
        chipTotals = findViewById(R.id.chipTotals);
        btnAll        = findViewById(R.id.btnAll);
        btnSelected   = findViewById(R.id.btnSelected);
        btnDeselected = findViewById(R.id.btnDeselected);
        btnOptions    = findViewById(R.id.btnOptions);

        chipStock.setOnClickListener(v  -> showSection(STOCK));
        chipProfit.setOnClickListener(v -> showSection(PROFIT));
        chipAL.setOnClickListener(v     -> showSection(AL));
        chipSplit.setOnClickListener(v  -> showRows(false));
        chipTotals.setOnClickListener(v -> showRows(true));
        btnAll.setOnClickListener(v        -> calculate("ALL"));
        btnSelected.setOnClickListener(v   -> calculate("SELECTED"));
        btnDeselected.setOnClickListener(v -> calculate("DESELECTED"));
        findViewById(R.id.btnSelectAll).setOnClickListener(v   -> selectAll(true));
        findViewById(R.id.btnDeselectAll).setOnClickListener(v -> selectAll(false));
        findViewById(R.id.btnPrint).setOnClickListener(v -> print());
        btnOptions.setOnClickListener(v -> {
            boolean open = layoutOptions.getVisibility() != View.VISIBLE;
            layoutOptions.setVisibility(open ? View.VISIBLE : View.GONE);
            paintChip(btnOptions, open);
        });
        paintChip(btnOptions, true);

        btnViewTable = findViewById(R.id.btnViewTable);
        btnViewBar   = findViewById(R.id.btnViewBar);
        btnViewLine  = findViewById(R.id.btnViewLine);
        btnViewPie   = findViewById(R.id.btnViewPie);
        layoutChart       = findViewById(R.id.layoutChart);
        layoutTableScroll = findViewById(R.id.layoutTableScroll);
        tvChartTitle = findViewById(R.id.tvChartTitle);
        misBarChart  = findViewById(R.id.misBarChart);
        misLineChart = findViewById(R.id.misLineChart);
        misPieChart  = findViewById(R.id.misPieChart);

        btnViewTable.setOnClickListener(v -> setViewMode("TABLE"));
        btnViewBar.setOnClickListener(v   -> setViewMode("BAR"));
        btnViewLine.setOnClickListener(v  -> setViewMode("LINE"));
        btnViewPie.setOnClickListener(v   -> setViewMode("PIE"));

        paintChips();
        paintViewChips();
        load();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tableHead.removeAllViews();
        tableMonthly.removeAllViews();
        tableTotal.removeAllViews();
        master.clear();
        base.clear();
        tvCloudNote.setVisibility(View.GONE);

        ApiService.getMisReport(companyId, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject report) {
                // The months first; expenses and the parts add to them. A cloud
                // without them still gives the rest of the report.
                ApiService.getMisExtras(companyId, new ApiService.Callback<JSONObject>() {
                    @Override public void onSuccess(JSONObject extras) {
                        runOnUiThread(() -> { progressBar.setVisibility(View.GONE); bind(report, extras); });
                    }
                    @Override public void onError(String msg) {
                        runOnUiThread(() -> { progressBar.setVisibility(View.GONE); bind(report, null); });
                    }
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MonthlyReportActivity.this, "Error: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ── Bind ───────────────────────────────────────────────────────────────────

    private void bind(JSONObject data, JSONObject extras) {
        JSONArray arr = data.optJSONArray("rows");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                MisRow r = new MisRow();
                r.month               = m.optString("month", "");
                r.material            = m.optString("jwlType", "");
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
                base.add(r);
            }
        }
        stockByMetal = data.optBoolean("stockByMetal", false);
        extrasLoaded = extras != null;
        if (extras != null) {
            putExpenses(extras.optJSONArray("expenses"));
            putAssets(extras.optJSONArray("assets"));
            putProfitParts(extras.optJSONArray("interestParts"));
            putRepledgeParts(extras.optJSONArray("repledgeParts"));
        }
        addMonthTotals();

        Set<String> months = new HashSet<>();
        for (MisRow r : base) months.add(r.month);
        tvRowCount.setText(months.size() + (months.size() == 1 ? " month" : " months"));

        tvCloudNote.setText("Expenses, net profit, assets and liabilities come with the next cloud update.");
        tvCloudNote.setVisibility(extrasLoaded ? View.GONE : View.VISIBLE);

        rebuild();
    }

    /** A month-level figure goes on the month's GOLD row, else its first; else a row of its own. */
    private MisRow monthRow(String month) {
        MisRow target = null;
        for (MisRow b : base) {
            if (!month.equals(b.month)) continue;
            if (target == null || "GOLD".equals(b.material)) target = b;
        }
        if (target == null) {
            target = new MisRow();
            target.month = month;
            target.material = "—";
            base.add(target);
        }
        return target;
    }

    private void putExpenses(JSONArray a) {
        if (a == null) return;
        String[] names = {"Company expenses", "Salaries", "Daily allowance", "Other to employees", "Repledge bill charges"};
        String[] keys  = {"company", "salary", "allowance", "employee", "repledge"};
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            double sum = 0;
            StringBuilder parts = new StringBuilder();
            for (int k = 0; k < keys.length; k++) {
                double v = o.optDouble(keys[k], 0);
                sum += v;
                if (v != 0) parts.append(parts.length() == 0 ? "" : "\n").append(names[k]).append(": ").append(money(v));
            }
            if (sum == 0) continue;
            MisRow r = monthRow(o.optString("month"));
            r.expenses = sum;
            r.expenseParts = parts.toString();
        }
    }

    private void putAssets(JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            double as = o.optDouble("assets", 0), li = o.optDouble("liabilities", 0);
            if (as == 0 && li == 0) continue;
            MisRow r = monthRow(o.optString("month"));
            r.assets = as;
            r.liabilities = li;
        }
    }

    private void putProfitParts(JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            double open = o.optDouble("opening", 0), doc = o.optDouble("docCharge", 0);
            String text = "Taken at opening: " + money(open)
                    + (doc != 0 ? "\n   of it document charge: " + money(doc) + "\n   of it interest: " + money(open - doc) : "")
                    + "\nInterest at closing: " + money(o.optDouble("closing", 0))
                    + "\nOther charges (fine, notice ...): " + money(o.optDouble("other", 0))
                    + "\nLess discount: " + money(o.optDouble("discount", 0));
            for (MisRow b : base) {
                if (o.optString("month").equals(b.month) && o.optString("material").equals(b.material)) b.profitParts = text;
            }
        }
    }

    private void putRepledgeParts(JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String text = "Interest taken at opening: " + money(o.optDouble("opening", 0))
                    + "\nReceived less than due at opening: " + money(o.optDouble("lessAtOpening", 0))
                    + "\nInterest at closing: " + money(o.optDouble("closing", 0))
                    + "\nGiven more than due at closing: " + money(o.optDouble("moreAtClosing", 0));
            for (MisRow b : base) {
                if (o.optString("month").equals(b.month) && "GOLD".equals(b.material)) b.repledgeParts = text;
            }
        }
    }

    /** Newest month first, GOLD, SILVER, then the month's TOTAL row when it has more than one. */
    private void addMonthTotals() {
        Collections.sort(base, (a, b) -> {
            int c = monthKey(b.month) - monthKey(a.month);
            return c != 0 ? c : materialRank(a.material) - materialRank(b.material);
        });
        master.clear();
        int i = 0;
        while (i < base.size()) {
            int end = i;
            while (end < base.size() && base.get(end).month.equals(base.get(i).month)) end++;
            List<MisRow> members = new ArrayList<>(base.subList(i, end));
            master.addAll(members);
            if (members.size() > 1) master.add(monthTotal(members));
            i = end;
        }
    }

    private static MisRow monthTotal(List<MisRow> rows) {
        MisRow t = new MisRow();
        t.month = rows.get(0).month;
        t.material = "TOTAL";
        t.members = rows;
        StringBuilder ex = new StringBuilder();
        for (MisRow p : rows) add(t, p);
        for (MisRow p : rows) if (!p.expenseParts.isEmpty()) ex.append(ex.length() == 0 ? "" : "\n").append(p.expenseParts);
        t.expenseParts = ex.toString();
        return t;
    }

    private static void add(MisRow t, MisRow p) {
        t.pawnBills += p.pawnBills;                     t.pawnAmt += p.pawnAmt;
        t.redeemBills += p.redeemBills;                 t.redeemAmt += p.redeemAmt;
        t.interest += p.interest;
        t.repledgeBills += p.repledgeBills;             t.repledgeAmt += p.repledgeAmt;
        t.repledgeRedeemBills += p.repledgeRedeemBills; t.repledgeRedeemAmt += p.repledgeRedeemAmt;
        t.repledgeInterest += p.repledgeInterest;
        t.repledgeStockBills += p.repledgeStockBills;   t.repledgeStockAmt += p.repledgeStockAmt;
        t.stockBills += p.stockBills;                   t.stockAmt += p.stockAmt;
        t.expenses += p.expenses;
        t.assets += p.assets;
        t.liabilities += p.liabilities;
    }

    private static int monthKey(String label) {
        try {
            String[] p = label.split("-");
            return Integer.parseInt(p[1]) * 12 + Arrays.asList(MONTH_NAMES).indexOf(p[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int materialRank(String m) {
        return "GOLD".equals(m) ? 0 : "SILVER".equals(m) ? 1 : "TOTAL".equals(m) ? 3 : 2;
    }

    // ── The columns ────────────────────────────────────────────────────────────

    private List<Col> columns() {
        List<Col> c = new ArrayList<>();
        c.add(new Col("COMPANY", "Open\nCount", 54, true, "COMPANY_OPENED", TONE_COUNT, r -> r.pawnBills));
        c.add(new Col("COMPANY", "Open\nCapital", 92, false, "COMPANY_OPENED", TONE_PLAIN, r -> r.pawnAmt));
        c.add(new Col("COMPANY", "Redeem\nCount", 60, true, "COMPANY_REDEEMED", TONE_COUNT, r -> r.redeemBills));
        c.add(new Col("COMPANY", "Redeem\nCapital", 92, false, "COMPANY_REDEEMED", TONE_PLAIN, r -> r.redeemAmt));
        c.add(new Col("COMPANY", "Earned\nCount", 60, true, null, TONE_COUNT, MisRow::earnedBills));
        c.add(new Col("COMPANY", "Earned\nCapital", 92, false, null, TONE_PLAIN, MisRow::earnedAmt));
        c.add(new Col("REPLEDGE", "Open\nCount", 54, true, "REPLEDGE_OPENED", TONE_COUNT, r -> r.repledgeBills));
        c.add(new Col("REPLEDGE", "Open\nCapital", 92, false, "REPLEDGE_OPENED", TONE_REPLEDGE, r -> r.repledgeAmt));
        c.add(new Col("REPLEDGE", "Redeem\nCount", 60, true, "REPLEDGE_REDEEMED", TONE_COUNT, r -> r.repledgeRedeemBills));
        c.add(new Col("REPLEDGE", "Redeem\nCapital", 92, false, "REPLEDGE_REDEEMED", TONE_REPLEDGE, r -> r.repledgeRedeemAmt));
        if (STOCK.equals(section)) {
            c.add(new Col("STOCK DETAILS", "Stock\nCount", 58, true, null, TONE_COUNT, r -> r.stockBills));
            c.add(new Col("STOCK DETAILS", "Stock\nCapital", 100, false, null, TONE_PLAIN, r -> r.stockAmt));
            c.add(new Col("STOCK DETAILS", "Repl\nCount", 54, true, null, TONE_COUNT, r -> r.repledgeStockBills));
            c.add(new Col("STOCK DETAILS", "Repl\nCapital", 100, false, null, TONE_REPLEDGE, r -> r.repledgeStockAmt));
        } else if (PROFIT.equals(section)) {
            c.add(new Col("PROFIT", "Company\nProfit", 92, false, "COMPANY_PROFIT", TONE_EARNED, r -> r.interest));
            c.add(new Col("PROFIT", "Repledge\nInt. Spent", 92, false, "REPLEDGE_INTEREST", TONE_SPENT, r -> r.repledgeInterest));
            c.add(new Col("PROFIT", "Gross\nProfit", 92, false, MisListActivity.KIND_GROSS, TONE_EARNED, MisRow::gross));
            c.add(new Col("PROFIT", "Expenses", 92, false, "EXPENSES", TONE_SPENT, r -> r.expenses));
            c.add(new Col("PROFIT", "Net\nProfit", 96, false, MisListActivity.KIND_NET, TONE_EARNED, MisRow::net));
        } else {
            c.add(new Col("ASSETS & LIABILITIES", "Assets", 100, false, "ASSETS", TONE_PLAIN, r -> r.assets));
            c.add(new Col("ASSETS & LIABILITIES", "Liabilities", 100, false, "LIABILITIES", TONE_PLAIN, r -> r.liabilities));
        }
        return c;
    }

    private static final int FIXED = 3;                    // tick, month, material
    private static final int[] FIXED_W = {34, 78, 62};

    private int[] widths(List<Col> cols) {
        int[] w = new int[FIXED + cols.size()];
        System.arraycopy(FIXED_W, 0, w, 0, FIXED);
        for (int i = 0; i < cols.size(); i++) w[FIXED + i] = cols.get(i).width;
        return w;
    }

    private boolean[] rightAlign(int n) {
        boolean[] r = new boolean[n];
        for (int i = FIXED; i < n; i++) r[i] = true;
        return r;
    }

    private int toneColor(int tone, double v) {
        if (v == 0) return royal.inkFaint;
        if (v < 0) return royal.ruby;
        switch (tone) {
            case TONE_COUNT:    return royal.inkSoft;
            case TONE_REPLEDGE: return royal.violet;
            case TONE_EARNED:   return royal.emerald;
            case TONE_SPENT:    return royal.ruby;
            default:            return royal.ink;
        }
    }

    // ── Drawing the table ──────────────────────────────────────────────────────

    /** Which rows are on screen, in the order picked. */
    private void pickShown() {
        shown.clear();
        Set<String> withTotal = new HashSet<>();
        for (MisRow b : master) if (b.isTotal()) withTotal.add(b.month);
        for (MisRow b : master) {
            if (totalsOnly && !(b.isTotal() || !withTotal.contains(b.month))) continue;
            shown.add(b);
        }
        if (sortColumn >= 0) {
            List<Col> cols = columns();
            Comparator<MisRow> by;
            if (sortColumn == 0)      by = Comparator.comparingInt(r -> monthKey(r.month));
            else if (sortColumn == 1) by = Comparator.comparingInt(r -> materialRank(r.material));
            else {
                int i = sortColumn - 2;
                if (i >= cols.size()) return;
                Read read = cols.get(i).read;
                by = Comparator.comparingDouble(read::of);
            }
            // equal rows keep their order, so a month's TOTAL stays under its GOLD and SILVER
            Collections.sort(shown, sortDown ? by.reversed() : by);
        }
    }

    private void rebuild() {
        List<Col> cols = columns();
        int[] w = widths(cols);
        boolean[] right = rightAlign(w.length);

        // The group band over the headings: one cell a group, spanning its columns.
        List<String> gLabels = new ArrayList<>();
        List<Integer> gWidths = new ArrayList<>();
        gLabels.add("MONTH / MATERIAL");
        gWidths.add(FIXED_W[0] + FIXED_W[1] + FIXED_W[2]);
        for (Col c : cols) {
            int last = gLabels.size() - 1;
            if (c.group.equals(gLabels.get(last))) gWidths.set(last, gWidths.get(last) + c.width);
            else { gLabels.add(c.group); gWidths.add(c.width); }
        }
        int[] gw = new int[gWidths.size()];
        int[] gc = new int[gw.length];
        boolean[] gb = new boolean[gw.length], gr = new boolean[gw.length];
        for (int i = 0; i < gw.length; i++) { gw[i] = gWidths.get(i); gc[i] = royal.onWineDim; gb[i] = true; }

        tableHead.removeAllViews();
        GridRowView band = new GridRowView(this).heightDp(24)
                .set(gLabels.toArray(new String[0]), gw, gc, gr, gb);
        band.setTextSizeSp(this, 10);
        band.setBackgroundResource(R.drawable.bg_table_group);
        tableHead.addView(band);

        // The headings. Tap one to sort by it, again to turn it round, again for month order.
        String[] heads = new String[w.length];
        int[] hc = new int[w.length];
        boolean[] hb = new boolean[w.length];
        heads[0] = "✓"; heads[1] = "Month"; heads[2] = "Material";
        for (int i = 0; i < cols.size(); i++) heads[FIXED + i] = cols.get(i).head;
        if (sortColumn >= 0 && sortColumn + 1 < heads.length) heads[sortColumn + 1] += sortDown ? " ▼" : " ▲";
        Arrays.fill(hc, royal.onWineTitle);
        Arrays.fill(hb, true);
        GridRowView head = new GridRowView(this).heightDp(42).set(heads, w, hc, right, hb);
        head.setTextSizeSp(this, 11);
        head.setBackgroundResource(R.drawable.bg_table_head);
        head.setOnCell(new GridRowView.OnCell() {
            @Override public void onTap(int column) {
                if (column == 0) return;
                int s = column - 1;
                if (sortColumn != s)   { sortColumn = s; sortDown = false; }
                else if (!sortDown)    { sortDown = true; }
                else                   { sortColumn = -1; sortDown = false; }
                rebuild();
            }
            @Override public void onLong(int column) { }
        });
        tableHead.addView(head);

        pickShown();
        tableMonthly.removeAllViews();
        rowViews.clear();
        for (MisRow r : shown) {
            GridRowView row = new GridRowView(this);
            drawRow(row, r, cols, w, right);
            final MisRow target = r;
            row.setOnCell(new GridRowView.OnCell() {
                @Override public void onTap(int column) {
                    if (column < FIXED) { toggle(target); return; }
                    Col c = cols.get(column - FIXED);
                    if (c.kind == null) { toggle(target); return; }
                    openList(c.kind, target);
                }
                @Override public void onLong(int column) {
                    if (column >= FIXED) showParts(cols.get(column - FIXED), target);
                }
            });
            rowViews.put(r, row);
            tableMonthly.addView(row);
        }
        refreshTotals();
        paintChips();
    }

    private void drawRow(GridRowView row, MisRow r, List<Col> cols, int[] w, boolean[] right) {
        String[] cells = new String[w.length];
        int[] colors = new int[w.length];
        boolean[] bold = new boolean[w.length];
        cells[0] = r.checked ? "☑" : "☐";
        colors[0] = r.checked ? royal.goldInk : royal.inkMute;
        cells[1] = r.month;
        colors[1] = royal.wineInk;
        bold[1] = true;
        cells[2] = r.material;
        colors[2] = "SILVER".equals(r.material) ? royal.navy : r.isTotal() ? royal.wineInk : royal.goldInk;
        bold[2] = r.isTotal();
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            double v = c.read.of(r);
            cells[FIXED + i] = c.count ? (v == 0 ? "—" : fmt.format((long) v)) : (v == 0 ? "—" : money(v));
            colors[FIXED + i] = toneColor(c.tone, v);
            bold[FIXED + i] = r.isTotal();
        }
        row.set(cells, w, colors, right, bold);
        row.setBackgroundResource(r.isTotal() ? R.drawable.bg_table_total
                : r.checked ? R.drawable.bg_table_row_selected : R.drawable.bg_table_row);
    }

    private void repaintRows() {
        List<Col> cols = columns();
        int[] w = widths(cols);
        boolean[] right = rightAlign(w.length);
        for (Map.Entry<MisRow, GridRowView> e : rowViews.entrySet()) drawRow(e.getValue(), e.getKey(), cols, w, right);
    }

    /** What the total row adds, as the desktop's setCompanyHeaderValues. */
    private MisRow totalRow() {
        MisRow t = new MisRow();
        Set<String> months = new HashSet<>();
        for (MisRow b : master) {
            if (b.isTotal()) continue;                // its GOLD and SILVER rows count, not it
            if (!counted(b)) continue;
            months.add(b.month);
            add(t, b);
        }
        t.month = String.valueOf(months.size());
        t.material = months.size() == 1 ? "month" : "months";
        return t;
    }

    private boolean counted(MisRow b) {
        return "ALL".equals(totalsOf)
                || ("SELECTED".equals(totalsOf)   &&  b.checked)
                || ("DESELECTED".equals(totalsOf) && !b.checked);
    }

    private void refreshTotals() {
        List<Col> cols = columns();
        int[] w = widths(cols);
        boolean[] right = rightAlign(w.length);
        MisRow t = totalRow();

        String[] cells = new String[w.length];
        int[] colors = new int[w.length];
        boolean[] bold = new boolean[w.length];
        Arrays.fill(bold, true);
        cells[0] = "";
        cells[1] = t.month;
        cells[2] = t.material;
        colors[1] = colors[2] = royal.wineInk;
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            double v = c.read.of(t);
            cells[FIXED + i] = c.count ? fmt.format((long) v) : money(v);
            colors[FIXED + i] = toneColor(c.tone, v);
        }
        // The four stock columns are running balances: added over months they answer nothing, so the
        // total row leaves them blank and the tiles above say what is in stock now.
        if (STOCK.equals(section)) {
            for (int k = 10; k <= 13; k++) cells[FIXED + k] = "";
        }
        tableTotal.removeAllViews();
        GridRowView row = new GridRowView(this).heightDp(40).set(cells, w, colors, right, bold);
        row.setBackgroundResource(R.drawable.bg_table_grand);
        tableTotal.addView(row);

        tvTotalsOf.setVisibility(View.VISIBLE);
        tvTotalsOf.setText("SELECTED".equals(totalsOf) ? "TOTAL FOR THE SELECTED MONTHS"
                : "DESELECTED".equals(totalsOf) ? "TOTAL FOR THE MONTHS NOT SELECTED" : "TOTAL FOR ALL MONTHS");
        layoutTilesScroll.setVisibility(View.VISIBLE);
        drawTiles(t);
    }

    /** The section's figures, large. The same numbers as the total row beneath. */
    private void drawTiles(MisRow t) {
        layoutTiles.removeAllViews();
        if (STOCK.equals(section)) {
            MisRow now = stockNow();
            tile("Stock now", now.stockBills + " bills", money(now.stockAmt), royal.wineInk, null);
            tile("At the lender now", now.repledgeStockBills + " bills", money(now.repledgeStockAmt), royal.violet, null);
            tile("Opened", t.pawnBills + " bills", money(t.pawnAmt), royal.ink, "COMPANY_OPENED");
            tile("Redeemed", t.redeemBills + " bills", money(t.redeemAmt), royal.ink, "COMPANY_REDEEMED");
            tile("Earned", t.earnedBills() + " bills", money(t.earnedAmt()), royal.ink, null);
        } else if (PROFIT.equals(section)) {
            tile("Company Profit", "", money(t.interest), royal.emerald, "COMPANY_PROFIT");
            tile("Repledge Int. Spent", "", money(t.repledgeInterest), royal.ruby, "REPLEDGE_INTEREST");
            tile("Gross Profit", "", money(t.gross()), t.gross() < 0 ? royal.ruby : royal.emerald, MisListActivity.KIND_GROSS);
            tile("Expenses", "", extrasLoaded ? money(t.expenses) : "—", royal.ruby, "EXPENSES");
            tile("Net Profit", "", extrasLoaded ? money(t.net()) : "—", t.net() < 0 ? royal.ruby : royal.emerald, MisListActivity.KIND_NET);
        } else {
            tile("Assets", "", extrasLoaded ? money(t.assets) : "—", royal.ink, "ASSETS");
            tile("Liabilities", "", extrasLoaded ? money(t.liabilities) : "—", royal.ink, "LIABILITIES");
        }
    }

    /** A tile's list: the rows behind its total, whichever ones Calculate has counted. */
    private void openTotalList(String kind) {
        if (!extrasLoaded) {
            Toast.makeText(this, "This list comes with the next cloud update", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, List<String>> months = new LinkedHashMap<>();
        for (MisRow b : master) {
            if (b.isTotal() || !counted(b)) continue;
            List<String> m = months.get(b.month);
            if (m == null) { m = new ArrayList<>(); months.put(b.month, m); }
            m.add(b.material);
        }
        if (months.isEmpty()) return;
        if (months.size() > MAX_LIST_MONTHS) {
            Toast.makeText(this, "That is " + months.size() + " months. Tick up to " + MAX_LIST_MONTHS
                    + " and tap again", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<String> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : months.entrySet()) {
            // Both a month's rows counted is one read for the whole month, as its TOTAL row's list.
            rows.add(e.getKey() + "|" + (e.getValue().size() == 1 ? e.getValue().get(0) : ""));
        }
        Intent i = new Intent(this, MisListActivity.class);
        i.putExtra("companyId", companyId);
        i.putExtra("companyName", companyName);
        i.putExtra(MisListActivity.EXTRA_KIND, kind);
        i.putStringArrayListExtra(MisListActivity.EXTRA_ROWS, rows);
        startActivity(i);
    }

    /**
     * Each metal's stock runs on its own row, so the shop's stock is the newest GOLD row's figure plus the
     * newest SILVER row's, whichever months those are (a month with no silver bills has no SILVER row).
     * GOLD carries the repledge. A cloud from before 04-11-2026 ran one sum through both metals; there the
     * newest month's last row (SILVER when it has one) is the whole shop.
     */
    private MisRow stockNow() {
        MisRow now = new MisRow();
        if (base.isEmpty()) return now;
        Set<String> seen = new HashSet<>();
        String newest = base.get(0).month;
        for (MisRow r : base) {                      // newest month first, GOLD before SILVER
            if (stockByMetal) {
                if (!seen.add(r.material)) continue;
                now.stockBills += r.stockBills;
                now.stockAmt += r.stockAmt;
            } else {
                if (!newest.equals(r.month)) break;
                now.stockBills = r.stockBills;
                now.stockAmt = r.stockAmt;
            }
            if ("GOLD".equals(r.material)) {           // either way only the GOLD row wanted gets here
                now.repledgeStockBills = r.repledgeStockBills;
                now.repledgeStockAmt = r.repledgeStockAmt;
            }
        }
        return now;
    }

    private void tile(String label, String count, String figure, int color, String listKind) {
        View v = getLayoutInflater().inflate(R.layout.item_mis_tile, layoutTiles, false);
        if (listKind != null) {
            int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setBackgroundResource(R.drawable.bg_tappable);
            v.setPadding(l, t, r, b);
            v.setOnClickListener(x -> openTotalList(listKind));
        }
        ((TextView) v.findViewById(R.id.tileLabel)).setText(label);
        TextView c = v.findViewById(R.id.tileCount);
        c.setText(count);
        c.setVisibility(count.isEmpty() ? View.GONE : View.VISIBLE);
        TextView f = v.findViewById(R.id.tileFigure);
        f.setText(figure);
        f.setTextColor(color);
        layoutTiles.addView(v);
    }

    // ── Ticks and Calculate ────────────────────────────────────────────────────

    /** A tick for a row; a month's TOTAL row ticks (or unticks) the whole month. */
    private void toggle(MisRow r) {
        boolean now = !r.checked;
        if (r.isTotal()) for (MisRow m : r.members) m.checked = now;
        r.checked = now;
        syncMonthTotals();
        totalsOf = "SELECTED";
        repaintRows();
        refreshTotals();
        paintChips();
    }

    private void syncMonthTotals() {
        for (MisRow b : master) {
            if (!b.isTotal()) continue;
            boolean all = true;
            for (MisRow m : b.members) all &= m.checked;
            b.checked = all;
        }
    }

    private void selectAll(boolean select) {
        for (MisRow b : master) b.checked = select;
        totalsOf = "SELECTED";
        repaintRows();
        refreshTotals();
        paintChips();
    }

    private void calculate(String which) {
        totalsOf = which;
        refreshTotals();
        paintChips();
    }

    private void showSection(String which) {
        section = which;
        sortColumn = -1;
        sortDown = false;
        rebuild();
    }

    private void showRows(boolean only) {
        totalsOnly = only;
        rebuild();
    }

    private void paintChip(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(on ? royal.onWine : royal.inkBody);
    }

    private void paintChips() {
        paintChip(chipStock,  STOCK.equals(section));
        paintChip(chipProfit, PROFIT.equals(section));
        paintChip(chipAL,     AL.equals(section));
        paintChip(chipSplit,  !totalsOnly);
        paintChip(chipTotals, totalsOnly);
        paintChip(btnAll,        "ALL".equals(totalsOf));
        paintChip(btnSelected,   "SELECTED".equals(totalsOf));
        paintChip(btnDeselected, "DESELECTED".equals(totalsOf));
    }

    private void paintViewChips() {
        paintChip(btnViewTable, "TABLE".equals(viewMode));
        paintChip(btnViewBar,   "BAR".equals(viewMode));
        paintChip(btnViewLine,  "LINE".equals(viewMode));
        paintChip(btnViewPie,   "PIE".equals(viewMode));
    }

    // ── A figure: its list, its parts ──────────────────────────────────────────

    private void openList(String kind, MisRow r) {
        if (!extrasLoaded) {
            Toast.makeText(this, "This list comes with the next cloud update", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, MisListActivity.class);
        i.putExtra("companyId", companyId);
        i.putExtra("companyName", companyName);
        i.putExtra(MisListActivity.EXTRA_KIND, kind);
        i.putExtra(MisListActivity.EXTRA_MONTH, r.month);
        boolean all = r.isTotal() || "—".equals(r.material);
        if (!all) i.putExtra(MisListActivity.EXTRA_MATERIAL, r.material);
        startActivity(i);
    }

    /** The desktop shows these on hover; a phone has the long press. */
    private void showParts(Col c, MisRow r) {
        String parts = "";
        if ("Expenses".equals(c.head)) parts = r.expenseParts;
        else if ("COMPANY_PROFIT".equals(c.kind)) {
            if (r.isTotal()) {
                StringBuilder s = new StringBuilder();
                for (MisRow m : r.members) {
                    if (!m.profitParts.isEmpty()) s.append(s.length() == 0 ? "" : "\n\n").append(m.material).append("\n").append(m.profitParts);
                }
                parts = s.toString();
            } else parts = r.profitParts;
        }
        else if ("REPLEDGE_INTEREST".equals(c.kind)) {
            if (r.isTotal()) { for (MisRow m : r.members) if (!m.repledgeParts.isEmpty()) parts = m.repledgeParts; }
            else parts = r.repledgeParts;
        }
        if (parts.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(c.head.replace('\n', ' ') + "  -  " + r.month + (r.isTotal() ? "" : "  -  " + r.material))
                .setMessage(parts).setPositiveButton("Close", null).show();
    }

    // ── Print ──────────────────────────────────────────────────────────────────

    /**
     * The section on screen, the rows as shown and sorted, each month's TOTAL,
     * and the total row. Only the months the total was worked out from, so the
     * rows above it add up to it.
     */
    private void print() {
        List<Col> cols = columns();
        int n = 2 + cols.size();
        String[] heads = new String[n];
        boolean[] right = new boolean[n];
        heads[0] = "Month";
        heads[1] = "Material";
        for (int i = 0; i < cols.size(); i++) {
            String group = cols.get(i).group;
            heads[2 + i] = ("COMPANY".equals(group) || "REPLEDGE".equals(group) ? group.charAt(0) + group.substring(1).toLowerCase(Locale.ROOT) + " " : "")
                    + cols.get(i).head;
            right[2 + i] = true;
        }
        TablePrint p = new TablePrint(heads, right);
        Set<String> months = new HashSet<>();
        int first = Integer.MAX_VALUE, last = 0;
        String firstLabel = "", lastLabel = "";
        for (MisRow r : shown) {
            boolean in = "ALL".equals(totalsOf) || ("SELECTED".equals(totalsOf) && r.checked)
                    || ("DESELECTED".equals(totalsOf) && !r.checked);
            if (!in) continue;
            months.add(r.month);
            int key = monthKey(r.month);
            if (key < first) { first = key; firstLabel = r.month; }
            if (key > last)  { last = key;  lastLabel = r.month; }
            p.row(r.isTotal() ? TablePrint.Kind.SUBTOTAL : TablePrint.Kind.ROW, cells(r, cols));
        }
        if (p.size() == 0) {
            Toast.makeText(this, "SELECTED".equals(totalsOf) ? "No month is ticked, so there is nothing to print."
                    : "Every month is ticked, so there is nothing left to print.", Toast.LENGTH_LONG).show();
            return;
        }
        MisRow t = totalRow();
        String[] total = cells(t, cols);
        total[0] = "TOTAL";
        total[1] = t.month + " " + t.material;
        if (STOCK.equals(section)) { total[12] = ""; total[13] = ""; }
        p.row(TablePrint.Kind.TOTAL, total);

        String name = PROFIT.equals(section) ? "Profit" : AL.equals(section) ? "Assets & Liabilities" : "Stock Details";
        String sub = firstLabel + " to " + lastLabel + "  ·  " + months.size() + (months.size() == 1 ? " month" : " months")
                + "  ·  " + (totalsOnly ? "Totals only (one row a month)" : "Gold and silver rows, with each month's total")
                + "  ·  TOTAL row: " + ("SELECTED".equals(totalsOf) ? "the ticked months"
                    : "DESELECTED".equals(totalsOf) ? "the months not ticked" : "all months");
        printing = p.show(this, "MIS REPORT  ·  " + name.toUpperCase(Locale.ROOT), sub, "MIS Report - " + name);
    }

    private String[] cells(MisRow r, List<Col> cols) {
        String[] out = new String[2 + cols.size()];
        out[0] = r.month;
        out[1] = r.material;
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            double v = c.read.of(r);
            out[2 + i] = c.count ? fmt.format((long) v) : money(v);
        }
        return out;
    }

    // ── Table / Bar / Line / Pie ───────────────────────────────────────────────

    private void setViewMode(String m) {
        viewMode = m;
        paintViewChips();

        boolean isTable = "TABLE".equals(m);
        layoutTableScroll.setVisibility(isTable ? View.VISIBLE : View.GONE);
        layoutChart.setVisibility(isTable ? View.GONE : View.VISIBLE);
        misBarChart.setVisibility("BAR".equals(m)  ? View.VISIBLE : View.GONE);
        misLineChart.setVisibility("LINE".equals(m) ? View.VISIBLE : View.GONE);
        misPieChart.setVisibility("PIE".equals(m)  ? View.VISIBLE : View.GONE);

        if (base.isEmpty()) return;
        switch (m) {
            case "BAR":  buildBarChart();  break;
            case "LINE": buildLineChart(); break;
            case "PIE":  buildPieChart();  break;
            default: break;
        }
    }

    /** One bar group per month: Pawn vs Redeem amount (gold+silver merged). */
    private void buildBarChart() {
        tvChartTitle.setText("Pawn vs Redeem Amount (by month)");
        Map<String,double[]> byMonth = new LinkedHashMap<>(); // month → {pawn, redeem}
        // rows are newest-first; reverse for left-to-right time order.
        for (int i = base.size() - 1; i >= 0; i--) {
            MisRow r = base.get(i);
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
        dsP.setColor(royal.goldRule); dsP.setDrawValues(false);
        BarDataSet dsR = new BarDataSet(redeem, "Redeem");
        dsR.setColor(royal.navy); dsR.setDrawValues(false);
        BarData bd = new BarData(dsP, dsR);
        float groupSpace = 0.3f, barSpace = 0.05f, barWidth = 0.3f;
        bd.setBarWidth(barWidth);
        styleChart(misBarChart);
        misBarChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        misBarChart.getXAxis().setCenterAxisLabels(true);
        misBarChart.getXAxis().setAxisMinimum(0f);
        misBarChart.getXAxis().setAxisMaximum(labels.size());
        misBarChart.setData(bd);
        if (labels.size() > 0) misBarChart.groupBars(0f, groupSpace, barSpace);
        misBarChart.animateY(400);
        misBarChart.invalidate();
    }

    /** Two lines over months: running Stock Amount + running Repledge Stock. */
    private void buildLineChart() {
        tvChartTitle.setText("Stock Amount Trend (running)");
        Map<String,double[]> byMonth = new LinkedHashMap<>(); // month → {stock, replStock}
        for (int i = base.size() - 1; i >= 0; i--) {
            MisRow r = base.get(i);
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
        dsS.setColor(royal.violet); dsS.setDrawCircles(false); dsS.setLineWidth(2f); dsS.setDrawValues(false);
        LineDataSet dsR = new LineDataSet(repl, "Repledge Stock");
        dsR.setColor(royal.goldRule); dsR.setDrawCircles(false); dsR.setLineWidth(2f); dsR.setDrawValues(false);
        styleChart(misLineChart);
        misLineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        misLineChart.setData(new LineData(dsS, dsR));
        misLineChart.animateY(400);
        misLineChart.invalidate();
    }

    /** Pie of total Pawn Amount: Gold vs Silver share across all months. */
    private void buildPieChart() {
        tvChartTitle.setText("Pawn Amount Share — Gold vs Silver");
        double gold = 0, silver = 0;
        for (MisRow r : base) {
            if ("GOLD".equalsIgnoreCase(r.material))   gold   += r.pawnAmt;
            if ("SILVER".equalsIgnoreCase(r.material)) silver += r.pawnAmt;
        }
        List<PieEntry> entries = new ArrayList<>();
        if (gold   > 0) entries.add(new PieEntry((float) gold,   "Gold"));
        if (silver > 0) entries.add(new PieEntry((float) silver, "Silver"));
        PieDataSet ds = new PieDataSet(entries, "");
        ds.setColors(royal.goldRule, royal.silver);
        ds.setValueTextColor(0xFF17090D); ds.setValueTextSize(12f);
        ds.setSliceSpace(2f);
        PieData pd = new PieData(ds);
        pd.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return fmt.format(v); }
        });
        misPieChart.setData(pd);
        misPieChart.getDescription().setEnabled(false);
        misPieChart.setEntryLabelColor(0xFF17090D);
        misPieChart.setHoleColor(0x00000000);
        misPieChart.setHoleRadius(45f);
        misPieChart.setTransparentCircleRadius(48f);
        misPieChart.getLegend().setTextColor(royal.ink);
        misPieChart.setCenterText("Pawn\nGold vs Silver");
        misPieChart.setCenterTextColor(royal.inkMute);
        misPieChart.animateY(400);
        misPieChart.invalidate();
    }

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

    /** Indian grouping for a loss too: -6,98,100. */
    private String money(double v) { return v < 0 ? "-" + fmt.format(-v) : fmt.format(v); }

    @SuppressWarnings("unused")
    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            getResources().getDisplayMetrics());
    }
}
