package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.pawnbroking.app.models.Company;
import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends AppCompatActivity {

    private Spinner spinnerCompany;
    private ProgressBar progressCompany;
    private TextView tvNoCompany;
    private View layoutContent;
    private LineChart chartBillCount, chartBillAmount;
    private static final java.text.NumberFormat IN_FMT =
            java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"));
    static { IN_FMT.setMaximumFractionDigits(0); }

    private List<Company> companies = new ArrayList<>();
    private Company selectedCompany;

    // Notification bell
    private TextView tvBellBadge;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private static final long POLL_INTERVAL_MS = 30_000L;
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            refreshBellBadge();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        spinnerCompany  = findViewById(R.id.spinnerCompany);
        progressCompany = findViewById(R.id.progressCompany);
        tvNoCompany     = findViewById(R.id.tvNoCompany);
        layoutContent   = findViewById(R.id.layoutContent);
        chartBillCount  = findViewById(R.id.chartBillCount);
        chartBillAmount = findViewById(R.id.chartBillAmount);
        styleChart(chartBillCount);
        styleChart(chartBillAmount);

        // Main buttons
        findViewById(R.id.btnStockDetails).setOnClickListener(v   -> open(StockDetailsActivity.class));
        findViewById(R.id.btnTodaysAccount).setOnClickListener(v  -> open(TodaysAccountActivity.class));
        findViewById(R.id.btnBilling).setOnClickListener(v        -> open(BillingActivity.class));
        findViewById(R.id.btnMonthlyReport).setOnClickListener(v  -> open(MonthlyReportActivity.class));

        loadCompanies();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_home, menu);
        MenuItem bell = menu.findItem(R.id.action_notifications);
        if (bell != null) {
            View v = bell.getActionView();
            if (v != null) {
                tvBellBadge = v.findViewById(R.id.tvBellBadge);
                v.setOnClickListener(x -> openNotifications());
            }
        }
        refreshBellBadge();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_notifications) { openNotifications(); return true; }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_logout) {
            logout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openNotifications() {
        startActivity(new Intent(this, NotificationsActivity.class));
    }

    @Override protected void onResume() {
        super.onResume();
        // Refresh badge immediately and start the 30s poll loop.
        refreshBellBadge();
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void refreshBellBadge() {
        ApiService.getNotifications(50, new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray data) {
                long lastRead = ApiService.getLastReadNotifId(HomeActivity.this);
                int unread = 0;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o != null && o.optLong("notif_id", 0) > lastRead) unread++;
                }
                final int count = unread;
                runOnUiThread(() -> applyBadge(count));
            }
            @Override public void onError(String msg) { /* silent on poll */ }
        });
    }

    private void applyBadge(int count) {
        if (tvBellBadge == null) return;
        if (count <= 0) {
            tvBellBadge.setVisibility(View.GONE);
        } else {
            tvBellBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            tvBellBadge.setVisibility(View.VISIBLE);
        }
    }

    private void loadCompanies() {
        progressCompany.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);
        tvNoCompany.setVisibility(View.GONE);

        ApiService.getCompanies(new ApiService.Callback<List<Company>>() {
            @Override public void onSuccess(List<Company> result) {
                runOnUiThread(() -> {
                    progressCompany.setVisibility(View.GONE);
                    companies = result;
                    if (companies.isEmpty()) {
                        tvNoCompany.setVisibility(View.VISIBLE);
                        return;
                    }
                    ArrayAdapter<Company> adapter = new ArrayAdapter<>(
                        HomeActivity.this, android.R.layout.simple_spinner_item, companies);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerCompany.setAdapter(adapter);
                    spinnerCompany.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                            selectedCompany = companies.get(pos);
                            loadCharts();
                        }
                        @Override public void onNothingSelected(AdapterView<?> p) {}
                    });
                    selectedCompany = companies.get(0);
                    layoutContent.setVisibility(View.VISIBLE);
                    loadCharts();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressCompany.setVisibility(View.GONE);
                    Toast.makeText(HomeActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void open(Class<?> activityClass) {
        if (selectedCompany == null) {
            Toast.makeText(this, "Please select a company first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, activityClass);
        i.putExtra("companyId",   selectedCompany.id);
        i.putExtra("companyName", selectedCompany.name);
        startActivity(i);
    }

    private void logout() {
        ApiService.logout(this);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    // ── Monthly charts ───────────────────────────────────────────────────────

    private void styleChart(LineChart chart) {
        chart.setNoDataText("No data yet");
        chart.setNoDataTextColor(0xFF888888);
        chart.setDrawGridBackground(false);
        chart.setTouchEnabled(true);
        chart.setPinchZoom(false);
        chart.setScaleEnabled(false);
        Description d = new Description();
        d.setText("");
        chart.setDescription(d);
        chart.getLegend().setTextColor(0xFFFFFFFF);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setTextColor(0xFFFFFFFF);
        x.setLabelRotationAngle(-45f);
        x.setGranularity(1f);
        x.setDrawGridLines(false);
        chart.getAxisLeft().setTextColor(0xFFFFFFFF);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return IN_FMT.format(v); }
        });
        chart.getAxisRight().setEnabled(false);
    }

    private void loadCharts() {
        if (selectedCompany == null) return;
        ApiService.getMonthlyReport(selectedCompany.id, new ApiService.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject data) {
                runOnUiThread(() -> bindCharts(data));
            }
            @Override public void onError(String msg) { /* silent — chart shows "No data" */ }
        });
    }

    private void bindCharts(org.json.JSONObject data) {
        org.json.JSONArray months = data.optJSONArray("months");
        if (months == null) months = new org.json.JSONArray();

        // Cloud returns newest-first; charts read left-to-right by time.
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Entry> total = new java.util.ArrayList<>();
        java.util.List<Entry> gold  = new java.util.ArrayList<>();
        java.util.List<Entry> silver= new java.util.ArrayList<>();
        java.util.List<Entry> totalAmt = new java.util.ArrayList<>();
        java.util.List<Entry> goldAmt  = new java.util.ArrayList<>();
        java.util.List<Entry> silverAmt= new java.util.ArrayList<>();

        int n = months.length();
        for (int i = n - 1, idx = 0; i >= 0; i--, idx++) {
            org.json.JSONObject m = months.optJSONObject(i);
            if (m == null) continue;
            labels.add(formatMonth(m.optString("month", "")));
            total .add(new Entry(idx, (float) m.optDouble("pawnBills",   0)));
            gold  .add(new Entry(idx, (float) m.optDouble("goldBills",   0)));
            silver.add(new Entry(idx, (float) m.optDouble("silverBills", 0)));
            totalAmt .add(new Entry(idx, (float) m.optDouble("pawnAmount",   0)));
            goldAmt  .add(new Entry(idx, (float) m.optDouble("goldAmount",   0)));
            silverAmt.add(new Entry(idx, (float) m.optDouble("silverAmount", 0)));
        }

        renderChart(chartBillCount, labels, total, gold, silver,
                    "Total", "Gold", "Silver");
        renderChart(chartBillAmount, labels, totalAmt, goldAmt, silverAmt,
                    "Total", "Gold", "Silver");
    }

    private void renderChart(LineChart chart, java.util.List<String> labels,
                             java.util.List<Entry> a, java.util.List<Entry> b, java.util.List<Entry> c,
                             String labelA, String labelB, String labelC) {
        if (labels.isEmpty()) { chart.clear(); chart.invalidate(); return; }
        LineDataSet dsTotal = makeSet(a, labelA, 0xFF9C27B0);       // purple
        LineDataSet dsGold  = makeSet(b, labelB, 0xFFE6B800);       // gold
        LineDataSet dsSilver= makeSet(c, labelC, 0xFF42A5F5);       // blue
        LineData ld = new LineData(dsTotal, dsGold, dsSilver);
        chart.setData(ld);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        // Force a label at every month so each data point is annotated,
        // matching the desktop chart's MMM-YYYY tick style.
        chart.getXAxis().setLabelCount(labels.size(), true);
        chart.animateY(400);
        chart.invalidate();
    }

    private LineDataSet makeSet(java.util.List<Entry> entries, String label, int color) {
        LineDataSet ds = new LineDataSet(entries, label);
        ds.setColor(color);
        ds.setCircleColor(color);
        ds.setLineWidth(2f);
        ds.setCircleRadius(3f);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        return ds;
    }

    /** "2026-04" → "APR-2026". */
    private static String formatMonth(String iso) {
        if (iso == null || iso.length() < 7) return iso == null ? "" : iso;
        String[] names = {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
        try {
            int mo = Integer.parseInt(iso.substring(5, 7));
            return names[mo - 1] + "-" + iso.substring(0, 4);
        } catch (Exception e) { return iso; }
    }
}
