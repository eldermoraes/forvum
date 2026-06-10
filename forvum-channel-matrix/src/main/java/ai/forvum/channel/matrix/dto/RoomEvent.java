package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One Matrix room event, used for both a {@link SyncTimeline} timeline event and a
 * {@link SyncInviteState} stripped state event (the stripped form is a subset of the full form).
 * Forvum consumes {@code type} ({@code m.room.message} for messages, {@code m.room.member} for
 * invites), {@code sender} (the authoring user id, matched against {@code allowedUserIds}),
 * {@code state_key} (the invited user for a member event; {@code null} on a message event), and
 * {@code content}. A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomEvent(String type,
                        String sender,
                        @JsonProperty("state_key") String stateKey,
                        EventContent content) {
}
