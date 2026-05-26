package com.magizhchi.sync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    static final AtomicReference<AgentState> STATE = new AtomicReference<>(new AgentState());

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        log.info("starting sync agent for shop={} -> {}", cfg.shopId, cfg.cloudUrl);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.dbUrl);
        hc.setUsername(cfg.dbUser);
        hc.setPassword(cfg.dbPassword);
        hc.setMaximumPoolSize(4);
        hc.setPoolName("pawnbroking-sync");
        HikariDataSource ds = new HikariDataSource(hc);

        // Self-heal the local DB schema BEFORE anything else touches sync_outbox.
        // If the user just restored the DB and lost our triggers, this fixes it.
        SchemaGuard guard = new SchemaGuard(ds, cfg.shopId, cfg.schemaGuardIntervalMs);
        guard.ensureNow();

        CloudClient cloud = new CloudClient(cfg);
        OutboxDrainer drainer = new OutboxDrainer(ds, cloud, cfg);
        ListenWorker listener = new ListenWorker(cfg, drainer);
        HealthServer health = new HealthServer(cfg.healthPort);

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown signal received");
            drainer.stop();
            listener.stop();
            guard.stop();
            health.stop();
            ds.close();
            stop.countDown();
        }));

        Thread tDrain  = new Thread(drainer,  "drainer");
        Thread tListen = new Thread(listener, "listener");
        Thread tGuard  = new Thread(guard,    "schema-guard");
        tDrain.start();
        tListen.start();
        tGuard.start();
        health.start();

        stop.await();
        log.info("sync agent stopped cleanly");
    }
}
