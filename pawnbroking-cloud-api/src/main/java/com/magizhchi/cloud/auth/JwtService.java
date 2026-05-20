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
    private final Duration accessTtl;

    public JwtService(@Value("${pawnbroking.jwt.secret}") String secret,
                      @Value("${pawnbroking.jwt.access-ttl-minutes}") long minutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(minutes);
    }

    public String mint(long userId, String shopId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of("shop_id", shopId, "role", role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }
}
