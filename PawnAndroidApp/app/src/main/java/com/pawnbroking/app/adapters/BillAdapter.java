package com.pawnbroking.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.R;
import com.pawnbroking.app.models.Bill;
import com.pawnbroking.app.util.Royal;
import com.pawnbroking.app.util.StatusPill;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BillAdapter extends RecyclerView.Adapter<BillAdapter.VH> {

    public interface OnBillClick { void onClick(Bill bill); }

    private final List<Bill> bills;
    private final OnBillClick listener;
    /** Built on first bind — an adapter has no Context until then. */
    private Royal royal;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));
    private final DecimalFormat grams = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    /** The search box's words, marked wherever they appear in a card's jewels. */
    private final List<String> marks = new ArrayList<>();

    public BillAdapter(List<Bill> bills, OnBillClick listener) {
        this.bills = bills;
        this.listener = listener;
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
    }

    /**
     * What the search box holds. Each word that is not only digits (a bill or phone number never names a
     * jewel) is marked in the jewels, so "kolusu" stands out on the cards it found.
     */
    public void setHighlight(String query) {
        marks.clear();
        if (query == null) return;
        for (String w : query.trim().split("\\s+")) {
            if (w.length() >= 2 && !w.matches("\\d+")) marks.add(w.toLowerCase(Locale.ROOT));
        }
    }

    private static final Pattern MARK = Pattern.compile("^(.*?)\\s*(\\([^)]*\\))$");
    private static final Pattern COUNT = Pattern.compile("^(.*?)\\s*-\\s*(\\d+)$");

    /**
     * The bill's items text as the card shows it: "STUD AATAM-2, LADIES RING-1" -> "STUD AATAM ×2 · LADIES RING".
     * The shop's own words, only the count made readable. A marked jewel keeps its mark ("STUD ×2 (BROKEN)");
     * the bracket comes off before the count is read, as the desktop's JewelLines does.
     */
    static String jewels(String items) {
        if (items == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : items.split(",")) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            String mark = "";
            Matcher m = MARK.matcher(p);
            if (m.matches()) { p = m.group(1).trim(); mark = " " + m.group(2); }
            String name = p, count = "";
            Matcher c = COUNT.matcher(p);
            if (c.matches()) {
                name = c.group(1).trim();
                if (!"1".equals(c.group(2).replaceFirst("^0+(?=\\d)", ""))) count = " ×" + c.group(2);
            }
            if (out.length() > 0) out.append("  ·  ");
            // One jewel never splits over two lines ("EAR" / "STUD ×2"): its own spaces do not break.
            out.append((name + count + mark).replace(' ', ' '));
        }
        return out.toString();
    }

    private CharSequence marked(String text, android.content.Context ctx) {
        if (marks.isEmpty() || text.isEmpty()) return text;
        android.text.SpannableString s = new android.text.SpannableString(text);
        String low = text.toLowerCase(Locale.ROOT);
        int wash = androidx.core.content.ContextCompat.getColor(ctx, R.color.jewel_mark);
        for (String w : marks) {
            for (int i = low.indexOf(w); i >= 0; i = low.indexOf(w, i + w.length())) {
                s.setSpan(new android.text.style.BackgroundColorSpan(wash), i, i + w.length(), 0);
                s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + w.length(), 0);
            }
        }
        return s;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.item_bill, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Bill b = bills.get(position);
        h.tvBillNo.setText(b.billNumber);
        h.tvCustomer.setText(b.customerName);
        h.tvMobile.setText(b.mobileNumber != null ? b.mobileNumber : "");
        h.tvMobile.setVisibility(b.mobileNumber != null && !b.mobileNumber.isEmpty()
                                  ? View.VISIBLE : View.GONE);
        h.tvAmount.setText("₹ " + fmt.format(b.amount));
        h.tvDate.setText(b.openingDate != null ? b.openingDate : "");
        h.tvDate.setVisibility(b.openingDate != null ? View.VISIBLE : View.GONE);
        h.tvMaterial.setText(b.materialType);

        // What was pledged, and its weight: how a customer without the bill describes it.
        String jewels = jewels(b.items);
        boolean hasWeight = b.grossWeight > 0;
        h.rowJewels.setVisibility(jewels.isEmpty() && !hasWeight ? View.GONE : View.VISIBLE);
        h.tvItems.setText(jewels.isEmpty() ? "Jewels not written on the bill" : marked(jewels, h.itemView.getContext()));
        h.colWeight.setVisibility(hasWeight ? View.VISIBLE : View.GONE);
        h.tvGross.setText(grams.format(b.grossWeight) + " g");
        boolean showNet = b.netWeight > 0 && Math.abs(b.netWeight - b.grossWeight) >= 0.0005;
        h.tvNet.setText(showNet ? "net " + grams.format(b.netWeight) + " g" : "");
        h.tvNet.setVisibility(showNet ? View.VISIBLE : View.GONE);

        if (royal == null) royal = new Royal(h.itemView.getContext());

        // The row's left edge is the metal, so a long list sorts gold
        // from silver without reading a word. setBackgroundResource
        // drops the layout's padding, so put it back.
        int pl = h.itemView.getPaddingLeft(),  pt = h.itemView.getPaddingTop();
        int pr = h.itemView.getPaddingRight(), pb = h.itemView.getPaddingBottom();
        h.itemView.setBackgroundResource(b.isGold()
                ? R.drawable.bg_row_gold : R.drawable.bg_row_silver);
        h.itemView.setPadding(pl, pt, pr, pb);

        // The metal tints its own tag; the bill number stays ink so the
        // number itself is the thing you read first.
        h.tvMaterial.setTextColor(royal.metal(b.isGold()));
        h.tvBillNo.setTextColor(royal.ink);

        h.tvStatus.setText(b.status);
        StatusPill.paint(h.tvStatus, b.status);

        h.itemView.setOnClickListener(v -> listener.onClick(b));
    }

    @Override
    public int getItemCount() { return bills.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvBillNo, tvCustomer, tvMobile, tvAmount, tvDate, tvMaterial, tvStatus;
        TextView tvItems, tvGross, tvNet;
        View rowJewels, colWeight;
        VH(View v) {
            super(v);
            tvBillNo   = v.findViewById(R.id.tvBillNo);
            tvCustomer = v.findViewById(R.id.tvCustomer);
            tvMobile   = v.findViewById(R.id.tvMobile);
            tvAmount   = v.findViewById(R.id.tvAmount);
            tvDate     = v.findViewById(R.id.tvDate);
            tvMaterial = v.findViewById(R.id.tvMaterial);
            tvStatus   = v.findViewById(R.id.tvStatus);
            rowJewels  = v.findViewById(R.id.rowJewels);
            colWeight  = v.findViewById(R.id.colWeight);
            tvItems    = v.findViewById(R.id.tvItems);
            tvGross    = v.findViewById(R.id.tvGross);
            tvNet      = v.findViewById(R.id.tvNet);
        }
    }
}
