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

    public ImageWatcher(DataSource ds, Config cfg) {
        this.ds = ds;
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void stop() { running = false; }

    /** Idempotent — creates the tracker table if missing. */
    public void ensureTrackerTable() {
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
        } catch (Exception e) {
            log.error("could not ensure sync_image_uploads table: {}", e.toString());
        }
    }

    @Override
    public void run() {
        ensureTrackerTable();
        while (running) {
            try {
                scanOnce();
            } catch (Throwable t) {
                log.warn("image scan failed: {}", t.toString());
            }
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
                    // Derive (companyId, material, bill_number, image_name) from path.
                    // root/<companyId>/<material>/<billNumber>/<imageName>
                    Path rel = root.relativize(p);
                    if (rel.getNameCount() != 4) continue; // skip stray files
                    String companyId  = rel.getName(0).toString();
                    String material   = rel.getName(1).toString();
                    String billNumber = rel.getName(2).toString();
                    String imageName  = rel.getName(3).toString();
                    if (!companyId.equalsIgnoreCase(cr.companyId)) continue;
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
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT company_id, camera_temp_file_name FROM company_other_settings " +
                "WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String cid  = rs.getString(1);
                String root = cfg.imageRootOverride != null
                        ? cfg.imageRootOverride
                        : rs.getString(2);
                if (cid != null && root != null && !root.isBlank())
                    out.add(new CompanyRoot(cid, root));
            }
        }
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
        if (r.statusCode() / 100 != 2) {
            throw new IOException("cloud upload status=" + r.statusCode() + " body=" + r.body());
        }
        recordUploaded(file.toAbsolutePath().toString(), companyId, material,
                       billNumber, imageName, attrs.size(), attrs.lastModifiedTime().toInstant());
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

    private static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))                        return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp"))                       return "image/webp";
        return "application/octet-stream";
    }
}
