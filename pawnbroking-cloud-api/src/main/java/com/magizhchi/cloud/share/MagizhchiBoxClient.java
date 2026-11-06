package com.magizhchi.cloud.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Minimal HTTP client for Magizhchi Share (the "box"). Just the four ops
 * cloud-api needs for bill images — token issue, personal conversation id,
 * file upload, file download. No external SDK dependency so we stay
 * self-contained.
 *
 * Wire format mirrors the published sdk-java client exactly:
 *   – API ops use header  X-Api-Key: mbk_…
 *   – Account ops (token issue) use Bearer JWT from /api/auth/login/verify
 *   – Upload is multipart with parts "file" and "folderPath"
 *   – Download is two-step: /download-url returns a presigned URL, fetch that
 */
public class MagizhchiBoxClient {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Random RNG = new Random();

    private final String baseUrl;
    private final HttpClient http;

    public MagizhchiBoxClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Mint a long-lived mbk_ API token from a freshly-issued box accessToken. */
    public String issueApiToken(String bearerJwt, String name) throws Exception {
        String body = "{\"name\":\"" + jsonEscape(name)
                    + "\",\"scopes\":[\"drive:read\",\"drive:write\"]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/api-tokens"))
                .header("Authorization", "Bearer " + bearerJwt)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box issueApiToken " + r.statusCode() + ": " + r.body());
        JsonNode n = M.readTree(r.body());
        String tok = n.path("plaintextToken").asText(null);
        if (tok == null || tok.isBlank())
            throw new RuntimeException("box issueApiToken: no plaintextToken in response");
        return tok;
    }

    /** Resolve (and cache server-side) the user's "Personal" conversation id. */
    public long personalConversationId(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/conversations/personal"))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box personalConversationId " + r.statusCode() + ": " + r.body());
        return M.readTree(r.body()).path("id").asLong();
    }

    /** Returns file_id of the uploaded blob. folderPath is e.g. "bills/CMP1/GOLD/E18444/". */
    public long uploadFile(String apiKey, long conversationId,
                           String filename, String contentType,
                           byte[] bytes, String folderPath) throws Exception {
        String boundary = "MagizhchiCloudBoundary" + Math.abs(RNG.nextLong());
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";

        StringBuilder head = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename.replace("\"", "\\\"")).append("\"\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n\r\n");

        StringBuilder tail = new StringBuilder().append("\r\n");
        if (folderPath != null && !folderPath.isBlank()) {
            String n = folderPath.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
            if (!n.isEmpty()) n += "/";
            tail.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"folderPath\"\r\n\r\n")
                .append(n).append("\r\n");
        }
        tail.append("--").append(boundary).append("--\r\n");

        byte[] headB = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailB = tail.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/files/send/" + conversationId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(headB, bytes, tailB)))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box uploadFile " + r.statusCode() + ": " + r.body());
        return M.readTree(r.body()).path("id").asLong();
    }

    /**
     * Streaming variant for large files (backup DB dumps 100MB+). Instead of
     * buffering the whole file in a byte[], it concatenates the multipart
     * head + the file's InputStream + the tail via a SequenceInputStream and
     * hands that to BodyPublishers.ofInputStream — so the JVM only holds a
     * small transfer buffer, never the entire file. Returns the box file id.
     */
    public long uploadFileStreaming(String apiKey, long conversationId,
                                    String filename, String contentType,
                                    java.io.InputStream fileStream, String folderPath) throws Exception {
        String boundary = "MagizhchiCloudBoundary" + Math.abs(RNG.nextLong());
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";

        StringBuilder head = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename.replace("\"", "\\\"")).append("\"\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n\r\n");

        StringBuilder tail = new StringBuilder().append("\r\n");
        if (folderPath != null && !folderPath.isBlank()) {
            String n = folderPath.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
            if (!n.isEmpty()) n += "/";
            tail.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"folderPath\"\r\n\r\n")
                .append(n).append("\r\n");
        }
        tail.append("--").append(boundary).append("--\r\n");

        java.io.InputStream headS = new java.io.ByteArrayInputStream(head.toString().getBytes(StandardCharsets.UTF_8));
        java.io.InputStream tailS = new java.io.ByteArrayInputStream(tail.toString().getBytes(StandardCharsets.UTF_8));
        java.io.InputStream body  = new java.io.SequenceInputStream(
                new java.io.SequenceInputStream(headS, fileStream), tailS);

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/files/send/" + conversationId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofMinutes(30))   // big files need a long window
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box uploadFileStreaming " + r.statusCode() + ": " + r.body());
        return M.readTree(r.body()).path("id").asLong();
    }

    /**
     * Streaming download — returns the blob as an InputStream instead of a
     * byte[]. Backups are routinely 100MB+; buffering one in the cloud's heap
     * risks an OOM on a small container, so the backup route pipes this
     * straight to the client with a fixed-size copy buffer.
     */
    public java.io.InputStream downloadFileStream(String apiKey, long fileId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/files/" + fileId + "/download-url"))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box download-url " + r.statusCode() + ": " + r.body());
        String url = M.readTree(r.body()).path("url").asText(null);
        if (url == null || url.isBlank()) throw new RuntimeException("box download-url: no url field");

        HttpResponse<java.io.InputStream> blob = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (blob.statusCode() / 100 != 2)
            throw new RuntimeException("box presigned download " + blob.statusCode());
        return blob.body();
    }

    public byte[] downloadFile(String apiKey, long fileId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/files/" + fileId + "/download-url"))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box download-url " + r.statusCode() + ": " + r.body());
        String url = M.readTree(r.body()).path("url").asText(null);
        if (url == null || url.isBlank()) throw new RuntimeException("box download-url: no url field");

        HttpResponse<byte[]> blob = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (blob.statusCode() / 100 != 2)
            throw new RuntimeException("box presigned download " + blob.statusCode());
        return blob.body();
    }

    /**
     * Deletes one file and gives the account its room back.
     *
     * <p>This is the half that was missing for a year. Backups were uploaded and
     * never removed, so a shop's box could only ever fill up - and the box
     * refuses an upload that would cross the account's limit, which is a cap on
     * the SIZE of the file, not on how many. So the day a shop got close to the
     * line, the 1 MB report still fitted and the 21 MB database dump did not,
     * and the shop went on seeing its PDFs arrive every morning while its actual
     * backup quietly stopped. Nothing is really off-site after that.
     */
    public void deleteFile(String apiKey, long fileId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/files/" + fileId))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .DELETE().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        // Already gone is the outcome we wanted. Only a real failure is an error.
        if (r.statusCode() == 404) return;
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box deleteFile " + r.statusCode() + ": " + r.body());
    }

    /**
     * What an account has used and what it is allowed, in bytes, and where its
     * plan stands.
     *
     * <p>From 06-11-2026 a paid box plan ends by itself: GRACE for a week (space
     * kept), then EXPIRED, when the limit goes back to the free plan's 5 GB and
     * every upload is refused while the account holds more than that. The plan
     * fields are null on a box older than that change, and then nothing here
     * behaves any differently from before.
     *
     * @param planState     FREE, ACTIVE, GRACE or EXPIRED (null = not reported)
     * @param pendingStatus PENDING_PAYMENT or PENDING_VERIFICATION while a renewal
     *                      is under way, else null
     */
    public record Usage(long usedBytes, long limitBytes,
                        String planCode, String planLabel, String planState,
                        java.time.Instant planExpiresAt, java.time.Instant graceEndsAt,
                        String pendingStatus) {
        public Usage(long usedBytes, long limitBytes) {
            this(usedBytes, limitBytes, null, null, null, null, null, null);
        }
        public long freeBytes() { return Math.max(0L, limitBytes - usedBytes); }
        public boolean fits(long size) { return usedBytes + size <= limitBytes; }

        /** The paid plan lapsed and its grace is over: the space is the free plan's again. */
        public boolean planEnded() { return "EXPIRED".equals(planState); }
        /** The paid plan lapsed; its space is kept until {@link #graceEndsAt}. */
        public boolean inGrace() { return "GRACE".equals(planState); }
        /** Ended, and holding more than the free plan allows: the box refuses every upload. */
        public boolean uploadsPaused() { return planEnded() && limitBytes > 0 && usedBytes >= limitBytes; }
        /** A running paid plan that ends within {@code days} of {@code now}. */
        public boolean endsWithin(java.time.Instant now, int days) {
            return "ACTIVE".equals(planState) && planExpiresAt != null
                && planExpiresAt.isAfter(now)
                && !planExpiresAt.isAfter(now.plus(java.time.Duration.ofDays(days)));
        }
        /** A renewal has been paid for and is waiting for Magizhchi to approve it. */
        public boolean renewalBeingChecked() { return "PENDING_VERIFICATION".equals(pendingStatus); }
    }

    /** An instant as the box writes it: ISO text, or epoch seconds or millis if Jackson was set that way. */
    static java.time.Instant instant(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        try {
            if (n.isNumber()) {
                double v = n.asDouble();
                return v > 1e11 ? java.time.Instant.ofEpochMilli((long) v)
                                : java.time.Instant.ofEpochMilli(Math.round(v * 1000));
            }
            String s = n.asText("").trim();
            if (s.isEmpty()) return null;
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            try { return java.time.Instant.parse(n.asText()); } catch (Exception ignored) { return null; }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText(null);
    }

    /** Reads a {@code /api/storage/usage} answer. Package-visible for the test. */
    static Usage parseUsage(String json) throws Exception {
        JsonNode n = M.readTree(json);
        JsonNode pending = n.path("pendingPayment");
        return new Usage(n.path("usedBytes").asLong(0L), n.path("limitBytes").asLong(0L),
                text(n, "planCode"), text(n, "planLabel"), text(n, "planState"),
                instant(n.get("planExpiresAt")), instant(n.get("graceEndsAt")),
                pending.isObject() ? text(pending, "status") : null);
    }

    /**
     * Reads the account's storage line so the caller can look BEFORE sending a
     * big file, rather than finding out from a 413 after pushing 21 MB up a
     * shop's broadband.
     */
    public Usage usage(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/storage/usage"))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("box usage " + r.statusCode() + ": " + r.body());
        return parseUsage(r.body());
    }

    /**
     * A box answer exactly as it came, so a caller can hand the box's own
     * status and words on (404 "Unknown plan", 409 "already approved") rather
     * than flattening every refusal into one error.
     */
    public record Answer(int status, String body) {
        public boolean ok() { return status / 100 == 2; }
        public JsonNode json() {
            try { return M.readTree(body == null || body.isBlank() ? "{}" : body); }
            catch (Exception e) { return M.createObjectNode(); }
        }
        /** The box's sentence for a refusal: message, else error, else the status. */
        public String words() {
            JsonNode n = json();
            String m = n.path("message").asText("");
            if (m.isBlank()) m = n.path("error").asText("");
            return m.isBlank() ? "the cloud box answered " + status : m;
        }
    }

    /**
     * The four renewal calls the box allows an API token since 06-11-2026.
     * Each acts only on the token owner's own account, which for a shop is the
     * account its photos and backups go to - the one whose plan needs paying for.
     */
    public Answer plans(String apiKey)                  { return call(apiKey, "GET",  "/api/plans", null); }
    public Answer myPayments(String apiKey)             { return call(apiKey, "GET",  "/api/payments/me", null); }
    public Answer storageUsageRaw(String apiKey)        { return call(apiKey, "GET",  "/api/storage/usage", null); }
    public Answer checkout(String apiKey, String planCode, String billingCycle) {
        return call(apiKey, "POST", "/api/payments/checkout",
                "{\"planCode\":\"" + jsonEscape(planCode) + "\",\"billingCycle\":\"" + jsonEscape(billingCycle) + "\"}");
    }
    public Answer submitUtr(String apiKey, String reference, String utr) {
        return call(apiKey, "POST", "/api/payments/"
                + java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8) + "/utr",
                "{\"utr\":\"" + jsonEscape(utr) + "\"}");
    }

    private Answer call(String apiKey, String method, String path, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30));
        if (json != null) {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Answer(502, "{\"message\":\"the cloud box could not be reached\"}");
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
