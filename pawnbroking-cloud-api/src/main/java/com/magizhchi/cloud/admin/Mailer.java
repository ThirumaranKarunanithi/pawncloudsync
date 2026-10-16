package com.magizhchi.cloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Sends a plain transactional email, through Resend's HTTP API.
 *
 * <p><b>Why HTTP and not SMTP.</b> Railway blocks the SMTP ports (25, 465,
 * 587) outright, so a JavaMailSender here would fail every time and look like
 * a configuration mistake forever. Magizhchi Share already learned this and
 * sends its sign-in codes over Resend's HTTPS API; this does the same, with
 * the same two environment variables, so there is one mail account to keep
 * working rather than two.
 *
 * <p>Nothing here ever throws at the caller. The only thing that sends mail is
 * a warning job, and a warning that cannot be delivered must not be allowed to
 * stop the job that found something worth warning about.
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final String RESEND_URL = "https://api.resend.com/emails";

    private final String apiKey;
    private final String from;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // Exactly the two names Magizhchi Share already uses, so the same Resend
    // account and verified sender serve both and there is one thing to keep
    // working rather than two. Spring's relaxed binding reads the Railway
    // environment variables RESEND_API_KEY and RESEND_FROM into these.
    public Mailer(@Value("${resend.api-key:}") String apiKey,
                  @Value("${resend.from-address:}") String from) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from   = from   == null ? "" : from.trim();
    }

    public boolean configured() { return !apiKey.isBlank() && !from.isBlank(); }

    /**
     * @return true if Resend accepted it. False is logged, never thrown.
     */
    public boolean send(List<String> to, String subject, String html) {
        if (!configured()) {
            log.warn("cannot send \"{}\": RESEND_API_KEY / RESEND_FROM are not set", subject);
            return false;
        }
        List<String> clean = to.stream()
                .filter(a -> a != null && a.contains("@"))
                .map(String::trim).distinct().toList();
        if (clean.isEmpty()) {
            log.warn("cannot send \"{}\": no usable address", subject);
            return false;
        }
        StringBuilder rcpt = new StringBuilder();
        for (String a : clean) {
            if (rcpt.length() > 0) rcpt.append(',');
            rcpt.append('"').append(esc(a)).append('"');
        }
        String body = "{\"from\":\"" + esc(from) + "\","
                    + "\"to\":[" + rcpt + "],"
                    + "\"subject\":\"" + esc(subject) + "\","
                    + "\"html\":\"" + esc(html) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RESEND_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                // Resend's burst limit is about two a second and clears at once;
                // a daily-quota 429 will not, so try once and give up either way.
                Thread.sleep(1200);
                r = http.send(req, HttpResponse.BodyHandlers.ofString());
            }
            if (r.statusCode() / 100 == 2) return true;
            // The verbatim body names the real cause - unverified sender domain,
            // bad key, quota - and is the only useful thing in the failure.
            log.error("Resend refused \"{}\" to {}: HTTP {} {}",
                      subject, clean, r.statusCode(), r.body());
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("could not send \"{}\" to {}: {}", subject, clean, e.toString());
            return false;
        }
    }

    /** JSON string escaping, including the control characters Resend rejects. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
