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
    // A View, not a LinearLayout: the id sits on the ScrollView that wraps
    // the page, and it is only ever shown or hidden. Typing it to the
    // concrete class made findViewById throw ClassCastException the moment
    // the layout wrapped it in a scroller, which closed the app on open.
    private View layoutContent;
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
    /** What the shop carried INTO the selected day — the Previous Day card. */
    private double cachedPreActual, cachedPreAvailable, cachedPreDeficit;
    /** Set when the selected day has a row of its own, i.e. it has been settled. */
    private boolean daySettled;
    /** A settled day's own stored closing figures. Shown as they stand, never re-derived. */
    private double settledActual, settledAvailable, settledDeficit;
    /** "CLOSED" when the settled row is the locked one (ref_mark L). */
    private String dayStatus = "";
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    /** The same Indian grouping without the paise, for the narrow columns of the grid. */
    private final NumberFormat wholeRupees = NumberFormat.getIntegerInstance(new Locale("en", "IN"));
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
        // Open on the day being looked at, not on the phone's today: most shops are running
        // several days behind the calendar, and landing on today every time means scrolling back.
        Calendar open = Calendar.getInstance();
        try {
            Date d = sdf.parse(selectedDate);
            if (d != null) open.setTime(d);
        } catch (Exception ignored) {}

        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(
            this, (view, year, month, day) -> {
                Calendar c = Calendar.getInstance();
                c.set(year, month, day);
                selectedDate = sdf.format(c.getTime());
                tvSelectedDate.setText(displaySdf.format(c.getTime()));
                load();
            },
            open.get(Calendar.YEAR),
            open.get(Calendar.MONTH),
            open.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    /**
     * One day's figures, the way the desktop reads them.
     *
     * <p>A row of {@code company_todays_account} holds BOTH halves of a day: {@code pre_*} is what
     * the shop carried in, {@code todays_*} is what it closed on. So the selected day's own row is
     * asked for first, and it answers the whole screen by itself. Only a day that has no row - one
     * nobody has settled yet - needs the day before it, and then its close is this day's opening.
     *
     * <p>This screen used to take the LAST CLOSED day's row every time and read the two halves out
     * of it crosswise: the date off {@code pre_date} and the money off {@code todays_actual}. On a
     * day that was already settled that put the day's own closing balance under the day before's
     * date, and then added the day's trading to it a second time - the day counted twice. The
     * desktop hit the same thing once and left a note about it in DrawerDay.
     */
    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        ApiService.getTodaysAccount(companyId, selectedDate, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject row) {
                // accountStatus is only put there when a row was actually found.
                if (row.has("accountStatus")) {
                    runOnUiThread(() -> {
                        showContent();
                        bindSettledDay(row);
                        loadOperations();
                    });
                } else {
                    loadDayNotSettled();
                }
            }
            @Override public void onError(String message) {
                android.util.Log.w("TodaysAcct", "day row load failed: " + message);
                loadDayNotSettled();
            }
        });
    }

    /** No row for the selected day, so its opening is the close of the day before it. */
    private void loadDayNotSettled() {
        final String before = prevDay(selectedDate);
        ApiService.getTodaysAccount(companyId, before, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject preRow) {
                runOnUiThread(() -> {
                    showContent();
                    bindOpeningFromDayBefore(before, preRow);
                    loadOperations();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    showContent();
                    android.util.Log.w("TodaysAcct", "pre-row load failed: " + message);
                    bindOpeningFromDayBefore(before, new JSONObject());
                    loadOperations();
                });
            }
        });
    }

    private void showContent() {
        progressBar.setVisibility(View.GONE);
        layoutContent.setVisibility(View.VISIBLE);
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

                        double actual, available, deficit;
                        if (daySettled) {
                            // The day has been settled: show what it was settled ON, which is the
                            // figure the counter signed off and the one the next day opens from.
                            // Working it out again from the opening plus the day's trading would
                            // add that trading to a total that already contains it.
                            actual    = settledActual;
                            available = settledAvailable;
                            deficit   = settledDeficit;
                        } else {
                            // Nothing settled yet, so the books say: what came in, plus the day.
                            actual    = cachedPreActual    + tc - td;
                            available = cachedPreAvailable + tc - td;
                            deficit   = cachedPreDeficit;
                        }
                        tvActualBalance.setText    ("₹ " + fmt.format(actual));
                        tvAvailableBalance.setText ("₹ " + fmt.format(available));
                        tvDeficit.setText          ("₹ " + fmt.format(deficit));
                        tvDeficit.setTextColor(deficit == 0 ? royal.emerald : royal.ruby);

                        boolean closed = "CLOSED".equalsIgnoreCase(dayStatus);
                        layoutStatus.setVisibility(View.VISIBLE);
                        tvAccountStatus.setText(closed ? "CLOSED" : "OPEN");
                        tvAccountStatus.setTextColor(closed ? royal.emerald : royal.amber);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> android.util.Log.w("TodaysAcct",
                        "operations load failed: " + message));
                }
            });
    }

    /**
     * The selected day's own row. Its {@code pre_*} half IS the Previous Day card - date and money
     * from the same half of the same row, which is the thing that was going wrong - and its
     * {@code todays_*} half is what the day was settled on, held for {@link #loadOperations()}.
     */
    private void bindSettledDay(JSONObject row) {
        daySettled       = true;
        dayStatus        = row.optString("accountStatus", "");
        settledActual    = row.optDouble("actualBalance",    0);
        settledAvailable = row.optDouble("availableBalance", 0);
        settledDeficit   = row.optDouble("deficit",          0);

        cachedPreActual    = row.optDouble("preActualBalance",    0);
        cachedPreAvailable = row.optDouble("preAvailableBalance", 0);
        cachedPreDeficit   = row.optDouble("preDeficit",          0);
        paintPreviousDay(row.optString("preDate", ""), row.optString("preNote", ""));

        String note = row.optString("todaysNote", "");
        tvTodaysNote.setText(note);
        tvTodaysNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * The day has not been settled, so what it carried in is what the day BEFORE it closed on -
     * that row's {@code todays_*} half, under that day's own date.
     */
    private void bindOpeningFromDayBefore(String theDayBefore, JSONObject row) {
        daySettled = false;
        dayStatus  = "";
        cachedPreActual    = row.optDouble("actualBalance",    0);
        cachedPreAvailable = row.optDouble("availableBalance", 0);
        cachedPreDeficit   = row.optDouble("deficit",          0);
        paintPreviousDay(theDayBefore, row.optString("todaysNote", ""));

        tvTodaysNote.setText("");
        tvTodaysNote.setVisibility(View.GONE);
    }

    /** Draws the Previous Day card from whatever the two binders put in the cached figures. */
    private void paintPreviousDay(String date, String note) {
        tvPreDate.setText(date == null || date.isEmpty() ? "—" : date);
        tvPreActual.setText("₹ " + fmt.format(cachedPreActual));
        tvPreAvailable.setText("₹ " + fmt.format(cachedPreAvailable));
        tvPreDeficit.setText("₹ " + fmt.format(cachedPreDeficit));
        tvPreDeficit.setTextColor(cachedPreDeficit == 0 ? royal.emerald : royal.ruby);
        tvPreNote.setText(note == null ? "" : note);
        tvPreNote.setVisibility(note == null || note.isEmpty() ? View.GONE : View.VISIBLE);
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
            tvName.setTextColor(detailType != null ? royal.wineInk : royal.ink);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            nameCell.addView(tvName);

            if (combo != null && !combo.trim().isEmpty()) {
                TextView tvCombo = new TextView(this);
                tvCombo.setText(combo.trim());
                tvCombo.setTextColor(royal.inkMute);
                tvCombo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                nameCell.addView(tvCombo);
            }

            // Width 0 with a weight: the name takes what is LEFT after the three number
            // columns have had theirs. Given WRAP_CONTENT it took as much as its long second
            // line wanted and the numbers went off the edge of the phone.
            TableRow.LayoutParams flexLp = new TableRow.LayoutParams(
                    0, TableRow.LayoutParams.WRAP_CONTENT, 1f);
            flexLp.column = 0;
            nameCell.setLayoutParams(flexLp);
            row.addView(nameCell);

            row.addView(makeCell(fmtNum(count),
                    royal.inkSoft, dp(36), Gravity.END));
            row.addView(makeCell("₹" + fmtShort(debit),
                    royal.money(debit, true), dp(88), Gravity.END));
            TextView creditCell = makeCell("₹" + fmtShort(credit),
                    royal.money(credit, false), dp(88), Gravity.END);
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(gravity);
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        TableRow.LayoutParams lp = new TableRow.LayoutParams(
                minWidthPx, TableRow.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * Whole rupees for the grid. The paise on a figure like 2,69,000.00 cost four characters
     * that a phone's width does not have, and this shop's money never has any: the TOTAL and
     * the balance cards below still carry them, where there is room.
     */
    private String fmtShort(double v) {
        return wholeRupees.format(Math.round(v));
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val,
                getResources().getDisplayMetrics());
    }
}
