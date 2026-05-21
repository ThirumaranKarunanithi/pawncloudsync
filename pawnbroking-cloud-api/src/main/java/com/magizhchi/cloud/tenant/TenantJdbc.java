package com.magizhchi.cloud.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.function.Function;

/**
 * Runs a JDBC block with `SET LOCAL search_path` pointing at the current
 * tenant's schema. Wrap every tenant-scoped query in this.
 *
 * Uses Spring's SingleConnectionDataSource with suppressClose=true so the
 * JdbcTemplate's per-statement close() is a no-op — many statements can
 * therefore run on the same physical connection (and same search_path).
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

            SingleConnectionDataSource wrapper = new SingleConnectionDataSource(conn, true);
            try {
                JdbcTemplate t = new JdbcTemplate(wrapper);
                T result = work.apply(t);
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                throw e;
            } finally {
                wrapper.destroy();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
