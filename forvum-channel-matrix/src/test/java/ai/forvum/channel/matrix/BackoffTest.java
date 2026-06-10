package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The pure exponential-backoff schedule for the sync-loop retry: doubles from the initial delay to the
 * cap, repeats the cap, and {@code reset()} restarts the schedule (mirrors the Discord channel's
 * {@code Backoff}). No clocks, no sleeping — deterministic POJO tests.
 */
class BackoffTest {

    @Test
    void doublesFromInitialToTheCapThenRepeatsTheCap() {
        Backoff backoff = new Backoff(1_000, 8_000);

        assertEquals(1_000, backoff.nextDelayMillis());
        assertEquals(2_000, backoff.nextDelayMillis());
        assertEquals(4_000, backoff.nextDelayMillis());
        assertEquals(8_000, backoff.nextDelayMillis());
        assertEquals(8_000, backoff.nextDelayMillis(), "the cap repeats");
    }

    @Test
    void resetReturnsToTheInitialDelay() {
        Backoff backoff = new Backoff(1_000, 8_000);
        backoff.nextDelayMillis();
        backoff.nextDelayMillis();

        backoff.reset();

        assertEquals(1_000, backoff.nextDelayMillis(), "a successful sync restarts the schedule");
    }

    @Test
    void defaultsAreOneSecondToOneMinute() {
        Backoff backoff = new Backoff();

        assertEquals(Backoff.DEFAULT_INITIAL_MILLIS, backoff.nextDelayMillis());
    }

    @Test
    void rejectsAnInvalidSchedule() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(2_000, 1_000));
    }
}
