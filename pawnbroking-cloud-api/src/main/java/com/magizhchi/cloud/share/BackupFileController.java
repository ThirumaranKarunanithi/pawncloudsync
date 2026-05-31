package com.magizhchi.cloud.share;

import com.magizhchi.cloud.tenant.TenantContext;
import com.magizhchi.cloud.tenant.TenantJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
 * There's no download endpoint here — the box's web UI is the access
 * surface for backups. If you ever want phone download, add a GET
 * mirroring BillImageController.
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
