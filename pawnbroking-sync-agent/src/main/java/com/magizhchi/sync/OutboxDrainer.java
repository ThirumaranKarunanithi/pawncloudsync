package com.magizhchi.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class OutboxDrainer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());

    private final DataSource ds;
    private final CloudClient cloud;
    private final Config cfg;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running = true;
    private long backoffMs = 1000;

    public OutboxDrainer(DataSource ds, CloudClient cloud, Config cfg) {
        this.ds = ds; this.cloud = cloud; this.cfg = cfg;
    }

    public void nudge() { wake.release(); }
    public void stop() { running = false; wake.release(); }

    @Override
    public void run() {
        while (running) {
            try {
                int drained = drainOnce();
                if (drained == 0) {
                    wake.tryAcquire(cfg.pollIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    wake.drainPermits();
                } else {
                    backoffMs = 1000; // success resets backoff
                }
            } catch (Exception e) {
                Agent.STATE.get().lastError = e.getMessage();
                // If sync_outbox was wiped by a DB restore, SchemaGuard will
                // recreate it within its check interval. Log quietly and wait.
                String msg = e.toString();
                if (msg.contains("relation \"sync_outbox\" does not exist")) {
                    log.warn("sync_outbox missing — waiting for SchemaGuard to repair...");
                    try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.warn("drain failed, backing off {}ms: {}", backoffMs, msg);
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    backoffMs = Math.min(backoffMs * 2, 5 * 60_000L);
                }
            }
        }
    }

    private int drainOnce() throws Exception {
        List<long[]> ids = new ArrayList<>();
        ArrayNode events = M.createArrayNode();

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_id, shop_id, table_name, op, row_pk, payload, created_at " +
                    "FROM sync_outbox WHERE sent_at IS NULL " +
                    "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED")) {
                ps.setInt(1, cfg.batchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode e = M.createObjectNode();
                        e.put("event_id",   rs.getString("event_id"));
                        e.put("shop_id",    rs.getString("shop_id"));
                        e.put("table",      rs.getString("table_name"));
                        e.put("op",         rs.getString("op"));
                        e.put("row_pk",     rs.getString("row_pk"));
                        e.set("payload",    M.readTree(rs.getString("payload")));
                        e.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                        events.add(e);
                    }
                }
            }

            if (events.isEmpty()) { c.commit(); return 0; }

            CloudClient.Result r = cloud.postBatch(cfg.shopId, events);

            if (r.success) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE sync_outbox SET sent_at = now() WHERE event_id = ANY (?::uuid[])")) {
                    String[] arr = events.findValuesAsText("event_id").toArray(new String[0]);
                    up.setArray(1, c.createArrayOf("uuid", arr));
                    up.executeUpdate();
                }
                c.commit();
                Agent.STATE.get().sentTotal.addAndGet(events.size());
                Agent.STATE.get().lastSentAt = Instant.now();
                Agent.STATE.get().lastBatchSize.set(events.size());
                log.info("sent {} events accepted={} duplicates={}", events.size(), r.accepted, r.duplicates);
            } else if (r.permanentFailure) {
                // 4xx (non-429) -> DLQ
                try (PreparedStatement dlq = c.prepareStatement(
                        "INSERT INTO sync_outbox_dlq SELECT * FROM sync_outbox WHERE event_id = ANY (?::uuid[])");
                     PreparedStatement del = c.prepareStatement(
                        "DELETE FROM sync_outbox WHERE event_id = ANY (?::uuid[])")) {
                    String[] arr = events.findValuesAsText("event_id").toArray(new String[0]);
                    Array a = c.createArrayOf("uuid", arr);
                    dlq.setArray(1, a); dlq.executeUpdate();
                    del.setArray(1, a); del.executeUpdate();
                }
                c.commit();
                Agent.STATE.get().dlqTotal.addAndGet(events.size());
                log.error("permanent failure, moved {} events to DLQ. status={} body={}",
                        events.size(), r.status, r.body);
            } else {
                // transient (5xx, 429, network) -> bump attempts, retry later
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE sync_outbox SET attempts = attempts + 1, last_error = ? " +
                        "WHERE event_id = ANY (?::uuid[])")) {
                    String[] arr = events.findValuesAsText("event_id").toArray(new String[0]);
                    up.setString(1, "status=" + r.status + " " + r.body);
                    up.setArray(2, c.createArrayOf("uuid", arr));
                    up.executeUpdate();
                }
                c.commit();
                throw new RuntimeException("transient cloud failure status=" + r.status);
            }
        }

        // refresh lag
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM sync_outbox WHERE sent_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) Agent.STATE.get().lagEvents.set(rs.getLong(1));
        }
        return events.size();
    }
}
