package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.adapters.BillAdapter;
import com.pawnbroking.app.models.Bill;
import com.pawnbroking.app.services.ApiService;

import java.util.ArrayList;
import java.util.List;

public class BillsActivity extends AppCompatActivity {

    private EditText etSearch;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvTotal, tvEmpty;
    private Button btnLoadMore;

    // filter chips
    private TextView chipAll, chipGold, chipSilver, chipOpened, chipClosed, chipAllStatus;

    private String companyId;
    private String companyName, type = "ALL", status = "OPENED";
    private int page = 0;
    private int total = 0;
    private final List<Bill> bills = new ArrayList<>();
    private BillAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bills);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");
        type        = getIntent().getStringExtra("type");
        status      = getIntent().getStringExtra("status");

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(companyName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch    = findViewById(R.id.etSearch);
        recyclerView= findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        tvTotal     = findViewById(R.id.tvTotal);
        tvEmpty     = findViewById(R.id.tvEmpty);
        btnLoadMore = findViewById(R.id.btnLoadMore);

        chipAll       = findViewById(R.id.chipAll);
        chipGold      = findViewById(R.id.chipGold);
        chipSilver    = findViewById(R.id.chipSilver);
        chipOpened    = findViewById(R.id.chipOpened);
        chipClosed    = findViewById(R.id.chipClosed);
        chipAllStatus = findViewById(R.id.chipAllStatus);

        adapter = new BillAdapter(bills, bill -> {
            Intent i = new Intent(this, BillDetailActivity.class);
            i.putExtra("companyId", companyId);
            i.putExtra("companyName", companyName);
            i.putExtra("billNumber",  bill.billNumber);
            i.putExtra("type",        bill.materialType);
            startActivity(i);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        updateChips();

        chipAll.setOnClickListener(v -> { type = "ALL"; updateChips(); load(true); });
        chipGold.setOnClickListener(v -> { type = "GOLD"; updateChips(); load(true); });
        chipSilver.setOnClickListener(v -> { type = "SILVER"; updateChips(); load(true); });
        chipOpened.setOnClickListener(v -> { status = "OPENED"; updateChips(); load(true); });
        chipClosed.setOnClickListener(v -> { status = "CLOSED"; updateChips(); load(true); });
        chipAllStatus.setOnClickListener(v -> { status = "ALL"; updateChips(); load(true); });

        btnLoadMore.setOnClickListener(v -> { page++; load(false); });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { load(true); }
        });

        load(true);
    }

    private void load(boolean reset) {
        if (reset) { page = 0; bills.clear(); adapter.notifyDataSetChanged(); }
        progressBar.setVisibility(View.VISIBLE);
        btnLoadMore.setVisibility(View.GONE);

        ApiService.getBills(companyId, type, status,
            etSearch.getText().toString(), page, 20,
            new ApiService.Callback<ApiService.BillsResult>() {
                @Override public void onSuccess(ApiService.BillsResult result) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        total = result.total;
                        bills.addAll(result.bills);
                        adapter.notifyDataSetChanged();
                        tvTotal.setText(total + " bills found");
                        tvEmpty.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
                        btnLoadMore.setVisibility(bills.size() < total ? View.VISIBLE : View.GONE);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(BillsActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    private void updateChips() {
        int gold = 0xFFD4AF37, white = 0xFFFFFFFF, dark = 0xFF16213E, green = 0xFF4CAF50;
        setChip(chipAll,       "ALL".equals(type),   gold, dark);
        setChip(chipGold,      "GOLD".equals(type),  gold, dark);
        setChip(chipSilver,    "SILVER".equals(type),gold, dark);
        setChip(chipOpened,    "OPENED".equals(status), green, dark);
        setChip(chipClosed,    "CLOSED".equals(status), green, dark);
        setChip(chipAllStatus, "ALL".equals(status),    green, dark);
    }

    private void setChip(TextView chip, boolean selected, int selectedBg, int selectedText) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.chip_selected);
            chip.setTextColor(selectedText);
        } else {
            chip.setBackgroundResource(R.drawable.chip_unselected);
            chip.setTextColor(0xFFCCCCCC);
        }
    }
}
