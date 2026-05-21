package com.magizhchi.cloud.auth;

import com.magizhchi.cloud.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);
    private final JwtService jwt;
    public JwtFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) { chain.doFilter(req, res); return; }
        String token = h.substring(7).trim();
        if (token.split("\\.").length != 3) { chain.doFilter(req, res); return; } // not a JWT

        // 1. Validate the JWT itself. ONLY a JWT problem returns 401 here.
        Claims c;
        try {
            c = jwt.parse(token);
        } catch (Exception e) {
            log.warn("jwt validation failed: {}", e.toString());
            res.sendError(401, "invalid jwt");
            return;
        }

        // 2. Populate context, then let the rest of the chain run.
        //    Any exception below is a CONTROLLER problem and must NOT be
        //    rewritten as 401 — let Spring's normal error handling map it.
        String shop = c.get("shop_id", String.class);
        String role = c.get("role", String.class);
        if (shop != null) TenantContext.set(shop);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user:" + c.getSubject(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role.toUpperCase())))));

        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
