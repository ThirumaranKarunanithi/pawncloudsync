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

import java.util.List;
import java.util.Map;

/**
 * Bill image pipeline.
 *
 *   sync-agent  →  POST /v1/bills/image   (multipart: file + metadata)
 *   Android     →  GET  /v1/bills/image?companyId=…&materialType=…&billNumber=…&imageName=…
 *
 * Both routes resolve the tenant via the standard auth filters
 * (api-key for sync, JWT for mobile) and the upload/download go through
 * the Magizhchi Share box using the per-tenant mbk_ token captured at
 * login. The local <tenant>.bill_images table is the lookup index from
 * (company, material, bill, image_name) to the box's file_id.
 */
@RestController
@RequestMapping("/v1/bills/image")
public class BillImageController {
    private static final Logger log = LoggerFactory.getLogger(BillImageController.class);

    private final JdbcTemplate publicJdbc;
    private final TenantJdbc tenantJdbc;
    private final MagizhchiBoxClient box;

    public BillImageController(JdbcTemplate publicJdbc, TenantJdbc tenantJdbc,
                               @Value("${pawnbroking.box.url}") String boxUrl) {
        this.publicJdbc = publicJdbc;
        this.tenantJdbc = tenantJdbc;
        this.box = new MagizhchiBoxClient(boxUrl);
    }

    /** Sync-agent uploads an image. Body: file + companyId + materialType + billNumber + imageName. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(
            @RequestParam("file")          MultipartFile file,
            @RequestParam("companyId")     String companyId,
            @RequestParam("materialType")  String materialType,
            @RequestParam("billNumber")    String billNumber,
            @RequestParam("imageName")     String imageName) {
        String shopId = TenantContext.get();
        if (shopId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");
        TenantToken tt = loadTenantToken(shopId);
        try {
            String folder = "bills/" + companyId + "/" + materialType + "/" + billNumber + "/";
            String contentType = file.getContentType();
            long fileId = box.uploadFile(tt.apiKey, tt.conversationId,
                    imageName, contentType, file.getBytes(), folder);
            // Upsert mapping so the next download finds it.
            tenantJdbc.inTenant(j -> {
                j.update(
                    "INSERT INTO bill_images(company_id, material_type, bill_number, image_name, " +
                    "                        magizhchi_file_id, file_size_bytes) " +
                    "VALUES (?,?,?,?,?,?) " +
                    "ON CONFLICT (company_id, material_type, bill_number, image_name) " +
                    "DO UPDATE SET magizhchi_file_id = EXCLUDED.magizhchi_file_id, " +
                    "              file_size_bytes   = EXCLUDED.file_size_bytes, " +
                    "              uploaded_at       = now()",
                    companyId, materialType, billNumber, imageName, fileId, file.getSize());
                return null;
            });
            return Map.of("ok", true, "fileId", fileId, "size", file.getSize());
        } catch (Exception e) {
            log.error("bill image upload failed ({} {}/{}/{}): {}",
                      shopId, companyId, materialType + "/" + billNumber, imageName, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "box upload failed: " + e.getMessage());
        }
    }

    /** Android downloads the bytes via the proxy (so the box token never ships in the APK). */
    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam("companyId")    String companyId,
            @RequestParam("materialType") String materialType,
            @RequestParam("billNumber")   String billNumber,
            @RequestParam("imageName")    String imageName) {
        String shopId = TenantContext.get();
        if (shopId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no tenant in context");
        TenantToken tt = loadTenantToken(shopId);

        Long fileId = tenantJdbc.inTenant(j -> {
            List<Long> ids = j.queryForList(
                "SELECT magizhchi_file_id FROM bill_images " +
                "WHERE company_id=? AND material_type=? AND bill_number=? AND image_name=?",
                Long.class, companyId, materialType, billNumber, imageName);
            return ids.isEmpty() ? null : ids.get(0);
        });
        if (fileId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "image not found");
        try {
            byte[] bytes = box.downloadFile(tt.apiKey, fileId);
            String ct = guessContentType(imageName);
            return ResponseEntity.ok()
                    .header("Content-Type", ct)
                    .header("Cache-Control", "private, max-age=3600")
                    .body(bytes);
        } catch (Exception e) {
            log.warn("bill image download failed ({} fileId={}): {}", shopId, fileId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "box download failed: " + e.getMessage());
        }
    }

    private record TenantToken(String apiKey, long conversationId) {}

    private TenantToken loadTenantToken(String shopId) {
        List<Map<String,Object>> rows = publicJdbc.queryForList(
            "SELECT magizhchi_token, magizhchi_conversation_id " +
            "  FROM public.tenants WHERE shop_id = ?", shopId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown shop_id");
        Object t = rows.get(0).get("magizhchi_token");
        Object c = rows.get(0).get("magizhchi_conversation_id");
        if (t == null || c == null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "tenant has no Magizhchi token yet — OTP-login once on the mobile app to provision");
        return new TenantToken(t.toString(), ((Number) c).longValue());
    }

    private static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))                       return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp"))                      return "image/webp";
        return "application/octet-stream";
    }
}
