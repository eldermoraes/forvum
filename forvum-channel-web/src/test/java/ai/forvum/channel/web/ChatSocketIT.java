package ai.forvum.channel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * The Web channel's WebSocket endpoint drives a turn and streams the rendered reply back over the same
 * connection. The {@link FakeTurnDriver} stands in for the engine (a Layer-3 channel cannot depend on
 * it): an inbound {@code "hello"} becomes one {@code TokenDelta("echo:hello")} then a terminal
 * {@code Done}; the channel renders the {@code TokenDelta} text and skips the {@code Done}, so the
 * client receives exactly one frame, {@code "echo:hello"}. A {@code @QuarkusTest} boots a real in-JVM
 * HTTP/WS server (forvum-channel-web is a headless Quarkus library — its {@code *IT} runs under
 * Surefire, CLAUDE.md section 4).
 */
@QuarkusTest
class ChatSocketIT {

    @TestHTTPResource("/ws/chat")
    URI uri;

    @Test
    void echoesTheRenderedTurnReplyAndSkipsTheTerminalDone() throws InterruptedException {
        LinkedBlockingDeque<String> messages = new LinkedBlockingDeque<>();

        WebSocketClientConnection connection = BasicWebSocketConnector.create()
                .baseUri(uri)
                .onTextMessage((c, message) -> messages.add(message))
                .connectAndAwait();
        try {
            connection.sendTextAndAwait("hello");

            String reply = messages.poll(10, TimeUnit.SECONDS);
            assertEquals("echo:hello", reply, "the channel streams the rendered TokenDelta text");

            // Done renders to empty and must NOT be sent: no second frame within a short window.
            assertNull(messages.poll(1, TimeUnit.SECONDS),
                    "the terminal Done renders empty and is not pushed to the client");
        } finally {
            connection.closeAndAwait();
        }
    }
}
