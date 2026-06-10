package ai.forvum.channel.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The response of the Slack Web API {@code POST /chat.postMessage} call. Slack signals failure as
 * {@code { "ok": false, "error": "channel_not_found" }} with HTTP 200 (it does NOT throw at the REST
 * layer), so the caller must check {@code ok} and log the {@code error} string — never let an
 * {@code ok: false} reply take the socket loop down. A real {@code RegisterForReflection}
 * (Quarkus-bearing Layer-3 module) plus {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatPostMessageResponse(boolean ok, String error) {
}
