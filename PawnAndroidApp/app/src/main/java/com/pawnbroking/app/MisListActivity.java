package com.pawnbroking.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.GridRowView;
import com.pawnbroking.app.util.Royal;
import com.pawnbroking.app.util.TablePrint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The list behind one MIS figure: the bills, the bill-by-bill profit, the
 * expenses. Group by, sort by and print as the desktop's MISDrillDown does.
 * The subtotal rows a grouping adds are made up here, so nothing opens them.
 */
public class MisListActivity extends AppCompatActivity {

    public static final String EXTRA_KIND = "kind", EXTRA_MONTH = "month", EXTRA_MATERIAL = "material";
    /** Instead of month and material: many rows of the MIS, each "MONTH|MATERIAL", material blank for both. */
    public static final String EXTRA_ROWS = "rows";
    public static final String KIND_GROSS = "GROSS_PROFIT", KIND_NET = "NET_PROFIT";

    private static final String COL_FROM = "From", COL_NAME = "Name / details";

    private static final String NO_GROUP = "None", NO_SORT = "As loaded";
    private static final String ASC = "Ascending", DESC = "Descending";
    private static final String DIFFERENCE = "Difference";

    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private Royal royal;
    private String companyId, kind, month, material;

    private ProgressBar progress;
    private LinearLayout tableHead, tableRows;
    private LinearLayout tablePicks;
    private TextView tvTitle, tvEmpty, tvFoot;
    private Spinner spGroup, spSort, spOrder;
    private Button btnPrint;
    private WebView printing;

    private String title = "";
    private final List<String> headers = new ArrayList<>();
    private final List<Boolean> numeric = new ArrayList<>();
    private final List<Boolean> hidden = new ArrayList<>();
    private final List<Object[]> base = new ArrayList<>();
    private final List<Integer> shownColumns = new ArrayList<>();
    private int[] widths;
    private boolean[] right;
    private String note = "";

    private boolean merged;
    private int monthsAsked = 1;

    /** A line of a Gross or Net list, and the row of its own list it came from. */
    private static final class Source {
        final String kind; final List<String> headers; final List<Boolean> hidden; final Object[] row;
        Source(String k, List<String> h, List<Boolean> x, Object[] r) { kind = k; headers = h; hidden = x; row = r; }
    }
    private final Map<Object[], Source> sources = new IdentityHashMap<>();

