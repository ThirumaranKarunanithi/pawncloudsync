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

        if (cfg.imageRootWrittenPlain) {
            log.info("image.root in sync.properties is written with single backslashes; "
                   + "reading it as the plain Windows path {}", cfg.imageRootOverride);
        }
        while (running) {
            boolean more = false;
            try { more = scanOnce(); } catch (Throwable t) { log.warn("image scan failed: {}", t.toString()); }
            // A full pass went up and more are waiting: look again straight away, so
            // the newest are picked afresh rather than sitting out the interval.
            if (more) continue;
            try { Thread.sleep(cfg.imageScanIntervalMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        log.info("ImageWatcher stopped");
    }

    /** A photo on disk that has not gone up yet. */
    private record Photo(Path path, String companyId, String material, String billNumber,
                         String imageName, long mtime) {}

    /** A file that failed is left alone this long, so a few bad files at the head of
     *  the queue (the newest go first) cannot take every pass for themselves. */
    private static final long RETRY_FAILED_AFTER_MS = 30L * 60L * 1000L;
    /** Failures in a row that end a pass: the line is down, or the cloud is refusing. */
    private static final int GIVE_UP_AFTER_FAILURES = 10;
    private final java.util.Map<String, Long> failedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastQuietReport = 0L;

    /**
     * One pass: find every photo not yet sent, send the newest first, at most
     * {@code image.batch.size} of them.
     *
     * <p>The newest go first because a shop clearing a backlog of years (annanagar's
     * was about 100,000 photos, a day and a half) wants today's bills on the phone
     * today; folder order sent 2017 before 2026. The pass is bounded so that a photo
     * taken while the backlog drains is found within minutes.
     *
     * @return true when a full pass went up and more are waiting
     */
    private boolean scanOnce() throws Exception {
        AgentState state = Agent.STATE.get();
        List<CompanyRoot> roots = resolveRoots();
        if (roots.isEmpty()) {
            if (moanedAbout.add("photo:none")) {
                log.warn("NO PHOTO FOLDER IS SET - camera_temp_file_name is empty for every "
                       + "company and image.root is not set, so no bill photo can upload.");
            }
            state.photoFolderUnreadable = "no photo folder is set (camera_temp_file_name)";
            return false;
        }
        moanedAbout.remove("photo:none");

        // Companies often share one folder (annanagar's CMP2 and CMP3 both use
        // SW IMAGES). Walk each folder once and let the path say whose photo it is.
        java.util.Map<String, List<String>> byFolder = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CompanyRoot cr : roots) {
            byFolder.computeIfAbsent(cr.root.trim(), k -> new ArrayList<>()).add(cr.companyId);
        }

        Set<String> alreadyUploaded = loadAlreadyUploadedPaths();
        List<Photo> waiting = new ArrayList<>();
        int[] onDisk = {0}, unreadable = {0}, restingAfterFailure = {0};
        long now = System.currentTimeMillis();
        StringBuilder missing = new StringBuilder();

        for (var e : byFolder.entrySet()) {
            String folder = e.getKey();
            List<String> companies = e.getValue();
            java.util.Collections.sort(companies);
            Path root = Paths.get(folder);
            if (!Files.isDirectory(root)) {
                if (missing.length() > 0) missing.append("; ");
                missing.append(String.join(",", companies)).append(": ").append(folder);
                warnPhotoFolderOnce(folder, companies);
                continue;
            }
            forgetPhotoMoan(folder);
            Files.walkFileTree(root, java.util.EnumSet.noneOf(FileVisitOption.class), 5,
                    new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
                    if (!a.isRegularFile() || !isImage(p)) return FileVisitResult.CONTINUE;
                    Photo ph = classify(root, p, companies, a.lastModifiedTime().toMillis());
                    if (ph == null) return FileVisitResult.CONTINUE;   // a stray file
                    onDisk[0]++;
                    String abs = p.toAbsolutePath().toString();
                    if (alreadyUploaded.contains(abs)) return FileVisitResult.CONTINUE;
                    Long failed = failedAt.get(abs);
                    if (failed != null && now - failed < RETRY_FAILED_AFTER_MS) {
                        restingAfterFailure[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    waiting.add(ph);
                    return FileVisitResult.CONTINUE;
                }

                // Files.walk gave up on the whole folder at the first entry it could
                // not open, so one bad sub-folder stopped every photo. Count it, go on.
                @Override
                public FileVisitResult visitFileFailed(Path p, IOException ex) {
                    unreadable[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        state.photoFolderUnreadable = missing.length() == 0 ? null : missing.toString();

        waiting.sort(java.util.Comparator.comparingLong(Photo::mtime).reversed());
        int limit = Math.min(waiting.size(), Math.max(1, cfg.imageBatchSize));
        int uploaded = 0, failed = 0, inARow = 0;
        boolean stopped = false;
        for (int i = 0; i < limit && running; i++) {
            Photo ph = waiting.get(i);
            String abs = ph.path().toAbsolutePath().toString();
            try {
                uploadOne(ph.path(), ph.companyId(), ph.material(), ph.billNumber(), ph.imageName());
                uploaded++;
                inARow = 0;
                failedAt.remove(abs);
                state.recordPhotoSuccess();
            } catch (Exception ue) {
                failed++;
                inARow++;
                String why = ue.getMessage() == null ? ue.toString() : ue.getMessage();
                failedAt.put(abs, System.currentTimeMillis());
                state.recordPhotoFailure(why);
                if (noRoom(why)) {
                    log.error("PHOTOS REFUSED - THE SHOP'S CLOUD STORAGE IS FULL. No photo can go up "
                            + "until the shop's box plan is raised. File: {} - {}", abs, why);
                    stopped = true;
                    break;
                }
                log.warn("upload failed for {}: {}", abs, why);
                if (inARow >= GIVE_UP_AFTER_FAILURES) {
                    log.warn("{} photo uploads failed in a row - stopping this pass, trying again in {}s",
                             inARow, cfg.imageScanIntervalMs / 1000);
                    stopped = true;
                    break;
                }
            }
        }

        int left = waiting.size() - uploaded;
        state.photosWaiting = left + restingAfterFailure[0];
        if (uploaded > 0 || failed > 0) {
            log.info("image scan: folders={} photos on disk={} waiting={} uploaded={} failed={} left={}{}",
                     byFolder.size(), onDisk[0], waiting.size(), uploaded, failed, left,
                     unreadable[0] > 0 ? " unreadable entries=" + unreadable[0] : "");
        } else if (now - lastQuietReport > 60L * 60L * 1000L) {
            // Once an hour, so "[image-watcher]" in the log always says something.
            lastQuietReport = now;
            log.info("image scan: folders={} photos on disk={} nothing new to send{}",
                     byFolder.size(), onDisk[0],
                     restingAfterFailure[0] > 0 ? " (" + restingAfterFailure[0] + " resting after a failure)" : "");
        }
        return !stopped && left > 0 && uploaded == limit;
    }

    private static boolean isImage(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg")
            || name.endsWith(".jpeg") || name.endsWith(".webp");
    }

    /**
     * Whose photo this is, from where it sits under the folder:
     * <pre>
     *   BILLS:     &lt;companyId&gt;/&lt;material&gt;/&lt;billNumber&gt;/&lt;imageName&gt;   (4 levels)
     *   CUSTOMERS: .../CUSTOMERS/&lt;customerId&gt;/&lt;imageName&gt;            (a CUSTOMERS or
     *              CUSTOMER segment anywhere in the path)
     * </pre>
     * Customer photos go to the box under {@code bills/<companyId>/CUSTOMER/<customerId>/},
     * reusing the bill pipeline; a folder shared by several companies files them
     * under the first company id. Null for anything else.
     */
    private static Photo classify(Path root, Path p, List<String> companies, long mtime) {
        Path rel = root.relativize(p);
        int n = rel.getNameCount();
        for (int i = 0; i + 1 < n; i++) {
            String seg = rel.getName(i).toString();
            if (seg.equalsIgnoreCase("CUSTOMERS") || seg.equalsIgnoreCase("CUSTOMER")) {
                return new Photo(p, companies.get(0), "CUSTOMER", rel.getName(i + 1).toString(),
                                 rel.getFileName().toString(), mtime);
            }
        }
        if (n != 4) return null;
        String companyId = rel.getName(0).toString();
        for (String c : companies) {
            if (c.equalsIgnoreCase(companyId)) {
                return new Photo(p, companyId, rel.getName(1).toString(), rel.getName(2).toString(),
                                 rel.getName(3).toString(), mtime);
            }
        }
        return null;
    }

    /** Says once, loudly, that a photo folder is missing; quiet again until it changes. */
    private void warnPhotoFolderOnce(String folder, List<String> companies) {
        if (moanedAbout.add("photo:" + folder.toLowerCase())) {
            log.warn("THE PHOTO FOLDER CANNOT BE FOUND: {} (company {}). No photo from it is "
                   + "being sent. Check camera_temp_file_name for the company{}. If it is a "
                   + "mapped drive letter, the agent runs as a Windows service and cannot see one.",
                     folder, String.join(",", companies),
                     cfg.imageRootOverride != null ? ", and image.root in sync.properties (it overrides it)" : "");
        }
    }

    private void forgetPhotoMoan(String folder) {
        if (moanedAbout.remove("photo:" + folder.toLowerCase())) {
            log.info("the photo folder can be found again: {}", folder);
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
    /**
     * Whether the cloud refused for want of room — which is the one refusal
     * that never clears by itself.
     *
     * <p>507 is what cloud-api now answers; the older wording and a bare 413
     * are matched too, so an agent that has not been updated alongside the
     * cloud still says the right thing to whoever reads the log.
     */
    private static boolean noRoom(String why) {
        if (why == null) return false;
        String s = why.toLowerCase();
        return s.contains("status=507") || s.contains("storage limit")
            || s.contains("box is full") || s.contains("status=413");
    }

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
        // What the scan is actually running with, so the progress report can show it
        // rather than leave somebody guessing at a shop PC. retention.days=0 in
        // particular is invisible and expensive: it means every dump ever written is
        // offered again on every pass, so a month of dead files can sit in front of
        // tonight's and never let it through.
        Agent.STATE.get().backupRetentionDays = cfg.backupRetentionDays;
        Agent.STATE.get().backupGzipOn        = cfg.backupGzip;

        List<CompanyRoot> roots = resolveBackupRoots();
        if (roots.isEmpty()) return;
        java.util.Map<String, long[]> alreadyUploaded = loadAlreadyUploadedBackups();
        int scanned = 0, uploaded = 0, skipped = 0;
        StringBuilder unreadable = new StringBuilder();
        for (CompanyRoot cr : roots) {
            Path root = Paths.get(cr.root);
            if (!Files.isDirectory(root)) {
                // Not debug any more. A folder the agent cannot read looks exactly
                // like a folder with nothing new in it, and telling those two apart
                // from the cloud is impossible — the cloud sees no upload either way.
                // Say which folder, and say it where somebody will meet it.
                if (unreadable.length() > 0) unreadable.append("; ");
                unreadable.append(cr.companyId).append(": ").append(cr.root);
                warnOncePerPath(cr.root, cr.companyId);
                continue;
            }
            forgetMoan(cr.root);
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
                        Agent.STATE.get().recordBackupSuccess();
                    } catch (Exception ue) {
                        String why = ue.getMessage() == null ? ue.toString() : ue.getMessage();
                        boolean noRoom = noRoom(why);
                        Agent.STATE.get().recordBackupFailure(why, noRoom);
                        if (noRoom) {
                            // Not a warning. The shop's off-site copy of its
                            // database has stopped, and no amount of retrying
                            // will start it again — only more room will. This
                            // was a log.warn for a year, which is to say it was
                            // nothing at all: shops went months without a backup
                            // while every screen they could see said "ok".
                            log.error("BACKUP REFUSED — THE SHOP'S CLOUD STORAGE IS FULL. "
                                    + "Nothing is being backed up off-site. The daily PDF "
                                    + "report is small enough to still fit, so the phone may "
                                    + "look healthy; it is not. Raise the shop's box plan. "
                                    + "File: {} — {}", abs, why);
                        } else {
                            log.warn("backup upload failed for {}: {}", abs, why);
                        }
                    }
                }
            }
        }
        Agent.STATE.get().backupFolderUnreadable =
                unreadable.length() == 0 ? null : unreadable.toString();
        if (uploaded > 0 || scanned > 0) {
            log.info("backup scan: roots={} scanned={} uploaded={} skipped={}",
                     roots.size(), scanned, uploaded, skipped);
        }
    }

    /**
     * Complains about an unreadable backup folder once, not once a minute.
     *
     * <p>The scan runs every {@code image.scan.interval.ms}, so a plain warn would
     * put the same line in the log sixty times an hour and bury everything else.
     * The path is remembered; it goes quiet until the folder comes back or changes,
     * and says so when it returns, because "it started working again" is the other
     * half of the story and is just as worth having.
     */
    private final java.util.Set<String> moanedAbout =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void warnOncePerPath(String root, String companyId) {
        if (moanedAbout.add(root)) {
            log.warn("THE BACKUP FOLDER CANNOT BE READ: {} (company {}). Nothing from it "
                   + "is being sent off-site. If this is a mapped drive letter, the agent "
                   + "runs as a Windows service and cannot see one - give it a local path "
                   + "or a UNC path it has rights to. Set it in company.backup_file_path.",
                     root, companyId);
        }
    }

    /** Called when a root reads again, so the log shows the recovery too. */
    private void forgetMoan(String root) {
        if (moanedAbout.remove(root)) {
            log.info("the backup folder can be read again: {}", root);
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
        // Recorded BEFORE the post, so a refusal still leaves behind what was
        // attempted and at what weight. A 502 from the proxy says nothing about
        // size, and "was it compressed?" is the first thing worth knowing when a
        // few hundred megabytes will not go up.
        Agent.STATE.get().recordBackupSize(fileName, attrs.size(),
                gzTmp != null ? Files.size(gzTmp) : attrs.size(), gzTmp != null);
        if (gzTmp == null && attrs.size() >= cfg.backupGzipMinBytes) {
            log.warn("sending {} UNCOMPRESSED at {} MB - backup.gzip is {} in "
                   + "sync.properties. A dump this size is commonly refused with a 502 "
                   + "by the proxy in front of the cloud; gzip takes it to a tenth.",
                     fileName, attrs.size() >> 20, cfg.backupGzip ? "on but skipped here" : "OFF");
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
