package com.pawnbroking.app;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.StatusPill;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Employee Activity: what each person did at the counter, in the order they did it.
 *
 * <p>The owner is usually not in the shop when they want to know this, which is the whole point of its being
 * on the phone. So it opens on today, newest first, and the narrowing - one person, one screen, one kind of
 * line, a day, or a word that appeared anywhere - is one tap away above the list.
 *
 * <p>Tapping a line that belonged to a bill asks for that bill instead, which is the question that follows
 * almost every interesting line: who else touched it, and what did they do to it.
 */
public class EmployeeActivityActivity extends AppCompatActivity {

    private static final String ANYBODY = "Everybody";
    private static final String ANY_SCREEN = "Every screen";
    private static final String ANYTHING = "Everything";
    private static final String[] ACTIONS = {
        ANYTHING, "SIGNED IN", "SIGNED OUT", "OPENED", "CLOSED", "TYPED", "PRESSED", "SAVED", "REFUSED",
    };

    private final SimpleDateFormat api = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat shown = new SimpleDateFormat("dd MMM", Locale.getDefault());

    private ProgressBar progressBar;
    private TextView tvEmpty, tvCount, tvFrom, tvTo;
    private EditText etSearch;
    private Spinner spWho, spScreen, spAction;
    private RecyclerView recyclerView;

    private final List<JSONObject> items = new ArrayList<>();
    private LineAdapter adapter;

