package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One pending invite in a Matrix {@link SyncResponse} ({@code rooms.invite.<roomId>}): the
 * {@code invite_state} carries the stripped state events, among them the {@code m.room.member} event
 * (membership {@code invite}) whose {@code sender} is the inviter Forvum gates auto-join on. A real
 * {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncInvitedRoom(@JsonProperty("invite_state") SyncInviteState inviteState) {
}
