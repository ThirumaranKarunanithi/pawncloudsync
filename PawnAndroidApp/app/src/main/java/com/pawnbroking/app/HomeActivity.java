package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
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

import com.pawnbroking.app.models.Company;
import com.pawnbroking.app.services.ApiService;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends AppCompatActivity {

    private Spinner spinnerCompany;
    private ProgressBar progressCompany;
    private TextView tvNoCompany;
    private View layoutContent;

    private List<Company> companies = new ArrayList<>();
    private Company selectedCompany;

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
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
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
                        }
                        @Override public void onNothingSelected(AdapterView<?> p) {}
                    });
                    selectedCompany = companies.get(0);
                    layoutContent.setVisibility(View.VISIBLE);
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
}
