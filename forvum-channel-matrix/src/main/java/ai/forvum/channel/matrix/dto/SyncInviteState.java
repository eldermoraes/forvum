package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code invite_state} of a {@link SyncInvitedRoom}: the stripped state events a pending invite
 * exposes (reusing {@link RoomEvent} — a stripped event is a subset: type, sender, state_key,
 * content). A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncInviteState(List<RoomEvent> events) {
}
