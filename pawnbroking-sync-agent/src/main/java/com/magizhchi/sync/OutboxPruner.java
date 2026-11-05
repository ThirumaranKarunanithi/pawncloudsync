package com.magizhchi.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Clears events the cloud already has, once they are a month old.
 *
 * <p>The drainer only stamps {@code sent_at} on an event the cloud accepted; nothing
 * ever took it away. So {@code sync_outbox} kept every change the shop had made since
 * the agent arrived, each with its whole row as JSON, plus the one-time history send
 * of every bill. It became most of the database - and the day's backup is a pg_dump
 * of the whole database, so it carried all of it every night. Alwarpuram's dump
 * reached 730 MB, about 100 MB gzipped, which the shop's line cannot upload in the
 * five minutes the proxy in front of the cloud allows: from 11-09-2026 not one
 * backup reached the cloud, while its day reports, being small, kept arriving.
 *
 * <p>Only SENT rows go, and only those sent more than {@code outbox.keep.sent.days}
 * ago (30 by default; 0 switches this off). An unsent row is the only copy of that
 * change on its way to the cloud and is never touched here - the queue was emptied
 * by hand at Aiyanarpuram on 01-11-2026 and nine days had to be rebuilt from the
 * shop's tables. A month of sent rows is kept because it is what answers "did this
 * change reach the cloud?", and what a re-queue repair compares against.
 *
 * <p>The "history already sent" mark is the table's comment, not its rows, so a later
 * Full setup never sends the history again because of this.
 *
 * <p>Runs a few minutes after the agent starts, then once a day, in batches of
 * {@value #BATCH} so no transaction is long. It cannot collide with the drainer: the
 * drainer only touches unsent rows and stamps them now, never a month ago. The space
 * is reused by PostgreSQL for new rows; nothing needs compacting for the backup to
 * shrink, because pg_dump writes out live rows only.
 */
final class OutboxPruner implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(OutboxPruner.class);
    static final int BATCH = 50_000;
    private static final long FIRST_RUN_AFTER_MS = 5 * 60_000L;
    private static final long EVERY_MS = 24 * 60 * 60_000L;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final DataSource ds;
    private final int keepDays;
    private volatile boolean running = true;

    OutboxPruner(DataSource ds, int keepDays) {
        this.ds = ds;
        this.keepDays = keepDays;
    }

    @Override
    public void run() {
        if (keepDays <= 0) {
            Agent.STATE.get().outboxCleared = "off (outbox.keep.sent.days = " + keepDays + ")";
            log.info("outbox clean-up is off (outbox.keep.sent.days={})", keepDays);
            return;
        }
        if (!pause(FIRST_RUN_AFTER_MS)) return;
        while (running) {
            try {
                clearOnce();
            } catch (Exception e) {
                // Next time, then. Nothing here is urgent, and a failure leaves every row as it was.
                Agent.STATE.get().outboxCleared = LocalDateTime.now().format(WHEN)
                        + ": could not clear old sent events - " + e.getMessage();
                log.warn("outbox clean-up failed: {}", e.toString());
            }
            if (!pause(EVERY_MS)) return;
        }
    }

    /** One pass. Returns how many sent events were removed. */
    long clearOnce() throws Exception {
        long t0 = System.currentTimeMillis();
        long removed = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement del = c.prepareStatement(
                 // ctid picks out exactly the rows the inner query found, without an
                 // index on sent_at (there is only one on the unsent rows).
                 "DELETE FROM sync_outbox WHERE ctid = ANY (ARRAY(" +
                 "  SELECT ctid FROM sync_outbox" +
                 "   WHERE sent_at IS NOT NULL" +
                 "     AND sent_at < now() - make_interval(days => ?)" +
                 "   LIMIT " + BATCH + "))")) {
            c.setAutoCommit(true);
            del.setInt(1, keepDays);
            while (running) {
                int n = del.executeUpdate();
                removed += n;
                if (n < BATCH) break;
                Thread.sleep(200);   // leave the database to the shop between batches
            }
        }
        long s = (System.currentTimeMillis() - t0) / 1000;
        String when = LocalDateTime.now().format(WHEN);
        Agent.STATE.get().outboxCleared = removed == 0
                ? when + ": nothing sent more than " + keepDays + " days ago"
                : String.format("%s: cleared %,d events sent more than %d days ago", when, removed, keepDays);
        if (removed > 0) {
            log.info("outbox clean-up: removed {} events sent more than {} days ago in {}s", removed, keepDays, s);
        } else {
            log.info("outbox clean-up: nothing sent more than {} days ago", keepDays);
        }
        return removed;
    }

    void stop() { running = false; }

    private boolean pause(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (running) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return true;
            try {
                Thread.sleep(Math.min(left, 1_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
