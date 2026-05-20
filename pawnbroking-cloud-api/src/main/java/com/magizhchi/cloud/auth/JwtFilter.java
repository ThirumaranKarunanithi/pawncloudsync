package com.magizhchi.cloud.auth;

import com.magizhchi.cloud.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    public JwtFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) { chain.doFilter(req, res); return; }
        String token = h.substring(7).trim();
        if (token.split("\\.").length != 3) { chain.doFilter(req, res); return; } // not a JWT

        try {
            Claims c = jwt.parse(token);
            String shop = c.get("shop_id", String.class);
            String role = c.get("role", String.class);
            TenantContext.set(shop);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "user:" + c.getSubject(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))));
            chain.doFilter(req, res);
        } catch (Exception e) {
            res.sendError(401, "invalid jwt");
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
