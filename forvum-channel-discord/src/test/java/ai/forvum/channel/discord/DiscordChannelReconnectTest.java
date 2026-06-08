package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The Discord gateway reconnect lifecycle ({@link DiscordChannel#onConnectionClosed(int)} +
 * {@link DiscordChannel#scheduleReconnect()} + the {@link Backoff} reset on READY). A plain POJO test (no
 * Quarkus boot, no live {@code wss://}): the real {@link DiscordChannel} is subclassed so its actual
 * connect is replaced by a counter (the live connect needs a CDI connector), the reconnect runs on a
 * same-thread executor, and the {@link DiscordChannel.Sleeper} seam records the requested backoff delays
 * instead of waiting. This asserts the policy directly:
 *
 * <ul>
 *   <li>a non-shutdown close schedules a reconnect, with the backoff GROWING across successive closes;</li>
 *   <li>a deliberate shutdown ({@code running == false}) schedules NO reconnect;</li>
 *   <li>a fatal 4xxx close code STOPS the channel (no reconnect);</li>
 *   <li>a successful READY RESETS the backoff so the next close starts the schedule over.</li>
 * </ul>
 */
class DiscordChannelReconnectTest {

    /**
     * A {@link DiscordChannel} whose connect is recorded (the live connect needs a CDI connector).
     * {@code @Vetoed} so ArC never treats this test subclass as a bean — otherwise the {@code @QuarkusTest}
     * in this module sees two {@code DiscordChannel} candidates and fails the endpoint's injection.
     */
    @Vetoed
    private static final class RecordingChannel extends DiscordChannel {
        int connectCount;

        @Override
        void connect() {
            connectCount++;
        }
    }

    /** Records the backoff delays the channel asks to sleep, and returns immediately (no real wait). */
    private static final class RecordingSleeper implements DiscordChannel.Sleeper {
        final List<Long> sleeps = new CopyOnWriteArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    /** Runs submitted tasks synchronously on the calling thread so the reconnect is deterministic. */
    private static final class SameThreadExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private static RecordingChannel runningChannel(RecordingSleeper sleeper) {
        RecordingChannel channel = new RecordingChannel();
        channel.connectExecutor = new SameThreadExecutor();
        channel.sleeper = sleeper;
        channel.running.set(true);
        return channel;
    }

    @Test
    void aNonShutdownCloseReconnectsWithGrowingBackoff() {
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingChannel channel = runningChannel(sleeper);

        channel.onConnectionClosed(1000); // a routine, non-fatal close (e.g. after op 7)
        channel.onConnectionClosed(1000); // another transient close, no READY in between

        assertEquals(2, channel.connectCount, "each non-shutdown close drives a reconnect");
        assertEquals(List.of(Backoff.DEFAULT_INITIAL_MILLIS, Backoff.DEFAULT_INITIAL_MILLIS * 2),
                sleeper.sleeps, "the reconnect backoff grows across successive closes (1s, 2s)");
    }

    @Test
    void aDeliberateShutdownCloseDoesNotReconnect() {
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingChannel channel = runningChannel(sleeper);
        channel.running.set(false); // a ShutdownEvent fired before the socket closed

        channel.onConnectionClosed(1000);

        assertEquals(0, channel.connectCount, "a deliberate shutdown never reconnects");
        assertTrue(sleeper.sleeps.isEmpty(), "no backoff is scheduled on a shutdown close");
    }

    @Test
    void aFatalCloseCodeStopsTheChannelWithoutReconnecting() {
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingChannel channel = runningChannel(sleeper);

        channel.onConnectionClosed(4004); // authentication failed — non-recoverable

        assertEquals(0, channel.connectCount, "a fatal 4xxx close code does not reconnect");
        assertTrue(sleeper.sleeps.isEmpty(), "no backoff is scheduled on a fatal close");
        assertFalse(channel.running.get(), "the channel stops running after a fatal close");
    }

    @Test
    void aSuccessfulReadyResetsTheBackoffSchedule() {
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingChannel channel = runningChannel(sleeper);

        channel.onConnectionClosed(1000); // 1s
        channel.onConnectionClosed(1000); // 2s
        channel.onReady();                // a healthy session: reset the schedule
        channel.onConnectionClosed(1000); // back to 1s

        assertEquals(List.of(Backoff.DEFAULT_INITIAL_MILLIS, Backoff.DEFAULT_INITIAL_MILLIS * 2,
                        Backoff.DEFAULT_INITIAL_MILLIS),
                sleeper.sleeps, "READY resets the backoff so the next reconnect starts at 1s again");
        assertEquals(3, channel.connectCount);
    }
}
