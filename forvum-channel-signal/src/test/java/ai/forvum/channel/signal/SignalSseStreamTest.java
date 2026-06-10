package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SignalChannel#streamOnce} against a REAL (but hermetic, in-test) HTTP server — the JDK
 * {@code com.sun.net.httpserver} standing in for the signal-cli daemon. Proves the hand-rolled blocking
 * SSE reader end-to-end: request shape (path, {@code Accept}, the URL-encoded {@code account} query) →
 * line streaming → event assembly → turn dispatch → JSON-RPC reply; and that a non-200 response is an
 * {@link IOException} (the reconnect trigger). NOT a live test: no signal-cli involved, the server is
 * local and per-test.
 */
class SignalSseStreamTest {

    private static final String TEXT_EVENT =
            "event:receive\n"
                    + "data:{\"envelope\":{\"sourceNumber\":\"+15550001111\","
                    + "\"dataMessage\":{\"message\":\"over http\",\"timestamp\":3}}}\n"
                    + "\n";

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static SignalChannel wiredChannel(FakeTurnDriver driver) {
        EnvelopeProcessor processor = new EnvelopeProcessor();
        processor.turns = driver;

        SignalChannel channel = new SignalChannel();
        channel.processor = processor;
        channel.config = new SignalChannelConfig(Path.of("/nonexistent/signal.json"));
        return channel;
    }

    @Test
    void streamOnceConsumesARealSseConnection() throws Exception {
        AtomicReference<String> requestedQuery = new AtomicReference<>();
        AtomicReference<String> acceptHeader = new AtomicReference<>();
        server.createContext("/api/v1/events", exchange -> {
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = (":keep-alive\n\n" + TEXT_EVENT).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        FakeTurnDriver driver = new FakeTurnDriver();
        SignalChannel channel = wiredChannel(driver);
        RecordingSignalRpcApi api = new RecordingSignalRpcApi();

        channel.streamOnce(HttpClient.newHttpClient(), api, baseUrl(), "+15559990000");

        assertEquals("account=%2B15559990000", requestedQuery.get(),
                "the account rides the events query, URL-encoded");
        assertEquals("text/event-stream", acceptHeader.get());
        assertEquals(1, driver.dispatched().size(), "the streamed event drove exactly one turn");
        assertEquals("over http", driver.dispatched().get(0).content());
        assertEquals(1, api.sent.size(), "the reply went out as one JSON-RPC send");
        assertEquals("echo:over http", api.sent.get(0).request().params().message());
    }

    @Test
    void aNon200ResponseIsAnIOExceptionForTheReconnectLoop() {
        server.createContext("/api/v1/events", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        SignalChannel channel = wiredChannel(new FakeTurnDriver());

        IOException failure = assertThrows(IOException.class,
                () -> channel.streamOnce(HttpClient.newHttpClient(), new RecordingSignalRpcApi(),
                        baseUrl(), "+1999"));
        assertTrue(failure.getMessage().contains("503"));
    }

    /**
     * The full lifecycle against the in-test daemon: an ENABLED {@code channels/signal.json} pointing at
     * the server → {@code onStart} launches the virtual-thread worker → the streamed event drives a turn
     * → the stream ends (server closes) → the loop backs off (shrunk schedule) and reconnects →
     * {@code onStop} terminates the worker. Covers the production {@code onStart}/{@code eventLoop}
     * wiring that the consume/boot tests bypass.
     */
    @Test
    void onStartStreamsReconnectsAndStopsCleanly(@org.junit.jupiter.api.io.TempDir Path home)
            throws Exception {
        CountDownLatch connections = new CountDownLatch(2); // initial connect + at least one reconnect
        server.createContext("/api/v1/events", exchange -> {
            connections.countDown();
            byte[] body = TEXT_EVENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } // closing ends the stream → the channel must back off and reconnect
        });

        Path channels = Files.createDirectories(home.resolve("channels"));
        Files.writeString(channels.resolve("signal.json"),
                "{ \"baseUrl\": \"" + baseUrl() + "\", \"account\": \"+15559990000\" }");

        FakeTurnDriver driver = new FakeTurnDriver();
        EnvelopeProcessor processor = new EnvelopeProcessor();
        processor.turns = driver;
        SignalChannel channel = new SignalChannel();
        channel.processor = processor;
        channel.config = new SignalChannelConfig(channels.resolve("signal.json"));
        channel.api = new RecordingSignalRpcApi();
        channel.backoff = new Backoff(1L, 4L); // shrink the reconnect schedule for the test

        channel.onStart(null);
        try {
            assertNotNull(channel.worker, "an enabled, fully-configured channel starts the worker");
            assertTrue(connections.await(10, TimeUnit.SECONDS),
                    "the loop reconnected after the daemon closed the stream");
        } finally {
            channel.onStop(null);
        }
        assertTrue(channel.worker.isShutdown(), "onStop shuts the worker down");
        assertTrue(driver.dispatched().size() >= 1, "at least one streamed event drove a turn");
        assertEquals("over http", driver.dispatched().get(0).content());
    }
}
