package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.models.User;
import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown when a user's OTP login resolves to MULTIPLE shops they have
 * access to. The picker lists each shop; tapping one exchanges the
 * selector token for a full access token bound to that shop, then
 * advances to Home.
 *
 * Also reachable from Home's overflow menu (Switch Shop) — in that case
 * the selectorToken extra is omitted and we use the live access token's
 * /my-shops endpoint instead.
 */
public class ShopPickerActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTOR_TOKEN = "selector_token";
    public static final String EXTRA_EMAIL          = "email";
    public static final String EXTRA_SHOPS_JSON     = "shops_json";
    public static final String EXTRA_SWITCH_MODE    = "switch_mode";

    private ProgressBar progressBar;
    private TextView    tvError;
    private RecyclerView rvShops;

    private String  selectorToken;
    private String  email;
    private boolean switchMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shop_picker);

        selectorToken = getIntent().getStringExtra(EXTRA_SELECTOR_TOKEN);
        email         = getIntent().getStringExtra(EXTRA_EMAIL);
        switchMode    = getIntent().getBooleanExtra(EXTRA_SWITCH_MODE, false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (switchMode && getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Switch Shop");
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        progressBar = findViewById(R.id.progressBar);
        tvError     = findViewById(R.id.tvError);
        rvShops     = findViewById(R.id.rvShops);
        rvShops.setLayoutManager(new LinearLayoutManager(this));

        // Two entry paths:
        //   1) From LoginActivity post-OTP — shops list was already pre-fetched
        //      and passed in via EXTRA_SHOPS_JSON to save a round trip.
        //   2) From Home's "Switch Shop" menu — we fetch the list ourselves.
        String shopsJson = getIntent().getStringExtra(EXTRA_SHOPS_JSON);
        if (shopsJson != null && !shopsJson.isEmpty()) {
            try { bindShops(new JSONArray(shopsJson)); }
            catch (Exception e) { showError("Bad shop list: " + e.getMessage()); }
        } else {
            loadShopsFromCloud();
        }
    }

    private void loadShopsFromCloud() {
        progressBar.setVisibility(View.VISIBLE);
        ApiService.getMyShops(new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray shops) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    bindShops(shops);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showError(message);
                });
            }
        });
    }

    private void bindShops(JSONArray shops) {
        if (shops == null || shops.length() == 0) {
            showError("No shops available for this account.");
            return;
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < shops.length(); i++) {
            JSONObject row = shops.optJSONObject(i);
            if (row != null) list.add(row);
        }
        rvShops.setAdapter(new ShopAdapter(list));
    }

    private void onShopChosen(String shopId) {
        progressBar.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);

        // Both paths converge: selectorToken is non-empty for first-login,
        // null/empty for Switch-Shop from Home — selectShop falls back to
        // the access token in prefs in that case.
        ApiService.selectShop(this, selectorToken, shopId, email,
            new ApiService.Callback<User>() {
                @Override public void onSuccess(User result) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        // Clear back stack so user can't navigate back to
                        // either the picker or LoginActivity.
                        Intent intent = new Intent(ShopPickerActivity.this, HomeActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        showError(message);
                    });
                }
            });
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    // ── Adapter ─────────────────────────────────────────────────────────

    private class ShopAdapter extends RecyclerView.Adapter<ShopAdapter.VH> {
        private final List<JSONObject> items;
        ShopAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_shop, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject row = items.get(pos);
            String label  = row.optString("label",   row.optString("shop_id", ""));
            String shopId = row.optString("shop_id", "");
            h.tvLabel.setText(label);
            h.tvShopId.setText("ID: " + shopId);
            h.itemView.setOnClickListener(v -> onShopChosen(shopId));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvLabel, tvShopId;
            VH(@NonNull View v) {
                super(v);
                tvLabel  = v.findViewById(R.id.tvShopLabel);
                tvShopId = v.findViewById(R.id.tvShopId);
            }
        }
    }
}
