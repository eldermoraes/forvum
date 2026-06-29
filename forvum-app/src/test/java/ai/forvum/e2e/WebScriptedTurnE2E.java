package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end across the whole app stack: a browser WebSocket frame drives a real turn — the web
 * channel's {@code ChatSocket} → the injected SDK {@code ChannelTurnDriver} (resolved to the engine's
 * {@code TurnService} on the app classpath, Resolution B) → the agent runtime → the in-process
 * {@code FakeModelProvider} — and the rendered reply ({@code "pong"}) streams back over the same
 * socket. The non-live counterpart to the {@code @Tag("live")} provider e2e scripts; it is the guard
 * that the channel-to-engine wiring composes app-wide without a real LLM.
 */
@QuarkusTest
@TestProfile(WebScriptedTurnE2E.FakeBackedHomeProfile.class)
class WebScriptedTurnE2E {

    static final String OPERATOR_TOKEN = "test-operator-secret-165";

    @TestHTTPResource("/ws/chat")
    URI uri;

    @Test
    void aWebSocketFrameDrivesARealTurnAndStreamsTheReplyBack() throws InterruptedException {
        LinkedBlockingDeque<String> messages = new LinkedBlockingDeque<>();

        WebSocketClientConnection connection = BasicWebSocketConnector.create()
                .baseUri(URI.create(uri + "?access_token=" + OPERATOR_TOKEN))
                .onTextMessage((c, message) -> messages.add(message))
                .connectAndAwait();
        try {
            connection.sendTextAndAwait("hello");

            String reply = messages.poll(10, TimeUnit.SECONDS);
            assertEquals("pong", reply,
                    "the engine drove the turn end-to-end through the in-process fake model");
        } finally {
            connection.closeAndAwait();
        }
    }

    @Test
    void anonymousHandshakeIsRejected() {
        // Without the operator token the /ws/chat upgrade is denied by the HTTP security policy (401), so
        // connect fails — an unauthenticated client cannot open an operator chat socket (#165).
        assertThrows(Exception.class,
                () -> BasicWebSocketConnector.create().baseUri(uri).connectAndAwait(),
                "an anonymous /ws/chat handshake must be rejected");
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so a real turn needs no LLM. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-web-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "forvum.home", HOME.toString(),
                    "forvum.operator.token", OPERATOR_TOKEN);
        }
    }
}
