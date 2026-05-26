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

    public BoxAuthController(JdbcTemplate jdbc, JwtService jwt,
                             @Value("${pawnbroking.box.url}") String boxUrl) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.boxUrl = boxUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.box = new MagizhchiBoxClient(this.boxUrl);
    }

    public record SendOtpRequest(String email, String shop_id) {}
    public record VerifyRequest (String email, String code, String shop_id) {}

    @PostMapping("/send-otp")
    public Map<String,Object> sendOtp(@RequestBody SendOtpRequest req) {
        requireField(req.email(),   "email");
        requireField(req.shop_id(), "shop_id");
        String email = req.email().trim().toLowerCase();
        requireAllowedEmail(req.shop_id(), email);

        String body = "{\"identifier\":\"" + jsonEscape(email) + "\"}";
        JsonNode boxRes = callBox("/api/auth/login/send-otp", body);
        return Map.of("message", boxRes.path("message").asText("Verification code sent."));
    }

    @PostMapping("/verify")
    public Map<String,Object> verify(@RequestBody VerifyRequest req) {
        requireField(req.email(),   "email");
        requireField(req.code(),    "code");
        requireField(req.shop_id(), "shop_id");
        String email = req.email().trim().toLowerCase();
        requireAllowedEmail(req.shop_id(), email);

        String body = "{\"identifier\":\"" + jsonEscape(email) + "\","
                    + "\"code\":\""        + jsonEscape(req.code().trim()) + "\"}";
        JsonNode boxRes = callBox("/api/auth/login/verify", body);

        // Capture the box's accessToken and (if we don't already have one)
        // mint a long-lived mbk_ API token for this tenant. Used later by
        // BillImageController to push/pull bill images on the user's behalf.
        captureMagizhchiTokenIfMissing(req.shop_id(), boxRes);

        // Upsert user and mint OUR JWT (cloud-api's, not the box's). The
        // shop_id claim drives tenant resolution on every subsequent call.
        Long userId = upsertUser(req.shop_id(), email);
        String token = jwt.mint(userId, req.shop_id(), "user");
        return Map.of(
            "access_token",  token,
            "shop_id",       req.shop_id(),
            "user_id",       userId,
            "role",          "user",
            "email",         email,
            "display_name",  boxRes.path("displayName").asText(email)
        );
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode callBox(String path, String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(boxUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("box call failed: {}", e.toString());
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
     * Ensures (a) the shop exists and is active, and (b) the email matches
     * the designated primary_email for that shop. The Android app for shop
     * X can only be signed into by the one email recorded in tenants.primary_email;
     * any other address is rejected before the box is even contacted.
     */
    private void requireAllowedEmail(String shopId, String emailLower) {
        List<String> rows = jdbc.queryForList(
            "SELECT primary_email FROM public.tenants WHERE shop_id = ? AND active = TRUE",
            String.class, shopId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown shop_id");
        }
        String allowed = rows.get(0);
        if (allowed == null || allowed.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this shop has no primary email configured — ask admin to set tenants.primary_email");
        }
        if (!allowed.trim().equalsIgnoreCase(emailLower)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this email is not authorised for this shop");
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
