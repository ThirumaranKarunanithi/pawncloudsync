package com.magizhchi.cloud.data;

import com.magizhchi.cloud.tenant.TenantContext;
import com.magizhchi.cloud.tenant.TenantJdbc;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/data")
public class DataController {
    private final TenantJdbc t;
    public DataController(TenantJdbc t) { this.t = t; }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard() {
        return t.inTenant(j -> {
            Long bills = j.queryForObject(
                "SELECT count(*) FROM projections WHERE table_name='bill_opening' " +
                "AND last_updated_at::date = current_date AND NOT deleted", Long.class);
            Long customers = j.queryForObject(
                "SELECT count(*) FROM projections WHERE table_name='customer_master' AND NOT deleted",
                Long.class);
            Double advTotal = j.queryForObject(
                "SELECT COALESCE(sum((payload->>'amount')::numeric),0) FROM projections " +
                "WHERE table_name='advance_amount' AND NOT deleted", Double.class);
            return Map.of(
                "shop_id", TenantContext.get(),
                "todays_bills", bills,
                "total_customers", customers,
                "advance_total", advTotal
            );
        });
    }

    @GetMapping("/{table}")
    public List<Map<String,Object>> list(@PathVariable String table,
                                          @RequestParam(defaultValue="100") int limit,
                                          @RequestParam(required=false) String q) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        int cap = Math.min(Math.max(limit, 1), 500);
        return t.inTenant(j -> {
            if (q == null || q.isBlank()) {
                return j.queryForList(
                    "SELECT row_pk, payload, last_updated_at FROM projections " +
                    "WHERE table_name = ? AND NOT deleted " +
                    "ORDER BY last_updated_at DESC LIMIT ?", table, cap);
            }
            return j.queryForList(
                "SELECT row_pk, payload, last_updated_at FROM projections " +
                "WHERE table_name = ? AND NOT deleted AND payload::text ILIKE ? " +
                "ORDER BY last_updated_at DESC LIMIT ?",
                table, "%" + q + "%", cap);
        });
    }

    @GetMapping("/{table}/{rowPk}")
    public Map<String,Object> one(@PathVariable String table, @PathVariable String rowPk) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        return t.inTenant(j -> j.queryForMap(
            "SELECT row_pk, payload, last_updated_at, deleted FROM projections " +
            "WHERE table_name = ? AND row_pk = ?", table, rowPk));
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(@RequestParam(defaultValue="50") int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        return t.inTenant(j -> j.queryForList(
            "SELECT notif_id, event_id, title, body, table_name, row_pk, created_at " +
            "FROM notifications ORDER BY created_at DESC LIMIT ?", cap));
    }
}
