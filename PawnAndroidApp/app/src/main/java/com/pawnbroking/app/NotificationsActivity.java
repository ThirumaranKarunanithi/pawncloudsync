package com.pawnbroking.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.services.ApiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * In-app notification centre. Pulls /v1/data/notifications and lists them
 * newest-first. Opening this screen marks every loaded notif_id as read so
 * the toolbar bell badge clears.
 */
public class NotificationsActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TextView tvEmpty;
    private RecyclerView recyclerView;
    private final List<JSONObject> items = new ArrayList<>();
    private NotifAdapter adapter;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_notifications);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Notifications");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        progressBar  = findViewById(R.id.progressBar);
        tvEmpty      = findViewById(R.id.tvEmpty);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotifAdapter();
        recyclerView.setAdapter(adapter);

        load();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        ApiService.getNotifications(100, new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    items.clear();
                    long maxId = ApiService.getLastReadNotifId(NotificationsActivity.this);
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.optJSONObject(i);
                        if (o == null) continue;
                        items.add(o);
                        long id = o.optLong("notif_id", 0);
                        if (id > maxId) maxId = id;
                    }
                    // Mark every loaded notif_id as read so the bell clears.
                    ApiService.markNotificationsRead(NotificationsActivity.this, maxId);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(NotificationsActivity.this,
                        "Error: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.VH> {
        @Override public VH onCreateViewHolder(ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_notification, p, false);
            return new VH(view);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            JSONObject o = items.get(pos);
            h.tvTitle.setText(o.optString("title", ""));
            h.tvBody.setText(o.optString("body", ""));
            String when = o.optString("created_at", "");
            if (when.length() >= 16) when = when.substring(0, 16).replace('T', ' ');
            h.tvWhen.setText(when);
        }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvBody, tvWhen;
            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvTitle);
                tvBody  = v.findViewById(R.id.tvBody);
                tvWhen  = v.findViewById(R.id.tvWhen);
            }
        }
    }
}
