package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.websockets.next.WebSocketClientConnection;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void connectTargetIsTheBaseGatewayUrlWithoutAResumableSession() {
        RecordingChannel channel = runningChannel(new RecordingSleeper());
        channel.gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
        channel.state = new GatewayState(); // nothing captured — fresh IDENTIFY on the base URL

        assertEquals("wss://gateway.discord.gg/?v=10&encoding=json", channel.connectTarget());
    }

    @Test
    void connectTargetIsTheResumeUrlWithGatewayParamsWhenTheSessionIsResumable() {
        RecordingChannel channel = runningChannel(new RecordingSleeper());
        channel.gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://gateway-us-east1-b.discord.gg"); // Discord sends the URL bare
        state.setLastSequence(42);
        channel.state = state;

        assertEquals("wss://gateway-us-east1-b.discord.gg/?v=10&encoding=json",
                channel.connectTarget(),
                "a resumable session dials the resume URL with the same gateway parameters");
    }

    @Test
    void connectTargetFallsBackToTheBaseUrlAfterTheStateResets() {
        RecordingChannel channel = runningChannel(new RecordingSleeper());
        channel.gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://gateway-us-east1-b.discord.gg");
        state.setLastSequence(42);
        state.reset(); // op 9 d=false — the resume context is gone
        channel.state = state;

        assertEquals("wss://gateway.discord.gg/?v=10&encoding=json", channel.connectTarget(),
                "after a non-resumable INVALID_SESSION the reconnect re-IDENTIFYs on the base URL");
    }

    @Test
    void withGatewayParamsLeavesAUrlAlreadyCarryingAQueryUntouched() {
        assertEquals("wss://x.example/?v=10&encoding=json",
                DiscordChannel.withGatewayParams("wss://x.example"));
        assertEquals("wss://x.example/?v=9",
                DiscordChannel.withGatewayParams("wss://x.example/?v=9"));
    }

    /** A channel whose dial always fails and whose reconnect is counted instead of scheduled. */
    @Vetoed
    private static final class FailingDialChannel extends DiscordChannel {
        final List<String> dialed = new CopyOnWriteArrayList<>();
        int reconnects;

        @Override
        WebSocketClientConnection dial(String target) {
            dialed.add(target);
            throw new IllegalStateException("connection refused");
        }

        @Override
        void scheduleReconnect() {
            reconnects++;
        }
    }

    private static final String BASE_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    private static GatewayState resumableState() {
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://gateway-us-east1-b.discord.gg"); // gateway hosts rotate
        state.setLastSequence(42);
        return state;
    }

    @Test
    void aFailedDialOfTheResumeUrlFallsBackToTheBaseGatewayUrl() {
        // A READY-captured resume host can be retired/unreachable (DNS failure, connection refused).
        // Without resetting the resume context on the failed dial, every backoff retry re-dials the
        // SAME dead host at the 60 s cap forever while the base gateway URL would work.
        FailingDialChannel channel = new FailingDialChannel();
        channel.gatewayUrl = BASE_URL;
        channel.state = resumableState();
        channel.running.set(true);

        channel.connect();

        assertEquals("wss://gateway-us-east1-b.discord.gg/?v=10&encoding=json", channel.dialed.get(0),
                "the first attempt dials the resume target");
        assertEquals(1, channel.reconnects, "the failed dial still schedules a backoff reconnect");
        assertFalse(channel.state.canResume(),
                "a failed dial of the resume host must drop the resume context");
        assertEquals(BASE_URL, channel.connectTarget(),
                "the NEXT attempt must fall back to the base gateway URL (fresh IDENTIFY)");

        channel.connect();

        assertEquals(BASE_URL, channel.dialed.get(1), "the second attempt actually dials the base URL");
    }

    @Test
    void aFailedDialOfTheBaseUrlJustRetriesWithBackoff() {
        FailingDialChannel channel = new FailingDialChannel();
        channel.gatewayUrl = BASE_URL;
        channel.state = new GatewayState(); // nothing to reset
        channel.running.set(true);

        channel.connect();

        assertEquals(List.of(BASE_URL), channel.dialed);
        assertEquals(1, channel.reconnects);
        assertEquals(BASE_URL, channel.connectTarget(), "the base URL stays the target");
    }

    @ParameterizedTest
    @ValueSource(ints = {4007, 4009})
    void aNonResumableCloseCodeResetsTheResumeContextAndStillReconnects(int closeCode) {
        // 4007 (invalid seq) / 4009 (session timed out): Discord documents both as "reconnect and
        // start a NEW session" — RESUMEing the same session/seq would just be closed again (4007 is
        // the documented answer to an invalid resume seq, so a resume loop is the failure mode).
        RecordingSleeper sleeper = new RecordingSleeper();
        RecordingChannel channel = runningChannel(sleeper);
        channel.gatewayUrl = BASE_URL;
        channel.state = resumableState();

        channel.onConnectionClosed(closeCode);

        assertEquals(1, channel.connectCount, "4007/4009 are not fatal — the channel reconnects");
        assertTrue(channel.running.get(), "the channel keeps running");
        assertFalse(channel.state.canResume(), "the dead session's resume context is dropped");
        assertEquals(BASE_URL, channel.connectTarget(),
                "the reconnect must IDENTIFY fresh on the base URL, not re-send the invalid session");
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
