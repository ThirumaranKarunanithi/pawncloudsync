package com.magizhchi.cloud.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magizhchi.cloud.tenant.TenantContext;
import com.magizhchi.cloud.tenant.TenantJdbc;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/data")
public class DataController {
    private static final Logger log = LoggerFactory.getLogger(DataController.class);
    private static final ObjectMapper M = new ObjectMapper();
    private final TenantJdbc t;
    public DataController(TenantJdbc t) { this.t = t; }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard() {
        return t.inTenant(j -> {
            // Table names mirror the desktop app's schema, not the stylised
            // names the previous /api/* server invented.
            Long bills = j.queryForObject(
                "SELECT count(*) FROM projections WHERE table_name='company_billing' " +
                "AND (payload->>'opening_date')::date = current_date AND NOT deleted",
                Long.class);
            Long customers = j.queryForObject(
                "SELECT count(*) FROM projections WHERE table_name='customer_details' AND NOT deleted",
                Long.class);
            Double advTotal = j.queryForObject(
                "SELECT COALESCE(sum((payload->>'paid_amount')::numeric),0) FROM projections " +
                "WHERE table_name='company_advance_amount' AND NOT deleted", Double.class);
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
                                          @RequestParam(required=false) String q,
                                          @RequestParam(name="order_by", required=false) String orderBy) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        int cap = Math.min(Math.max(limit, 1), 500);
        // ORDER BY only allowed against a payload JSONB field. Caller passes
        // "field" (ASC) or "field:desc"; field name is whitelisted to a-z_
        // to keep SQL safe.
        String orderField = null;
        boolean orderDesc = false;
        if (orderBy != null && !orderBy.isBlank()) {
            String[] parts = orderBy.split(":");
            if (parts[0].matches("[a-z_][a-z0-9_]*")) {
                orderField = parts[0];
                orderDesc  = parts.length > 1 && "desc".equalsIgnoreCase(parts[1]);
            }
        }
        final String of = orderField;
        final boolean odesc = orderDesc;
        return t.inTenant(j -> {
            String order = of != null
                ? "(payload->>'" + of + "') " + (odesc ? "DESC" : "ASC") + " NULLS LAST"
                : "last_updated_at DESC";
            List<Map<String,Object>> rows;
            if (q == null || q.isBlank()) {
                rows = j.queryForList(
                    "SELECT row_pk, payload, last_updated_at FROM projections " +
                    "WHERE table_name = ? AND NOT deleted " +
                    "ORDER BY " + order + " LIMIT ?", table, cap);
            } else {
                rows = j.queryForList(
                    "SELECT row_pk, payload, last_updated_at FROM projections " +
                    "WHERE table_name = ? AND NOT deleted AND payload::text ILIKE ? " +
                    "ORDER BY " + order + " LIMIT ?",
                    table, "%" + q + "%", cap);
            }
            return rehydrate(rows);
        });
    }

    @GetMapping("/{table}/{rowPk}")
    public Map<String,Object> one(@PathVariable String table, @PathVariable String rowPk) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        return t.inTenant(j -> {
            List<Map<String,Object>> rows = j.queryForList(
                "SELECT row_pk, payload, last_updated_at, deleted FROM projections " +
                "WHERE table_name = ? AND row_pk = ?", table, rowPk);
            if (rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        table + " row '" + rowPk + "' not found");
            return rehydrateOne(rows.get(0));
        });
    }

