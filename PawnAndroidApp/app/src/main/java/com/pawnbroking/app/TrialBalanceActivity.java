package com.pawnbroking.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Locale;

public class TrialBalanceActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private LinearLayout layoutContent, llIncome, llExpense, llAsset, llLiability;
    private TextView tvCompanyName, tvFrom, tvTo;
    private TextView tvTotalIncome, tvTotalExpense, tvTotalAsset;

    private String companyId, companyName;
    private String dateFrom, dateTo;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat dsp = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trial_balance);

        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");

        // Default: first of current month → today
        Calendar cal = Calendar.getInstance();
        dateTo   = sdf.format(cal.getTime());
        cal.set(Calendar.DAY_OF_MONTH, 1);
        dateFrom = sdf.format(cal.getTime());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Trial Balance");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) { load(); return true; }
            return false;
        });

        tvCompanyName = findViewById(R.id.tvCompanyName);
        tvFrom        = findViewById(R.id.tvFrom);
        tvTo          = findViewById(R.id.tvTo);
        progressBar   = findViewById(R.id.progressBar);
        layoutContent = findViewById(R.id.layoutContent);
        llIncome      = findViewById(R.id.llIncome);
        llExpense     = findViewById(R.id.llExpense);
        llAsset       = findViewById(R.id.llAsset);
        llLiability   = findViewById(R.id.llLiability);
        tvTotalIncome = findViewById(R.id.tvTotalIncome);
        tvTotalExpense= findViewById(R.id.tvTotalExpense);
        tvTotalAsset  = findViewById(R.id.tvTotalAsset);

        tvCompanyName.setText(companyName);
        refreshDateLabels();

        try {
            Calendar cf = Calendar.getInstance();
            cf.setTime(sdf.parse(dateFrom));
            tvFrom.setText(dsp.format(cf.getTime()));
            Calendar ct = Calendar.getInstance();
            ct.setTime(sdf.parse(dateTo));
            tvTo.setText(dsp.format(ct.getTime()));
        } catch (Exception ignored) {}

        // Date pickers
        findViewById(R.id.btnFrom).setOnClickListener(v -> pickDate(true));
        findViewById(R.id.btnTo).setOnClickListener(v  -> pickDate(false));

        load();
    }

    private void refreshDateLabels() {
        try {
            tvFrom.setText(dsp.format(sdf.parse(dateFrom)));
            tvTo.setText(dsp.format(sdf.parse(dateTo)));
        } catch (Exception ignored) {}
    }

    private void pickDate(boolean isFrom) {
        Calendar c = Calendar.getInstance();
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this,
            (view, year, month, day) -> {
                Calendar sel = Calendar.getInstance();
                sel.set(year, month, day);
                if (isFrom) { dateFrom = sdf.format(sel.getTime()); }
                else        { dateTo   = sdf.format(sel.getTime()); }
                refreshDateLabels();
                load();
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.show();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        ApiService.getTrialBalance(companyId, dateFrom, dateTo, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                    bind(data);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(TrialBalanceActivity.this, "Error: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void bind(JSONObject data) {
        llIncome.removeAllViews();
        llExpense.removeAllViews();
        llAsset.removeAllViews();
        llLiability.removeAllViews();

        fillSection(llIncome,    data.optJSONArray("income"),    Color.parseColor("#A5D6A7"));
        fillSection(llExpense,   data.optJSONArray("expense"),   Color.parseColor("#EF9A9A"));
        fillSection(llAsset,     data.optJSONArray("asset"),     Color.parseColor("#90CAF9"));
        fillSection(llLiability, data.optJSONArray("liability"), Color.parseColor("#E6B800"));

        tvTotalIncome.setText("₹ " + fmt.format(data.optDouble("totalIncome",  0)));
        tvTotalExpense.setText("₹ " + fmt.format(data.optDouble("totalExpense", 0)));
        tvTotalAsset.setText("₹ "  + fmt.format(data.optDouble("totalAsset",   0)));

        // Profit/loss badge colour
        double profit = data.optDouble("totalIncome", 0) - data.optDouble("totalExpense", 0);
        // Already in liability section
    }

    private void fillSection(LinearLayout container, JSONArray arr, int valueColor) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            double amount = item.optDouble("amount", 0);
            if (amount == 0) continue; // skip zero lines

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView tvName = new TextView(this);
            tvName.setText(item.optString("name", ""));
            tvName.setTextColor(Color.parseColor("#CCCCCC"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(lp);
            row.addView(tvName);

            TextView tvAmt = new TextView(this);
            tvAmt.setText("₹ " + fmt.format(amount));
            tvAmt.setTextColor(amount < 0 ? Color.parseColor("#EF9A9A") : valueColor);
            tvAmt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvAmt.setTypeface(null, Typeface.BOLD);
            row.addView(tvAmt);

            container.addView(row);
        }
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
            getResources().getDisplayMetrics());
    }
}
