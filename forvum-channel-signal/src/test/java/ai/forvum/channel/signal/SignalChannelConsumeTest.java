package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.sdk.ChannelTurnDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The stream-consumption cycle ({@link SignalChannel#consume}): SSE lines → assembled events →
 * {@link SignalEvents} classification → {@code EnvelopeProcessor.process} (drive a turn) → the JSON-RPC
 * reply. A plain POJO unit test (no Quarkus boot, no socket): {@code SignalChannel},
 * {@code EnvelopeProcessor}, and {@code FakeTurnDriver} are constructed directly and wired by hand, with
 * a fixture line stream standing in for a daemon connection.
 */
class SignalChannelConsumeTest {

    @TempDir
    static Path home;

    private static final String TEXT_EVENT_DATA =
            "{\"envelope\":{\"sourceNumber\":\"+15550001111\","
                    + "\"dataMessage\":{\"message\":\"ping\",\"timestamp\":1}},\"account\":\"+1999\"}";

    /**
     * Wire a {@code SignalChannel} with the given turn driver + a config that opts into #170 public mode:
     * an empty allow-list now denies by default, so the fixture's senders need {@code allowAllUsers}.
     */
    private static SignalChannel wiredChannel(ChannelTurnDriver driver) {
        EnvelopeProcessor processor = new EnvelopeProcessor();
        processor.turns = driver;

        SignalChannel channel = new SignalChannel();
        channel.processor = processor;
        channel.config = publicModeConfig();
        return channel;
    }

    /** A config bound to a {@code channels/signal.json} that admits any sender (#170 public mode). */
    private static SignalChannelConfig publicModeConfig() {
        try {
            Path file = Files.createDirectories(home.resolve("channels")).resolve("signal.json");
            Files.writeString(file, "{ \"allowAllUsers\": true }");
            return new SignalChannelConfig(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aTextEventDrivesTheTurnAndSendsTheReply() {
        FakeTurnDriver driver = new FakeTurnDriver();
        SignalChannel channel = wiredChannel(driver);
        RecordingSignalRpcApi api = new RecordingSignalRpcApi();

        channel.consume(Stream.of(
                ":keep-alive",
                "",
                "event:receive",
                "data:" + TEXT_EVENT_DATA,
                ""), api, "http://localhost:8080", "+1999");

        // The wired config opts into #170 public mode (allowAllUsers), so the sender is admitted.
        assertEquals(1, driver.dispatched().size(), "the text event drove exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("signal", dispatched.channelId());
        assertEquals("+15550001111", dispatched.nativeUserId(), "native user id is the sender's number");
        assertEquals("ping", dispatched.content());

        List<RecordingSignalRpcApi.Sent> sent = api.sent;
        assertEquals(1, sent.size(), "the rendered reply was sent back as one JSON-RPC send");
        assertEquals("http://localhost:8080", sent.get(0).baseUrl());
        assertEquals("send", sent.get(0).request().method());
        assertEquals("+1999", sent.get(0).request().params().account());
        assertEquals(List.of("+15550001111"), sent.get(0).request().params().recipient());
        assertEquals("echo:ping", sent.get(0).request().params().message());
    }

    @Test
    void receiptsAndTypingEventsDriveNoTurnAndSendNothing() {
        FakeTurnDriver driver = new FakeTurnDriver();
        SignalChannel channel = wiredChannel(driver);
        RecordingSignalRpcApi api = new RecordingSignalRpcApi();

        channel.consume(Stream.of(
                "event:receive",
                "data:{\"envelope\":{\"sourceNumber\":\"+1\",\"receiptMessage\":{\"isDelivery\":true}}}",
                "",
                "event:receive",
                "data:{\"envelope\":{\"sourceNumber\":\"+1\",\"typingMessage\":{\"action\":\"STARTED\"}}}",
                ""), api, "http://localhost:8080", "+1999");

        assertTrue(driver.dispatched().isEmpty(), "receipts/typing drive no turn");
        assertTrue(api.sent.isEmpty(), "receipts/typing send nothing");
    }

    @Test
    void aCompleteEventResetsTheBackoffButKeepAlivesDoNot() {
        SignalChannel channel = wiredChannel(new FakeTurnDriver());
        channel.backoff = new Backoff(1_000L, 60_000L);
        channel.backoff.nextDelayMillis(); // 1000
        channel.backoff.nextDelayMillis(); // 2000 — schedule advanced by two failed connects

        channel.consume(Stream.of(":keep-alive", ""), new RecordingSignalRpcApi(), "http://x", "+1");
        assertEquals(4_000L, channel.backoff.nextDelayMillis(),
                "a keep-alive comment completes no event, so the backoff is NOT reset");

        channel.backoff.reset();
        channel.backoff.nextDelayMillis(); // 1000
        channel.backoff.nextDelayMillis(); // 2000
        channel.consume(Stream.of("event:receive", "data:" + TEXT_EVENT_DATA, ""),
                new RecordingSignalRpcApi(), "http://x", "+1");
        assertEquals(1_000L, channel.backoff.nextDelayMillis(),
                "a complete event marks the stream healthy and resets the backoff");
    }

    @Test
    void aThrowingEventIsIsolatedAndTheStreamContinues() {
        // A driver that throws on the FIRST dispatch: the event's failure must be caught (the M4
        // watcher lesson) so the SECOND event still drives its turn. A lambda delegate — NOT a
        // subclass of the @ApplicationScoped FakeTurnDriver (an un-vetoed bean subclass would be a
        // second ambiguous bean in this module's @QuarkusTest, the P2-CRON-DELIVERY trap).
        FakeTurnDriver delegate = new FakeTurnDriver();
        AtomicBoolean first = new AtomicBoolean(true);
        ChannelTurnDriver throwingFirst = (message, sink) -> {
            if (first.getAndSet(false)) {
                throw new IllegalStateException("first event blows up");
            }
            delegate.dispatch(message, sink);
        };
        SignalChannel channel = wiredChannel(throwingFirst);
        RecordingSignalRpcApi api = new RecordingSignalRpcApi();

        channel.consume(Stream.of(
                "event:receive", "data:" + TEXT_EVENT_DATA, "",
                "event:receive", "data:" + TEXT_EVENT_DATA, ""), api, "http://x", "+1999");

        assertEquals(1, delegate.dispatched().size(),
                "the second event still ran after the first one threw");
        assertEquals(1, api.sent.size(), "the second event's reply was still sent");
    }
}
