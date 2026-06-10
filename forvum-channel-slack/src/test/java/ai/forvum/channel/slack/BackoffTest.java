package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The pure exponential-backoff schedule behind the Socket Mode reconnect loop: doubling from the initial
 * delay up to the cap, and {@code reset()} returning to the start (called on a successful hello).
 * Deterministic — no clock, no sleeping. (Mirrors the Discord channel's {@code BackoffTest}.)
 */
class BackoffTest {

    @Test
    void doublesEachCallUpToTheCap() {
        Backoff backoff = new Backoff(1_000, 8_000);

        assertEquals(1_000, backoff.nextDelayMillis());
        assertEquals(2_000, backoff.nextDelayMillis());
        assertEquals(4_000, backoff.nextDelayMillis());
        assertEquals(8_000, backoff.nextDelayMillis());
        assertEquals(8_000, backoff.nextDelayMillis(), "the delay stays at the cap once reached");
    }

    @Test
    void resetReturnsToTheInitialDelay() {
        Backoff backoff = new Backoff(1_000, 8_000);
        backoff.nextDelayMillis();
        backoff.nextDelayMillis();

        backoff.reset();

        assertEquals(1_000, backoff.nextDelayMillis(), "reset restarts the schedule at the initial delay");
    }

    @Test
    void defaultsAreOneSecondToOneMinute() {
        Backoff backoff = new Backoff();

        assertEquals(Backoff.DEFAULT_INITIAL_MILLIS, backoff.nextDelayMillis());
        assertEquals(1_000, Backoff.DEFAULT_INITIAL_MILLIS);
        assertEquals(60_000, Backoff.DEFAULT_MAX_MILLIS);
    }

    @Test
    void rejectsANonPositiveInitialOrAnInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(-1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(2_000, 1_000));
    }
}
