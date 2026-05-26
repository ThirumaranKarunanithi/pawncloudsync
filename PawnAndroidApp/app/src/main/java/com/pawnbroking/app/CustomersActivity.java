package com.pawnbroking.app;

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

import java.util.ArrayList;
import java.util.List;

public class CustomersActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private EditText etSearch;
    private TextView tvEmpty;

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

        adapter = new CustomerAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                handler.removeCallbacks(searchRunnable);
                if (s.length() >= 2) handler.postDelayed(searchRunnable, 400);
                else { customers.clear(); adapter.notifyDataSetChanged(); }
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
                    progressBar.setVisibility(View.GONE);
                    customers.clear();
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.optJSONObject(i);
                        if (o != null) customers.add(o);
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(customers.isEmpty() ? View.VISIBLE : View.GONE);
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
            String spType = c.optString("spouse_type", "");
            String spName = c.optString("spouse_name", "");
            if (!spName.isEmpty()) name += " " + spType + " " + spName;
            h.tvName.setText(name);
            h.tvMobile.setText(c.optString("mobile_number", ""));
            String addr = c.optString("area", "") + ", " + c.optString("city", "");
            h.tvAddr.setText(addr.trim().replaceAll("^,\\s*|,\\s*$", ""));
            String status = c.optString("status", "ACTIVE");
            h.tvStatus.setText(status);
            h.tvStatus.setTextColor("BLOCKED".equalsIgnoreCase(status)
                ? android.graphics.Color.parseColor("#F44336")
                : android.graphics.Color.parseColor("#4CAF50"));
        }
        @Override public int getItemCount() { return customers.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvMobile, tvAddr, tvStatus;
            VH(View v) {
                super(v);
                tvName   = v.findViewById(R.id.tvName);
                tvMobile = v.findViewById(R.id.tvMobile);
                tvAddr   = v.findViewById(R.id.tvAddr);
                tvStatus = v.findViewById(R.id.tvStatus);
            }
        }
    }
}
