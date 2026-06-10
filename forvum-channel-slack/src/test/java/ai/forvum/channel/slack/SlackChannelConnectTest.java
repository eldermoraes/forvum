package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.slack.dto.ChatPostMessage;
import ai.forvum.channel.slack.dto.ChatPostMessageResponse;
import ai.forvum.channel.slack.dto.ConnectionsOpenResponse;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code SlackChannel.connect()}'s {@code apps.connections.open} bootstrap arms, driven POJO-style with
 * a canned {@link SlackRestClient} (no live Slack, no CDI): a fatal credential error stops the channel,
 * a transient/empty/blank-url answer schedules a backoff reconnect, a thrown REST exception is caught
 * (redacted) and retried, and a stopped channel never even calls the API. Also covers the
 * {@code onStart} happy path (both tokens present → the connect worker starts on a virtual-thread
 * executor) and {@code onStop} shutting it down — the only lifecycle arc the boot test's no-op branches
 * cannot reach.
 */
class SlackChannelConnectTest {

    /** A channel whose scheduleReconnect is counted instead of sleeping/re-dialing. {@code @Vetoed} so
     *  ArC never sees a second {@code SlackChannel} bean (the module has a {@code @QuarkusTest}). */
    @Vetoed
    private static final class CountingReconnectChannel extends SlackChannel {
        int reconnects;

        @Override
        void scheduleReconnect() {
            reconnects++;
        }
    }

    /** A canned {@link SlackRestClient} answering {@code connectionsOpen} with a fixed response. */
    private static final class CannedRestClient implements SlackRestClient {
        final ConnectionsOpenResponse response;
        final RuntimeException failure;
        final AtomicInteger calls = new AtomicInteger();

        CannedRestClient(ConnectionsOpenResponse response) {
            this.response = response;
            this.failure = null;
        }

        CannedRestClient(RuntimeException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        public ConnectionsOpenResponse connectionsOpen(String authorization) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public ChatPostMessageResponse postMessage(String authorization, ChatPostMessage body) {
            throw new UnsupportedOperationException("not part of the connect bootstrap");
        }
    }

    private static CountingReconnectChannel runningChannel(SlackRestClient rest) {
        CountingReconnectChannel channel = new CountingReconnectChannel();
        channel.rest = rest;
        channel.running.set(true);
        return channel;
    }

    @Test
    void aFatalConnectionsOpenErrorStopsTheChannel() {
        CountingReconnectChannel channel = runningChannel(
                new CannedRestClient(new ConnectionsOpenResponse(false, null, "invalid_auth")));

        channel.connect();

        assertFalse(channel.running.get(), "a bad/revoked appToken stops the channel");
        assertEquals(0, channel.reconnects, "no reconnect is scheduled on a fatal error");
    }

    @Test
    void aTransientConnectionsOpenErrorSchedulesAReconnect() {
        CountingReconnectChannel channel = runningChannel(
                new CannedRestClient(new ConnectionsOpenResponse(false, null, "ratelimited")));

        channel.connect();

        assertTrue(channel.running.get());
        assertEquals(1, channel.reconnects, "a transient API failure retries with backoff");
    }

    @Test
    void anEmptyResponseSchedulesAReconnect() {
        CountingReconnectChannel channel = runningChannel(new CannedRestClient((ConnectionsOpenResponse) null));

        channel.connect();

        assertEquals(1, channel.reconnects, "a null/empty API answer is treated as transient");
    }

    @Test
    void anOkResponseWithoutAUrlSchedulesAReconnect() {
        CountingReconnectChannel channel = runningChannel(
                new CannedRestClient(new ConnectionsOpenResponse(true, "  ", null)));

        channel.connect();

        assertEquals(1, channel.reconnects, "ok=true without a usable wss URL cannot connect — retry");
    }

    @Test
    void aThrownRestFailureIsCaughtAndRetried() {
        CountingReconnectChannel channel = runningChannel(new CannedRestClient(
                new RuntimeException("401 from Authorization: Bearer xoxb-1-secret")));

        assertDoesNotThrow(channel::connect, "a REST exception must never escape the connect worker");
        assertEquals(1, channel.reconnects);
    }

    @Test
    void aStoppedChannelNeverCallsTheApi() {
        CannedRestClient rest = new CannedRestClient(new ConnectionsOpenResponse(true, "wss://x", null));
        CountingReconnectChannel channel = runningChannel(rest);
        channel.running.set(false);

        channel.connect();

        assertEquals(0, rest.calls.get(), "a stopped channel must not mint socket URLs");
        assertEquals(0, channel.reconnects);
    }

    @Test
    void onStartWithBothTokensStartsTheConnectWorkerAndOnStopShutsItDown(@TempDir Path home)
            throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("slack.json"),
                "{ \"enabled\": true, \"botToken\": \"xoxb-1\", \"appToken\": \"xapp-1\" }");

        // connect() counted, not dialed: onStart submits it asynchronously to the VT executor.
        @Vetoed
        final class RecordingConnectChannel extends SlackChannel {
            @Override
            void connect() {
                // no-op: the worker submission, not the dial, is under test here
            }
        }
        RecordingConnectChannel channel = new RecordingConnectChannel();
        channel.config = new SlackChannelConfig(channels.resolve("slack.json"));

        channel.onStart(null);

        assertTrue(channel.started(), "both tokens present — the channel must start");
        assertNotNull(channel.connectExecutor, "the connect worker executor must be created");

        channel.onStop(null);

        assertFalse(channel.started(), "onStop stops the channel");
        assertTrue(channel.connectExecutor.isShutdown(), "onStop shuts the worker executor down");
    }
}
