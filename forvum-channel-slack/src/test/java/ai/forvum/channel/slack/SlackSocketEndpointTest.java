package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.slack.SlackChannelConfig.Spec;
import ai.forvum.channel.slack.dto.MessageEvent;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSession;

/**
 * Endpoint-level pins for {@link SlackSocketEndpoint}'s frame handling, driven through
 * {@code onText} with a recording fake {@link WebSocketClientConnection} (no live {@code wss://}):
 *
 * <ul>
 *   <li><strong>ack-before-turn + non-blocking handler.</strong> Inbound frames are SERIAL
 *       (websockets-next's {@code @WebSocketClient} default), so the Dispatch arm must ack
 *       synchronously and run the turn on its OWN virtual thread — a turn blocking the handler would
 *       make every queued envelope miss Slack's ~3 s ack deadline and be redelivered as a duplicate.</li>
 *   <li><strong>disconnect → close.</strong> Honoring a {@code disconnect} frame closes the socket so
 *       {@code @OnClose} hands off to the channel's reconnect.</li>
 *   <li><strong>failure isolation.</strong> A processor failure is swallowed (never closes the socket —
 *       the M16 lesson) and a failed ack still runs the turn.</li>
 * </ul>
 *
 * The endpoint class is excluded from the JaCoCo gate (the {@code @OnOpen}/{@code @OnClose} framework
 * arcs stay live-path), but these plain unit tests pin the act() ordering/offload semantics mutations
 * would otherwise leave green.
 */
class SlackSocketEndpointTest {

    private static final String EVENTS_API_FRAME =
            "{\"envelope_id\":\"e-1\",\"type\":\"events_api\",\"retry_attempt\":0,\"payload\":{"
                    + "\"event\":{\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U42\","
                    + "\"text\":\"hello\",\"ts\":\"1717.0001\"}}}";

    /** Records the ack sends and closes; the shared {@code events} list pins cross-object ordering. */
    static final class RecordingClientConnection implements WebSocketClientConnection {
        final List<String> events;
        final CopyOnWriteArrayList<String> sentFrames = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<CloseReason> closes = new CopyOnWriteArrayList<>();
        volatile RuntimeException sendFailure;

        RecordingClientConnection(List<String> events) {
            this.events = events;
        }

        @Override
        public void sendTextAndAwait(String message) {
            events.add("ack");
            sentFrames.add(message);
            if (sendFailure != null) {
                throw sendFailure;
            }
        }

        @Override
        public void closeAndAwait() {
            closes.add(CloseReason.NORMAL);
        }

        @Override
        public void closeAndAwait(CloseReason reason) {
            closes.add(reason);
        }

        @Override
        public UserData userData() {
            return new UserData() {
                @SuppressWarnings("unchecked")
                @Override
                public <V> V get(TypedKey<V> key) {
                    return (V) "test-bot-token";
                }

                @Override
                public <V> V put(TypedKey<V> key, V value) {
                    return null;
                }

                @Override
                public <V> V remove(TypedKey<V> key) {
                    return null;
                }

                @Override
                public int size() {
                    return 0;
                }

                @Override
                public void clear() {
                }
            };
        }

        // --- surface the endpoint never touches ----------------------------------------------------
        @Override public String id() { return "test-connection"; }
        @Override public String clientId() { return "test-client"; }
        @Override public String pathParam(String name) { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SSLSession sslSession() { return null; }
        @Override public boolean isClosed() { return false; }
        @Override public CloseReason closeReason() { return null; }
        @Override public HandshakeRequest handshakeRequest() { return null; }
        @Override public String subprotocol() { return null; }
        @Override public Instant creationTime() { return Instant.now(); }
        @Override public Uni<Void> close(CloseReason reason) { throw new UnsupportedOperationException(); }
        @Override public Uni<Void> sendText(String message) { throw new UnsupportedOperationException(); }
        @Override public <M> Uni<Void> sendText(M message) { throw new UnsupportedOperationException(); }
        @Override public Uni<Void> sendBinary(Buffer message) { throw new UnsupportedOperationException(); }
        @Override public Uni<Void> sendPing(Buffer data) { throw new UnsupportedOperationException(); }
        @Override public Uni<Void> sendPong(Buffer data) { throw new UnsupportedOperationException(); }
    }

    /**
     * Records {@code process} calls into the shared ordering list and blocks until released, so the
     * test can assert the handler returned while the turn was still in flight. {@code @Vetoed}: the
     * module's {@code @QuarkusTest} must not see a second {@code SlackMessageProcessor} bean.
     */
    @Vetoed
    static final class RecordingProcessor extends SlackMessageProcessor {
        final List<String> events;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile MessageEvent processed;
        volatile RuntimeException failure;

        RecordingProcessor(List<String> events) {
            this.events = events;
        }

