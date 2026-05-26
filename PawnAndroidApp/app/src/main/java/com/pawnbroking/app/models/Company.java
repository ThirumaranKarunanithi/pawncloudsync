package com.pawnbroking.app.models;

import org.json.JSONObject;

public class Company {
    public final String id;
    public final String name;
    public final String city;
    public final String area;
    public final String mobileNumber;
    public final String type;
    public final String status;

    public Company(String id, String name, String city, String area,
                   String mobileNumber, String type, String status) {
        this.id = id;
        this.name = name;
        this.city = city;
        this.area = area;
        this.mobileNumber = mobileNumber;
        this.type = type;
        this.status = status;
    }

    public static Company fromJson(JSONObject j) throws Exception {
        String name = j.optString("name", "");
        if (name.isEmpty()) name = j.optString("id", "");
        return new Company(
            j.optString("id", ""),
            name,
            j.optString("city", ""),
            j.optString("area", null),
            j.optString("mobile_number", null),
            j.optString("type", null),
            j.optString("status", "ACTIVE")
        );
    }

    /** Spinner shows "CMP1 — RAJESHWARI PAWN BROKING" so the user sees both
     *  the short id and the business name, mirroring the desktop combobox. */
    @Override
    public String toString() {
        if (id == null || id.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(id)) return id;
        return id + " — " + name;
    }
}
