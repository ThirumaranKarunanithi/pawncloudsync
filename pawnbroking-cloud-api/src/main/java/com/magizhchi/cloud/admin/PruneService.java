package com.magizhchi.cloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps the cloud database from filling up with history nobody reads.
 *
 * Every shop's schema has three tables that grow: {@code events} (the raw
 * log of every sync message ever sent), {@code notifications} (the bell
 * list) and {@code projections} (the current row of every bill, customer
 * and day account). Only the third is what the phone app reads — and this
 * job never touches it. The first two are pruned to a window.
 *
 * What it learned the hard way, on 21-09-2026, when the Railway volume hit
 * 91%: raw events were two thirds of a 4 GB database, and the shops' oldest
 * events were 113 days old — so a 180-day window would have deleted nothing
 * at all. The default is 90 days, and the window is a setting rather than a
 * constant so it can be changed from the console without a redeploy.
 *
 * Deleting does not shrink the file; it stops the growth and lets Postgres
 * reuse the room. VACUUM (never VACUUM FULL, which needs as much free space
 * as the table is big) is run afterwards so that reuse actually happens.
 */
@Service
public class PruneService {
    private static final Logger log = LoggerFactory.getLogger(PruneService.class);

    /** Rows per DELETE. One huge transaction on a nearly full volume is how a warning becomes an outage. */
    private static final int BATCH = 20_000;
    /** A nightly job must be finished long before the shops open. */
    private static final long BUDGET_MS = 20 * 60_000L;
    /** Only one instance may prune at a time, however many are running. */
    private static final long LOCK_KEY = 7712_3311L;

    private final JdbcTemplate jdbc;
    private final boolean scheduleEnabled;

    public PruneService(JdbcTemplate jdbc,
                        @Value("${pawnbroking.prune.schedule-enabled:true}") boolean scheduleEnabled) {
        this.jdbc = jdbc;
        this.scheduleEnabled = scheduleEnabled;
    }

