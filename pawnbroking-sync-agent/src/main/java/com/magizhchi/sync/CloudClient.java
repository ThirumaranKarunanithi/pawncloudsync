package com.magizhchi.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CloudClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper M = new ObjectMapper();
    private final Config cfg;

    public CloudClient(Config cfg) { this.cfg = cfg; }

    public Result postBatch(String shopId, ArrayNode events) {
        try {
            ObjectNode body = M.createObjectNode();
            body.put("shop_id", shopId);
            body.set("events", events);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.cloudUrl + "/v1/sync"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + cfg.cloudApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            Result r = new Result();
            r.status = resp.statusCode();
            r.body   = resp.body();
            if (r.status >= 200 && r.status < 300) {
                r.success = true;
                try {
                    var node = M.readTree(resp.body());
                    r.accepted   = node.path("accepted").asInt();
                    r.duplicates = node.path("duplicates").asInt();
                } catch (Exception ignored) {}
            } else if ((r.status >= 400 && r.status < 500) && r.status != 408 && r.status != 429) {
                r.permanentFailure = true;
            }
            return r;
        } catch (Exception e) {
            Result r = new Result();
            r.status = -1; r.body = e.getMessage();
            return r;
        }
    }

    public static class Result {
        public boolean success;
        public boolean permanentFailure;
        public int status;
        public String body;
        public int accepted;
        public int duplicates;
    }
}
