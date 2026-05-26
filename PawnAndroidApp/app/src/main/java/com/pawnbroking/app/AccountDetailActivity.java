package com.pawnbroking.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class AccountDetailActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TableLayout tableDetail;
    private TextView tvCount;

    private String companyId, companyName, date, type, title;

    // Maps detail type → {materialType, billNumberColumnIndex}
    // Used to make rows tappable and open BillingActivity
    private static final Map<String, String[]> BILL_NAV = new HashMap<>();
    static {
        BILL_NAV.put("GOLD_OPENING",   new String[]{"GOLD",   "1"});
        BILL_NAV.put("SILVER_OPENING", new String[]{"SILVER", "1"});
        BILL_NAV.put("GOLD_CLOSING",   new String[]{"GOLD",   "1"});
        BILL_NAV.put("SILVER_CLOSING", new String[]{"SILVER", "1"});
        BILL_NAV.put("GOLD_ADVANCE",   new String[]{"GOLD",   "2"});
        BILL_NAV.put("SILVER_ADVANCE", new String[]{"SILVER", "2"});
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_detail);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");
        date        = getIntent().getStringExtra("date");
        type        = getIntent().getStringExtra("type");
        title       = getIntent().getStringExtra("title");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title != null ? title : "Detail");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.tvCompanyName)).setText(companyName);
        ((TextView) findViewById(R.id.tvDate)).setText(date);

        progressBar = findViewById(R.id.progressBar);
        tableDetail = findViewById(R.id.tableDetail);
        tvCount     = findViewById(R.id.tvCount);

        load();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tableDetail.removeAllViews();
        tvCount.setVisibility(View.GONE);

        ApiService.getTodaysAccountDetails(companyId, date, type, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    bind(data);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(AccountDetailActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void bind(JSONObject data) {
        JSONArray headers = data.optJSONArray("headers");
        JSONArray rows    = data.optJSONArray("rows");
        int count         = data.optInt("count", 0);

        tvCount.setText(count + " record" + (count != 1 ? "s" : ""));
        tvCount.setVisibility(View.VISIBLE);

        if (headers == null) return;

        // Header row
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#1E2A4A"));
        for (int i = 0; i < headers.length(); i++) {
            headerRow.addView(makeHeader(headers.optString(i, "")));
        }
        tableDetail.addView(headerRow);

        // Divider
        View div = new View(this);
        TableLayout.LayoutParams divLp = new TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT, dp(1));
        div.setLayoutParams(divLp);
        div.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        tableDetail.addView(div);

        if (rows == null) return;

        // Determine if rows should navigate to BillingActivity
        String[] navInfo   = BILL_NAV.get(type != null ? type.toUpperCase() : "");
        String   navMat    = navInfo != null ? navInfo[0] : null;
        int      billCol   = navInfo != null ? Integer.parseInt(navInfo[1]) : -1;

        // Data rows
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;

            TableRow tr = new TableRow(this);
            int bgColor = (i % 2 == 0)
                    ? Color.parseColor("#16213E")
                    : Color.parseColor("#1A2744");
            tr.setBackgroundColor(bgColor);

            for (int j = 0; j < row.length(); j++) {
                String cellText = row.optString(j, "");
                // Highlight bill number column in gold; right-align numbers
                boolean isBillNoCol = (j == billCol);
                boolean isAmount = false;
                try { Double.parseDouble(cellText.replace(",", "")); isAmount = true; }
                catch (Exception ignored) {}
                TextView cell = makeCell(cellText, isAmount);
                if (isBillNoCol) {
                    cell.setTextColor(Color.parseColor("#E6B800")); // gold
                    cell.setTypeface(null, Typeface.BOLD);
                }
                tr.addView(cell);
            }

            // Make row tappable → open BillingActivity
            if (navMat != null && billCol >= 0 && billCol < row.length()) {
                final String billNumber   = row.optString(billCol, "").trim();
                final String materialType = navMat;
                if (!billNumber.isEmpty()) {
                    tr.setForeground(getDrawable(android.R.drawable.list_selector_background));
                    tr.setOnClickListener(v -> openBill(billNumber, materialType));
                }
            }

            tableDetail.addView(tr);
        }
    }

    private void openBill(String billNumber, String materialType) {
        Intent intent = new Intent(this, BillingActivity.class);
        intent.putExtra("companyId",    companyId);
        intent.putExtra("billNumber",   billNumber);
        intent.putExtra("materialType", materialType);
        startActivity(intent);
    }

    private TextView makeHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E6B800"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView makeCell(String text, boolean rightAlign) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setGravity(rightAlign ? Gravity.END : Gravity.START);
        return tv;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
