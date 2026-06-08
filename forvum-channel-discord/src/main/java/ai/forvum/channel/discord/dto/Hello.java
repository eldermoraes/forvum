package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code d} payload of an {@code op 10} HELLO frame, the first frame the gateway sends after the
 * connection opens. {@code heartbeat_interval} (milliseconds) is the cadence at which the client must
 * send {@code op 1} HEARTBEAT frames; missing it makes the gateway drop the connection. A real
 * {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record Hello(@JsonProperty("heartbeat_interval") long heartbeatInterval) {
}