    /**
     * Monthly aggregation of company_billing for the MIS Report screen.
     * Buckets by opening_date month; profit / earned use closing data.
     * Returns the most recent {@code limit} months, newest first.
     */
    @GetMapping("/monthly-report")
    public Map<String,Object> monthlyReport(
            @RequestParam(defaultValue="24") int limit,
            @RequestParam(name="companyId", required=false) String companyId) {
        int cap = Math.min(Math.max(limit, 1), 120);
        String companyFilter = (companyId == null || companyId.isBlank()
                                || "ALL".equalsIgnoreCase(companyId))
                ? null : companyId;
        try {
            return t.inTenant(j -> {
                Object[] args = companyFilter == null
                        ? new Object[]{ cap }
                        : new Object[]{ companyFilter, cap };
                String companyPredicate = companyFilter == null
                        ? ""
                        : " AND payload->>'company_id' = ? ";
                List<Map<String,Object>> months = j.queryForList(
                    "SELECT to_char(opd, 'YYYY-MM') AS month, " +
                    "       count(*) AS \"pawnBills\", " +
                    "       COALESCE(sum(amt), 0) AS \"pawnAmount\", " +
                    "       count(*) FILTER (WHERE mat='GOLD')   AS \"goldBills\", " +
                    "       count(*) FILTER (WHERE mat='SILVER') AS \"silverBills\", " +
                    "       COALESCE(sum(amt) FILTER (WHERE mat='GOLD'),   0) AS \"goldAmount\", " +
                    "       COALESCE(sum(amt) FILTER (WHERE mat='SILVER'), 0) AS \"silverAmount\", " +
                    "       count(*) FILTER (WHERE status='DELIVERED') AS \"redeemBills\", " +
                    "       COALESCE(sum(cta) FILTER (WHERE status='DELIVERED'), 0) AS \"redeemAmount\", " +
                    "       COALESCE(sum(cta - amt) FILTER (WHERE status='DELIVERED'), 0) AS \"profit\", " +
                    "       count(*) FILTER (WHERE status NOT IN ('DELIVERED','CANCELLED')) AS \"stockBills\", " +
                    "       COALESCE(sum(amt) FILTER (WHERE status NOT IN ('DELIVERED','CANCELLED')), 0) AS \"stockAmount\", " +
                    "       count(*) FILTER (WHERE status='DELIVERED' AND cta > amt) AS \"earnedBills\", " +
                    "       COALESCE(sum(cta - amt) FILTER (WHERE status='DELIVERED' AND cta > amt), 0) AS \"earnedAmount\" " +
                    "  FROM ( " +
                    "    SELECT " +
                    "      to_date(substring(payload->>'opening_date' from 1 for 10), 'YYYY-MM-DD') AS opd, " +
                    "      CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "           THEN (payload->>'amount')::numeric ELSE 0 END AS amt, " +
                    "      CASE WHEN payload->>'close_taken_amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "           THEN (payload->>'close_taken_amount')::numeric ELSE 0 END AS cta, " +
                    "      upper(COALESCE(payload->>'jewel_material_type', '')) AS mat, " +
                    "      COALESCE(payload->>'status', '') AS status " +
                    "    FROM projections " +
                    "    WHERE table_name = 'company_billing' AND NOT deleted " +
                    "      AND payload->>'opening_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                              companyPredicate +
                    "  ) src " +
                    "  GROUP BY month " +
                    "  ORDER BY month DESC " +
                    "  LIMIT ?",
                    args);
                return Map.of("total", months.size(), "months", months);
            });
        } catch (Exception e) {
            log.error("monthlyReport failed: {}", e.toString(), e);
            return Map.of("total", 0, "months", List.of(),
                          "error", String.valueOf(e.getMessage()));
        }
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(@RequestParam(defaultValue="50") int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        return t.inTenant(j -> j.queryForList(
            "SELECT notif_id, event_id, title, body, table_name, row_pk, created_at " +
            "FROM notifications ORDER BY created_at DESC LIMIT ?", cap));
    }

    // --- helpers ------------------------------------------------------------
    private static List<Map<String,Object>> rehydrate(List<Map<String,Object>> rows) {
        for (Map<String,Object> row : rows) rehydrateOne(row);
        return rows;
    }

    private static Map<String,Object> rehydrateOne(Map<String,Object> row) {
        Object p = row.get("payload");
        if (p instanceof PGobject pg && pg.getValue() != null) {
            try {
                JsonNode node = M.readTree(pg.getValue());
                row.put("payload", node);
            } catch (Exception ignored) {
                row.put("payload", pg.getValue());
            }
        }
        return row;
    }
}
