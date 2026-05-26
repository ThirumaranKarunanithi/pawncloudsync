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
        return new Company(
            j.optString("id", ""),
            j.getString("name"),
            j.optString("city", ""),
            j.optString("area", null),
            j.optString("mobile_number", null),
            j.optString("type", null),
            j.optString("status", "ACTIVE")
        );
    }

    @Override
    public String toString() { return name; }
}
