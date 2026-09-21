package com.magizhchi.cloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes sure the admin console has at least one way in.
 *
 * ADMIN_EMAILS (comma separated) is read on every boot and those addresses
 * are added if missing — the way back in when the console has locked
 * everyone out. It never removes anyone: admins are taken off from the page
 * itself, and a stale env var should not quietly undo that.
 */
@Component
@Order(20)   // after TenantBootstrap, so the tenants exist before anyone signs in
public class AdminBootstrap implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final String adminEmails;

    public AdminBootstrap(JdbcTemplate jdbc,
                          @Value("${pawnbroking.admin.emails:}") String adminEmails) {
        this.jdbc = jdbc;
        this.adminEmails = adminEmails;
    }

    @Override
    public void run(String... args) {
        int added = 0;
        for (String raw : adminEmails.split(",")) {
            String email = raw.trim().toLowerCase();
            if (email.isEmpty() || !email.contains("@")) continue;
            added += jdbc.update(
                    "INSERT INTO public.admin_users(email, added_by) VALUES (?, 'ADMIN_EMAILS') " +
                    "ON CONFLICT (email) DO UPDATE SET revoked_at = NULL", email);
        }
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM public.admin_users WHERE revoked_at IS NULL", Integer.class);
        if (live == null || live == 0) {
            log.warn("NO ADMIN can open /admin.html. Set the ADMIN_EMAILS variable to an address " +
                     "that has a Magizhchi Share account and restart.");
        } else if (added > 0) {
            log.info("admin console: {} admin(s), {} from ADMIN_EMAILS this boot", live, added);
        }
    }
}
