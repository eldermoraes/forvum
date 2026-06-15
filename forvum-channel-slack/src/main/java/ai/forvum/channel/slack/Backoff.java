package ai.forvum.channel.slack;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A pure, lock-free exponential-backoff counter for the Slack Socket Mode reconnect loop: each
 * {@link #nextDelayMillis()} doubles the delay (starting at {@code initialMillis}) up to a {@code maxMillis}
 * cap, and {@link #reset()} returns to the initial delay (called on a successful hello so a healthy socket
 * restarts the schedule). No clock, no IO, no {@code synchronized} — backed by a single {@link AtomicLong}
 * so the inbound-frame virtual thread (hello → reset) and the reconnect virtual thread (next delay) can
 * touch it without a lock (CLAUDE.md §3.8). Computation is deterministic, so the schedule is unit-testable
 * directly without sleeping. (Intentionally identical to the Discord channel's {@code Backoff} — module
 * isolation forbids a shared type.)
 */
final class Backoff {

    static final long DEFAULT_INITIAL_MILLIS = 1_000L;
    static final long DEFAULT_MAX_MILLIS = 60_000L;

    private final long initialMillis;
    private final long maxMillis;
    /** The delay the NEXT call will return; reset to {@code initialMillis}, doubled per call up to the cap. */
    private final AtomicLong nextMillis;

    Backoff(long initialMillis, long maxMillis) {
        if (initialMillis <= 0 || maxMillis < initialMillis) {
            throw new IllegalArgumentException(
                    "Require 0 < initialMillis <= maxMillis; got " + initialMillis + ", " + maxMillis + ".");
        }
        this.initialMillis = initialMillis;
        this.maxMillis = maxMillis;
        this.nextMillis = new AtomicLong(initialMillis);
    }

    Backoff() {
        this(DEFAULT_INITIAL_MILLIS, DEFAULT_MAX_MILLIS);
    }

    /**
     * Return the current delay, then advance the schedule (double, capped at {@code maxMillis}) for the
     * following call. Returns {@code initialMillis, 2*initialMillis, 4*initialMillis, … maxMillis,
     * maxMillis, …}.
     */
    long nextDelayMillis() {
        return nextMillis.getAndUpdate(current -> Math.min(maxMillis, current * 2));
    }

    /** Return the schedule to its initial delay (called on a successful hello). */
    void reset() {
        nextMillis.set(initialMillis);
    }
}
