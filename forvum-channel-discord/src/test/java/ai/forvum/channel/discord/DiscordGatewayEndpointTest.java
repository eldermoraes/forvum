package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

/**
 * Endpoint-level pins for {@link DiscordGatewayEndpoint}'s RESUME wire path, driven through
 * {@code onText} with a recording fake {@link WebSocketClientConnection} (no live {@code wss://}):
 *
 * <ul>
 *   <li><strong>Close codes carry resume intent.</strong> Discord invalidates the session when the
 *       CLIENT closes with 1000/1001, so the op-7 RECONNECT and resumable op-9 paths — whose whole
 *       point is the upcoming op-6 RESUME — must close with a non-1000 application code (4000), while
 *       a non-resumable op-9 keeps the session-invalidating 1000. Without this distinction the
 *       decide()-level RESUME tests pin intent that the transport defeats (the green-for-wrong-reason
 *       shape).</li>
 *   <li><strong>HELLO actually SENDS the op-6 frame</strong> when the state is resumable (and op-2
 *       when not) — the wire-path half the protocol tests cannot see.</li>
 *   <li><strong>RESUMED resets the channel backoff</strong>, and the {@code sendResume}
 *       empty-session fallback degrades to IDENTIFY.</li>
 * </ul>
 */
class DiscordGatewayEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HELLO_FRAME = "{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}";
    private static final String OP7_RECONNECT_FRAME = "{\"op\":7,\"d\":null}";
    private static final String OP9_RESUMABLE_FRAME = "{\"op\":9,\"d\":true}";
    private static final String OP9_NON_RESUMABLE_FRAME = "{\"op\":9,\"d\":false}";
    private static final String RESUMED_FRAME = "{\"op\":0,\"t\":\"RESUMED\",\"s\":43,\"d\":null}";

    /** Records sent frames and close reasons; the endpoint never touches the rest of the surface. */
    static final class RecordingClientConnection implements WebSocketClientConnection {
        final CopyOnWriteArrayList<String> sentFrames = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<CloseReason> closes = new CopyOnWriteArrayList<>();

        @Override
        public void sendTextAndAwait(String message) {
            sentFrames.add(message);
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
                    return (V) "test-token";
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

    /** Counts {@code onReady} callbacks. {@code @Vetoed}: no second bean for the {@code @QuarkusTest}. */
    @Vetoed
    static final class RecordingChannel extends DiscordChannel {
        final AtomicInteger readyCalls = new AtomicInteger();

        @Override
        void onReady() {
            readyCalls.incrementAndGet();
        }

        @Override
        void onConnectionClosed(int closeCode) {
        }
    }

    private final RecordingChannel channel = new RecordingChannel();
    private final DiscordGatewayEndpoint endpoint = new DiscordGatewayEndpoint();
    private final RecordingClientConnection connection = new RecordingClientConnection();

    private DiscordGatewayEndpoint endpoint(GatewayState state) {
        endpoint.state = state;
        endpoint.channel = channel;
        return endpoint;
    }

    /** A state as it stands after a healthy session: READY captured + a dispatch sequence seen. */
    private static GatewayState resumableState() {
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://resume.example");
        state.setLastSequence(42);
        return state;
    }

    @AfterEach
    void stopHeartbeat() {
        endpoint.stopHeartbeat(); // a HELLO test armed the heartbeat thread; never leak it
    }

    @Test
    void anOp7ReconnectClosesWithTheResumeIntentCodeNot1000() {
        endpoint(resumableState()).onText(connection, OP7_RECONNECT_FRAME);

        assertEquals(1, connection.closes.size(), "op 7 closes the connection");
        CloseReason reason = connection.closes.get(0);
        // Discord's Gateway docs: a CLIENT close with 1000/1001 invalidates the session — op 7 exists
        // to be RESUMEd, so closing 1000 here would defeat the resume on every routine reconnect.
        assertNotEquals(1000, reason.getCode(),
                "closing 1000 invalidates the very session Discord asked us to resume");
        assertEquals(DiscordGatewayEndpoint.RECONNECT_CLOSE_CODE, reason.getCode());
    }

    @Test
    void aResumableInvalidSessionClosesWithTheResumeIntentCode() {
        endpoint(resumableState()).onText(connection, OP9_RESUMABLE_FRAME);

        assertEquals(1, connection.closes.size());
        assertEquals(DiscordGatewayEndpoint.RECONNECT_CLOSE_CODE, connection.closes.get(0).getCode(),
                "op 9 d=true keeps the session — the close must not invalidate it");
    }

    @Test
    void aNonResumableInvalidSessionClosesWithTheNormalCode() {
        endpoint(resumableState()).onText(connection, OP9_NON_RESUMABLE_FRAME);

        assertEquals(1, connection.closes.size());
        assertEquals(1000, connection.closes.get(0).getCode(),
                "op 9 d=false means the session is dead — the deliberate 1000 close is correct here");
    }

    @Test
    void helloWithAResumableSessionSendsTheOp6ResumeFrame() throws Exception {
        endpoint(resumableState()).onText(connection, HELLO_FRAME);

        assertEquals(1, connection.sentFrames.size(), "HELLO answers with exactly one frame");
        JsonNode frame = MAPPER.readTree(connection.sentFrames.get(0));
        assertEquals(GatewayProtocol.OP_RESUME, frame.get("op").asInt(),
                "a resumable session continues via RESUME on the wire, not just in decide()");
        assertEquals("sess-1", frame.get("d").get("session_id").asText());
        assertEquals(42L, frame.get("d").get("seq").asLong());
        assertEquals("test-token", frame.get("d").get("token").asText());
    }

    @Test
    void helloWithoutAResumableSessionSendsIdentify() throws Exception {
        endpoint(new GatewayState()).onText(connection, HELLO_FRAME);

        assertEquals(1, connection.sentFrames.size());
        assertEquals(GatewayProtocol.OP_IDENTIFY,
                MAPPER.readTree(connection.sentFrames.get(0)).get("op").asInt());
    }

    @Test
    void sendResumeFallsBackToIdentifyWhenTheSessionVanished() throws Exception {
        // decide() only yields SendResume when canResume(), but the state lives in atomics: an op-9
        // reset can land between the decision and the send. The degraded behavior is a fresh IDENTIFY,
        // never a malformed RESUME.
        endpoint(new GatewayState()).sendResume(connection);

        assertEquals(1, connection.sentFrames.size());
        assertEquals(GatewayProtocol.OP_IDENTIFY,
                MAPPER.readTree(connection.sentFrames.get(0)).get("op").asInt(),
                "an empty session at send time degrades to IDENTIFY");
    }

    @Test
    void aResumedDispatchResetsTheChannelBackoff() {
        endpoint(resumableState()).onText(connection, RESUMED_FRAME);

        assertEquals(1, channel.readyCalls.get(),
                "RESUMED restarts the reconnect-backoff schedule exactly as READY does");
        assertTrue(connection.sentFrames.isEmpty(), "RESUMED needs no outbound action");
        assertEquals(43L, endpoint.state.lastSequence().orElseThrow());
    }
}
