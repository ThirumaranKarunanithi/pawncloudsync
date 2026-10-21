package com.magizhchi.sync;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the agent will say about itself on /health.
 *
 * <p>{@code lastError} used to be written in one place only: the
 * drainer's catch block. A 4xx from the cloud is not an exception, it is
 * a return value, so an agent whose key had been revoked refused every
 * batch with 401 and still answered
 * {@code {"status":"ok","last_error":null}}. Annanagar ran like that for
 * four days and looked healthy the whole time.
 *
 * <p>Nothing here is assigned by hand any more. Every outcome goes
 * through {@link #recordSuccess} or {@link #recordFailure}, so a failure
 * cannot be forgotten and — just as important — a stale error cannot
 * outlive the fix.
 */
public class AgentState {

    public final AtomicLong lagEvents = new AtomicLong();
    public final AtomicLong sentTotal = new AtomicLong();
    public final AtomicLong dlqTotal  = new AtomicLong();
    public final AtomicInteger lastBatchSize = new AtomicInteger();
    public volatile Instant lastSentAt;

    /** Null only while nothing has failed since the last batch got through. */
    public volatile String lastError;
    /** When {@link #lastError} was set, so a stale one is obvious. */
    public volatile Instant lastErrorAt;
    /** Batches refused in a row. Back to zero the moment one lands. */
    public final AtomicInteger consecutiveFailures = new AtomicInteger();

    public void recordSuccess(int batchSize) {
        sentTotal.addAndGet(batchSize);
        lastBatchSize.set(batchSize);
        lastSentAt = Instant.now();
        consecutiveFailures.set(0);
        lastError = null;
        lastErrorAt = null;
    }

    public void recordFailure(String why) {
        lastError = why;
        lastErrorAt = Instant.now();
        consecutiveFailures.incrementAndGet();
    }

    // ── Backups ──────────────────────────────────────────────────────────────
    //
    // Kept apart from the event-sync fields above because the two fail
    // independently and for different reasons, and reading one as the other is
    // how this went unnoticed for so long: event sync was perfectly healthy —
    // "status": "ok" on every check — while the nightly database backup had
    // not reached the cloud for months. Nothing on this page said otherwise.

    /** When a backup last reached the cloud. */
    public volatile Instant lastBackupAt;
    /** Why the last backup attempt failed, in the cloud's own words. */
    public volatile String lastBackupError;
    public volatile Instant lastBackupErrorAt;
    /** Set when the refusal was "no room", which no amount of retrying fixes. */
    public volatile boolean backupBlockedByStorage;

    /**
     * A backup folder the agent was told to watch and cannot read, named in full.
     *
     * <p>This was a {@code log.debug} line, which is to say it was nothing: a shop
     * whose folder had moved, or which sits on a mapped drive letter the service
     * cannot see, looked exactly like a shop with nothing new to send. A whole day
     * went into one of those. Null when every folder reads.
     */
    public volatile String backupFolderUnreadable;

    /**
     * Whether the last backup we sent went up compressed, and what it weighed.
     *
     * <p>Worth its own field because an uncompressed dump of a few hundred MB is
     * refused by the proxy in front of the cloud with a 502, and the refusal says
     * nothing about size — so "was it gzipped?" is the first question to ask and
     * there was no way to answer it short of reading the agent's log on the shop PC.
     */
    public volatile String lastBackupSize;

    /** The settings the backup scan is actually running with, for the report to show. */
    public volatile int     backupRetentionDays = -1;
    public volatile boolean backupGzipOn;

    public void recordBackupSuccess() {
        lastBackupAt = Instant.now();
        lastBackupError = null;
        lastBackupErrorAt = null;
        backupBlockedByStorage = false;
    }

    public void recordBackupFailure(String why, boolean noRoom) {
        lastBackupError = why;
        lastBackupErrorAt = Instant.now();
        backupBlockedByStorage = noRoom;
    }

    /** What the last upload weighed, and whether gzip was applied on the way. */
    public void recordBackupSize(String fileName, long rawBytes, long sentBytes, boolean gzipped) {
        lastBackupSize = fileName + ": " + (rawBytes >> 20) + " MB"
                       + (gzipped ? " -> " + (sentBytes >> 20) + " MB gzipped"
                                  : " sent UNCOMPRESSED");
    }

    /**
     * One word for whoever is looking: ok, degraded, or failing.
     *
     * <p>A single refusal is normal — the cloud restarts, the line
     * drops. Three in a row is not: at 25 events a batch nothing is
     * moving, and somebody should look at last_error.
     */
    public String health() {
        int fails = consecutiveFailures.get();
        if (fails == 0) return "ok";
        return fails < 3 ? "degraded" : "failing";
    }
}
