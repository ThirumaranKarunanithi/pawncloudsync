package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomersActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private EditText etSearch;
    private TextView tvEmpty, tvNote;

    private String companyId, companyName;
    private final List<JSONObject> customers = new ArrayList<>();
    private CustomerAdapter adapter;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = this::search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customers);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Customers");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.tvCompanyName)).setText(companyName);

        progressBar  = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);
        etSearch     = findViewById(R.id.etSearch);
        tvEmpty      = findViewById(R.id.tvEmpty);
        tvNote       = findViewById(R.id.tvNote);

        adapter = new CustomerAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                handler.removeCallbacks(searchRunnable);
                if (s.length() >= 2) handler.postDelayed(searchRunnable, 400);
                else { customers.clear(); adapter.notifyDataSetChanged(); tvNote.setVisibility(View.GONE); }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void search() {
        String query = etSearch.getText().toString().trim();
        if (query.length() < 2) return;

        progressBar.setVisibility(View.VISIBLE);
        ApiService.searchCustomers(companyId, query, new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray data) {
                runOnUiThread(() -> {
                    // A slower, older search must not overwrite a newer one.
                    if (!query.equals(etSearch.getText().toString().trim())) return;
                    progressBar.setVisibility(View.GONE);
                    customers.clear();
                    boolean capped = false;
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.optJSONObject(i);
                        if (o != null) { customers.add(o); capped |= o.optBoolean("capped"); }
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(customers.isEmpty() ? View.VISIBLE : View.GONE);
                    int n = customers.size();
                    tvNote.setText(n + (n == 1 ? " customer" : " customers") + (capped
                            ? " · found from the newest " + ApiService.CUSTOMER_BILLS_MAX
                              + " bills that match — type more of the name to reach older customers"
                            : ""));
                    tvNote.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(CustomersActivity.this, "Error: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    class CustomerAdapter extends RecyclerView.Adapter<CustomerAdapter.VH> {
        @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_customer, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            JSONObject c = customers.get(pos);
            String name = c.optString("customer_name", "");
            String nameTa = c.optString("customer_name_ta", "").trim();
            if (!nameTa.isEmpty()) name += "  " + nameTa;
            String spType = c.optString("spouse_type", "");
            String spName = c.optString("spouse_name", "");
            if (!spName.isEmpty()) name += " " + spType + " " + spName;
            h.tvName.setText(name);
            String mobile = c.optString("mobile_number", "").trim();
            h.tvMobile.setText(mobile);
            h.tvMobile.setVisibility(mobile.isEmpty() ? View.GONE : View.VISIBLE);
            h.tvAddr.setText(joinPlace(c.optString("street", ""), c.optString("area", ""), c.optString("city", "")));
            // Only a blocked customer is worth a word; ACTIVE on every row
            // said nothing.
            boolean blocked = "BLOCKED".equalsIgnoreCase(c.optString("customer_status", ""));
            h.tvStatus.setText(blocked ? "BLOCKED" : "");
            h.tvStatus.setVisibility(blocked ? View.VISIBLE : View.GONE);
            h.tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(
                h.itemView.getContext(), R.color.ruby));
            h.tvBills.setText(billsLine(c));
            h.itemView.setOnClickListener(v -> openBills(c));
        }
        @Override public int getItemCount() { return customers.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvMobile, tvAddr, tvStatus, tvBills;
            VH(View v) {
                super(v);
                tvName   = v.findViewById(R.id.tvName);
                tvMobile = v.findViewById(R.id.tvMobile);
                tvAddr   = v.findViewById(R.id.tvAddr);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvBills  = v.findViewById(R.id.tvBills);
            }
        }
    }

    private static String joinPlace(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty() || "null".equals(p)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    /** "12 bills · 2 open · ₹45,000 · last 20-10-2026", or "No bills yet". */
    private static String billsLine(JSONObject c) {
        int bills = c.optInt("bills");
        if (bills == 0) return "No bills yet";
        int open = c.optInt("open");
        StringBuilder sb = new StringBuilder();
        sb.append(bills).append(bills == 1 ? " bill" : " bills");
        if (open > 0) {
            sb.append(" · ").append(open).append(" open · ₹")
              .append(NumberFormat.getNumberInstance(new Locale("en", "IN"))
                      .format(Math.round(c.optDouble("owed", 0))));
        } else {
            sb.append(" · none open");
        }
        String last = c.optString("last", "");
        if (last.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            sb.append(" · last ").append(last, 8, 10).append('-')
              .append(last, 5, 7).append('-').append(last, 0, 4);
        }
        return sb.toString();
    }

    /**
     * Lists this customer's bills, every status. The mobile picks out this one
     * person; a name alone would list everyone who shares it.
     */
    private void openBills(JSONObject c) {
        String mobile = c.optString("mobile_number", "").trim();
        Intent i = new Intent(this, BillsActivity.class);
        i.putExtra("companyId", companyId);
        i.putExtra("companyName", companyName);
        i.putExtra("type", "ALL");
        i.putExtra("status", "ALL");
        i.putExtra("search", mobile.isEmpty() ? c.optString("customer_name", "") : mobile);
        startActivity(i);
    }
}
