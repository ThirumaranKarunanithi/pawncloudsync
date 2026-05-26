package com.pawnbroking.app.models;

import org.json.JSONObject;

public class Bill {
    public final String billNumber;
    public final String materialType;
    public final String customerName;
    public final String mobileNumber;
    public final String openingDate;
    public final String acceptedClosingDate;
    public final double amount;
    public final double openTakenAmount;
    public final String status;
    public final String items;

    public Bill(String billNumber, String materialType, String customerName,
                String mobileNumber, String openingDate, String acceptedClosingDate,
                double amount, double openTakenAmount, String status, String items) {
        this.billNumber = billNumber;
        this.materialType = materialType;
        this.customerName = customerName;
        this.mobileNumber = mobileNumber;
        this.openingDate = openingDate;
        this.acceptedClosingDate = acceptedClosingDate;
        this.amount = amount;
        this.openTakenAmount = openTakenAmount;
        this.status = status;
        this.items = items;
    }

    /**
     * Accepts either:
     *  - a flat bill object (legacy /api/bills format), or
     *  - a projection row {row_pk, payload:{...}, last_updated_at} (cloud /v1/data/*)
     */
    public static Bill fromJson(JSONObject row) throws Exception {
        JSONObject j = row.optJSONObject("payload");
        if (j == null) j = row;
        String billNumber = j.optString("bill_number", row.optString("row_pk", ""));
        return new Bill(
            billNumber,
            j.optString("jewel_material_type", j.optString("material_type", "")),
            j.optString("customer_name", ""),
            j.optString("mobile_number", null),
            j.optString("opening_date", null),
            j.optString("accepted_closing_date", null),
            toDouble(j.opt("amount")),
            toDouble(j.opt("open_taken_amount")),
            j.optString("status", ""),
            j.optString("items", null)
        );
    }

    public boolean isGold()   { return "GOLD".equals(materialType); }
    public boolean isOpened() { return "OPENED".equals(status); }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.0; }
    }
}
