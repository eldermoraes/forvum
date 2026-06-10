package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code timeline} of a {@link SyncJoinedRoom}: the room's new events since the last sync.
 * {@code limited}/{@code prev_batch} (history pagination) are not consumed — Forvum reacts to live
 * messages only and never replays history. A real {@code RegisterForReflection} (Quarkus-bearing
 * Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncTimeline(List<RoomEvent> events) {
}
