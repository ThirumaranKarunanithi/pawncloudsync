package com.magizhchi.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Periodically ensures the sync infrastructure (sync_outbox table,
 * sync_capture function, per-table triggers) exists in the local DB.
 *
 * Solves the "I just restored my DB and now sync is broken" problem:
 * every {@link #intervalMs} this checks whether the schema is intact,
 * and if not, re-runs the V1 + V2 migrations from the bundled resources.
 *
 * Migrations are idempotent so this is safe to run repeatedly.
 */
public class SchemaGuard implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SchemaGuard.class);
    private final DataSource ds;
    private final long intervalMs;
    private final String shopId;
    private volatile boolean running = true;

    /** Tables we install (do not count them as "user tables" needing triggers). */
    private static final String OWN_TABLES =
            "'sync_outbox','sync_outbox_dlq'";

    public SchemaGuard(DataSource ds, String shopId, long intervalMs) {
        this.ds = ds;
        this.shopId = shopId;
        this.intervalMs = intervalMs;
    }

    public void stop() { running = false; }

    /** Synchronous repair — call once at startup before drainer/listener start. */
    public synchronized void ensureNow() {
        try (Connection conn = ds.getConnection()) {
            boolean repaired = false;

            if (!tableExists(conn, "sync_outbox")) {
                log.warn("sync_outbox missing -> applying V1");
                executeSql(conn, loadResource("/migrations/V1__sync_outbox.sql"));
                repaired = true;
            }

            int triggersBefore = countSyncTriggers(conn);
            int userTables     = countUserTables(conn);
            if (triggersBefore < userTables) {
                log.warn("only {} of {} user tables have sync triggers -> applying V2",
                        triggersBefore, userTables);
                executeSql(conn, loadResource("/migrations/V2__attach_all_triggers.sql"));
                repaired = true;
            }

            // Always make sure the shop_id default is set
            try (Statement st = conn.createStatement()) {
                String dbName;
                try (ResultSet rs = st.executeQuery("SELECT current_database()")) {
                    rs.next(); dbName = rs.getString(1);
                }
                st.execute("ALTER DATABASE \"" + dbName + "\" SET app.shop_id = '" + shopId + "'");
            } catch (Exception e) {
                log.debug("could not set DB-level shop_id default: {}", e.getMessage());
            }

            if (repaired) {
                int triggersAfter = countSyncTriggers(conn);
                log.info("schema repair complete: {} sync triggers now attached", triggersAfter);
            } else {
                log.debug("schema check OK ({} triggers attached)", triggersBefore);
            }
        } catch (Exception e) {
            log.error("schema guard failure: {}", e.toString(), e);
        }
    }

    @Override
    public void run() {
        while (running) {
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (!running) return;
            ensureNow();
        }
    }

    // ---------------- helpers ----------------

    private static boolean tableExists(Connection conn, String table) throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema = current_schema() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static int countSyncTriggers(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM pg_trigger " +
                "WHERE tgname LIKE 'trg_sync_%' AND NOT tgisinternal")) {
            rs.next(); return rs.getInt(1);
        }
    }

    private static int countUserTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema = current_schema() " +
                "  AND table_type   = 'BASE TABLE' " +
                "  AND table_name NOT IN (" + OWN_TABLES + ") " +
                "  AND table_name NOT LIKE 'flyway_%' " +
                "  AND table_name NOT LIKE 'pg_%' " +
                "  AND table_name <> 'spatial_ref_sys'")) {
            rs.next(); return rs.getInt(1);
        }
    }

    private static void executeSql(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String loadResource(String path) {
        try (InputStream in = SchemaGuard.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("failed to load " + path, e);
        }
    }
}