    private String companyId, companyName;
    private String from, to;
    /** Set when a line has been tapped, so the list is about one bill until the search box is cleared. */
    private String bill;
    private boolean ready;          // true once the drop-downs are filled, so filling them loads nothing

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_employee_activity);

        companyId = getIntent().getStringExtra("companyId");
        companyName = getIntent().getStringExtra("companyName");

        Calendar cal = Calendar.getInstance();
        from = api.format(cal.getTime());
        to = from;

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Employee Activity");
            getSupportActionBar().setSubtitle(companyName);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.menu_detail);
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) {
                load();
                return true;
            }
            return false;
        });

        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvCount = findViewById(R.id.tvCount);
        tvFrom = findViewById(R.id.tvFrom);
        tvTo = findViewById(R.id.tvTo);
        etSearch = findViewById(R.id.etSearch);
        spWho = findViewById(R.id.spWho);
        spScreen = findViewById(R.id.spScreen);
        spAction = findViewById(R.id.spAction);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LineAdapter();
        recyclerView.setAdapter(adapter);

        fill(spWho, new String[] {ANYBODY});
        fill(spScreen, new String[] {ANY_SCREEN});
        fill(spAction, ACTIONS);
        showDates();

        tvFrom.setOnClickListener(v -> pick(true));
        tvTo.setOnClickListener(v -> pick(false));
        etSearch.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                bill = null;                     // a fresh search is no longer about the bill that was tapped
                load();
                return true;
            }
            return false;
        });
        AdapterView.OnItemSelectedListener reload = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (ready) load();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        spWho.setOnItemSelectedListener(reload);
        spScreen.setOnItemSelectedListener(reload);
        spAction.setOnItemSelectedListener(reload);

        choices();
        load();
    }

    /** The two drop-downs, filled from what is actually in the log so neither offers an empty answer. */
    private void choices() {
        ApiService.getActivityChoices(companyId, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    ready = false;
                    fill(spWho, with(ANYBODY, data.optJSONArray("people")));
                    fill(spScreen, with(ANY_SCREEN, data.optJSONArray("screens")));
                    ready = true;
                });
            }

            @Override public void onError(String message) {
                ready = true;                    // the lists stay as they are; the list itself still loads
            }
        });
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        ApiService.getActivity(companyId, chosen(spWho, ANYBODY), chosen(spScreen, ANY_SCREEN),
                chosen(spAction, ANYTHING), bill, from, to, etSearch.getText().toString(), 400,
                new ApiService.Callback<JSONArray>() {
                    @Override public void onSuccess(JSONArray data) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            items.clear();
                            for (int i = 0; i < data.length(); i++) {
                                JSONObject row = data.optJSONObject(i);
                                if (row == null) {
                                    continue;
                                }
                                JSONObject body = row.optJSONObject("payload");
                                items.add(body != null ? body : row);
                            }
                            adapter.notifyDataSetChanged();
                            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            tvCount.setText(said());
                        });
                    }

                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            tvEmpty.setVisibility(View.VISIBLE);
                            Toast.makeText(EmployeeActivityActivity.this,
                                    message == null ? "Could not read the activity" : message,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /** What the list is of, in words, under the boxes that made it. */
    private String said() {
        StringBuilder s = new StringBuilder();
        s.append(items.size()).append(items.size() == 1 ? " line" : " lines");
        if (bill != null) {
            s.append("  ·  bill ").append(bill).append("  (search to clear)");
        }
        if (items.size() >= 400) {
            s.append("  ·  the newest 400");
        }
        return s.toString();
    }

    private void pick(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        try {
            cal.setTime(api.parse(isFrom ? from : to));
        } catch (Exception ignored) {
            // today will do
        }
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, d);
            if (isFrom) {
                from = api.format(picked.getTime());
                if (from.compareTo(to) > 0) {
                    to = from;                   // a day that ends before it starts has nothing in it
                }
            } else {
                to = api.format(picked.getTime());
                if (to.compareTo(from) < 0) {
                    from = to;
                }
            }
            showDates();
            load();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showDates() {
        tvFrom.setText(pretty(from));
        tvTo.setText(pretty(to));
    }

    private String pretty(String yyyymmdd) {
        try {
            return shown.format(api.parse(yyyymmdd));
        } catch (Exception e) {
            return yyyymmdd;
        }
    }

    private void fill(Spinner sp, String[] values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
    }

    private String[] with(String first, JSONArray rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        if (rest != null) {
            for (int i = 0; i < rest.length(); i++) {
                String v = rest.optString(i, null);
                if (v != null && !v.isEmpty() && !"null".equals(v)) {
                    out.add(v);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /** The chosen value, or null when it is the "any" one. */
    private String chosen(Spinner sp, String any) {
        Object v = sp.getSelectedItem();
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return any.equals(s) ? null : s;
    }

    // ------------------------------------------------------------------ the list
    private final class LineAdapter extends RecyclerView.Adapter<LineAdapter.Holder> {

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_activity_line, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            JSONObject r = items.get(position);
            String name = text(r, "emp_name");
            String user = text(r, "user_id");
            String emp = text(r, "emp_id");
            String billNumber = text(r, "bill_number");
            String detail = text(r, "detail");

            h.who.setText(name.isEmpty() ? user : name);
            h.when.setText(clock(text(r, "happened_at")));
            h.action.setText(text(r, "action"));
            StatusPill.paintAction(h.action, text(r, "action"));
            h.screen.setText(text(r, "screen"));
            h.bill.setText(billNumber);
            h.bill.setVisibility(billNumber.isEmpty() ? View.GONE : View.VISIBLE);
            h.detail.setText(detail);
            h.detail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

            StringBuilder ids = new StringBuilder();
            if (!user.isEmpty()) {
                ids.append("user ").append(user);
            }
            if (!emp.isEmpty()) {
                ids.append(ids.length() == 0 ? "" : "   ").append("emp ").append(emp);
            }
            h.ids.setText(ids);
            h.ids.setVisibility(ids.length() == 0 ? View.GONE : View.VISIBLE);

            h.itemView.setOnClickListener(v -> {
                if (billNumber.isEmpty()) {
                    return;
                }
                bill = billNumber;
                etSearch.setText("");
                ready = false;
                spWho.setSelection(0);
                spScreen.setSelection(0);
                spAction.setSelection(0);
                ready = true;
                load();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView who, when, action, screen, bill, detail, ids;

            Holder(View v) {
                super(v);
                who = v.findViewById(R.id.tvWho);
                when = v.findViewById(R.id.tvWhen);
                action = v.findViewById(R.id.tvAction);
                screen = v.findViewById(R.id.tvScreen);
                bill = v.findViewById(R.id.tvBill);
                detail = v.findViewById(R.id.tvDetail);
                ids = v.findViewById(R.id.tvIds);
            }
        }
    }

    private static String text(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || "null".equals(v) ? "" : v;
    }

    /**
     * The time of day, which is what the owner is reading a line for. The date is already in the two date
     * boxes above, so it is only shown when the list spans more than one day.
     */
    private String clock(String stamp) {
        if (stamp == null || stamp.length() < 16) {
            return stamp == null ? "" : stamp;
        }
        String time = stamp.substring(11, 16);
        return from.equals(to) ? time : stamp.substring(8, 10) + " " + time;
    }
}
