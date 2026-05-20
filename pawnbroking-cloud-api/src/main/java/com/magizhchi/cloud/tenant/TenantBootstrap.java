package com.magizhchi.cloud.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Provisions a Postgres schema per tenant (alwarpuram, annanagar, ...).
 * Runs on startup; idempotent. Reads schema list from `pawnbroking.tenants`.
 */
@Component
public class TenantBootstrap implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final JdbcTemplate jdbc;
    private final String tenantsCsv;

    public TenantBootstrap(JdbcTemplate jdbc,
                           @Value("${pawnbroking.tenants}") String tenantsCsv) {
        this.jdbc = jdbc;
        this.tenantsCsv = tenantsCsv;
    }

    @Override
    public void run(String... args) throws Exception {
        String ddl;
        try (var in = new ClassPathResource("db/tenant/tenant.sql").getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String raw : tenantsCsv.split(",")) {
            String shopId = raw.trim().toLowerCase();
            if (shopId.isEmpty()) continue;
            if (!shopId.matches("[a-z0-9_]+")) {
                log.warn("skipping invalid tenant id '{}'", shopId);
                continue;
            }
            provision(shopId);
        }
    }

    public void provision(String shopId) {
        String schema = shopId; // schema name == shop id
        log.info("provisioning tenant schema '{}'", schema);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
        jdbc.update("INSERT INTO public.tenants(shop_id, schema_name, display_name) " +
                    "VALUES (?,?,?) ON CONFLICT (shop_id) DO NOTHING",
                    shopId, schema, capitalize(shopId));

        // apply DDL inside the tenant schema using search_path
        try (var conn = jdbc.getDataSource().getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute("SET search_path TO " + quote(schema));
                String ddl = new String(new ClassPathResource("db/tenant/tenant.sql")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                st.execute(ddl);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply tenant DDL to " + schema, e);
        }

        // Auto-create an API key + default admin user if none yet
        Integer keyCount = jdbc.queryForObject(
                "SELECT count(*) FROM public.shop_credentials WHERE shop_id = ?",
                Integer.class, shopId);
        if (keyCount == null || keyCount == 0) {
            String apiKey = shopId.toUpperCase() + "_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO public.shop_credentials(api_key, shop_id, label) VALUES (?,?,?)",
                        apiKey, shopId, "auto-bootstrap");
            log.warn("API KEY for shop '{}' = {}   (give this to that shop's sync agent)", shopId, apiKey);
        }
        Integer userCount = jdbc.queryForObject(
                "SELECT count(*) FROM public.app_users WHERE shop_id = ?", Integer.class, shopId);
        if (userCount == null || userCount == 0) {
            String hash = new BCryptPasswordEncoder().encode("admin");
            jdbc.update("INSERT INTO public.app_users(shop_id, username, password_hash, role) " +
                        "VALUES (?,?,?,'admin')", shopId, "admin", hash);
            log.warn("default mobile login for shop '{}': admin / admin   (CHANGE IT)", shopId);
        }
    }

    private static String quote(String ident) {
        if (!ident.matches("[a-z0-9_]+"))
            throw new IllegalArgumentException("bad ident: " + ident);
        return "\"" + ident + "\"";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
