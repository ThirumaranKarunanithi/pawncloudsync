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
