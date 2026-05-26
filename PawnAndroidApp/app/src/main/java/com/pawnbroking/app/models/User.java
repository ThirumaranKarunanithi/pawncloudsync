package com.pawnbroking.app.models;

import org.json.JSONObject;

public class User {
    public final String userId;
    public final String userName;
    public final String employeeName;
    public final String roleId;
    public final String roleName;
    public final String token;

    public User(String userId, String userName, String employeeName,
                String roleId, String roleName, String token) {
        this.userId = userId;
        this.userName = userName;
        this.employeeName = employeeName;
        this.roleId = roleId;
        this.roleName = roleName;
        this.token = token;
    }

    /**
     * Parses the cloud-api /v1/auth/mobile response:
     *   { access_token, shop_id, user_id, role }
     *
     * The legacy server also returned employee_name / role_name; the cloud
     * does not, so we synthesise readable values from the username we sent
     * (stored via {@link #fromLogin}).
     */
    public static User fromJson(JSONObject j) throws Exception {
        String token = j.optString("access_token", j.optString("token", ""));
        long userId  = j.optLong("user_id", j.optLong("userId", 0));
        String role  = j.optString("role", "user");
        return new User(
            String.valueOf(userId),
            j.optString("username", ""),     // filled in by fromLogin below
            j.optString("employee_name", ""),// filled in by fromLogin below
            "0",
            role,
            token
        );
    }

    /** Convenience wrapper used by ApiService.login — keeps the username
     *  the user typed (cloud response omits it). */
    public static User fromLogin(JSONObject j, String typedUsername) throws Exception {
        User u = fromJson(j);
        return new User(u.userId,
                        typedUsername,
                        typedUsername,
                        u.roleId,
                        u.roleName,
                        u.token);
    }
}
