package com.pawnbroking.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class TodaysAccountActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private LinearLayout layoutContent;
    private TextView tvCompanyName, tvSelectedDate;
    private TableLayout tableOperations;

    // Balance cards
    private TextView tvPreDate, tvPreActual, tvPreAvailable, tvPreDeficit, tvPreNote;
    private TextView tvActualBalance, tvAvailableBalance, tvDeficit, tvTodaysNote;
    private TextView tvTotalDebit, tvTotalCredit;
    private View layoutStatus;
    private TextView tvAccountStatus;

    private String companyId, companyName;
    private String selectedDate;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat displaySdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    // Maps operation name → detail type key for drill-down
    private static final Map<String, String> DETAIL_TYPES = new LinkedHashMap<>();
    static {
        DETAIL_TYPES.put("GOLD BILL OPENING",     "GOLD_OPENING");
        DETAIL_TYPES.put("GOLD ADVANCE AMOUNT",   "GOLD_ADVANCE");
        DETAIL_TYPES.put("GOLD BILL CLOSING",     "GOLD_CLOSING");
        DETAIL_TYPES.put("SILVER BILL OPENING",   "SILVER_OPENING");
        DETAIL_TYPES.put("SILVER ADVANCE AMOUNT", "SILVER_ADVANCE");
        DETAIL_TYPES.put("SILVER BILL CLOSING",   "SILVER_CLOSING");
        DETAIL_TYPES.put("REPLEDGE BILL OPENING", "REPLEDGE_OPENING");
        DETAIL_TYPES.put("REPLEDGE BILL CLOSING", "REPLEDGE_CLOSING");
        DETAIL_TYPES.put("EXPENSES",              "EXPENSES");
        DETAIL_TYPES.put("INCOMES",               "INCOMES");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todays_account);

        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");
        selectedDate = sdf.format(new Date());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Today's Account");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) { load(); return true; }
            return false;
        });

        progressBar    = findViewById(R.id.progressBar);
        layoutContent  = findViewById(R.id.layoutContent);
        tvCompanyName  = findViewById(R.id.tvCompanyName);
        tvSelectedDate = findViewById(R.id.tvSelectedDate);
        tableOperations= findViewById(R.id.tableOperations);

        tvPreDate      = findViewById(R.id.tvPreDate);
        tvPreActual    = findViewById(R.id.tvPreActual);
        tvPreAvailable = findViewById(R.id.tvPreAvailable);
        tvPreDeficit   = findViewById(R.id.tvPreDeficit);
        tvPreNote      = findViewById(R.id.tvPreNote);

        tvActualBalance   = findViewById(R.id.tvActualBalance);
        tvAvailableBalance= findViewById(R.id.tvAvailableBalance);
        tvDeficit         = findViewById(R.id.tvDeficit);
        tvTodaysNote      = findViewById(R.id.tvTodaysNote);

        tvTotalDebit    = findViewById(R.id.tvTotalDebit);
        tvTotalCredit   = findViewById(R.id.tvTotalCredit);
        layoutStatus    = findViewById(R.id.layoutStatus);
        tvAccountStatus = findViewById(R.id.tvAccountStatus);

        tvCompanyName.setText(companyName);
        tvSelectedDate.setText(displaySdf.format(new Date()));

        // Date picker button
        findViewById(R.id.btnPickDate).setOnClickListener(v -> showDatePicker());

        load();
    }

    private void showDatePicker() {
        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(
            this, (view, year, month, day) -> {
                Calendar c = Calendar.getInstance();
                c.set(year, month, day);
                selectedDate = sdf.format(c.getTime());
                tvSelectedDate.setText(displaySdf.format(c.getTime()));
                load();
            },
            Calendar.getInstance().get(Calendar.YEAR),
            Calendar.getInstance().get(Calendar.MONTH),
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        ApiService.getTodaysAccount(companyId, selectedDate, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                    bind(data);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(TodaysAccountActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void bind(JSONObject data) {
        // Previous day balance
        String preDate = data.optString("preDate", "");
        tvPreDate.setText(preDate.isEmpty() ? "—" : preDate);
        tvPreActual.setText("₹ " + fmt.format(data.optDouble("preActualBalance", 0)));
        tvPreAvailable.setText("₹ " + fmt.format(data.optDouble("preAvailableBalance", 0)));
        double preDeficit = data.optDouble("preDeficit", 0);
        tvPreDeficit.setText("₹ " + fmt.format(preDeficit));
        tvPreDeficit.setTextColor(preDeficit == 0
                ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        String preNote = data.optString("preNote", "");
        tvPreNote.setText(preNote.isEmpty() ? "" : preNote);
        tvPreNote.setVisibility(preNote.isEmpty() ? View.GONE : View.VISIBLE);

        // Today's balance
        double actualBalance    = data.optDouble("actualBalance", 0);
        double availableBalance = data.optDouble("availableBalance", 0);
        double deficit          = data.optDouble("deficit", 0);
        tvActualBalance.setText("₹ " + fmt.format(actualBalance));
        tvAvailableBalance.setText("₹ " + fmt.format(availableBalance));
        tvDeficit.setText("₹ " + fmt.format(deficit));
        tvDeficit.setTextColor(deficit == 0
                ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        String todaysNote = data.optString("todaysNote", "");
        tvTodaysNote.setText(todaysNote.isEmpty() ? "" : todaysNote);
        tvTodaysNote.setVisibility(todaysNote.isEmpty() ? View.GONE : View.VISIBLE);

        // Totals
        tvTotalDebit.setText("₹ " + fmt.format(data.optDouble("totalDebit", 0)));
        tvTotalCredit.setText("₹ " + fmt.format(data.optDouble("totalCredit", 0)));

        // Account status badge (desktop save-mode logic)
        String status = data.optString("accountStatus", "");
        if (!status.isEmpty() && !"UNKNOWN".equals(status)) {
            layoutStatus.setVisibility(View.VISIBLE);
            boolean yetToClose = "YET TO CLOSE".equals(status);
            tvAccountStatus.setText(status);
            tvAccountStatus.setTextColor(yetToClose
                    ? Color.parseColor("#FF9800")   // orange  — not yet saved
                    : Color.parseColor("#4CAF50"));  // green   — already saved/closed
        } else {
            layoutStatus.setVisibility(View.GONE);
        }

        // Operations table
        buildOperationsTable(data.optJSONArray("operations"));
    }

    private void buildOperationsTable(JSONArray ops) {
        // Keep only the static header row (first child)
        while (tableOperations.getChildCount() > 1) tableOperations.removeViewAt(1);

        if (ops == null) return;

        // Section divider label above table
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;

            String name   = op.optString("name", "").toUpperCase();
            long   count  = op.optLong("count", 0);
            double debit  = op.optDouble("debit", 0);
            double credit = op.optDouble("credit", 0);
            final String detailType = DETAIL_TYPES.get(name);

            // Alternate row background
            int rowBg = (i % 2 == 0) ? Color.parseColor("#1E2A4A") : Color.parseColor("#16213E");

            // Main row
            TableRow row = new TableRow(this);
            row.setBackgroundColor(rowBg);
            row.setPadding(0, dp(2), 0, dp(2));

            // Name cell (shows tap hint if detail exists)
            TextView tvName = new TextView(this);
            tvName.setText(name + (detailType != null ? " ›" : ""));
            tvName.setTextColor(detailType != null
                    ? Color.parseColor("#64B5F6") : Color.WHITE);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvName.setPadding(dp(8), dp(3), dp(8), dp(3));
            TableRow.LayoutParams flexLp = new TableRow.LayoutParams(
                    0, TableRow.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(flexLp);
            row.addView(tvName);

            row.addView(makeCell(
                    count > 0 ? String.valueOf(count) : "—",
                    Color.parseColor("#AAAAAA"), dp(50), Gravity.CENTER));
            row.addView(makeCell(
                    debit > 0 ? "₹" + fmtShort(debit) : "—",
                    Color.parseColor("#EF9A9A"), dp(80), Gravity.END));
            row.addView(makeCell(
                    credit > 0 ? "₹" + fmtShort(credit) : "—",
                    Color.parseColor("#A5D6A7"), dp(80), Gravity.END));

            // Tap to drill-down
            if (detailType != null) {
                final String finalName = name;
                row.setOnClickListener(v -> openDetail(detailType, finalName));
                row.setForeground(getDrawable(android.R.drawable.list_selector_background));
            }

            tableOperations.addView(row);

            // (detail sub-rows removed — tap the row to see drill-down details)
        }
    }

    private void openDetail(String type, String name) {
        Intent intent = new Intent(this, AccountDetailActivity.class);
        intent.putExtra("companyId",   companyId);
        intent.putExtra("companyName", companyName);
        intent.putExtra("date",        selectedDate);
        intent.putExtra("type",        type);
        intent.putExtra("title",       name);
        startActivity(intent);
    }

    private TextView makeCell(String text, int color, int minWidthPx, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setGravity(gravity);
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        TableRow.LayoutParams lp = new TableRow.LayoutParams(
                minWidthPx, TableRow.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String fmtShort(double v) {
        return fmt.format(v);
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
                getResources().getDisplayMetrics());
    }
}
