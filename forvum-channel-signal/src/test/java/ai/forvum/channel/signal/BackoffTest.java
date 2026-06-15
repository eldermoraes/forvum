package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The pure exponential-backoff schedule ({@link Backoff}) the Signal SSE reconnect loop drives: delays
 * double from the initial value up to the cap, and {@link Backoff#reset()} (called on the first healthy
 * event) returns to the start. A plain unit test — the computation is deterministic, so the whole
 * schedule is asserted without sleeping.
 */
class BackoffTest {

    @Test
    void delaysDoubleFromInitialAndAreCappedAtMax() {
        Backoff backoff = new Backoff(1_000L, 8_000L);

        assertEquals(1_000L, backoff.nextDelayMillis(), "first delay is the initial value");
        assertEquals(2_000L, backoff.nextDelayMillis(), "then it doubles");
        assertEquals(4_000L, backoff.nextDelayMillis());
        assertEquals(8_000L, backoff.nextDelayMillis(), "then it reaches the cap");
        assertEquals(8_000L, backoff.nextDelayMillis(), "and stays capped, never exceeding max");
    }

    @Test
    void resetReturnsTheScheduleToTheInitialDelay() {
        Backoff backoff = new Backoff(1_000L, 60_000L);
        backoff.nextDelayMillis(); // 1000
        backoff.nextDelayMillis(); // 2000
        backoff.nextDelayMillis(); // 4000

        backoff.reset();

        assertEquals(1_000L, backoff.nextDelayMillis(),
                "after reset the schedule starts over (a healthy event healed the stream)");
        assertEquals(2_000L, backoff.nextDelayMillis());
    }

    @Test
    void defaultScheduleStartsAt1sAndCapsAt60s() {
        Backoff backoff = new Backoff();
        assertEquals(Backoff.DEFAULT_INITIAL_MILLIS, backoff.nextDelayMillis(), "default initial is 1s");

        long last = 0;
        for (int i = 0; i < 20; i++) {
            last = backoff.nextDelayMillis();
        }
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, last, "default cap is 60s after enough doublings");
    }

    @Test
    void rejectsAnInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0L, 60_000L));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(2_000L, 1_000L));
    }
}
