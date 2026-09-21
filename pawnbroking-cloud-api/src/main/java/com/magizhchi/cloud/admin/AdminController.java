package com.magizhchi.cloud.admin;

import com.magizhchi.cloud.auth.JwtService;
import com.magizhchi.cloud.tenant.TenantBootstrap;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The admin console behind /admin.html.
 *
 * Everything here is about the estate rather than one shop: how many shops
 * there are, which of them are still sending, what each one stores, who may
 * sign in to it, and what share of the monthly bill each one accounts for.
 *
 * Two rules run through it:
 *   - an admin is not a shop user. Admins live in public.admin_users and are
 *     checked on EVERY call, so removing one takes effect immediately rather
 *     than when their token runs out.
 *   - a shop id becomes a Postgres schema name, so it is validated to
 *     [a-z0-9_] everywhere it is used, and never concatenated before that.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final AdminOtp otp;
    private final TenantBootstrap tenants;
    private final PruneService prune;
    /** Same BCrypt the mobile app's password login uses, over the same table. */
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder pwd
            = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public AdminController(JdbcTemplate jdbc, JwtService jwt, AdminOtp otp,
                           TenantBootstrap tenants, PruneService prune) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.otp = otp;
        this.tenants = tenants;
        this.prune = prune;
    }

    // ── sign in ───────────────────────────────────────────────────────────────

    public record EmailRequest(String email) {}
    public record VerifyRequest(String email, String code) {}
    public record PasswordRequest(String email, String password) {}

    /** Per address: {how many wrong passwords in a row, when the last one was}. */
    private static final Map<String, long[]> FAILURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 15 * 60_000L;

    /**
     * The code only goes out to an email that is already an admin. An
     * unknown address gets the same answer as a known one, so this cannot be
     * used to find out who the admins are.
     */
    @PostMapping("/login/send-otp")
    public Map<String, Object> sendOtp(@RequestBody EmailRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        if (isAdmin(email)) {
            otp.sendOtp(email);
        } else {
            log.warn("admin OTP asked for non-admin email {}", email);
        }
        return Map.of("message", "If that address is an admin, a code is on its way.");
    }

    @PostMapping("/login/verify")
    public Map<String, Object> verify(@RequestBody VerifyRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        String code = req == null || req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) throw bad("code is required");
        if (!isAdmin(email)) throw forbidden("this address is not an admin");
        String displayName = otp.verify(email, code);   // throws if the code is wrong
        log.info("admin signed in: {}", email);
        return Map.of(
                "token", jwt.mintAdmin(email),
                "email", email,
                "display_name", displayName,
                "expires_in_hours", 12
        );
    }

    /**
     * Sign in with a password instead of a code.
     *
     * The code route depends on the email provider, and a provider that has
     * hit its daily quota locks every admin out of the console exactly when
     * something is wrong and it is needed. This reads the same
     * public.login_passwords table the mobile app uses (BCrypt), and only for
     * an address that is already an admin.
     *
     * Wrong passwords are counted per address: five in a row and that address
     * waits fifteen minutes.
     */
    @PostMapping("/login/password")
    public Map<String, Object> passwordLogin(@RequestBody PasswordRequest req) {
        String email = normalizeEmail(req == null ? null : req.email());
        String password = req == null || req.password() == null ? "" : req.password();
        if (email.isEmpty() || password.isEmpty()) throw bad("email and password are required");

        long now = System.currentTimeMillis();
        long[] state = FAILURES.get(email);
        if (state != null && state[0] >= MAX_FAILURES && now - state[1] < LOCKOUT_MS) {
            long waitMin = (LOCKOUT_MS - (now - state[1])) / 60_000 + 1;
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "too many wrong passwords — try again in " + waitMin + " minutes");
        }

        // Same answer whether the address is not an admin or the password is
        // wrong: the console should not tell a stranger who the admins are.
        boolean ok = false;
        if (isAdmin(email)) {
            List<String> hashes = jdbc.queryForList(
                    "SELECT password_hash FROM public.login_passwords WHERE email = ?",
                    String.class, email);
            ok = !hashes.isEmpty() && pwd.matches(password, hashes.get(0));
        }
        if (!ok) {
            // A run of failures older than the lockout starts counting again.
            FAILURES.compute(email, (k, v) -> (v == null || now - v[1] > LOCKOUT_MS)
                    ? new long[]{1, now}
                    : new long[]{v[0] + 1, now});
            log.warn("admin password sign-in refused for {}", email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "wrong email or password. If no password is set for this address, " +
                    "sign in with a code and set one from the page.");
        }
        FAILURES.remove(email);
        log.info("admin signed in with a password: {}", email);
        return Map.of("token", jwt.mintAdmin(email), "email", email,
                      "display_name", email, "expires_in_hours", 12);
    }

    /** Set or change your own console password. Only ever your own. */
    @PostMapping("/admins/me/password")
    public Map<String, Object> setMyPassword(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @RequestBody PasswordRequest req) {
        String admin = requireAdmin(auth);
        String password = req == null || req.password() == null ? "" : req.password();
        if (password.length() < 8) throw bad("choose at least 8 characters");
        jdbc.update("INSERT INTO public.login_passwords(email, password_hash) VALUES (?,?) " +
                    "ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, " +
                    "updated_at = now()", admin, pwd.encode(password));
        FAILURES.remove(admin);
        log.info("admin {} set a console password", admin);
        return Map.of("ok", true,
                      "note", "You can now sign in with this password when the email code is slow or blocked. " +
                              "It is the same password the mobile app uses for this address.");
    }

    // ── the estate ────────────────────────────────────────────────────────────

    /** Totals across every shop, plus the monthly bill they are measured against. */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireAdmin(auth);
        List<Map<String, Object>> shops = shopRows();

        long dbBytes = 0, boxBytes = 0, photos = 0, backups = 0, rows = 0;
        int syncingToday = 0, silent7 = 0, neverSynced = 0, noPhotoKey = 0;
        for (Map<String, Object> s : shops) {
            dbBytes += num(s.get("db_bytes"));
            boxBytes += num(s.get("box_bytes"));
            photos += num(s.get("photos"));
            backups += num(s.get("backups"));
            rows += num(s.get("rows"));
            Long hours = (Long) s.get("hours_since_sync");
            if (hours == null) neverSynced++;
            else if (hours <= 24) syncingToday++;
            else if (hours > 24 * 7) silent7++;
            if (!Boolean.TRUE.equals(s.get("photo_key"))) noPhotoKey++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shops_total", shops.size());
        out.put("shops_active", shops.stream().filter(s -> Boolean.TRUE.equals(s.get("active"))).count());
        out.put("syncing_today", syncingToday);
        out.put("silent_over_7_days", silent7);
        out.put("never_synced", neverSynced);
        out.put("without_photo_key", noPhotoKey);
        out.put("db_bytes", dbBytes);
        out.put("box_bytes", boxBytes);
        out.put("totals", Map.of("photos", photos, "backups", backups, "rows", rows,
                                 "bytes", dbBytes + boxBytes));
        out.put("monthly_cost", monthlyCost());
        out.put("currency", currency());
        return out;
    }

    /** One row per shop: who it is, whether it is still sending, what it stores, what it costs. */
    @GetMapping("/shops")
    public List<Map<String, Object>> shops(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireAdmin(auth);
        List<Map<String, Object>> shops = shopRows();

        long totalBytes = shops.stream()
                .mapToLong(s -> num(s.get("db_bytes")) + num(s.get("box_bytes"))).sum();
        BigDecimal monthly = monthlyCost();
        for (Map<String, Object> s : shops) {
            long bytes = num(s.get("db_bytes")) + num(s.get("box_bytes"));
            // A shop that stores nothing still exists; with no bytes anywhere,
            // an even split says more than a column of zeroes.
            BigDecimal share = totalBytes > 0
                    ? BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalBytes), 2, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(100.0 / Math.max(1, shops.size())).setScale(2, RoundingMode.HALF_UP);
            s.put("cost_percent", share);
            s.put("cost_amount", monthly.multiply(share)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        return shops;
    }

    public record NewShopRequest(String shop_id, String display_name, String email) {}

    /**
     * Create a shop end to end: the schema and its tables, the tenant row,
     * the owner's sign-in, and the sync key the shop PC needs.
     *
     * The key is returned ONCE, here. It is stored in plain text (the sync
     * agents read it back), but the page shows it once and tells you to put
     * it straight into the setup exe.
     */
    @PostMapping("/shops")
    public Map<String, Object> createShop(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @RequestBody NewShopRequest req) {
        String admin = requireAdmin(auth);
        String shopId = requireShopId(req == null ? null : req.shop_id());
        String email = normalizeEmail(req == null ? null : req.email());
        if (email.isEmpty()) throw bad("the owner's email is required — it is how they sign in");
        String displayName = req.display_name() == null || req.display_name().isBlank()
                ? capitalize(shopId) : req.display_name().trim();

        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM public.tenants WHERE shop_id = ?", Integer.class, shopId);
        if (exists != null && exists > 0)
            throw bad("a shop called " + shopId + " already exists");

        // Makes the schema, its tables, the tenant row and a first api key.
        tenants.provision(shopId);
        jdbc.update("UPDATE public.tenants SET display_name = ?, primary_email = ? WHERE shop_id = ?",
                    displayName, email, shopId);
        jdbc.update("INSERT INTO public.user_shop_access(email, shop_id, role) VALUES (?,?, 'OWNER') " +
                    "ON CONFLICT (email, shop_id) DO UPDATE SET revoked_at = NULL",
                    email, shopId);

        // provision() mints a bootstrap key of its own when a shop has none, and
        // it is in the old SHOPID_uuid shape. Revoke it so the shop ends with
        // exactly one live key: the mbk_ one shown on the page.
        jdbc.update("UPDATE public.shop_credentials SET revoked_at = now() " +
                    "WHERE shop_id = ? AND revoked_at IS NULL", shopId);
        String apiKey = mintApiKey(shopId, displayName + " - sync agent");
        log.info("admin {} created shop {} for {}", admin, shopId, email);

        return Map.of(
                "shop_id", shopId,
                "display_name", displayName,
                "owner_email", email,
                "api_key", apiKey,
                "next_steps", List.of(
                        "Run PawnBrokingSyncSetup.exe on the shop PC and give it this shop id and key.",
                        "Have " + email + " sign in once on the phone — until then photos and backups are refused (503).",
                        "The shop's Magizhchi Share account must exist for that email, or Send OTP answers Not Found."));
    }

    public record ShopPatch(String display_name, Boolean active) {}

    @PatchMapping("/shops/{shopId}")
    public Map<String, Object> patchShop(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable String shopId,
                                         @RequestBody ShopPatch patch) {
        String admin = requireAdmin(auth);
        String id = requireExistingShop(shopId);
        if (patch.display_name() != null && !patch.display_name().isBlank())
            jdbc.update("UPDATE public.tenants SET display_name = ? WHERE shop_id = ?",
                        patch.display_name().trim(), id);
        if (patch.active() != null) {
            jdbc.update("UPDATE public.tenants SET active = ? WHERE shop_id = ?", patch.active(), id);
            log.warn("admin {} set shop {} active={}", admin, id, patch.active());
        }
        return Map.of("ok", true);
    }

    public record AccessRequest(String email, String role) {}

    /** Give an address access to a shop — the phone sign-in list for that shop. */
    @PostMapping("/shops/{shopId}/emails")
    public Map<String, Object> addEmail(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable String shopId,
                                        @RequestBody AccessRequest req) {
        String admin = requireAdmin(auth);
        String id = requireExistingShop(shopId);
        String email = normalizeEmail(req == null ? null : req.email());
        if (email.isEmpty()) throw bad("email is required");
        String role = req.role() == null || req.role().isBlank() ? "OWNER" : req.role().trim().toUpperCase();
        if (!List.of("OWNER", "VIEWER", "AUDITOR").contains(role))
            throw bad("role must be OWNER, VIEWER or AUDITOR");
        jdbc.update("INSERT INTO public.user_shop_access(email, shop_id, role) VALUES (?,?,?) " +
                    "ON CONFLICT (email, shop_id) DO UPDATE SET role = EXCLUDED.role, revoked_at = NULL",
                    email, id, role);
        log.info("admin {} gave {} access to {} as {}", admin, email, id, role);
        return Map.of("ok", true, "email", email, "role", role);
    }

    /**
     * Take an address off a shop. The row is kept and stamped rather than
     * deleted, so it stays visible who had access and until when.
     */
    @DeleteMapping("/shops/{shopId}/emails/{email}")
    public Map<String, Object> removeEmail(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String shopId,
                                           @PathVariable String email) {
        String admin = requireAdmin(auth);
        String id = requireExistingShop(shopId);
        String addr = normalizeEmail(email);
        int n = jdbc.update("UPDATE public.user_shop_access SET revoked_at = now() " +
                            "WHERE lower(email) = ? AND shop_id = ? AND revoked_at IS NULL", addr, id);
        if (n == 0) throw bad(addr + " does not have access to " + id);
        log.warn("admin {} removed {} from {}", admin, addr, id);
        return Map.of("ok", true);
    }

    /**
     * A fresh sync key. The old one is revoked, so the shop PC stops syncing
     * until the new key is put into its setup — which is the point of the
     * button, and the page says so before it runs.
     */
    @PostMapping("/shops/{shopId}/api-key")
    public Map<String, Object> rotateKey(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable String shopId) {
        String admin = requireAdmin(auth);
        String id = requireExistingShop(shopId);
        jdbc.update("UPDATE public.shop_credentials SET revoked_at = now() " +
                    "WHERE shop_id = ? AND revoked_at IS NULL", id);
        String key = mintApiKey(id, "rotated by " + admin);
        log.warn("admin {} rotated the sync key for {}", admin, id);
        return Map.of("shop_id", id, "api_key", key,
                      "warning", "The shop PC stops syncing until this key is in its sync.properties.");
    }

    /** The key a shop is using now, so an admin can read it back to a shop PC. */
    @GetMapping("/shops/{shopId}/api-key")
    public Map<String, Object> currentKey(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String shopId) {
        String admin = requireAdmin(auth);
        String id = requireExistingShop(shopId);
        List<String> keys = jdbc.queryForList(
                "SELECT api_key FROM public.shop_credentials " +
                "WHERE shop_id = ? AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",
                String.class, id);
        if (keys.isEmpty()) throw bad("this shop has no live key — make one with Rotate");
        log.info("admin {} read the sync key for {}", admin, id);
        return Map.of("shop_id", id, "api_key", keys.get(0));
    }

    // ── admins ────────────────────────────────────────────────────────────────

    @GetMapping("/admins")
    public List<Map<String, Object>> admins(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireAdmin(auth);
        return jdbc.queryForList(
                "SELECT email, added_at, added_by FROM public.admin_users " +
                "WHERE revoked_at IS NULL ORDER BY email");
    }

    @PostMapping("/admins")
    public Map<String, Object> addAdmin(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody EmailRequest req) {
        String admin = requireAdmin(auth);
        String email = normalizeEmail(req == null ? null : req.email());
        if (!email.contains("@")) throw bad("that does not look like an email address");
        jdbc.update("INSERT INTO public.admin_users(email, added_by) VALUES (?,?) " +
                    "ON CONFLICT (email) DO UPDATE SET revoked_at = NULL, added_by = EXCLUDED.added_by",
                    email, admin);
        log.warn("admin {} added admin {}", admin, email);
        return Map.of("ok", true, "email", email,
                      "note", "They sign in at /admin.html with a code sent to that address. " +
                              "It must have a Magizhchi Share account.");
    }

    /**
     * Remove an admin — but never the last one, and never yourself by
     * accident. A console nobody can open is not a safe state to leave.
     */
    @DeleteMapping("/admins/{email}")
    public Map<String, Object> removeAdmin(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable String email) {
        String admin = requireAdmin(auth);
        String addr = normalizeEmail(email);
        if (addr.equalsIgnoreCase(admin))
            throw bad("you cannot remove yourself — ask another admin to do it");
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM public.admin_users WHERE revoked_at IS NULL", Integer.class);
        if (live != null && live <= 1) throw bad("this is the last admin — add another one first");
        int n = jdbc.update("UPDATE public.admin_users SET revoked_at = now() " +
                            "WHERE email = ? AND revoked_at IS NULL", addr);
        if (n == 0) throw bad(addr + " is not an admin");
        log.warn("admin {} removed admin {}", admin, addr);
        return Map.of("ok", true);
    }

    // ── what it costs ─────────────────────────────────────────────────────────

    @GetMapping("/costs")
    public Map<String, Object> costs(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireAdmin(auth);
        return Map.of(
                "items", jdbc.queryForList(
                        "SELECT id, name, monthly_amount, currency, note FROM public.cost_items ORDER BY id"),
                "monthly_total", monthlyCost(),
                "currency", currency());
    }

    public record CostItem(Long id, String name, BigDecimal monthly_amount, String note) {}

    /** Replace the cost lines with what the page shows. Small list, edited rarely. */
    @PutMapping("/costs")
    public Map<String, Object> putCosts(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody List<CostItem> items) {
        String admin = requireAdmin(auth);
        if (items == null) throw bad("no cost lines given");
        jdbc.update("DELETE FROM public.cost_items");
        for (CostItem it : items) {
            if (it.name() == null || it.name().isBlank()) continue;
            BigDecimal amt = it.monthly_amount() == null ? BigDecimal.ZERO : it.monthly_amount();
            if (amt.signum() < 0) throw bad("a cost cannot be negative: " + it.name());
            jdbc.update("INSERT INTO public.cost_items(name, monthly_amount, note) VALUES (?,?,?)",
                        it.name().trim(), amt, it.note());
        }
        log.info("admin {} updated the cost lines", admin);
        return Map.of("ok", true, "monthly_total", monthlyCost());
    }

    // ── housekeeping ──────────────────────────────────────────────────────────

    /** The prune settings, what the last runs did, and where the space is now. */
    @GetMapping("/housekeeping")
    public Map<String, Object> housekeeping(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireAdmin(auth);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.parseBoolean(prune.setting("prune.enabled", "true")));
        out.put("events_days", prune.intSetting("prune.events.days", 90));
        out.put("notifications_days", prune.intSetting("prune.notifications.days", 30));
        out.put("vacuum", Boolean.parseBoolean(prune.setting("prune.vacuum", "true")));
        out.put("volume_gb", prune.intSetting("prune.volume.gb", 0));
        out.put("checkpoint_every", prune.intSetting("prune.checkpoint.every.batches", 5));
        // What the volume actually holds now — the database plus its
        // write-ahead log, which is the part that filled the disk.
        try {
            out.put("database_bytes", jdbc.queryForObject(
                    "SELECT pg_database_size(current_database())", Long.class));
            out.put("wal_bytes", jdbc.queryForObject(
                    "SELECT COALESCE(sum(size), 0) FROM pg_ls_waldir()", Long.class));
        } catch (Exception e) {
            log.warn("could not read the database or WAL size: {}", e.toString());
        }
        out.put("runs", jdbc.queryForList(
                "SELECT id, started_at, finished_at, triggered_by, dry_run, events_deleted, " +
                "       notifications_deleted, bytes_before, bytes_after, shops, note " +
                "  FROM public.prune_runs ORDER BY started_at DESC LIMIT 10"));
        // Where the space actually is, the same three tables the job knows about.
        out.put("biggest", jdbc.queryForList(
                "SELECT n.nspname AS schema, c.relname AS table, " +
                "       pg_total_relation_size(c.oid) AS bytes, c.reltuples::bigint AS approx_rows " +
                "  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                " WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog','information_schema') " +
                " ORDER BY pg_total_relation_size(c.oid) DESC LIMIT 12"));
        return out;
    }

    public record HousekeepingPatch(Boolean enabled, Integer events_days,
                                    Integer notifications_days, Boolean vacuum,
                                    Integer volume_gb, Integer checkpoint_every) {}

    @PutMapping("/housekeeping")
    public Map<String, Object> putHousekeeping(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @RequestBody HousekeepingPatch p) {
        String admin = requireAdmin(auth);
        if (p.events_days() != null) {
            if (p.events_days() < 0 || p.events_days() > 3650) throw bad("events window must be 0-3650 days");
            // A window shorter than a month throws away the evidence a
            // date-tamper question needs, which is the one thing raw events
            // are kept for.
            if (p.events_days() > 0 && p.events_days() < 30)
                throw bad("keep at least 30 days of events — they are what a date-tamper check reads");
            prune.putSetting("prune.events.days", String.valueOf(p.events_days()), admin);
        }
        if (p.notifications_days() != null) {
            if (p.notifications_days() < 0 || p.notifications_days() > 3650)
                throw bad("notifications window must be 0-3650 days");
            prune.putSetting("prune.notifications.days", String.valueOf(p.notifications_days()), admin);
        }
        if (p.enabled() != null) prune.putSetting("prune.enabled", String.valueOf(p.enabled()), admin);
        if (p.vacuum() != null) prune.putSetting("prune.vacuum", String.valueOf(p.vacuum()), admin);
        if (p.volume_gb() != null) {
            if (p.volume_gb() < 0 || p.volume_gb() > 10000) throw bad("volume size must be 0-10000 GB");
            prune.putSetting("prune.volume.gb", String.valueOf(p.volume_gb()), admin);
        }
        if (p.checkpoint_every() != null) {
            if (p.checkpoint_every() < 1 || p.checkpoint_every() > 100)
                throw bad("checkpoint every 1-100 batches");
            prune.putSetting("prune.checkpoint.every.batches", String.valueOf(p.checkpoint_every()), admin);
        }
        log.info("admin {} changed the housekeeping settings", admin);
        return Map.of("ok", true);
    }

    /**
     * Run it now. {@code dry=true} counts what would go and deletes nothing —
     * worth doing first on a shop you have never pruned.
     */
    @PostMapping("/housekeeping/run")
    public Map<String, Object> runPrune(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestParam(defaultValue = "false") boolean dry) {
        String admin = requireAdmin(auth);
        log.warn("admin {} started a prune by hand (dry={})", admin, dry);
        return prune.run(admin, dry);
    }

    // ── the numbers behind a shop row ─────────────────────────────────────────

    private List<Map<String, Object>> shopRows() {
        List<Map<String, Object>> tenantRows = jdbc.queryForList(
                "SELECT t.shop_id, t.display_name, t.schema_name, t.active, t.created_at, " +
                "       (t.magizhchi_token IS NOT NULL) AS photo_key, " +
                "       (SELECT count(*) FROM public.shop_credentials sc " +
                "         WHERE sc.shop_id = t.shop_id AND sc.revoked_at IS NULL) AS live_keys " +
                "  FROM public.tenants t ORDER BY t.shop_id");

        // One pass for every schema's size — cheaper than a query per shop.
        Map<String, Long> dbBytes = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT n.nspname AS schema, COALESCE(sum(pg_total_relation_size(c.oid)), 0) AS bytes " +
                "  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                " WHERE c.relkind = 'r' GROUP BY n.nspname")) {
            dbBytes.put((String) r.get("schema"), num(r.get("bytes")));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tenantRows) {
            String shopId = (String) t.get("shop_id");
            String schema = (String) t.get("schema_name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shop_id", shopId);
            row.put("display_name", t.get("display_name"));
            row.put("active", t.get("active"));
            row.put("created_at", t.get("created_at"));
            row.put("photo_key", t.get("photo_key"));
            row.put("live_keys", t.get("live_keys"));
            row.put("emails", jdbc.queryForList(
                    "SELECT email, role FROM public.user_shop_access " +
                    "WHERE shop_id = ? AND revoked_at IS NULL ORDER BY email", shopId));
            row.put("db_bytes", dbBytes.getOrDefault(schema, 0L));
            row.putAll(tenantNumbers(schema));
            out.add(row);
        }
        return out;
    }

    /**
     * What one shop holds. A schema that has not finished provisioning (or
     * was removed by hand) must not break the whole page, so a failure here
     * becomes zeroes and a flag rather than a 500.
     */
    private Map<String, Object> tenantNumbers(String schema) {
        Map<String, Object> m = new LinkedHashMap<>();
        String s;
        try {
            s = quoteSchema(schema);
        } catch (IllegalArgumentException e) {
            m.put("readable", false);
            m.put("photos", 0L); m.put("photo_bytes", 0L);
            m.put("backups", 0L); m.put("backup_bytes", 0L);
            m.put("box_bytes", 0L); m.put("rows", 0L);
            m.put("bills", 0L); m.put("customers", 0L); m.put("keyless_rows", 0L);
            m.put("last_event_at", null); m.put("hours_since_sync", null);
            return m;
        }
        try {
            Map<String, Object> r = jdbc.queryForMap(
                "SELECT (SELECT count(*) FROM " + s + ".bill_images) AS photos, " +
                "       (SELECT COALESCE(sum(file_size_bytes),0) FROM " + s + ".bill_images) AS photo_bytes, " +
                "       (SELECT count(*) FROM " + s + ".backup_files) AS backups, " +
                "       (SELECT COALESCE(sum(file_size_bytes),0) FROM " + s + ".backup_files) AS backup_bytes, " +
                "       (SELECT count(*) FROM " + s + ".projections WHERE NOT deleted) AS rows, " +
                // What a person means by "how big is this shop": its bills.
                // The row count above is every table the app keeps — bills,
                // customers, repledges, advances, day accounts and fourteen
                // ledgers — and is not a count of anything recognisable.
                "       (SELECT count(*) FROM " + s + ".projections " +
                "         WHERE table_name = 'company_billing' AND NOT deleted) AS bills, " +
                "       (SELECT count(*) FROM " + s + ".projections " +
                "         WHERE table_name = 'customer_details' AND NOT deleted) AS customers, " +
                // Rows the cloud had to invent a key for: one per event rather
                // than one per thing, so they pile up. This is the duplication
                // the shop-PC setup now fixes by giving those tables keys.
                "       (SELECT count(*) FROM " + s + ".projections " +
                "         WHERE row_pk LIKE 'evt:%' AND NOT deleted) AS keyless_rows, " +
                "       (SELECT max(received_at) FROM " + s + ".events) AS last_event_at");
            long photoBytes = num(r.get("photo_bytes"));
            long backupBytes = num(r.get("backup_bytes"));
            m.put("readable", true);
            m.put("bills", num(r.get("bills")));
            m.put("customers", num(r.get("customers")));
            m.put("keyless_rows", num(r.get("keyless_rows")));
            m.put("photos", num(r.get("photos")));
            m.put("photo_bytes", photoBytes);
            m.put("backups", num(r.get("backups")));
            m.put("backup_bytes", backupBytes);
            m.put("box_bytes", photoBytes + backupBytes);
            m.put("rows", num(r.get("rows")));
            Object last = r.get("last_event_at");
            m.put("last_event_at", last);
            m.put("hours_since_sync", last == null ? null
                    : Math.max(0, (System.currentTimeMillis() - ((java.sql.Timestamp) last).getTime()) / 3_600_000L));
        } catch (Exception e) {
            log.warn("cannot read tenant schema {}: {}", schema, e.toString());
            m.put("readable", false);
            m.put("photos", 0L); m.put("photo_bytes", 0L);
            m.put("backups", 0L); m.put("backup_bytes", 0L);
            m.put("box_bytes", 0L); m.put("rows", 0L);
            m.put("bills", 0L); m.put("customers", 0L); m.put("keyless_rows", 0L);
            m.put("last_event_at", null); m.put("hours_since_sync", null);
        }
        return m;
    }

    private String mintApiKey(String shopId, String label) {
        String key = "mbk_" + UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO public.shop_credentials(api_key, shop_id, label) VALUES (?,?,?)",
                    key, shopId, label);
        return key;
    }

    private BigDecimal monthlyCost() {
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(sum(monthly_amount), 0) FROM public.cost_items", BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total.setScale(2, RoundingMode.HALF_UP);
    }

    private String currency() {
        List<String> c = jdbc.queryForList(
                "SELECT currency FROM public.cost_items ORDER BY id LIMIT 1", String.class);
        return c.isEmpty() ? "INR" : c.get(0);
    }

    // ── guards ────────────────────────────────────────────────────────────────

    /** @return the signed-in admin's email. Throws 401 for anything else. */
    private String requireAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first");
        Claims c;
        try {
            c = jwt.parse(authHeader.substring(7).trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "your sign-in has expired");
        }
        if (!"admin".equals(c.get("kind", String.class)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this is not an admin sign-in");
        String email = normalizeEmail(c.getSubject());
        // Checked every call: an admin removed a minute ago is out now, not
        // when their token expires.
        if (!isAdmin(email))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this address is no longer an admin");
        return email;
    }

    private boolean isAdmin(String email) {
        if (email == null || email.isEmpty()) return false;
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM public.admin_users WHERE email = ? AND revoked_at IS NULL",
                Integer.class, email);
        return n != null && n > 0;
    }

    private String requireExistingShop(String shopId) {
        String id = requireShopId(shopId);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM public.tenants WHERE shop_id = ?", Integer.class, id);
        if (n == null || n == 0) throw bad("no shop called " + id);
        return id;
    }

    /** A shop id becomes a schema name, so it may only ever be [a-z0-9_]. */
    private static String requireShopId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase();
        if (!id.matches("[a-z0-9_]{2,40}"))
            throw bad("shop id must be 2-40 characters, lowercase letters, digits or _ " +
                      "(it becomes the database schema name)");
        return id;
    }

    private static String quoteSchema(String schema) {
        if (schema == null || !schema.matches("[a-z0-9_]+"))
            throw new IllegalArgumentException("bad schema name: " + schema);
        return "\"" + schema + "\"";
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
