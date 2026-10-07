package com.pawnbroking.app;

import com.pawnbroking.app.util.DateFmt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.pawnbroking.app.config.AppConfig;
import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.services.BackupStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Off-site backup browser.
 *
 * Lists every backup the sync-agent has pushed to Magizhchi Share (newest
 * first), shows which are cached on this phone, and lets the user download or
 * share one. The newest file is also fetched automatically once a day by
 * {@link com.pawnbroking.app.services.BackupSyncWorker} on Wi-Fi.
 *
 * Local copies are capped at {@link AppConfig#BACKUP_KEEP_LOCAL}; pruning only
 * happens AFTER a newer download succeeds, so the phone always holds several
 * restore points rather than betting everything on the latest file.
 */
public class BackupFilesActivity extends AppCompatActivity {

    /** Warn if the newest cloud backup is older than this. */
    private static final long STALE_AFTER_MS = 48L * 60 * 60 * 1000;

    private ProgressBar progressBar;
    private TextView tvEmpty, tvLatestInfo, tvStorageInfo, tvStaleWarning;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;

    /** Everything the cloud returned. */
    private final List<JSONObject> allItems = new ArrayList<>();
    /** What the list currently shows — allItems narrowed by the search box. */
    private final List<JSONObject> items = new ArrayList<>();
    private String query = "";
    private BackupAdapter adapter;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_backups);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Backups");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        progressBar    = findViewById(R.id.progressBar);
        tvEmpty        = findViewById(R.id.tvEmpty);
        tvLatestInfo   = findViewById(R.id.tvLatestInfo);
        tvStorageInfo  = findViewById(R.id.tvStorageInfo);
        tvStaleWarning = findViewById(R.id.tvStaleWarning);
        recyclerView   = findViewById(R.id.recyclerView);
        swipeRefresh   = findViewById(R.id.swipeRefresh);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BackupAdapter();
        recyclerView.setAdapter(adapter);
        swipeRefresh.setOnRefreshListener(this::load);

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.US);
                applyFilter();
            }
        });

        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Clear downloaded files");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            new AlertDialog.Builder(this)
                .setTitle("Clear downloaded backups?")
                .setMessage("This removes the copies stored on this phone. "
                          + "The backups stay safe in the cloud and can be downloaded again.")
                .setPositiveButton("Clear", (d, w) -> {
                    int n = BackupStore.clearAll(this);
                    Toast.makeText(this, "Removed " + n + " file(s)", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                    updateSummary();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        progressBar.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        tvEmpty.setVisibility(View.GONE);
        ApiService.listBackups(200, new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray data) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    allItems.clear();
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject o = data.optJSONObject(i);
                        if (o != null) allItems.add(o);
                    }
                    applyFilter();
                    updateSummary();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                    Toast.makeText(BackupFilesActivity.this, "Error: " + msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * Narrows the list to rows matching the search box. Matching runs over the
     * file name, company and the FORMATTED date, so "01082026", "cmp1" and
     * "02 aug" all find what the user sees on screen.
     */
    private void applyFilter() {
        items.clear();
        for (JSONObject o : allItems) {
            if (query.isEmpty() || matches(o, query)) items.add(o);
        }
        adapter.notifyDataSetChanged();

        if (items.isEmpty()) {
            tvEmpty.setText(allItems.isEmpty()
                    ? "No backup files yet"
                    : "No backups match \"" + query + "\"");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private static boolean matches(JSONObject o, String q) {
        return o.optString("file_name", "").toLowerCase(Locale.US).contains(q)
            || o.optString("company_id", "").toLowerCase(Locale.US).contains(q)
            || o.optString("relative_path", "").toLowerCase(Locale.US).contains(q)
            || prettyDate(o.optString("uploaded_at", "")).toLowerCase(Locale.US).contains(q);
    }

    private void updateSummary() {
        int cached = BackupStore.localFiles(this).size();
        tvStorageInfo.setText(cached + " of " + AppConfig.BACKUP_KEEP_LOCAL
                + " kept on this phone • " + BackupStore.humanSize(BackupStore.usedBytes(this)));

        // Always describe the newest backup overall — a search must never make
        // the shop look like it has fewer (or staler) backups than it really has.
        if (allItems.isEmpty()) {
            tvLatestInfo.setText("No backups uploaded yet");
            tvStaleWarning.setVisibility(View.GONE);
            return;
        }
        JSONObject newest = allItems.get(0);
        String when = newest.optString("uploaded_at", "");
        tvLatestInfo.setText("Latest: " + newest.optString("file_name", "?")
                + "  (" + BackupStore.humanSize(newest.optLong("file_size_bytes", 0)) + ")");

        // Backup jobs fail silently on the shop PC; surfacing the age here is
        // the only way the owner finds out before they actually need it.
        long ts = parseTimestamp(when);
        if (ts > 0 && System.currentTimeMillis() - ts > STALE_AFTER_MS) {
            long days = (System.currentTimeMillis() - ts) / (24L * 60 * 60 * 1000);
            tvStaleWarning.setText("⚠ No new backup for " + days
                    + " day(s) — check the shop PC's backup job.");
            tvStaleWarning.setVisibility(View.VISIBLE);
        } else {
            tvStaleWarning.setVisibility(View.GONE);
        }
    }

    private static long parseTimestamp(String s) {
        if (s == null || s.isEmpty()) return 0;
        String v = s.length() > 19 ? s.substring(0, 19) : s;
        v = v.replace('T', ' ');
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date d = f.parse(v);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) { return 0; }
    }

    private static String prettyDate(String raw) {
        return DateFmt.stamp(raw);
    }

    private void download(JSONObject o, BackupVH vh) {
        String fileName  = o.optString("file_name", "");
        String companyId = o.optString("company_id", "");
        String relPath   = o.optString("relative_path", "");
        if (fileName.isEmpty() || companyId.isEmpty()) return;

        vh.progress.setVisibility(View.VISIBLE);
        vh.btnDownload.setEnabled(false);
        exec.execute(() -> {
            String err = null;
            try {
                File dest = BackupStore.localFile(this, fileName);
                ApiService.downloadBackupSync(companyId, relPath, fileName, dest);
                // Prune only after the new copy is safely written.
                BackupStore.pruneOldCopies(this);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            runOnUiThread(() -> {
                vh.progress.setVisibility(View.GONE);
                vh.btnDownload.setEnabled(true);
                if (fErr != null) {
                    Toast.makeText(this, "Download failed: " + fErr, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Downloaded", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                    updateSummary();
                }
            });
        });
    }

    private void share(String fileName) {
        File f = BackupStore.localFile(this, fileName);
        if (!f.exists()) {
            Toast.makeText(this, "Download it first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share backup"));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class BackupVH extends RecyclerView.ViewHolder {
        final TextView name, meta, status;
        final Button btnDownload, btnShare;
        final ProgressBar progress;
        BackupVH(View v) {
            super(v);
            name        = v.findViewById(R.id.tvFileName);
            meta        = v.findViewById(R.id.tvMeta);
            status      = v.findViewById(R.id.tvStatus);
            btnDownload = v.findViewById(R.id.btnDownload);
            btnShare    = v.findViewById(R.id.btnShare);
            progress    = v.findViewById(R.id.progressItem);
        }
    }

    private class BackupAdapter extends RecyclerView.Adapter<BackupVH> {
        @Override public BackupVH onCreateViewHolder(ViewGroup p, int t) {
            return new BackupVH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_backup, p, false));
        }

        @Override public void onBindViewHolder(BackupVH h, int pos) {
            JSONObject o = items.get(pos);
            String fileName = o.optString("file_name", "?");
            h.name.setText(fileName);
            h.meta.setText(prettyDate(o.optString("uploaded_at", ""))
                    + "  •  " + BackupStore.humanSize(o.optLong("file_size_bytes", 0))
                    + "  •  " + o.optString("company_id", ""));

            boolean cached = BackupStore.isDownloaded(BackupFilesActivity.this, fileName);
            if (cached) {
                h.status.setText("✓ On this phone");
                h.status.setTextColor(getResources().getColor(R.color.green));
                h.btnDownload.setText("Re-download");
                h.btnShare.setVisibility(View.VISIBLE);
            } else {
                h.status.setText("In cloud only");
                h.status.setTextColor(getResources().getColor(R.color.white54));
                h.btnDownload.setText("Download");
                h.btnShare.setVisibility(View.GONE);
            }
            h.progress.setVisibility(View.GONE);
            h.btnDownload.setEnabled(true);
            h.btnDownload.setOnClickListener(v -> download(o, h));
            h.btnShare.setOnClickListener(v -> share(fileName));
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
