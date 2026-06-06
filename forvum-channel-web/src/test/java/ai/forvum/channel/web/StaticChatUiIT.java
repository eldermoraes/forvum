package ai.forvum.channel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The minimal chat UI ({@code index.html} + {@code chat.js}) ships as a static resource under
 * {@code META-INF/resources/} and is served by vertx-http (pulled in transitively by
 * quarkus-websockets-next). Confirms the page is reachable so the WebSocket endpoint has a front end.
 */
@QuarkusTest
class StaticChatUiIT {

    @TestHTTPResource("/index.html")
    URI indexUri;

    @Test
    void servesTheChatUi() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(indexUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "index.html is served by vertx-http");
        assertTrue(response.body().contains("Forvum"), "the chat UI carries the Forvum title");
    }
}
