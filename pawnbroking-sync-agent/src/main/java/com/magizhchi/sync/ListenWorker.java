package com.magizhchi.sync;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class ListenWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ListenWorker.class);
    private final Config cfg;
    private final OutboxDrainer drainer;
    private volatile boolean running = true;
    private volatile Connection currentConn;

    public ListenWorker(Config cfg, OutboxDrainer drainer) {
        this.cfg = cfg; this.drainer = drainer;
        new Thread(this, "listener-pg").start();
    }

    @Override
    public void run() {
        while (running) {
            try (Connection conn = DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)) {
                currentConn = conn;
                try (Statement st = conn.createStatement()) { st.execute("LISTEN sync_channel"); }
                PGConnection pg = conn.unwrap(PGConnection.class);
                log.info("LISTEN sync_channel started");
                while (running) {
                    // nudge so we drain anything already in the outbox at startup
                    drainer.nudge();
                    PGNotification[] notes = pg.getNotifications(10_000);
                    if (notes != null && notes.length > 0) {
                        log.debug("got {} notifications", notes.length);
                        drainer.nudge();
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("listener crashed, reconnecting in 3s: {}", e.toString());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    public void stop() {
        running = false;
        try { if (currentConn != null) currentConn.close(); } catch (Exception ignored) {}
    }
}
