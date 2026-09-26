package com.magizhchi.sync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Everything the shop PC needs, in one run — the other half of
 * PawnBrokingSyncSetup.exe.
 *
 * The installer copies the files and registers the service; this does the
 * work that used to be a runbook: write (or merge) sync.properties, prove
 * the cloud key, install the agent's own schema, add the bits the desktop
 * app needs (SUSPENSE, Re+ pricing, notice mode), give repledge_billing a
 * primary key, send the history ONCE, and check that the photo and backup
 * folders the agent uploads from actually exist on this machine.
 *
 * Safe on an existing shop: every step is idempotent and the history is
 * guarded by a marker on sync_outbox, so a shop that has already sent it
 * never sends it twice.
 *
 * Three modes, because a shop that is already working should not be touched:
 *
 *   --mode check      LOOKS ONLY. Not one statement writes. Use it on a live
 *                     shop to see what is there and what is behind.
 *   --mode backups    The backup files and nothing else: what is on disk, what
 *                     is uploaded, what is held back by backup.retention.days.
 *                     It may change only that one setting (--backup-retention)
 *                     and the agent's own upload bookkeeping
 *                     (--requeue-backups). It never touches bills, photos,
 *                     triggers or the history.
 *   --mode full       The whole shop PC setup, for a new shop or a repair
 *                     (default, and what --run alone means).
 *
 * Usage (the installer does this for you):
 *   java -cp pawnbroking-sync-agent.jar com.magizhchi.sync.Setup --write-config \
 *        --shop-id x --api-key mbk_... --cloud-url https://... \
 *        --db-user postgres --db-password secret
 *   java -cp pawnbroking-sync-agent.jar com.magizhchi.sync.Setup --run --mode check
 *
 * Options for --run:
 *   --config PATH          sync.properties (default %PROGRAMDATA%\PawnBroking\sync.properties)
 *   --mode MODE            check | backups | full (default full)
 *   --history MODE         auto (default) | skip | force   [full only]
 *   --backup-retention N   set backup.retention.days to N  [backups only]
 *   --requeue-backups      forget the upload records so every backup in the
 *                          window is uploaded again        [backups only]
 *   --report PATH          where to write the report (default beside the config)
 *   --wait-db SECS         keep retrying the database this long (default 60)
 *   --no-cloud-check       don't talk to the cloud
 */
public final class Setup {

    private static final String DEFAULT_CONFIG =
            System.getenv("PROGRAMDATA") + "\\PawnBroking\\sync.properties";

    /** Keys we write and their defaults; anything already in the file wins,
     *  except batch.size, which is corrected if it is one of the old stalling values. */
    private static final String[][] DEFAULTS = {
            {"db.url",                   "jdbc:postgresql://localhost:5432/pawnbroking"},
            {"batch.size",               "25"},
            {"poll.interval.ms",         "5000"},
            {"health.port",              "17654"},
            {"schema.guard.interval.ms", "30000"},
            {"image.scan.interval.ms",   "60000"},
            {"image.upload.interval.ms", "1100"},
            {"backup.retention.days",    "30"},
            {"backup.gzip",              "true"},
            {"backup.gzip.min.bytes",    "5242880"},
    };

    private static final Set<String> IMAGE_EXT =
            Set.of(".png", ".jpg", ".jpeg", ".webp");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final StringBuilder REPORT = new StringBuilder();
    private static final List<String> WARNINGS = new ArrayList<>();

