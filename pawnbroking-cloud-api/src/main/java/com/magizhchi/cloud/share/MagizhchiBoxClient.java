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

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
