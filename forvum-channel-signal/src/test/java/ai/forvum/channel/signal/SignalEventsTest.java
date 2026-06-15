package ai.forvum.channel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.signal.SignalEvents.Ignored;
import ai.forvum.channel.signal.SignalEvents.Inbound;
import ai.forvum.channel.signal.SignalEvents.SseEvent;
import ai.forvum.channel.signal.SignalEvents.TextMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * {@link SignalEvents#parse} classifies recorded signal-cli daemon event fixtures: only a direct
 * {@code dataMessage} text message becomes a {@link TextMessage}; receipts, typing notifications, sync
 * messages, group messages, malformed payloads, and non-receive events are {@link Ignored} (each pinned
 * here, per the P2-CH fixture-test requirement). A plain unit test — pure protocol, no socket.
 */
class SignalEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Inbound parse(String data) {
        return SignalEvents.parse(MAPPER, new SseEvent("receive", data));
    }

    // --- the consumed shape -----------------------------------------------------------------------

    @Test
    void aDirectTextMessageBecomesATextMessage() {
        Inbound inbound = parse("""
                { "envelope": { "source": "+15550001111", "sourceNumber": "+15550001111",
                                "sourceUuid": "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111",
                                "sourceName": "Alice", "timestamp": 1700000000000,
                                "dataMessage": { "message": "hello forvum", "timestamp": 1700000000000 } },
                  "account": "+15559990000" }
                """);

        TextMessage text = assertInstanceOf(TextMessage.class, inbound);
        assertEquals("+15550001111", text.replyTo(), "replies go to the sender's number when present");
        assertEquals("+15550001111", text.sourceNumber());
        assertEquals("9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111", text.sourceUuid());
        assertEquals("hello forvum", text.text());
        assertEquals(1700000000000L, text.timestamp());
    }

    @Test
    void theJsonRpcNotificationWrappingIsUnwrapped() {
        // signal-cli's JSON-RPC emit shape: the receive payload rides params.
        Inbound inbound = parse("""
                { "jsonrpc": "2.0", "method": "receive",
                  "params": { "envelope": { "sourceNumber": "+15550001111",
                                            "dataMessage": { "message": "wrapped", "timestamp": 5 } },
                              "account": "+15559990000" } }
                """);

        TextMessage text = assertInstanceOf(TextMessage.class, inbound);
        assertEquals("wrapped", text.text());
        assertEquals(5L, text.timestamp());
    }

    @Test
    void aUuidOnlySenderRepliesToTheUuid() {
        Inbound inbound = parse("""
                { "envelope": { "sourceUuid": "9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111",
                                "dataMessage": { "message": "uuid only" } } }
                """);

        TextMessage text = assertInstanceOf(TextMessage.class, inbound);
        assertEquals("9d3f5c8e-0000-4e2a-9aa3-2f37d1f3a111", text.replyTo());
        assertNull(text.sourceNumber());
    }

    @Test
    void aLegacySourceOnlyEnvelopeRepliesToSource() {
        Inbound inbound = parse("""
                { "envelope": { "source": "+15550001111", "dataMessage": { "message": "legacy" } } }
                """);

        TextMessage text = assertInstanceOf(TextMessage.class, inbound);
        assertEquals("+15550001111", text.replyTo());
    }

    // --- the ignored shapes (each a recorded daemon fixture) --------------------------------------

    @Test
    void aReceiptIsIgnored() {
        Inbound inbound = parse("""
                { "envelope": { "sourceNumber": "+15550001111", "timestamp": 1700000000001,
                                "receiptMessage": { "when": 1700000000001, "isDelivery": true,
                                                    "isRead": false, "timestamps": [1700000000000] } } }
                """);

        Ignored ignored = assertInstanceOf(Ignored.class, inbound);
        assertEquals("receipt", ignored.reason());
    }

    @Test
    void aTypingNotificationIsIgnored() {
        Inbound inbound = parse("""
                { "envelope": { "sourceNumber": "+15550001111",
                                "typingMessage": { "action": "STARTED", "timestamp": 1700000000002 } } }
                """);

        Ignored ignored = assertInstanceOf(Ignored.class, inbound);
        assertEquals("typing notification", ignored.reason());
    }

    @Test
    void aSyncMessageIsIgnoredEvenWhenNull() {
        // signal-cli may set syncMessage to null instead of omitting it; property EXISTENCE must win,
        // or a replayed sentTranscript loops the bot's own replies (the OpenClaw lesson).
        Inbound withPayload = parse("""
                { "envelope": { "sourceNumber": "+15559990000",
                                "syncMessage": { "sentMessage": { "message": "my own reply" } } } }
                """);
        Inbound withNull = parse("""
                { "envelope": { "sourceNumber": "+15550001111", "syncMessage": null,
                                "dataMessage": { "message": "looks real" } } }
                """);

        assertEquals("sync message", assertInstanceOf(Ignored.class, withPayload).reason());
        assertEquals("sync message", assertInstanceOf(Ignored.class, withNull).reason());
    }

    @Test
    void aGroupMessageIsIgnored() {
        // v0.5 is direct-messages-only: dataMessage.groupInfo present → ignore (documented limitation).
        Inbound inbound = parse("""
                { "envelope": { "sourceNumber": "+15550001111",
                                "dataMessage": { "message": "hi group",
                                                 "groupInfo": { "groupId": "abc==", "type": "DELIVER" } } } }
                """);

        Ignored ignored = assertInstanceOf(Ignored.class, inbound);
        assertEquals("group message (direct messages only in v0.5)", ignored.reason());
    }

    @Test
    void aReactionOnlyDataMessageIsIgnored() {
        Inbound inbound = parse("""
                { "envelope": { "sourceNumber": "+15550001111",
                                "dataMessage": { "message": null,
                                                 "reaction": { "emoji": "👍", "isRemove": false } } } }
                """);

        assertEquals("no message text", assertInstanceOf(Ignored.class, inbound).reason());
    }

    @Test
    void aMalformedDataLineIsIgnoredNotThrown() {
        assertEquals("malformed event JSON",
                assertInstanceOf(Ignored.class, parse("{ not json")).reason());
        assertEquals("malformed event JSON",
                assertInstanceOf(Ignored.class, parse("42")).reason(), "a non-object payload");
    }

    @Test
    void aNonReceiveEventNameIsIgnored() {
        Inbound inbound = SignalEvents.parse(MAPPER, new SseEvent("update", "{\"envelope\":{}}"));

        assertTrue(assertInstanceOf(Ignored.class, inbound).reason().contains("non-receive event"));
    }

    @Test
    void anUnnamedSseEventIsStillParsed() {
        // Defensive: an SSE event with no event: field (the default "message" dispatch) still carries
        // the receive payload on some daemon builds — classify by payload, not only by name.
        Inbound inbound = SignalEvents.parse(MAPPER, new SseEvent(null,
                "{ \"envelope\": { \"sourceNumber\": \"+15550001111\", "
                        + "\"dataMessage\": { \"message\": \"unnamed\" } } }"));

        assertEquals("unnamed", assertInstanceOf(TextMessage.class, inbound).text());
    }

    @Test
    void aNonReceiveJsonRpcMethodIsIgnored() {
        Inbound inbound = parse("{ \"jsonrpc\": \"2.0\", \"method\": \"listAccounts\", \"params\": {} }");

        assertTrue(assertInstanceOf(Ignored.class, inbound).reason().contains("non-receive method"));
    }

    @Test
    void anEventWithoutDataIsIgnored() {
        assertEquals("event with no data", assertInstanceOf(Ignored.class,
                SignalEvents.parse(MAPPER, new SseEvent("receive", null))).reason());
        assertEquals("event with no data", assertInstanceOf(Ignored.class, parse("  ")).reason());
    }

    @Test
    void aDaemonExceptionPayloadIsSurfacedNotSwallowed() {
        // signal-cli emits a stream-side receive error as { "exception": { "message": "..." } } with no
        // envelope; it must carry the daemon-exception reason (the consume loop logs it at WARN), NOT
        // the generic "no envelope" that other ignores get at DEBUG.
        Ignored ignored = assertInstanceOf(Ignored.class,
                parse("{ \"exception\": { \"message\": \"receive failed: not registered\" } }"));
        assertTrue(ignored.reason().startsWith(SignalEvents.DAEMON_EXCEPTION_REASON),
                "a daemon stream error must carry the daemon-exception reason prefix");
        assertTrue(ignored.reason().contains("receive failed: not registered"),
                "the daemon's error detail is kept (logged at WARN by the consume loop)");
    }

    @Test
    void aTrulyEnvelopeLessPayloadIsIgnored() {
        assertEquals("no envelope",
                assertInstanceOf(Ignored.class, parse("{ \"foo\": 1 }")).reason());
    }

    @Test
    void anEditMessageIsIgnoredWithAnEditSpecificReason() {
        // signal-cli delivers an edited message under envelope.editMessage.dataMessage (the top-level
        // dataMessage is absent), so it must be ignored EXPLICITLY as an edit — not the misleading
        // generic "no data message" the absent top-level field would otherwise produce.
        Ignored ignored = assertInstanceOf(Ignored.class, parse("""
                { "envelope": { "sourceNumber": "+15550001111",
                                "editMessage": { "targetSentTimestamp": 5,
                                                 "dataMessage": { "message": "corrected text" } } } }
                """));
        assertTrue(ignored.reason().contains("edit message"),
                "an edited message is ignored with an edit-specific reason");
    }

    @Test
    void aDataMessageLessEnvelopeIsIgnored() {
        assertEquals("no data message", assertInstanceOf(Ignored.class,
                parse("{ \"envelope\": { \"sourceNumber\": \"+15550001111\" } }")).reason());
    }

    @Test
    void aBlankTextMessageIsIgnored() {
        assertEquals("no message text", assertInstanceOf(Ignored.class,
                parse("{ \"envelope\": { \"sourceNumber\": \"+1\", "
                        + "\"dataMessage\": { \"message\": \"   \" } } }")).reason());
    }

    @Test
    void aSenderLessTextMessageIsIgnored() {
        assertEquals("no sender", assertInstanceOf(Ignored.class,
                parse("{ \"envelope\": { \"dataMessage\": { \"message\": \"orphan\" } } }")).reason());
    }
}
