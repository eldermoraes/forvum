package ai.forvum.channel.slack;

import ai.forvum.channel.slack.dto.AckFrame;
import ai.forvum.channel.slack.dto.MessageEvent;
import ai.forvum.channel.slack.dto.SocketEnvelope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure (socket-free) Slack Socket Mode protocol logic: frame-type constants, envelope parse + ack
 * encode, and the {@link #decide} reaction function that maps an inbound {@link SocketEnvelope} to a
 * {@link Reaction} telling the endpoint what to do. Keeping this layer free of any WebSocket dependency
 * is what makes the hello, events_api-ack, and disconnect flows unit-testable without a live
 * {@code wss://} connection — the same discipline as the Discord channel's {@code GatewayProtocol}.
 *
 * <p>Unlike the Discord gateway, Socket Mode carries no client-side session state (no sequence, no
 * session id — Slack tracks delivery by per-frame {@code envelope_id}, and keep-alive is WebSocket-level
 * ping/pong handled by the transport), so {@link #decide} is a pure function of the envelope alone: no
 * state object, no IO, no lock.
 *
 * <p>{@link #decide} classifies STRUCTURALLY only (is this a message event at all?); the behavioral
 * filtering — subtype/bot/self skipping and the {@code allowedUserIds} gate — is
 * {@link SlackMessageProcessor}'s, mirroring how the Discord protocol/processor split works.
 */
public final class SlackSocketProtocol {

    private SlackSocketProtocol() {
    }

    // --- Socket Mode frame types ------------------------------------------------------------------
    /** Sent once after the connection opens: the socket is established and will receive events. */
    static final String TYPE_HELLO = "hello";
    /** An Events API delivery; MUST be acknowledged with {@code { "envelope_id": ... }} within ~3 s. */
    static final String TYPE_EVENTS_API = "events_api";
    /** Slack asks the client to drop this socket (reason: refresh/link_disabled/...) and reconnect. */
    static final String TYPE_DISCONNECT = "disconnect";

    /** The {@code payload.event.type} of a conversation message (the only event Forvum consumes). */
    static final String EVENT_MESSAGE = "message";

    /** What the endpoint should do in response to an inbound frame. A sealed, exhaustive decision. */
    public sealed interface Reaction permits Connected, Dispatch, AckOnly, Reconnect, Ignored {
    }

    /** hello arrived: the socket is live (the channel resets its reconnect backoff). */
    public record Connected() implements Reaction {
    }

    /**
     * An {@code events_api} frame carrying a message event: ack {@code envelopeId} FIRST (Slack's ~3 s
     * deadline; a turn can take far longer), then hand {@code message} to the processor.
     */
    public record Dispatch(String envelopeId, MessageEvent message) implements Reaction {
    }

    /**
     * An {@code events_api} frame Forvum does not consume (a non-message event, or no event at all):
     * it must STILL be acknowledged or Slack redelivers it and eventually disables the connection.
     */
    public record AckOnly(String envelopeId) implements Reaction {
    }

    /**
     * Slack asked us to drop this socket ({@code type: disconnect}, e.g. {@code refresh_requested} or
     * {@code link_disabled}) — close it; the channel then reconnects through a FRESH
     * {@code apps.connections.open} (a Socket Mode URL is single-use, never reused).
     */
    public record Reconnect(String reason) implements Reaction {
    }

    /** A frame Forvum does not act on (an unknown type, or an unackable malformed events_api frame). */
    public record Ignored() implements Reaction {
    }

    /** Parse a raw Socket Mode text frame into the generic {@link SocketEnvelope}. */
    public static SocketEnvelope parse(ObjectMapper mapper, String frame) {
        try {
            return mapper.readValue(frame, SocketEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unparseable Slack Socket Mode frame.", e);
        }
    }

    /**
     * Decide the reaction to an inbound frame. Pure beyond the supplied mapper — no IO, no lock, no
     * state (Socket Mode has none client-side), so it is safe to call from an inbound-frame virtual
     * thread.
     *
     * @param mapper   Jackson mapper for decoding the {@code payload.event} of a message delivery
     * @param envelope the parsed inbound frame
     */
    public static Reaction decide(ObjectMapper mapper, SocketEnvelope envelope) {
        return switch (envelope.type() == null ? "" : envelope.type()) {
            case TYPE_HELLO -> new Connected();
            case TYPE_DISCONNECT ->
                    new Reconnect(envelope.reason() == null ? "unspecified" : envelope.reason());
            case TYPE_EVENTS_API -> eventsApi(mapper, envelope);
            default -> new Ignored();
        };
    }

    /**
     * Classify an {@code events_api} frame: a {@code payload.event} of type {@code message} is decoded
     * and dispatched (the processor then filters subtype/bot/allow-list); anything else is ack-only.
     * A frame with no {@code envelope_id} cannot be acknowledged at all — dropped (out-of-contract).
     */
    private static Reaction eventsApi(ObjectMapper mapper, SocketEnvelope envelope) {
        if (envelope.envelopeId() == null || envelope.envelopeId().isBlank()) {
            return new Ignored();
        }
        JsonNode event = envelope.payload() == null ? null : envelope.payload().get("event");
        if (event == null || event.isNull() || !EVENT_MESSAGE.equals(event.path("type").asText())) {
            return new AckOnly(envelope.envelopeId());
        }
        return new Dispatch(envelope.envelopeId(), mapper.convertValue(event, MessageEvent.class));
    }

    /**
     * Encode the outbound acknowledgment frame {@code { "envelope_id": "<id>" }} for an
     * {@code events_api} delivery (the {@link AckFrame} record — the NATIVE-FRAME-trap candidate this
     * module pins with an encode test).
     */
    public static String encodeAck(ObjectMapper mapper, String envelopeId) {
        try {
            return mapper.writeValueAsString(new AckFrame(envelopeId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode Slack Socket Mode ack frame.", e);
        }
    }
}
