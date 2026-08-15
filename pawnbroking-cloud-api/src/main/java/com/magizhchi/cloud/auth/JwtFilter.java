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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public JwtFilter(JwtService jwt, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            // Diagnostic: any /v1/data or /v1/auth/box request without auth is a client bug.
            String path = req.getRequestURI();
            if (path.startsWith("/v1/data") || path.startsWith("/v1/auth/box/verify")) {
                log.warn("missing Authorization header on {} {}  (UA={})",
                         req.getMethod(), path, req.getHeader("User-Agent"));
            }
            chain.doFilter(req, res); return;
        }
        String token = h.substring(7).trim();
        if (token.split("\\.").length != 3) {
            log.warn("Authorization present but not a JWT (parts={}) on {} {}",
                     token.split("\\.").length, req.getMethod(), req.getRequestURI());
            chain.doFilter(req, res); return;
        }

        // 1. Validate the JWT itself. ONLY a JWT problem returns 401 here.
        Claims c;
        try {
            c = jwt.parse(token);
        } catch (Exception e) {
            // Not one of ours — it may be a Magizhchi ID token from another
            // suite product (Meet's Pawn Shop rooms). That path resolves its
            // own tenant and finishes the request.
            if (jwt.suiteSsoEnabled() && trySuiteToken(token, req, res, chain)) return;
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

    /**
     * Magizhchi ID (suite SSO) path, used by Meet's Pawn Shop rooms.
     *
     * A suite token says WHO the caller is, never which shop — so the caller
     * names the shop with the {@code X-Shop-Id} header and
     * {@code public.user_shop_access} decides whether that email may read it.
     * The token alone grants nothing: without a matching grant this returns
     * 403, so a valid suite login can never reach another owner's shop.
     *
     * @return true when the request was handled here.
     */
    private boolean trySuiteToken(String token, HttpServletRequest req, HttpServletResponse res,
                                  FilterChain chain) throws ServletException, IOException {
        Claims c;
        try {
            c = jwt.parseSuite(token);
        } catch (Exception e) {
            return false;   // not a suite token either — caller reports 401
        }

        String email = firstNonBlank(c.get("email", String.class), c.getSubject());
        if (email == null) {
            res.sendError(401, "suite token carries no email");
            return true;
        }
        email = email.trim().toLowerCase();

        List<String> shops = jdbc.queryForList(
            "SELECT t.shop_id FROM public.tenants t " +
            "  JOIN public.user_shop_access uas " +
            "    ON uas.shop_id = t.shop_id AND uas.revoked_at IS NULL " +
            " WHERE lower(uas.email) = ? AND t.active = TRUE ORDER BY t.shop_id",
            String.class, email);
        if (shops.isEmpty()) {
            res.sendError(403, "this Magizhchi account has no pawn shop access");
            return true;
        }

        String wanted = firstNonBlank(req.getHeader("X-Shop-Id"), req.getParameter("shop_id"));
        String shop;
        if (wanted == null || wanted.isBlank()) {
            // One shop is unambiguous; more than one needs the caller to say.
            if (shops.size() > 1) {
                res.sendError(400, "X-Shop-Id required — this account can read " + shops.size() + " shops");
                return true;
            }
            shop = shops.get(0);
        } else {
            shop = wanted.trim();
            boolean allowed = shops.stream().anyMatch(s -> s.equalsIgnoreCase(shop));
            if (!allowed) {
                log.warn("suite user {} denied shop {}", email, shop);
                res.sendError(403, "no access to shop " + shop);
                return true;
            }
        }

        TenantContext.set(shop);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "suite:" + email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        return true;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
