package com.magizhchi.cloud.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magizhchi.cloud.share.MagizhchiBoxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Passwordless email-OTP login backed by Magizhchi Share ("the box").
 * The box owns identity (registration, OTP delivery, OTP verification).
 * We use it as an oracle for "this email proved ownership at time T", then
 * mint our OWN JWT with the shop_id claim so all downstream /v1/data calls
 * keep working unchanged.
 *
 * Flow:
 *   POST /v1/auth/box/send-otp  {email, shop_id}    → 200 OK
 *   POST /v1/auth/box/verify    {email, code, shop_id} → {access_token, ...}
 *
 * On verify success we upsert public.app_users so future password lookups
 * still see the user (with a sentinel password_hash that no BCrypt input
 * can ever match — box-OTP users can't log in via /v1/auth/mobile).
 */
@RestController
@RequestMapping("/v1/auth/box")
public class BoxAuthController {
    private static final Logger log = LoggerFactory.getLogger(BoxAuthController.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final String NO_PASSWORD = "!box-otp";   // unmatchable BCrypt input

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final String boxUrl;
    private final HttpClient http;
    private final MagizhchiBoxClient box;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder pwd
            = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public BoxAuthController(JdbcTemplate jdbc, JwtService jwt,
                             @Value("${pawnbroking.box.url}") String boxUrl) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.boxUrl = boxUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.box = new MagizhchiBoxClient(this.boxUrl);
    }

    // shop_id is optional now (multi-shop support). When omitted we look up
    // every shop the email has access to via public.user_shop_access.
    public record SendOtpRequest(String email, String shop_id) {}
    public record VerifyRequest (String email, String code, String shop_id) {}
    public record SelectShopRequest(String shop_id) {}
    public record SetPasswordRequest(String email, String code, String password) {}
    public record PasswordLoginRequest(String email, String password) {}

    @PostMapping("/send-otp")
    public Map<String,Object> sendOtp(@RequestBody SendOtpRequest req) {
        requireField(req.email(), "email");
        String email = req.email().trim().toLowerCase();
        // Email must be in user_shop_access for AT LEAST one active shop.
        // (No shop_id requirement — multi-shop emails work transparently.)
        List<String> shops = lookupShopsForEmail(email);
        if (shops.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this email is not authorised for any shop");
        }

        String body = "{\"identifier\":\"" + jsonEscape(email) + "\"}";
        JsonNode boxRes = callBox("/api/auth/login/send-otp", body);
        return Map.of("message", boxRes.path("message").asText("Verification code sent."));
    }

    @PostMapping("/verify")
    public Map<String,Object> verify(@RequestBody VerifyRequest req) {
        requireField(req.email(), "email");
        requireField(req.code(),  "code");
        String email = req.email().trim().toLowerCase();
        List<String> shops = lookupShopsForEmail(email);
        if (shops.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this email is not authorised for any shop");
        }

        String body = "{\"identifier\":\"" + jsonEscape(email) + "\","
                    + "\"code\":\""        + jsonEscape(req.code().trim()) + "\"}";
        JsonNode boxRes = callBox("/api/auth/login/verify", body);
        String displayName = boxRes.path("displayName").asText(email);
        return buildLoginResponse(email, shops, displayName, boxRes);
    }

    /**
     * Shared login-response builder used by BOTH OTP verify and password
     * login. Single-shop → full access token. Multi-shop → selector token
     * + shops list for the picker. {@code boxRes} may be null (password
     * path) — the box token is only captured when we have a box login.
     */
    private Map<String,Object> buildLoginResponse(String email, List<String> shops,
                                                   String displayName, JsonNode boxRes) {
        if (shops.size() == 1) {
            String shopId = shops.get(0);
            if (boxRes != null) captureMagizhchiTokenIfMissing(shopId, boxRes);
            Long userId = upsertUser(shopId, email);
            String token = jwt.mint(userId, shopId, "user");
            return Map.of(
                "access_token",  token,
                "shop_id",       shopId,
                "user_id",       userId,
                "role",          "user",
                "email",         email,
                "display_name",  displayName,
                "shops_count",   1
            );
        }
        String selector = jwt.mintSelector(email);
        List<Map<String,Object>> shopRows = jdbc.queryForList(
            "SELECT t.shop_id, COALESCE(t.display_name, t.shop_id) AS label " +
            "  FROM public.tenants t " +
            "  JOIN public.user_shop_access uas " +
            "    ON uas.shop_id = t.shop_id AND uas.revoked_at IS NULL " +
            " WHERE lower(uas.email) = ? AND t.active = TRUE " +
            " ORDER BY t.shop_id",
            email);
        return Map.of(
            "selector_token", selector,
            "shops",          shopRows,
            "email",          email,
            "display_name",   displayName,
            "shops_count",    shopRows.size()
        );
    }

    /**
     * Set (or change) the password for an email. Gated by a fresh OTP so
     * only someone who controls the inbox can set it. After this, the user
     * can sign in with {@link #passwordLogin} instead of waiting for OTP.
     */
    @PostMapping("/set-password")
    public Map<String,Object> setPassword(@RequestBody SetPasswordRequest req) {
        requireField(req.email(),    "email");
        requireField(req.code(),     "code");
        requireField(req.password(), "password");
        if (req.password().length() < 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password too short (min 4)");
        String email = req.email().trim().toLowerCase();
        List<String> shops = lookupShopsForEmail(email);
        if (shops.isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "email not authorised for any shop");

        // Verify the OTP with the box before allowing a password set.
        String body = "{\"identifier\":\"" + jsonEscape(email) + "\","
                    + "\"code\":\""        + jsonEscape(req.code().trim()) + "\"}";
        callBox("/api/auth/login/verify", body);   // throws if OTP wrong

        String hash = pwd.encode(req.password());
        jdbc.update(
            "INSERT INTO public.login_passwords(email, password_hash) VALUES (?,?) " +
            "ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, updated_at = now()",
            email, hash);
        return Map.of("ok", true, "message", "Password set. You can now log in with it.");
    }

    /**
     * Password login — no OTP, no box round-trip. Verifies the stored
     * BCrypt hash and returns the same single/multi-shop response as OTP
     * verify. Image features still work because the box token was captured
     * during the earlier OTP login (password login doesn't re-capture it).
     */
    @PostMapping("/password-login")
    public Map<String,Object> passwordLogin(@RequestBody PasswordLoginRequest req) {
        requireField(req.email(),    "email");
        requireField(req.password(), "password");
        String email = req.email().trim().toLowerCase();
        List<String> shops = lookupShopsForEmail(email);
        if (shops.isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "email not authorised for any shop");

        List<String> hashes = jdbc.queryForList(
            "SELECT password_hash FROM public.login_passwords WHERE email = ?",
            String.class, email);
        if (hashes.isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "no password set — sign in with OTP first, then set a password");
        if (!pwd.matches(req.password(), hashes.get(0)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong password");

        return buildLoginResponse(email, shops, email, /*boxRes*/ null);
    }

    /**
     * Exchange a token + chosen shop_id for a full access token. Accepts
     * EITHER a selector token (issued by /verify when the email has 2+
     * shops) OR a live access token (so the in-app Switch-Shop menu can
     * call it without re-OTPing). The email→shop authorisation check
     * applies in both cases.
     */
    @PostMapping("/select-shop")
    public Map<String,Object> selectShop(@RequestHeader("Authorization") String auth,
                                          @RequestBody SelectShopRequest req) {
        requireField(req.shop_id(), "shop_id");
        String email = readEmailFromAnyToken(auth);
        List<String> shops = lookupShopsForEmail(email);
        if (!shops.contains(req.shop_id())) {
            log.warn("/select-shop denied: email='{}' has {} shops {} — not in list",
                     email, shops.size(), shops);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this email is not authorised for that shop");
        }
        Long userId = upsertUser(req.shop_id(), email);
        String token = jwt.mint(userId, req.shop_id(), "user");
        log.info("/select-shop: email='{}' switched to shop_id='{}'", email, req.shop_id());
        return Map.of(
            "access_token", token,
            "shop_id",      req.shop_id(),
            "user_id",      userId,
            "role",         "user",
            "email",        email
        );
    }

    /** Lists every shop the authenticated email can switch to. Works with
     *  both a selector token AND a normal access token (so the in-app
     *  Switch-Shop menu can call it without re-OTPing). */
    @GetMapping("/my-shops")
    public Map<String,Object> myShops(@RequestHeader("Authorization") String auth) {
        String email = readEmailFromAnyToken(auth);
        List<Map<String,Object>> shopRows = jdbc.queryForList(
            "SELECT t.shop_id, COALESCE(t.display_name, t.shop_id) AS label " +
            "  FROM public.tenants t " +
            "  JOIN public.user_shop_access uas " +
            "    ON uas.shop_id = t.shop_id AND uas.revoked_at IS NULL " +
            " WHERE lower(uas.email) = ? AND t.active = TRUE " +
            " ORDER BY t.shop_id",
            email);
        // Diagnostic — surface what the endpoint computed so multi-shop
        // mismatches are easy to triage from Railway logs OR the response
        // payload itself (the picker just ignores the extra fields).
        log.info("/my-shops resolved email='{}' → {} shops: {}",
                 email, shopRows.size(), shopRows);
        Map<String,Object> out = new java.util.LinkedHashMap<>();
        out.put("shops", shopRows);
        out.put("email", email);
        out.put("resolved_count", shopRows.size());
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode callBox(String path, String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(boxUrl + path))
                .timeout(Duration.ofSeconds(30))   // box OTP email can be slow
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = null;
        // One retry on transient network/timeout — the box occasionally
        // takes >15s to send the OTP email, which surfaced to users as
        // "Server didn't respond in time".
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                log.warn("box call attempt {} failed: {}", attempt + 1, e.toString());
            }
        }
        if (last != null || res == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "magizhchi box unreachable");
        }
        if (res.statusCode() / 100 != 2) {
            String msg = extractMessage(res.body(), "box error " + res.statusCode());
            // Propagate the box's status code so the client can react (e.g. 404 = unknown email)
            throw new ResponseStatusException(HttpStatus.valueOf(res.statusCode()), msg);
        }
        try {
            return M.readTree(res.body());
        } catch (Exception e) {
            return M.createObjectNode();
        }
    }

    /**
     * One-shot bootstrap: on the very first successful OTP login for a
     * tenant, use the box's freshly-issued accessToken to mint a long-
     * lived mbk_ API token and stash it on public.tenants. From then on
     * BillImageController uses that key to push/pull image bytes without
     * needing the user to be online.
     */
    private void captureMagizhchiTokenIfMissing(String shopId, JsonNode loginRes) {
        // Already have one? Skip — same token works until revoked.
        Integer existing = jdbc.queryForObject(
            "SELECT count(*) FROM public.tenants " +
            "WHERE shop_id = ? AND magizhchi_token IS NOT NULL", Integer.class, shopId);
        if (existing != null && existing > 0) return;

        String accessToken = loginRes.path("accessToken").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("box login returned no accessToken — cannot mint mbk_ for {}", shopId);
            return;
        }
        try {
            String mbk = box.issueApiToken(accessToken,
                "Pawnbroking-Cloud (" + shopId + ")");
            Long convId = box.personalConversationId(mbk);
            jdbc.update(
                "UPDATE public.tenants SET magizhchi_token = ?, magizhchi_conversation_id = ? WHERE shop_id = ?",
                mbk, convId, shopId);
            log.info("minted Magizhchi API token for shop '{}' (conv={})", shopId, convId);
        } catch (Exception e) {
            log.error("failed to mint Magizhchi token for {}: {}", shopId, e.toString());
            // Don't fail the user's login — image features just stay disabled.
        }
    }

    private Long upsertUser(String shopId, String email) {
        jdbc.update(
            "INSERT INTO public.app_users(shop_id, username, password_hash, role) " +
            "VALUES (?,?,?, 'user') " +
            "ON CONFLICT (shop_id, username) DO NOTHING",
            shopId, email, NO_PASSWORD);
        List<Long> ids = jdbc.queryForList(
            "SELECT user_id FROM public.app_users WHERE shop_id = ? AND username = ?",
            Long.class, shopId, email);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "could not provision user");
        return ids.get(0);
    }

    /**
     * Returns every active shop_id the given email has non-revoked access
     * to. Drives the multi-shop flow: 1 shop → mint access token directly;
     * 2+ shops → return selector + picker list.
     */
    private List<String> lookupShopsForEmail(String emailLower) {
        return jdbc.queryForList(
            "SELECT t.shop_id " +
            "  FROM public.tenants t " +
            "  JOIN public.user_shop_access uas " +
            "    ON uas.shop_id = t.shop_id AND uas.revoked_at IS NULL " +
            " WHERE lower(uas.email) = ? AND t.active = TRUE " +
            " ORDER BY t.shop_id",
            String.class, emailLower);
    }

    /**
     * Reads the email from either a selector token (subject = email) OR a
     * normal access token (subject = user_id, then look up the email).
     * Used by /my-shops so the in-app Switch-Shop menu works after login.
     */
    private String readEmailFromAnyToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer");
        io.jsonwebtoken.Claims c;
        try { c = jwt.parse(authHeader.substring(7).trim()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad token"); }

        if ("selector".equals(c.get("kind", String.class))) {
            log.info("readEmailFromAnyToken: selector token → email='{}'", c.getSubject());
            return c.getSubject();
        }
        // Access token: subject is user_id, look up username (= email for box-OTP users).
        try {
            long userId = Long.parseLong(c.getSubject());
            List<String> emails = jdbc.queryForList(
                "SELECT username FROM public.app_users WHERE user_id = ?",
                String.class, userId);
            if (emails.isEmpty()) {
                log.warn("readEmailFromAnyToken: user_id={} has NO app_users row", userId);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user");
            }
            String email = emails.get(0).toLowerCase();
            log.info("readEmailFromAnyToken: user_id={} → email='{}' (shop_id_in_jwt={})",
                     userId, email, c.get("shop_id", String.class));
            return email;
        } catch (NumberFormatException e) {
            log.warn("readEmailFromAnyToken: subject not a user_id: '{}'", c.getSubject());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad token subject");
        }
    }

    private static void requireField(String v, String name) {
        if (v == null || v.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractMessage(String body, String fallback) {
        try {
            JsonNode n = M.readTree(body);
            String m = n.path("message").asText(null);
            if (m == null || m.isBlank()) m = n.path("error").asText(null);
            return (m == null || m.isBlank()) ? fallback : m;
        } catch (Exception e) { return fallback; }
    }
}
