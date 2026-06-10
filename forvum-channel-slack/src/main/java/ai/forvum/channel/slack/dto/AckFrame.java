package ai.forvum.channel.slack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The OUTBOUND Socket Mode acknowledgment frame: {@code { "envelope_id": "<id>" }}, echoing an
 * {@code events_api} envelope's id. Slack expects the ack within ~3 seconds or it redelivers the event
 * (and eventually drops the app), so the endpoint sends it BEFORE driving the turn.
 *
 * <p>Carries {@code @RegisterForReflection} because EVERY ack is serialized through this record via
 * {@code ObjectMapper.writeValueAsString}; without the hint the native binary cannot reflect its
 * accessor and emits an empty/malformed frame, so every event is redelivered and the channel
 * silently degrades — and the CI no-token native smoke never serializes a frame (no token → no
 * connection), so it cannot catch the omission. Hence the explicit annotation + the encode-path test
 * (the Discord NATIVE-FRAME lesson, CLAUDE.md §14 [P2-CH/discord]).
 */
@RegisterForReflection
public record AckFrame(@JsonProperty("envelope_id") String envelopeId) {
}
