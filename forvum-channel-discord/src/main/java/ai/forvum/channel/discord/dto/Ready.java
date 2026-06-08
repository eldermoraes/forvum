package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code d} payload of the {@code READY} dispatch ({@code op 0}, {@code t == "READY"}), sent once the
 * session is established. Forvum captures:
 *
 * <ul>
 *   <li>{@code session_id} — required to RESUME a dropped session.</li>
 *   <li>{@code resume_gateway_url} — the WebSocket URL to reconnect to when resuming (distinct from the
 *       initial {@code wss://gateway.discord.gg}).</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} (the READY payload also carries the bot user, guild
 * list, etc., none of which Forvum consumes in v0.1).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ready(@JsonProperty("session_id") String sessionId,
                    @JsonProperty("resume_gateway_url") String resumeGatewayUrl) {
}
