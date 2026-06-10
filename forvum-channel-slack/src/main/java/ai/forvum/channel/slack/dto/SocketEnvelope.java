package ai.forvum.channel.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Slack Socket Mode inbound frame envelope (every Socket Mode message has this shape):
 *
 * <ul>
 *   <li>{@code type} — the frame type Forvum handles: {@code "hello"} (connection established),
 *       {@code "events_api"} (an Events API delivery that MUST be acknowledged within ~3 s), and
 *       {@code "disconnect"} (Slack asks the client to drop this socket and open a fresh one).</li>
 *   <li>{@code envelope_id} — present on acknowledgeable frames ({@code events_api}); echoed back as
 *       {@code { "envelope_id": ... }} (see {@link AckFrame}) to acknowledge the delivery. Nullable.</li>
 *   <li>{@code payload} — the type-specific payload, kept as a raw {@link JsonNode} so the envelope is
 *       generic; for {@code events_api} the inner {@code payload.event} is decoded into
 *       {@link MessageEvent} only when it is a message event.</li>
 *   <li>{@code reason} — present on {@code disconnect} frames (e.g. {@code refresh_requested},
 *       {@code link_disabled}). Nullable.</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} (frames also carry {@code debug_info},
 * {@code retry_attempt}, {@code accepts_response_payload}, etc., none of which Forvum consumes).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SocketEnvelope(String type,
                             @JsonProperty("envelope_id") String envelopeId,
                             JsonNode payload,
                             String reason) {
}
