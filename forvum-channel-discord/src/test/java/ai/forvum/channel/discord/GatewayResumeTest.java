package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.discord.GatewayProtocol.Acknowledged;
import ai.forvum.channel.discord.GatewayProtocol.Reaction;
import ai.forvum.channel.discord.GatewayProtocol.SendIdentify;
import ai.forvum.channel.discord.GatewayProtocol.SendResume;
import ai.forvum.channel.discord.dto.GatewayPayload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Socket-free unit tests for the op-6 RESUME flow (the follow-up the P2-CH lesson named): the resume
 * context captured at READY makes the next HELLO yield {@code SendResume} instead of
 * {@code SendIdentify}; a non-resumable INVALID_SESSION ({@code op 9, d=false}) resets the context so
 * the next HELLO falls back to a fresh IDENTIFY; the {@code RESUMED} dispatch is acknowledged (the
 * endpoint resets the backoff on it); and the op-6 frame encodes {@code token}/{@code session_id}/
 * {@code seq} — the outbound NATIVE-FRAME pin, like IDENTIFY/HEARTBEAT.
 */
class GatewayResumeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HELLO_FRAME = "{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}";

    private static GatewayPayload parse(String frame) {
        return GatewayProtocol.parse(MAPPER, frame);
    }

    /** A state as it stands after a healthy session: READY captured + a dispatch sequence seen. */
    private static GatewayState resumableState() {
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://resume.example");
        state.setLastSequence(42);
        return state;
    }

    @Test
    void aFreshStateCannotResume() {
        assertFalse(new GatewayState().canResume(), "nothing captured yet — only IDENTIFY is possible");
    }

    @Test
    void aReadyWithoutAnyDispatchSequenceCannotResume() {
        GatewayState state = new GatewayState();
        state.onReady("sess-1", "wss://resume.example");

        assertFalse(state.canResume(), "RESUME requires a last seq; READY alone is not enough");
    }

    @Test
    void aReadyPlusASequenceCanResume() {
        assertTrue(resumableState().canResume());
    }

    @Test
    void resetDropsTheResumeContext() {
        GatewayState state = resumableState();
        state.reset();

        assertFalse(state.canResume(), "a non-resumable INVALID_SESSION must force a fresh IDENTIFY");
    }

    @Test
    void helloWithAResumableSessionYieldsSendResumeWithTheHeartbeatInterval() {
        Reaction reaction = GatewayProtocol.decide(MAPPER, parse(HELLO_FRAME), resumableState());

        SendResume resume = assertInstanceOf(SendResume.class, reaction,
                "a captured session continues via RESUME, not a fresh IDENTIFY");
        assertEquals(41250L, resume.heartbeatIntervalMillis(),
                "the heartbeat is armed from HELLO exactly as on the IDENTIFY path");
    }

    @Test
    void helloWithoutAResumableSessionYieldsSendIdentify() {
        Reaction reaction = GatewayProtocol.decide(MAPPER, parse(HELLO_FRAME), new GatewayState());

        assertInstanceOf(SendIdentify.class, reaction);
    }

    @Test
    void helloAfterANonResumableInvalidSessionFallsBackToIdentify() {
        GatewayState state = resumableState();
        // op 9 d=false: the server refused the session — decide() resets the state...
        GatewayProtocol.decide(MAPPER, parse("{\"op\":9,\"d\":false}"), state);
        // ...so the HELLO of the NEXT connection opens a fresh session.
        Reaction reaction = GatewayProtocol.decide(MAPPER, parse(HELLO_FRAME), state);

        assertInstanceOf(SendIdentify.class, reaction,
                "a failed/refused resume must fall back to a fresh IDENTIFY");
    }

    @Test
    void helloAfterAResumableInvalidSessionStillResumes() {
        GatewayState state = resumableState();
        // op 9 d=true keeps the session; after the reconnect the client may RESUME it.
        GatewayProtocol.decide(MAPPER, parse("{\"op\":9,\"d\":true}"), state);

        assertInstanceOf(SendResume.class,
                GatewayProtocol.decide(MAPPER, parse(HELLO_FRAME), state));
    }

    @Test
    void resumedDispatchIsAcknowledged() {
        GatewayState state = resumableState();
        Reaction reaction = GatewayProtocol.decide(
                MAPPER, parse("{\"op\":0,\"t\":\"RESUMED\",\"s\":43,\"d\":null}"), state);

        assertInstanceOf(Acknowledged.class, reaction,
                "RESUMED needs no outbound action; the endpoint resets the backoff on it");
        assertEquals(43L, state.lastSequence().orElseThrow(), "the RESUMED sequence is recorded");
    }

    @Test
    void resumeOutboundFrameIsWellFormedWithOpcode6TokenSessionAndSeq() throws Exception {
        // Pins the op-6 outbound frame (the Resume record carrying @RegisterForReflection): in a native
        // binary an un-hinted record serializes to an empty/malformed frame, so the resume handshake
        // silently fails — and the CI no-token native smoke never serializes a frame, so it cannot catch
        // it (the same NATIVE-FRAME trap as IDENTIFY/HEARTBEAT).
        JsonNode root = MAPPER.readTree(
                GatewayProtocol.encodeResume(MAPPER, "secret-token", "sess-1", 42));

        assertTrue(root.has("op"), "the outbound frame carries an \"op\" field");
        assertTrue(root.has("d"), "the outbound frame carries a \"d\" field");
        assertEquals(6, root.get("op").asInt(), "RESUME is opcode 6");
        assertEquals(GatewayProtocol.OP_RESUME, root.get("op").asInt());
        assertEquals("secret-token", root.get("d").get("token").asText(), "the token rides the frame");
        assertEquals("sess-1", root.get("d").get("session_id").asText(),
                "the captured session id rides as session_id");
        assertEquals(42L, root.get("d").get("seq").asLong(), "the last sequence rides as seq");
    }
}
