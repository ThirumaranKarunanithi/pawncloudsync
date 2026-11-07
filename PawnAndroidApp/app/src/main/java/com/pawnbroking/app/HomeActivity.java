package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.pawnbroking.app.models.Company;
import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.BottomNav;
import com.pawnbroking.app.util.Royal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HomeActivity extends AppCompatActivity {

    private Spinner spinnerCompany;
    private Royal royal;
    private ProgressBar progressCompany;
    private TextView tvNoCompany;
    private View layoutContent;
    private LineChart chartBillCount, chartBillAmount;
    private static final java.text.NumberFormat IN_FMT =
            java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"));
    static { IN_FMT.setMaximumFractionDigits(0); }

    private List<Company> companies = new ArrayList<>();
    private Company selectedCompany;

    // Notification bell
    private TextView tvBellBadge;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private static final long POLL_INTERVAL_MS = 30_000L;
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            refreshBellBadge();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        royal = new Royal(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Show the active shop as the toolbar subtitle so it's always clear
        // WHICH shop's records are on screen — the universal single-app model
        // means this changes per logged-in user / Switch Shop selection.
        String activeShop = ApiService.getCurrentShopId(this);
        if (getSupportActionBar() != null && activeShop != null && !activeShop.isEmpty()) {
            getSupportActionBar().setSubtitle(capitalizeShop(activeShop));
        }
        toolbar.setSubtitleTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_wine_dim));

        spinnerCompany  = findViewById(R.id.spinnerCompany);
        progressCompany = findViewById(R.id.progressCompany);
        tvNoCompany     = findViewById(R.id.tvNoCompany);
        layoutContent   = findViewById(R.id.layoutContent);
        chartBillCount  = findViewById(R.id.chartBillCount);
        chartBillAmount = findViewById(R.id.chartBillAmount);
        styleChart(chartBillCount);
        styleChart(chartBillAmount);

        // Main buttons
        findViewById(R.id.btnStockDetails).setOnClickListener(v   -> open(StockDetailsActivity.class));
        findViewById(R.id.btnTodaysAccount).setOnClickListener(v  -> open(TodaysAccountActivity.class));
        findViewById(R.id.btnBilling).setOnClickListener(v        -> open(BillingActivity.class));
        findViewById(R.id.btnMonthlyReport).setOnClickListener(v  -> open(MonthlyReportActivity.class));
        findViewById(R.id.btnEmployeeActivity).setOnClickListener(v -> open(EmployeeActivityActivity.class));
        rateScreen = new java.lang.ref.WeakReference<>(this);
        setUpRateTiles();

        loadCompanies();

        // Daily Wi-Fi fetch of the newest backup so a restore point is already
        // on the phone before anyone needs it. KEEP policy — safe every start.
        com.pawnbroking.app.services.BackupSyncWorker.schedule(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_home, menu);
        MenuItem bell = menu.findItem(R.id.action_notifications);
        if (bell != null) {
            View v = bell.getActionView();
            if (v != null) {
                tvBellBadge = v.findViewById(R.id.tvBellBadge);
                v.setOnClickListener(x -> openNotifications());
            }
        }
        refreshBellBadge();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_notifications) { openNotifications(); return true; }
        if (item.getItemId() == R.id.action_backups) {
            startActivity(new Intent(this, BackupFilesActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_switch_shop) {
            // Picker fetches the user's shops via /my-shops (using the live
            // access token) and re-mints a JWT for the chosen shop.
            Intent i = new Intent(this, ShopPickerActivity.class);
            i.putExtra(ShopPickerActivity.EXTRA_SWITCH_MODE, true);
            i.putExtra(ShopPickerActivity.EXTRA_EMAIL,
                       ApiService.getSavedEmployeeName(this));
            startActivity(i);
            return true;
        }
        if (item.getItemId() == R.id.action_logout) {
            logout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openNotifications() {
        startActivity(new Intent(this, NotificationsActivity.class));
    }

    /** "alwarpuram" → "Alwarpuram" for the toolbar subtitle. */
    private String capitalizeShop(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override protected void onResume() {
        super.onResume();
        // Refresh badge immediately and start the 30s poll loop.
        refreshBellBadge();
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        refreshBoxStrip();
        rateScreen = new java.lang.ref.WeakReference<>(this);
        refreshRate(false);
    }

    // ── Cloud box plan strip (1.6) ────────────────────────────────────────────
    // From 06-11-2026 a paid cloud box plan ends by itself, and when it has,
    // photos and backups stop reaching the cloud. The owner hardly ever opens
    // Backups, so Home says it - only when something needs doing - and a tap
    // opens the plan screen to renew. Asked at most every 10 minutes per shop.

    private static final long BOX_CHECK_MS = 10 * 60 * 1000L;
    private static long boxCheckedAt;
    private static String boxCheckedShop;
    private static JSONObject boxLast;

    /** The plan screen's own answer, so Home does not go on saying "renew" after a payment. */
    static void boxPlanSeen(String shop, JSONObject data) {
        boxLast = data; boxCheckedAt = System.currentTimeMillis(); boxCheckedShop = shop;
    }

    private void refreshBoxStrip() {
        String shop = ApiService.getCurrentShopId(this);
        boolean fresh = boxLast != null && shop != null && shop.equals(boxCheckedShop)
                && System.currentTimeMillis() - boxCheckedAt < BOX_CHECK_MS;
        if (fresh) { applyBoxStrip(boxLast); return; }
        if (shop == null || !shop.equals(boxCheckedShop)) applyBoxStrip(null);
        ApiService.boxPlan(new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                boxLast = data; boxCheckedAt = System.currentTimeMillis(); boxCheckedShop = shop;
                runOnUiThread(() -> { if (!isFinishing()) applyBoxStrip(data); });
            }
            @Override public void onError(String msg) { /* an older cloud, or offline: say nothing */ }
        });
    }

    private void applyBoxStrip(JSONObject p) {
        TextView strip = findViewById(R.id.tvBoxStrip);
        if (strip == null) return;
        String attention = p == null || p.isNull("attention") ? null : p.optString("attention", null);
        JSONObject pending = p == null ? null : p.optJSONObject("pendingPayment");
        boolean checking = pending != null && "PENDING_VERIFICATION".equals(pending.optString("status"));
        if (attention == null || attention.isEmpty()) { strip.setVisibility(View.GONE); return; }

        boolean bad = !checking && ("PAUSED".equals(attention) || "ENDED".equals(attention) || "FULL".equals(attention));
        String text = checking
                ? "Cloud box plan: your payment is being checked by Magizhchi. The plan comes back once it is approved."
                : p.optString("headline", "The cloud box plan needs renewing.") + "  ›";
        strip.setText(text);
        int pad = strip.getPaddingTop();
        strip.setBackgroundResource(bad ? R.drawable.bg_result_bad : R.drawable.bg_result_warn);
        strip.setPadding(pad, pad, pad, pad);
        strip.setTextColor(androidx.core.content.ContextCompat.getColor(this, bad ? R.color.ruby_ink : R.color.ink));
        strip.setOnClickListener(v -> startActivity(new Intent(this, BoxPlanActivity.class)));
        strip.setVisibility(View.VISIBLE);
    }

    // ── Today's rate (1.7) ────────────────────────────────────────────────────
    // The shop's rate board: 22 carat gold and silver a gram, and the company
    // rate under each, for the company on screen. The cloud answers from what
    // the shop's desktop took at its first sign-in of the day, or reads
    // goodreturns.in itself when the desktop has not yet today. The last answer
    // per shop and company is kept on the phone, so the board is there at once
    // and offline, with its date; the cloud is asked again every 5 minutes at
    // most, and on a tap of Refresh.

    private static final long RATE_CHECK_MS = 5 * 60 * 1000L;
    private static final java.util.Map<String, Long> rateAskedAt = new java.util.HashMap<>();
    private static final String RATE_PREFS = "todays_rate";
    private JSONObject rateShown;

    private void setUpRateTiles() {
        View gold = findViewById(R.id.tileRateGold), silver = findViewById(R.id.tileRateSilver);
        gold.setBackgroundResource(R.drawable.bg_rate_gold);
        silver.setBackgroundResource(R.drawable.bg_rate_silver);
        ((TextView) gold.findViewById(R.id.rateMetal)).setText("GOLD");
        ((TextView) gold.findViewById(R.id.rateKind)).setText("22 carat, a gram");
        ((android.widget.ImageView) gold.findViewById(R.id.rateIcon)).setImageResource(R.drawable.ic3d_rate_gold);
        ((TextView) silver.findViewById(R.id.rateMetal)).setText("SILVER");
        ((TextView) silver.findViewById(R.id.rateKind)).setText("a gram");
        ((android.widget.ImageView) silver.findViewById(R.id.rateIcon)).setImageResource(R.drawable.ic3d_rate_silver);
        // The padding was set before the background, which would otherwise replace it with its own.
        for (View t : new View[] {gold, silver}) {
            int s = getResources().getDimensionPixelSize(R.dimen.sp_lg);
            t.setPadding(s, dp(10), dp(10), dp(11));
            t.setOnClickListener(v -> showRateDetails());
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String rateKey() {
        return ApiService.getCurrentShopId(this) + "|" + (selectedCompany == null ? "" : selectedCompany.id);
    }

    private JSONObject keptRate(String key) {
        String s = getSharedPreferences(RATE_PREFS, MODE_PRIVATE).getString(key, null);
        try { return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    private void refreshRate(boolean force) {
        if (selectedCompany == null) return;
        final String key = rateKey();
        JSONObject kept = keptRate(key);
        applyRate(kept);
        Long asked = rateAskedAt.get(key);
        long now = System.currentTimeMillis();
        if (!force && asked != null && now - asked < RATE_CHECK_MS) return;   // asked lately, or still asking
        rateAskedAt.put(key, now);
        final android.content.Context app = getApplicationContext();
        // The answer goes to the Home on screen when it comes, not to the one that asked: a Home redrawn
        // meanwhile (dark theme at start, a turn of the phone) does not ask again while this is on its way.
        ApiService.todaysRate(selectedCompany.id, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                app.getSharedPreferences(RATE_PREFS, MODE_PRIVATE).edit().putString(key, data.toString()).apply();
                HomeActivity a = rateScreen.get();
                if (a == null) return;
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed() || !key.equals(a.rateKey())) return;
                    a.applyRate(data);
                    if (force) Toast.makeText(a, "Today's rate is up to date", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String msg) {
                // Offline, or a cloud from before 1.7: keep what is shown (it carries its own date).
                rateAskedAt.remove(key);
                HomeActivity a = rateScreen.get();
                if (force && a != null) a.runOnUiThread(() -> Toast.makeText(a,
                        "The cloud could not be reached just now", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** The Home on screen, for an answer that comes after a redraw. */
    private static java.lang.ref.WeakReference<HomeActivity> rateScreen = new java.lang.ref.WeakReference<>(null);

    /** Fills the board from the cloud's answer; null or "not available" hides it. */
    private void applyRate(JSONObject r) {
        View section = findViewById(R.id.rateSection);
        rateShown = r;
        if (r == null || !r.optBoolean("available", false)
                || (r.optJSONObject("gold") == null && r.optJSONObject("silver") == null)) {
            section.setVisibility(View.GONE);
            return;
        }
        fillRateTile(findViewById(R.id.tileRateGold), r.optJSONObject("gold"));
        fillRateTile(findViewById(R.id.tileRateSilver), r.optJSONObject("silver"));
        boolean both = r.optJSONObject("gold") != null && r.optJSONObject("silver") != null;
        findViewById(R.id.tileRateSilver).setVisibility(r.optJSONObject("silver") == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.tileRateGold).setVisibility(r.optJSONObject("gold") == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.rateGap).setVisibility(both ? View.VISIBLE : View.GONE);

        // A kept answer from an earlier day says "today" no longer: say its date instead.
        java.time.LocalDate asOf = parseDay(r.optString("as_of", ""));
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean isToday = r.optBoolean("today", false) && today.equals(asOf);
        String note = r.optString("note", "");
        if (r.optBoolean("today", false) && !isToday && asOf != null) {
            String city = r.isNull("city") ? "" : r.optString("city", "");
            note = (city.isEmpty() ? "" : city + " · ") + "as on " + asOf.format(DAY_DMY)
                    + " · could not reach the cloud";
        }
        TextView day = findViewById(R.id.tvRateDay);
        day.setText(asOf == null ? "" : (isToday ? asOf.format(DAY_HEAD) : "AS ON " + asOf.format(DAY_SHORT))
                .toUpperCase(java.util.Locale.ENGLISH));
        ((TextView) findViewById(R.id.tvRateNote)).setText(note);
        int mark = androidx.core.content.ContextCompat.getColor(this, isToday ? R.color.emerald : R.color.amber);
        findViewById(R.id.dotRate).getBackground().mutate().setTint(mark);
        section.setVisibility(View.VISIBLE);
    }

    private static final java.time.format.DateTimeFormatter DAY_HEAD =
            java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM", java.util.Locale.ENGLISH);
    private static final java.time.format.DateTimeFormatter DAY_SHORT =
            java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH);
    private static final java.time.format.DateTimeFormatter DAY_DMY =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy", java.util.Locale.ENGLISH);

    private static java.time.LocalDate parseDay(String s) {
        try { return s == null || s.isEmpty() ? null : java.time.LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private void fillRateTile(View tile, JSONObject m) {
        if (m == null) return;
        ((TextView) tile.findViewById(R.id.rateFigure)).setText(rupees(m.optDouble("rate", 0)));
        String p = m.isNull("percent") ? null : String.valueOf(m.optInt("percent"));
        ((TextView) tile.findViewById(R.id.rateCompanyLabel)).setText(p == null ? "Company rate" : "Company " + p + " %");
        ((TextView) tile.findViewById(R.id.rateCompany)).setText(rupees(m.optDouble("company_rate", 0)));
    }

    /** ₹13,139 / ₹204.90 / ₹1,05,112: Indian grouping, paise only when there are some. */
    private static String rupees(double v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"));
        boolean whole = Math.abs(v - Math.rint(v)) < 0.005;
        f.setMinimumFractionDigits(whole ? 0 : 2);
        f.setMaximumFractionDigits(whole ? 0 : 2);
        return "₹" + f.format(v);
    }

    /** The board's tap: every figure in full - a sovereign, a kilo - where it came from, and Refresh. */
    private void showRateDetails() {
        JSONObject r = rateShown;
        if (r == null) return;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        JSONObject g = r.optJSONObject("gold"), s = r.optJSONObject("silver");
        if (g != null) {
            rateHead(b, "GOLD · 22 CARAT");
            double rate = g.optDouble("rate", 0);
            b.append(rupees(rate)).append("  a gram\n");
            b.append(rupees(Math.round(rate * 8))).append("  a sovereign (8 grams)\n");
            b.append(rupees(g.optDouble("company_rate", 0))).append("  company rate")
                    .append(g.isNull("percent") ? "" : " (" + g.optInt("percent") + " %)").append("\n");
        }
        if (s != null) {
            if (b.length() > 0) b.append("\n");
            rateHead(b, "SILVER");
            double rate = s.optDouble("rate", 0);
            b.append(rupees(rate)).append("  a gram\n");
            b.append(rupees(Math.round(rate * 1000))).append("  a kilo\n");
            b.append(rupees(s.optDouble("company_rate", 0))).append("  company rate")
                    .append(s.isNull("percent") ? "" : " (" + s.optInt("percent") + " %)").append("\n");
        }
        b.append("\n").append(((TextView) findViewById(R.id.tvRateNote)).getText());
        if (r.optBoolean("live", false)) {
            b.append("\n\nThe company rate here is at the company's %. The shop's own figures change when the desktop "
                    + "takes today's rate at sign-in (or with \"Get today's rate now\" in the Company Module).");
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Today's rate" + (selectedCompany == null ? "" : " - " + selectedCompany.name))
                .setMessage(b)
                .setNeutralButton("Refresh", (d, w) -> refreshRate(true))
                .setPositiveButton("OK", null)
                .show();
    }

    private void rateHead(android.text.SpannableStringBuilder b, String text) {
        int from = b.length();
        b.append(text).append("\n");
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, b.length() - 1, 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(this, R.color.gold_ink)), from, b.length() - 1, 0);
    }

    @Override protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void refreshBellBadge() {
        ApiService.getNotifications(50, new ApiService.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray data) {
                long lastRead = ApiService.getLastReadNotifId(HomeActivity.this);
                int unread = 0;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.optJSONObject(i);
                    if (o != null && o.optLong("notif_id", 0) > lastRead) unread++;
                }
                final int count = unread;
                runOnUiThread(() -> applyBadge(count));
            }
            @Override public void onError(String msg) { /* silent on poll */ }
        });
    }

    private void applyBadge(int count) {
        if (tvBellBadge == null) return;
        if (count <= 0) {
            tvBellBadge.setVisibility(View.GONE);
        } else {
            tvBellBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            tvBellBadge.setVisibility(View.VISIBLE);
        }
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
                            attachBar();
                            refreshRate(false);
                            loadCharts();
                        }
                        @Override public void onNothingSelected(AdapterView<?> p) {}
                    });
                    selectedCompany = companies.get(0);
                    attachBar();
                    refreshRate(false);
                    layoutContent.setVisibility(View.VISIBLE);
                    loadCharts();
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

    /**
     * Re-wires the bottom bar. Called whenever the company changes, so
     * the bar carries the company the user is actually looking at — the
     * screens it opens all need it.
     */
    private void attachBar() {
        BottomNav.attach(this, BottomNav.Tab.HOME,
                selectedCompany == null ? null : selectedCompany.id,
                selectedCompany == null ? null : selectedCompany.name);
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
        com.pawnbroking.app.services.BackupSyncWorker.cancel(this);
        ApiService.logout(this);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    // ── Monthly charts ───────────────────────────────────────────────────────

    private void styleChart(LineChart chart) {
        // MPAndroidChart draws outside the resource system, so it has to
        // be handed resolved colours or it keeps its library defaults
        // (white type, invisible on parchment).
        final int ink  = androidx.core.content.ContextCompat.getColor(this, R.color.ink);
        final int mute = androidx.core.content.ContextCompat.getColor(this, R.color.ink_mute);

        chart.setNoDataText("No data yet");
        chart.setNoDataTextColor(mute);
        chart.setDrawGridBackground(false);
        chart.setTouchEnabled(true);
        chart.setPinchZoom(false);
        chart.setScaleEnabled(false);
        Description d = new Description();
        d.setText("");
        chart.setDescription(d);
        chart.getLegend().setTextColor(ink);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setTextColor(mute);
        x.setLabelRotationAngle(-45f);
        x.setGranularity(1f);
        x.setDrawGridLines(false);
        chart.getAxisLeft().setTextColor(mute);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return IN_FMT.format(v); }
        });
        chart.getAxisRight().setEnabled(false);
    }

    private void loadCharts() {
        if (selectedCompany == null) return;
        ApiService.getMonthlyReport(selectedCompany.id, new ApiService.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject data) {
                runOnUiThread(() -> bindCharts(data));
            }
            @Override public void onError(String msg) { /* silent — chart shows "No data" */ }
        });
    }

    private void bindCharts(org.json.JSONObject data) {
        org.json.JSONArray months = data.optJSONArray("months");
        if (months == null) months = new org.json.JSONArray();

        // Cloud returns newest-first; charts read left-to-right by time.
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Entry> total = new java.util.ArrayList<>();
        java.util.List<Entry> gold  = new java.util.ArrayList<>();
        java.util.List<Entry> silver= new java.util.ArrayList<>();
        java.util.List<Entry> totalAmt = new java.util.ArrayList<>();
        java.util.List<Entry> goldAmt  = new java.util.ArrayList<>();
        java.util.List<Entry> silverAmt= new java.util.ArrayList<>();

        int n = months.length();
        for (int i = n - 1, idx = 0; i >= 0; i--, idx++) {
            org.json.JSONObject m = months.optJSONObject(i);
            if (m == null) continue;
            labels.add(formatMonth(m.optString("month", "")));
            total .add(new Entry(idx, (float) m.optDouble("pawnBills",   0)));
            gold  .add(new Entry(idx, (float) m.optDouble("goldBills",   0)));
            silver.add(new Entry(idx, (float) m.optDouble("silverBills", 0)));
            totalAmt .add(new Entry(idx, (float) m.optDouble("pawnAmount",   0)));
            goldAmt  .add(new Entry(idx, (float) m.optDouble("goldAmount",   0)));
            silverAmt.add(new Entry(idx, (float) m.optDouble("silverAmount", 0)));
        }

        renderChart(chartBillCount, labels, total, gold, silver,
                    "Total", "Gold", "Silver");
        renderChart(chartBillAmount, labels, totalAmt, goldAmt, silverAmt,
                    "Total", "Gold", "Silver");
    }

    private void renderChart(LineChart chart, java.util.List<String> labels,
                             java.util.List<Entry> a, java.util.List<Entry> b, java.util.List<Entry> c,
                             String labelA, String labelB, String labelC) {
        if (labels.isEmpty()) { chart.clear(); chart.invalidate(); return; }
        LineDataSet dsTotal = makeSet(a, labelA, royal.ink);      // both metals; wine vanished on the night card
        LineDataSet dsGold  = makeSet(b, labelB, royal.goldRule); // gold
        LineDataSet dsSilver= makeSet(c, labelC, royal.navy);     // silver
        LineData ld = new LineData(dsTotal, dsGold, dsSilver);
        chart.setData(ld);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        // Force a label at every month so each data point is annotated,
        // matching the desktop chart's MMM-YYYY tick style.
        chart.getXAxis().setLabelCount(labels.size(), true);
        chart.animateY(400);
        chart.invalidate();
    }

    private LineDataSet makeSet(java.util.List<Entry> entries, String label, int color) {
        LineDataSet ds = new LineDataSet(entries, label);
        ds.setColor(color);
        ds.setCircleColor(color);
        ds.setLineWidth(2f);
        ds.setCircleRadius(3f);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        return ds;
    }

    /**
     * Normalises the month label for the X-axis. The cloud's monthly-report
     * already returns it pre-formatted as "APR-2026", so we use it as-is.
     * Only if we ever get a raw ISO "2026-04" do we convert it. This avoids
     * the earlier bug where "APR-2026" was mis-parsed into "FEB-APR-".
     */
    private static String formatMonth(String label) {
        if (label == null || label.isEmpty()) return "";
        // Already "MON-YYYY" (a letter in the first char) → use as-is.
        if (Character.isLetter(label.charAt(0))) return label;
        // Raw ISO "YYYY-MM" → convert to "MON-YYYY".
        if (label.length() >= 7 && label.charAt(4) == '-') {
            String[] names = {"JAN","FEB","MAR","APR","MAY","JUN",
                              "JUL","AUG","SEP","OCT","NOV","DEC"};
            try {
                int mo = Integer.parseInt(label.substring(5, 7));
                return names[mo - 1] + "-" + label.substring(0, 4);
            } catch (Exception ignored) {}
        }
        return label;
    }
}
