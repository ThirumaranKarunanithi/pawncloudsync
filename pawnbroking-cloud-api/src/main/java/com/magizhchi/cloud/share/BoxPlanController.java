package com.magizhchi.cloud.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magizhchi.cloud.admin.Mailer;
import com.magizhchi.cloud.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shop's cloud box plan, and renewing it, for the phone.
 *
 * <p>From 06-11-2026 a paid box plan ends by itself and the shop pays to renew
 * it. The phone never holds the box token: it asks here, and this relays the
 * box's own renewal calls with the shop's token, so the payment lands on the
 * very account the shop's photos and backups go to.
 *
 * <p>Anyone signed in to the shop can see the plan. Only an OWNER in
 * {@code user_shop_access} can start a payment or hand in its UTR - a VIEWER
 * or AUDITOR sees the same screen with the button replaced by who can.
 *
 * <pre>
 *   GET  /v1/box/plan                       plan, usage, plans on offer, recent payments
 *   POST /v1/box/renew        {planCode, billingCycle}   -> the box's payment (UPI link)
 *   POST /v1/box/renew/{reference}/utr {utr}             -> payment now being verified
 * </pre>
 */
@RestController
@RequestMapping("/v1/box")
public class BoxPlanController {
    private static final Logger log = LoggerFactory.getLogger(BoxPlanController.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MagizhchiBoxClient box;
    private final Mailer mailer;

    public BoxPlanController(JdbcTemplate jdbc, Mailer mailer,
                             @Value("${pawnbroking.box.url}") String boxUrl) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.box = new MagizhchiBoxClient(boxUrl);
    }

    public record RenewRequest(String planCode, String billingCycle) {}
    public record UtrRequest(String utr) {}

