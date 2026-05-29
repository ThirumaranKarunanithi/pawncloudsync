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
                                          @RequestParam(name="order_by", required=false) String orderBy,
                                          @RequestParam(name="companyId", required=false) String companyId,
                                          @RequestParam(name="material",  required=false) String material,
                                          @RequestParam(name="status",    required=false) String status,
                                          @RequestParam(name="statuses",  required=false) String statuses,
                                          @RequestParam(name="repledged", required=false) String repledged,
                                          @RequestParam(name="dateFrom",  required=false) String dateFrom,
                                          @RequestParam(name="dateTo",    required=false) String dateTo,
                                          @RequestParam(name="customerName", required=false) String customerName,
                                          @RequestParam(name="amountFrom", required=false) Double amountFrom,
                                          @RequestParam(name="amountTo",   required=false) Double amountTo) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        int cap = Math.min(Math.max(limit, 1), 500);
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

        WhereBuild wb = buildWhere(table, q, companyId, material, status,
                                   statuses, repledged,
                                   dateFrom, dateTo, customerName, amountFrom, amountTo);
        wb.args.add(cap);

        return t.inTenant(j -> {
            String order = of != null
                ? "(payload->>'" + of + "') " + (odesc ? "DESC" : "ASC") + " NULLS LAST"
                : "last_updated_at DESC";
            String sql = "SELECT row_pk, payload, last_updated_at FROM projections" +
                         wb.where + " ORDER BY " + order + " LIMIT ?";
            List<Map<String,Object>> rows = j.queryForList(sql, wb.args.toArray());
            return rehydrate(rows);
        });
    }

    /**
     * Aggregates the same filter set as {@link #list} without a LIMIT — so
     * the mobile stock screen can show the <i>true</i> total bill count and
     * sum across all matching rows, not just the top 500 displayed.
     */
    @GetMapping("/{table}/summary")
    public Map<String,Object> summary(@PathVariable String table,
                                       @RequestParam(required=false) String q,
                                       @RequestParam(name="companyId", required=false) String companyId,
                                       @RequestParam(name="material",  required=false) String material,
                                       @RequestParam(name="status",    required=false) String status,
                                       @RequestParam(name="statuses",  required=false) String statuses,
                                       @RequestParam(name="repledged", required=false) String repledged,
                                       @RequestParam(name="dateFrom",  required=false) String dateFrom,
                                       @RequestParam(name="dateTo",    required=false) String dateTo,
                                       @RequestParam(name="customerName", required=false) String customerName,
                                       @RequestParam(name="amountFrom", required=false) Double amountFrom,
                                       @RequestParam(name="amountTo",   required=false) Double amountTo) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("bad table");
        WhereBuild wb = buildWhere(table, q, companyId, material, status,
                                   statuses, repledged,
                                   dateFrom, dateTo, customerName, amountFrom, amountTo);
        return t.inTenant(j -> {
            String sql =
                "SELECT count(*) AS total, " +
                "       COALESCE(sum(CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                "                         THEN (payload->>'amount')::numeric ELSE 0 END), 0) AS \"totalAmount\", " +
                "       COALESCE(sum(CASE WHEN payload->>'interest' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                "                         THEN (payload->>'interest')::numeric ELSE 0 END), 0) AS \"totalInterest\" " +
                "  FROM projections " + wb.where;
            return j.queryForMap(sql, wb.args.toArray());
        });
    }

    // ── shared WHERE builder ─────────────────────────────────────────────────

    private static final class WhereBuild {
        final String where;
        final List<Object> args;
        WhereBuild(String w, List<Object> a) { this.where = w; this.args = a; }
    }

    private WhereBuild buildWhere(String table, String q, String companyId,
                                   String material, String status,
                                   String dateFrom, String dateTo,
                                   String customerName,
                                   Double amountFrom, Double amountTo) {
        return buildWhere(table, q, companyId, material, status,
                          /*statuses*/ null, /*repledged*/ null,
                          dateFrom, dateTo, customerName, amountFrom, amountTo);
    }

    private WhereBuild buildWhere(String table, String q, String companyId,
                                   String material, String status,
                                   String statuses, String repledged,
                                   String dateFrom, String dateTo,
                                   String customerName,
                                   Double amountFrom, Double amountTo) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(table);
        StringBuilder w = new StringBuilder(" WHERE table_name = ? AND NOT deleted ");

        if (q != null && !q.isBlank()) {
            w.append(" AND payload::text ILIKE ? ");
            args.add("%" + q + "%");
        }
        if (companyId != null && !companyId.isBlank() && !"ALL".equalsIgnoreCase(companyId)) {
            w.append(" AND payload->>'company_id' = ? "); args.add(companyId);
        }
        if (material != null && !material.isBlank() && !"ALL".equalsIgnoreCase(material)) {
            w.append(" AND upper(payload->>'jewel_material_type') = ? ");
            args.add(material.toUpperCase());
        }
        // statuses (CSV) wins over single status if both given. Build IN (...)
        // dynamically with parameterised placeholders.
        if (statuses != null && !statuses.isBlank() && !"ALL".equalsIgnoreCase(statuses)) {
            String[] parts = statuses.toUpperCase().split(",");
            List<String> cleaned = new java.util.ArrayList<>();
            for (String p : parts) { String t = p.trim(); if (!t.isEmpty()) cleaned.add(t); }
            if (!cleaned.isEmpty()) {
                w.append(" AND upper(COALESCE(payload->>'status','')) IN (");
                for (int i = 0; i < cleaned.size(); i++) {
                    if (i > 0) w.append(",");
                    w.append("?");
                    args.add(cleaned.get(i));
                }
                w.append(") ");
            }
        } else if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            w.append(" AND upper(COALESCE(payload->>'status','')) = ? ");
            args.add(status.toUpperCase());
        }
        // repledged=true → only rows with a non-empty repledge_bill_id;
        // repledged=false → only rows WITHOUT one. Anything else: no filter.
        if ("true".equalsIgnoreCase(repledged)) {
            w.append(" AND COALESCE(payload->>'repledge_bill_id','') <> '' ");
        } else if ("false".equalsIgnoreCase(repledged)) {
            w.append(" AND COALESCE(payload->>'repledge_bill_id','') = '' ");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            w.append(" AND payload->>'opening_date' >= ? "); args.add(dateFrom);
        }
        if (dateTo != null && !dateTo.isBlank()) {
            w.append(" AND payload->>'opening_date' <= ? "); args.add(dateTo);
        }
        if (customerName != null && !customerName.isBlank()) {
            w.append(" AND payload->>'customer_name' ILIKE ? ");
            args.add("%" + customerName + "%");
        }
        if (amountFrom != null) {
            w.append(" AND CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                     "          THEN (payload->>'amount')::numeric ELSE 0 END >= ? ");
            args.add(amountFrom);
        }
        if (amountTo != null) {
            w.append(" AND CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                     "          THEN (payload->>'amount')::numeric ELSE 0 END <= ? ");
            args.add(amountTo);
        }
        return new WhereBuild(w.toString(), args);
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
