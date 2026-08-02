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

    public BackupFileController(JdbcTemplate publicJdbc, TenantJdbc tenantJdbc,
                                @Value("${pawnbroking.box.url}") String boxUrl) {
        this.publicJdbc = publicJdbc;
        this.tenantJdbc = tenantJdbc;
        this.box = new MagizhchiBoxClient(boxUrl);
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
        try {
            String folder = "backups/" + companyId + "/" + (rel.isEmpty() ? "" : rel + "/");
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "box upload failed: " + e.getMessage());
        }
    }

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
