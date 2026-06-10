package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.slack.SlackSocketProtocol.AckOnly;
import ai.forvum.channel.slack.SlackSocketProtocol.Connected;
import ai.forvum.channel.slack.SlackSocketProtocol.Dispatch;
import ai.forvum.channel.slack.SlackSocketProtocol.Ignored;
import ai.forvum.channel.slack.SlackSocketProtocol.Reaction;
import ai.forvum.channel.slack.SlackSocketProtocol.Reconnect;
import ai.forvum.channel.slack.dto.ChatPostMessage;
import ai.forvum.channel.slack.dto.MessageEvent;
import ai.forvum.channel.slack.dto.SocketEnvelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Socket-free unit tests for the Slack Socket Mode frame flow over recorded frame fixtures: hello,
 * events_api message decoding (incl. thread_ts/bot_id/subtype pass-through), the ack-only arm for
 * non-message events, disconnect → reconnect, and unknown frames. These exercise the pure
 * {@link SlackSocketProtocol} without a live {@code wss://} connection (the P2-CH testability
 * requirement, mirroring the Discord channel's {@code GatewayProtocolTest}).
 */
class SlackSocketProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Reaction decide(String frame) {
        return SlackSocketProtocol.decide(MAPPER, SlackSocketProtocol.parse(MAPPER, frame));
    }

    @Test
    void helloYieldsConnected() {
        Reaction reaction = decide(
                "{\"type\":\"hello\",\"num_connections\":1,"
                        + "\"debug_info\":{\"host\":\"applink-1\",\"approximate_connection_time\":18060},"
                        + "\"connection_info\":{\"app_id\":\"A111\"}}");

        assertInstanceOf(Connected.class, reaction, "hello establishes the socket");
    }

    @Test
    void eventsApiMessageDecodesToADispatchWithItsEnvelopeId() {
        Reaction reaction = decide(
                "{\"envelope_id\":\"e-1\",\"type\":\"events_api\",\"accepts_response_payload\":false,"
                        + "\"retry_attempt\":0,\"retry_reason\":\"\",\"payload\":{"
                        + "\"token\":\"verif\",\"team_id\":\"T1\",\"api_app_id\":\"A1\","
                        + "\"type\":\"event_callback\",\"event\":{"
                        + "\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U42\","
                        + "\"text\":\"hello\",\"ts\":\"1717.0001\",\"channel_type\":\"channel\"}}}");

        Dispatch dispatch = assertInstanceOf(Dispatch.class, reaction);
        assertEquals("e-1", dispatch.envelopeId(), "the envelope id is what the ack must echo");
        MessageEvent message = dispatch.message();
        assertEquals("C123", message.channel());
        assertEquals("U42", message.user());
        assertEquals("hello", message.text());
        assertNull(message.subtype(), "a plain user message has no subtype");
        assertNull(message.botId(), "a human message has no bot_id");
        assertNull(message.threadTs(), "a top-level message has no thread_ts");
    }

    @Test
    void eventsApiThreadedMessageCarriesItsThreadTs() {
        Reaction reaction = decide(
                "{\"envelope_id\":\"e-2\",\"type\":\"events_api\",\"payload\":{\"event\":{"
                        + "\"type\":\"message\",\"channel\":\"C123\",\"user\":\"U42\","
                        + "\"text\":\"in thread\",\"ts\":\"1717.0002\",\"thread_ts\":\"1717.0001\"}}}");

        Dispatch dispatch = assertInstanceOf(Dispatch.class, reaction);
        assertEquals("1717.0001", dispatch.message().threadTs(),
                "thread_ts is decoded so the threaded-reply follow-up is non-breaking");
    }

    @Test
    void eventsApiBotOrSubtypedMessageStillDispatchesWithItsMarkers() {
        // decide() classifies STRUCTURALLY; the behavioral bot/subtype filtering is the processor's.
        // The protocol's job is to surface the markers intact.
        Reaction reaction = decide(
                "{\"envelope_id\":\"e-3\",\"type\":\"events_api\",\"payload\":{\"event\":{"
                        + "\"type\":\"message\",\"subtype\":\"bot_message\",\"bot_id\":\"B9\","
                        + "\"channel\":\"C123\",\"text\":\"I am a bot\",\"ts\":\"1717.0003\"}}}");

        Dispatch dispatch = assertInstanceOf(Dispatch.class, reaction);
        assertEquals("bot_message", dispatch.message().subtype());
        assertEquals("B9", dispatch.message().botId());
        assertNull(dispatch.message().user(), "a bot message carries no user");
    }

    @Test
    void eventsApiNonMessageEventIsAckOnly() {
        Reaction reaction = decide(
                "{\"envelope_id\":\"e-4\",\"type\":\"events_api\",\"payload\":{\"event\":{"
                        + "\"type\":\"reaction_added\",\"user\":\"U42\",\"reaction\":\"thumbsup\"}}}");

        AckOnly ackOnly = assertInstanceOf(AckOnly.class, reaction,
                "a non-message event must STILL be acked or Slack redelivers it");
        assertEquals("e-4", ackOnly.envelopeId());
    }

    @Test
    void eventsApiWithNoEventAtAllIsAckOnly() {
        Reaction reaction = decide("{\"envelope_id\":\"e-5\",\"type\":\"events_api\",\"payload\":{}}");

        assertEquals("e-5", assertInstanceOf(AckOnly.class, reaction).envelopeId());
    }

    @Test
    void eventsApiWithoutAnEnvelopeIdIsIgnored() {
        // Out-of-contract: an events_api frame without an envelope_id cannot be acknowledged at all.
        Reaction reaction = decide(
                "{\"type\":\"events_api\",\"payload\":{\"event\":{\"type\":\"message\","
                        + "\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"x\"}}}");

        assertInstanceOf(Ignored.class, reaction);
    }

    @Test
    void disconnectYieldsReconnectWithItsReason() {
        Reaction reaction = decide(
                "{\"type\":\"disconnect\",\"reason\":\"refresh_requested\","
                        + "\"debug_info\":{\"host\":\"applink-1\"}}");

        Reconnect reconnect = assertInstanceOf(Reconnect.class, reaction);
        assertEquals("refresh_requested", reconnect.reason());
    }

    @Test
    void disconnectWithoutAReasonYieldsReconnect() {
        Reconnect reconnect = assertInstanceOf(Reconnect.class, decide("{\"type\":\"disconnect\"}"));
        assertEquals("unspecified", reconnect.reason());
    }

    @Test
    void anUnknownFrameTypeIsIgnored() {
        assertInstanceOf(Ignored.class, decide("{\"type\":\"slash_commands\",\"envelope_id\":\"e-9\"}"));
        assertInstanceOf(Ignored.class, decide("{\"no_type_at_all\":true}"));
    }

    @Test
    void anUnparseableFrameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SlackSocketProtocol.parse(MAPPER, "this is not json"));
    }

    @Test
    void ackOutboundFrameIsWellFormedWithItsEnvelopeId() throws Exception {
        // Pins the outbound ack envelope (the AckFrame record carrying @RegisterForReflection): in a
        // native binary an un-hinted record serializes to an empty/malformed frame, so every events_api
        // delivery is redelivered and the channel silently degrades — and the CI no-token native smoke
        // never serializes a frame, so it cannot catch it (the Discord NATIVE-FRAME lesson). This test
        // asserts the encode path produces { "envelope_id": "<id>" }.
        JsonNode root = MAPPER.readTree(SlackSocketProtocol.encodeAck(MAPPER, "e-1"));

        assertTrue(root.has("envelope_id"), "the ack frame carries an \"envelope_id\" field");
        assertEquals("e-1", root.get("envelope_id").asText(), "the envelope id round-trips");
        assertEquals(1, root.size(), "the ack frame is exactly { \"envelope_id\": ... }");
    }

    @Test
    void chatPostMessageBodyEncodesChannelAndText() throws Exception {
        // The OUTBOUND REST body record (same NATIVE-FRAME discipline as the ack frame): the reply path
        // serializes ChatPostMessage on every send, so pin its JSON shape.
        JsonNode root = MAPPER.readTree(
                MAPPER.writeValueAsString(new ChatPostMessage("C123", "hello back")));

        assertEquals("C123", root.get("channel").asText());
        assertEquals("hello back", root.get("text").asText());
    }

    @Test
    void parseToleratesUnknownEnvelopeFields() {
        SocketEnvelope envelope = SlackSocketProtocol.parse(MAPPER,
                "{\"type\":\"hello\",\"some_future_field\":{\"nested\":true}}");

        assertEquals("hello", envelope.type());
    }
}
