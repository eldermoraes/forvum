package ai.forvum.channel.matrix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code content} of a {@link RoomEvent}. Forvum consumes {@code msgtype}/{@code body} from an
 * {@code m.room.message} event (only {@code m.text} drives a turn) and {@code membership} from an
 * {@code m.room.member} stripped event ({@code invite} marks the pending-invite member event). The
 * unused fields of either event kind are simply {@code null}. A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventContent(String msgtype, String body, String membership) {
}
