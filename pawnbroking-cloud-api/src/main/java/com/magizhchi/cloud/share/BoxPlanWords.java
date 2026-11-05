package com.magizhchi.cloud.share;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The box plan in plain words, said the same way by an upload refusal, the
 * phone's backup line and the admins' mail.
 *
 * <p>From 06-11-2026 a paid box plan ends by itself: a week of GRACE with the
 * space kept, then the account's limit goes back to the free 5 GB. Nothing is
 * deleted, but a shop holding more than that has every upload refused - photos,
 * reports and backups alike - until the plan is renewed. Before this class, the
 * only thing anybody would have been told was "the cloud box is full", which
 * sends people to buy space when the real answer is "renew the plan".
 */
public final class BoxPlanWords {
    private BoxPlanWords() {}

    /** The free plan's space, which an ended plan goes back to. */
    public static final long FREE_BYTES = 5L << 30;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(IST);

    public static String day(Instant i) { return i == null ? "an unknown date" : DAY.format(i); }

    public static String gb(long b) {
        if (b < 0) return "an unknown amount";
        double g = b / 1073741824.0;
        if (g >= 1024) return String.format("%.1f TB", g / 1024);
        return g >= 1 ? String.format("%.1f GB", g) : String.format("%d MB", b >> 20);
    }

    private static String plan(MagizhchiBoxClient.Usage u) {
        // Once the grace is over the box has already moved the account onto the
        // free plan: it reports "Free" and no end date, so naming the label here
        // would say "the Free plan ended".
        if (u.planEnded()) return "paid cloud box plan";
        return u.planLabel() == null || u.planLabel().isBlank()
                ? "cloud box plan" : "cloud box plan (" + u.planLabel() + ")";
    }

    private static String renewing(MagizhchiBoxClient.Usage u) {
        return u.renewalBeingChecked() ? " A renewal has been paid for and is being checked." : "";
    }

    /** Why the box refuses every upload, when an ended plan is the reason; else null. */
    public static String pausedWhy(MagizhchiBoxClient.Usage u) {
        if (u == null || !u.uploadsPaused()) return null;
        return "the shop's " + plan(u) + " ended"
             + (u.planExpiresAt() == null ? "" : " on " + day(u.planExpiresAt()))
             + " and its space went back to the free " + gb(u.limitBytes())
             + ", but it holds " + gb(u.usedBytes())
             + ", so nothing new can be uploaded until the plan is renewed."
             + " Nothing stored is lost: every file can still be opened and downloaded.";
    }

    /**
     * One or two sentences about the plan worth telling the shop, or null when
     * there is nothing to say (running, not ending within a week, or a box that
     * does not report plans).
     */
    public static String note(MagizhchiBoxClient.Usage u, Instant now) {
        if (u == null) return null;
        if (u.uploadsPaused()) return cap(pausedWhy(u)) + renewing(u);
        if (u.planEnded()) {
            return "The shop's " + plan(u) + " has ended and its space is now the free "
                 + gb(u.limitBytes()) + " (" + gb(u.usedBytes()) + " used). Uploads stop once that is full."
                 + renewing(u);
        }
        if (u.inGrace()) {
            boolean over = u.usedBytes() > FREE_BYTES;
            return "The shop's " + plan(u) + " ended"
                 + (u.planExpiresAt() == null ? "" : " on " + day(u.planExpiresAt()))
                 + ". Its space is kept until " + day(u.graceEndsAt())
                 + "; after that it goes back to the free " + gb(FREE_BYTES)
                 + (over ? ", and as it holds " + gb(u.usedBytes()) + ", uploads would stop" : "")
                 + ". Ask for the plan to be renewed before then." + renewing(u);
        }
        if (u.endsWithin(now, 7)) {
            return "The shop's " + plan(u) + " ends on " + day(u.planExpiresAt())
                 + ". Ask for it to be renewed before then so backups and photos keep going up."
                 + renewing(u);
        }
        return null;
    }

    /**
     * True when an exception carries the box's own words for an upload that
     * would cross the account's limit.
     */
    public static boolean looksFull(Throwable e) {
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
     * A 507 that carries its message in the body.
     *
     * <p>Thrown as a ResponseStatusException, Spring's default error body leaves
     * the message out (and switching that on would change every other error body
     * the phone and the agent read), so the words never reached the shop PC: its
     * progress window showed "status=507" and nothing else. Built here, the agent
     * prints the sentence that says what to do.
     */
    public static ResponseEntity<Map<String, Object>> refusal(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.INSUFFICIENT_STORAGE.value());
        body.put("error", "Insufficient Storage");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE).body(body);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