        @Override
        public void process(MessageEvent event, Spec spec, SlackRestClient rest, String authorization) {
            events.add("process");
            processed = event;
            started.countDown();
            if (failure != null) {
                throw failure;
            }
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A channel whose callbacks are no-ops (the lifecycle policy has its own tests). */
    @Vetoed
    static final class NoopChannel extends SlackChannel {
        @Override
        void onConnected() {
        }

        @Override
        void onConnectionClosed(int closeCode) {
        }
    }

    private static SlackSocketEndpoint endpoint(RecordingProcessor processor, Path home) {
        SlackSocketEndpoint endpoint = new SlackSocketEndpoint();
        endpoint.processor = processor;
        // An absent channels/slack.json reads as Spec.empty() — the recording processor ignores it.
        endpoint.config = new SlackChannelConfig(home.resolve("channels").resolve("slack.json"));
        endpoint.channel = new NoopChannel();
        endpoint.rest = new RecordingSlackRestClient();
        return endpoint;
    }

    @Test
    void theDispatchArmAcksSynchronouslyThenRunsTheTurnOffTheFrameHandler(@TempDir Path home)
            throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingProcessor processor = new RecordingProcessor(events);
        SlackSocketEndpoint endpoint = endpoint(processor, home);
        RecordingClientConnection connection = new RecordingClientConnection(events);

        try {
            // Inbound frames are SERIAL: while a handler runs, the NEXT frame (another delivery, a
            // disconnect) is queued. onText must therefore return long before the turn completes; a
            // synchronous multi-second turn here would breach the ~3 s ack deadline for every queued
            // envelope, and Slack would redeliver them as duplicate turns.
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> endpoint.onText(connection, EVENTS_API_FRAME),
                    "onText must not block on the turn (it runs on its own virtual thread)");

            assertEquals(1, connection.sentFrames.size(), "the ack is sent synchronously in onText");
            assertTrue(connection.sentFrames.get(0).contains("e-1"), "the ack echoes the envelope id");
            assertEquals(1, processor.release.getCount(),
                    "the handler returned while the turn was still in flight");

            assertTrue(processor.started.await(5, TimeUnit.SECONDS), "the offloaded turn must start");
            assertEquals("ack", events.get(0), "the ack happens BEFORE the turn starts");
            assertEquals(List.of("ack", "process"), List.copyOf(events));
            assertEquals("hello", processor.processed.text(), "the decoded event reaches the processor");
        } finally {
            processor.release.countDown();
        }
    }

    @Test
    void aFailedAckStillRunsTheTurn(@TempDir Path home) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingProcessor processor = new RecordingProcessor(events);
        processor.release.countDown(); // non-blocking turn
        SlackSocketEndpoint endpoint = endpoint(processor, home);
        RecordingClientConnection connection = new RecordingClientConnection(events);
        connection.sendFailure = new IllegalStateException("socket flaked mid-ack");

        assertDoesNotThrow(() -> endpoint.onText(connection, EVENTS_API_FRAME),
                "an ack failure is logged, never propagated");

        assertTrue(processor.started.await(5, TimeUnit.SECONDS),
                "a failed ack means Slack will redeliver, but the turn already in hand still runs");
    }

    @Test
    void aDisconnectFrameClosesTheConnection(@TempDir Path home) {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingProcessor processor = new RecordingProcessor(events);
        SlackSocketEndpoint endpoint = endpoint(processor, home);
        RecordingClientConnection connection = new RecordingClientConnection(events);

        endpoint.onText(connection,
                "{\"type\":\"disconnect\",\"reason\":\"refresh_requested\","
                        + "\"debug_info\":{\"host\":\"applink-1\"}}");

        assertEquals(1, connection.closes.size(),
                "honoring a disconnect frame closes the socket; @OnClose then hands off to the channel");
        assertTrue(connection.sentFrames.isEmpty(), "a disconnect frame is not acknowledged");
        assertTrue(events.stream().noneMatch("process"::equals), "a disconnect frame drives no turn");
    }

    @Test
    void aProcessorFailureIsSwallowedAndNeverClosesTheSocket(@TempDir Path home) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingProcessor processor = new RecordingProcessor(events);
        processor.failure = new IllegalStateException("turn blew up (config read mid-edit)");
        SlackSocketEndpoint endpoint = endpoint(processor, home);
        RecordingClientConnection connection = new RecordingClientConnection(events);

        assertDoesNotThrow(() -> endpoint.onText(connection, EVENTS_API_FRAME));

        assertTrue(processor.started.await(5, TimeUnit.SECONDS));
        assertEquals(1, connection.sentFrames.size(), "the envelope was still acked");
        assertTrue(connection.closes.isEmpty(),
                "a failed turn must never close the socket (websockets-next would otherwise kill it"
                        + " on an uncaught handler exception — the M16 lesson)");
    }
}
