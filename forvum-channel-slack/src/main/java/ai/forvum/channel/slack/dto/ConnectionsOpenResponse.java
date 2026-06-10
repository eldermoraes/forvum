package ai.forvum.channel.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The response of the Slack Web API {@code POST /apps.connections.open} call (authorized with the
 * app-level {@code xapp-} token): {@code { "ok": true, "url": "wss://..." }} on success,
 * {@code { "ok": false, "error": "invalid_auth" }} on failure. The {@code url} is a TEMPORARY Socket
 * Mode WebSocket URL — minted fresh on every connect, never reused across reconnects. A real
 * {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionsOpenResponse(boolean ok, String url, String error) {
}
