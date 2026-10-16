package com.magizhchi.cloud.share;

import com.magizhchi.cloud.tenant.TenantContext;
import com.magizhchi.cloud.tenant.TenantJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Generic file backup. Mirrors the shop's local
 * {@code company.backup_file_path} directory into Magizhchi Share under
 * {@code backups/<companyId>/<relativePath>/<fileName>} so the shop has
 * an off-site copy of every file in that tree.
 *
 *   sync-agent → POST /v1/files/backup   (multipart: file + companyId
 *                                         + relativePath + fileName)
 *
 *   mobile     → GET  /v1/files/backup/list      (metadata, newest first)
 *   mobile     → GET  /v1/files/backup/download  (streamed bytes)
 *
 * The box's web UI remains an access surface too; the GETs exist so the
 * phone can list and cache backups without ever holding the box token.
 */
@RestController
@RequestMapping("/v1/files/backup")
public class BackupFileController {
    private static final Logger log = LoggerFactory.getLogger(BackupFileController.class);

    private final JdbcTemplate publicJdbc;
    private final TenantJdbc tenantJdbc;
    private final MagizhchiBoxClient box;
    private final int keepDays;
    private final int keepFloor;

    public BackupFileController(JdbcTemplate publicJdbc, TenantJdbc tenantJdbc,
                                @Value("${pawnbroking.box.url}") String boxUrl,
                                @Value("${pawnbroking.backup.keep-days:30}") int keepDays,
                                @Value("${pawnbroking.backup.keep-floor:7}") int keepFloor) {
        this.publicJdbc = publicJdbc;
        this.tenantJdbc = tenantJdbc;
        this.box = new MagizhchiBoxClient(boxUrl);
        this.keepDays = keepDays;
        this.keepFloor = keepFloor;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(
            @RequestParam("file")          MultipartFile file,
            @RequestParam("companyId")     String companyId,
            @RequestParam(value="relativePath", required=false) String relativePath,
            @RequestParam("fileName")      String fileName) {
        String shopId = TenantContext.get();
        if (shopId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");
        TenantToken tt = loadTenantToken(shopId);
        String rel = relativePath == null ? "" : relativePath.replace('\\', '/')
                .replaceAll("^/+", "").replaceAll("/+$", "");
        // Retire what is past its keep window BEFORE sending anything. Doing it
        // after a successful upload would have been tidier and would have fixed
        // nobody: a shop whose box is already full can never complete an upload,
        // so a tidy-up that only runs on success would never run again for the
        // shops that most need it. Run first and they heal themselves.
        retire(tt, companyId);
        try {
            String folder = "backups/" + companyId + "/" + (rel.isEmpty() ? "" : rel + "/");
            // The box refuses the whole file when it would cross the account's
            // limit, so ask before pushing 21 MB up a shop's broadband only to
            // be told no at the end of it.
            makeRoomFor(tt, companyId, file.getSize());
            // Stream from Tomcat's on-disk temp file straight to the box —
            // never load the (100MB+) backup into the cloud's heap.
            long fileId;
            try (java.io.InputStream in = file.getInputStream()) {
                fileId = box.uploadFileStreaming(tt.apiKey, tt.conversationId,
                        fileName, file.getContentType(), in, folder);
            }
            tenantJdbc.inTenant(j -> {
                j.update(
                    "INSERT INTO backup_files(company_id, relative_path, file_name, " +
                    "                         magizhchi_file_id, file_size_bytes) " +
                    "VALUES (?,?,?,?,?) " +
                    "ON CONFLICT (company_id, relative_path, file_name) " +
                    "DO UPDATE SET magizhchi_file_id = EXCLUDED.magizhchi_file_id, " +
                    "              file_size_bytes   = EXCLUDED.file_size_bytes, " +
                    "              uploaded_at       = now()",
                    companyId, rel, fileName, fileId, file.getSize());
                return null;
            });
            return Map.of("ok", true, "fileId", fileId, "size", file.getSize());
        } catch (Exception e) {
            String key = companyId + "/" + (rel.isEmpty() ? "" : rel + "/") + fileName;
            log.error("backup upload failed ({} {}): {}", shopId, key, e.toString());
            // A full box is not a bad gateway, and calling it one is what hid
            // this for a year: the agent logged a line nobody read, the phone
            // told the shop to go and look at a backup job that was working
            // perfectly, and the shop believed it. Say which it is.
            if (looksFull(e)) {
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                        "the cloud box for this shop is full — " + whatFillsIt(companyId));
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "box upload failed: " + e.getMessage());
        }
    }

    /** The box's own words when an upload would cross the account's limit. */
    private static boolean looksFull(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String s = m.toLowerCase();
            if (s.contains("storage limit") || s.contains("payload_too_large")
                    || s.contains(" 413") || s.contains("413:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names what is actually taking the room, because the answer changes what
     * anybody should do about it. Backups filling the box is what retirement
     * now prevents by itself; PHOTOS filling it cannot be swept up, since a
     * shop wants every one of them kept — that shop needs a bigger plan, and
     * saying so plainly is the whole point of this message.
     */
    private String whatFillsIt(String companyId) {
        try {
            return tenantJdbc.inTenant(j -> {
                long photos = j.queryForObject(
                    "SELECT COALESCE(sum(file_size_bytes),0) FROM bill_images", Long.class);
                long backups = j.queryForObject(
                    "SELECT COALESCE(sum(file_size_bytes),0) FROM backup_files", Long.class);
                String lead = photos > backups
                        ? "mostly bill photos, which are not swept up: this shop needs a bigger box plan"
                        : "mostly old backups: they are being retired now, so try again";
                return lead + " (photos " + mb(photos) + ", backups " + mb(backups) + ")";
            });
        } catch (Exception ignored) {
            return "could not read the breakdown";
        }
    }

    private static String mb(long bytes) { return (bytes >> 20) + " MB"; }

    /**
     * Mobile lists the shop's backup files, newest first. Returns metadata
     * only (no bytes) so the phone can show the list cheaply and decide what
     * to download.
     */
    @GetMapping("/list")
    public List<Map<String,Object>> list(
            @RequestParam(value="companyId", required=false) String companyId,
            @RequestParam(value="limit",     required=false, defaultValue="100") int limit) {
        String shopId = TenantContext.get();
        if (shopId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");
        int lim = Math.min(Math.max(limit, 1), 500);
        return tenantJdbc.inTenant(j -> {
            if (companyId != null && !companyId.isBlank()) {
                return j.queryForList(
                    "SELECT company_id, relative_path, file_name, file_size_bytes, uploaded_at " +
                    "  FROM backup_files WHERE company_id = ? " +
                    " ORDER BY uploaded_at DESC LIMIT " + lim, companyId);
            }
            return j.queryForList(
                "SELECT company_id, relative_path, file_name, file_size_bytes, uploaded_at " +
                "  FROM backup_files ORDER BY uploaded_at DESC LIMIT " + lim);
        });
    }

    /**
     * Mobile downloads one backup file's bytes through the proxy (so the box
     * token never ships in the APK). Streamed end-to-end — a 100MB+ dump
     * never lands in the cloud's heap.
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam("companyId") String companyId,
            @RequestParam(value="relativePath", required=false) String relativePath,
            @RequestParam("fileName")  String fileName) {
        String shopId = TenantContext.get();
        if (shopId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");
        TenantToken tt = loadTenantToken(shopId);
        String rel = relativePath == null ? "" : relativePath.replace('\\', '/')
                .replaceAll("^/+", "").replaceAll("/+$", "");

        Map<String,Object> row = tenantJdbc.inTenant(j -> {
            List<Map<String,Object>> rows = j.queryForList(
                "SELECT magizhchi_file_id, file_size_bytes FROM backup_files " +
                " WHERE company_id=? AND relative_path=? AND file_name=?",
                companyId, rel, fileName);
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (row == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "backup file not found");

        long fileId = ((Number) row.get("magizhchi_file_id")).longValue();
        Object sizeObj = row.get("file_size_bytes");

        StreamingResponseBody body = out -> {
            try (java.io.InputStream in = box.downloadFileStream(tt.apiKey, fileId)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            } catch (Exception e) {
                log.warn("backup download failed ({} fileId={}): {}", shopId, fileId, e.toString());
                throw new java.io.IOException("box download failed: " + e.getMessage(), e);
            }
        };

        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + fileName.replace("\"", "") + "\"");
        if (sizeObj instanceof Number sz && sz.longValue() > 0)
            b = b.header("Content-Length", String.valueOf(sz.longValue()));
        return b.body(body);
    }

    /**
     * Why the backups are, or are not, arriving — so the phone can say the true
     * thing instead of guessing.
     *
     * <p>The phone used to work this out on its own: no backup for N days, so
     * it told the shop to go and check the backup job on its PC. That advice was
     * wrong in every case that mattered. The backup job was running perfectly;
     * the shop's cloud box was full, and a full box takes the small daily report
     * and refuses the large database dump, so the phone could even see fresh
     * files arriving while the important one was being turned away. Months were
     * lost to people looking in the one place the fault was not.
     */
    @GetMapping("/health")
    public Map<String,Object> health(
            @RequestParam(value="companyId", required=false) String companyId) {
        String shopId = TenantContext.get();
        if (shopId == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");

        long used = -1, limit = -1;
        try {
            MagizhchiBoxClient.Usage u = box.usage(loadTenantToken(shopId).apiKey);
            used = u.usedBytes(); limit = u.limitBytes();
        } catch (Exception e) {
            // Fall back to what the nightly watch last saw, rather than claiming
            // health we cannot actually confirm.
            try {
                Map<String,Object> row = publicJdbc.queryForMap(
                    "SELECT used_bytes, limit_bytes FROM public.box_quota WHERE shop_id = ?", shopId);
                used  = ((Number) row.get("used_bytes")).longValue();
                limit = ((Number) row.get("limit_bytes")).longValue();
            } catch (Exception ignored) { /* no reading at all */ }
        }

        int pct = (limit > 0 && used >= 0) ? (int) Math.round(100.0 * used / limit) : -1;

        // The newest dump, and the newest of anything. When the first is much
        // older than the second, files ARE arriving and the big ones are not —
        // which is the fingerprint of a full box, and the thing the shop needs
        // told in words.
        Map<String,Object> t = tenantJdbc.inTenant(j -> {
            String where = (companyId == null || companyId.isBlank()) ? "" : " AND company_id = ?";
            Object[] a = where.isEmpty() ? new Object[0] : new Object[]{ companyId };
            java.util.Map<String,Object> m = new java.util.HashMap<>();
            m.put("last_any", j.queryForObject(
                "SELECT max(uploaded_at) FROM backup_files WHERE true" + where,
                java.sql.Timestamp.class, a));
            m.put("last_dump", j.queryForObject(
                "SELECT max(uploaded_at) FROM backup_files " +
                " WHERE lower(file_name) NOT LIKE '%.pdf'" + where,
                java.sql.Timestamp.class, a));
            return m;
        });

        Object lastAny  = t.get("last_any");
        Object lastDump = t.get("last_dump");
        long dumpAgeDays = ageDays(lastDump);

        boolean full    = pct >= 98;
        boolean filling = pct >= 80 && pct < 98;
        // Reports still landing while dumps are days behind: the box is the
        // suspect, not the shop PC, whatever the percentage happens to read.
        boolean lopsided = dumpAgeDays >= 3 && ageDays(lastAny) <= 1;

        String reason, advice;
        // Blaming storage needs the storage figure to actually SAY so. An
        // earlier cut of this blamed a full box whenever reports were arriving
        // and dumps were not, which on a box at 2% would have announced "your
        // cloud storage is full (2% used)" and sent somebody off to buy space
        // they already had. Lopsided is the symptom; only the percentage is
        // evidence of the cause.
        if (full || (lopsided && pct >= 90)) {
            reason = "CLOUD_FULL";
            advice = "The shop PC is fine — its cloud storage is full"
                   + (pct >= 0 ? " (" + pct + "% used)" : "")
                   + ". A full box still accepts the small daily report and refuses the "
                   + "larger database backup, which is why reports keep arriving. "
                   + "Ask for the storage plan to be raised.";
        } else if (lopsided) {
            reason = "DUMPS_NOT_ARRIVING";
            // What is NOT knowable from here: whether the agent tried to send a
            // dump and was refused, or never found one to send. Both look
            // identical to the cloud — it sees reports and no dump either way.
            // So name the one thing that IS settled (storage is not the cause)
            // and send them to the agent's own panel, which can tell them apart.
            advice = "Daily reports are arriving but the database backup is not."
                   + (pct >= 0
                      ? " Cloud storage is only " + pct + "% used, so it is not full."
                      : "")
                   + " Either the backup file is not being written where the sync agent "
                   + "looks for it, or its upload is failing. The agent's own progress "
                   + "window on the shop PC has a Backup line that says which.";
        } else if (dumpAgeDays >= 2) {
            reason = "STALE";
            advice = "No backup has reached the cloud for " + dumpAgeDays
                   + " days, and nothing else is arriving either — check that the shop PC "
                   + "is switched on and the sync agent is running.";
        } else if (filling) {
            reason = "FILLING";
            advice = "Backups are arriving. Cloud storage is " + pct
                   + "% used — worth raising the plan before it fills.";
        } else {
            reason = "OK";
            advice = "Backups are arriving.";
        }

        java.util.Map<String,Object> out = new java.util.HashMap<>();
        out.put("reason", reason);
        out.put("advice", advice);
        out.put("usedBytes", used);
        out.put("limitBytes", limit);
        out.put("percentUsed", pct);
        out.put("lastBackupAt", lastDump == null ? null : lastDump.toString());
        out.put("lastAnyFileAt", lastAny == null ? null : lastAny.toString());
        out.put("daysSinceBackup", dumpAgeDays);
        return out;
    }

    private static long ageDays(Object ts) {
        if (!(ts instanceof java.sql.Timestamp t)) return Long.MAX_VALUE;
        return java.time.Duration.between(t.toInstant(), java.time.Instant.now()).toDays();
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /** One file the box is holding for this shop. */
    private record Held(long fileId, String rel, String name, long size) {}

    /**
     * Drops this company's backups that are past the keep window.
     *
     * <p><b>Never empties a kind.</b> The newest {@code keepFloor} of each sort of
     * file - the dumps, the reports - are kept whatever their age, because a shop
     * whose backup job has been stopped for two months would otherwise have its
     * last off-site copy swept away on the very day somebody needed it. Age alone
     * is the wrong rule when the thing being aged is the only copy left.
     *
     * <p>Failure here never fails an upload. Getting the backup in matters more
     * than tidying up behind it, and the next upload will try again.
     */
    private void retire(TenantToken tt, String companyId) {
        try {
            List<Held> old = prunable(companyId, keepDays);
            if (old.isEmpty()) return;
            long freed = drop(tt, companyId, old, Long.MAX_VALUE);
            if (freed > 0) {
                log.info("retired {} backup file(s) for {} past {} days, freed {}",
                         old.size(), companyId, keepDays, mb(freed));
            }
        } catch (Exception e) {
            log.warn("backup retirement failed for {}: {}", companyId, e.toString());
        }
    }

    /**
     * Makes the file about to be sent fit, if it can be made to fit by dropping
     * backups this shop no longer needs.
     *
     * <p>This goes past the keep window on purpose. The choice at this point is
     * between dropping a backup that is newer than thirty days and never taking
     * another backup again, and the second is much worse - that is precisely the
     * state these shops have been in. The floor still holds, so the newest few of
     * every kind survive, and photos are never touched.
     */
    private void makeRoomFor(TenantToken tt, String companyId, long needBytes) {
        if (needBytes <= 0) return;
        try {
            MagizhchiBoxClient.Usage u = box.usage(tt.apiKey);
            if (u.limitBytes() <= 0 || u.fits(needBytes)) return;
            long short0 = needBytes - u.freeBytes();
            log.info("box for {} is {} short of room for a {} file — retiring oldest backups",
                     companyId, mb(short0), mb(needBytes));
            // Ignore the age rule, keep the floor, oldest first, stop once it fits.
            long freed = drop(tt, companyId, prunable(companyId, 0), short0);
            log.info("freed {} of the {} needed for {}", mb(freed), mb(short0), companyId);
        } catch (Exception e) {
            // Not fatal: the upload is still worth attempting, and if the box
            // does refuse it the catch above now says so in plain words.
            log.warn("could not check or make room on the box for {}: {}", companyId, e.toString());
        }
    }

    /**
     * The files eligible to go, oldest first. {@code olderThanDays} of 0 means
     * "any age" - used only when the box is full and something has to give.
     */
    private List<Held> prunable(String companyId, int olderThanDays) {
        return tenantJdbc.inTenant(j -> j.query(
            "WITH ranked AS (" +
            "  SELECT magizhchi_file_id, relative_path, file_name, file_size_bytes, uploaded_at," +
            // Rank within each KIND of file, so a daily dump and a daily report
            // each keep their own floor instead of the bigger stream burying the
            // other. ".backup.gz" and ".pdf" are two streams, not one.
            "         row_number() OVER (PARTITION BY CASE" +
            "             WHEN position('.' in file_name) > 0" +
            "             THEN lower(substring(file_name from position('.' in file_name)))" +
            "             ELSE '' END" +
            "           ORDER BY uploaded_at DESC) AS rn" +
            "    FROM backup_files WHERE company_id = ? AND magizhchi_file_id IS NOT NULL)" +
            " SELECT magizhchi_file_id, relative_path, file_name, file_size_bytes FROM ranked" +
            "  WHERE rn > ?" +
            (olderThanDays > 0 ? " AND uploaded_at < now() - (? * interval '1 day')" : "") +
            "  ORDER BY uploaded_at",
            (rs, n) -> new Held(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
            olderThanDays > 0 ? new Object[]{ companyId, keepFloor, olderThanDays }
                              : new Object[]{ companyId, keepFloor }));
    }

    /**
     * Removes files from the box and forgets them here, stopping once
     * {@code stopAfter} bytes have been freed. Returns the bytes freed.
     *
     * <p>Box first, row second. The other order leaves a row pointing at bytes
     * that are gone, which the phone finds out about only when somebody taps
     * Download on the backup they need.
     */
    private long drop(TenantToken tt, String companyId, List<Held> files, long stopAfter) {
        long freed = 0;
        for (Held h : files) {
            try {
                box.deleteFile(tt.apiKey, h.fileId());
            } catch (Exception e) {
                log.warn("could not delete box file {} for {}: {}", h.fileId(), companyId, e.toString());
                continue;   // leave the row; the next sweep will try again
            }
            try {
                tenantJdbc.inTenant(j -> j.update(
                    "DELETE FROM backup_files WHERE company_id=? AND relative_path=? AND file_name=?",
                    companyId, h.rel(), h.name()));
            } catch (Exception e) {
                log.warn("deleted box file {} but could not clear its row: {}", h.fileId(), e.toString());
            }
            freed += h.size();
            if (freed >= stopAfter) break;
        }
        return freed;
    }

    private record TenantToken(String apiKey, long conversationId) {}

    private TenantToken loadTenantToken(String shopId) {
        List<Map<String,Object>> rows = publicJdbc.queryForList(
            "SELECT magizhchi_token, magizhchi_conversation_id " +
            "  FROM public.tenants WHERE shop_id = ?", shopId);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown shop_id");
        Object t = rows.get(0).get("magizhchi_token");
        Object c = rows.get(0).get("magizhchi_conversation_id");
        if (t == null || c == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "tenant has no Magizhchi token yet — OTP-login on the mobile app once");
        return new TenantToken(t.toString(), ((Number) c).longValue());
    }
}
