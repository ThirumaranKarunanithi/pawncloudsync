package com.magizhchi.cloud.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One shop row, one live projection.
 *
 * The cloud keys a row by the row_pk its shop sends, and that key changed shape as the agent learned each
 * table's real primary key: a debit was "DEBCO1000", then "CMP1|DEBCO1000", then "DEBCO1000|CMP1" once the
 * shop had PRIMARY KEY (id, company_id). Each change re-sent the table under the new key and left the old
 * copies live beside it, so the phone's MIS counted expenses, assets and liabilities two or three times
 * (Karumbalai's October expenses listed eight entries twice). The three day tables did the same when
 * sync_row_id arrived (setup S5c): the old copies have no sync_row_id, the new ones do.
 *
 * Ingest uses these rules to retire older copies as a row arrives under its current key, and the nightly
 * prune sweeps whatever is already there. Retired means deleted = TRUE with last_op 'X': the rows stay in
 * the table, and "UPDATE projections SET deleted = FALSE WHERE last_op = 'X'" puts them back.
 */
public final class StaleCopies {
    private StaleCopies() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shop tables with PRIMARY KEY (id, company_id): company_id + id is one row. */
    static final Set<String> BY_ID = Set.of(
            "company_bill_credit", "company_bill_debit", "company_other_credit", "company_other_debit",
            "employee_advance_amount_credit", "employee_advance_amount_debit", "employee_daily_allowance_debit",
            "employee_other_amount_credit", "employee_other_amount_debit", "employee_salary_amount_debit",
            "repledge_bill_credit", "repledge_bill_debit", "repledge_other_credit", "repledge_other_debit");

    /**
     * Tables keyed by sync_row_id since S5c, and the fields that match an old copy to its new one. These are
     * not unique (two advances on one bill in a day), which is why only copies WITHOUT sync_row_id are ever
     * retired: once a shop sends sync_row_id, every one of its rows has one.
     */
    static final Map<String, List<String>> BY_SURROGATE = new LinkedHashMap<>();
    static {
        BY_SURROGATE.put("company_todays_account", List.of("company_id", "todays_date"));
        BY_SURROGATE.put("company_todays_account_available_amount", List.of("company_id", "todays_date"));
        BY_SURROGATE.put("company_advance_amount", List.of("company_id", "jewel_material_type", "bill_number", "paid_date"));
    }

    /** Metal is written GOLD, Gold and gold over the years; it matches whatever its case. */
    private static final String METAL = "jewel_material_type";

    private static final String RETIRE =
            "UPDATE projections SET deleted = TRUE, last_op = 'X' " +
            "WHERE table_name = ? AND row_pk <> ? AND NOT deleted ";

    /**
     * Retires the older copies of the row that just arrived (inserted, updated or deleted) under rowPk.
     * A delete retires them too, or the old copies would outlive the row the shop removed.
     */
    static int retireOlder(JdbcTemplate jdbc, String table, String rowPk, Object payload) {
        if (table == null || !(BY_ID.contains(table) || BY_SURROGATE.containsKey(table))) return 0;
        JsonNode p = tree(payload);
        if (p == null || !p.isObject()) return 0;

        if (BY_ID.contains(table)) {
            String co = p.path("company_id").asText("");
            if (co.isEmpty() || p.path("id").asText("").isEmpty()) return 0;
            ObjectNode probe = MAPPER.createObjectNode();
            probe.set("id", p.get("id"));
            return jdbc.update(RETIRE + "AND payload @> ?::jsonb AND payload->>'company_id' = ?",
                               table, rowPk, probe.toString(), co);
        }

        if (p.path("sync_row_id").isMissingNode() || p.path("sync_row_id").isNull()) return 0;
        ObjectNode probe = MAPPER.createObjectNode();
        StringBuilder sql = new StringBuilder(RETIRE).append("AND payload->'sync_row_id' IS NULL AND payload @> ?::jsonb ");
        List<Object> args = new ArrayList<>(List.of(table, rowPk));
        String metal = null;
        for (String f : BY_SURROGATE.get(table)) {
            if (METAL.equals(f)) { metal = p.path(f).asText(""); continue; }
            if (p.path(f).asText("").isEmpty()) return 0;          // nothing to match an old copy on
            probe.set(f, p.get(f));
        }
        args.add(probe.toString());
        if (metal != null) {
            sql.append("AND upper(coalesce(payload->>'" + METAL + "', '')) = upper(?) ");
            args.add(metal);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * One shop's sweep: of each row's live copies keep the newest, retire the others. Returns how many it
     * retired (or would, in a dry run). The keyed copy is not trusted over the plain one; whichever arrived
     * last carries the shop's latest values, and ingest retires the rest as soon as the current key is used.
     */
    public static long sweep(JdbcTemplate jdbc, String schema, boolean dryRun) {
        if (schema == null || !schema.matches("[a-z0-9_]+")) return 0;
        String p = "\"" + schema + "\".projections";
        List<String> pick = new ArrayList<>();

        StringBuilder ids = new StringBuilder();
        for (String t : BY_ID) ids.append(ids.length() == 0 ? "" : ",").append('\'').append(t).append('\'');
        pick.add("SELECT table_name, row_pk FROM (" +
                 " SELECT table_name, row_pk, row_number() OVER (" +
                 "   PARTITION BY table_name, payload->>'company_id', payload->>'id'" +
                 "   ORDER BY last_updated_at DESC, row_pk DESC) AS rn" +
                 " FROM " + p + " WHERE NOT deleted AND table_name IN (" + ids + ")" +
                 "   AND coalesce(payload->>'id', '') <> '') r WHERE rn > 1");

        for (Map.Entry<String, List<String>> e : BY_SURROGATE.entrySet()) {
            String nat = natural(e.getValue());
            pick.add("SELECT table_name, row_pk FROM " + p +
                     " WHERE table_name = '" + e.getKey() + "' AND NOT deleted AND payload->'sync_row_id' IS NULL" +
                     "   AND " + nat + " IN (SELECT " + nat + " FROM " + p +
                     "     WHERE table_name = '" + e.getKey() + "' AND NOT deleted AND payload->'sync_row_id' IS NOT NULL)");
        }

        long total = 0;
        for (String sel : pick) {
            if (dryRun) {
                Long n = jdbc.queryForObject("SELECT count(*) FROM (" + sel + ") x", Long.class);
                total += n == null ? 0 : n;
            } else {
                total += jdbc.update("UPDATE " + p + " SET deleted = TRUE, last_op = 'X'" +
                                     " WHERE (table_name, row_pk) IN (" + sel + ")");
            }
        }
        return total;
    }

    private static String natural(List<String> fields) {
        StringBuilder s = new StringBuilder("concat_ws('|'");
        for (String f : fields) {
            String v = "coalesce(payload->>'" + f + "', '')";
            s.append(", ").append(METAL.equals(f) ? "upper(" + v + ")" : v);
        }
        return s.append(')').toString();
    }

    private static JsonNode tree(Object payload) {
        try {
            if (payload == null) return null;
            if (payload instanceof JsonNode j) return j;
            if (payload instanceof String s) return MAPPER.readTree(s);
            return MAPPER.valueToTree(payload);
        } catch (Exception e) {
            return null;
        }
    }
}
