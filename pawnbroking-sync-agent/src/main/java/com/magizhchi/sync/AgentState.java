package com.magizhchi.sync;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AgentState {
    public final AtomicLong lagEvents = new AtomicLong();
    public final AtomicLong sentTotal = new AtomicLong();
    public final AtomicLong dlqTotal  = new AtomicLong();
    public final AtomicInteger lastBatchSize = new AtomicInteger();
    public volatile Instant lastSentAt;
    public volatile String lastError;
}
