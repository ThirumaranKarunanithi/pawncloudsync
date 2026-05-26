package com.magizhchi.cloud.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.magizhchi.cloud.fcm.FcmService;
import com.magizhchi.cloud.tenant.TenantContext;
import com.magizhchi.cloud.tenant.TenantJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/v1/sync")
public class SyncController {
    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final TenantJdbc tenantJdbc;
    private final FcmService fcm;

    public SyncController(TenantJdbc tenantJdbc, FcmService fcm) {
        this.tenantJdbc = tenantJdbc; this.fcm = fcm;
    }

    @PostMapping
    public Map<String,Object> ingest(@RequestBody Map<String,Object> body) {
        String reqShop = (String) body.get("shop_id");
        String ctxShop = TenantContext.get();
        if (ctxShop == null) throw new RuntimeException("no tenant resolved");
        // The API key is authoritative for the tenant. We log a mismatch
        // for visibility but do NOT reject — the body value is metadata
        // from the agent's config; the key already proves the caller's identity.
        if (reqShop != null && !reqShop.equalsIgnoreCase(ctxShop)) {
            log.warn("body.shop_id={} differs from api_key tenant={} — using api_key",
                     reqShop, ctxShop);
        }
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> events = (List<Map<String,Object>>) body.getOrDefault("events", List.of());
        int[] counts = new int[]{0, 0, 0};   // [accepted, duplicates, skipped]
        List<NotificationItem> notifs = new ArrayList<>();

        tenantJdbc.inTenant(jdbc -> {
            for (Map<String,Object> e : events) {
                String eventId = (String) e.get("event_id");
                // Per-event SAVEPOINT so one poison row can't roll back the
                // whole batch (a Postgres tx aborts on any SQLException and
                // refuses all subsequent statements until rollback).
                jdbc.execute((ConnectionCallback<Void>) conn -> {
                    Savepoint sp = conn.setSavepoint();
                    try {
                        applyEvent(jdbc, e, counts, notifs);
                        conn.releaseSavepoint(sp);
                    } catch (Exception ex) {
                        conn.rollback(sp);
                        counts[2]++;
                        log.warn("skipped event {} ({}): {}",
                                 eventId, e.get("table"), ex.getMessage());
                    }
                    return null;
                });
            }
            return null;
        });

        if (!notifs.isEmpty()) fcm.broadcast(ctxShop, notifs);
        return Map.of("accepted", counts[0], "duplicates", counts[1], "skipped", counts[2]);
    }

    private void applyEvent(JdbcTemplate jdbc, Map<String,Object> e,
                            int[] counts, List<NotificationItem> notifs) {
        String eventId = (String) e.get("event_id");
        String table   = (String) e.get("table");
        String op      = (String) e.get("op");
        String rowPk   = (String) e.get("row_pk");
        Object payload = e.get("payload");
        // Some local-DB triggers don't emit row_pk for tables with composite
        // primary keys (e.g. company_advance_amount). Synthesize a stable
        // key from event_id so the projections NOT NULL constraint is
        // satisfied and the row is still queryable. Same event_id always
        // maps to the same synthesized row_pk, so re-delivery is idempotent.
        if (rowPk == null || rowPk.isBlank()) rowPk = "evt:" + eventId;
        Timestamp created = e.get("created_at") != null
                ? Timestamp.from(Instant.parse((String) e.get("created_at")))
                : new Timestamp(System.currentTimeMillis());

        int n = jdbc.update(
            "INSERT INTO events(event_id, table_name, op, row_pk, payload, created_at) " +
            "VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (event_id) DO NOTHING",
            eventId, table, op, rowPk, toJson(payload), created);
        if (n == 0) { counts[1]++; return; }
        counts[0]++;

        if ("D".equals(op)) {
            jdbc.update("UPDATE projections SET deleted = TRUE, last_op = 'D', " +
                        "last_event_id = ?::uuid, last_updated_at = now() " +
                        "WHERE table_name = ? AND row_pk = ?",
                        eventId, table, rowPk);
        } else {
            jdbc.update(
                "INSERT INTO projections(table_name,row_pk,payload,last_op,last_event_id,deleted) " +
                "VALUES (?,?,?::jsonb,?,?::uuid,FALSE) " +
                "ON CONFLICT (table_name,row_pk) DO UPDATE SET " +
                "  payload = EXCLUDED.payload, last_op = EXCLUDED.last_op, " +
                "  last_event_id = EXCLUDED.last_event_id, last_updated_at = now(), " +
                "  deleted = FALSE",
                table, rowPk, toJson(payload), op, eventId);
        }

        NotificationItem ni = humanize(table, op, rowPk, payload);
        jdbc.update("INSERT INTO notifications(event_id, title, body, table_name, row_pk) " +
                    "VALUES (?::uuid, ?, ?, ?, ?)",
                    eventId, ni.title, ni.body, table, rowPk);
        notifs.add(ni);
    }

    private static String toJson(Object o) {
        if (o == null) return "{}";
        if (o instanceof String s) return s;
        if (o instanceof JsonNode j) return j.toString();
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }

    public record NotificationItem(String title, String body, String table, String rowPk, String eventId) {}

    private static NotificationItem humanize(String table, String op, String rowPk, Object payload) {
        String action = switch (op) {
            case "I" -> "created"; case "U" -> "updated"; case "D" -> "deleted";
            default -> "changed";
        };
        String pretty = switch (table) {
            case "bill_opening"     -> "Bill";
            case "bill_closing"     -> "Bill closing";
            case "customer_master"  -> "Customer";
            case "company_master"   -> "Company";
            case "credit"           -> "Credit";
            case "debit"            -> "Debit";
            case "advance_amount"   -> "Advance";
            case "expense_income"   -> "Expense/Income";
            case "stock_details"    -> "Stock";
            default -> table;
        };
        String title = pretty + " " + action + (rowPk != null ? " #" + rowPk : "");
        String body  = summary(payload);
        return new NotificationItem(title, body, table, rowPk, null);
    }

    private static String summary(Object payload) {
        if (!(payload instanceof Map<?,?> raw)) return "";
        @SuppressWarnings("unchecked")
        Map<Object,Object> m = (Map<Object,Object>) raw;
        Object name = firstNonNull(m.get("name"), m.get("customer_name"), m.get("description"));
        Object amt  = firstNonNull(m.get("amount"), m.get("net_amount"), m.get("total"));
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name);
        if (amt != null)  { if (sb.length() > 0) sb.append(" — "); sb.append("₹").append(amt); }
        return sb.length() == 0 ? "Tap to view" : sb.toString();
    }

    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }
}
