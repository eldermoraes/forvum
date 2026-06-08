package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.discord.GatewayProtocol.Acknowledged;
import ai.forvum.channel.discord.GatewayProtocol.Ignored;
import ai.forvum.channel.discord.GatewayProtocol.MessageReceived;
import ai.forvum.channel.discord.GatewayProtocol.Reaction;
import ai.forvum.channel.discord.GatewayProtocol.Reconnect;
import ai.forvum.channel.discord.GatewayProtocol.ReIdentify;
import ai.forvum.channel.discord.GatewayProtocol.SendIdentify;
import ai.forvum.channel.discord.dto.GatewayPayload;
import ai.forvum.channel.discord.dto.MessageCreate;
import ai.forvum.channel.discord.dto.Ready;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Socket-free unit tests for the Discord Gateway v10 opcode flow: HELLO → IDENTIFY + heartbeat arming,
 * the heartbeat carrying the last sequence, MESSAGE_CREATE decoding, READY capture, and the
 * RECONNECT/INVALID_SESSION reactions. These exercise the pure {@link GatewayProtocol} + {@link
 * GatewayState} without a live {@code wss://} connection (the P2-CH testability requirement).
 */
class GatewayProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GatewayPayload parse(String frame) {
        return GatewayProtocol.parse(MAPPER, frame);
    }

    @Test
    void helloYieldsSendIdentifyWithTheHeartbeatInterval() {
        GatewayState state = new GatewayState();
        Reaction reaction = GatewayProtocol.decide(
                MAPPER, parse("{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}"), state);

        SendIdentify identify = assertInstanceOf(SendIdentify.class, reaction);
        assertEquals(41250L, identify.heartbeatIntervalMillis(), "HELLO arms the heartbeat at its interval");
    }

    @Test
    void identifyFrameCarriesTokenAndForvumIntents() throws Exception {
        String frame = GatewayProtocol.encodeIdentify(MAPPER, "secret-token");
        JsonNode root = MAPPER.readTree(frame);

        assertEquals(GatewayProtocol.OP_IDENTIFY, root.get("op").asInt());
        assertEquals("secret-token", root.get("d").get("token").asText());
        // GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
        int expected = (1 << 9) | (1 << 12) | (1 << 15);
        assertEquals(expected, root.get("d").get("intents").asInt(), "Forvum requests message-content intent");
    }

    @Test
    void identifyOutboundFrameIsWellFormedWithOpcode2AndAnObjectPayload() throws Exception {
        // Pins the outbound-frame envelope (the OutboundFrame record carrying @RegisterForReflection): in a
        // native binary an un-hinted record serializes to an empty/malformed frame, so the gateway handshake
        // fails — and the CI no-token native smoke never serializes a frame, so it cannot catch it. This test
        // asserts the IDENTIFY encode path produces { "op": 2, "d": {...} } with the IDENTIFY payload.
        JsonNode root = MAPPER.readTree(GatewayProtocol.encodeIdentify(MAPPER, "secret-token"));

        assertTrue(root.has("op"), "the outbound frame carries an \"op\" field");
        assertTrue(root.has("d"), "the outbound frame carries a \"d\" field");
        assertEquals(2, root.get("op").asInt(), "IDENTIFY is opcode 2");
        assertEquals(GatewayProtocol.OP_IDENTIFY, root.get("op").asInt());
        assertTrue(root.get("d").isObject(), "IDENTIFY's \"d\" is the (object) Identify payload, not empty");
        assertEquals("secret-token", root.get("d").get("token").asText(), "the IDENTIFY token round-trips");
    }

    @Test
    void heartbeatOutboundFrameIsWellFormedWithOpcode1() throws Exception {
        // The HEARTBEAT encode path through the same OutboundFrame record: { "op": 1, "d": <seq|null> }.
        JsonNode withSeq = MAPPER.readTree(GatewayProtocol.encodeHeartbeat(MAPPER, Optional.of(99L)));
        assertTrue(withSeq.has("op"), "the heartbeat frame carries an \"op\" field");
        assertTrue(withSeq.has("d"), "the heartbeat frame carries a \"d\" field");
        assertEquals(1, withSeq.get("op").asInt(), "HEARTBEAT is opcode 1");
        assertEquals(GatewayProtocol.OP_HEARTBEAT, withSeq.get("op").asInt());
        assertEquals(99L, withSeq.get("d").asLong(), "the heartbeat carries the supplied sequence");

        JsonNode noSeq = MAPPER.readTree(GatewayProtocol.encodeHeartbeat(MAPPER, Optional.empty()));
        assertEquals(1, noSeq.get("op").asInt(), "HEARTBEAT is opcode 1 even before any dispatch");
        assertTrue(noSeq.get("d").isNull(), "a heartbeat before any dispatch sends d:null");
    }

    @Test
    void heartbeatCarriesTheLastSequenceOnceADispatchHasBeenSeen() throws Exception {
        GatewayState state = new GatewayState();
        // A DISPATCH with s=7 records the sequence (via decide's side effect).
        GatewayProtocol.decide(MAPPER, parse("{\"op\":0,\"t\":\"TYPING_START\",\"s\":7,\"d\":{}}"), state);
        assertEquals(Optional.of(7L), state.lastSequence());

        JsonNode hb = MAPPER.readTree(GatewayProtocol.encodeHeartbeat(MAPPER, state.lastSequence()));
        assertEquals(GatewayProtocol.OP_HEARTBEAT, hb.get("op").asInt());
        assertEquals(7L, hb.get("d").asLong(), "the heartbeat echoes the last seen sequence");
    }

    @Test
    void heartbeatCarriesNullSequenceBeforeAnyDispatch() throws Exception {
        GatewayState state = new GatewayState();
        assertTrue(state.lastSequence().isEmpty(), "no sequence before the first dispatch");

        JsonNode hb = MAPPER.readTree(GatewayProtocol.encodeHeartbeat(MAPPER, state.lastSequence()));
        assertTrue(hb.get("d").isNull(), "a heartbeat before any dispatch sends d:null");
    }

    @Test
    void messageCreateDispatchDecodesToTheMessagePayload() {
        GatewayState state = new GatewayState();
        Reaction reaction = GatewayProtocol.decide(MAPPER, parse(
                "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"s\":12,\"d\":{"
                        + "\"channel_id\":\"555\",\"content\":\"hello\","
                        + "\"author\":{\"id\":\"42\",\"bot\":false}}}"), state);

        MessageReceived received = assertInstanceOf(MessageReceived.class, reaction);
        MessageCreate msg = received.message();
        assertEquals("555", msg.channelId());
        assertEquals("hello", msg.content());
        assertEquals("42", msg.author().id());
        assertFalse(msg.author().bot());
        assertEquals(Optional.of(12L), state.lastSequence(), "the dispatch sequence was recorded");
    }

    @Test
    void readyDispatchAcknowledgesAndItsPayloadCarriesSessionAndResumeUrl() {
        GatewayState state = new GatewayState();
        GatewayPayload payload = parse(
                "{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{"
                        + "\"session_id\":\"abc123\",\"resume_gateway_url\":\"wss://resume.example\"}}");

        Reaction reaction = GatewayProtocol.decide(MAPPER, payload, state);
        assertInstanceOf(Acknowledged.class, reaction, "READY is acknowledged; the endpoint captures its data");

        Ready ready = GatewayProtocol.readyOf(MAPPER, payload);
        assertEquals("abc123", ready.sessionId());
        assertEquals("wss://resume.example", ready.resumeGatewayUrl());
    }

    @Test
    void reconnectOpcodeYieldsReconnect() {
        Reaction reaction =
                GatewayProtocol.decide(MAPPER, parse("{\"op\":7,\"d\":null}"), new GatewayState());
        assertInstanceOf(Reconnect.class, reaction);
    }

    @Test
    void invalidSessionResumableTrueKeepsTheSession() {
        GatewayState state = new GatewayState();
        state.onReady("sess", "wss://resume");
        state.setLastSequence(9);

        Reaction reaction = GatewayProtocol.decide(MAPPER, parse("{\"op\":9,\"d\":true}"), state);

        ReIdentify reIdentify = assertInstanceOf(ReIdentify.class, reaction);
        assertTrue(reIdentify.resumable(), "a resumable invalid session keeps the session for RESUME");
        assertEquals(Optional.of("sess"), state.sessionId(), "the session is retained when resumable");
    }

    @Test
    void invalidSessionResumableFalseResetsTheSession() {
        GatewayState state = new GatewayState();
        state.onReady("sess", "wss://resume");
        state.setLastSequence(9);

        Reaction reaction = GatewayProtocol.decide(MAPPER, parse("{\"op\":9,\"d\":false}"), state);

        ReIdentify reIdentify = assertInstanceOf(ReIdentify.class, reaction);
        assertFalse(reIdentify.resumable(), "a non-resumable invalid session forces a fresh IDENTIFY");
        assertTrue(state.sessionId().isEmpty(), "the session is dropped when not resumable");
        assertTrue(state.lastSequence().isEmpty(), "the sequence is reset with the session");
    }

    @Test
    void heartbeatAckIsAcknowledged() {
        Reaction reaction =
                GatewayProtocol.decide(MAPPER, parse("{\"op\":11,\"d\":null}"), new GatewayState());
        assertInstanceOf(Acknowledged.class, reaction);
    }

    @Test
    void anUnhandledDispatchIsIgnored() {
        Reaction reaction = GatewayProtocol.decide(
                MAPPER, parse("{\"op\":0,\"t\":\"PRESENCE_UPDATE\",\"s\":3,\"d\":{}}"), new GatewayState());
        assertInstanceOf(Ignored.class, reaction);
    }
}
