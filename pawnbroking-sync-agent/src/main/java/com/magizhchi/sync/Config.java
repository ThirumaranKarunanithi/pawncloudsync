package com.magizhchi.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
    public final String dbUrl, dbUser, dbPassword;
    public final String cloudUrl, cloudApiKey, shopId;
    public final int batchSize, healthPort;
    public final long pollIntervalMs;
    public final long schemaGuardIntervalMs;
    public final long imageScanIntervalMs;
    public final String imageRootOverride; // optional; null = auto-resolve per company

    private Config(Properties p) {
        this.dbUrl                 = require(p, "db.url");
        this.dbUser                = require(p, "db.user");
        this.dbPassword            = require(p, "db.password");
        this.cloudUrl              = require(p, "cloud.url");
        this.cloudApiKey           = require(p, "cloud.api_key");
        this.shopId                = require(p, "shop.id");
        this.batchSize             = Integer.parseInt(p.getProperty("batch.size", "200"));
        this.pollIntervalMs        = Long.parseLong(p.getProperty("poll.interval.ms", "5000"));
        this.healthPort            = Integer.parseInt(p.getProperty("health.port", "17654"));
        this.schemaGuardIntervalMs = Long.parseLong(p.getProperty("schema.guard.interval.ms", "30000"));
        this.imageScanIntervalMs   = Long.parseLong(p.getProperty("image.scan.interval.ms",   "60000"));
        // Optional override; if blank we read camera_temp_file_name from each company.
        String root = p.getProperty("image.root", "").trim();
        this.imageRootOverride = root.isEmpty() ? null : root;
    }

    private static String require(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Missing config: " + k);
        return v.trim();
    }

    public static Config load() {
        String path = System.getenv().getOrDefault(
                "PAWNBROKING_SYNC_CONFIG",
                System.getenv("PROGRAMDATA") + "\\PawnBroking\\sync.properties");
        try {
            Properties p = new Properties();
            try (var in = Files.newInputStream(Path.of(path))) { p.load(in); }
            return new Config(p);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read config at " + path, e);
        }
    }
}
