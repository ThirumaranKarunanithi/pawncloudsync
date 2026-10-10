package com.pawnbroking.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.Royal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class TodaysAccountActivity extends AppCompatActivity {

    private Royal royal;
    private ProgressBar progressBar;
    private LinearLayout layoutContent;
    private TextView tvCompanyName, tvSelectedDate;
    private TableLayout tableOperations;

    // Balance cards
    private TextView tvPreDate, tvPreActual, tvPreAvailable, tvPreDeficit, tvPreNote;
    private TextView tvActualBalance, tvAvailableBalance, tvDeficit, tvTodaysNote;
    private TextView tvTotalDebit, tvTotalCredit;
    private TextView tvGoldPf, tvSilverPf, tvTotalPf;
    private View layoutStatus;
    private TextView tvAccountStatus;

    private String companyId, companyName;
    private String selectedDate;
    /** The L-marker date (last closed-account day) — drives Previous Day. */
    private String lastLDate;
    /** Cached pre-day balance from the L row — used to derive Today's Balance
     *  when the selected (L+1) date has no own row yet. */
    private double cachedPreActual, cachedPreAvailable, cachedPreDeficit;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat displaySdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    // Maps operation name → detail type key for drill-down. Keys must match
    // the cloud's row names EXACTLY (uppercased) — the cloud emits e.g.
    // "GOLD BILL ADVANCE AMOUNT" (with the word BILL), so the key has to
    // match that, not the shorter "GOLD ADVANCE AMOUNT".
    private static final Map<String, String> DETAIL_TYPES = new LinkedHashMap<>();
    static {
        DETAIL_TYPES.put("GOLD BILL OPENING",          "GOLD_OPENING");
        DETAIL_TYPES.put("GOLD BILL ADVANCE AMOUNT",   "GOLD_ADVANCE");
        DETAIL_TYPES.put("GOLD BILL CLOSING",          "GOLD_CLOSING");
        DETAIL_TYPES.put("SILVER BILL OPENING",        "SILVER_OPENING");
        DETAIL_TYPES.put("SILVER BILL ADVANCE AMOUNT", "SILVER_ADVANCE");
        DETAIL_TYPES.put("SILVER BILL CLOSING",        "SILVER_CLOSING");
        DETAIL_TYPES.put("REPLEDGE BILL OPENING",      "REPLEDGE_OPENING");
        DETAIL_TYPES.put("REPLEDGE BILL CLOSING",      "REPLEDGE_CLOSING");
        DETAIL_TYPES.put("EXPENSES",                   "EXPENSES");
        DETAIL_TYPES.put("INCOMES",                    "INCOMES");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todays_account);

        royal = new Royal(this);
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);

        companyId   = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");
        selectedDate = sdf.format(new Date());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Today's Account");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_detail);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) { load(); return true; }
            return false;
        });

        progressBar    = findViewById(R.id.progressBar);
        layoutContent  = findViewById(R.id.layoutContent);
        tvCompanyName  = findViewById(R.id.tvCompanyName);
        tvSelectedDate = findViewById(R.id.tvSelectedDate);
        tableOperations= findViewById(R.id.tableOperations);

        tvPreDate      = findViewById(R.id.tvPreDate);
        tvPreActual    = findViewById(R.id.tvPreActual);
        tvPreAvailable = findViewById(R.id.tvPreAvailable);
        tvPreDeficit   = findViewById(R.id.tvPreDeficit);
        tvPreNote      = findViewById(R.id.tvPreNote);

        tvActualBalance   = findViewById(R.id.tvActualBalance);
        tvAvailableBalance= findViewById(R.id.tvAvailableBalance);
        tvDeficit         = findViewById(R.id.tvDeficit);
        tvTodaysNote      = findViewById(R.id.tvTodaysNote);

        tvTotalDebit    = findViewById(R.id.tvTotalDebit);
        tvTotalCredit   = findViewById(R.id.tvTotalCredit);
        tvGoldPf        = findViewById(R.id.tvGoldPf);
        tvSilverPf      = findViewById(R.id.tvSilverPf);
        tvTotalPf       = findViewById(R.id.tvTotalPf);
        layoutStatus    = findViewById(R.id.layoutStatus);
        tvAccountStatus = findViewById(R.id.tvAccountStatus);

        tvCompanyName.setText(companyName);
        tvSelectedDate.setText(displaySdf.format(new Date()));

        // Date picker button
        findViewById(R.id.btnPickDate).setOnClickListener(v -> showDatePicker());

        // Most shops aren't running their books up to "today" — default to
        // the last L-marker row in company_todays_account for this company.
        // If lookup fails, fall back to today (the value we just initialised).
        resolveDefaultDateThenLoad();
    }

    private void resolveDefaultDateThenLoad() {
        ApiService.getLastAccountDate(companyId, new ApiService.Callback<String>() {
            @Override public void onSuccess(String isoDate) {
                runOnUiThread(() -> {
                    // Desktop convention: when the last L-marker (last
                    // closed day) is D, default the screen to D+1 — i.e.
                    // "today is the next un-closed business day". D goes
                    // into the Previous Day card.
                    try {
                        Date dL = sdf.parse(isoDate);
                        if (dL != null) {
                            lastLDate = isoDate;
                            Calendar c = Calendar.getInstance();
                            c.setTime(dL);
                            c.add(Calendar.DAY_OF_MONTH, 1);
                            selectedDate = sdf.format(c.getTime());
                            tvSelectedDate.setText(displaySdf.format(c.getTime()));
                        }
                    } catch (Exception ignored) {}
                    load();
                });
            }
            @Override public void onError(String msg) {
                // Make the failure visible — silent fallback to today was
                // masking a real problem (empty network response, JWT issue,
                // cloud not yet redeployed, etc.). Now you see exactly why.
                runOnUiThread(() -> {
                    android.util.Log.w("TodaysAcct", "last-date lookup failed: " + msg);
                    Toast.makeText(TodaysAccountActivity.this,
                        "Last date lookup: " + msg, Toast.LENGTH_LONG).show();
                    load();
                });
            }
        });
    }

    private void showDatePicker() {
        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(
            this, (view, year, month, day) -> {
                Calendar c = Calendar.getInstance();
                c.set(year, month, day);
                selectedDate = sdf.format(c.getTime());
                tvSelectedDate.setText(displaySdf.format(c.getTime()));
                load();
            },
            Calendar.getInstance().get(Calendar.YEAR),
            Calendar.getInstance().get(Calendar.MONTH),
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        // Two parallel fetches:
        //   1. Previous Day card uses the L-marker row (selectedDate - 1 day,
        //      i.e. the last closed account day). If no L row is known yet,
        //      we fall back to the selected date itself.
        //   2. Today's card + operations + Pf bar all key off selectedDate.
        final String preDate = lastLDate != null ? lastLDate : prevDay(selectedDate);
        ApiService.getTodaysAccount(companyId, preDate, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject preRow) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                    bindPrevious(preRow);
                    // After the pre-row populates cached balances, ops load
                    // computes Today's Balance = pre + credits - debits.
                    loadOperations();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                    android.util.Log.w("TodaysAcct", "pre-row load failed: " + message);
                    bindPrevious(new JSONObject()); // zero out, still load ops
                    loadOperations();
                });
            }
        });
    }

    /** Returns date - 1 day in ISO yyyy-MM-dd form. */
    private String prevDay(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(sdf.parse(iso));
            c.add(Calendar.DAY_OF_MONTH, -1);
            return sdf.format(c.getTime());
        } catch (Exception e) { return iso; }
    }

    /** Asks the cloud for per-op debit/credit/count + Pf bar, then derives
     *  Today's Balance from pre-balance + totalCredit − totalDebit. */
    private void loadOperations() {
        ApiService.getTodaysAccountOps(companyId, selectedDate,
            new ApiService.Callback<JSONObject>() {
                @Override public void onSuccess(JSONObject data) {
                    runOnUiThread(() -> {
                        JSONArray ops = data.optJSONArray("operations");
                        buildOperationsTable(ops);
                        double td = data.optDouble("totalDebit",  0);
                        double tc = data.optDouble("totalCredit", 0);
                        double goldPf   = data.optDouble("goldPf",   0);
                        double silverPf = data.optDouble("silverPf", 0);
                        double totalPf  = data.optDouble("totalPf",  0);

                        tvTotalDebit.setText ("₹ " + fmt.format(td));
                        tvTotalCredit.setText("₹ " + fmt.format(tc));
                        tvGoldPf.setText  ("₹ " + fmt.format(goldPf));
                        tvSilverPf.setText("₹ " + fmt.format(silverPf));
                        tvTotalPf.setText ("₹ " + fmt.format(totalPf));

                        // Desktop derivation: today's actual = pre actual +
                        // credits − debits. Same for available; deficit
                        // carries forward unless the user clears it.
                        double actual    = cachedPreActual    + tc - td;
                        double available = cachedPreAvailable + tc - td;
                        double deficit   = cachedPreDeficit;
                        tvActualBalance.setText    ("₹ " + fmt.format(actual));
                        tvAvailableBalance.setText ("₹ " + fmt.format(available));
                        tvDeficit.setText          ("₹ " + fmt.format(deficit));
                        tvDeficit.setTextColor(deficit == 0 ? royal.emerald : royal.ruby);

                        // No L row yet for the selected (L+1) date → OPEN.
                        layoutStatus.setVisibility(View.VISIBLE);
                        tvAccountStatus.setText("OPEN");
                        tvAccountStatus.setTextColor(royal.amber);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> android.util.Log.w("TodaysAcct",
                        "operations load failed: " + message));
                }
            });
    }

    /**
     * Renders only the Previous Day card from the L-marker row. Today's
     * Balance + Pf strip are filled in by {@link #loadOperations()} because
     * they need the operations totals to derive correctly.
     */
    private void bindPrevious(JSONObject data) {
        String preDate = data.optString("preDate", lastLDate == null ? "" : lastLDate);
        // For the L-marker row, the "todays_*" fields are actually the
        // closing balances of that day — which become the OPENING balances
        // for the next (selected) day's Previous Day card.
        double preActual    = data.optDouble("actualBalance",    0);
        double preAvailable = data.optDouble("availableBalance", 0);
        double preDeficit   = data.optDouble("deficit",          0);
        // Cache so the ops callback can derive today's balance from these.
        cachedPreActual    = preActual;
        cachedPreAvailable = preAvailable;
        cachedPreDeficit   = preDeficit;

        // If preDate is empty, fall back to the row's own todays_date.
        if (preDate.isEmpty()) preDate = data.optString("date", "");
        tvPreDate.setText(preDate.isEmpty() ? "—" : preDate);
        tvPreActual.setText("₹ " + fmt.format(preActual));
        tvPreAvailable.setText("₹ " + fmt.format(preAvailable));
        tvPreDeficit.setText("₹ " + fmt.format(preDeficit));
        tvPreDeficit.setTextColor(preDeficit == 0 ? royal.emerald : royal.ruby);
        String preNote = data.optString("todaysNote", "");
        tvPreNote.setText(preNote.isEmpty() ? "" : preNote);
        tvPreNote.setVisibility(preNote.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void buildOperationsTable(JSONArray ops) {
        // Keep only the static header row (first child)
        while (tableOperations.getChildCount() > 1) tableOperations.removeViewAt(1);

        if (ops == null) return;

        // Render EVERY row the cloud returns (always 10 — matches desktop).
        // Zeros render as "0.0" instead of "—" so the grid reads like the
        // desktop screen exactly.
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;

            String name   = op.optString("name", "").toUpperCase();
            long   count  = op.optLong("count", 0);
            double debit  = op.optDouble("debit", 0);
            double credit = op.optDouble("credit", 0);
            String combo  = op.optString("combo", "");
            final String detailType = DETAIL_TYPES.get(name);

            // Parchment with a hairline under it. The old zebra stripe
            // went when the rows stopped being dark.
            TableRow row = new TableRow(this);
            row.setBackgroundResource(R.drawable.bg_table_row);
            row.setPadding(0, dp(2), 0, dp(2));

            // Name, with Combo as a second line beneath it. Combo used to
            // be a fifth cell 220dp wide inside a HorizontalScrollView,
            // which pushed Credits off the right edge of every phone. The
            // RB/NB split is worth reading, so it moved rather than went.
            LinearLayout nameCell = new LinearLayout(this);
            nameCell.setOrientation(LinearLayout.VERTICAL);
            nameCell.setPadding(dp(12), dp(7), dp(6), dp(7));

            TextView tvName = new TextView(this);
            tvName.setText(name + (detailType != null ? " ›" : ""));
            // A row that opens a drill-down is wine, like a link; the two
            // that have no detail type (Liability, Asset) stay plain ink.
            tvName.setTextColor(detailType != null ? royal.wine : royal.ink);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            nameCell.addView(tvName);

            if (combo != null && !combo.trim().isEmpty()) {
                TextView tvCombo = new TextView(this);
                tvCombo.setText(combo.trim());
                tvCombo.setTextColor(royal.inkMute);
                tvCombo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                nameCell.addView(tvCombo);
            }

            TableRow.LayoutParams flexLp = new TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT);
            flexLp.column = 0;
            nameCell.setLayoutParams(flexLp);
            row.addView(nameCell);

            row.addView(makeCell(fmtNum(count),
                    royal.inkSoft, dp(30), Gravity.END));
            row.addView(makeCell("₹" + fmtShort(debit),
                    royal.money(debit, true), dp(70), Gravity.END));
            TextView creditCell = makeCell("₹" + fmtShort(credit),
                    royal.money(credit, false), dp(70), Gravity.END);
            // Line the last column up with the header's 12dp gutter.
            creditCell.setPadding(dp(6), dp(3), dp(12), dp(3));
            row.addView(creditCell);

            if (detailType != null) {
                final String finalName = name;
                row.setOnClickListener(v -> openDetail(detailType, finalName));
                row.setForeground(getDrawable(android.R.drawable.list_selector_background));
            }
            tableOperations.addView(row);
        }
    }

    /** Indian-format the count: "7" → "7", desktop shows "7.0" but we keep
     *  it integer-only since fractional counts are nonsensical. */
    private String fmtNum(long n) {
        return n == 0 ? "0" : String.valueOf(n);
    }

    private void openDetail(String type, String name) {
        Intent intent = new Intent(this, AccountDetailActivity.class);
        intent.putExtra("companyId",   companyId);
        intent.putExtra("companyName", companyName);
        intent.putExtra("date",        selectedDate);
        intent.putExtra("type",        type);
        intent.putExtra("title",       name);
        startActivity(intent);
    }

    private TextView makeCell(String text, int color, int minWidthPx, int gravity) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setGravity(gravity);
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        TableRow.LayoutParams lp = new TableRow.LayoutParams(
                minWidthPx, TableRow.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String fmtShort(double v) {
        return fmt.format(v);
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
                getResources().getDisplayMetrics());
    }
}
