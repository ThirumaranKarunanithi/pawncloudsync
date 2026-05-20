package com.magizhchi.cloud.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs a JDBC block with `SET LOCAL search_path` pointing at the current
 * tenant's schema. Wrap every tenant-scoped query in this.
 */
@Component
public class TenantJdbc {
    private final DataSource ds;

    public TenantJdbc(DataSource ds) { this.ds = ds; }

    public <T> T inTenant(Function<JdbcTemplate, T> work) {
        String shop = TenantContext.get();
        if (shop == null) throw new IllegalStateException("no tenant in context");
        if (!shop.matches("[a-z0-9_]+")) throw new IllegalStateException("bad shop: " + shop);
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SET LOCAL search_path TO \"" + shop + "\", public");
            }
            JdbcTemplate t = new JdbcTemplate(new SingleConnectionDataSource(conn));
            T result = work.apply(t);
            conn.commit();
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
