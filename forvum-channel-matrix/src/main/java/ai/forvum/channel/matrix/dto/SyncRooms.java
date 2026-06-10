package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * The {@code rooms} section of a Matrix {@link SyncResponse}: {@code join} maps a room id to its
 * {@link SyncJoinedRoom} (new timeline events), {@code invite} maps a room id to its
 * {@link SyncInvitedRoom} (a pending invite's stripped state). The {@code leave}/{@code knock}
 * sections are not consumed. A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module)
 * plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncRooms(Map<String, SyncJoinedRoom> join, Map<String, SyncInvitedRoom> invite) {
}
