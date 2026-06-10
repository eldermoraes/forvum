package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One joined room in a Matrix {@link SyncResponse} ({@code rooms.join.<roomId>}). Forvum consumes only
 * the {@code timeline} (new message events); state, ephemeral events, and notification counts are
 * dropped. A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncJoinedRoom(SyncTimeline timeline) {
}
