package com.magizhchi.cloud.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder enc;
    private final JwtService jwt;

    public AuthController(JdbcTemplate jdbc, BCryptPasswordEncoder enc, JwtService jwt) {
        this.jdbc = jdbc; this.enc = enc; this.jwt = jwt;
    }

    public record LoginRequest(String shop_id, String username, String password) {}

    @PostMapping("/mobile")
    public Map<String,Object> mobileLogin(@RequestBody LoginRequest req) {
        List<Map<String,Object>> rows = jdbc.queryForList(
            "SELECT user_id, password_hash, role FROM public.app_users " +
            "WHERE shop_id = ? AND username = ?", req.shop_id(), req.username());
        if (rows.isEmpty()) throw new RuntimeException("bad credentials");
        Map<String,Object> u = rows.get(0);
        if (!enc.matches(req.password(), (String) u.get("password_hash")))
            throw new RuntimeException("bad credentials");
        long userId = ((Number) u.get("user_id")).longValue();
        String role = (String) u.get("role");
        String token = jwt.mint(userId, req.shop_id(), role);
        return Map.of("access_token", token, "shop_id", req.shop_id(),
                      "user_id", userId, "role", role);
    }
}
