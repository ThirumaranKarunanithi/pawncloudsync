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
                // Mirrors the desktop's MIS query: three UNION ALL legs for
                // pawn (openings, NOT CANCELED), redeem (closings in CLOSED/
                // DELIVERED/REBILLED-*), and interest (todays_pf_amount from
                // company_todays_account_available_amount). Outer SELECT uses
                // a window for running cumulative stock_bills/stock_amount;
                // per-month difference becomes earnedBills/earnedAmount.
                // Gold/Silver breakdown is appended (FILTERed sums in the
                // pawn leg) so the home chart's Gold + Silver lines still
                // render — desktop SQL doesn't track this; we add it.
                String companyPredicate = companyFilter == null
                        ? "" : " AND payload->>'company_id' = ? ";
                // Each of pawn / redeem / interest legs gets the company
                // filter parameter, so we pass it 3 times when present.
                List<Object> args = new java.util.ArrayList<>();
                if (companyFilter != null) {
                    args.add(companyFilter);  // pawn
                    args.add(companyFilter);  // redeem
                    args.add(companyFilter);  // interest
                }
                args.add(cap);

                String sql =
                    "SELECT month, " +
                    "       pawn_total_bill         AS \"pawnBills\", " +
                    "       pawn_amount             AS \"pawnAmount\", " +
                    "       gold_total_bill         AS \"goldBills\", " +
                    "       silver_total_bill       AS \"silverBills\", " +
                    "       gold_amount             AS \"goldAmount\", " +
                    "       silver_amount           AS \"silverAmount\", " +
                    "       redeem_total_bills      AS \"redeemBills\", " +
                    "       redeem_amt              AS \"redeemAmount\", " +
                    "       tot_profit              AS \"profit\", " +
                    "       total_stock_bills       AS \"stockBills\", " +
                    "       total_stock_amount      AS \"stockAmount\", " +
                    "       (pawn_total_bill - redeem_total_bills)  AS \"earnedBills\", " +
                    "       (pawn_amount     - redeem_amt)          AS \"earnedAmount\" " +
                    "  FROM ( " +
                    "  SELECT mon, yyyy, " +
                    "         CASE WHEN mon='01' THEN 'JAN' WHEN mon='02' THEN 'FEB' " +
                    "              WHEN mon='03' THEN 'MAR' WHEN mon='04' THEN 'APR' " +
                    "              WHEN mon='05' THEN 'MAY' WHEN mon='06' THEN 'JUN' " +
                    "              WHEN mon='07' THEN 'JUL' WHEN mon='08' THEN 'AUG' " +
                    "              WHEN mon='09' THEN 'SEP' WHEN mon='10' THEN 'OCT' " +
                    "              WHEN mon='11' THEN 'NOV' WHEN mon='12' THEN 'DEC' " +
                    "              ELSE mon END || '-' || yyyy AS month, " +
                    "         SUM(pawn_total_bill)   AS pawn_total_bill, " +
                    "         SUM(pawn_amount)       AS pawn_amount, " +
                    "         SUM(gold_total_bill)   AS gold_total_bill, " +
                    "         SUM(silver_total_bill) AS silver_total_bill, " +
                    "         SUM(gold_amount)       AS gold_amount, " +
                    "         SUM(silver_amount)     AS silver_amount, " +
                    "         SUM(redeem_total_bills) AS redeem_total_bills, " +
                    "         SUM(redeem_amt)         AS redeem_amt, " +
                    "         SUM(interest)           AS tot_profit, " +
                    "         SUM(SUM(pawn_total_bill) - SUM(redeem_total_bills)) " +
                    "           OVER (ORDER BY yyyy ASC, mon ASC) AS total_stock_bills, " +
                    "         SUM(SUM(pawn_amount)     - SUM(redeem_amt)) " +
                    "           OVER (ORDER BY yyyy ASC, mon ASC) AS total_stock_amount " +
                    "    FROM ( " +
                    // ── 1. pawn (openings, NOT CANCELED) ───────────────────
                    "      SELECT " +
                    "        substring(payload->>'opening_date' from 6 for 2) AS mon, " +
                    "        substring(payload->>'opening_date' from 1 for 4) AS yyyy, " +
                    "        count(*)                                                          AS pawn_total_bill, " +
                    "        sum(CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "                 THEN (payload->>'amount')::numeric ELSE 0 END)          AS pawn_amount, " +
                    "        count(*) FILTER (WHERE upper(payload->>'jewel_material_type')='GOLD')   AS gold_total_bill, " +
                    "        count(*) FILTER (WHERE upper(payload->>'jewel_material_type')='SILVER') AS silver_total_bill, " +
                    "        sum(CASE WHEN upper(payload->>'jewel_material_type')='GOLD' " +
                    "                   AND payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "                 THEN (payload->>'amount')::numeric ELSE 0 END)          AS gold_amount, " +
                    "        sum(CASE WHEN upper(payload->>'jewel_material_type')='SILVER' " +
                    "                   AND payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "                 THEN (payload->>'amount')::numeric ELSE 0 END)          AS silver_amount, " +
                    "        0 AS redeem_total_bills, 0 AS redeem_amt, 0 AS interest " +
                    "      FROM projections " +
                    "      WHERE table_name='company_billing' AND NOT deleted " +
                    "        AND payload->>'opening_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                    "        AND upper(COALESCE(payload->>'status','')) NOT IN ('CANCELED','CANCELLED') " +
                            companyPredicate +
                    "      GROUP BY 1,2 " +
                    "      UNION ALL " +
                    // ── 2. redeem (closings in CLOSED / DELIVERED / REBILLED-*) ─
                    "      SELECT " +
                    "        substring(payload->>'closing_date' from 6 for 2) AS mon, " +
                    "        substring(payload->>'closing_date' from 1 for 4) AS yyyy, " +
                    "        0, 0, 0, 0, 0, 0, " +
                    "        count(*)                                                          AS redeem_total_bills, " +
                    "        sum(CASE WHEN payload->>'amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "                 THEN (payload->>'amount')::numeric ELSE 0 END)          AS redeem_amt, " +
                    "        0 AS interest " +
                    "      FROM projections " +
                    "      WHERE table_name='company_billing' AND NOT deleted " +
                    "        AND payload->>'closing_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                    "        AND upper(COALESCE(payload->>'status','')) IN " +
                    "            ('CLOSED','DELIVERED','REBILLED','REBILLED-ADDED','REBILLED-REMOVED','REBILLED-MULTIPLE') " +
                            companyPredicate +
                    "      GROUP BY 1,2 " +
                    "      UNION ALL " +
                    // ── 3. interest (todays_pf_amount per todays_date) ─────
                    "      SELECT " +
                    "        substring(payload->>'todays_date' from 6 for 2) AS mon, " +
                    "        substring(payload->>'todays_date' from 1 for 4) AS yyyy, " +
                    "        0, 0, 0, 0, 0, 0, 0, 0, " +
                    "        sum(CASE WHEN payload->>'todays_pf_amount' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "                 THEN (payload->>'todays_pf_amount')::numeric ELSE 0 END) AS interest " +
                    "      FROM projections " +
                    "      WHERE table_name='company_todays_account_available_amount' AND NOT deleted " +
                    "        AND payload->>'todays_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                            companyPredicate +
                    "      GROUP BY 1,2 " +
                    "    ) chi " +
                    "    GROUP BY chi.mon, chi.yyyy " +
                    "  ) child " +
                    "  ORDER BY yyyy DESC, mon DESC " +
                    "  LIMIT ?";

                List<Map<String,Object>> months = j.queryForList(sql, args.toArray());
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
