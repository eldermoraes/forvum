package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.whatsapp.WhatsAppChannelConfig.Spec;
import ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage;
import ai.forvum.channel.whatsapp.dto.SendMessageRequest;
import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.TokenDelta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link MessageProcessor}: the allow-list gate (admit → turn + reply; refuse → friendly message + no
 * turn + a count-only PII-free audit), a failed turn's {@link ErrorEvent} surfacing as a reply (the M16
 * render arm), a Graph error envelope / transport exception being swallowed (the webhook worker
 * survives), and the {@code Bearer}-token redaction. Driven through {@code process()} with an in-test
 * turn driver + a recording Graph client — no Quarkus boot.
 */
class MessageProcessorTest {

    private static final ModelRef MODEL = ModelRef.parse("fake:test-model");

    private static Spec spec(Set<String> allowed, boolean allowAllUsers) {
        return new Spec(true, Optional.of("vt"), Optional.of("as"), Optional.of("at"),
                Optional.of("PNID"), "v21.0", allowed, allowAllUsers);
    }

    private static InboundMessage msg(String from, String text) {
        return new InboundMessage(from, text, "wamid.1", 1L);
    }

    /** A recording {@link GraphApi}: captures every send (and its auth header) and returns a configurable
     *  response or throws a configurable exception. */
    private static final class RecordingGraphApi implements GraphApi {
        final List<SendMessageRequest> sent = new CopyOnWriteArrayList<>();
        final List<String> auths = new CopyOnWriteArrayList<>();
        JsonNode response = JsonNodeFactory.instance.objectNode();
        RuntimeException toThrow;

        @Override
        public JsonNode send(String apiVersion, String phoneNumberId, String authorization,
                             SendMessageRequest body) {
            auths.add(authorization);
            if (toThrow != null) {
                throw toThrow;
            }
            sent.add(body);
            return response;
        }
    }

    @Test
    void anAllowedSenderDrivesATurnAndTheReplyIsSent() {
        List<ChannelMessage> dispatched = new CopyOnWriteArrayList<>();
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> {
            dispatched.add(message);
            sink.accept(new TokenDelta(Instant.now(), "echo:" + message.content(), MODEL));
        };
        RecordingGraphApi api = new RecordingGraphApi();

        processor.process(msg("15550001111", "hi"), spec(Set.of(), true), api);

        assertEquals(1, dispatched.size(), "an allowed sender drives exactly one turn");
        assertEquals("whatsapp", dispatched.get(0).channelId());
        assertEquals("15550001111", dispatched.get(0).nativeUserId());
        assertEquals("hi", dispatched.get(0).content());
        assertEquals(1, api.sent.size());
        assertEquals("whatsapp", api.sent.get(0).messaging_product());
        assertEquals("15550001111", api.sent.get(0).to());
        assertEquals("text", api.sent.get(0).type());
        assertEquals("echo:hi", api.sent.get(0).text().body());
        assertEquals("Bearer at", api.auths.get(0), "the access token rides the Authorization header");
    }

    @Test
    void aDisallowedSenderGetsTheRefusalAndNoTurnRuns() {
        List<ChannelMessage> dispatched = new CopyOnWriteArrayList<>();
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> dispatched.add(message);
        RecordingGraphApi api = new RecordingGraphApi();

        processor.process(msg("15557772222", "let me in"), spec(Set.of("15550001111"), false), api);

        assertTrue(dispatched.isEmpty(), "a refused sender must NOT drive a turn");
        assertEquals(1, api.sent.size(), "the refusal is sent back");
        assertEquals("15557772222", api.sent.get(0).to());
        assertEquals(MessageProcessor.REFUSAL_MESSAGE, api.sent.get(0).text().body());
    }

    @Test
    void theRefusalAuditLogsTheCountNeverTheSenderOrAllowListMembers() {
        String audit = MessageProcessor.refusalAudit(3);
        assertTrue(audit.contains("3"), "the authorized-set size is logged");
        assertFalse(audit.contains("15557772222"), "no sender wa_id in the audit");
        assertFalse(audit.contains("15550001111"), "no allow-list member in the audit");
    }

    @Test
    void aFailedTurnsErrorEventIsSentBackToTheSender() {
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> {
            sink.accept(new TokenDelta(Instant.now(), "partial...", MODEL));
            sink.accept(ErrorEvent.from(Instant.now(), UUID.randomUUID(), "TURN_FAILED",
                    "the model is unavailable", new RuntimeException("boom")));
        };
        RecordingGraphApi api = new RecordingGraphApi();

        processor.process(msg("15550001111", "hi"), spec(Set.of(), true), api);

        assertTrue(api.sent.stream().anyMatch(s -> "the model is unavailable".equals(s.text().body())),
                "the failed turn's ErrorEvent message must reach the user (the M16 render arm)");
    }

    @Test
    void aGraphErrorEnvelopeIsSwallowedAndTheTurnStillRan() {
        List<ChannelMessage> dispatched = new CopyOnWriteArrayList<>();
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> {
            dispatched.add(message);
            sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        };
        RecordingGraphApi api = new RecordingGraphApi();
        api.response = JsonNodeFactory.instance.objectNode()
                .set("error", JsonNodeFactory.instance.objectNode().put("code", 131).put("message", "x"));

        assertDoesNotThrow(() -> processor.process(msg("15550001111", "hi"), spec(Set.of(), true), api));
        assertEquals(1, dispatched.size(), "the turn ran; the send error is logged, never thrown");
    }

    @Test
    void aTransportExceptionDuringSendIsSwallowed() {
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        RecordingGraphApi api = new RecordingGraphApi();
        api.toThrow = new RuntimeException("connect failed; Authorization: Bearer at-secret");

        assertDoesNotThrow(() -> processor.process(msg("15550001111", "hi"), spec(Set.of(), true), api));
    }

    @Test
    void redactMasksABearerToken() {
        String redacted = MessageProcessor.redact("POST failed, Authorization: Bearer at-secret-123");
        assertTrue(redacted.contains("Bearer <redacted>"));
        assertFalse(redacted.contains("at-secret-123"), "the bearer token must not survive redaction");
    }
}