    @GetMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan() {
        String shopId = TenantContext.get();
        if (shopId == null) return refuse(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        String token = tokenOf(shopId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shopId", shopId);
        out.put("canPay", isOwner(shopId, callerEmail()));
        out.put("whoCanPay", "Only the shop's owner can renew the plan.");
        if (token == null) {
            out.put("available", false);
            out.put("headline", "This shop's cloud box is not set up yet.");
            return ResponseEntity.ok(out);
        }
        out.put("available", true);

        MagizhchiBoxClient.Answer ua = box.storageUsageRaw(token);
        if (!ua.ok()) {
            log.warn("box plan for {}: usage {} {}", shopId, ua.status(), ua.words());
            return refuse(HttpStatus.BAD_GATEWAY, "The cloud box did not answer just now. Try again in a minute.");
        }
        JsonNode un = ua.json();
        MagizhchiBoxClient.Usage u;
        try { u = MagizhchiBoxClient.parseUsage(ua.body()); }
        catch (Exception e) { return refuse(HttpStatus.BAD_GATEWAY, "The cloud box's answer could not be read."); }

        Instant now = Instant.now();
        out.put("usedBytes", u.usedBytes());
        out.put("limitBytes", u.limitBytes());
        out.put("used", BoxPlanWords.gb(u.usedBytes()));
        out.put("limit", BoxPlanWords.gb(u.limitBytes()));
        out.put("planCode", u.planCode());
        out.put("planLabel", u.planEnded() ? "Free (paid plan ended)" : u.planLabel());
        out.put("planState", u.planState());
        out.put("planExpiresAt", u.planExpiresAt() == null ? null : u.planExpiresAt().toString());
        out.put("planEnds", u.planExpiresAt() == null ? null : BoxPlanWords.day(u.planExpiresAt()));
        out.put("graceEndsAt", u.graceEndsAt() == null ? null : u.graceEndsAt().toString());
        out.put("graceEnds", u.graceEndsAt() == null ? null : BoxPlanWords.day(u.graceEndsAt()));
        JsonNode pending = un.path("pendingPayment");
        out.put("pendingPayment", pending.isObject() ? M.convertValue(pending, Map.class) : null);

        String[] a = attention(u, now);
        out.put("attention", a[0]);
        out.put("headline", a[1]);
        out.put("note", BoxPlanWords.note(u, now));

        MagizhchiBoxClient.Answer pa = box.plans(token);
        List<Object> plans = new ArrayList<>();
        if (pa.ok() && pa.json().isArray()) {
            for (JsonNode p : pa.json()) {
                // The free plan is where an ended plan falls back to - nothing to buy.
                if (p.path("monthlyPaise").asLong(0) <= 0 && p.path("yearlyPaise").asLong(0) <= 0) continue;
                plans.add(M.convertValue(p, Map.class));
            }
        } else {
            log.warn("box plan for {}: plans {} {}", shopId, pa.status(), pa.words());
        }
        out.put("plans", plans);

        MagizhchiBoxClient.Answer my = box.myPayments(token);
        List<Object> payments = new ArrayList<>();
        if (my.ok() && my.json().isArray()) {
            for (JsonNode p : my.json()) {
                if (payments.size() >= 6) break;
                payments.add(M.convertValue(p, Map.class));
            }
        }
        out.put("payments", payments);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/renew")
    public ResponseEntity<Map<String, Object>> renew(@RequestBody RenewRequest req) {
        String shopId = TenantContext.get();
        if (shopId == null) return refuse(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        String email = callerEmail();
        if (!isOwner(shopId, email)) return refuse(HttpStatus.FORBIDDEN, "Only the shop's owner can renew the plan.");
        String plan = req == null || req.planCode() == null ? "" : req.planCode().trim();
        String cycle = req == null || req.billingCycle() == null ? "" : req.billingCycle().trim().toLowerCase();
        if (plan.isEmpty()) return refuse(HttpStatus.BAD_REQUEST, "Choose a plan.");
        if (!cycle.equals("monthly") && !cycle.equals("yearly"))
            return refuse(HttpStatus.BAD_REQUEST, "Choose monthly or yearly.");
        String token = tokenOf(shopId);
        if (token == null) return refuse(HttpStatus.CONFLICT, "This shop's cloud box is not set up yet.");

        MagizhchiBoxClient.Answer a = box.checkout(token, plan, cycle);
        if (!a.ok()) {
            log.warn("box renew for {} by {}: {} {}", shopId, email, a.status(), a.words());
            return refuse(statusOf(a), a.words());
        }
        JsonNode p = a.json();
        log.info("box renew for {} by {}: {} {} ref {} {} paise", shopId, email, plan, cycle,
                 p.path("reference").asText(), p.path("amountPaise").asLong());
        return ResponseEntity.ok(asMap(p));
    }

    @PostMapping("/renew/{reference}/utr")
    public ResponseEntity<Map<String, Object>> utr(@PathVariable String reference, @RequestBody UtrRequest req) {
        String shopId = TenantContext.get();
        if (shopId == null) return refuse(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        String email = callerEmail();
        if (!isOwner(shopId, email)) return refuse(HttpStatus.FORBIDDEN, "Only the shop's owner can renew the plan.");
        String utr = req == null || req.utr() == null ? "" : req.utr().replaceAll("\\s+", "");
        if (!utr.matches("[A-Za-z0-9]{8,35}"))
            return refuse(HttpStatus.BAD_REQUEST, "Type the 12-digit UTR (UPI reference number) from the payment app.");
        String token = tokenOf(shopId);
        if (token == null) return refuse(HttpStatus.CONFLICT, "This shop's cloud box is not set up yet.");

        MagizhchiBoxClient.Answer a = box.submitUtr(token, reference, utr);
        if (!a.ok()) {
            log.warn("box utr for {} ref {} by {}: {} {}", shopId, reference, email, a.status(), a.words());
            return refuse(statusOf(a), a.words());
        }
        JsonNode p = a.json();
        log.info("box utr for {} ref {} by {}: now {}", shopId, reference, email, p.path("status").asText());
        tellAdmins(shopId, email, p);
        return ResponseEntity.ok(asMap(p));
    }

    // ── internals ──────────────────────────────────────────────────────────

    /**
     * {kind, headline} for the strip on the phone's Home and Backups screens,
     * or {null, null} when the plan needs nothing doing. The kinds are the
     * Box's own advice: renew when the plan ends within a week, is in grace or
     * has ended; and a full box needs a bigger plan, which is the same payment.
     */
    static String[] attention(MagizhchiBoxClient.Usage u, Instant now) {
        if (u.uploadsPaused())
            return new String[]{"PAUSED", "Uploads have stopped: the cloud box plan has ended. Renew it."};
        if (u.planEnded())
            return new String[]{"ENDED", "The cloud box plan has ended; it is on the free " + BoxPlanWords.gb(u.limitBytes()) + " now."};
        if (u.inGrace())
            return new String[]{"GRACE", "The cloud box plan has ended. Space is kept until "
                    + BoxPlanWords.day(u.graceEndsAt()) + ". Renew it before then."};
        if (u.endsWithin(now, 7))
            return new String[]{"ENDING", "The cloud box plan ends on " + BoxPlanWords.day(u.planExpiresAt()) + ". Renew it."};
        if (u.limitBytes() > 0 && u.usedBytes() * 10 >= u.limitBytes() * 9L) {
            int pct = (int) Math.min(999, Math.round(100.0 * u.usedBytes() / u.limitBytes()));
            return new String[]{"FULL", "The cloud box is " + pct + "% full. A bigger plan keeps photos and backups going up."};
        }
        return new String[]{null, null};
    }

    private String tokenOf(String shopId) {
        List<String> t = jdbc.queryForList(
            "SELECT magizhchi_token FROM public.tenants WHERE shop_id = ? AND magizhchi_token IS NOT NULL",
            String.class, shopId);
        return t.isEmpty() || t.get(0).isBlank() ? null : t.get(0);
    }

    /** The signed-in person's email, from a phone sign-in or a Magizhchi ID one. */
    private String callerEmail() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getPrincipal() == null) return null;
        String p = String.valueOf(a.getPrincipal());
        if (p.startsWith("suite:")) return p.substring(6).trim().toLowerCase();
        if (!p.startsWith("user:")) return null;      // the shop PC's agent key never pays
        try {
            List<String> e = jdbc.queryForList(
                "SELECT username FROM public.app_users WHERE user_id = ?", String.class,
                Long.parseLong(p.substring(5)));
            return e.isEmpty() ? null : e.get(0).trim().toLowerCase();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isOwner(String shopId, String email) {
        if (shopId == null || email == null || email.isBlank()) return false;
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM public.user_shop_access " +
            " WHERE lower(email) = ? AND shop_id = ? AND revoked_at IS NULL AND upper(role) = 'OWNER'",
            Integer.class, email, shopId);
        return n != null && n > 0;
    }

    /** Somebody at Magizhchi has to approve the payment on the box's Payments page. */
    private void tellAdmins(String shopId, String email, JsonNode p) {
        try {
            List<String> admins = jdbc.queryForList(
                "SELECT email FROM public.admin_users WHERE revoked_at IS NULL", String.class);
            if (admins.isEmpty()) return;
            List<String> names = jdbc.queryForList(
                "SELECT COALESCE(display_name, shop_id) FROM public.tenants WHERE shop_id = ?", String.class, shopId);
            String name = names.isEmpty() ? shopId : names.get(0);
            String amount = rupees(p.path("amountPaise").asLong(0));
            String subject = name + ": paid " + amount + " for the box plan - approve it";
            String html =
                "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#2b2b2b\">"
              + "<h2 style=\"color:#6B1020;margin:0 0 12px\">" + esc(subject) + "</h2>"
              + "<p>" + esc(name) + " has paid for its cloud box plan from the phone app and "
              + "handed in the UPI reference. Check it against the bank and approve it on "
              + "Magizhchi Box's <b>Payments</b> page; the plan comes back the moment it is approved.</p>"
              + "<p><b>Plan:</b> " + esc(p.path("planCode").asText()) + " " + esc(p.path("billingCycle").asText())
              + " &nbsp; <b>Amount:</b> " + amount
              + "<br><b>Reference:</b> " + esc(p.path("reference").asText())
              + " &nbsp; <b>UTR:</b> " + esc(p.path("paymentUtr").asText())
              + "<br><b>Sent by:</b> " + esc(email == null ? "unknown" : email) + "</p></div>";
            mailer.send(admins, subject, html);
        } catch (Exception e) {
            log.warn("could not tell admins about {}'s box payment: {}", shopId, e.toString());
        }
    }

    static String rupees(long paise) {
        long r = paise / 100;
        String s = String.valueOf(r);
        // Indian grouping: last three digits, then pairs.
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) b.append(',');
                b.append(head.charAt(i));
            }
            s = b + "," + tail;
        }
        long p = paise % 100;
        return "₹" + s + (p == 0 ? "" : String.format(".%02d", p));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode n) {
        return n.isObject() ? M.convertValue(n, Map.class) : new LinkedHashMap<>();
    }

    /** The box's own status for a refusal it made; a gateway error for one it could not make. */
    private static HttpStatus statusOf(MagizhchiBoxClient.Answer a) {
        HttpStatus s = HttpStatus.resolve(a.status());
        if (s == null || s.is5xxServerError()) return HttpStatus.BAD_GATEWAY;
        if (s == HttpStatus.UNAUTHORIZED) return HttpStatus.BAD_GATEWAY;   // the shop's token, not the person
        return s;
    }

    /** The phone reads "error" first, so the sentence goes there. */
    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String words) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", words);
        b.put("status", status.value());
        return ResponseEntity.status(status).body(b);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
