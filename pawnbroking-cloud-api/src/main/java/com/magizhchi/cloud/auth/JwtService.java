package com.magizhchi.cloud.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {
    private final SecretKey key;
    /** Magizhchi ID's shared suite secret; null when SSO isn't configured. */
    private final SecretKey suiteKey;
    private final Duration accessTtl;

    public JwtService(@Value("${pawnbroking.jwt.secret}") String secret,
                      @Value("${pawnbroking.jwt.access-ttl-minutes}") long minutes,
                      @Value("${pawnbroking.magizhchi.jwt.secret:}") String suiteSecret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(minutes);
        // HS256 needs >= 32 bytes; a short or absent value means "not configured"
        // rather than a startup failure, so shops keep working without SSO.
        this.suiteKey = (suiteSecret == null || suiteSecret.trim().length() < 32) ? null
                : Keys.hmacShaKeyFor(suiteSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    public boolean suiteSsoEnabled() { return suiteKey != null; }

    /**
     * Validates a Magizhchi ID (suite SSO) token. It proves WHO the caller is
     * across the suite; it carries no shop_id, so the caller must say which
     * shop it wants and {@code user_shop_access} decides whether they may.
     */
    public Claims parseSuite(String token) {
        if (suiteKey == null) throw new IllegalStateException("magizhchi sso not configured");
        return Jwts.parser().verifyWith(suiteKey).build().parseSignedClaims(token).getPayload();
    }

    public String mint(long userId, String shopId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of("shop_id", shopId, "role", role, "kind", "access"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Short-lived "selector" token issued after OTP verification when the
     * email has access to 2+ shops. Carries only the email (no shop_id) and
     * is valid only at /v1/auth/my-shops + /v1/auth/select-shop. The app
     * exchanges it for a normal access token once the user picks a shop.
     */
    public String mintSelector(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claims(Map.of("kind", "selector"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(10))))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }
}
