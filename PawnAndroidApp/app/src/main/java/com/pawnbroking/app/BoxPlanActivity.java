package com.pawnbroking.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.pawnbroking.app.services.ApiService;
import com.pawnbroking.app.util.StatusPill;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The shop's cloud box plan, and renewing it (1.6).
 *
 * <p>From 06-11-2026 a paid cloud box plan ends by itself: a week of grace,
 * then the box goes back to the free 5 GB and, for a shop holding more than
 * that, every photo, report and backup upload stops. The shop pays to renew.
 *
 * <p>The flow is the box's own: pick a plan and Monthly / Yearly, the cloud
 * starts a payment and hands back a UPI link, the payment app pays Magizhchi,
 * the owner types the 12-digit UTR, and Magizhchi approves it. The phone never
 * holds the box token: every call goes through the pawn cloud, which uses the
 * shop's own, so the payment lands on the account the shop's files live in.
 *
 * <p>Anyone signed in to the shop sees this screen; only an owner can pay.
 */
public class BoxPlanActivity extends AppCompatActivity {

    private static final String PREFS = "box_plan";
    /** The payment last sent to a UPI app, so its UTR is asked for when the
     *  owner comes back - even if Android dropped this screen meanwhile. */
    private static final String K_LAUNCHED = "launchedRef";

    private ProgressBar progressBar, barUsed;
    private SwipeRefreshLayout swipe;
    private View content, boxPending, rowPendingButtons, sectionRenew, sectionPayments;
    private TextView tvError, tvPlanName, tvPlanState, tvPlanDates, tvUsed, tvNote, tvPending,
                     tvOwnerOnly, chipYearly, chipMonthly;
    private LinearLayout listPlans, listPayments;
    private Button btnPay;

