package com.magizhchi.cloud.admin;

import com.magizhchi.cloud.share.MagizhchiBoxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Watches how full each shop's cloud box is, and says so BEFORE it matters.
 *
 * <p><b>The fault this exists to prevent.</b> A shop's bill photos and its
 * nightly database dump share one box account. The box refuses an upload that
 * would cross the account's limit — and a limit is about the SIZE of a file,
 * not how many — so a nearly-full account still takes the 1 MB report every
 * morning and refuses the 21 MB dump every night. The shop watches its PDFs
 * arrive and concludes everything is working. Meanwhile the thing it is
 * actually paying for, the off-site copy of its database, has not happened for
 * months. Several shops left over exactly this, and nobody could have known,
 * because the only trace was a warning line in an agent log on a PC under a
 * counter.
 *
 * <p><b>Why a band and not a percentage.</b> Warning once per crossing — 80,
 * then 90, then 95 — means a shop sitting at 85% for a month is mailed once.
 * Mailing every morning would be read for three days and ignored after that,
 * which is the same as not mailing at all, and it is how warnings normally
 * fail. Dropping back below a band re-arms it.
 */
@Service
public class BoxQuotaWatch {
    private static final Logger log = LoggerFactory.getLogger(BoxQuotaWatch.class);

    /** Highest first — the first one a shop is at or past is the band it is in. */
    private static final int[] BANDS = { 95, 90, 80 };

    private final JdbcTemplate jdbc;
    private final MagizhchiBoxClient box;
    private final Mailer mailer;
    private final boolean enabled;

