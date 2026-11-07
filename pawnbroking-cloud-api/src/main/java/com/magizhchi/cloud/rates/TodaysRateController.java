package com.magizhchi.cloud.rates;

import com.magizhchi.cloud.tenant.TenantJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's Home card: today's 22 carat gold and silver rate a gram for one company, and its company rate.
 *
 * <p>The rate is the one the shop's desktop keeps in company_other_settings (Company Module -> Other Settings),
 * which it takes from goodreturns.in at the first sign-in of the day (TodaysRate) or which someone typed in.
 * company_settings TODAYS_RATE_LAST says when and for which city it was taken; when the kept rate is not the
 * one it took, someone typed it in the Company Module.
 *
 * <p>When the kept rate is not today's (nobody has signed in on the desktop yet today), the cloud reads
 * goodreturns.in itself and shows that, with the company rate at the company's %, and says so. Without it, the
 * kept rate is shown with its date.
 */
@RestController
@RequestMapping("/v1/data")
public class TodaysRateController {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int DEFAULT_PERCENT = 70;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final TenantJdbc t;
    private final GoodReturns goodReturns;

    public TodaysRateController(TenantJdbc t, GoodReturns goodReturns) {
        this.t = t;
        this.goodReturns = goodReturns;
    }

    /** One metal's row as kept: today's rate, company rate, Default City, when the cloud last had it. */
    record Kept(double rate, double companyRate, String city, Instant updated) { }

    /** What the shop's data says, read in one go so no connection is held while goodreturns.in is asked. */
    record Shop(String companyId, Kept gold, Kept silver, Map<String, String> settings, String companyCity) { }

