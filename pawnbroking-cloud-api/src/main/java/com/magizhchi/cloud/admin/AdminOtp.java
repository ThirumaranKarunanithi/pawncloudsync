package com.magizhchi.cloud.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The same two Magizhchi Share endpoints the shop owners' login uses, for
 * the admin console's own sign-in.
 *
 * It is a separate class rather than a call into {@link
 * com.magizhchi.cloud.auth.BoxAuthController} on purpose: that controller is
 * the live path every shop signs in through, and the admin page is not worth
 * a change to it. The box stays the only thing that decides whether an email
 * really received a code; this class just asks it.
 */
@Component
public class AdminOtp {
    private static final Logger log = LoggerFactory.getLogger(AdminOtp.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final String boxUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public AdminOtp(@Value("${pawnbroking.box.url}") String boxUrl) {
        this.boxUrl = boxUrl.replaceAll("/+$", "");
    }

    public String sendOtp(String email) {
        JsonNode res = call("/api/auth/login/send-otp",
                "{\"identifier\":\"" + escape(email) + "\"}");
        return res.path("message").asText("Verification code sent.");
    }

    /** @return the display name the box knows, or the email. Throws on a wrong code. */
    public String verify(String email, String code) {
        JsonNode res = call("/api/auth/login/verify",
                "{\"identifier\":\"" + escape(email) + "\",\"code\":\"" + escape(code) + "\"}");
        return res.path("displayName").asText(email);
    }

    private JsonNode call(String path, String body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(boxUrl + path))
                .timeout(Duration.ofSeconds(30))   // the OTP email can be slow
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = null;
        Exception last = null;
        for (int attempt = 0; attempt < 2 && res == null; attempt++) {
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
                last = null;
            } catch (Exception e) {
                last = e;
                log.warn("box call attempt {} failed: {}", attempt + 1, e.toString());
            }
        }
        if (last != null || res == null)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "magizhchi share unreachable");
        if (res.statusCode() / 100 != 2)
            throw new ResponseStatusException(HttpStatus.valueOf(res.statusCode()),
                    message(res.body(), "share error " + res.statusCode()));
        try {
            return M.readTree(res.body());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "unreadable answer from share");
        }
    }

    private static String message(String body, String fallback) {
        try {
            JsonNode n = M.readTree(body);
            String m = n.path("message").asText(null);
            if (m == null || m.isBlank()) m = n.path("error").asText(null);
            return (m == null || m.isBlank()) ? fallback : m;
        } catch (Exception e) { return fallback; }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
