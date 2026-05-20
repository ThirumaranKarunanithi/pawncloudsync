package com.magizhchi.cloud.devices;

import com.magizhchi.cloud.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/devices")
public class DeviceController {
    private final JdbcTemplate jdbc;
    public DeviceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record DeviceReq(Long user_id, String fcm_token, String device_label) {}

    @PostMapping
    public Map<String,Object> register(@RequestBody DeviceReq req) {
        String shop = TenantContext.get();
        jdbc.update(
            "INSERT INTO public.devices(shop_id, user_id, fcm_token, device_label, last_seen_at) " +
            "VALUES (?,?,?,?, now()) " +
            "ON CONFLICT (shop_id, fcm_token) DO UPDATE SET " +
            "  user_id = EXCLUDED.user_id, device_label = EXCLUDED.device_label, " +
            "  last_seen_at = now()",
            shop, req.user_id(), req.fcm_token(), req.device_label());
        return Map.of("ok", true);
    }

    @DeleteMapping
    public Map<String,Object> remove(@RequestParam String fcm_token) {
        String shop = TenantContext.get();
        int n = jdbc.update("DELETE FROM public.devices WHERE shop_id = ? AND fcm_token = ?",
                            shop, fcm_token);
        return Map.of("deleted", n);
    }
}