    /** TODAYS_RATE_LAST: "yyyy-MM-dd HH:mm|city|gold|silver|pageDate". */
    record Last(LocalDateTime at, String city, double gold, double silver) {
        static Last parse(String v) {
            if (v == null) return null;
            String[] p = v.split("\\|");
            if (p.length < 4) return null;
            try {
                return new Last(LocalDateTime.parse(p[0].trim(), STAMP), p[1].trim(),
                        Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    @GetMapping("/todays-rate")
    public Map<String, Object> todaysRate(@RequestParam(required = false) String companyId) {
        Shop shop = t.inTenant(j -> read(j, companyId));
        return answer(shop, LocalDateTime.now(IST), goodReturns);
    }

    static Shop read(JdbcTemplate j, String companyId) {
        String co = companyId == null ? "" : companyId.trim();
        if (co.isEmpty()) {
            List<String> ids = j.queryForList(
                    "SELECT DISTINCT payload->>'company_id' FROM projections WHERE table_name = 'company_other_settings' "
                    + "AND NOT deleted AND COALESCE(payload->>'company_id', '') <> '' ORDER BY 1 LIMIT 1", String.class);
            if (ids.isEmpty()) return new Shop("", null, null, Map.of(), null);
            co = ids.get(0);
        }
        // Old copies of a row can sit beside the live one under another key: the newest is the one in use.
        Map<String, Kept> metals = new HashMap<>();
        for (Map<String, Object> r : j.queryForList(
                "SELECT payload->>'jewel_material_type' AS m, payload->>'todays_rate' AS tr, payload->>'company_rate' AS cr, "
                + "payload->>'city' AS city, last_updated_at FROM projections WHERE table_name = 'company_other_settings' "
                + "AND NOT deleted AND payload->>'company_id' = ? ORDER BY last_updated_at DESC", co)) {
            String m = String.valueOf(r.get("m")).trim().toUpperCase();
            double rate = number(r.get("tr"));
            if (metals.containsKey(m) || rate <= 0) continue;
            Object at = r.get("last_updated_at");
            metals.put(m, new Kept(rate, number(r.get("cr")), (String) r.get("city"),
                    at instanceof Timestamp ts ? ts.toInstant() : null));
        }
        Map<String, String> settings = new HashMap<>();
        for (Map<String, Object> r : j.queryForList(
                "SELECT payload->>'setting_key' AS k, payload->>'setting_value' AS v FROM projections "
                + "WHERE table_name = 'company_settings' AND NOT deleted AND payload->>'company_id' = ? "
                + "AND payload->>'setting_key' IN ('TODAYS_RATE_LAST', 'COMPANY_RATE_PERCENT_GOLD', 'COMPANY_RATE_PERCENT_SILVER') "
                + "ORDER BY last_updated_at", co)) {
            settings.put((String) r.get("k"), (String) r.get("v"));
        }
        List<String> city = j.queryForList(
                "SELECT payload->>'city' FROM projections WHERE table_name = 'company' AND NOT deleted "
                + "AND payload->>'id' = ? ORDER BY last_updated_at DESC LIMIT 1", String.class, co);
        return new Shop(co, metals.get("GOLD"), metals.get("SILVER"), settings, city.isEmpty() ? null : city.get(0));
    }

    static Map<String, Object> answer(Shop s, LocalDateTime now, GoodReturns live) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company_id", s.companyId());
        if (s.gold() == null && s.silver() == null) {
            out.put("available", false);
            out.put("note", "No gold or silver rate is kept for this company yet (Company Module -> Other Settings).");
            return out;
        }
        LocalDate today = now.toLocalDate();
        int pGold = percent(s.settings().get("COMPANY_RATE_PERCENT_GOLD"));
        int pSilver = percent(s.settings().get("COMPANY_RATE_PERCENT_SILVER"));
        Last last = Last.parse(s.settings().get("TODAYS_RATE_LAST"));

        // Taken from the page when the kept rates are still the ones it took; otherwise typed in.
        boolean fromPage = last != null
                && (s.gold() == null || same(s.gold().rate(), last.gold()))
                && (s.silver() == null || same(s.silver().rate(), last.silver()));
        LocalDateTime keptAt = fromPage ? last.at() : newest(s);
        LocalDate keptOn = keptAt == null ? null : keptAt.toLocalDate();

        String city = first(s.gold() == null ? null : s.gold().city(), s.silver() == null ? null : s.silver().city(),
                s.companyCity(), last == null ? null : last.city());

        if (keptOn == null || keptOn.isBefore(today)) {
            GoodReturns.Rates r = live == null ? null : live.rates(city, today);
            if (r != null && (keptOn == null || r.date().isAfter(keptOn))) {
                String at = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.readAt()), IST).format(HM);
                if (s.gold() != null) out.put("gold", metal(r.gold(), Math.round(r.gold() * pGold / 100.0), pGold));
                if (s.silver() != null) out.put("silver", metal(r.silver(), Math.round(r.silver() * pSilver / 100.0), pSilver));
                out.put("available", true);
                out.put("city", r.city());
                out.put("as_of", r.date().toString());
                out.put("at", at);
                out.put("today", r.date().equals(today));
                out.put("source", GoodReturns.SOURCE);
                out.put("live", true);
                out.put("note", r.city() + " · " + GoodReturns.SOURCE + " at " + at
                        + " · not yet in the shop's desktop");
                return out;
            }
        }

        if (s.gold() != null) out.put("gold", kept(s.gold(), pGold));
        if (s.silver() != null) out.put("silver", kept(s.silver(), pSilver));
        boolean isToday = keptOn != null && keptOn.equals(today);
        String at = keptAt == null ? null : keptAt.format(HM);
        out.put("available", true);
        out.put("city", fromPage ? last.city() : city);
        out.put("as_of", keptOn == null ? null : keptOn.toString());
        out.put("at", at);
        out.put("today", isToday);
        out.put("source", fromPage ? GoodReturns.SOURCE : "Company Module");
        out.put("live", false);
        String when = keptOn == null ? "" : isToday ? "updated today " + at : "as on " + keptOn.format(DAY);
        String note = fromPage
                ? last.city() + " · " + GoodReturns.SOURCE + " · " + when
                : "Set in the Company Module" + (when.isEmpty() ? "" : " · " + when);
        if (!isToday) note += " · not updated today";
        out.put("note", note);
        return out;
    }

    /** The kept figures; the % only when the kept company rate is that % of the rate (it may have been typed). */
    private static Map<String, Object> kept(Kept k, int percent) {
        boolean matches = Math.abs(Math.round(k.rate() * percent / 100.0) - k.companyRate()) <= 1;
        return metal(k.rate(), k.companyRate(), matches ? percent : null);
    }

    private static Map<String, Object> metal(double rate, double companyRate, Integer percent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", rate);
        m.put("company_rate", companyRate);
        m.put("percent", percent);
        return m;
    }

    private static LocalDateTime newest(Shop s) {
        Instant a = s.gold() == null ? null : s.gold().updated();
        Instant b = s.silver() == null ? null : s.silver().updated();
        Instant n = a == null ? b : b == null ? a : (a.isAfter(b) ? a : b);
        return n == null ? null : LocalDateTime.ofInstant(n, IST);
    }

    private static boolean same(double a, double b) { return Math.abs(a - b) < 0.005; }

    private static int percent(String v) {
        try {
            int p = Integer.parseInt(v == null ? "" : v.trim());
            return p > 0 && p <= 100 ? p : DEFAULT_PERCENT;
        } catch (NumberFormatException e) {
            return DEFAULT_PERCENT;
        }
    }

    private static double number(Object o) {
        if (o == null) return 0;
        try {
            return Double.parseDouble(o.toString().trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String first(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }
}