    /** What is on screen right now, in order. A subtotal has made == true. */
    private static final class Line {
        final Object[] cells; final boolean made;
        Line(Object[] c, boolean m) { cells = c; made = m; }
    }
    private final List<Line> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mis_list);
        fmt.setMaximumFractionDigits(0);
        royal = new Royal(this);

        companyId = getIntent().getStringExtra("companyId");
        kind      = getIntent().getStringExtra(EXTRA_KIND);
        month     = getIntent().getStringExtra(EXTRA_MONTH);
        material  = getIntent().getStringExtra(EXTRA_MATERIAL);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("MIS Report");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        progress   = findViewById(R.id.progressBar);
        tableHead  = findViewById(R.id.tableHead);
        tableRows  = findViewById(R.id.tableRows);
        tablePicks = findViewById(R.id.layoutPicks);
        tvTitle    = findViewById(R.id.tvListTitle);
        tvEmpty    = findViewById(R.id.tvEmpty);
        tvFoot     = findViewById(R.id.tvFoot);
        spGroup    = findViewById(R.id.spGroup);
        spSort     = findViewById(R.id.spSort);
        spOrder    = findViewById(R.id.spOrder);
        btnPrint   = findViewById(R.id.btnPrint);
        btnPrint.setOnClickListener(v -> print());

        load();
    }

    /**
     * One list, or several read one after another and shown as one: the same list over many
     * months, or the lists a Gross or Net figure is made of. Gross and Net have no list of
     * their own on the desktop either; theirs is simply the lists they are worked out from,
     * so the rows here add up to the figure that was tapped.
     */
    private void load() {
        progress.setVisibility(View.VISIBLE);
        btnPrint.setVisibility(View.GONE);
        List<String[]> targets = new ArrayList<>();
        ArrayList<String> many = getIntent().getStringArrayListExtra(EXTRA_ROWS);
        if (many != null) {
            for (String t : many) {
                String[] p = t.split("\\|", -1);
                targets.add(new String[] {p[0], p.length < 2 || p[1].isEmpty() ? null : p[1]});
            }
        } else {
            targets.add(new String[] {month, material});
        }
        String[] kinds = KIND_GROSS.equals(kind) ? new String[] {"COMPANY_PROFIT", "REPLEDGE_INTEREST"}
                : KIND_NET.equals(kind) ? new String[] {"COMPANY_PROFIT", "REPLEDGE_INTEREST", "EXPENSES"}
                : new String[] {kind};
        merged = kinds.length > 1;
        monthsAsked = targets.size();
        List<String[]> jobs = new ArrayList<>();
        for (String[] t : targets) for (String k : kinds) jobs.add(new String[] {k, t[0], t[1]});
        fetch(jobs, 0, new ArrayList<>());
    }

    private void fetch(List<String[]> jobs, int at, List<JSONObject> got) {
        if (isFinishing() || isDestroyed()) return;
        if (at == jobs.size()) {
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.GONE);
                present(jobs, got);
            });
            return;
        }
        if (jobs.size() > 3) {
            runOnUiThread(() -> {
                tvEmpty.setText("Reading " + (at + 1) + " of " + jobs.size() + "…");
                tvEmpty.setVisibility(View.VISIBLE);
            });
        }
        String[] job = jobs.get(at);
        ApiService.getMisList(companyId, job[0], job[1], job[2], new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                got.add(data);
                fetch(jobs, at + 1, got);
            }
            @Override public void onError(String msg) {
                // All or nothing: part of a list would not add up to the figure tapped.
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    tvEmpty.setText("Could not read the list: " + msg);
                    tvEmpty.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void present(List<String[]> jobs, List<JSONObject> got) {
        if (merged) {
            mergeSources(jobs, got);
        } else {
            for (JSONObject d : got) read(d, headers, numeric, hidden, base);
            title = got.size() == 1 ? got.get(0).optString("title", "") : nameOf(kind) + wherePart();
            note = got.size() == 1 ? got.get(0).optString("note", "") : "";
        }
        bind();
    }

    private String wherePart() {
        if (monthsAsked > 1) return ", " + monthsAsked + " rows of the MIS";
        return "  -  " + month + (material != null ? "  -  " + material : "");
    }

    private static String nameOf(String kind) {
        switch (kind) {
            case KIND_GROSS:          return "Gross profit";
            case KIND_NET:            return "Net profit";
            case "COMPANY_OPENED":    return "Company bills opened";
            case "COMPANY_REDEEMED":  return "Company bills redeemed";
            case "REPLEDGE_OPENED":   return "Repledge bills opened";
            case "REPLEDGE_REDEEMED": return "Repledge bills redeemed";
            case "COMPANY_PROFIT":    return "Company profit";
            case "REPLEDGE_INTEREST": return "Repledge interest spent";
            case "EXPENSES":          return "Expenses";
            case "ASSETS":            return "Assets";
            case "LIABILITIES":       return "Liabilities";
            default:                  return kind;
        }
    }

    /** Adopts the reply's columns the first time, and adds its rows. */
    private static void read(JSONObject d, List<String> hs, List<Boolean> num, List<Boolean> hid, List<Object[]> rows) {
        JSONArray h = d.optJSONArray("headers"), n = d.optJSONArray("numeric"), x = d.optJSONArray("hidden");
        if (hs.isEmpty() && h != null) {
            for (int i = 0; i < h.length(); i++) {
                hs.add(h.optString(i));
                num.add(n != null && n.optBoolean(i));
                hid.add(x != null && x.optBoolean(i));
            }
        }
        JSONArray rs = d.optJSONArray("rows");
        if (rs == null) return;
        for (int r = 0; r < rs.length(); r++) {
            JSONArray a = rs.optJSONArray(r);
            if (a == null) continue;
            Object[] row = new Object[hs.size()];
            for (int c = 0; c < row.length; c++) {
                row[c] = num.get(c) ? (Object) a.optDouble(c, 0) : (a.isNull(c) ? "" : a.optString(c, ""));
            }
            rows.add(row);
        }
    }

    /** Earned less spent, line by line. Each line remembers where it came from, so a tap opens the same thing it opens in its own list. */
    private void mergeSources(List<String[]> jobs, List<JSONObject> got) {
        title = nameOf(kind) + wherePart();
        Collections.addAll(headers, "Date", COL_FROM, "Event / kind", "Bill / Id", COL_NAME, "Earned", "Spent", "Profit");
        for (int i = 0; i < headers.size(); i++) { numeric.add(i >= 5); hidden.add(false); }
        for (int j = 0; j < got.size(); j++) {
            String from = jobs.get(j)[0];
            List<String> hs = new ArrayList<>();
            List<Boolean> num = new ArrayList<>(), hid = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            read(got.get(j), hs, num, hid, rows);
            int figure = got.get(j).optInt("figureColumn", -1);
            if (figure < 0) continue;
            boolean earned = "COMPANY_PROFIT".equals(from);
            boolean expense = "EXPENSES".equals(from);
            for (Object[] r : rows) {
                double v = (Double) r[figure];
                String name = earned ? cell(hs, r, "Customer")
                        : expense ? joined(cell(hs, r, "Type / person"), cell(hs, r, "Details"))
                        : cell(hs, r, "Party");
                Object[] line = {
                        cell(hs, r, "Date"),
                        earned ? "Company profit" : expense ? "Expense" : "Repledge interest",
                        cell(hs, r, expense ? "Kind" : "Event"),
                        cell(hs, r, earned ? "Bill" : expense ? "Id" : "Repledge bill"),
                        name,
                        earned ? v : 0d,
                        earned ? 0d : v,
                        earned ? v : -v };
                sources.put(line, new Source(from, hs, hid, r));
                base.add(line);
            }
        }
    }

    private String cell(List<String> hs, Object[] row, String header) {
        int i = hs.indexOf(header);
        return i < 0 || row[i] == null ? "" : text(row[i]);
    }

    private static String joined(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " - " + b;
    }

    private void bind() {
        tvTitle.setText(title);
        if (base.isEmpty()) {
            tvEmpty.setText(note.isEmpty() ? "Nothing in this month." : note);
            tvEmpty.setVisibility(View.VISIBLE);
            btnPrint.setVisibility(View.GONE);
            tablePicks.setVisibility(View.GONE);
            tvFoot.setVisibility(View.GONE);
            return;
        }
        btnPrint.setVisibility(View.VISIBLE);

        List<String> names = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            if (hidden.get(i)) continue;
            if ("Material".equals(headers.get(i)) && material != null) continue;   // already in the title
            shownColumns.add(i);
            names.add(headers.get(i));
        }
        widths = new int[shownColumns.size()];
        right = new boolean[shownColumns.size()];
        for (int k = 0; k < widths.length; k++) {
            String h = headers.get(shownColumns.get(k));
            boolean number = numeric.get(shownColumns.get(k));
            right[k] = number;
            widths[k] = number ? 92
                    : h.equals("Customer") || h.equals("Details") || h.equals("Items") || h.equals("Party") || h.equals(COL_NAME) ? 190
                    : h.equals("Kind") || h.equals("Type / person") || h.equals(COL_FROM) || h.equals("Event / kind") ? 130
                    : h.equals("Material") || h.equals("Event") || h.equals("Status") ? 86 : 88;
        }

        List<String> groupNames = new ArrayList<>(); groupNames.add(NO_GROUP); groupNames.addAll(names);
        List<String> sortNames  = new ArrayList<>(); sortNames.add(NO_SORT);   sortNames.addAll(names);
        spinner(spGroup, groupNames);
        spinner(spSort, sortNames);
        List<String> orders = new ArrayList<>(); orders.add(ASC); orders.add(DESC);
        spinner(spOrder, orders);

        tableHead.removeAllViews();
        String[] heads = new String[widths.length];
        int[] colors = new int[widths.length];
        boolean[] bold = new boolean[widths.length];
        for (int k = 0; k < heads.length; k++) {
            heads[k] = twoLines(headers.get(shownColumns.get(k)));
            colors[k] = royal.onWineTitle;
            bold[k] = true;
        }
        GridRowView head = new GridRowView(this).heightDp(44).set(heads, widths, colors, right, bold);
        head.setBackgroundResource(R.drawable.bg_table_head);
        tableHead.addView(head);

        StringBuilder foot = new StringBuilder(base.size() + (base.size() == 1 ? " entry" : " entries"));
        for (int i = 0; i < headers.size(); i++) {
            if (!numeric.get(i)) continue;
            double s = 0;
            for (Object[] r : base) s += (Double) r[i];
            foot.append("   ·   ").append(headers.get(i)).append(": ").append(money(s));
        }
        foot.append("\nTap a row to open its ").append(merged ? "bill or entry" : billList(kind) ? "bill" : "entry");
        tvFoot.setText(foot.toString());

        // Grouped by where each line came from: the subtotals are then the figures it is worked out from.
        if (merged) spGroup.setSelection(groupNames.indexOf(COL_FROM));
        apply();
    }

    private static boolean billList(String kind) {
        return !"EXPENSES".equals(kind) && !"ASSETS".equals(kind) && !"LIABILITIES".equals(kind);
    }

    private void spinner(Spinner sp, List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, R.layout.item_spinner_compact, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { apply(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    /** The picks to rows. Groups sit together with a subtotal under each, as the desktop's. */
    private void apply() {
        if (widths == null) return;
        int gi = columnOf(String.valueOf(spGroup.getSelectedItem()));
        int si = columnOf(String.valueOf(spSort.getSelectedItem()));
        boolean down = DESC.equals(String.valueOf(spOrder.getSelectedItem()));

        List<Object[]> rows = new ArrayList<>(base);
        Comparator<Object[]> bySort = si < 0 ? null : by(si);
        if (bySort != null && down) bySort = Collections.reverseOrder(bySort);

        shown.clear();
        if (gi < 0) {
            if (bySort != null) Collections.sort(rows, bySort);
            for (Object[] r : rows) shown.add(new Line(r, false));
        } else {
            Comparator<Object[]> byGroup = by(gi);
            if (down) byGroup = Collections.reverseOrder(byGroup);
            Collections.sort(rows, bySort == null ? byGroup : byGroup.thenComparing(bySort));
            int i = 0;
            while (i < rows.size()) {
                String value = text(rows.get(i)[gi]);
                int end = i;
                while (end < rows.size() && Objects.equals(text(rows.get(end)[gi]), value)) end++;
                for (int k = i; k < end; k++) shown.add(new Line(rows.get(k), false));
                Object[] sub = new Object[headers.size()];
                java.util.Arrays.fill(sub, "");
                int n = end - i;
                sub[gi] = (value.isEmpty() ? "(blank)" : value) + "  -  " + n + (n == 1 ? " entry" : " entries");
                for (int c = 0; c < headers.size(); c++) {
                    if (!numeric.get(c)) continue;
                    double t = 0;
                    for (int k = i; k < end; k++) t += (Double) rows.get(k)[c];
                    sub[c] = t;
                }
                shown.add(new Line(sub, true));
                i = end;
            }
        }
        draw();
    }

    private int columnOf(String name) {
        for (int c : shownColumns) if (headers.get(c).equals(name)) return c;
        return -1;
    }

    private void draw() {
        tableRows.removeAllViews();
        int event = headers.indexOf("Event");
        for (Line l : shown) {
            GridRowView row = new GridRowView(this);
            String[] cells = new String[widths.length];
            int[] colors = new int[widths.length];
            boolean[] bold = new boolean[widths.length];
            for (int k = 0; k < cells.length; k++) {
                int c = shownColumns.get(k);
                cells[k] = text(l.cells[c]);
                colors[k] = royal.ink;
                bold[k] = l.made;
                if (numeric.get(c) && l.cells[c] instanceof Double) {
                    double v = (Double) l.cells[c];
                    if (DIFFERENCE.equals(headers.get(c))) {
                        colors[k] = v != 0 ? royal.amber : royal.inkFaint;   // not as it should have been: stands out
                        bold[k] = bold[k] || v != 0;
                    } else if (v < 0) {
                        colors[k] = royal.ruby;
                    } else if (merged && v == 0) {
                        colors[k] = royal.inkFaint;                          // earned or spent, not both
                    }
                }
            }
            row.set(cells, widths, colors, right, bold);
            if (l.made) {
                row.setBackgroundResource(R.drawable.bg_table_total);
            } else if (event >= 0) {
                row.setBackgroundResource("Opened".equals(l.cells[event])
                        ? R.drawable.bg_table_row_opened : R.drawable.bg_table_row_closed);
            } else {
                row.setBackgroundResource(R.drawable.bg_table_row);
            }
            if (!l.made) {
                row.setOnCell(new GridRowView.OnCell() {
                    @Override public void onTap(int column) { open(l.cells); }
                    @Override public void onLong(int column) { open(l.cells); }
                });
            }
            tableRows.addView(row);
        }
    }

    /** A row: its bill in the bill screen. An entry has no screen on the phone, so its details in full. */
    private void open(Object[] row) {
        Source from = sources.get(row);
        if (from != null) open(from.kind, from.headers, from.hidden, from.row);
        else open(kind, headers, hidden, row);
    }

    private void open(String rowKind, List<String> hs, List<Boolean> hid, Object[] row) {
        if (billList(rowKind)) {
            boolean repledge = rowKind.startsWith("REPLEDGE");
            String bill = cell(hs, row, repledge ? "Company bill" : "Bill");
            if (bill.isEmpty()) { Toast.makeText(this, "This row has no company bill", Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent(this, BillingActivity.class);
            i.putExtra("companyId", companyId);
            i.putExtra("billNumber", bill);
            String mat = cell(hs, row, "Material");
            i.putExtra("materialType", repledge ? "GOLD" : !mat.isEmpty() ? mat : material);
            startActivity(i);
            return;
        }
        StringBuilder s = new StringBuilder();
        for (int c = 0; c < hs.size(); c++) {
            if (hid.get(c)) continue;
            String v = text(row[c]);
            if (!v.isEmpty()) s.append(hs.get(c)).append(":  ").append(v).append("\n");
        }
        String kindWord = cell(hs, row, "Kind");
        new AlertDialog.Builder(this).setTitle(kindWord.isEmpty() ? title : kindWord)
                .setMessage(s.toString().trim()).setPositiveButton("Close", null).show();
    }

    private void print() {
        if (shown.isEmpty()) return;
        String[] heads = new String[widths.length];
        for (int k = 0; k < heads.length; k++) heads[k] = headers.get(shownColumns.get(k));
        TablePrint p = new TablePrint(heads, right);
        for (Line l : shown) {
            String[] cells = new String[widths.length];
            for (int k = 0; k < cells.length; k++) cells[k] = text(l.cells[shownColumns.get(k)]);
            p.row(l.made ? TablePrint.Kind.SUBTOTAL : TablePrint.Kind.ROW, cells);
        }
        String[] totals = new String[widths.length];
        int words = 0;
        for (int k = 0; k < totals.length; k++) {
            int c = shownColumns.get(k);
            if (numeric.get(c)) {
                double s = 0;
                for (Object[] r : base) s += (Double) r[c];
                totals[k] = money(s);
            } else {
                totals[k] = words == 0 ? "TOTAL" : words == 1 ? base.size() + (base.size() == 1 ? " entry" : " entries") : "";
                words++;
            }
        }
        p.row(TablePrint.Kind.TOTAL, totals);
        String g = String.valueOf(spGroup.getSelectedItem()), s = String.valueOf(spSort.getSelectedItem());
        String order = String.valueOf(spOrder.getSelectedItem()).toLowerCase(Locale.ROOT);
        String sub = base.size() + (base.size() == 1 ? " entry" : " entries")
                + (NO_GROUP.equals(g) ? "" : "  ·  grouped by " + g.toLowerCase(Locale.ROOT) + ", " + order)
                + (NO_SORT.equals(s) ? "" : "  ·  sorted by " + s.toLowerCase(Locale.ROOT) + ", " + order);
        printing = p.show(this, "MIS  ·  " + title.toUpperCase(Locale.ROOT), sub, "MIS Report - " + title);
    }

    private String money(double v) { return v < 0 ? "-" + fmt.format(-v) : fmt.format(v); }

    private String text(Object v) { return v instanceof Double ? money((Double) v) : v == null ? "" : v.toString(); }

    /** Numbers by value, anything else as text, dates turned round so they sort as dates. */
    private Comparator<Object[]> by(int col) {
        if (numeric.get(col)) {
            return (a, b) -> Double.compare(a[col] instanceof Double ? (Double) a[col] : 0, b[col] instanceof Double ? (Double) b[col] : 0);
        }
        return (a, b) -> sortable(text(a[col])).compareToIgnoreCase(sortable(text(b[col])));
    }

    private static String sortable(String v) {
        if (v != null && v.matches("[0-9]{2}-[0-9]{2}-[0-9]{4}")) {
            return v.substring(6) + "-" + v.substring(3, 5) + "-" + v.substring(0, 2);
        }
        return v == null ? "" : v;
    }

    private static String twoLines(String h) {
        if (h.length() <= 10 || !h.contains(" ")) return h;
        int mid = h.length() / 2, at = -1;
        for (int d = 0; d < h.length(); d++) {
            if (mid - d > 0 && h.charAt(mid - d) == ' ') { at = mid - d; break; }
            if (mid + d < h.length() && h.charAt(mid + d) == ' ') { at = mid + d; break; }
        }
        return at < 0 ? h : h.substring(0, at) + "\n" + h.substring(at + 1);
    }
}
