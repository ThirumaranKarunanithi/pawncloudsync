package com.magizhchi.cloud.rates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Today's 22 carat gold and silver a gram from goodreturns.in, read the way the desktop's TodaysRate reads it
 * (same pages, same checks, same patterns). Used only when a shop's desktop has not taken today's rate yet.
 *
 * <p>Each city is asked at most once in {@link #KEEP}, whichever shop asks; a city that failed is not asked
 * again for {@link #KEEP_FAILED}. Nothing here ever throws to the caller: no rate is {@code null}.
 */
@Component
public class GoodReturns {
    private static final Logger log = LoggerFactory.getLogger(GoodReturns.class);
    public static final String SOURCE = "goodreturns.in";
    static final String FALLBACK_CITY = "Chennai";
    static final Duration KEEP = Duration.ofMinutes(30);
    static final Duration KEEP_FAILED = Duration.ofMinutes(10);
    static String base = "https://www.goodreturns.in/";

    /** What the pages gave: 22 carat gold and silver a gram, for which city, on which day, read when. */
    public record Rates(double gold, double silver, String city, LocalDate date, long readAt) { }

    private record Kept(Rates rates, long at) { }
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The city's rates, else Chennai's; null when neither could be read. {@code today} is the shop's date. */
    public Rates rates(String city, LocalDate today) {
        String c = plainCity(city);
        if (!c.isEmpty() && !c.equalsIgnoreCase(FALLBACK_CITY)) {
            Rates r = cached(c, today);
            if (r != null) return r;
        }
        return cached(FALLBACK_CITY, today);
    }

    /** "MADURAI-625002" / "Madurai 625 002" / "Madurai, TN" -> "MADURAI" / "Madurai" / "Madurai". */
    static String plainCity(String city) {
        if (city == null) return "";
        String c = city.trim();
        int comma = c.indexOf(',');
        if (comma > 0) c = c.substring(0, comma);
        return c.replaceAll("[\\s.-]*\\d[\\d\\s]*$", "").trim();
    }

    static String slug(String city) {
        return city.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private Rates cached(String city, LocalDate today) {
        String key = slug(city);
        if (key.isEmpty()) return null;
        long now = System.currentTimeMillis();
        Kept k = kept.get(key);
        if (k != null) {
            boolean fresh = k.rates != null ? now - k.at < KEEP.toMillis() : now - k.at < KEEP_FAILED.toMillis();
            if (fresh) return k.rates;
        }
        Rates r;
        try {
            r = fetch(key, today);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("today's rate: {} could not be read for {}: {}", SOURCE, city, e.toString());
            r = null;
        }
        kept.put(key, new Kept(r, now));
        return r;
    }

    private Rates fetch(String slug, LocalDate today) throws Exception {
        CompletableFuture<String> gold = get(base + "gold-rates/" + slug + ".html");
        CompletableFuture<String> silver = get(base + "silver-rates/" + slug + ".html");
        String goldPage = gold.get(10, TimeUnit.SECONDS), silverPage = silver.get(10, TimeUnit.SECONDS);
        if (goldPage == null || silverPage == null) return null;            // no page for this city
        String name = titleCity(goldPage);
        if (name == null || !name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(slug.replace("-", ""))) {
            return null;                                                      // not this city's page
        }
        LocalDate date = pageDate(goldPage);
        if (date == null || date.isBefore(today.minusDays(2))) {
            throw new IOException("the page for " + name + " is not up to date (" + date + ")");
        }
        double g = gold(goldPage), s = silver(silverPage);
        if (g < 2000 || g > 100000 || s < 20 || s > 2000) {
            throw new IOException("rates that do not look right: gold " + g + ", silver " + s);
        }
        return new Rates(g, s, name, date, System.currentTimeMillis());
    }

    /** The page, or null on 404. */
    private CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(9))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PawnBroking")
                .header("Accept", "text/html").GET().build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() == 404) return null;
            if (res.statusCode() != 200) throw new IllegalStateException(SOURCE + " answered " + res.statusCode());
            return res.body();
        });
    }

    // ------------------------------------------------------------------ reading a page (as the desktop does)

    static String text(String html) {
        String t = html.replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ");
        t = t.replace("&#x20b9;", "₹").replace("&#8377;", "₹").replace("&amp;", "&").replace("&nbsp;", " ");
        return t.replaceAll("\\s+", " ");
    }

    private static double number(String s) { return Double.parseDouble(s.replace(",", "")); }

    static double gold(String html) throws IOException {
        String t = text(html);
        Matcher m = Pattern.compile("22K Gold /g ₹ ?([\\d,]+(?:\\.\\d+)?)").matcher(t);
        if (m.find()) return number(m.group(1));
        m = Pattern.compile("₹ ?([\\d,]+(?:\\.\\d+)?) per gram for 22 karat").matcher(t);
        if (m.find()) return number(m.group(1));
        throw new IOException("the 22 carat gold rate is not on the page");
    }

    static double silver(String html) throws IOException {
        String t = text(html);
        Matcher m = Pattern.compile("Silver /g ₹ ?([\\d,]+(?:\\.\\d+)?)").matcher(t);
        if (m.find()) return number(m.group(1));
        m = Pattern.compile("price of silver in [A-Za-z .-]+ today is ₹ ?([\\d,]+(?:\\.\\d+)?) per gram").matcher(t);
        if (m.find()) return number(m.group(1));
        throw new IOException("the silver rate is not on the page");
    }

    private static String title(String html) {
        Matcher m = Pattern.compile("(?is)<title>(.*?)</title>").matcher(html);
        return m.find() ? m.group(1).replace("&amp;", "&").trim() : "";
    }

    static String titleCity(String html) {
        Matcher m = Pattern.compile("Gold Rate in ([^,]+),").matcher(title(html));
        return m.find() ? m.group(1).trim() : null;
    }

    static LocalDate pageDate(String html) {
        Matcher m = Pattern.compile("on (\\d{1,2}) ([A-Za-z]+) (\\d{4})").matcher(title(html));
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1) + " " + m.group(2) + " " + m.group(3),
                    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