    private JSONObject plan;            // the cloud's last answer
    private String cycle = "yearly";
    private String chosen;              // planCode picked
    private boolean busy;
    private JSONObject lastPayment;     // the one sent to the UPI app
    private AlertDialog utrDialog;
    private ActivityResultLauncher<Intent> upiLauncher;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_box_plan);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Cloud Box Plan");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        progressBar       = findViewById(R.id.progressBar);
        swipe             = findViewById(R.id.swipeRefresh);
        content           = findViewById(R.id.content);
        tvError           = findViewById(R.id.tvError);
        tvPlanName        = findViewById(R.id.tvPlanName);
        tvPlanState       = findViewById(R.id.tvPlanState);
        tvPlanDates       = findViewById(R.id.tvPlanDates);
        barUsed           = findViewById(R.id.barUsed);
        tvUsed            = findViewById(R.id.tvUsed);
        tvNote            = findViewById(R.id.tvNote);
        boxPending        = findViewById(R.id.boxPending);
        tvPending         = findViewById(R.id.tvPending);
        rowPendingButtons = findViewById(R.id.rowPendingButtons);
        sectionRenew      = findViewById(R.id.sectionRenew);
        sectionPayments   = findViewById(R.id.sectionPayments);
        chipYearly        = findViewById(R.id.chipYearly);
        chipMonthly       = findViewById(R.id.chipMonthly);
        listPlans         = findViewById(R.id.listPlans);
        listPayments      = findViewById(R.id.listPayments);
        btnPay            = findViewById(R.id.btnPay);
        tvOwnerOnly       = findViewById(R.id.tvOwnerOnly);

        swipe.setOnRefreshListener(this::load);
        chipYearly.setOnClickListener(v -> { cycle = "yearly"; drawChoices(); });
        chipMonthly.setOnClickListener(v -> { cycle = "monthly"; drawChoices(); });
        btnPay.setOnClickListener(v -> startPayment());
        findViewById(R.id.btnPayAgain).setOnClickListener(v -> payAgain());
        findViewById(R.id.btnEnterUtr).setOnClickListener(v -> {
            JSONObject p = unpaid();
            if (p != null) askUtr(p, null, null);
        });

        upiLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::afterUpi);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();          // a payment may have been approved while we were away
    }

    // ── Load and draw ──────────────────────────────────────────────────────────

    private void load() {
        if (plan == null) progressBar.setVisibility(View.VISIBLE);
        ApiService.boxPlan(new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject data) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    swipe.setRefreshing(false);
                    plan = data;
                    HomeActivity.boxPlanSeen(ApiService.getCurrentShopId(BoxPlanActivity.this), data);
                    draw();
                    maybeAskForLaunchedUtr();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    progressBar.setVisibility(View.GONE);
                    swipe.setRefreshing(false);
                    if (plan == null) {
                        tvError.setText(msg);
                        tvError.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(BoxPlanActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void draw() {
        tvError.setVisibility(View.GONE);
        if (!plan.optBoolean("available", false)) {
            content.setVisibility(View.GONE);
            tvError.setText(plan.optString("headline", "This shop's cloud box is not set up yet."));
            tvError.setVisibility(View.VISIBLE);
            return;
        }
        content.setVisibility(View.VISIBLE);

        String state = str(plan, "planState");
        String attention = str(plan, "attention");
        String label = str(plan, "planLabel");
        tvPlanName.setText(label == null ? "Cloud box" : label);

        // The pill and the dates line say where the plan stands, in a word and a day.
        String pill, tone, dates;
        if ("ACTIVE".equals(state)) {
            boolean soon = "ENDING".equals(attention);
            pill = soon ? "ENDS SOON" : "RUNNING"; tone = soon ? "warn" : "good";
            dates = "Paid until " + str(plan, "planEnds", "?");
        } else if ("GRACE".equals(state)) {
            pill = "ENDED"; tone = "warn";
            dates = "Ended on " + str(plan, "planEnds", "?") + "  ·  space kept until " + str(plan, "graceEnds", "?");
        } else if ("EXPIRED".equals(state)) {
            boolean paused = "PAUSED".equals(attention);
            pill = paused ? "UPLOADS STOPPED" : "ENDED"; tone = "bad";
            dates = "The paid plan has ended; the box is on the free plan.";
        } else if ("FREE".equals(state)) {
            pill = "FREE"; tone = "quiet"; dates = "The free plan.";
        } else {
            pill = null; tone = null; dates = "";
        }
        if (pill == null) {
            tvPlanState.setVisibility(View.GONE);
        } else {
            tvPlanState.setText(pill);
            StatusPill.paintTone(tvPlanState, tone);
            tvPlanState.setVisibility(View.VISIBLE);
        }
        tvPlanDates.setText(dates);
        tvPlanDates.setVisibility(dates.isEmpty() ? View.GONE : View.VISIBLE);

        long used = plan.optLong("usedBytes", 0), limit = plan.optLong("limitBytes", 0);
        int permille = limit <= 0 ? 0 : (int) Math.min(1000, used * 1000 / limit);
        barUsed.setProgress(permille);
        barUsed.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                permille >= 900 ? R.color.ruby : R.color.wine)));
        long pct = limit <= 0 ? 0 : Math.round(100.0 * used / limit);
        // "1900%" is what an ended plan's 95 GB on the free 5 GB works out to:
        // true, but it reads like a fault. Over the limit, say it in words.
        tvUsed.setText(used > limit && limit > 0
                ? str(plan, "used", "?") + " stored: more than the " + str(plan, "limit", "?") + " this plan allows"
                : str(plan, "used", "?") + " of " + str(plan, "limit", "?") + " used (" + pct + "%)");

        String note = str(plan, "note");
        if (note == null && attention != null) note = str(plan, "headline");
        if (note != null) {
            boolean bad = "PAUSED".equals(attention) || "ENDED".equals(attention) || "FULL".equals(attention);
            tvNote.setText(note);
            tvNote.setBackgroundResource(bad ? R.drawable.bg_result_bad : R.drawable.bg_result_warn);
            tvNote.setTextColor(ContextCompat.getColor(this, bad ? R.color.ruby_ink : R.color.ink));
            int p = dp(12);
            tvNote.setPadding(p, p, p, p);
            tvNote.setVisibility(View.VISIBLE);
        } else {
            tvNote.setVisibility(View.GONE);
        }

        boolean canPay = plan.optBoolean("canPay", false);
        JSONObject pending = plan.optJSONObject("pendingPayment");
        String pendingStatus = pending == null ? null : pending.optString("status", null);
        if ("PENDING_VERIFICATION".equals(pendingStatus)) {
            // The box's advice: while a payment is being checked, say so
            // instead of offering another one.
            tvPending.setText(rupees(pending.optLong("amountPaise")) + " for " + planName(pending.optString("planCode"))
                    + " (" + pending.optString("billingCycle") + ") is paid and Magizhchi is checking it. "
                    + "The plan comes back the moment it is approved.");
            rowPendingButtons.setVisibility(View.GONE);
            boxPending.setVisibility(View.VISIBLE);
            sectionRenew.setVisibility(View.GONE);
        } else {
            JSONObject unpaid = unpaid();
            if (unpaid != null) {
                tvPending.setText("A payment of " + rupees(unpaid.optLong("amountPaise")) + " for "
                        + planName(unpaid.optString("planCode")) + " (" + unpaid.optString("billingCycle")
                        + ") was started, but no UTR has been sent. If it was paid, enter the UTR; if not, pay it now.");
                rowPendingButtons.setVisibility(canPay ? View.VISIBLE : View.GONE);
                boxPending.setVisibility(View.VISIBLE);
            } else {
                boxPending.setVisibility(View.GONE);
            }
            sectionRenew.setVisibility(View.VISIBLE);
        }

        if (chosen == null) chosen = defaultChoice();
        drawChoices();
        btnPay.setVisibility(canPay ? View.VISIBLE : View.GONE);
        tvOwnerOnly.setText(str(plan, "whoCanPay", "Only the shop's owner can renew the plan."));
        tvOwnerOnly.setVisibility(canPay ? View.GONE : View.VISIBLE);

        drawPayments();
    }

    /**
     * Renewing the plan it is on is the usual case. Once a plan has ENDED the
     * box reports "Free", so the plan last paid for stands in for it; failing
     * both, the smallest plan with half as much room again as is stored - the
     * smallest that merely holds it (100 GB for 95 GB) would be full in weeks.
     */
    private String defaultChoice() {
        JSONArray plans = plan.optJSONArray("plans");
        if (plans == null) return null;
        JSONObject pending = plan.optJSONObject("pendingPayment");
        if (pending != null && holds(find(plans, pending.optString("planCode")))) return pending.optString("planCode");
        if (holds(find(plans, str(plan, "planCode")))) return str(plan, "planCode");
        JSONArray pays = plan.optJSONArray("payments");
        for (int i = 0; pays != null && i < pays.length(); i++) {
            JSONObject p = pays.optJSONObject(i);
            if (p != null && "APPROVED".equals(p.optString("status")) && holds(find(plans, p.optString("planCode"))))
                return p.optString("planCode");
        }
        long used = plan.optLong("usedBytes", 0);
        String last = null;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (!holds(p)) continue;
            last = p.optString("code");
            if (p.optLong("storageBytes", 0) >= used + used / 2) return last;
        }
        return last;
    }

    private boolean holds(JSONObject p) {
        return p != null && p.optLong("storageBytes", 0) > plan.optLong("usedBytes", 0);
    }

    private static JSONObject find(JSONArray plans, String code) {
        if (code == null) return null;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (p != null && code.equals(p.optString("code"))) return p;
        }
        return null;
    }

    private void drawChoices() {
        paintChip(chipYearly, "yearly".equals(cycle));
        paintChip(chipMonthly, "monthly".equals(cycle));

        listPlans.removeAllViews();
        JSONArray plans = plan == null ? null : plan.optJSONArray("plans");
        if (plans == null || plans.length() == 0) {
            TextView t = new TextView(this);
            t.setText("The plans could not be read just now. Pull down to try again.");
            t.setTextColor(ContextCompat.getColor(this, R.color.ink_mute));
            listPlans.addView(t);
            btnPay.setEnabled(false);
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        String current = str(plan, "planCode");
        long used = plan.optLong("usedBytes", 0);
        JSONObject picked = null;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject p = plans.optJSONObject(i);
            if (p == null) continue;
            String code = p.optString("code");
            boolean fits = holds(p);
            boolean on = code.equals(chosen) && fits;
            if (on) picked = p;

            View row = inf.inflate(R.layout.item_box_plan, listPlans, false);
            ((TextView) row.findViewById(R.id.tvTick)).setText(on ? "✓" : "");
            ((TextView) row.findViewById(R.id.tvLabel)).setText(p.optString("label", code)
                    + (code.equals(current) && "ACTIVE".equals(str(plan, "planState")) ? "  (this plan)" : ""));
            long room = p.optLong("storageBytes");
            long fullPct = room <= 0 ? 0 : Math.round(100.0 * used / room);
            ((TextView) row.findViewById(R.id.tvSpace)).setText(!fits
                    ? size(room) + ": too small for the " + size(used) + " stored"
                    : fullPct >= 80 ? size(room) + " of space: " + fullPct + "% full from the start"
                    : size(room) + " of space");
            long paise = "yearly".equals(cycle) ? p.optLong("yearlyPaise") : p.optLong("monthlyPaise");
            ((TextView) row.findViewById(R.id.tvPrice)).setText(rupees(paise));
            ((TextView) row.findViewById(R.id.tvPer)).setText("yearly".equals(cycle) ? "a year" : "a month");
            if (on) {
                int ps = row.getPaddingStart(), pt = row.getPaddingTop(), pe = row.getPaddingEnd(), pb = row.getPaddingBottom();
                row.setBackgroundResource(R.drawable.bg_table_row_selected);
                row.setPaddingRelative(ps, pt, pe, pb);
            }
            row.setAlpha(fits ? 1f : 0.45f);
            row.setEnabled(fits);
            if (fits) row.setOnClickListener(v -> { chosen = code; drawChoices(); });
            listPlans.addView(row);
        }
        if (picked == null) {
            btnPay.setText("Choose a plan");
            btnPay.setEnabled(false);
        } else {
            long paise = "yearly".equals(cycle) ? picked.optLong("yearlyPaise") : picked.optLong("monthlyPaise");
            btnPay.setText(busy ? "Starting the payment" : "Pay " + rupees(paise) + " with UPI");
            btnPay.setEnabled(!busy);
        }
    }

    private void paintChip(TextView chip, boolean on) {
        int ps = chip.getPaddingStart(), pt = chip.getPaddingTop(), pe = chip.getPaddingEnd(), pb = chip.getPaddingBottom();
        chip.setBackgroundResource(on ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setPaddingRelative(ps, pt, pe, pb);
        chip.setTextColor(ContextCompat.getColor(this, on ? R.color.on_wine : R.color.ink_body));
    }

    private void drawPayments() {
        listPayments.removeAllViews();
        JSONArray pays = plan.optJSONArray("payments");
        if (pays == null || pays.length() == 0) { sectionPayments.setVisibility(View.GONE); return; }
        sectionPayments.setVisibility(View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < pays.length(); i++) {
            JSONObject p = pays.optJSONObject(i);
            if (p == null) continue;
            View row = inf.inflate(R.layout.item_box_payment, listPayments, false);
            ((TextView) row.findViewById(R.id.tvWhat)).setText(rupees(p.optLong("amountPaise"))
                    + "  ·  " + planName(p.optString("planCode")) + ", " + p.optString("billingCycle"));
            TextView st = row.findViewById(R.id.tvStatus);
            String status = p.optString("status");
            switch (status) {
                case "APPROVED":             st.setText("APPROVED");      StatusPill.paintTone(st, "good"); break;
                case "REJECTED":             st.setText("REJECTED");      StatusPill.paintTone(st, "bad");  break;
                case "PENDING_VERIFICATION": st.setText("BEING CHECKED"); StatusPill.paintTone(st, "warn"); break;
                default:                     st.setText("NOT PAID");      StatusPill.paintTone(st, "quiet");
            }
            String utr = str(p, "paymentUtr");
            ((TextView) row.findViewById(R.id.tvMeta)).setText("Ref " + p.optString("reference")
                    + (utr == null ? "" : "  ·  UTR " + utr) + "  ·  " + when(str(p, "createdAt")));
            String why = str(p, "adminNote");
            TextView tvWhy = row.findViewById(R.id.tvWhy);
            if ("REJECTED".equals(status) && why != null) {
                tvWhy.setText("Why: " + why);
                tvWhy.setVisibility(View.VISIBLE);
            }
            listPayments.addView(row);
        }
    }

    /** The newest payment started but not yet paid for, from the payments list (it carries the UPI link). */
    private JSONObject unpaid() {
        JSONArray pays = plan == null ? null : plan.optJSONArray("payments");
        if (pays == null) return null;
        for (int i = 0; i < pays.length(); i++) {
            JSONObject p = pays.optJSONObject(i);
            if (p != null && "PENDING_PAYMENT".equals(p.optString("status"))) return p;
        }
        return null;
    }

    private String planName(String code) {
        JSONArray plans = plan == null ? null : plan.optJSONArray("plans");
        JSONObject p = plans == null ? null : find(plans, code);
        return p == null ? code : p.optString("label", code);
    }

    // ── Paying ────────────────────────────────────────────────────────────────

    private void startPayment() {
        if (busy || chosen == null) return;
        busy = true;
        drawChoices();
        ApiService.boxRenew(chosen, cycle, new ApiService.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject payment) {
                runOnUiThread(() -> {
                    busy = false;
                    drawChoices();
                    openUpi(payment);
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    busy = false;
                    drawChoices();
                    new AlertDialog.Builder(BoxPlanActivity.this)
                            .setTitle("The payment could not be started")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void payAgain() {
        JSONObject p = unpaid();
        if (p != null) openUpi(p);
    }

    private void openUpi(JSONObject payment) {
        lastPayment = payment;
        prefs().edit().putString(K_LAUNCHED, payment.optString("reference")).apply();
        String uri = payment.optString("upiUri", "");
        if (uri.isEmpty()) {
            askUtr(payment, null, "Pay by hand with these details:");
            return;
        }
        try {
            // Plain ACTION_VIEW, not a chooser: Android asks which UPI app when
            // there are several, and only this way does the app's answer come back.
            upiLauncher.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            askUtr(payment, null, "No UPI app was found on this phone. Pay by hand with these details:");
        }
    }

    /**
     * What the UPI app said. The answer is a query string such as
     * {@code txnId=..&responseCode=00&Status=SUCCESS&ApprovalRefNo=..}; key case
     * differs between apps, and many send nothing at all. ApprovalRefNo is the
     * bank's reference - the UTR - so when it is 12 digits it is filled in.
     */
    private void afterUpi(ActivityResult r) {
        JSONObject payment = lastPayment;
        if (payment == null) return;
        Map<String, String> ans = new HashMap<>();
        Intent data = r.getData();
        String raw = data == null ? null : data.getStringExtra("response");
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) ans.put(pair.substring(0, eq).trim().toLowerCase(Locale.US), Uri.decode(pair.substring(eq + 1)).trim());
            }
        }
        String status = ans.get("status");
        String ref = ans.get("approvalrefno");
        String prefill = ref != null && ref.matches("\\d{12}") ? ref : null;
        String lead = status != null && status.equalsIgnoreCase("FAILURE")
                ? "The payment app says the payment did not go through. If money did leave the account, type the UTR; otherwise tap Not paid yet and try again."
                : null;
        askUtr(payment, prefill, lead);
    }

    /** Back from a UPI app after Android closed this screen: ask for that payment's UTR once. */
    private void maybeAskForLaunchedUtr() {
        String ref = prefs().getString(K_LAUNCHED, null);
        if (ref == null || utrDialog != null || !plan.optBoolean("canPay", false)) return;
        JSONObject p = unpaid();
        if (p != null && ref.equals(p.optString("reference"))) {
            askUtr(p, null, null);
        } else {
            prefs().edit().remove(K_LAUNCHED).apply();      // paid, approved or gone
        }
    }

    private void askUtr(JSONObject payment, String prefill, String lead) {
        if (utrDialog != null && utrDialog.isShowing()) return;
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_box_utr, null);
        TextView payTo = v.findViewById(R.id.tvPayTo);
        EditText et = v.findViewById(R.id.etUtr);
        TextView count = v.findViewById(R.id.tvUtrCount);

        payTo.setText((lead == null ? "" : lead + "\n\n")
                + "Pay " + rupees(payment.optLong("amountPaise")) + " to\n"
                + payment.optString("upiVpa", "?") + " (" + payment.optString("payeeName", "Magizhchi Software") + ")\n"
                + "Note: " + payment.optString("reference"));

        utrDialog = new AlertDialog.Builder(this)
                .setTitle("Enter the UTR")
                .setView(v)
                .setPositiveButton("Send", null)
                .setNegativeButton("Not paid yet", (d, w) -> prefs().edit().remove(K_LAUNCHED).apply())
                .setNeutralButton("Copy UPI ID", null)
                .create();
        utrDialog.setOnDismissListener(d -> utrDialog = null);
        utrDialog.setOnShowListener(d -> {
            Button send = utrDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            send.setEnabled(prefill != null);
            et.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable e) {
                    count.setTextColor(ContextCompat.getColor(BoxPlanActivity.this, R.color.ink_mute));
                    count.setText(e.length() + " of 12");
                    send.setEnabled(e.length() == 12);
                }
            });
            if (prefill != null) et.setText(prefill);
            utrDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("UPI ID", payment.optString("upiVpa")));
                Toast.makeText(this, "UPI ID copied", Toast.LENGTH_SHORT).show();
            });
            send.setOnClickListener(x -> {
                send.setEnabled(false);
                count.setText("Sending");
                ApiService.boxSubmitUtr(payment.optString("reference"), et.getText().toString().trim(),
                        new ApiService.Callback<JSONObject>() {
                    @Override public void onSuccess(JSONObject done) {
                        runOnUiThread(() -> {
                            prefs().edit().remove(K_LAUNCHED).apply();
                            if (utrDialog != null) utrDialog.dismiss();
                            new AlertDialog.Builder(BoxPlanActivity.this)
                                    .setTitle("Sent")
                                    .setMessage("Magizhchi will check the payment against the bank. "
                                            + "The plan comes back as soon as it is approved, usually the same day.")
                                    .setPositiveButton("OK", null)
                                    .show();
                            load();
                        });
                    }
                    @Override public void onError(String msg) {
                        runOnUiThread(() -> {
                            count.setTextColor(ContextCompat.getColor(BoxPlanActivity.this, R.color.ruby_ink));
                            count.setText(msg);
                            send.setEnabled(et.length() == 12);
                        });
                    }
                });
            });
        });
        utrDialog.show();
    }

    // ── Small things ──────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS + "_" + ApiService.getCurrentShopId(this), MODE_PRIVATE);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String str(JSONObject o, String k) { return str(o, k, null); }

    private static String str(JSONObject o, String k, String fallback) {
        if (o == null || o.isNull(k)) return fallback;
        String v = o.optString(k, "");
        return v.isEmpty() ? fallback : v;
    }

    /** ₹14,999 - Indian grouping, paise only when there are some. */
    static String rupees(long paise) {
        String s = String.valueOf(paise / 100);
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) b.append(',');
                b.append(head.charAt(i));
            }
            s = b + "," + tail;
        }
        long p = paise % 100;
        return "₹" + s + (p == 0 ? "" : String.format(Locale.US, ".%02d", p));
    }

    static String size(long bytes) {
        double g = bytes / 1073741824.0;
        if (g >= 1024) return trim(g / 1024) + " TB";
        if (g >= 1) return trim(g) + " GB";
        return (bytes >> 20) + " MB";
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    /** A box time (UTC, ISO) as the shop's day and time. */
    private static String when(String iso) {
        if (iso == null || iso.length() < 19) return iso == null ? "" : iso;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.substring(0, 19));
            SimpleDateFormat out = new SimpleDateFormat("dd-MM-yyyy h:mm a", Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
            return d == null ? iso : out.format(d).replace("AM", "am").replace("PM", "pm");
        } catch (Exception e) {
            return iso;
        }
    }
}
