package com.magizhchi.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real folder behind a Magizhchi Box drive letter.
 *
 * <p>The Box app shows a group's folder as a drive (M:, N:, ...) with {@code subst},
 * and a subst letter lives only in the signed-in user's session. This agent runs as
 * a Windows service, so a backup or photo folder set as {@code M:\...} simply did not
 * exist for it, and looked exactly like a folder with nothing new in it. Alwarpuram
 * moved its backup folder to {@code M:\Shops\Alwarpuram\BACKUP} on 19-10-2026 and not
 * one file reached the cloud after that, while its bills and photos kept arriving.
 *
 * <p>The Box app publishes where each letter points, for readers like this one:
 * <pre>
 *   %ProgramData%\Magizhchi\Box\drives\&lt;windows-user&gt;.json
 *   {"updatedAt": "...", "drives": [{"letter": "M", "path": "C:\\Users\\u\\Magizhchi Box\\Shops", ...}]}
 * </pre>
 * The folder behind the letter is an ordinary local folder (Box keeps its files on
 * the PC, never cloud-only), and Box uploads what lands there whether it came in
 * through M: or not. So reading it here reads the very files the desktop wrote.
 */
final class BoxDrives {
    private static final Logger log = LoggerFactory.getLogger(BoxDrives.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DRIVE = Pattern.compile("^([A-Za-z]):(?:[\\\\/]+(.*))?$");

    /** Each configured path is announced once, not on every scan. */
    private static final Set<String> told = ConcurrentHashMap.newKeySet();

    private BoxDrives() {}

    /**
     * The folder to read for {@code configured}: the same place under the Box drive
     * folder its letter stands for, when there is one, else the path unchanged (and
     * the caller reports it missing, as before).
     *
     * <p>The Box folder wins even where the letter can be seen (a signed-in window):
     * {@code sync_backup_uploads} and {@code sync_image_uploads} remember files by
     * full path, so every reader must name a file the way the service does, or
     * files already sent would count as waiting.
     */
    static Path resolve(String configured) {
        String raw = configured == null ? "" : configured.trim();
        Path real = behindLetter(raw);
        if (real == null) return Paths.get(raw);
        if (told.add(raw.toLowerCase(Locale.ROOT))) {
            log.info("{} is a Magizhchi Box drive; reading the folder behind it: {}", raw, real);
        }
        return real;
    }

    /** A sentence for a report, or null when {@code configured} is not a Box drive path. */
    static String explain(String configured) {
        String raw = configured == null ? "" : configured.trim();
        Path real = behindLetter(raw);
        return real == null ? null : raw + " is a Magizhchi Box drive; the agent reads " + real;
    }

    /** True when the Box app has published a drive with this path's letter, folder or no folder. */
    static boolean isBoxLetter(String configured) {
        Matcher m = DRIVE.matcher(configured == null ? "" : configured.trim());
        return m.matches() && !drives(m.group(1)).isEmpty();
    }

    /** The existing folder behind {@code raw}'s drive letter, or null. */
    private static Path behindLetter(String raw) {
        Matcher m = DRIVE.matcher(raw);
        if (!m.matches()) return null;
        String rest = m.group(2) == null ? "" : m.group(2);
        // Two Windows users can each have an M:; the one that holds the folder wins.
        for (Published d : drives(m.group(1))) {
            try {
                Path p = rest.isEmpty() ? d.path : d.path.resolve(rest);
                if (Files.isDirectory(p)) return p;
            } catch (Exception ignored) { /* a malformed entry: try the next */ }
        }
        return null;
    }

    private record Published(Path path, String updatedAt) {}

    /** Every published drive with this letter, newest first. Never throws. */
    private static List<Published> drives(String letter) {
        List<Published> out = new ArrayList<>();
        String base = System.getenv("ProgramData");
        Path dir = Paths.get(base == null || base.isBlank() ? "C:\\ProgramData" : base,
                             "Magizhchi", "Box", "drives");
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                try {
                    JsonNode doc = JSON.readTree(f.toFile());
                    String when = doc.path("updatedAt").asText("");
                    for (JsonNode d : doc.path("drives")) {
                        String path = d.path("path").asText("");
                        if (letter.equalsIgnoreCase(d.path("letter").asText("")) && !path.isBlank()) {
                            out.add(new Published(Paths.get(path), when));
                        }
                    }
                } catch (Exception e) {
                    log.debug("unreadable Box drive file {}: {}", f, e.toString());
                }
            }
        } catch (Exception e) {
            log.debug("cannot list {}: {}", dir, e.toString());
        }
        out.sort(Comparator.comparing(Published::updatedAt).reversed());
        return out;
    }
}
