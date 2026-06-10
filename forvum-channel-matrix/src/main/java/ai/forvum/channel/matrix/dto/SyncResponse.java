package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Matrix client-server API v3 {@code GET /_matrix/client/v3/sync} response envelope. Forvum
 * consumes {@code next_batch} (the token the NEXT sync passes as {@code since}, acknowledging this
 * batch) and {@code rooms} (joined-room timelines + pending invites). A real
 * {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} so the many unconsumed sync sections
 * (presence, account_data, to_device, ...) are dropped.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncResponse(@JsonProperty("next_batch") String nextBatch, SyncRooms rooms) {
}
