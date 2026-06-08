package ai.forvum.channel.discord;

import ai.forvum.channel.discord.dto.GatewayPayload;
import ai.forvum.channel.discord.dto.Hello;
import ai.forvum.channel.discord.dto.Identify;
import ai.forvum.channel.discord.dto.MessageCreate;
import ai.forvum.channel.discord.dto.Ready;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Pure (socket-free) Discord Gateway v10 protocol logic: opcode/intent constants, frame parse + encode,
 * and the {@link #decide} reaction function that maps an inbound {@link GatewayPayload} (plus the current
 * {@link GatewayState}) to a {@link Reaction} telling the endpoint what to do. Keeping this layer free of
 * any WebSocket dependency is what makes the HELLO→IDENTIFY, heartbeat, and MESSAGE_CREATE flows
 * unit-testable without a live {@code wss://} connection (the P2-CH testability requirement).
 *
 * <p>{@link #decide} only reads/updates {@link GatewayState} (atomics) and returns a value; it performs no
 * IO and takes no lock, so it is safe to call from an inbound-frame virtual thread.
 */
public final class GatewayProtocol {

    private GatewayProtocol() {
    }

    // --- Gateway opcodes (Discord Gateway v10) ---------------------------------------------------
    public static final int OP_DISPATCH = 0;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_RECONNECT = 7;
    public static final int OP_INVALID_SESSION = 9;
    public static final int OP_HELLO = 10;
    public static final int OP_HEARTBEAT_ACK = 11;

    // --- Gateway intents (bit flags) -------------------------------------------------------------
    /** {@code GUILD_MESSAGES} (1 << 9): MESSAGE_CREATE in guild text channels. */
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;
    /** {@code DIRECT_MESSAGES} (1 << 12): MESSAGE_CREATE in DMs to the bot. */
    public static final int INTENT_DIRECT_MESSAGES = 1 << 12;
    /** {@code MESSAGE_CONTENT} (1 << 15): the privileged intent that populates {@code content}. */
    public static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    /** The intent bitmask Forvum sends in IDENTIFY: guild + DM messages, with message content. */
    public static final int FORVUM_INTENTS =
            INTENT_GUILD_MESSAGES | INTENT_DIRECT_MESSAGES | INTENT_MESSAGE_CONTENT;

    // --- Dispatch event names (op 0) -------------------------------------------------------------
    static final String EVENT_READY = "READY";
    static final String EVENT_MESSAGE_CREATE = "MESSAGE_CREATE";

    /** What the endpoint should do in response to an inbound frame. A sealed, exhaustive decision. */
    public sealed interface Reaction
            permits SendIdentify, MessageReceived, Reconnect, ReIdentify, Acknowledged, Ignored {
    }

    /** HELLO arrived: send IDENTIFY and arm the heartbeat loop at {@code heartbeatIntervalMillis}. */
    public record SendIdentify(long heartbeatIntervalMillis) implements Reaction {
    }

    /** A MESSAGE_CREATE dispatch decoded into its payload — the endpoint drives a turn from it. */
    public record MessageReceived(MessageCreate message) implements Reaction {
    }

    /** Gateway asked us to reconnect (op 7) — close and re-open, resuming if a session exists. */
    public record Reconnect() implements Reaction {
    }

    /**
     * Session invalidated (op 9). {@code resumable} mirrors the boolean {@code d} payload: when false the
     * client must start a fresh session (re-IDENTIFY) after a short randomized delay.
     */
    public record ReIdentify(boolean resumable) implements Reaction {
    }

    /** A benign frame that updated state but needs no outbound action (e.g. HEARTBEAT_ACK, READY). */
    public record Acknowledged() implements Reaction {
    }

    /** A frame Forvum does not act on (an unhandled dispatch, a bot/self/non-text message, etc.). */
    public record Ignored() implements Reaction {
    }

    /** Parse a raw gateway text frame into the generic {@link GatewayPayload} envelope. */
    public static GatewayPayload parse(ObjectMapper mapper, String frame) {
        try {
            return mapper.readValue(frame, GatewayPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unparseable Discord gateway frame.", e);
        }
    }

    /**
     * Decide the reaction to an inbound frame, updating {@code state} (sequence, session) as a side
     * effect. Pure beyond the atomic state mutation and the supplied mapper — no IO, no lock.
     *
     * @param mapper  Jackson mapper for decoding the opcode-specific {@code d} payload
     * @param payload the parsed inbound frame
     * @param state   the gateway state (updated in place via atomics)
     */
    public static Reaction decide(ObjectMapper mapper, GatewayPayload payload, GatewayState state) {
        if (payload.s() != null) {
            state.setLastSequence(payload.s());
        }
        return switch (payload.op()) {
            case OP_HELLO -> {
                Hello hello = mapper.convertValue(payload.d(), Hello.class);
                yield new SendIdentify(hello.heartbeatInterval());
            }
            case OP_DISPATCH -> dispatch(mapper, payload);
            case OP_RECONNECT -> new Reconnect();
            case OP_INVALID_SESSION -> {
                boolean resumable = payload.d() != null && payload.d().asBoolean(false);
                if (!resumable) {
                    state.reset();
                }
                yield new ReIdentify(resumable);
            }
            case OP_HEARTBEAT_ACK -> new Acknowledged();
            // op 1 (the gateway asking US to heartbeat immediately) and any unknown op: no-op.
            default -> new Ignored();
        };
    }

    /** Decode a DISPATCH (op 0) by its {@code t} event name. Only READY + MESSAGE_CREATE are consumed. */
    private static Reaction dispatch(ObjectMapper mapper, GatewayPayload payload) {
        return switch (payload.t() == null ? "" : payload.t()) {
            case EVENT_MESSAGE_CREATE ->
                    new MessageReceived(mapper.convertValue(payload.d(), MessageCreate.class));
            // READY's session/resume capture is done by the endpoint (it needs the typed Ready); here we
            // simply acknowledge so the seq is recorded.
            case EVENT_READY -> new Acknowledged();
            default -> new Ignored();
        };
    }

    /** Decode the READY payload (session_id + resume_gateway_url) from a DISPATCH frame's {@code d}. */
    public static Ready readyOf(ObjectMapper mapper, GatewayPayload payload) {
        return mapper.convertValue(payload.d(), Ready.class);
    }

    /** Encode the {@code op 2} IDENTIFY frame for {@code token} with Forvum's intents. */
    public static String encodeIdentify(ObjectMapper mapper, String token) {
        return encodeFrame(mapper, OP_IDENTIFY, Identify.of(token, FORVUM_INTENTS));
    }

    /**
     * Encode an {@code op 1} HEARTBEAT frame whose {@code d} is the last sequence number, or JSON
     * {@code null} when none has been seen yet ({@code { "op": 1, "d": null }}).
     */
    public static String encodeHeartbeat(ObjectMapper mapper, Optional<Long> lastSequence) {
        return encodeFrame(mapper, OP_HEARTBEAT, lastSequence.orElse(null));
    }

    /** Encode a gateway frame {@code { "op": <op>, "d": <data> }} (the only fields a client sends). */
    static String encodeFrame(ObjectMapper mapper, int op, Object data) {
        try {
            // A two-field outbound envelope: op + d. (s/t are gateway→client only.)
            return mapper.writeValueAsString(new OutboundFrame(op, data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode Discord gateway frame.", e);
        }
    }

    /**
     * Minimal outbound frame the client emits: {@code { "op": ..., "d": ... }}. Carries
     * {@code @RegisterForReflection} because EVERY outbound frame (IDENTIFY op 2, HEARTBEAT op 1) is
     * serialized through this record via {@link ObjectMapper#writeValueAsString}; without the hint the
     * native binary cannot reflect its accessors and emits a malformed/empty frame, so the gateway
     * handshake fails. The CI no-token native smoke never serializes a frame (no token → no IDENTIFY),
     * so it cannot catch the omission — hence the explicit annotation + the encode-path tests.
     */
    @io.quarkus.runtime.annotations.RegisterForReflection
    record OutboundFrame(int op, Object d) {
    }
}
