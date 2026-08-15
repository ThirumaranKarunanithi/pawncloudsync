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
    public final long imageUploadIntervalMs;
    public final String imageRootOverride;
    /** Only sync backup files modified within this many days (0 = no limit). */
    public final int backupRetentionDays; // optional; null = auto-resolve per company
    /** Gzip backup files before upload (pg_dump -F t output compresses ~5-10x). */
    public final boolean backupGzip;
    /** Only gzip files at least this large — tiny files aren't worth the CPU. */
    public final long backupGzipMinBytes;

    private Config(Properties p) {
        this.dbUrl                 = require(p, "db.url");
        this.dbUser                = require(p, "db.user");
        this.dbPassword            = require(p, "db.password");
        this.cloudUrl              = require(p, "cloud.url");
        this.cloudApiKey           = require(p, "cloud.api_key");
        this.shopId                = require(p, "shop.id");
        // The cloud spends roughly 0.4s per event (a savepoint and a tenant switch
        // each), so 200 needs about 80s against CloudClient's 30s request timeout:
        // every batch was aborted mid-flight and retried forever, which looks like
        // a dead agent rather than a batch that is simply too big. 25 lands near
        // 10s and leaves room for a slow day.
        this.batchSize             = Integer.parseInt(p.getProperty("batch.size", "25"));
        this.pollIntervalMs        = Long.parseLong(p.getProperty("poll.interval.ms", "5000"));
        this.healthPort            = Integer.parseInt(p.getProperty("health.port", "17654"));
        this.schemaGuardIntervalMs = Long.parseLong(p.getProperty("schema.guard.interval.ms", "30000"));
        this.imageScanIntervalMs   = Long.parseLong(p.getProperty("image.scan.interval.ms",   "60000"));
        // Time between consecutive image uploads (ms). 1100 = ~54 req/min (Magizhchi box limit
        // is 60/min). Lower = faster but risk of 429. Set to 500-700 if your plan allows.
        this.imageUploadIntervalMs = Long.parseLong(p.getProperty("image.upload.interval.ms", "1100"));
        // Optional override; if blank we read camera_temp_file_name from each company.
        String root = p.getProperty("image.root", "").trim();
        this.imageRootOverride = root.isEmpty() ? null : root;
        this.backupRetentionDays = Integer.parseInt(p.getProperty("backup.retention.days", "30"));
        // Backups are pg_dump tar-format (uncompressed) and run 500MB+. Proxying
        // that through the cloud fails with 502; gzip brings it to ~10-20% and
        // cuts box storage by the same factor. Set backup.gzip=false to disable.
        this.backupGzip          = Boolean.parseBoolean(p.getProperty("backup.gzip", "true"));
        this.backupGzipMinBytes  = Long.parseLong(p.getProperty("backup.gzip.min.bytes", "5242880")); // 5 MB
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
