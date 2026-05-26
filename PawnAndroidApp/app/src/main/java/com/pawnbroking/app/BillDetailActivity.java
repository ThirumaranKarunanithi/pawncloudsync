package com.pawnbroking.app;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class BillDetailActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private LinearLayout layoutContent;
    private TextView tvBillNumber, tvCompanyName, tvStatus, tvMaterialIcon, tvNote;
    private LinearLayout sectionCustomer, sectionJewel, sectionFinancial,
                         sectionDates, sectionClosing, sectionNote;

    private String companyId;
    private String billNumber, type, companyName;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bill_detail);

        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);

        companyId   = getIntent().getStringExtra("companyId");
        billNumber  = getIntent().getStringExtra("billNumber");
        type        = getIntent().getStringExtra("type");
        companyName = getIntent().getStringExtra("companyName");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Bill: " + billNumber);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) { loadBill(); return true; }
            return false;
        });

        progressBar    = findViewById(R.id.progressBar);
        layoutContent  = findViewById(R.id.layoutContent);
        tvBillNumber   = findViewById(R.id.tvBillNumber);
        tvCompanyName  = findViewById(R.id.tvCompanyName);
        tvStatus       = findViewById(R.id.tvStatus);
        tvMaterialIcon = findViewById(R.id.tvMaterialIcon);
        tvNote         = findViewById(R.id.tvNote);
        sectionCustomer  = findViewById(R.id.sectionCustomer);
        sectionJewel     = findViewById(R.id.sectionJewel);
        sectionFinancial = findViewById(R.id.sectionFinancial);
        sectionDates     = findViewById(R.id.sectionDates);
        sectionClosing   = findViewById(R.id.sectionClosing);
        sectionNote      = findViewById(R.id.sectionNote);

        loadBill();
    }

    private void loadBill() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        ApiService.getBillDetail(companyId, billNumber, type, new ApiService.Callback<JSONObject>() {
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
                    Toast.makeText(BillDetailActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void bind(JSONObject b) {
        boolean isGold = "GOLD".equals(type);
        int goldColor  = isGold ? Color.parseColor("#E6B800") : Color.parseColor("#AAAAAA");
        String status  = b.optString("status", "");

        tvBillNumber.setText(billNumber);
        tvBillNumber.setTextColor(goldColor);
        tvMaterialIcon.setText(isGold ? "★" : "●");
        tvMaterialIcon.setTextColor(goldColor);
        tvCompanyName.setText(companyName);
        tvStatus.setText(status);
        int statusColor;
        switch (status) {
            case "OPENED": statusColor = Color.parseColor("#4CAF50"); break;
            case "CLOSED": statusColor = Color.parseColor("#FF9800"); break;
            default:       statusColor = Color.parseColor("#2196F3"); break;
        }
        tvStatus.setTextColor(statusColor);

        // Customer
        clearSection(sectionCustomer);
        addRow(sectionCustomer, "Name",     b.optString("customer_name", null));
        addRow(sectionCustomer, "Mobile",   b.optString("mobile_number", null));
        addRow(sectionCustomer, "Mobile 2", b.optString("mobile_number_2", null));
        addRow(sectionCustomer, "Address",  buildAddress(b));
        addRow(sectionCustomer, "Nominee",  b.optString("nominee_name", null));
        String idType = b.optString("cust_id_proof_type", "");
        String idNum  = b.optString("cust_id_proof_number", "");
        String idFull = (idType + " " + idNum).trim();
        addRow(sectionCustomer, "ID Proof", idFull.isEmpty() ? null : idFull);

        // Jewel
        clearSection(sectionJewel);
        addRow(sectionJewel, "Material",     type);
        addRow(sectionJewel, "Items",        b.optString("items", null));
        addRow(sectionJewel, "Gross Weight", val(b.opt("gross_weight")));
        addRow(sectionJewel, "Net Weight",   val(b.opt("net_weight")));
        addRow(sectionJewel, "Purity",       val(b.opt("purity")));

        // Financial
        clearSection(sectionFinancial);
        addRow(sectionFinancial, "Loan Amount",   money(b.opt("amount")));
        addRow(sectionFinancial, "Interest",      money(b.opt("interest")));
        addRow(sectionFinancial, "Doc Charge",    money(b.opt("document_charge")));
        addRow(sectionFinancial, "Given Amount",  money(b.opt("given_amount")));
        addRow(sectionFinancial, "To Give",       money(b.opt("togive_amount")));
        addRow(sectionFinancial, "Advance Paid",  money(b.opt("total_advance_amount_paid")));

        // Dates
        clearSection(sectionDates);
        addRow(sectionDates, "Opening Date",   coalesce(b.optString("opening_date_str", null), b.optString("opening_date", null)));
        addRow(sectionDates, "Expected Close", coalesce(b.optString("accepted_closing_date_str", null), b.optString("accepted_closing_date", null)));
        addRow(sectionDates, "Closing Date",   coalesce(b.optString("closing_date_str", null), b.optString("closing_date", null)));
        addRow(sectionDates, "Created By",     val(b.opt("created_user_id")));
        addRow(sectionDates, "Created At",     coalesce(b.optString("created_date_str", null), b.optString("created_date", null)));

        // Closing
        if (!"OPENED".equals(status)) {
            sectionClosing.setVisibility(View.VISIBLE);
            clearSection(sectionClosing);
            addRow(sectionClosing, "Got Amount",  money(b.opt("got_amount")));
            addRow(sectionClosing, "To Get",      money(b.opt("toget_amount")));
            addRow(sectionClosing, "Discount",    money(b.opt("discount_amount")));
            addRow(sectionClosing, "Closed By",   val(b.opt("closed_user_id")));
            addRow(sectionClosing, "Closed Date", coalesce(b.optString("closed_date_str", null), b.optString("closed_date", null)));
        } else {
            sectionClosing.setVisibility(View.GONE);
        }

        // Note
        String note = b.optString("note", "");
        if (!note.isEmpty()) {
            sectionNote.setVisibility(View.VISIBLE);
            tvNote.setText(note);
        } else {
            sectionNote.setVisibility(View.GONE);
        }
    }

    /** Remove dynamically-added rows (keep first 2 children: header + divider) */
    private void clearSection(LinearLayout section) {
        while (section.getChildCount() > 2) section.removeViewAt(2);
    }

    private void addRow(LinearLayout section, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;

        int dp16 = dp(16); int dp8 = dp(8);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp16, dp8, dp16, dp8);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor("#8AFFFFFF"));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLabel.setLayoutParams(lp);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvValue.setLayoutParams(lp2);

        row.addView(tvLabel);
        row.addView(tvValue);
        section.addView(row);
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
                getResources().getDisplayMetrics());
    }

    private String buildAddress(JSONObject b) {
        String[] parts = {
            b.optString("door_number", null), b.optString("street", null),
            b.optString("area", null),        b.optString("city", null)
        };
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String money(Object v) {
        if (v == null) return null;
        double d;
        if (v instanceof Number) d = ((Number) v).doubleValue();
        else { try { d = Double.parseDouble(v.toString()); } catch (Exception e) { return null; } }
        if (d == 0) return null;
        return "₹ " + fmt.format(d);
    }

    private String val(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String coalesce(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return null;
    }
}
