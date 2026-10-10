package com.magizhchi.cloud.owed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Bill Closing would ask for an open bill today.
 *
 * <p>A port of the desktop's {@code reports.OwedToday}, which is itself
 * Bill Closing's own arithmetic lifted out so the 80/20 screen and
 * Stock Details could reuse it. The steps, in order: the months (or
 * days) taken, the shop's close formula evaluated over them, a notice
 * charge if the bill is old enough, and a fine if it has run past a
 * slab. Then:
 *
 * <pre>To Get = principal + interest - advances + notice + fine</pre>
 *
 * <p>The card-lost charge is left out, exactly as on the desktop: it
 * depends on the customer turning up without their copy.
 *
 * <p><b>It answers nothing rather than guessing.</b> {@link #of} returns
 * null when the shop's settings have not reached the cloud, when the
 * formula is not arithmetic this can evaluate, or when the company is
 * RE+ - those shops price per customer out of {@code customer_pricing},
 * which does not sync yet. A blank cell is recoverable; a wrong rupee
 * figure on a phone in a shop is not.
 *
 * <p>Every settings table is read once per instance and held: a stock
 * list asks the same handful of questions for thousands of bills.
 */
public final class OwedToday {

    /** The interest taken, and the whole To Get amount. */
    public static final class Owed {
        public final double interest;
        public final double toGet;
        Owed(double interest, double toGet) { this.interest = interest; this.toGet = toGet; }
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final String companyId;
    private final LocalDate asOf;

    private final String interestType;        // MONTH | DAY
    private final LocalDate noticeDate;
    private final double noticeCharge;
    private final boolean rePlus;

    private final List<JsonNode> reductions;  // company_reduce_months_or_days
    private final List<JsonNode> formulas;    // company_formula (CLOSE)
    private final List<JsonNode> monthRules;  // company_month_setting
    private final List<JsonNode> fines;       // fine_charges

    /** Why this instance cannot answer, or null when it can. */
    private final String blocked;

    public OwedToday(JdbcTemplate j, String companyId, LocalDate asOf) {
        this.companyId = companyId;
        this.asOf = asOf;

        JsonNode company = null;
        for (JsonNode c : read(j, "company")) {
            if (companyId.equals(txt(c, "id"))) { company = c; break; }
        }
        this.reductions = forCompany(read(j, "company_reduce_months_or_days"));
        this.monthRules = forCompany(read(j, "company_month_setting"));
        this.fines      = forCompany(read(j, "fine_charges"));

        List<JsonNode> allFormulas = new ArrayList<>();
        for (JsonNode f : forCompany(read(j, "company_formula"))) {
            if ("CLOSE".equalsIgnoreCase(txt(f, "formula_operation_type"))) allFormulas.add(f);
        }
        this.formulas = allFormulas;

        this.interestType = company == null ? null : txt(company, "day_or_monthly_interest");
        this.noticeDate   = company == null ? null : date(txt(company, "notice_charge_date"));
        this.noticeCharge = company == null ? 0 : dbl(company, "notice_charge_amount");
        this.rePlus       = company != null && "RE+".equalsIgnoreCase(txt(company, "type"));

        if (company == null)            this.blocked = "the company row has not reached the cloud";
        else if (rePlus)                this.blocked = "RE+ shops price per customer, and customer_pricing does not sync yet";
        else if (interestType == null)  this.blocked = "the company has no day/monthly interest setting";
        else if (formulas.isEmpty())    this.blocked = "no CLOSE formula has reached the cloud";
        else                            this.blocked = null;
    }

    /** Null when nothing can be computed, with {@link #why()} saying so. */
    public String why() { return blocked; }
    public boolean usable() { return blocked == null; }

    /**
     * @param amount   the principal on the bill
     * @param rate     the bill's interest, which is a PERCENTAGE
     * @param advance  total advance already paid against the bill
     * @return the figures, or null if this shop cannot be computed
     */
    public Owed of(String material, LocalDate opened, double amount,
                   double rate, double documentCharge, double advance) {
        if (blocked != null || opened == null || material == null) return null;

        String[] reduce  = rule(material, "REDUCTION");
        String[] minimum = rule(material, "MINIMUM");

        long totalDays = MonthMath.days(opened, asOf);
        double takenMonths = 0, takenDays = 0, taken = 0;

        if ("MONTH".equals(interestType)) {
            long[] actual = MonthMath.chettinad(opened, asOf);
            long[] t = null;
            if ("MONTHS FROM TOTAL MONTH".equals(reduce[1])) {
                t = MonthMath.monthsLessTotalMonths(actual, intOf(reduce[0]));
            } else if ("MONTHS FROM OPENING MONTH".equals(reduce[1])) {
                t = MonthMath.monthsLessOpeningMonths(opened, totalDays, intOf(reduce[0]));
            } else if ("DAYS".equals(reduce[1])) {
                t = MonthMath.monthsLessDays(opened, totalDays, intOf(reduce[0]));
            }
            if (t == null) return null;             // no reduction rule: the desktop would show 0 months
            double fraction = actual[0] > 0 ? remainingDaysAsMonths(material, t[1]) : 0;
            takenMonths = t[0] + fraction;
            taken = takenMonths;
        } else if ("DAY".equals(interestType)) {
            long days = -1;
            if ("MONTHS FROM OPENING MONTH".equals(reduce[1])) {
                days = MonthMath.daysLessMonths(opened, totalDays, intOf(reduce[0]));
            } else if ("DAYS".equals(reduce[1])) {
                days = MonthMath.daysLessDays(totalDays, intOf(reduce[0]));
            }
            if (days < 0) return null;
            if ("MONTHS FROM OPENING MONTH".equals(minimum[1])
                    && !MonthMath.monthMinimumMet(opened, days, intOf(minimum[0]))) {
                days = MonthMath.daysInMonths(opened, intOf(minimum[0]));
            } else if ("DAYS".equals(minimum[1])
                    && !MonthMath.dayMinimumMet(days, intOf(minimum[0]))) {
                days = intOf(minimum[0]);
            }
            takenDays = days;
            taken = days;
        } else {
            return null;
        }

        String formula = closeFormula(material, amount);
        if (formula == null) return null;

        double interest;
        try {
            interest = Math.round(Formula.eval(formula, amount, rate, documentCharge,
                                               takenMonths, takenDays));
        } catch (Formula.Unsupported e) {
            return null;                            // say nothing rather than something wrong
        }

        double notice = 0;
        if (noticeDate != null && !opened.isAfter(noticeDate)) notice = noticeCharge;

        double fine = 0;
        JsonNode slab = fineSlab(material, taken);
        if (slab != null && interestType.equals(txt(slab, "interest_type"))) {
            Double fineMonths = null;
            String how = txt(slab, "calculation_method");
            if ("ALL MONTHS".equals(how)) {
                fineMonths = takenMonths;
            } else if ("REMAINING MONTHS".equals(how)) {
                Double from = fineStart(material);
                if (from != null) fineMonths = takenMonths - from;
            }
            if (fineMonths != null) {
                try {
                    fine = Math.round(Formula.eval(formula, amount, dbl(slab, "charged_interest"),
                                                   0, fineMonths, takenDays));
                } catch (Formula.Unsupported e) {
                    return null;
                }
            }
        }

        return new Owed(interest, amount + interest - advance + notice + fine);
    }

    // ── the shop's settings ──────────────────────────────────────────────

    /** {days_or_months, reduction_type} for a material, or {"0", null}. */
    private String[] rule(String material, String type) {
        for (JsonNode r : reductions) {
            if (material.equalsIgnoreCase(txt(r, "jewel_material_type"))
                    && type.equalsIgnoreCase(txt(r, "reduction_or_minimum_type"))) {
                String n = txt(r, "days_or_months");
                return new String[] { n == null ? "0" : n, txt(r, "reduction_type") };
            }
        }
        return new String[] { "0", null };
    }

    /** The first formula whose material, amount band and date range fit. */
    private String closeFormula(String material, double amount) {
        for (JsonNode f : formulas) {
            if (!material.equalsIgnoreCase(txt(f, "jewel_material_type"))) continue;
            if (amount < dbl(f, "amount_from") || amount > dbl(f, "amount_to")) continue;
            if (!within(txt(f, "date_from"), txt(f, "date_to"), asOf)) continue;
            return txt(f, "formula");
        }
        return null;
    }

    /** Leftover days expressed as a fraction of a month. */
    private double remainingDaysAsMonths(String material, double days) {
        for (JsonNode m : monthRules) {
            if (!material.equalsIgnoreCase(txt(m, "jewel_material_type"))) continue;
            if (days < dbl(m, "days_from") || days > dbl(m, "days_to")) continue;
            if (!within(txt(m, "date_from"), txt(m, "date_to"), asOf)) continue;
            return dbl(m, "as_month");
        }
        return 0;
    }

    private JsonNode fineSlab(String material, double months) {
        for (JsonNode f : fines) {
            if (!material.equalsIgnoreCase(txt(f, "jewel_material_type"))) continue;
            if (months >= dbl(f, "month_days_from") && months <= dbl(f, "month_days_to")) return f;
        }
        return null;
    }

    /** The lowest slab start for a material - where "remaining" counts from. */
    private Double fineStart(String material) {
        Double lowest = null;
        for (JsonNode f : fines) {
            if (!material.equalsIgnoreCase(txt(f, "jewel_material_type"))) continue;
            double from = dbl(f, "month_days_from");
            if (lowest == null || from < lowest) lowest = from;
        }
        return lowest;
    }

    // ── reading projections ──────────────────────────────────────────────

    private List<JsonNode> forCompany(List<JsonNode> rows) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode r : rows) if (companyId.equals(txt(r, "company_id"))) out.add(r);
        return out;
    }

    private static List<JsonNode> read(JdbcTemplate j, String table) {
        List<JsonNode> out = new ArrayList<>();
        List<Map<String, Object>> rows = j.queryForList(
                "SELECT payload FROM projections WHERE table_name = ? AND NOT deleted", table);
        for (Map<String, Object> r : rows) {
            Object p = r.get("payload");
            String s = p instanceof PGobject pg ? pg.getValue() : (p == null ? null : p.toString());
            if (s == null) continue;
            try { out.add(M.readTree(s)); } catch (Exception ignored) { }
        }
        return out;
    }

    private static String txt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static double dbl(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return 0;
        try { return Double.parseDouble(v.asText()); } catch (NumberFormatException e) { return 0; }
    }

    private static int intOf(String s) {
        try { return (int) Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, ISO); }
        catch (Exception e) { return null; }
    }

    /** An open-ended range counts as covering the day. */
    private static boolean within(String from, String to, LocalDate day) {
        LocalDate f = date(from), t = date(to);
        if (f != null && day.isBefore(f)) return false;
        return t == null || !day.isAfter(t);
    }

    /** Settings cache keyed per company, so a list does not re-read them. */
    public static final class Cache {
        private final Map<String, OwedToday> byCompany = new HashMap<>();
        public OwedToday get(JdbcTemplate j, String companyId, LocalDate asOf) {
            return byCompany.computeIfAbsent(companyId, c -> new OwedToday(j, c, asOf));
        }
    }
}
