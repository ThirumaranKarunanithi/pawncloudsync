package com.pawnbroking.app.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pawnbroking.app.R;
import com.pawnbroking.app.models.Bill;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class BillAdapter extends RecyclerView.Adapter<BillAdapter.VH> {

    public interface OnBillClick { void onClick(Bill bill); }

    private final List<Bill> bills;
    private final OnBillClick listener;
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("en", "IN"));

    public BillAdapter(List<Bill> bills, OnBillClick listener) {
        this.bills = bills;
        this.listener = listener;
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
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

        int goldColor  = b.isGold() ? Color.parseColor("#E6B800") : Color.parseColor("#AAAAAA");
        h.tvMaterial.setTextColor(goldColor);
        h.tvBillNo.setTextColor(goldColor);

        int statusColor;
        switch (b.status) {
            case "OPENED": statusColor = Color.parseColor("#4CAF50"); break;
            case "CLOSED": statusColor = Color.parseColor("#FF9800"); break;
            default:       statusColor = Color.parseColor("#2196F3"); break;
        }
        h.tvStatus.setText(b.status);
        h.tvStatus.setTextColor(statusColor);

        h.itemView.setOnClickListener(v -> listener.onClick(b));
    }

    @Override
    public int getItemCount() { return bills.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvBillNo, tvCustomer, tvMobile, tvAmount, tvDate, tvMaterial, tvStatus;
        VH(View v) {
            super(v);
            tvBillNo   = v.findViewById(R.id.tvBillNo);
            tvCustomer = v.findViewById(R.id.tvCustomer);
            tvMobile   = v.findViewById(R.id.tvMobile);
            tvAmount   = v.findViewById(R.id.tvAmount);
            tvDate     = v.findViewById(R.id.tvDate);
            tvMaterial = v.findViewById(R.id.tvMaterial);
            tvStatus   = v.findViewById(R.id.tvStatus);
        }
    }
}
