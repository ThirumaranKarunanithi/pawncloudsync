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
                                          @RequestParam(name="amountTo",   required=false) Double amountTo,
                                          @RequestParam(name="refMark",    required=false) String refMark,
                                          @RequestParam(name="todaysDate", required=false) String todaysDate) {
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
                                   dateFrom, dateTo, customerName, amountFrom, amountTo,
                                   refMark, todaysDate);
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
                          dateFrom, dateTo, customerName, amountFrom, amountTo,
                          /*refMark*/ null, /*todaysDate*/ null);
    }

    private WhereBuild buildWhere(String table, String q, String companyId,
                                   String material, String status,
                                   String statuses, String repledged,
                                   String dateFrom, String dateTo,
                                   String customerName,
                                   Double amountFrom, Double amountTo) {
        return buildWhere(table, q, companyId, material, status,
                          statuses, repledged,
                          dateFrom, dateTo, customerName, amountFrom, amountTo,
                          /*refMark*/ null, /*todaysDate*/ null);
    }

    private WhereBuild buildWhere(String table, String q, String companyId,
                                   String material, String status,
                                   String statuses, String repledged,
                                   String dateFrom, String dateTo,
                                   String customerName,
                                   Double amountFrom, Double amountTo,
                                   String refMark, String todaysDate) {
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
        // ref_mark / todays_date are JSONB-keyed (not whole-payload ILIKE) —
        // robust against either jsonb compact-text or json-with-whitespace
        // storage, which the q-substring trick was not.
        if (refMark != null && !refMark.isBlank()) {
            w.append(" AND upper(COALESCE(payload->>'ref_mark','')) = ? ");
            args.add(refMark.toUpperCase());
        }
        if (todaysDate != null && !todaysDate.isBlank()) {
            // Tolerate timestamps stored alongside dates by left-anchoring.
            w.append(" AND COALESCE(payload->>'todays_date','') LIKE ? ");
            args.add(todaysDate + "%");
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

    /**
     * Full MIS report — one row per (month, jewel_type) with the complete
     * 16-column desktop layout including the repledge legs. Mirrors the
     * user's desktop MIS SQL exactly, adapted to the JSONB projection store:
     *
     *   pawn (openings)  + redeem (closings)            from company_billing
     *   repledge opening + repledge redeem (closings)   from repledge_billing
     *   running cumulative stock (bills+amount) for both bill and repledge
     *
     * Repledge columns are emitted only for GOLD rows (desktop convention).
     * companyId optionally narrows to one company; omit for all in the shop.
     */
    @GetMapping("/mis-report")
    public Map<String,Object> misReport(
            @RequestParam(name="companyId", required=false) String companyId,
            @RequestParam(defaultValue="240") int limit) {
        int cap = Math.min(Math.max(limit, 1), 600);
        final String companyFilter = (companyId == null || companyId.isBlank()
                                      || "ALL".equalsIgnoreCase(companyId))
                ? null : companyId;
        try {
            return t.inTenant(j -> {
                String comp = companyFilter == null ? "" : " AND payload->>'company_id' = ? ";
                // numeric-coercion helper for a billing/repledge field
                java.util.function.Function<String,String> num = f ->
                    "(CASE WHEN payload->>'" + f + "' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                    "THEN (payload->>'" + f + "')::numeric ELSE 0 END)";

                String openMon  = "substring(payload->>'opening_date' from 6 for 2)";
                String openYr   = "substring(payload->>'opening_date' from 1 for 4)";
                String closeMon = "substring(payload->>'closing_date' from 6 for 2)";
                String closeYr  = "substring(payload->>'closing_date' from 1 for 4)";
                String redeemStatuses =
                    "('CLOSED','DELIVERED','REBILLED','REBILLED-REMOVED','REBILLED-ADDED','REBILLED-MULTIPLE')";

                StringBuilder leg = new StringBuilder();
                // ── helper to emit a bill pawn/redeem leg for a material ──
                // pawn leg
                java.util.function.BiFunction<String,Boolean,String> billLeg = (mat, isPawn) -> {
                    if (isPawn) {
                        return
                        "SELECT " + openMon + " mon, " + openYr + " yyyy, '" + mat + "' jwl_type, " +
                        "  count(*) pawn_total_bill, sum(" + num.apply("amount") + ") pawn_amount, " +
                        "  0 redeem_total_bills, 0 redeem_amt, " +
                        "  sum(" + num.apply("open_taken_amount") + ") interest, " +
                        "  0 repledge_total_bill, 0 repledge_amount, " +
                        "  0 repledge_redeem_total_bills, 0 repledge_redeem_amt, 0 repledge_interest " +
                        "FROM projections WHERE table_name='company_billing' AND NOT deleted " +
                        "  AND payload->>'opening_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                        "  AND upper(payload->>'jewel_material_type')='" + mat + "' " +
                        "  AND upper(COALESCE(payload->>'status','')) NOT IN ('CANCELED','CANCELLED') " +
                        comp + " GROUP BY 1,2,3 ";
                    } else {
                        return
                        "SELECT " + closeMon + " mon, " + closeYr + " yyyy, '" + mat + "' jwl_type, " +
                        "  0 pawn_total_bill, 0 pawn_amount, " +
                        "  count(*) redeem_total_bills, sum(" + num.apply("amount") + ") redeem_amt, " +
                        "  ((sum(" + num.apply("close_taken_amount") + ")+sum(" + num.apply("total_other_charges") +
                        "  ))-sum(" + num.apply("discount_amount") + ")) interest, " +
                        "  0 repledge_total_bill, 0 repledge_amount, " +
                        "  0 repledge_redeem_total_bills, 0 repledge_redeem_amt, 0 repledge_interest " +
                        "FROM projections WHERE table_name='company_billing' AND NOT deleted " +
                        "  AND payload->>'closing_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                        "  AND upper(payload->>'jewel_material_type')='" + mat + "' " +
                        "  AND upper(COALESCE(payload->>'status','')) IN " + redeemStatuses +
                        comp + " GROUP BY 1,2,3 ";
                    }
                };

                String repledgeOpen =
                    "SELECT " + openMon + " mon, " + openYr + " yyyy, 'GOLD' jwl_type, " +
                    "  0 pawn_total_bill, 0 pawn_amount, 0 redeem_total_bills, 0 redeem_amt, 0 interest, " +
                    "  count(*) repledge_total_bill, sum(" + num.apply("amount") + ") repledge_amount, " +
                    "  0 repledge_redeem_total_bills, 0 repledge_redeem_amt, " +
                    "  sum(" + num.apply("open_taken_amount") + ") repledge_interest " +
                    "FROM projections WHERE table_name='repledge_billing' AND NOT deleted " +
                    "  AND payload->>'opening_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                    "  AND upper(COALESCE(payload->>'jewel_material_type',''))='GOLD' " +
                    comp + " GROUP BY 1,2,3 ";

                String repledgeClose =
                    "SELECT " + closeMon + " mon, " + closeYr + " yyyy, 'GOLD' jwl_type, " +
                    "  0 pawn_total_bill, 0 pawn_amount, 0 redeem_total_bills, 0 redeem_amt, 0 interest, " +
                    "  0 repledge_total_bill, 0 repledge_amount, " +
                    "  count(*) repledge_redeem_total_bills, sum(" + num.apply("amount") + ") repledge_redeem_amt, " +
                    "  (sum(" + num.apply("given_amount") + ")-sum(" + num.apply("amount") + ")) repledge_interest " +
                    "FROM projections WHERE table_name='repledge_billing' AND NOT deleted " +
                    "  AND payload->>'closing_date' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' " +
                    "  AND upper(COALESCE(payload->>'jewel_material_type',''))='GOLD' " +
                    comp + " GROUP BY 1,2,3 ";

                String unionAll =
                    billLeg.apply("GOLD", true)   + " UNION ALL " +
                    billLeg.apply("GOLD", false)  + " UNION ALL " +
                    billLeg.apply("SILVER", true) + " UNION ALL " +
                    billLeg.apply("SILVER", false)+ " UNION ALL " +
                    repledgeOpen + " UNION ALL " + repledgeClose;

                // arg order matches the 6 legs that carry the company predicate
                List<Object> args = new java.util.ArrayList<>();
                if (companyFilter != null) for (int i = 0; i < 6; i++) args.add(companyFilter);
                args.add(cap);

                String monthName =
                    "CASE child.mon WHEN '01' THEN 'JAN' WHEN '02' THEN 'FEB' WHEN '03' THEN 'MAR' " +
                    "WHEN '04' THEN 'APR' WHEN '05' THEN 'MAY' WHEN '06' THEN 'JUN' WHEN '07' THEN 'JUL' " +
                    "WHEN '08' THEN 'AUG' WHEN '09' THEN 'SEP' WHEN '10' THEN 'OCT' WHEN '11' THEN 'NOV' " +
                    "WHEN '12' THEN 'DEC' ELSE child.mon END || '-' || child.yyyy";

                String sql =
                    "SELECT " + monthName + " AS month, child.jwl_type AS \"jwlType\", " +
                    "  pawn_total_bill AS \"pawnBills\", pawn_amount AS \"pawnAmount\", " +
                    "  redeem_total_bills AS \"redeemBills\", redeem_amt AS \"redeemAmount\", " +
                    "  interest AS \"interest\", " +
                    "  (CASE WHEN child.jwl_type='GOLD' THEN total_repledge_bills ELSE 0 END) AS \"repledgeBills\", " +
                    "  (CASE WHEN child.jwl_type='GOLD' THEN total_repledge_amount ELSE 0 END) AS \"repledgeAmount\", " +
                    "  total_repledge_redeem_total_bills AS \"repledgeRedeemBills\", " +
                    "  total_repledge_redeem_amt AS \"repledgeRedeemAmount\", " +
                    "  (CASE WHEN child.jwl_type='GOLD' THEN total_repledge_interest ELSE 0 END) AS \"repledgeInterest\", " +
                    "  (CASE WHEN child.jwl_type='GOLD' THEN total_repledge_stock_bills ELSE 0 END) AS \"repledgeStockBills\", " +
                    "  (CASE WHEN child.jwl_type='GOLD' THEN total_repledge_stock_amount ELSE 0 END) AS \"repledgeStockAmount\", " +
                    "  total_stock_bills AS \"stockBills\", total_stock_amount AS \"stockAmount\", " +
                    "  child.yyyy AS yyyy, child.mon AS mon " +
                    "FROM ( " +
                    "  SELECT mon, yyyy, jwl_type, " +
                    "    sum(pawn_total_bill) pawn_total_bill, sum(pawn_amount) pawn_amount, " +
                    "    sum(redeem_total_bills) redeem_total_bills, sum(redeem_amt) redeem_amt, " +
                    "    sum(interest) interest, " +
                    "    sum(repledge_total_bill) total_repledge_bills, sum(repledge_amount) total_repledge_amount, " +
                    "    sum(repledge_redeem_total_bills) total_repledge_redeem_total_bills, " +
                    "    sum(repledge_redeem_amt) total_repledge_redeem_amt, " +
                    "    sum(repledge_interest) total_repledge_interest, " +
                    "    sum(sum(repledge_total_bill)-sum(repledge_redeem_total_bills)) " +
                    "      OVER (ORDER BY yyyy ASC, mon, jwl_type) total_repledge_stock_bills, " +
                    "    sum(sum(repledge_amount)-sum(repledge_redeem_amt)) " +
                    "      OVER (ORDER BY yyyy ASC, mon, jwl_type) total_repledge_stock_amount, " +
                    "    sum(sum(pawn_total_bill)-sum(redeem_total_bills)) " +
                    "      OVER (ORDER BY yyyy ASC, mon, jwl_type) total_stock_bills, " +
                    "    sum(sum(pawn_amount)-sum(redeem_amt)) " +
                    "      OVER (ORDER BY yyyy ASC, mon, jwl_type) total_stock_amount " +
                    "  FROM ( " + unionAll + " ) chi " +
                    "  GROUP BY chi.mon, chi.yyyy, chi.jwl_type " +
                    ") child " +
                    "ORDER BY child.yyyy DESC, child.mon DESC, child.jwl_type " +
                    "LIMIT ?";

                List<Map<String,Object>> rows = j.queryForList(sql, args.toArray());
                return Map.of("total", rows.size(), "rows", rows);
            });
        } catch (Exception e) {
            log.error("misReport failed: {}", e.toString(), e);
            return Map.of("total", 0, "rows", List.of(),
                          "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Operations breakdown for the Today's Account screen — matches the
     * desktop's 10-row fixed grid exactly. Always returns ALL 10 rows even
     * when zero, so the layout stays stable. Each row carries a "combo"
     * string with the per-field breakdown the desktop shows in its right
     * column.
     *
     * Debit  = money OUT of the shop (bill opening, repledge closing,
     *          expenses, advance paid).
     * Credit = money IN to the shop  (bill closing, repledge opening,
     *          incomes).
     *
     * Returns: { operations: [ {name, count, debit, credit, combo}, ... ],
     *            totalDebit, totalCredit,
     *            goldPf, silverPf, totalPf }
     */
    @GetMapping("/todays-account-ops")
    public Map<String,Object> todaysAccountOps(
            @RequestParam(name="companyId", required=false) String companyId,
            @RequestParam(name="date")     String date) {
        if (date == null || date.isBlank())
            throw new IllegalArgumentException("date is required (yyyy-MM-dd)");
        final String compFilter = (companyId == null || companyId.isBlank()
                                   || "ALL".equalsIgnoreCase(companyId))
                ? null : companyId;
        return t.inTenant(j -> {
            java.util.List<Map<String,Object>> ops = new java.util.ArrayList<>();
            double totalDebit  = 0;
            double totalCredit = 0;

            // ── Rows 1, 3 : GOLD/SILVER BILL OPENING (debit) ─────────────
            String notCanceled = " AND upper(COALESCE(payload->>'status','')) " +
                                 " NOT IN ('CANCELED','CANCELLED') ";
            String closedStatus = " AND upper(COALESCE(payload->>'status','')) IN " +
                "('CLOSED','DELIVERED','REBILLED','REBILLED-ADDED','REBILLED-REMOVED','REBILLED-MULTIPLE') ";

            for (String mat : new String[]{"GOLD","SILVER"}) {
                Map<String,Object> openR = billOpeningAgg(j, date, compFilter, mat, notCanceled);
                long   cnt   = num(openR, "cnt").longValue();
                double amt   = num(openR, "amt").doubleValue();
                double taken = num(openR, "taken").doubleValue();
                double doc   = num(openR, "doc").doubleValue();
                long   rb    = num(openR, "rb").longValue();
                long   nb    = num(openR, "nb").longValue();
                // Opening Debit  = sum(amount)            (principal lent).
                // Opening Credit = sum(open_taken_amount) (cash received).
                // Combo Intr     = taken − doc            (derived from the
                //                                          same cash flow).
                double rowDebit  = amt;
                double rowCredit = taken;
                double intr      = taken - doc;
                ops.add(opRow(mat + " BILL OPENING", cnt, rowDebit, rowCredit,
                        "( Amt: " + b(amt) + ", Intr: " + b(intr) + ", Doc: " + b(doc) + " )"
                        + "  ( RB: " + rb + ", NB: " + nb + " )"));
                totalDebit  += rowDebit;
                totalCredit += rowCredit;

                // ── Rows 2, 5 : GOLD/SILVER BILL ADVANCE AMOUNT (debit) ──
                // company_advance_amount rows are joined to their parent bill
                // by bill_number, which carries the material. We do the
                // material-pivot inline as a sub-query to keep it one round-trip.
                Map<String,Object> advR = advanceByMaterialAgg(j, date, compFilter, mat);
                long   aCnt = num(advR, "cnt").longValue();
                double aAmt = num(advR, "amt").doubleValue();
                ops.add(opRow(mat + " BILL ADVANCE AMOUNT", aCnt, aAmt, 0, ""));
                totalDebit += aAmt;

                // ── Rows 3, 6 : GOLD/SILVER BILL CLOSING (credit) ────────
                Map<String,Object> closeR = billClosingAgg(j, date, compFilter, mat, closedStatus);
                long   cCnt  = num(closeR, "cnt").longValue();
                double cAmt  = num(closeR, "amt").doubleValue();
                double cIntr = num(closeR, "intr").doubleValue();   // close_taken_amount
                double cFine = num(closeR, "fine").doubleValue();   // total_other_charges
                double cLess = num(closeR, "less").doubleValue();   // discount_amount
                double cAdv  = num(closeR, "adv").doubleValue();
                // Desktop formula: amt + intr + fine − less + adv. Matches
                // GOLD (65000+1200+0−0+0 = 66200) and SILVER (5200+3448+
                // 333−1+0 = 8980).
                double closingCredit = cAmt + cIntr + cFine - cLess + cAdv;
                ops.add(opRow(mat + " BILL CLOSING", cCnt, 0, closingCredit,
                        "( Amt: " + b(cAmt) + ", Intr: " + b(cIntr)
                        + ", Fine: " + n(cFine) + ", Less: " + n(cLess)
                        + ", Adv Amt: " + n(cAdv) + " )"));
                totalCredit += closingCredit;
            }

            // Re-order so we get: GOLD OPENING, GOLD ADV, GOLD CLOSING,
            // SILVER OPENING, SILVER ADV, SILVER CLOSING (desktop order).
            ops = reorderForDesktop(ops);

            // ── Row 7 : REPLEDGE BILL OPENING (credit — financier paid us)
            Map<String,Object> rOpen = repledgeAggRich(j, "opening_date", date, compFilter);
            long   roCnt = num(rOpen, "cnt").longValue();
            double roAmt = num(rOpen, "amt").doubleValue();
            double roInt = num(rOpen, "intr").doubleValue();
            double roDoc = num(rOpen, "doc").doubleValue();
            ops.add(opRow("REPLEDGE BILL OPENING", roCnt, 0, roAmt,
                    "( Amt: " + b(roAmt) + ", Intr: " + b(roInt) + ", Doc: " + b(roDoc) + " )"));
            totalCredit += roAmt;

            // ── Row 8 : REPLEDGE BILL CLOSING (debit — we paid financier)
            Map<String,Object> rClose = repledgeAggRich(j, "closing_date", date, compFilter);
            long   rcCnt = num(rClose, "cnt").longValue();
            double rcAmt = num(rClose, "amt").doubleValue();
            double rcInt = num(rClose, "intr").doubleValue();
            // Desktop closing debit = principal + interest paid to financier.
            double rcDebit = rcAmt + rcInt;
            ops.add(opRow("REPLEDGE BILL CLOSING", rcCnt, rcDebit, 0,
                    "( Amt: " + b(rcAmt) + ", Intr: " + b(rcInt) + " )"));
            totalDebit += rcDebit;

            // ── Rows 9-10 : EXPENSES (debit) / INCOMES (credit) ──────────
            // Expense/income rows live in company_expense_income (one table,
            // type column splits the two). If the table or column doesn't
            // exist on this tenant the helper safely returns zeros.
            Map<String,Object> exp = expenseIncomeAgg(j, date, compFilter, "EXPENSE");
            long   eCnt = num(exp, "cnt").longValue();
            double eAmt = num(exp, "amt").doubleValue();
            ops.add(opRow("EXPENSES", eCnt, eAmt, 0, "0"));
            totalDebit += eAmt;

            Map<String,Object> inc = expenseIncomeAgg(j, date, compFilter, "INCOME");
            long   iCnt = num(inc, "cnt").longValue();
            double iAmt = num(inc, "amt").doubleValue();
            ops.add(opRow("INCOMES", iCnt, 0, iAmt, "0"));
            totalCredit += iAmt;

            // ── Profit bar : Gold Pf, Silver Pf, Total Pf ────────────────
            // Authoritative source is company_todays_account_available_amount.
            // We try todays_pf_amount for the total, gold_pf_amount /
            // silver_pf_amount for the split. If the per-material columns
            // don't exist we fall back to the bill-closing interest split.
            Map<String,Object> pf = profitAgg(j, date, compFilter);
            double goldPf   = num(pf, "gold").doubleValue();
            double silverPf = num(pf, "silver").doubleValue();
            double totalPf  = num(pf, "total").doubleValue();
            if (totalPf == 0 && (goldPf != 0 || silverPf != 0))
                totalPf = goldPf + silverPf;

            Map<String,Object> out = new java.util.LinkedHashMap<>();
            out.put("date",        date);
            out.put("operations",  ops);
            out.put("totalDebit",  totalDebit);
            out.put("totalCredit", totalCredit);
            out.put("goldPf",      goldPf);
            out.put("silverPf",    silverPf);
            out.put("totalPf",     totalPf);
            return out;
        });
    }

    /** Re-order GOLD then SILVER (opening, advance, closing each). */
    private static java.util.List<Map<String,Object>> reorderForDesktop(
            java.util.List<Map<String,Object>> in) {
        java.util.List<Map<String,Object>> out = new java.util.ArrayList<>(in.size());
        for (String mat : new String[]{"GOLD","SILVER"}) {
            for (String suffix : new String[]{" BILL OPENING", " BILL ADVANCE AMOUNT", " BILL CLOSING"}) {
                String want = mat + suffix;
                for (Map<String,Object> r : in)
                    if (want.equals(r.get("name"))) { out.add(r); break; }
            }
        }
        return out;
    }

    // ── per-row aggregation helpers ──────────────────────────────────────

    private Map<String,Object> billOpeningAgg(org.springframework.jdbc.core.JdbcTemplate j,
                                               String date, String companyId,
                                               String material, String statusClause) {
        // Confirmed desktop schema:
        //   amount             — principal lent (Debit)
        //   open_taken_amount  — cash received from customer at opening
        //                        (Intr + Doc charge collected upfront)
        //   document_charge    — the Doc portion of open_taken_amount
        //   `interest` column  — interest RATE per-month (not rupees); we
        //                        derive the rupee Intr as (open_taken − doc).
        StringBuilder sql = new StringBuilder(
            "SELECT count(*)                                                  AS cnt, " +
            "       COALESCE(sum(numF(payload->>'amount')),                0) AS amt, " +
            "       COALESCE(sum(numF(payload->>'open_taken_amount')),     0) AS taken, " +
            "       COALESCE(sum(numF(payload->>'document_charge')),       0) AS doc, " +
            "       count(*) FILTER (WHERE COALESCE(payload->>'repledge_bill_id','') <> '') AS rb, " +
            "       count(*) FILTER (WHERE COALESCE(payload->>'repledge_bill_id','') =  '') AS nb " +
            "  FROM projections " +
            " WHERE table_name = 'company_billing' AND NOT deleted " +
            "   AND COALESCE(payload->>'opening_date','') LIKE ? " +
            "   AND upper(COALESCE(payload->>'jewel_material_type','')) = ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "%");
        args.add(material);
        if (companyId != null) { sql.append(" AND payload->>'company_id' = ? "); args.add(companyId); }
        sql.append(statusClause);
        return queryRowOrZero(j, sql.toString(), args.toArray(),
                "cnt","amt","intr","doc","rb","nb");
    }

    private Map<String,Object> billClosingAgg(org.springframework.jdbc.core.JdbcTemplate j,
                                               String date, String companyId,
                                               String material, String statusClause) {
        // Confirmed desktop schema (closing row):
        //   amount                     — principal returned by customer
        //   close_taken_amount         — INTEREST portion collected
        //                                (NOT the grand total; just intr)
        //   total_other_charges        — late fine / notice / etc. ("Fine")
        //   discount_amount            — discount given     ("Less")
        //   total_advance_amount_paid  — advance already paid ("Adv")
        //
        // Desktop credit formula:
        //   credit = amount + close_taken_amount + total_other_charges
        //          - discount_amount + total_advance_amount_paid
        // The `interest` column is a per-month RATE — never used as rupees.
        StringBuilder sql = new StringBuilder(
            "SELECT count(*)                                                       AS cnt, " +
            "       COALESCE(sum(numF(payload->>'amount')),                     0) AS amt, " +
            "       COALESCE(sum(numF(payload->>'close_taken_amount')),         0) AS intr, " +
            "       COALESCE(sum(numF(payload->>'total_other_charges')),        0) AS fine, " +
            "       COALESCE(sum(numF(payload->>'discount_amount')),            0) AS less, " +
            "       COALESCE(sum(numF(payload->>'total_advance_amount_paid')),  0) AS adv " +
            "  FROM projections " +
            " WHERE table_name = 'company_billing' AND NOT deleted " +
            "   AND COALESCE(payload->>'closing_date','') LIKE ? " +
            "   AND upper(COALESCE(payload->>'jewel_material_type','')) = ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "%");
        args.add(material);
        if (companyId != null) { sql.append(" AND payload->>'company_id' = ? "); args.add(companyId); }
        sql.append(statusClause);
        return queryRowOrZero(j, sql.toString(), args.toArray(),
                "cnt","amt","intr","fine","less","adv");
    }

    private Map<String,Object> advanceByMaterialAgg(org.springframework.jdbc.core.JdbcTemplate j,
                                                     String date, String companyId,
                                                     String material) {
        // Advance rows carry bill_number; join to company_billing to filter
        // by material. company_id stays on the advance itself.
        StringBuilder sql = new StringBuilder(
            "SELECT count(*)                                       AS cnt, " +
            "       COALESCE(sum(numF(a.payload->>'paid_amount')),0) AS amt " +
            "  FROM projections a " +
            "  JOIN projections b " +
            "    ON b.table_name = 'company_billing' AND NOT b.deleted " +
            "   AND b.payload->>'bill_number' = a.payload->>'bill_number' " +
            "   AND upper(COALESCE(b.payload->>'jewel_material_type','')) = ? " +
            " WHERE a.table_name = 'company_advance_amount' AND NOT a.deleted " +
            "   AND COALESCE(a.payload->>'advance_date','') LIKE ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(material);
        args.add(date + "%");
        if (companyId != null) {
            sql.append(" AND a.payload->>'company_id' = ? "); args.add(companyId);
        }
        return queryRowOrZero(j, sql.toString(), args.toArray(), "cnt","amt");
    }

    private Map<String,Object> repledgeAggRich(org.springframework.jdbc.core.JdbcTemplate j,
                                                String dateField, String date,
                                                String companyId) {
        // Column names per the user's desktop schema:
        //   amount, interest, document_charge.
        StringBuilder sql = new StringBuilder(
            "SELECT count(*)                                            AS cnt, " +
            "       COALESCE(sum(numF(payload->>'amount')),          0) AS amt, " +
            "       COALESCE(sum(numF(payload->>'interest')),        0) AS intr, " +
            "       COALESCE(sum(numF(payload->>'document_charge')), 0) AS doc " +
            "  FROM projections " +
            " WHERE table_name = 'repledge_billing' AND NOT deleted " +
            "   AND COALESCE(payload->>'" + dateField + "','') LIKE ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "%");
        if (companyId != null) { sql.append(" AND payload->>'company_id' = ? "); args.add(companyId); }
        return queryRowOrZero(j, sql.toString(), args.toArray(),
                "cnt","amt","intr","doc");
    }

    private Map<String,Object> expenseIncomeAgg(org.springframework.jdbc.core.JdbcTemplate j,
                                                 String date, String companyId,
                                                 String kind) {
        // Desktop schema (confirmed):
        //   EXPENSES → company_other_debit  (debitted_date, debitted_amount)
        //   INCOMES  → company_other_credit (credited_date, credited_amount)
        // The sync pipeline left duplicates on these tables; DISTINCT ON id
        // collapses each row to one even if multiple projections exist.
        boolean isExpense = "EXPENSE".equalsIgnoreCase(kind);
        String table     = isExpense ? "company_other_debit"   : "company_other_credit";
        String dateCol   = isExpense ? "debitted_date"         : "credited_date";
        String amountCol = isExpense ? "debitted_amount"       : "credited_amount";

        StringBuilder sql = new StringBuilder(
            "SELECT count(*)                                            AS cnt, " +
            "       COALESCE(sum(numF(payload->>'" + amountCol + "')),0) AS amt " +
            "  FROM ( " +
            "    SELECT DISTINCT ON (payload->>'id') payload " +
            "      FROM projections " +
            "     WHERE table_name = '" + table + "' AND NOT deleted " +
            "       AND COALESCE(payload->>'" + dateCol + "','') LIKE ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "%");
        if (companyId != null) {
            sql.append(" AND payload->>'company_id' = ? ");
            args.add(companyId);
        }
        sql.append(
            "     ORDER BY payload->>'id', last_updated_at DESC " +
            "  ) sub ");
        try {
            return queryRowOrZero(j, sql.toString(), args.toArray(), "cnt","amt");
        } catch (Exception ignored) {
            Map<String,Object> zero = new java.util.HashMap<>();
            zero.put("cnt", 0L); zero.put("amt", 0d);
            return zero;
        }
    }

    private Map<String,Object> profitAgg(org.springframework.jdbc.core.JdbcTemplate j,
                                          String date, String companyId) {
        // The sync pipeline left duplicate projection rows for older days
        // (composite-PK residue from the V2-era trigger). DISTINCT ON keeps
        // the newest projection per (company_id, todays_date) so each day's
        // Pf is counted once instead of N times.
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(sum(numF(payload->>'gold_pf_amount')),0)   AS gold, " +
            "       COALESCE(sum(numF(payload->>'silver_pf_amount')),0) AS silver, " +
            "       COALESCE(sum(numF(payload->>'todays_pf_amount')),0) AS total " +
            "  FROM ( " +
            "    SELECT DISTINCT ON (payload->>'company_id', payload->>'todays_date') " +
            "           payload " +
            "      FROM projections " +
            "     WHERE table_name = 'company_todays_account_available_amount' " +
            "       AND NOT deleted " +
            "       AND COALESCE(payload->>'todays_date','') LIKE ? ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(date + "%");
        if (companyId != null) { sql.append(" AND payload->>'company_id' = ? "); args.add(companyId); }
        sql.append(
            "     ORDER BY payload->>'company_id', payload->>'todays_date', " +
            "              last_updated_at DESC " +
            "  ) sub ");
        try {
            return queryRowOrZero(j, sql.toString(), args.toArray(),
                    "gold","silver","total");
        } catch (Exception ignored) {
            Map<String,Object> zero = new java.util.HashMap<>();
            zero.put("gold", 0d); zero.put("silver", 0d); zero.put("total", 0d);
            return zero;
        }
    }

    private static Map<String,Object> queryRowOrZero(
            org.springframework.jdbc.core.JdbcTemplate j,
            String sql, Object[] args, String... cols) {
        try {
            return j.queryForMap(numericGuard(sql), args);
        } catch (Exception e) {
            log.warn("op aggregation failed: {}", e.toString());
            Map<String,Object> zero = new java.util.HashMap<>();
            for (String c : cols) zero.put(c, 0);
            return zero;
        }
    }

    /** Rewrites "numF(payload->>'field')" to a robust numeric coercion that
     *  treats non-numeric strings as 0. Keeps the SQL building above readable. */
    private static String numericGuard(String sql) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("numF\\((a\\.)?payload->>'([a-zA-Z0-9_]+)'\\)")
            .matcher(sql);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1) == null ? "" : m.group(1);  // "a." or ""
            String field  = m.group(2);
            String expr =
                "(CASE WHEN " + prefix + "payload->>'" + field + "' ~ '^-?[0-9]+(\\.[0-9]+)?$' " +
                "      THEN (" + prefix + "payload->>'" + field + "')::numeric ELSE 0 END)";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(expr));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Map<String,Object> opRow(String name, long count,
                                             double debit, double credit, String combo) {
        Map<String,Object> r = new java.util.LinkedHashMap<>();
        r.put("name",   name);
        r.put("count",  count);
        r.put("debit",  debit);
        r.put("credit", credit);
        r.put("combo",  combo == null ? "" : combo);
        return r;
    }

    /** Compact-format number: 0 → "0", 12345 → "12345" (no decimals when whole). */
    private static String n(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v))
            return Long.toString((long) v);
        return String.valueOf(v);
    }

    /** Blank-when-zero: matches the desktop's "( Amt: , Intr: , Doc: )"
     *  rendering for opening/repledge rows where the column has no data. */
    private static String b(double v) {
        return v == 0 ? "" : n(v);
    }

    /** Null-safe numeric extractor for JDBC map results. */
    private static Number num(Map<String,Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (o instanceof Number n) return n;
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
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