    public BoxQuotaWatch(JdbcTemplate jdbc, Mailer mailer,
                         @Value("${pawnbroking.box.url}") String boxUrl,
                         @Value("${pawnbroking.quota.watch-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.box = new MagizhchiBoxClient(boxUrl);
        this.enabled = enabled;
    }

    /**
     * 07:00 India time. After the 02:30 prune and after the shops' overnight
     * backups have gone up, so the figure is the day's real one — and early
     * enough that somebody reads the mail before the shop opens.
     */
    @Scheduled(cron = "${pawnbroking.quota.cron:0 0 7 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!enabled) { log.info("box quota watch is switched off by configuration"); return; }
        sweep();
    }

    /** Also callable from the admin console. Returns one row per shop looked at. */
    public List<Map<String, Object>> sweep() {
        List<Map<String, Object>> shops = jdbc.queryForList(
            "SELECT shop_id, display_name, primary_email, magizhchi_token " +
            "  FROM public.tenants " +
            " WHERE active AND magizhchi_token IS NOT NULL ORDER BY shop_id");
        List<String> admins = jdbc.queryForList(
            "SELECT email FROM public.admin_users WHERE revoked_at IS NULL", String.class);

        List<Map<String, Object>> out = new ArrayList<>();
        int warned = 0;
        for (Map<String, Object> s : shops) {
            String shopId = String.valueOf(s.get("shop_id"));
            try {
                MagizhchiBoxClient.Usage u = box.usage(String.valueOf(s.get("magizhchi_token")));
                int pct = u.limitBytes() <= 0 ? 0
                        : (int) Math.round(100.0 * u.usedBytes() / u.limitBytes());
                int band = bandOf(pct);
                int prev = record(shopId, u.usedBytes(), u.limitBytes(), null);

                if (band > prev) {
                    if (tell(s, admins, pct, u)) warned++;
                    jdbc.update("UPDATE public.box_quota SET warned_band=?, warned_at=now() " +
                                " WHERE shop_id=?", band, shopId);
                } else if (band < prev) {
                    // It came back down — re-arm, so a shop that fills again is
                    // warned again rather than being silently written off.
                    jdbc.update("UPDATE public.box_quota SET warned_band=? WHERE shop_id=?",
                                band, shopId);
                }
                out.add(Map.of("shop_id", shopId, "used_bytes", u.usedBytes(),
                               "limit_bytes", u.limitBytes(), "pct", pct, "band", band));
            } catch (Exception e) {
                // A box we cannot read must not look like a shop that is empty.
                log.warn("could not read box usage for {}: {}", shopId, e.toString());
                record(shopId, -1, -1, e.toString());
                out.add(Map.of("shop_id", shopId, "error", e.toString()));
            }
        }
        log.info("box quota watch: {} shop(s) checked, {} warned", shops.size(), warned);
        return out;
    }

    private static int bandOf(int pct) {
        for (int b : BANDS) if (pct >= b) return b;
        return 0;
    }

    /** Upserts the reading and returns the band this shop was last warned at. */
    private int record(String shopId, long used, long limit, String error) {
        Integer prev = jdbc.queryForObject(
            "INSERT INTO public.box_quota(shop_id, used_bytes, limit_bytes, checked_at, last_error) " +
            "VALUES (?,?,?,now(),?) " +
            "ON CONFLICT (shop_id) DO UPDATE SET " +
            // A failed reading keeps the last good numbers rather than zeroing
            // them, so the console does not show a shop as empty when the truth
            // is that nobody could ask.
            "  used_bytes  = CASE WHEN EXCLUDED.used_bytes  < 0 THEN public.box_quota.used_bytes " +
            "                     ELSE EXCLUDED.used_bytes END, " +
            "  limit_bytes = CASE WHEN EXCLUDED.limit_bytes < 0 THEN public.box_quota.limit_bytes " +
            "                     ELSE EXCLUDED.limit_bytes END, " +
            "  checked_at  = now(), last_error = EXCLUDED.last_error " +
            "RETURNING warned_band", Integer.class, shopId, used, limit, error);
        return prev == null ? 0 : prev;
    }

    /** The warning itself. Plain, specific, and it names what will stop. */
    private boolean tell(Map<String, Object> shop, List<String> admins,
                         int pct, MagizhchiBoxClient.Usage u) {
        String name = String.valueOf(shop.getOrDefault("display_name", shop.get("shop_id")));
        String email = shop.get("primary_email") == null ? null
                     : String.valueOf(shop.get("primary_email"));

        List<String> to = new ArrayList<>(admins);
        if (email != null && !email.isBlank()) to.add(email);

        boolean critical = pct >= 95;
        String subject = (critical ? "URGENT: " : "")
                + name + " cloud storage is " + pct + "% full";

        String html =
            "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:15px;color:#2b2b2b\">"
          + "<h2 style=\"color:#6B1020;margin:0 0 12px\">" + esc(name) + " — cloud storage "
          + pct + "% full</h2>"
          + "<p><b>Using " + gb(u.usedBytes()) + " of " + gb(u.limitBytes()) + ".</b></p>"
          + (critical
             ? "<p style=\"background:#FDECEC;border-left:4px solid #CC0000;padding:10px 12px\">"
             + "<b>This shop's database backup has most likely already stopped.</b> "
             + "When the box is nearly full, the small daily PDF report still fits and the "
             + "much larger database backup does not — so the reports keep arriving on the "
             + "phone and look like proof that everything is working, while the backup that "
             + "actually matters has not been saved for some time.</p>"
             : "<p>Nothing has stopped yet. Acting now avoids the failure described below.</p>")
          + "<p><b>What to do</b></p><ul>"
          + "<li>Raise this shop's box plan — storage is the only thing that needs to change.</li>"
          + "<li>Old backups past 30 days are now retired automatically, so backups alone "
          + "will not fill a box again. If this one is full of <i>bill photos</i>, only a "
          + "bigger plan will help: photos are never deleted.</li>"
          + "</ul>"
          + "<p style=\"color:#666;font-size:13px\">Sent once when a shop crosses 80%, 90% "
          + "and 95% — not every day.</p></div>";

        boolean sent = mailer.send(to, subject, html);
        if (sent) log.info("warned {} at {}% ({} recipients)", name, pct, to.size());
        return sent;
    }

    private static String gb(long b) {
        if (b <= 0) return "unknown";
        double g = b / 1073741824.0;
        return g >= 1 ? String.format("%.2f GB", g) : String.format("%d MB", b >> 20);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