    /**
     * 02:30 India time: the shops are shut, the desktop backups have run,
     * and any agent that was catching up has had all night to do it.
     */
    @Scheduled(cron = "${pawnbroking.prune.cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        if (!scheduleEnabled) {
            log.info("nightly prune is switched off by configuration");
            return;
        }
        try {
            run("schedule", false);
        } catch (Exception e) {
            // A failed prune must never take the API down with it.
            log.error("nightly prune failed: {}", e.toString(), e);
        }
    }

    /**
     * @param triggeredBy "schedule", or the admin's email when run from the console
     * @param dryRun      count what would go, delete nothing
     */
    public synchronized Map<String, Object> run(String triggeredBy, boolean dryRun) {
        Boolean enabled = Boolean.valueOf(setting("prune.enabled", "true"));
        int eventDays = intSetting("prune.events.days", 90);
        int notifDays = intSetting("prune.notifications.days", 30);
        boolean vacuum = Boolean.parseBoolean(setting("prune.vacuum", "true"));

        if (!enabled && !dryRun) {
            log.info("prune is switched off in the console settings");
            return Map.of("skipped", true, "reason", "switched off in the console");
        }

        // Refuse to delete when the volume is nearly full.
        //
        // Deleting rows WRITES: every deleted row goes into the write-ahead
        // log first, and that log only shrinks at a checkpoint. On 21-09-2026
        // a prune of 2.3 million rows was started with 360 MB free, filled
        // pg_wal, and Postgres shut itself down and could not restart until
        // the volume was resized. Batching kept each transaction small, which
        // is not the same as keeping the log small.
        //
        // Postgres cannot see its own disk, so the volume size is a number the
        // console is told once. Left at 0 the check is simply skipped — it
        // cannot invent a limit it has no way to know.
        if (!dryRun) {
            String tight = spaceWarning();
            if (tight != null) {
                log.warn("prune refused: {}", tight);
                return Map.of("skipped", true, "reason", tight);
            }
        }
        // 0 means "keep everything" for that table — a deliberate off switch,
        // not a bug, so it is honoured rather than corrected.
        if (eventDays <= 0 && notifDays <= 0) {
            return Map.of("skipped", true, "reason", "both windows are 0, so nothing is ever pruned");
        }

        // The advisory lock belongs to the CONNECTION that took it, so that one
        // connection is held open for the whole run and released at the end.
        // Taking it with one pooled connection and releasing with another would
        // leave the lock stuck on a connection sitting idle in the pool, and
        // every later prune would find the database "already pruning".
        try (java.sql.Connection lockConn = jdbc.getDataSource().getConnection()) {
            if (!tryLock(lockConn)) {
                log.warn("another prune is already running — this one steps aside");
                return Map.of("skipped", true, "reason", "another prune is already running");
            }
            try {
                return doRun(triggeredBy, dryRun, eventDays, notifDays, vacuum);
            } finally {
                unlock(lockConn);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("prune could not reach the database: " + e.getMessage(), e);
        }
    }

    private boolean tryLock(java.sql.Connection c) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (var rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        }
    }

    private void unlock(java.sql.Connection c) {
        try (var ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.executeQuery().close();
        } catch (java.sql.SQLException e) {
            log.warn("could not release the prune lock: {}", e.toString());
        }
    }

    private Map<String, Object> doRun(String triggeredBy, boolean dryRun,
                                      int eventDays, int notifDays, boolean vacuum) {
        long runId = -1;
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        long events = 0, notifs = 0, repledge = 0, bytesBefore = totalBytes(), shops = 0;
        List<String> touched = new ArrayList<>();
        try {
            runId = jdbc.queryForObject(
                    "INSERT INTO public.prune_runs(triggered_by, dry_run, bytes_before) " +
                    "VALUES (?,?,?) RETURNING id", Long.class, triggeredBy, dryRun, bytesBefore);

            for (Map<String, Object> t : jdbc.queryForList(
                    "SELECT shop_id, schema_name FROM public.tenants ORDER BY shop_id")) {
                String shopId = (String) t.get("shop_id");
                String schema = (String) t.get("schema_name");
                if (schema == null || !schema.matches("[a-z0-9_]+")) {
                    log.warn("prune skipping {} — unusable schema name {}", shopId, schema);
                    continue;
                }
                shops++;
                long e = 0, n = 0, r = 0;
                try {
                    if (eventDays > 0)
                        e = prune(schema, "events", "received_at", eventDays, dryRun, deadline);
                    if (notifDays > 0)
                        n = prune(schema, "notifications", "created_at", notifDays, dryRun, deadline);
                    r = dropCollapsedRepledge(schema, dryRun);
                } catch (Exception ex) {
                    // One shop's problem is not every shop's problem.
                    log.warn("prune failed for {}: {}", shopId, ex.toString());
                }
                events += e;
                notifs += n;
                repledge += r;
                if (r > 0)
                    touched.add(shopId + " (" + r + " collapsed repledge rows)");
                if (e > 0 || n > 0) {
                    touched.add(shopId + " (" + e + " events, " + n + " notifications)");
                    if (vacuum && !dryRun) {
                        vacuum(schema, "events");
                        vacuum(schema, "notifications");
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    log.warn("prune ran out of its {} minute budget — the rest waits for tomorrow",
                             BUDGET_MS / 60_000);
                    break;
                }
            }

            long bytesAfter = totalBytes();
            String note = (dryRun ? "DRY RUN. " : "") +
                    "events older than " + eventDays + " days, notifications older than " + notifDays +
                    (repledge > 0 ? ", plus " + repledge + " collapsed repledge rows" : "") +
                    (touched.isEmpty() ? ". Nothing needed pruning." : ". " + String.join("; ", touched));
            jdbc.update("UPDATE public.prune_runs SET finished_at = now(), events_deleted = ?, " +
                        "notifications_deleted = ?, bytes_after = ?, shops = ?, note = ? WHERE id = ?",
                        events, notifs, bytesAfter, shops, note, runId);
            log.info("prune {}: {} events, {} notifications across {} shops ({} -> {})",
                     dryRun ? "dry run" : "done", events, notifs, shops,
                     human(bytesBefore), human(bytesAfter));

            return Map.of("run_id", runId, "dry_run", dryRun,
                          "events_deleted", events, "notifications_deleted", notifs,
                          "shops", shops, "bytes_before", bytesBefore, "bytes_after", bytesAfter,
                          "note", note);
        } finally {
            // A run that threw still gets an end time, or it reads as still running.
            if (runId > 0)
                jdbc.update("UPDATE public.prune_runs SET finished_at = COALESCE(finished_at, now()) WHERE id = ?", runId);
        }
    }

    /**
     * Clear repledge rows left behind by the old capture key.
     *
     * Before the agent read a table's real primary key, repledge_billing was
     * keyed company_id|repledge_bill_number|bill_number — and that table has
     * no bill_number column, so every leg of one repledge bill shared the key
     * and the cloud, which upserts on (table_name, row_pk), kept only the
     * last. The desktop showed 4 repledges and the phone showed 1.
     *
     * Once a shop's setup adds the primary key it re-sends every repledge
     * under its own key, and the good rows land beside the collapsed ones —
     * which would then be counted twice. This drops the old ones.
     *
     * THE SAFETY RULE: only when that shop already has correctly-keyed rows.
     * A shop still running the old agent has nothing but collapsed rows, and
     * deleting those would take its repledges off the phone altogether. One
     * wrong row beats none, until its agent is updated.
     *
     * A correct key is a bare repledge_bill_id, so it never contains '|'.
     */
    private long dropCollapsedRepledge(String schema, boolean dryRun) {
        Long good = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".projections " +
                " WHERE table_name = 'repledge_billing' AND row_pk NOT LIKE '%|%'", Long.class);
        if (good == null || good == 0) return 0;

        Long stale = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".projections " +
                " WHERE table_name = 'repledge_billing' AND row_pk LIKE '%|%'", Long.class);
        if (stale == null || stale == 0) return 0;

        if (dryRun) {
            log.info("prune dry run: {} would drop {} collapsed repledge rows ({} good ones remain)",
                     schema, stale, good);
            return stale;
        }
        int deleted = jdbc.update(
                "DELETE FROM " + schema + ".projections " +
                " WHERE table_name = 'repledge_billing' AND row_pk LIKE '%|%'");
        log.info("prune: {} dropped {} collapsed repledge rows, {} good ones remain",
                 schema, deleted, good);
        return deleted;
    }

    /**
     * Delete in batches until the window is clear, the budget runs out, or a
     * batch comes back empty. ctid keeps each statement to rows Postgres has
     * already found, which is what makes a batch finish quickly on a table
     * with hundreds of thousands of rows.
     */
    private long prune(String schema, String table, String dateColumn, int days,
                       boolean dryRun, long deadline) {
        String qualified = "\"" + schema + "\"." + table;
        if (!exists(schema, table)) return 0;

        if (dryRun) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM " + qualified + " WHERE " + dateColumn +
                    " < now() - make_interval(days => ?)", Long.class, days);
            return n == null ? 0 : n;
        }

        int checkpointEvery = Math.max(1, intSetting("prune.checkpoint.every.batches", 5));
        long total = 0;
        int batches = 0;
        while (System.currentTimeMillis() < deadline) {
            int deleted = jdbc.update(
                    "DELETE FROM " + qualified + " WHERE ctid IN (" +
                    "  SELECT ctid FROM " + qualified +
                    "   WHERE " + dateColumn + " < now() - make_interval(days => ?) LIMIT " + BATCH + ")",
                    days);
            total += deleted;
            batches++;
            // A checkpoint is what lets Postgres recycle the write-ahead log
            // instead of piling it up on the disk. Without this, a long run of
            // deletes grows pg_wal until the next timed checkpoint — which is
            // exactly how this job filled a volume once.
            if (batches % checkpointEvery == 0) checkpoint();
            if (deleted < BATCH) break;
        }
        if (batches > 0) checkpoint();
        return total;
    }

    private void checkpoint() {
        try {
            jdbc.execute("CHECKPOINT");
        } catch (Exception e) {
            // Needs a superuser. Where it is not allowed, say so once and carry
            // on: the deletes are still correct, they just lean on Postgres's
            // own timed checkpoints.
            log.warn("could not CHECKPOINT between batches ({}). Watch the volume.", e.getMessage());
        }
    }

    /**
     * @return why it is too tight to delete anything right now, or null when
     *         there is room (or when nobody has said how big the volume is).
     */
    private String spaceWarning() {
        int volumeGb = intSetting("prune.volume.gb", 0);
        if (volumeGb <= 0) return null;
        long used = 0;
        try {
            Long db = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
            Long wal = jdbc.queryForObject("SELECT COALESCE(sum(size), 0) FROM pg_ls_waldir()", Long.class);
            used = (db == null ? 0 : db) + (wal == null ? 0 : wal);
        } catch (Exception e) {
            log.warn("could not measure how full the volume is: {}", e.toString());
            return null;
        }
        long volume = volumeGb * 1024L * 1024 * 1024;
        // Deleting needs room to write the log first. Below this much free
        // space, the safe move is to make the volume bigger, not to delete.
        if (used > volume * 85 / 100) {
            return "the database and its log are " + human(used) + " of a " + volumeGb +
                   " GB volume. Deleting writes to the log before it frees anything, so this " +
                   "would risk filling the disk. Make the volume bigger first, then run it again.";
        }
        return null;
    }

    private void vacuum(String schema, String table) {
        if (!exists(schema, table)) return;
        try {
            // Plain VACUUM: no exclusive lock, no need for spare disk. It makes
            // the freed room reusable; only VACUUM FULL would shrink the file,
            // and that is a deliberate, manual job.
            jdbc.execute("VACUUM (ANALYZE) \"" + schema + "\"." + table);
        } catch (Exception e) {
            log.warn("vacuum of {}.{} failed: {}", schema, table, e.toString());
        }
    }

    private boolean exists(String schema, String table) {
        Boolean b = jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, schema + "." + table);
        return Boolean.TRUE.equals(b);
    }

    private long totalBytes() {
        Long b = jdbc.queryForObject(
                "SELECT COALESCE(sum(pg_total_relation_size(c.oid)), 0) FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog','information_schema')",
                Long.class);
        return b == null ? 0 : b;
    }

    // ── settings ──────────────────────────────────────────────────────────────

    public String setting(String key, String fallback) {
        List<String> v = jdbc.queryForList(
                "SELECT value FROM public.admin_settings WHERE key = ?", String.class, key);
        return v.isEmpty() ? fallback : v.get(0);
    }

    public int intSetting(String key, int fallback) {
        try { return Integer.parseInt(setting(key, String.valueOf(fallback)).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    public void putSetting(String key, String value, String who) {
        jdbc.update("INSERT INTO public.admin_settings(key, value, updated_by) VALUES (?,?,?) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, " +
                    "updated_at = now(), updated_by = EXCLUDED.updated_by", key, value, who);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double n = bytes;
        int i = -1;
        do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
        return String.format("%.1f %s", n, u[i]);
    }
}