    public static void main(String[] args) {
        // Before any class that holds a logger is touched: the shop reads the
        // setup's own lines, not Hikari's and SchemaGuard's.
        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", "setup-logback.xml");

        Map<String, String> a = parseArgs(args);
        try {
            readAnswersFile(a);
            if (a.containsKey("write-config")) {
                writeConfig(a);
                System.exit(0);
            }
            System.exit(run(a));
        } catch (Throwable t) {
            say("");
            say("STOPPED: " + t.getMessage());
            if (a.containsKey("debug")) t.printStackTrace();
            try { writeReportFile(a.getOrDefault("report", defaultReportPath(a))); } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    // ── config ────────────────────────────────────────────────────────────────

    /**
     * Create or update sync.properties without losing anything the shop already
     * has. Identity values are replaced, missing tuning keys are appended, and a
     * batch.size big enough to stall the queue is corrected.
     */
    private static void writeConfig(Map<String, String> a) throws Exception {
        Path path = Paths.get(a.getOrDefault("config", DEFAULT_CONFIG));
        Files.createDirectories(path.getParent());

        List<String> lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.ISO_8859_1))
                : new ArrayList<>();

        if (!lines.isEmpty()) {
            Path bak = path.resolveSibling("sync.properties." +
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
                            .format(Instant.now()) + ".bak");
            Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
            say("kept a copy of the old config as " + bak.getFileName());
        } else {
            lines.add("# =====================================================================");
            lines.add("#  Pawnbroking Sync Agent - per-shop configuration");
            lines.add("#  Written by PawnBrokingSyncSetup.exe. Run the setup again rather");
            lines.add("#  than editing this by hand.");
            lines.add("# =====================================================================");
            lines.add("");
        }

        Map<String, String> set = new LinkedHashMap<>();
        putIf(set, "shop.id",       a.get("shop-id"));
        putIf(set, "cloud.api_key", a.get("api-key"));
        putIf(set, "cloud.url",     a.get("cloud-url"));
        putIf(set, "db.user",       a.get("db-user"));
        putIf(set, "db.password",   a.get("db-password"));
        putIf(set, "db.url",        a.get("db-url"));

        // Correct a batch size that cannot finish inside the agent's 30s timeout.
        String existingBatch = readKey(lines, "batch.size");
        if (existingBatch != null && isNumber(existingBatch) && Integer.parseInt(existingBatch) > 50) {
            set.put("batch.size", "25");
            say("batch.size was " + existingBatch + " - corrected to 25 (200 stalls on a backlog)");
        }

        for (Map.Entry<String, String> e : set.entrySet()) {
            replaceOrAppend(lines, e.getKey(), e.getValue());
        }
        // Anything the file has never heard of gets the safe default.
        List<String> added = new ArrayList<>();
        for (String[] d : DEFAULTS) {
            if (readKey(lines, d[0]) == null) {
                lines.add(d[0] + "=" + escape(d[1]));
                added.add(d[0]);
            }
        }
        if (!added.isEmpty()) say("added missing settings: " + String.join(", ", added));

        Files.write(path, lines, StandardCharsets.ISO_8859_1);
        say("config written: " + path);
    }

