package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.whatsapp.WhatsAppChannelConfig.Spec;
import ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.event.TokenDelta;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Pins {@code MessageProcessor.redact()} at every WIRED log site — not just in isolation
 * ({@code MessageProcessorTest.redactMasksABearerToken}): replacing {@code redact(...)} with the raw
 * message at the transport-exception catch OR the Graph error-envelope branch turns a test here red. The
 * Graph access token rides the {@code Authorization: Bearer <token>} header, so a Graph error / transport
 * exception message can echo it; both log sites must emit the REDACTED message only. Records are captured
 * through a handler on the backing JBoss LogManager logger (the module pins
 * {@code java.util.logging.manager} to it), mirroring {@code MatrixLogRedactionWiringTest}.
 */
class WhatsAppLogRedactionWiringTest {

    private static final ModelRef MODEL = ModelRef.parse("fake:m");
    private static final String SECRET = "EAAtoken-graph-secret-xyz";
    private static final String LEAKY = "HTTP 401 OAuthException for Authorization: Bearer " + SECRET;
    private static final Spec SPEC = new Spec(true, Optional.of("vt"), Optional.of("as"),
            Optional.of("at"), Optional.of("PNID"), "v21.0", Set.of());

    private static InboundMessage msg() {
        return new InboundMessage("15550001111", "hi", "wamid.1", 1L);
    }

    /** Run {@code action} while capturing the formatted records of {@code loggerClass}'s logger. */
    private static List<String> capturedLogs(Class<?> loggerClass, Runnable action) {
        List<String> messages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                messages.add(logRecord instanceof ExtLogRecord ext
                        ? ext.getFormattedMessage()
                        : logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(loggerClass.getName());
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    private static void assertRedacted(List<String> logs) {
        assertTrue(logs.stream().anyMatch(m -> m.contains("Bearer <redacted>")),
                "the failure must be logged with the token redacted; got: " + logs);
        assertFalse(logs.stream().anyMatch(m -> m.contains(SECRET)),
                "the access token must never reach the logs; got: " + logs);
    }

    @Test
    void aTransportExceptionLogsTheRedactedMessageOnly() {
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        GraphApi throwing = (apiVersion, phoneNumberId, authorization, body) -> {
            throw new RuntimeException(LEAKY);
        };

        List<String> logs =
                capturedLogs(MessageProcessor.class, () -> processor.process(msg(), SPEC, throwing));

        assertRedacted(logs);
    }

    @Test
    void aGraphErrorEnvelopeMessageIsRedacted() {
        MessageProcessor processor = new MessageProcessor();
        processor.turns = (message, sink) -> sink.accept(new TokenDelta(Instant.now(), "reply", MODEL));
        ObjectNode errorEnvelope = JsonNodeFactory.instance.objectNode();
        errorEnvelope.set("error",
                JsonNodeFactory.instance.objectNode().put("code", 131).put("message", LEAKY));
        GraphApi errorApi = (apiVersion, phoneNumberId, authorization, body) -> errorEnvelope;

        List<String> logs =
                capturedLogs(MessageProcessor.class, () -> processor.process(msg(), SPEC, errorApi));

        assertRedacted(logs);
    }
}
