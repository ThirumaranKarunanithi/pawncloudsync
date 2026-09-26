package com.magizhchi.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Scans the bill-image directories on the shop PC and POSTs new files to
 * the cloud-api's /v1/bills/image endpoint. Tracks every successful upload
 * in the local {@code sync_image_uploads} table so we don't re-send the
 * same file every poll.
 *
 * Layout expected on disk:
 *   {@code <camera_temp_file_name>/<companyId>/<materialType>/<billNumber>/<imageName>.png}
 *
 * The root path comes either from {@code image.root} in sync.properties
 * (override) or from {@code company_other_settings.camera_temp_file_name}
 * looked up per company. Different companies can have different roots.
 */
public class ImageWatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ImageWatcher.class);
    private static final Random RNG = new Random();

    private final DataSource ds;
    private final Config cfg;
    private final HttpClient http;
    private volatile boolean running = true;
    /** Throttle between uploads, configurable via image.upload.interval.ms.
     *  Magizhchi Share rate limit is 60 req/min/token (1000ms is the floor). */
    private long lastUploadAt = 0L;

    public ImageWatcher(DataSource ds, Config cfg) {
        this.ds = ds;
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void stop() { running = false; }

    /** Idempotent — creates both tracker tables if missing. */
    public void ensureTrackerTable() { ensureTrackerTables(ds); }

    /** Same thing without an agent: {@link Setup} calls this so the tables
     *  (and therefore the progress report) exist before the service runs. */
    public static void ensureTrackerTables(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(
                "CREATE TABLE IF NOT EXISTS sync_image_uploads (" +
                "  abs_path     TEXT PRIMARY KEY," +
                "  company_id   TEXT NOT NULL," +
                "  material     TEXT NOT NULL," +
                "  bill_number  TEXT NOT NULL," +
                "  image_name   TEXT NOT NULL," +
                "  size_bytes   BIGINT NOT NULL," +
                "  mtime        TIMESTAMPTZ NOT NULL," +
                "  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()" +
                ")");
            s.execute(
                "CREATE TABLE IF NOT EXISTS sync_backup_uploads (" +
                "  abs_path      TEXT PRIMARY KEY," +
                "  company_id    TEXT NOT NULL," +
                "  relative_path TEXT NOT NULL," +
                "  file_name     TEXT NOT NULL," +
                "  size_bytes    BIGINT NOT NULL," +
                "  mtime         TIMESTAMPTZ NOT NULL," +
                "  uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now()" +
                ")");
        } catch (Exception e) {
            log.error("could not ensure tracker tables: {}", e.toString());
        }
    }

    @Override
    public void run() {
        ensureTrackerTable();
        // Backups run on their OWN thread. Otherwise a huge first-time image
        // backlog (tens of thousands of files at ~1.1s each = many hours)
        // would block scanBackupsOnce() from ever being called, since both
        // used to run sequentially in this loop. Separating them lets backups
        // upload immediately even while images are still draining.
        Thread backupThread = new Thread(() -> {
            while (running) {
                try { scanBackupsOnce(); }
                catch (Throwable t) { log.warn("backup scan failed: {}", t.toString()); }
                try { Thread.sleep(cfg.imageScanIntervalMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }, "backup-watcher");
        backupThread.setDaemon(true);
        backupThread.start();

        while (running) {
            try { scanOnce(); } catch (Throwable t) { log.warn("image scan failed: {}", t.toString()); }
            try { Thread.sleep(cfg.imageScanIntervalMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        log.info("ImageWatcher stopped");
    }

    private void scanOnce() throws Exception {
        // 1. Build the list of {companyId, root} pairs to walk.
        List<CompanyRoot> roots = resolveRoots();
        if (roots.isEmpty()) {
            log.debug("no image roots configured");
            return;
        }

        Set<String> alreadyUploaded = loadAlreadyUploadedPaths();
        int scanned = 0, uploaded = 0, skipped = 0;

        for (CompanyRoot cr : roots) {
            Path root = Paths.get(cr.root);
            if (!Files.isDirectory(root)) {
                log.debug("image root for {} does not exist: {}", cr.companyId, cr.root);
                continue;
            }
            try (var stream = Files.walk(root, 5)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString().toLowerCase();
                    if (!name.endsWith(".png") && !name.endsWith(".jpg")
                        && !name.endsWith(".jpeg") && !name.endsWith(".webp")) continue;
                    scanned++;
                    String abs = p.toAbsolutePath().toString();
                    if (alreadyUploaded.contains(abs)) { skipped++; continue; }
                    // Two supported layouts under the image root:
                    //   BILLS:     <companyId>/<material>/<billNumber>/<imageName>   (4 levels)
                    //   CUSTOMERS: …/CUSTOMERS/<customerId>/<imageName>             (a
                    //              CUSTOMERS/CUSTOMER segment anywhere in the path)
                    // Customer photos are stored in the box under
                    //   bills/<companyId>/CUSTOMER/<customerId>/<file>
                    // reusing the same upload pipeline + bill_images index.
                    Path rel = root.relativize(p);
                    int n = rel.getNameCount();
                    String companyId, material, billNumber, imageName;

                    int custIdx = -1;
                    for (int i = 0; i < n; i++) {
                        String seg = rel.getName(i).toString();
                        if (seg.equalsIgnoreCase("CUSTOMERS") || seg.equalsIgnoreCase("CUSTOMER")) {
                            custIdx = i; break;
                        }
                    }

                    if (custIdx >= 0 && custIdx + 1 < n) {
                        // Customer photo.
                        companyId  = cr.companyId;
                        material   = "CUSTOMER";
                        billNumber = rel.getName(custIdx + 1).toString(); // customerId
                        imageName  = rel.getFileName().toString();
                    } else if (n == 4) {
                        // Bill photo (original layout).
                        companyId  = rel.getName(0).toString();
                        material   = rel.getName(1).toString();
                        billNumber = rel.getName(2).toString();
                        imageName  = rel.getName(3).toString();
                        if (!companyId.equalsIgnoreCase(cr.companyId)) continue;
                    } else {
                        continue; // unknown layout — skip stray files
                    }

                    try {
                        uploadOne(p, companyId, material, billNumber, imageName);
                        uploaded++;
                    } catch (Exception ue) {
                        log.warn("upload failed for {}: {}", abs, ue.getMessage());
                    }
                }
            }
        }
        if (uploaded > 0 || scanned > 0) {
            log.info("image scan: roots={} scanned={} uploaded={} skipped={}",
                     roots.size(), scanned, uploaded, skipped);
        }
    }

    private record CompanyRoot(String companyId, String root) {}

    private List<CompanyRoot> resolveRoots() throws Exception {
        List<CompanyRoot> out = new ArrayList<>();
        // Dedup by companyId so we don't walk the same root twice when a
        // company has multiple material rows (e.g., CMP2 GOLD + CMP2 SILVER).
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT company_id, camera_temp_file_name FROM company_other_settings " +
                "WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String cid  = rs.getString(1);
                String root = cfg.imageRootOverride != null
                        ? cfg.imageRootOverride
                        : rs.getString(2);
                if (cid != null && root != null && !root.isBlank() && seen.add(cid))
                    out.add(new CompanyRoot(cid, root));
            }
        }
        // Shuffle so no single company always wins all upload budget in a
        // scan — gives CMP2 fair access alongside CMP3 etc.
        java.util.Collections.shuffle(out, RNG);
        return out;
    }

    private Set<String> loadAlreadyUploadedPaths() throws Exception {
        Set<String> out = new HashSet<>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT abs_path FROM sync_image_uploads")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private void uploadOne(Path file, String companyId, String material,
                           String billNumber, String imageName) throws Exception {
        // Client-side throttle so we stay under the box's 60 req/min limit.
        throttle();

        byte[] bytes = Files.readAllBytes(file);
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        String contentType = guessContentType(imageName);

        String boundary = "PawnSyncImage" + Math.abs(RNG.nextLong());
        byte[] body = buildMultipart(boundary, bytes, file.getFileName().toString(),
                                     contentType, companyId, material, billNumber, imageName);

        HttpRequest req = HttpRequest.newBuilder(
                URI.create(cfg.cloudUrl + "/v1/bills/image"))
                .header("Authorization", "Bearer " + cfg.cloudApiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());

        // Transient failures — back off and retry up to 3 times. Covers 429
        // (rate limit) plus 502/503/504 (box or cloud briefly unavailable).
        // Previously only 429/502-rate-limit retried, so a 503 burst churned
        // through files one-per-second with no backoff and no retry.
        int attempt = 0;
        while (isTransient(r) && attempt < 3) {
            attempt++;
            long retryS = parseRetryAfter(r);
            if (retryS <= 0) retryS = Math.min(30, 2L * attempt); // 2s,4s,6s default
            log.info("box transient {} on {} — retry {}/3 in {}s",
                     r.statusCode(), file.getFileName(), attempt, retryS);
            Thread.sleep(retryS * 1000L);
            lastUploadAt = 0L; // reset throttle so retry isn't doubly delayed
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("cloud upload status=" + r.statusCode() + " body=" + r.body());
        }
        recordUploaded(file.toAbsolutePath().toString(), companyId, material,
                       billNumber, imageName, attrs.size(), attrs.lastModifiedTime().toInstant());
    }

    /** True for statuses worth retrying: rate-limit + transient gateway errors. */
    private static boolean isTransient(HttpResponse<String> r) {
        int s = r.statusCode();
        if (s == 429 || s == 502 || s == 503 || s == 504) return true;
        return false;
    }

    private synchronized void throttle() throws InterruptedException {
        long interval = cfg.imageUploadIntervalMs;
        long since = System.currentTimeMillis() - lastUploadAt;
        if (since < interval) Thread.sleep(interval - since);
        lastUploadAt = System.currentTimeMillis();
    }

    private static long parseRetryAfter(HttpResponse<String> r) {
        // Prefer the Retry-After header; fall back to "Retry in Ns" in the body.
        long hdr = r.headers().firstValue("Retry-After")
                .map(s -> { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; } })
                .orElse(0L);
        if (hdr > 0) return hdr;
        if (r.body() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Retry in (\\d+)s").matcher(r.body());
            if (m.find()) try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
        }
        return 5;  // safe default
    }

    private void recordUploaded(String absPath, String companyId, String material,
                                String billNumber, String imageName,
                                long size, java.time.Instant mtime) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_image_uploads(abs_path, company_id, material, bill_number, " +
                "                               image_name, size_bytes, mtime) " +
                "VALUES (?,?,?,?,?,?, ?::timestamptz) " +
                "ON CONFLICT (abs_path) DO UPDATE SET " +
                "  size_bytes=EXCLUDED.size_bytes, mtime=EXCLUDED.mtime, uploaded_at=now()")) {
            ps.setString(1, absPath);
            ps.setString(2, companyId);
            ps.setString(3, material);
            ps.setString(4, billNumber);
            ps.setString(5, imageName);
            ps.setLong  (6, size);
            ps.setString(7, mtime.toString());
            ps.executeUpdate();
        }
    }

    private static byte[] buildMultipart(String boundary, byte[] fileBytes, String filename,
                                          String contentType, String companyId, String material,
                                          String billNumber, String imageName) {
        var baos = new java.io.ByteArrayOutputStream();
        try {
            appendTextPart(baos, boundary, "companyId",    companyId);
            appendTextPart(baos, boundary, "materialType", material);
            appendTextPart(baos, boundary, "billNumber",   billNumber);
            appendTextPart(baos, boundary, "imageName",    imageName);
            // file last
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + filename.replace("\"", "\\\"") + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            baos.write(head.getBytes(StandardCharsets.UTF_8));
            baos.write(fileBytes);
            baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return baos.toByteArray();
    }

    private static void appendTextPart(java.io.ByteArrayOutputStream baos, String boundary,
                                       String name, String value) throws IOException {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n";
        baos.write(s.getBytes(StandardCharsets.UTF_8));
    }

    // ── Backup folder sync ────────────────────────────────────────────────────

    /** Walk each company's backup_file_path and POST any new file to
     *  /v1/files/backup. Shares the box rate-limit throttle with images. */
    private void scanBackupsOnce() throws Exception {
        List<CompanyRoot> roots = resolveBackupRoots();
        if (roots.isEmpty()) return;
        java.util.Map<String, long[]> alreadyUploaded = loadAlreadyUploadedBackups();
        int scanned = 0, uploaded = 0, skipped = 0;
        for (CompanyRoot cr : roots) {
            Path root = Paths.get(cr.root);
            if (!Files.isDirectory(root)) {
                log.debug("backup root for {} does not exist: {}", cr.companyId, cr.root);
                continue;
            }
            // Only sync backups modified within the retention window — old
            // daily dumps (100MB+ each) would otherwise flood the box. Default
            // 30 days; override with backup.retention.days in sync.properties.
            long cutoffMs = System.currentTimeMillis()
                    - (long) cfg.backupRetentionDays * 24L * 60L * 60L * 1000L;
            try (var stream = Files.walk(root)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    scanned++;
                    String abs = p.toAbsolutePath().toString();
                    long[] sent = alreadyUploaded.get(abs);
                    if (sent != null) {
                        // Re-send only when the file genuinely changed: a different
                        // size, or an mtime clearly newer than what we shipped. The
                        // 5s margin keeps timestamp-precision differences between
                        // NTFS and Postgres from causing an endless re-upload loop.
                        long sz = Files.size(p);
                        long mt = Files.getLastModifiedTime(p).toMillis();
                        if (sz == sent[0] && mt <= sent[1] + 5000) { skipped++; continue; }
                        log.info("backup changed since last upload, re-sending: {}", abs);
                    }
                    // Skip files older than the retention window.
                    if (cfg.backupRetentionDays > 0) {
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (mtime < cutoffMs) { skipped++; continue; }
                    }
                    Path rel = root.relativize(p);
                    String fileName = rel.getFileName().toString();
                    Path parent = rel.getParent();
                    String relPath = parent == null ? "" : parent.toString().replace('\\', '/');
                    try {
                        uploadBackup(p, cr.companyId, relPath, fileName);
                        uploaded++;
                    } catch (Exception ue) {
                        log.warn("backup upload failed for {}: {}", abs, ue.getMessage());
                    }
                }
            }
        }
        if (uploaded > 0 || scanned > 0) {
            log.info("backup scan: roots={} scanned={} uploaded={} skipped={}",
                     roots.size(), scanned, uploaded, skipped);
        }
    }

    private List<CompanyRoot> resolveBackupRoots() throws Exception {
        List<CompanyRoot> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id, backup_file_path FROM company " +
                "WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String cid  = rs.getString(1);
                String root = rs.getString(2);
                if (cid != null && root != null && !root.isBlank())
                    out.add(new CompanyRoot(cid, root));
            }
        }
        return out;
    }

    /**
     * abs_path -> {size_bytes, mtime_millis} of what we last shipped. Keyed on
     * path but carrying size+mtime so a file REPLACED at the same path (a
     * re-run backup overwriting today's dump) is detected and re-uploaded
     * instead of being skipped forever.
     */
    private java.util.Map<String, long[]> loadAlreadyUploadedBackups() throws Exception {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT abs_path, size_bytes, " +
                 "       (extract(epoch from mtime) * 1000)::bigint FROM sync_backup_uploads")) {
            while (rs.next()) out.put(rs.getString(1), new long[]{ rs.getLong(2), rs.getLong(3) });
        }
        return out;
    }

    private void uploadBackup(Path file, String companyId, String relPath, String fileName) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);

        // pg_dump tar-format backups run 500MB+ and get killed with 502 when
        // proxied through the cloud. Gzip first — these compress ~5-10x, which
        // also cuts upload time and box storage by the same factor. The ORIGINAL
        // path and size are what we record, so dedupe/retention are unaffected.
        Path gzTmp = null;
        if (cfg.backupGzip && attrs.size() >= cfg.backupGzipMinBytes
                && !isAlreadyCompressed(fileName)) {
            gzTmp = Files.createTempFile("pawnsync-backup-", ".gz");
            gzTmp.toFile().deleteOnExit();   // safety net if we die mid-upload
            long t0 = System.currentTimeMillis();
            gzipTo(file, gzTmp);
            log.info("gzipped {}: {} MB -> {} MB in {}s", fileName,
                     attrs.size() >> 20, Files.size(gzTmp) >> 20,
                     (System.currentTimeMillis() - t0) / 1000);
        }
        try {
            postBackup(gzTmp != null ? gzTmp : file,
                       gzTmp != null ? fileName + ".gz" : fileName,
                       companyId, relPath);
        } finally {
            if (gzTmp != null) Files.deleteIfExists(gzTmp);
        }
        recordBackupUploaded(file, companyId, relPath, fileName, attrs);
    }

    /** POSTs one file to the cloud as multipart, retrying transient failures. */
    private void postBackup(Path file, String fileName, String companyId, String relPath)
            throws Exception {
        throttle();
        String contentType = guessContentType(fileName);
        String boundary = "PawnSyncBackup" + Math.abs(RNG.nextLong());

        // Build head (text parts + file part header) and tail as byte arrays,
        // then STREAM the file body between them so a 100MB+ backup is never
        // loaded into the agent's heap. The file part must come last because
        // the field parts precede it.
        var headBaos = new java.io.ByteArrayOutputStream();
        appendTextPart(headBaos, boundary, "companyId",    companyId);
        appendTextPart(headBaos, boundary, "relativePath", relPath);
        appendTextPart(headBaos, boundary, "fileName",     fileName);
        headBaos.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName.replace("\"", "\\\"") + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] headB = headBaos.toByteArray();
        byte[] tailB = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        // Fresh streams per attempt (a consumed InputStream can't be replayed).
        java.util.function.Supplier<java.io.InputStream> bodySupplier = () -> {
            try {
                return new java.io.SequenceInputStream(
                    new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(headB),
                        Files.newInputStream(file)),
                    new java.io.ByteArrayInputStream(tailB));
            } catch (IOException e) { throw new RuntimeException(e); }
        };

        java.util.function.Supplier<HttpRequest> reqSupplier = () -> HttpRequest.newBuilder(
                URI.create(cfg.cloudUrl + "/v1/files/backup"))
                .header("Authorization", "Bearer " + cfg.cloudApiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(30))   // big files need a long window
                .POST(HttpRequest.BodyPublishers.ofInputStream(bodySupplier))
                .build();

        HttpResponse<String> r = http.send(reqSupplier.get(), HttpResponse.BodyHandlers.ofString());
        // Transient (429/502/503/504) → back off and retry up to 3 times.
        int attempt = 0;
        while (isTransient(r) && attempt < 3) {
            attempt++;
            long retryS = parseRetryAfter(r);
            if (retryS <= 0) retryS = Math.min(30, 2L * attempt);
            log.info("box transient {} (backup) on {} — retry {}/3 in {}s",
                     r.statusCode(), fileName, attempt, retryS);
            Thread.sleep(retryS * 1000L);
            lastUploadAt = 0L;
            r = http.send(reqSupplier.get(), HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("cloud backup status=" + r.statusCode() + " body=" + r.body());
        }
    }

    /** Streams src through GZIP into dst — constant memory whatever the size. */
    private static void gzipTo(Path src, Path dst) throws IOException {
        try (java.io.InputStream in =
                     new java.io.BufferedInputStream(Files.newInputStream(src), 1 << 16);
             java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(
                     new java.io.BufferedOutputStream(Files.newOutputStream(dst), 1 << 16), 1 << 16)) {
            in.transferTo(out);
        }
    }

    /** Re-compressing these burns CPU and usually makes the file slightly bigger. */
    private static boolean isAlreadyCompressed(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".gz")  || n.endsWith(".zip")  || n.endsWith(".7z")
            || n.endsWith(".rar") || n.endsWith(".jpg")  || n.endsWith(".jpeg")
            || n.endsWith(".png") || n.endsWith(".pdf");
    }

    /** Marks the ORIGINAL (uncompressed) file as sent so it isn't re-uploaded. */
    private void recordBackupUploaded(Path file, String companyId, String relPath,
                                      String fileName, BasicFileAttributes attrs) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_backup_uploads(abs_path, company_id, relative_path, file_name, " +
                "                                size_bytes, mtime) " +
                "VALUES (?,?,?,?,?, ?::timestamptz) " +
                "ON CONFLICT (abs_path) DO UPDATE SET " +
                "  size_bytes=EXCLUDED.size_bytes, mtime=EXCLUDED.mtime, uploaded_at=now()")) {
            ps.setString(1, file.toAbsolutePath().toString());
            ps.setString(2, companyId);
            ps.setString(3, relPath);
            ps.setString(4, fileName);
            ps.setLong  (5, attrs.size());
            ps.setString(6, attrs.lastModifiedTime().toInstant().toString());
            ps.executeUpdate();
        }
    }

    private static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))                        return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp"))                       return "image/webp";
        return "application/octet-stream";
    }
}