    /**
     * The installer hands the wizard's answers over in a file rather than on the
     * command line: Inno writes every Exec parameter into its own setup log, and
     * the shop's database password has no business being there. Read verbatim —
     * no escaping — and deleted as soon as it is read.
     */
    private static void readAnswersFile(Map<String, String> a) {
        String path = a.get("answers");
        if (path == null || path.isBlank()) return;
        Path f = Paths.get(path);
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                // Inno writes the file with a BOM; it would otherwise ride along
                // in the first key's name.
                String t = line.replace("﻿", "").strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int i = t.indexOf('=');
                if (i <= 0) continue;
                // The value is taken exactly as written, so a password may hold
                // backslashes, spaces, quotes or '=' without any escaping.
                a.putIfAbsent(t.substring(0, i).trim(), t.substring(i + 1));
            }
        } catch (IOException e) {
            say("could not read the answers file " + path + ": " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(f); } catch (IOException ignored) { }
        }
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String readKey(List<String> lines, String key) {
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("#") || t.startsWith("!")) continue;
            int i = t.indexOf('=');
            if (i > 0 && t.substring(0, i).trim().equals(key)) return t.substring(i + 1).trim();
        }
        return null;
    }

    private static void replaceOrAppend(List<String> lines, String key, String value) {
        String out = key + "=" + escape(value);
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("#") || t.startsWith("!")) continue;
            int p = t.indexOf('=');
            if (p > 0 && t.substring(0, p).trim().equals(key)) { lines.set(i, out); return; }
        }
        lines.add(out);
    }

    /** A .properties value: backslashes are escapes, so a Windows path must double them. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\");
    }

    private static boolean isNumber(String s) {
        try { Integer.parseInt(s.trim()); return true; } catch (Exception e) { return false; }
    }

    // ── the run ───────────────────────────────────────────────────────────────

    private static int run(Map<String, String> a) throws Exception {
        String configPath = a.getOrDefault("config", DEFAULT_CONFIG);
        String mode = a.getOrDefault("mode", "full");
        if (!List.of("check", "backups", "full").contains(mode))
            throw new IllegalArgumentException("--mode must be check, backups or full");
        boolean writes = "full".equals(mode);

        String historyMode = a.getOrDefault("history", "auto");   // may become "skip" on a full disk
        if (!List.of("auto", "skip", "force").contains(historyMode))
            throw new IllegalArgumentException("--history must be auto, skip or force");

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(configPath))) { p.load(in); }
        String shopId   = required(p, "shop.id");
        String dbUrl    = required(p, "db.url");
        String dbUser   = required(p, "db.user");
        String dbPass   = required(p, "db.password");
        String cloudUrl = required(p, "cloud.url");
        String apiKey   = required(p, "cloud.api_key");

        head("Pawnbroking shop setup");
        say("Shop      : " + shopId);
        say("Database  : " + dbUrl);
        say("Cloud     : " + cloudUrl);
        say("Started   : " + STAMP.format(Instant.now()));
        switch (mode) {
            case "check" -> say("What it does: LOOKS ONLY. Nothing on this PC is changed.");
            case "backups" -> say("What it does: the BACKUP FILES only. The database, the history and " +
                                  "the photos are left exactly as they are.");
            default -> say("What it does: the full setup - agent schema, the desktop bits, the history " +
                           "once, and the folder checks.");
        }

        // 1. The database, with patience — PostgreSQL may still be starting.
        head("1. Local database");
        int waitDb = Integer.parseInt(a.getOrDefault("wait-db", "60"));
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(dbUrl);
        hc.setUsername(dbUser);
        hc.setPassword(dbPass);
        hc.setMaximumPoolSize(2);
        hc.setPoolName("pawnbroking-setup");
        hc.setConnectionTimeout(10_000);
        try (HikariDataSource ds = new HikariDataSource(hc)) {
            waitForDb(ds, waitDb);
            long freeGb;
            try (Connection c = ds.getConnection()) {
                say("connected as " + dbUser + ", server " + c.getMetaData().getDatabaseProductVersion());
                if (!tableExists(c, "company_billing"))
                    throw new IllegalStateException(
                            "this database has no company_billing table, so it is not the shop database. " +
                            "Correct db.url in " + configPath + " (it must end in /pawnbroking) and run the setup again.");
                freeGb = checkDiskSpace(c);
            }

            // The history send rewrites every row it sends. On a disk this full
            // that can stop PostgreSQL - and the desktop app with it - so the
            // rest of the setup goes ahead and the send waits for free space.
            if (writes && freeGb >= 0 && freeGb < 2 && !"force".equals(historyMode)) {
                historyMode = "skip";
                warn("only " + freeGb + " GB free where PostgreSQL keeps its data - the history was NOT sent. " +
                     "Free some space (2 GB is enough for most shops) and run the setup again.");
            }

            // 2. Does the cloud know this shop?
            head("2. Cloud");
            if (a.containsKey("no-cloud-check")) say("skipped (--no-cloud-check)");
            else cloudHandshake(cloudUrl, apiKey, shopId);

            List<String[]> report;
            if (writes) {
                // 3. The agent's own schema — outbox, capture function, triggers.
                head("3. Sync tables and triggers");
                new SchemaGuard(ds, shopId, 30_000).ensureNow();
                // The agent makes these when it first runs; making them here means the
                // report and the progress shortcut work before the service has started.
                ImageWatcher.ensureTrackerTables(ds);
                try (Connection c = ds.getConnection()) {
                    say("sync_outbox " + (tableExists(c, "sync_outbox") ? "ready" : "MISSING"));
                    say(countTriggers(c) + " tables have a capture trigger");
                }

                // 4. The desktop app's own additions.
                head("4. Desktop database bits");
                try (Connection c = ds.getConnection()) {
                    addSuspenseStatus(c);
                }

                // 5. Everything else, plus the one-time history send.
                head("5. Shop setup and history");
                String sql = loadShopSetupSql(shopId, historyMode);
                Path resolved = Paths.get(configPath).resolveSibling("shop_setup_" + shopId + ".sql");
                try { Files.writeString(resolved, sql, StandardCharsets.UTF_8); } catch (Exception ignored) {}
                try (Connection c = ds.getConnection()) {
                    if ("force".equals(historyMode) && tableExists(c, "sync_outbox")) {
                        try (Statement st = c.createStatement()) {
                            st.execute("COMMENT ON TABLE sync_outbox IS NULL");
                        }
                        say("--history force: the previous send marker was cleared");
                    }
                    say("working ... the history send can take a few minutes on a big shop");
                    report = runScript(c, sql);
                }
            } else {
                // Look, and say what is there. Not one statement below writes.
                head("3. What this shop looks like");
                try (Connection c = ds.getConnection()) {
                    say("sync_outbox " + (tableExists(c, "sync_outbox") ? "ready" : "MISSING - the agent has never run here"));
                    say(countTriggers(c) + " tables have a capture trigger");
                    report = runScript(c, loadStatusSql());
                }
            }

            // 6. Photos and backups are files, not rows.
            head(writes ? "6. Photo and backup folders" : "4. Photo and backup folders");
            try (Connection c = ds.getConnection()) {
                checkFolders(c, p, !writes);
                backupDetail(c, p, "backups".equals(mode), a);
            }

            head("Report");
            printReport(report);
        }

        if (!WARNINGS.isEmpty()) {
            head("Needs attention");
            for (String w : WARNINGS) say(" - " + w);
        }

        head("What happens next");
        switch (mode) {
            case "check" -> {
                say("1. NOTHING on this PC was changed by this run - it only looked.");
                say("2. Anything above that says \"not there\" is only put right by Full setup.");
                say("3. If only the backups are wrong, run the setup again and choose");
                say("   \"Backups only\": it touches the backup files and nothing else.");
            }
            case "backups" -> {
                say("1. Only the backup side was looked at, and only it could have changed.");
                say("2. The agent uploads what is queued by itself, about 54 files a minute.");
                say("3. If a retention change was made, restart the service for it to count:");
                say("      pawnbroking-sync.exe restart");
                say("4. Bills, photos, the history, the triggers and every other setting were");
                say("   left exactly as they were.");
            }
            default -> {
                say("1. The agent sends the queue 25 rows at a time, by itself. Leave the PC on.");
                say("2. Photos and backups go up at about 54 files a minute - a shop with a lot of");
                say("   photos takes a few hours the first time.");
                say("3. Photos and backups are REFUSED (503) until someone signs in once on the");
                say("   phone for this shop. That first sign-in is what mints the box token.");
                say("4. Nothing here needs doing again. Run this setup again only after restoring a");
                say("   database, moving the shop to another PC, or when told to.");
            }
        }

        head("Done");
        say("Finished  : " + STAMP.format(Instant.now()));
        say("Run \"Sync progress report\" on the desktop at any time to see how far it has got.");
        String reportPath = a.getOrDefault("report", defaultReportPath(a));
        writeReportFile(reportPath);
        say("This report: " + reportPath);
        return 0;
    }

    private static String required(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank())
            throw new IllegalStateException("sync.properties has no " + k);
        return v.trim();
    }

    private static void waitForDb(HikariDataSource ds, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = ds.getConnection()) { return; }
            catch (Exception e) {
                last = e;
                say("waiting for PostgreSQL ...");
                Thread.sleep(3000);
            }
        }
        throw new IllegalStateException(
                "cannot reach the database: " + (last == null ? "?" : last.getMessage()) +
                ". Check PostgreSQL is running and that db.user / db.password are the ones pgAdmin uses.");
    }

    /** @return free GB on the drive PostgreSQL keeps its data on, or -1 if unknown. */
    private static long checkDiskSpace(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW data_directory")) {
            if (!rs.next()) return -1;
            Path dir = Paths.get(rs.getString(1));
            Path root = dir.getRoot();
            if (root == null) return -1;
            long freeGb = root.toFile().getUsableSpace() / (1024L * 1024 * 1024);
            say("free space on " + root + " " + freeGb + " GB");
            return freeGb;
        } catch (Exception e) {
            // SHOW data_directory needs a superuser; not worth failing the run over.
            return -1;
        }
    }

    /** Prove the key and the tenant before anything else gets queued behind a 401. */
    private static void cloudHandshake(String cloudUrl, String apiKey, String shopId) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cloudUrl.replaceAll("/+$", "") + "/v1/sync"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"shop_id\":\"" + shopId + "\",\"events\":[]}"))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 200 && r.statusCode() < 300) {
                say("the cloud accepted this shop's key");
            } else if (r.statusCode() == 401 || r.statusCode() == 403) {
                warn("the cloud refused the key (" + r.statusCode() + "). cloud.api_key is wrong, or it was " +
                     "revoked. Take the mbk_ key from R5 of the shop's cloud provisioning file and run the setup again.");
            } else if (r.statusCode() >= 500) {
                warn("the cloud answered " + r.statusCode() + ". Most often the shop is not in the TENANTS " +
                     "variable yet (R4) - add it, wait for the redeploy, then run the setup again. Data queues " +
                     "up safely on this PC until then.");
            } else {
                warn("the cloud answered " + r.statusCode() + ": " + firstLine(r.body()));
            }
        } catch (Exception e) {
            warn("could not reach " + cloudUrl + " (" + e.getMessage() + "). The agent keeps trying by itself; " +
                 "nothing is lost, but check the shop's internet.");
        }
    }

    /**
     * SUSPENSE has to go in on its own connection, outside any batch:
     * older PostgreSQL refuses ALTER TYPE ... ADD VALUE inside a transaction.
     */
    private static void addSuspenseStatus(Connection c) throws SQLException {
        if (!typeExists(c, "company_bill_status")) {
            say("company_bill_status enum not found - skipped");
            return;
        }
        if (enumHasLabel(c, "company_bill_status", "SUSPENSE")) {
            say("SUSPENSE bill status already there");
            return;
        }
        boolean afterCanceled = enumHasLabel(c, "company_bill_status", "CANCELED");
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TYPE company_bill_status ADD VALUE IF NOT EXISTS 'SUSPENSE'" +
                       (afterCanceled ? " AFTER 'CANCELED'" : ""));
            say("SUSPENSE bill status added");
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** The read-only twin of shop_setup.sql: it looks, and changes nothing. */
    private static String loadStatusSql() throws IOException {
        try (InputStream in = Setup.class.getResourceAsStream("/setup/status_report.sql")) {
            if (in == null) throw new IllegalStateException("status_report.sql missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String loadShopSetupSql(String shopId, String historyMode) throws IOException {
        try (InputStream in = Setup.class.getResourceAsStream("/setup/shop_setup.sql")) {
            if (in == null) throw new IllegalStateException("shop_setup.sql missing from the jar");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String note = switch (historyMode) {
                case "skip"  -> "This run was told NOT to send the history.";
                case "force" -> "This run was told to send the history again on purpose.";
                default      -> "Sent once, then never again.";
            };
            return sql.replace("${SHOP_ID}", shopId)
                      .replace("${HISTORY_MODE}", historyMode)
                      .replace("${HISTORY_MODE_NOTE}", note);
        }
    }

    /**
     * The whole file goes to PostgreSQL as one statement, exactly as pgAdmin's
     * F5 sends it, so the settings S6 and S7 leave behind are still there when
     * S8 reads them. The last result set is the report.
     */
    private static List<String[]> runScript(Connection c, String sql) throws SQLException {
        List<String[]> last = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(0);           // the history send is allowed to take its time
            boolean isResultSet = st.execute(sql);
            while (true) {
                if (isResultSet) {
                    List<String[]> rows = new ArrayList<>();
                    try (ResultSet rs = st.getResultSet()) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            String[] row = new String[cols];
                            for (int i = 0; i < cols; i++) row[i] = rs.getString(i + 1);
                            rows.add(row);
                        }
                    }
                    if (!rows.isEmpty()) last = rows;
                }
                isResultSet = st.getMoreResults();
                if (!isResultSet && st.getUpdateCount() == -1) break;
            }
        }
        return last;
    }

    private static void printReport(List<String[]> rows) {
        if (rows.isEmpty()) { say("(the database returned no report)"); return; }
        for (String[] r : rows) {
            String item   = r.length > 1 ? nvl(r[1]) : "";
            String status = r.length > 2 ? nvl(r[2]) : "";
            say(String.format("%-42s %s", item, status));
            String s = status.toUpperCase(Locale.ROOT);
            if (s.startsWith("MISSING") || s.startsWith("WAITING") || s.startsWith("STOPPED"))
                warn(item + ": " + status);
        }
    }

    // ── photos and backups ────────────────────────────────────────────────────

    private static void checkFolders(Connection c, Properties p, boolean lookOnly) {
        String override = p.getProperty("image.root", "").trim();
        Map<String, String> photoRoots = new LinkedHashMap<>();
        if (!override.isEmpty()) photoRoots.put("(image.root)", override);
        else photoRoots.putAll(query2(c,
                "SELECT DISTINCT company_id, camera_temp_file_name FROM company_other_settings " +
                "WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> ''"));

        if (photoRoots.isEmpty()) {
            warn("no photo folder is set (company_other_settings.camera_temp_file_name) - no photo can ever upload.");
        } else {
            for (Map.Entry<String, String> e : photoRoots.entrySet()) {
                Path root = Paths.get(e.getValue());
                if (!Files.isDirectory(root)) {
                    warn("photo folder for " + e.getKey() + " does not exist on this PC: " + e.getValue() +
                         " - copy the images from the old machine to that exact path, or correct " +
                         "camera_temp_file_name for this company. Photos cannot upload until then.");
                    say("photos " + e.getKey() + ": MISSING " + e.getValue());
                } else {
                    long[] n = countFiles(root, true);
                    say("photos " + e.getKey() + ": " + n[0] + " image files in " + root);
                }
            }
        }

        if (lookOnly) say("(nothing above was changed - this run only looked)");
    }

    // ── backups ───────────────────────────────────────────────────────────────

    /**
     * The backup side on its own: what is on disk, what the agent has already
     * uploaded, what is left, and what is being held back by
     * backup.retention.days. With {@code repair} it may also change those two
     * things — the retention setting and the agent's own upload bookkeeping —
     * and NOTHING else on the machine.
     */
    private static void backupDetail(Connection c, Properties p, boolean repair, Map<String, String> a) {
        head(repair ? "Backup files (the only thing this run may change)" : "Backup files");

        int retention = 30;
        try { retention = Integer.parseInt(p.getProperty("backup.retention.days", "30").trim()); }
        catch (Exception ignored) { }
        say("backup.retention.days = " + retention + (retention == 0 ? " (every file, whatever its age)"
                                                                     : " (older files are never uploaded)"));

        Map<String, String> roots = query2(c,
                "SELECT id, backup_file_path FROM company " +
                "WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> ''");
        if (roots.isEmpty()) {
            warn("no backup folder is set (company.backup_file_path) - no backup can ever upload. " +
                 "Set it in the desktop app's Company Module.");
            return;
        }

        Map<String, long[]> uploaded = loadUploadedBackups(c);
        long cutoff = System.currentTimeMillis() - retention * 86_400_000L;
        long totFiles = 0, totDone = 0, totLeft = 0, totLeftBytes = 0, totHidden = 0, totHiddenBytes = 0, newest = 0;
        List<Path> toRequeue = new ArrayList<>();

        for (Map.Entry<String, String> e : roots.entrySet()) {
            Path root = Paths.get(e.getValue());
            if (!Files.isDirectory(root)) {
                warn("backup folder for " + e.getKey() + " does not exist on this PC: " + e.getValue() +
                     " - the desktop app writes its dumps there, so check the path in Company Module. " +
                     "Nothing was created.");
                say("  " + e.getKey() + ": MISSING " + e.getValue());
                continue;
            }
            long files = 0, done = 0, left = 0, leftBytes = 0, hidden = 0, hiddenBytes = 0, rootNewest = 0;
            for (Path f : listFiles(root)) {
                long size, mtime;
                try {
                    BasicFileAttributes at = Files.readAttributes(f, BasicFileAttributes.class);
                    size = at.size();
                    mtime = at.lastModifiedTime().toMillis();
                } catch (IOException ex) { continue; }
                files++;
                if (mtime > rootNewest) rootNewest = mtime;
                long[] row = uploaded.get(f.toAbsolutePath().toString().toLowerCase(Locale.ROOT));
                // The agent's own rule: same path, same size, not touched since.
                boolean isDone = row != null && row[0] == size && mtime <= row[1] + 5000;
                if (isDone) { done++; continue; }
                if (retention > 0 && mtime < cutoff) { hidden++; hiddenBytes += size; continue; }
                left++; leftBytes += size;
                toRequeue.add(f);
            }
            say("  " + e.getKey() + " " + root);
            say("     on disk " + files + ", uploaded " + done + ", still to upload " + left +
                " (" + mb(leftBytes) + ")" + (hidden > 0 ? ", held back by retention " + hidden + " (" + mb(hiddenBytes) + ")" : ""));
            say("     newest file " + (rootNewest > 0 ? STAMP.format(Instant.ofEpochMilli(rootNewest)) : "none"));
            if (files == 0)
                warn("the backup folder " + root + " is empty - the desktop app has never written a dump " +
                     "there, so there is nothing for the phone to show.");
            else if (rootNewest > 0 && rootNewest < System.currentTimeMillis() - 7L * 86_400_000L)
                warn("the newest backup in " + root + " is from " + STAMP.format(Instant.ofEpochMilli(rootNewest)) +
                     " - the DESKTOP backup job has stopped writing files. The agent can only upload what is there.");
            totFiles += files; totDone += done; totLeft += left; totLeftBytes += leftBytes;
            totHidden += hidden; totHiddenBytes += hiddenBytes;
            if (rootNewest > newest) newest = rootNewest;
        }

        say("  TOTAL on disk " + totFiles + ", uploaded " + totDone + ", still to upload " + totLeft +
            " (" + mb(totLeftBytes) + ")");
        String lastUp = scalar(c, "SELECT COALESCE(to_char(max(uploaded_at), 'DD-MM-YYYY HH24:MI'), 'never') " +
                                  "FROM sync_backup_uploads");
        say("  last upload recorded on this PC: " + lastUp);

        if (totHidden > 0)
            say("  " + totHidden + " older file(s) (" + mb(totHiddenBytes) + ") are skipped on purpose by " +
                "backup.retention.days=" + retention + ". To send them too, set it to 0.");

        for (String problem : recentUploadProblems())
            warn("the agent's log shows: " + problem);

        if (!repair) {
            if (totLeft > 0)
                say("  " + totLeft + " file(s) are queued for the agent already - it uploads them by itself, " +
                    "about 54 a minute. Nothing needs doing here.");
            return;
        }

        // ---- repair, backups only ----
        String setRetention = a.get("backup-retention");
        if (setRetention != null && isNumber(setRetention)) {
            int want = Integer.parseInt(setRetention.trim());
            if (want != retention) {
                Path cfg = Paths.get(a.getOrDefault("config", DEFAULT_CONFIG));
                try {
                    List<String> lines = new ArrayList<>(Files.readAllLines(cfg, StandardCharsets.ISO_8859_1));
                    replaceOrAppend(lines, "backup.retention.days", String.valueOf(want));
                    Files.write(cfg, lines, StandardCharsets.ISO_8859_1);
                    say("  backup.retention.days changed from " + retention + " to " + want +
                        " - THE ONLY line touched in sync.properties.");
                    say("  Restart the service for it to take effect: pawnbroking-sync.exe restart");
                } catch (IOException ex) {
                    warn("could not change backup.retention.days: " + ex.getMessage());
                }
            } else {
                say("  backup.retention.days is already " + want + " - left alone.");
            }
        }

        if (a.containsKey("requeue-backups")) {
            // Only the agent's own bookkeeping. The cloud replaces a file uploaded
            // twice (same company, path and name), so nothing is duplicated there.
            // The capture trigger sits on this table as well, so it goes off for
            // this one statement: forgetting a local record is not news for the
            // cloud, and the shop's queue should not grow because of a repair.
            int n = execUpdateWithoutCapture(c, "DELETE FROM sync_backup_uploads");
            say("  forgot " + n + " upload record(s) - the agent will upload every backup in the window again.");
            say("  Bills, photos, the history and every other setting were not touched.");
        } else if (totLeft > 0) {
            say("  " + totLeft + " file(s) are already queued for the agent - no need to forget anything. " +
                "Use --requeue-backups only when the cloud is missing a file this PC thinks it sent.");
        }
    }

    private static Map<String, long[]> loadUploadedBackups(Connection c) {
        Map<String, long[]> out = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT abs_path, size_bytes, (extract(epoch from mtime) * 1000)::bigint " +
                     "FROM sync_backup_uploads")) {
            while (rs.next())
                out.put(rs.getString(1).replace('/', '\\').toLowerCase(Locale.ROOT),
                        new long[]{rs.getLong(2), rs.getLong(3)});
        } catch (SQLException e) {
            say("  (no upload record on this PC yet)");
        }
        return out;
    }

    /** Every file under a folder, depth first, with a time limit so a network drive cannot hang the run. */
    private static List<Path> listFiles(Path root) {
        List<Path> out = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + 60_000;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes at) {
                    if (System.currentTimeMillis() > deadline) return FileVisitResult.TERMINATE;
                    out.add(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
        return out;
    }

    /** What the agent's own log says went wrong lately, in words that mean something. */
    private static List<String> recentUploadProblems() {
        List<String> out = new ArrayList<>();
        List<Path> logs = List.of(
                Paths.get("C:\\pawnbrokingSync\\logs\\pawnbroking-sync.err.log"),
                Paths.get("C:\\pawnbrokingSync\\logs\\pawnbroking-sync.out.log"),
                Paths.get("C:\\Program Files\\PawnbrokingSync\\pawnbroking-sync.err.log"),
                Paths.get("C:\\Program Files\\PawnbrokingSync\\pawnbroking-sync.out.log"));
        Set<String> seen = new LinkedHashSet<>();
        for (Path log : logs) {
            if (!Files.isRegularFile(log)) continue;
            try {
                List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
                for (String l : lines.subList(Math.max(0, lines.size() - 400), lines.size())) {
                    if (l.contains("status=401") || l.contains("status=403"))
                        seen.add("the cloud refused the key (401) - cloud.api_key is wrong or revoked");
                    else if (l.contains("status=503"))
                        seen.add("503 - nobody has signed in on the phone for this shop yet, so photos and " +
                                 "backups are refused. One sign-in fixes it");
                    else if (l.contains("status=507"))
                        seen.add("507 - the shop's Magizhchi Share account is FULL. Upgrade it, or lower " +
                                 "backup.retention.days");
                    else if (l.contains("status=502"))
                        seen.add("502 on a backup - this is the too-big-to-proxy error that the current " +
                                 "agent fixes by gzipping first. This PC needs the agent updated (Full setup)");
                    else if (l.contains("status=413"))
                        seen.add("413 - the cloud is on an old deploy that refuses big files");
                    else if (l.contains("status=429"))
                        seen.add("429 - the box rate limit was hit; the agent slows down and retries by itself");
                }
            } catch (IOException ignored) { }
        }
        out.addAll(seen);
        return out;
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static String scalar(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? nvl(rs.getString(1)) : "";
        } catch (SQLException e) { return "-"; }
    }

    private static int execUpdate(Connection c, String sql) {
        try (Statement st = c.createStatement()) { return st.executeUpdate(sql); }
        catch (SQLException e) { warn("could not run " + sql + ": " + firstLine(e.getMessage())); return 0; }
    }

    /**
     * Run one statement with the sync triggers off for this connection only.
     * session_replication_role is per-session and needs a superuser; where the
     * shop's db.user is not one, the statement still runs and the events it
     * queues are named in the report rather than left as a surprise.
     */
    private static int execUpdateWithoutCapture(Connection c, String sql) {
        boolean quiet = false;
        try (Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            quiet = true;
        } catch (SQLException e) {
            say("  (db.user is not a superuser, so the agent will also send the forgotten records as " +
                "deletes - harmless, and the cloud ignores them)");
        }
        try {
            return execUpdate(c, sql);
        } finally {
            if (quiet) {
                try (Statement st = c.createStatement()) { st.execute("SET session_replication_role = origin"); }
                catch (SQLException ignored) { }
            }
        }
    }

    /** @return {count, totalBytes, newestMillis} — images only when imagesOnly. */
    private static long[] countFiles(Path root, boolean imagesOnly) {
        final long[] acc = {0, 0, 0};
        final long deadline = System.currentTimeMillis() + 120_000;   // a big shop has 160k photos
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes at) {
                    if (System.currentTimeMillis() > deadline) return FileVisitResult.TERMINATE;
                    if (imagesOnly) {
                        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        int dot = n.lastIndexOf('.');
                        if (dot < 0 || !IMAGE_EXT.contains(n.substring(dot))) return FileVisitResult.CONTINUE;
                    }
                    acc[0]++;
                    acc[1] += at.size();
                    long m = at.lastModifiedTime().toMillis();
                    if (m > acc[2]) acc[2] = m;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
        return acc;
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private static Map<String, String> query2(Connection c, String sql) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String k = rs.getString(1), v = rs.getString(2);
                if (k != null && v != null && !v.isBlank()) out.putIfAbsent(k, v.trim());
            }
        } catch (SQLException e) {
            say("could not read " + firstLine(e.getMessage()));
        }
        return out;
    }

    private static boolean tableExists(Connection c, String t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass('public.' || ?) IS NOT NULL")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        }
    }

    private static boolean typeExists(Connection c, String type) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_type WHERE typname = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean enumHasLabel(Connection c, String type, String label) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid " +
                "WHERE t.typname = ? AND e.enumlabel = ?")) {
            ps.setString(1, type);
            ps.setString(2, label);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static int countTriggers(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_sync_%' AND NOT tgisinternal")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (!s.startsWith("--")) continue;
            String k = s.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    private static String defaultReportPath(Map<String, String> a) {
        Path cfg = Paths.get(a.getOrDefault("config", DEFAULT_CONFIG));
        return cfg.resolveSibling("setup-report.txt").toString();
    }

    private static void writeReportFile(String path) throws IOException {
        Path p = Paths.get(path);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, REPORT.toString(), StandardCharsets.UTF_8);
    }

    private static void head(String title) {
        say("");
        say("== " + title + " " + "=".repeat(Math.max(0, 60 - title.length())));
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
        REPORT.append(line).append(System.lineSeparator());
    }

    private static void warn(String line) {
        WARNINGS.add(line);
        say("!! " + line);
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    private Setup() { }
}
