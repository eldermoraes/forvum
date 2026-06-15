package ai.forvum.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link WhatsAppEvents#parse} extracts inbound TEXT messages from recorded Meta webhook payloads: a
 * direct text message is returned; delivery/read statuses, non-text messages, reactions, missing bodies,
 * and malformed payloads yield an empty list (never a thrown exception). A plain unit test — pure
 * protocol, no socket.
 */
class WhatsAppEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<InboundMessage> parse(String body) {
        return WhatsAppEvents.parse(MAPPER, body);
    }

    @Test
    void aTextMessageIsExtracted() {
        List<InboundMessage> messages = parse("""
                { "object": "whatsapp_business_account",
                  "entry": [ { "id": "WABA",
                    "changes": [ { "field": "messages", "value": {
                      "messaging_product": "whatsapp",
                      "metadata": { "phone_number_id": "PNID" },
                      "contacts": [ { "wa_id": "15550001111", "profile": { "name": "Alice" } } ],
                      "messages": [ { "from": "15550001111", "id": "wamid.ABC",
                                      "timestamp": "1700000000", "type": "text",
                                      "text": { "body": "hello forvum" } } ] } } ] } ] }
                """);

        assertEquals(1, messages.size());
        InboundMessage m = messages.get(0);
        assertEquals("15550001111", m.from());
        assertEquals("hello forvum", m.text());
        assertEquals("wamid.ABC", m.messageId());
        assertEquals(1700000000L, m.timestamp());
    }

    @Test
    void multipleBatchedTextMessagesAreAllExtracted() {
        List<InboundMessage> messages = parse("""
                { "entry": [ { "changes": [ { "value": { "messages": [
                    { "from": "1", "type": "text", "text": { "body": "first" } },
                    { "from": "2", "type": "text", "text": { "body": "second" } } ] } } ] } ] }
                """);

        assertEquals(List.of("first", "second"),
                messages.stream().map(InboundMessage::text).toList(),
                "a webhook can batch several messages — all text ones are processed");
    }

    @Test
    void aStatusOnlyNotificationYieldsNothing() {
        // delivery/read receipts ride value.statuses, not value.messages.
        List<InboundMessage> messages = parse("""
                { "entry": [ { "changes": [ { "value": { "statuses": [
                    { "id": "wamid.X", "status": "delivered", "recipient_id": "15550001111" } ] } } ] } ] }
                """);
        assertTrue(messages.isEmpty(), "a status notification drives no turn");
    }

    @Test
    void aNonTextMessageIsIgnored() {
        List<InboundMessage> messages = parse("""
                { "entry": [ { "changes": [ { "value": { "messages": [
                    { "from": "1", "type": "image", "image": { "id": "media-1" } } ] } } ] } ] }
                """);
        assertTrue(messages.isEmpty(), "non-text messages are out of scope in v0.5");
    }

    @Test
    void aReactionOrEmptyBodyMessageIsIgnored() {
        assertTrue(parse("""
                { "entry": [ { "changes": [ { "value": { "messages": [
                    { "from": "1", "type": "reaction", "reaction": { "emoji": "👍" } } ] } } ] } ] }
                """).isEmpty());
        assertTrue(parse("""
                { "entry": [ { "changes": [ { "value": { "messages": [
                    { "from": "1", "type": "text", "text": { "body": "   " } } ] } } ] } ] }
                """).isEmpty(), "a blank text body is ignored");
    }

    @Test
    void malformedOrEmptyPayloadsYieldNothingNotAThrow() {
        assertTrue(parse("{ not json").isEmpty());
        assertTrue(parse("42").isEmpty());
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
        assertTrue(parse("{ \"entry\": [] }").isEmpty());
    }

    @Test
    void messagesAcrossMultipleEntryElementsAreAllExtracted() {
        // Meta batches notifications across multiple entry[] elements (one per WABA event group), not
        // just multiple messages in one change — the OUTER loop must be exercised at n>1.
        List<InboundMessage> messages = parse("""
                { "entry": [
                    { "changes": [ { "value": { "messages": [
                        { "from": "1", "type": "text", "text": { "body": "from entry A" } } ] } } ] },
                    { "changes": [ { "value": { "messages": [
                        { "from": "2", "type": "text", "text": { "body": "from entry B" } } ] } } ] } ] }
                """);

        assertEquals(List.of("from entry A", "from entry B"),
                messages.stream().map(InboundMessage::text).toList());
    }

    @Test
    void aMixedBatchExtractsOnlyTheTextMessageAlongsideAStatusAndANonTextMessage() {
        // The realistic payload interleaves a text message with a status receipt and a non-text message
        // in the same value — only the text one drives a turn.
        List<InboundMessage> messages = parse("""
                { "entry": [ { "changes": [ { "value": {
                    "statuses": [ { "id": "wamid.S", "status": "read" } ],
                    "messages": [
                        { "from": "1", "type": "image", "image": { "id": "m" } },
                        { "from": "2", "type": "text", "text": { "body": "the real one" } } ] } } ] } ] }
                """);

        assertEquals(1, messages.size());
        assertEquals("the real one", messages.get(0).text());
        assertEquals("2", messages.get(0).from());
    }
}
