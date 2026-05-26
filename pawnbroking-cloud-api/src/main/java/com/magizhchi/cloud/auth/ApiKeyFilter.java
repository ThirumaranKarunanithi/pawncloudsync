package com.magizhchi.cloud.auth;

import com.magizhchi.cloud.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** For sync agents: Bearer <api_key> -> resolves shop_id from shop_credentials. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private final JdbcTemplate jdbc;
    public ApiKeyFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // sync-agent → /v1/sync (event drain) and /v1/bills/image (image upload).
        // Anything else (mobile JWT paths) is handled by JwtFilter.
        boolean apiKeyPath = path.startsWith("/v1/sync") || path.startsWith("/v1/bills/image");
        if (!apiKeyPath) { chain.doFilter(req, res); return; }
        log.info("ApiKeyFilter saw {} {}", req.getMethod(), path);

        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            log.warn("missing/invalid Authorization header on {}", path);
            res.sendError(401, "missing bearer"); return;
        }
        String key = h.substring(7).trim();
        // skip JWTs - they look like a.b.c
        if (key.split("\\.").length == 3) {
            log.debug("looks like a JWT, skipping API key path");
            chain.doFilter(req, res); return;
        }

        // 1. Look up the API key. Failures here are auth problems.
        String shopId;
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT shop_id FROM public.shop_credentials WHERE api_key = ? AND revoked_at IS NULL",
                    String.class, key);
            if (rows.isEmpty()) {
                log.warn("unknown api key (prefix={})", key.length() > 10 ? key.substring(0, 10) : key);
                res.sendError(401, "bad api key"); return;
            }
            shopId = rows.get(0);
        } catch (Exception e) {
            log.error("api key lookup failed: {}", e.toString(), e);
            res.sendError(503, "auth backend unavailable");
            return;
        }

        // 2. Populate context and let the controller run.
        //    Controller exceptions must NOT be swallowed here.
        log.info("authenticated agent:{}", shopId);
        TenantContext.set(shopId);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "agent:" + shopId, null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
