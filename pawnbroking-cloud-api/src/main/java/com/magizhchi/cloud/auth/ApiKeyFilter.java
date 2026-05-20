package com.magizhchi.cloud.auth;

import com.magizhchi.cloud.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final JdbcTemplate jdbc;
    public ApiKeyFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/v1/sync")) { chain.doFilter(req, res); return; }

        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            res.sendError(401, "missing bearer"); return;
        }
        String key = h.substring(7).trim();
        // skip JWTs - they look like a.b.c
        if (key.split("\\.").length == 3) { chain.doFilter(req, res); return; }

        List<String> rows = jdbc.queryForList(
                "SELECT shop_id FROM public.shop_credentials WHERE api_key = ? AND revoked_at IS NULL",
                String.class, key);
        if (rows.isEmpty()) { res.sendError(401, "bad api key"); return; }

        String shopId = rows.get(0);
        TenantContext.set(shopId);
        try {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "agent:" + shopId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
