package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.signal.SignalChannelConfig.Spec;
import ai.forvum.channel.signal.SignalEvents.TextMessage;
import ai.forvum.channel.signal.dto.JsonRpcError;
import ai.forvum.channel.signal.dto.JsonRpcResponse;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.TokenDelta;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The reply / send paths of {@link EnvelopeProcessor} that the booted {@code EnvelopeProcessorIT}
 * (happy path only) and {@code SignalRedactTest} (redact as a pure function) leave unexercised: the
 * non-identifying refusal audit (no PII), the self-echo predicate, a failed turn's {@link ErrorEvent}
 * surfacing as a user-visible reply (the M16 render-arm), a JSON-RPC error envelope, and a transport
 * exception during send — each driven through {@code process()} with an in-test driver / RPC double, no
 * Quarkus boot. Plain unit test.
 */
class EnvelopeProcessorReplyTest {

    private static final String ACCOUNT = "+15559990000";
    private static final String BASE = "http://localhost:8080";
    private static final ModelRef MODEL = ModelRef.parse("fake:test-model");

    private static TextMessage from(String number, String content) {
        return new TextMessage(number, number, null, content, 1L);
    }

    private static Spec anySender() {
        return new Spec(true, Optional.of(BASE), Optional.of(ACCOUNT), Set.of());
    }

    // --- Fix 1 / finding 13: the refusal audit carries the SIZE only, never PII -------------------

    @Test
    void theRefusalAuditLogsTheAuthorizedCountNeverTheSenderOrTheAllowListMembers() {
        String audit = EnvelopeProcessor.refusalAudit(3);
        assertTrue(audit.contains("3"), "the authorized-set size is logged");
        // The method takes only an int, so a phone number / UUID cannot structurally reach the line —
        // pin that a representative sender id and allow-list member never appear.
        assertFalse(audit.contains("+1555"), "no sender phone number in the audit");
        assertFalse(audit.contains("9d3f5c8e"), "no allow-list UUID member in the audit");
    }

    // --- Fix 2: the self-echo predicate (own-account match across every sender id) ----------------

    @Test
    void isOwnMessageMatchesTheAccountAcrossEverySenderIdAndNeverOnABlankAccount() {
        assertTrue(EnvelopeProcessor.isOwnMessage(new TextMessage(ACCOUNT, ACCOUNT, null, "x", 0), ACCOUNT),
                "own number matches");
        assertTrue(EnvelopeProcessor.isOwnMessage(
                        new TextMessage("u-1", null, "u-1", "x", 0), "u-1"),
                "own uuid matches");
        assertFalse(EnvelopeProcessor.isOwnMessage(
                        new TextMessage("+15550001111", "+15550001111", null, "x", 0), ACCOUNT),
                "a different sender is not the bot");
        assertFalse(EnvelopeProcessor.isOwnMessage(new TextMessage(ACCOUNT, ACCOUNT, null, "x", 0), "   "),
                "a blank account never matches");
        assertFalse(EnvelopeProcessor.isOwnMessage(new TextMessage(ACCOUNT, ACCOUNT, null, "x", 0), null),
                "a null account never matches");
    }

    // --- findings 9 / 15: a failed turn's ErrorEvent is sent back to the user ---------------------

    @Test
    void aFailedTurnsErrorEventIsSentBackToTheSender() {
        EnvelopeProcessor processor = new EnvelopeProcessor();
        processor.turns = (message, sink) -> {
            Instant now = Instant.now();
            sink.accept(new TokenDelta(now, "partial...", MODEL));
            sink.accept(ErrorEvent.from(now, UUID.randomUUID(), "TURN_FAILED",
                    "the model is unavailable", new RuntimeException("boom")));
        };
        RecordingSignalRpcApi api = new RecordingSignalRpcApi();

        processor.process(from("+15550001111", "hi"), anySender(), api, BASE, ACCOUNT);

        List<String> sentMessages = api.sent.stream().map(s -> s.request().params().message()).toList();
        assertTrue(sentMessages.contains("the model is unavailable"),
                "the failed turn's ErrorEvent message must reach the user as a reply (the M16 render arm)");
        assertEquals(List.of("+15550001111"), api.sent.get(0).request().params().recipient());
    }

    // --- finding 10: a JSON-RPC error envelope is logged, not thrown; the turn still ran ----------

    @Test
    void aJsonRpcErrorEnvelopeIsHandledWithoutThrowingAndTheTurnStillRan() {
        EnvelopeProcessor processor = new EnvelopeProcessor();
        CopyOnWriteArrayList<ChannelMessage> dispatched = new CopyOnWriteArrayList<>();
        processor.turns = (message, sink) -> {
            dispatched.add(message);
            sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        };
        // HTTP 200 with a JSON-RPC error object (a rate limit / unregistered recipient): the WARN
        // branch logs code+message+length and returns — it must NOT propagate.
        SignalRpcApi errorApi = (baseUrl, request) -> new JsonRpcResponse(null, new JsonRpcError(429,
                "rate limited"));

        assertDoesNotThrow(() ->
                processor.process(from("+15550001111", "hi"), anySender(), errorApi, BASE, ACCOUNT));
        assertEquals(1, dispatched.size(),
                "the turn ran; the send error is on the reply and is logged, never thrown");
    }

    // --- finding 11: a transport exception during send is swallowed (the stream survives) ---------

    @Test
    void aTransportExceptionDuringSendIsSwallowedSoTheStreamSurvives() {
        EnvelopeProcessor processor = new EnvelopeProcessor();
        processor.turns = (message, sink) -> sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        // The thrown message echoes a request URL whose account query is the operator's number; the
        // catch(RuntimeException) arm redacts it (SignalChannel.redact, pinned by SignalRedactTest) and
        // logs — it must NOT propagate, or one failed send would kill the stream-consuming loop.
        SignalRpcApi throwingApi = (baseUrl, request) -> {
            throw new RuntimeException("connect failed: GET " + SignalChannel.eventsUri(BASE, ACCOUNT));
        };

        assertDoesNotThrow(() ->
                processor.process(from("+15550001111", "hi"), anySender(), throwingApi, BASE, ACCOUNT));
    }
}
