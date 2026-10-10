package com.magizhchi.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

public class HealthServer {
    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);
    private final int port;
    private HttpServer server;
    private final ObjectMapper M = new ObjectMapper();

    public HealthServer(int port) { this.port = port; }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", ex -> {
            AgentState s = Agent.STATE.get();
            Map<String,Object> body = new LinkedHashMap<>();
            // "status" used to be the literal string "ok" whatever was
            // happening, so an agent refusing every batch with 401 still
            // read as healthy. It now answers ok / degraded / failing.
            // The HTTP code stays 200 on purpose: upload-progress.ps1
            // calls this through Invoke-RestMethod inside an empty
            // catch, so a 503 would blank its health panel at exactly
            // the moment the panel is worth reading.
            body.put("status", s.health());
            body.put("lag_events", s.lagEvents.get());
            body.put("sent_total", s.sentTotal.get());
            body.put("dlq_total", s.dlqTotal.get());
            body.put("last_sent_at", s.lastSentAt == null ? null : s.lastSentAt.toString());
            body.put("last_batch_size", s.lastBatchSize.get());
            body.put("consecutive_failures", s.consecutiveFailures.get());
            body.put("last_error", s.lastError);
            body.put("last_error_at", s.lastErrorAt == null ? null : s.lastErrorAt.toString());
            byte[] out = M.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.setExecutor(null);
        server.start();
        log.info("health server on http://127.0.0.1:{}/health", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }
}
