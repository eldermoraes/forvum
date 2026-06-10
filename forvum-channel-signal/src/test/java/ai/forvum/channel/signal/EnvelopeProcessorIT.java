package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.signal.SignalChannelConfig.Spec;
import ai.forvum.channel.signal.SignalEvents.TextMessage;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The module's CDI + REST-client wiring and the {@code allowedUserIds} enforcement (the P2-CH channel
 * Verify), in one booted {@code @QuarkusTest}: the real {@code EnvelopeProcessor} bean drives turns
 * against the in-module {@link FakeTurnDriver} (the engine's {@code TurnService} is banned by the
 * Layer-3 enforcer) and a {@link RecordingSignalRpcApi}; the {@code @RestClient}-registered
 * {@link SignalRpcApi} resolves from CDI; and — with {@code forvum.home} pinned to a hermetic path with
 * no {@code channels/signal.json} (test {@code application.properties}) — the {@link SignalChannel}
 * boots INERT: no worker, no connection, nothing thrown (the no-config native smoke contract). Boots
 * Quarkus in-JVM; runs under Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class EnvelopeProcessorIT {

    @Inject
    EnvelopeProcessor processor;

    @Inject
    FakeTurnDriver driver;

    @Inject
    SignalChannel channel;

    @Inject
    @RestClient
    SignalRpcApi restClient;

    private RecordingSignalRpcApi api;

    @BeforeEach
    void resetDriver() {
        driver.reset();
        api = new RecordingSignalRpcApi();
    }

    private static TextMessage text(String number, String uuid, String content) {
        String replyTo = number != null ? number : uuid;
        return new TextMessage(replyTo, number, uuid, content, 1L);
    }

    private static Spec spec(Set<String> allowed) {
        return new Spec(true, Optional.of("http://localhost:8080"), Optional.of("+15559990000"),
                allowed);
    }

    @Test
    void theRestClientAndTheChannelBeansResolveAndTheNoConfigBootIsInert() {
        assertNotNull(restClient, "the @RegisterRestClient(configKey=signal-rpc) client must resolve");
        // forvum.home is pinned to a hermetic path with NO channels/signal.json, so the channel's
        // onStart observer already ran as a no-op: disabled, no worker, nothing thrown.
        assertFalse(channel.isStreaming(), "an unconfigured channel never starts streaming");
    }

    @Test
    void anAllowedSenderDrivesATurnAndTheReplyIsSentBack() {
        // empty allow-list => any sender allowed
        processor.process(text("+15550001111", null, "hello"), spec(Set.of()), api,
                "http://localhost:8080", "+15559990000");

        assertEquals(1, driver.dispatched().size(), "an allowed sender must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("signal", dispatched.channelId());
        assertEquals("+15550001111", dispatched.nativeUserId());
        assertEquals("hello", dispatched.content());

        List<RecordingSignalRpcApi.Sent> sent = api.sent;
        assertEquals(1, sent.size(), "the TokenDelta reply is sent; the terminal Done renders to nothing");
        assertEquals("send", sent.get(0).request().method());
        assertEquals(List.of("+15550001111"), sent.get(0).request().params().recipient());
        assertEquals("+15559990000", sent.get(0).request().params().account());
        assertEquals("echo:hello", sent.get(0).request().params().message());
    }

    @Test
    void aDisallowedSenderIsRefusedWithAFriendlyMessageAndNoTurnRuns() {
        processor.process(text("+15557772222", null, "let me in"),
                spec(Set.of("+15550001111")), api, "http://localhost:8080", "+15559990000");

        assertTrue(driver.dispatched().isEmpty(), "a refused sender must NOT drive a turn");
        assertEquals(1, api.sent.size(), "the refusal must be sent back to the sender");
        assertEquals(List.of("+15557772222"), api.sent.get(0).request().params().recipient());
        assertEquals(EnvelopeProcessor.REFUSAL_MESSAGE, api.sent.get(0).request().params().message());
    }

    @Test
    void anAllowedListedSenderDrivesATurn() {
        processor.process(text("+15550001111", null, "hi"), spec(Set.of("+15550001111")), api,
                "http://localhost:8080", "+15559990000");

        assertEquals(1, driver.dispatched().size());
        assertEquals("echo:hi", api.sent.get(0).request().params().message());
    }

    @Test
    void aUuidListedSenderIsAdmittedByItsUuid() {
        // The sender's number is NOT listed but its uuid is: the allow-list must match EITHER id.
        processor.process(text("+15557772222", "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111", "via uuid"),
                spec(Set.of("9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111")), api,
                "http://localhost:8080", "+15559990000");

        assertEquals(1, driver.dispatched().size(), "the uuid admits the sender");
        assertEquals("echo:via uuid", api.sent.get(0).request().params().message());
    }

    @Test
    void aRefusalIsAddressedToAUuidOnlySender() {
        processor.process(text(null, "1111-not-allowed", "knock knock"),
                spec(Set.of("+15550001111")), api, "http://localhost:8080", "+15559990000");

        assertTrue(driver.dispatched().isEmpty());
        assertEquals(List.of("1111-not-allowed"), api.sent.get(0).request().params().recipient(),
                "the refusal goes back to the uuid when the sender has no number");
        assertEquals(EnvelopeProcessor.REFUSAL_MESSAGE, api.sent.get(0).request().params().message());
    }
}
